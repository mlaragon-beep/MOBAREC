package com.framelock.recorder.engine

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Process
import com.framelock.recorder.model.AudioCaptureMode
import com.framelock.recorder.model.AudioSourceState
import java.io.FileDescriptor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Fault-isolated Dual Audio Capture Engine.
 *
 * Design guarantees:
 * 1. Captures internal game audio via AudioPlaybackCaptureConfiguration (USAGE_GAME + USAGE_MEDIA).
 * 2. Captures microphone via independent AudioRecord + independent AAC MediaCodec.
 * 3. Monitors internal PCM energy (RMS). If Android or Mobile Legends blocks internal capture
 *    (AudioFlinger emits pure zeroes), DualAudioCaptureEngine reports the limitation and
 *    continues recording microphone audio without interrupting the recording session.
 * 4. Supports both dual-track MP4 muxing AND standalone .m4a microphone stem output so mic
 *    audio is always cleanly isolated from game audio.
 */
class DualAudioCaptureEngine(
    private val mode: AudioCaptureMode,
    private val separateMicStemFd: FileDescriptor?,
    private val onStatusUpdate: (AudioSourceState, String?) -> Unit,
    private val onPrimaryAudioFormat: (MediaFormat) -> Int,
    private val onSecondaryMicFormat: (MediaFormat) -> Int,
    private val onMuxPrimarySample: (trackIndex: Int, buffer: ByteBuffer, info: MediaCodec.BufferInfo) -> Unit,
    private val onMuxSecondarySample: (trackIndex: Int, buffer: ByteBuffer, info: MediaCodec.BufferInfo) -> Unit
) {
    companion object {
        private const val SAMPLE_RATE = 48_000
        private const val INTERNAL_BITRATE = 192_000
        private const val MIC_BITRATE = 128_000
        private const val SILENT_BUFFER_THRESHOLD_FRAMES = 90 // ~1.9s at ~21ms/buffer
    }

    @Volatile
    private var running = false

    @Volatile
    private var paused = false

    @Volatile
    var internalPeakDb: Float = -96f
        private set

    @Volatile
    var micPeakDb: Float = -96f
        private set

    private var internalRecord: AudioRecord? = null
    private var micRecord: AudioRecord? = null
    private var internalCodec: MediaCodec? = null
    private var micCodec: MediaCodec? = null
    private var stemMuxer: MediaMuxer? = null
    private var stemAudioTrackIndex: Int = -1
    private var stemMuxerStarted = false

    private var internalThread: Thread? = null
    private var micThread: Thread? = null

    private var internalActive = false
    private var micActive = false
    private var internalBlockedDetected = false
    private var startTimeNs = 0L
    private var pauseOffsetUs = 0L
    private var pauseStartNs = 0L

    @SuppressLint("MissingPermission")
    fun start(mediaProjection: MediaProjection) {
        running = true
        startTimeNs = System.nanoTime()

        val wantsInternal = mode != AudioCaptureMode.MIC_ONLY
        val wantsMic = mode != AudioCaptureMode.INTERNAL_ONLY

        if (wantsInternal) {
            internalActive = initInternalCapture(mediaProjection)
        }

        if (wantsMic) {
            micActive = initMicCapture()
        }

        emitCurrentAudioState(
            if (wantsInternal && !internalActive && micActive) {
                "Internal AudioPlaybackCapture failed to initialize; continuing with Microphone backup."
            } else if (wantsInternal && !internalActive && !micActive) {
                "No audio source could be initialized; continuing video-only recording."
            } else null
        )

        if (internalActive) {
            internalThread = Thread({
                Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
                runCaptureLoop(
                    record = internalRecord!!,
                    codec = internalCodec!!,
                    isInternal = true
                )
            }, "FrameLock-InternalAudio").apply { start() }
        }

        if (micActive) {
            micThread = Thread({
                Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
                runCaptureLoop(
                    record = micRecord!!,
                    codec = micCodec!!,
                    isInternal = false
                )
            }, "FrameLock-MicAudio").apply { start() }
        }
    }

    fun pause() {
        if (!paused) {
            paused = true
            pauseStartNs = System.nanoTime()
        }
    }

    fun resume() {
        if (paused) {
            pauseOffsetUs += ((System.nanoTime() - pauseStartNs) / 1000L).coerceAtLeast(0L)
            paused = false
        }
    }

    fun stop() {
        running = false
        try {
            internalThread?.join(1000L)
        } catch (_: InterruptedException) {
        }
        try {
            micThread?.join(1000L)
        } catch (_: InterruptedException) {
        }
        releaseRecorder(internalRecord)
        releaseRecorder(micRecord)
        releaseCodec(internalCodec)
        releaseCodec(micCodec)
        try {
            if (stemMuxerStarted) stemMuxer?.stop()
            stemMuxer?.release()
        } catch (_: Throwable) {
        }
        internalRecord = null
        micRecord = null
        internalCodec = null
        micCodec = null
        stemMuxer = null
    }

    @SuppressLint("MissingPermission")
    private fun initInternalCapture(mediaProjection: MediaProjection): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        return try {
            val captureConfig = AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .build()

            val format = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                .build()

            val minBuf = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_STEREO,
                AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(4096)

            val record = AudioRecord.Builder()
                .setAudioFormat(format)
                .setBufferSizeInBytes(minBuf * 2)
                .setAudioPlaybackCaptureConfig(captureConfig)
                .build()

            if (record.state != AudioRecord.STATE_INITIALIZED) {
                record.release()
                return false
            }

            val codec = createAacEncoder(channelCount = 2, bitrate = INTERNAL_BITRATE)
            record.startRecording()
            internalRecord = record
            internalCodec = codec
            true
        } catch (_: Throwable) {
            false
        }
    }

    @SuppressLint("MissingPermission")
    private fun initMicCapture(): Boolean {
        return try {
            val minBuf = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(2048)

            val record = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minBuf * 2
            )

            if (record.state != AudioRecord.STATE_INITIALIZED) {
                record.release()
                return false
            }

            val codec = createAacEncoder(channelCount = 1, bitrate = MIC_BITRATE)
            if (separateMicStemFd != null) {
                stemMuxer = MediaMuxer(
                    separateMicStemFd,
                    MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
                )
            }
            record.startRecording()
            micRecord = record
            micCodec = codec
            true
        } catch (_: Throwable) {
            false
        }
    }

    private fun createAacEncoder(channelCount: Int, bitrate: Int): MediaCodec {
        val format = MediaFormat.createAudioFormat(
            MediaFormat.MIMETYPE_AUDIO_AAC,
            SAMPLE_RATE,
            channelCount
        ).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
        }
        return MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply {
            configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            start()
        }
    }

    private fun runCaptureLoop(
        record: AudioRecord,
        codec: MediaCodec,
        isInternal: Boolean
    ) {
        val pcmBytes = ByteArray(4096)
        val outInfo = MediaCodec.BufferInfo()
        var muxTrackIndex = -1
        var consecutiveZeroFrames = 0

        while (running) {
            val readCount = record.read(pcmBytes, 0, pcmBytes.size)
            if (readCount <= 0) continue

            val db = computePeakDb(pcmBytes, readCount)
            if (isInternal) {
                internalPeakDb = db
                if (db <= -95f) {
                    consecutiveZeroFrames++
                    if (consecutiveZeroFrames == SILENT_BUFFER_THRESHOLD_FRAMES && !internalBlockedDetected) {
                        internalBlockedDetected = true
                        emitCurrentAudioState(
                            "Internal audio stream is silent. Android or Mobile Legends may have restricted AudioPlaybackCapture; microphone backup remains active."
                        )
                    }
                } else {
                    consecutiveZeroFrames = 0
                }
            } else {
                micPeakDb = db
            }

            if (paused) continue

            val inIndex = try {
                codec.dequeueInputBuffer(5_000L)
            } catch (_: Throwable) {
                break
            }

            if (inIndex >= 0) {
                val inputBuffer = codec.getInputBuffer(inIndex)
                inputBuffer?.clear()
                inputBuffer?.put(pcmBytes, 0, readCount)
                val ptsUs = ((System.nanoTime() - startTimeNs) / 1000L - pauseOffsetUs)
                    .coerceAtLeast(0L)
                codec.queueInputBuffer(inIndex, 0, readCount, ptsUs, 0)
            }

            while (running) {
                val outIndex = try {
                    codec.dequeueOutputBuffer(outInfo, 0L)
                } catch (_: Throwable) {
                    break
                }
                if (outIndex == MediaCodec.INFO_TRY_AGAIN_LATER) break
                if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val outFmt = codec.outputFormat
                    if (isInternal) {
                        muxTrackIndex = onPrimaryAudioFormat(outFmt)
                    } else if (stemMuxer != null) {
                        stemAudioTrackIndex = stemMuxer!!.addTrack(outFmt)
                        stemMuxer!!.start()
                        stemMuxerStarted = true
                    } else {
                        muxTrackIndex = onSecondaryMicFormat(outFmt)
                    }
                    continue
                }
                if (outIndex >= 0) {
                    val encoded = codec.getOutputBuffer(outIndex)
                    val isConfig = (outInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                    if (encoded != null && outInfo.size > 0 && !isConfig) {
                        encoded.position(outInfo.offset)
                        encoded.limit(outInfo.offset + outInfo.size)
                        if (isInternal && muxTrackIndex >= 0) {
                            onMuxPrimarySample(muxTrackIndex, encoded, outInfo)
                        } else if (!isInternal) {
                            if (stemMuxerStarted && stemAudioTrackIndex >= 0) {
                                stemMuxer?.writeSampleData(stemAudioTrackIndex, encoded, outInfo)
                            } else if (muxTrackIndex >= 0) {
                                onMuxSecondarySample(muxTrackIndex, encoded, outInfo)
                            }
                        }
                    }
                    codec.releaseOutputBuffer(outIndex, false)
                }
            }
        }
    }

    private fun emitCurrentAudioState(notice: String?) {
        val state = when {
            internalActive && micActive && !internalBlockedDetected -> AudioSourceState.ACTIVE_INTERNAL_AND_MIC
            internalActive && micActive && internalBlockedDetected -> AudioSourceState.INTERNAL_BLOCKED_MIC_BACKUP
            !internalActive && micActive && mode != AudioCaptureMode.MIC_ONLY -> AudioSourceState.INTERNAL_FAILED_MIC_BACKUP
            internalActive && !micActive -> AudioSourceState.ACTIVE_INTERNAL_ONLY
            !internalActive && micActive -> AudioSourceState.ACTIVE_MIC_ONLY
            else -> AudioSourceState.SILENT_NO_AUDIO
        }
        onStatusUpdate(state, notice)
    }

    private fun computePeakDb(pcm: ByteArray, length: Int): Float {
        if (length < 2) return -96f
        val shortBuffer = ByteBuffer.wrap(pcm, 0, length).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        var sumSq = 0.0
        val count = shortBuffer.remaining()
        while (shortBuffer.hasRemaining()) {
            val s = shortBuffer.get().toDouble() / 32768.0
            sumSq += s * s
        }
        if (count == 0) return -96f
        val rms = sqrt(sumSq / count)
        return if (rms <= 1e-5) -96f else (20.0 * log10(rms)).toFloat().coerceIn(-96f, 0f)
    }

    private fun releaseRecorder(record: AudioRecord?) {
        try {
            record?.stop()
            record?.release()
        } catch (_: Throwable) {
        }
    }

    private fun releaseCodec(codec: MediaCodec?) {
        try {
            codec?.stop()
            codec?.release()
        } catch (_: Throwable) {
        }
    }
}
