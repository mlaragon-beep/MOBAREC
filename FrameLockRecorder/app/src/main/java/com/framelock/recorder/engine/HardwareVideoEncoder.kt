package com.framelock.recorder.engine

import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.os.Build
import android.view.Surface
import com.framelock.recorder.model.RecordingConfig

/**
 * Zero-copy hardware video encoder pipeline:
 * MediaProjection -> VirtualDisplay -> MediaCodec Input Surface -> Compressed H.264/HEVC frames.
 *
 * Uses KEY_PRIORITY = 1 (non-realtime encoder priority) when prioritizeGameFps is enabled so
 * Adreno GPU scheduling prioritizes Mobile Legends rendering over video encode bursts.
 */
class HardwareVideoEncoder(
    private val width: Int,
    private val height: Int,
    private val densityDpi: Int,
    private val config: RecordingConfig,
    private val onFormatChanged: (MediaFormat) -> Int,
    private val onEncodedSample: (trackIndex: Int, buffer: java.nio.ByteBuffer, info: MediaCodec.BufferInfo) -> Unit
) {
    private var codec: MediaCodec? = null
    private var inputSurface: Surface? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var videoTrackIndex: Int = -1

    @Volatile
    private var running = false

    @Volatile
    private var paused = false

    private var pauseStartedUs: Long = 0L
    private var totalPausedOffsetUs: Long = 0L
    private var drainThread: Thread? = null

    val baseBitrateBps: Int
        get() {
            val fpsScale = when (config.fps.fps) {
                90 -> 1.35f
                60 -> 1.0f
                else -> 0.7f
            }
            val hevcEfficiency = if (config.preferHevc) 0.78f else 1.0f
            return (config.resolution.baseBitrateMbps * 1_000_000 * fpsScale * hevcEfficiency).toInt()
        }

    fun getCodecInstance(): MediaCodec? = codec

    fun start(mediaProjection: MediaProjection) {
        val mimeType = if (config.preferHevc) {
            MediaFormat.MIMETYPE_VIDEO_HEVC
        } else {
            MediaFormat.MIMETYPE_VIDEO_AVC
        }

        val format = MediaFormat.createVideoFormat(mimeType, width, height).apply {
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
            )
            setInteger(MediaFormat.KEY_BIT_RATE, baseBitrateBps)
            setInteger(MediaFormat.KEY_FRAME_RATE, config.fps.fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
            setInteger(
                MediaFormat.KEY_BITRATE_MODE,
                MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR
            )
            // Hint hardware scaler/encoder target operating rate without starving game GPU
            setInteger(MediaFormat.KEY_OPERATING_RATE, config.fps.fps)
            setInteger(
                MediaFormat.KEY_PRIORITY,
                if (config.prioritizeGameFps) 1 else 0
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
            }
        }

        val mediaCodec = MediaCodec.createEncoderByType(mimeType)
        mediaCodec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val surface = mediaCodec.createInputSurface()
        mediaCodec.start()

        val display = mediaProjection.createVirtualDisplay(
            "FrameLock-Capture",
            width,
            height,
            densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            surface,
            null,
            null
        )

        codec = mediaCodec
        inputSurface = surface
        virtualDisplay = display
        running = true

        drainThread = Thread({ drainLoop(mediaCodec) }, "FrameLock-VideoDrain").apply {
            priority = Thread.NORM_PRIORITY
            start()
        }
    }

    fun pause() {
        if (!paused) {
            paused = true
            pauseStartedUs = System.nanoTime() / 1000L
        }
    }

    fun resume() {
        if (paused) {
            val nowUs = System.nanoTime() / 1000L
            totalPausedOffsetUs += (nowUs - pauseStartedUs).coerceAtLeast(0L)
            paused = false
        }
    }

    fun stop() {
        running = false
        try {
            codec?.signalEndOfInputStream()
        } catch (_: Throwable) {
        }
        try {
            drainThread?.join(1500L)
        } catch (_: InterruptedException) {
        }
        try {
            virtualDisplay?.release()
        } catch (_: Throwable) {
        }
        try {
            inputSurface?.release()
        } catch (_: Throwable) {
        }
        try {
            codec?.stop()
            codec?.release()
        } catch (_: Throwable) {
        }
        virtualDisplay = null
        inputSurface = null
        codec = null
        drainThread = null
    }

    private fun drainLoop(mediaCodec: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        while (running) {
            val outIndex = try {
                mediaCodec.dequeueOutputBuffer(info, 10_000L)
            } catch (_: Throwable) {
                break
            }

            when {
                outIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    if (videoTrackIndex < 0) {
                        videoTrackIndex = onFormatChanged(mediaCodec.outputFormat)
                    }
                }
                outIndex >= 0 -> {
                    val encoded = mediaCodec.getOutputBuffer(outIndex)
                    val isConfig = (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                    if (encoded != null && info.size > 0 && !isConfig && !paused && videoTrackIndex >= 0) {
                        encoded.position(info.offset)
                        encoded.limit(info.offset + info.size)
                        info.presentationTimeUs = (info.presentationTimeUs - totalPausedOffsetUs)
                            .coerceAtLeast(0L)
                        onEncodedSample(videoTrackIndex, encoded, info)
                    }
                    mediaCodec.releaseOutputBuffer(outIndex, false)
                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        break
                    }
                }
            }
        }
    }
}
