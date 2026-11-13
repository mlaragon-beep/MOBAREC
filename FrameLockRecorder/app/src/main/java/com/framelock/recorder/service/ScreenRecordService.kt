package com.framelock.recorder.service

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import com.framelock.recorder.compat.DeviceCapabilityInspector
import com.framelock.recorder.engine.DualAudioCaptureEngine
import com.framelock.recorder.engine.HardwareVideoEncoder
import com.framelock.recorder.engine.PerformanceGovernor
import com.framelock.recorder.model.AudioCaptureMode
import com.framelock.recorder.model.AudioSourceState
import com.framelock.recorder.model.FpsPreset
import com.framelock.recorder.model.RecordingConfig
import com.framelock.recorder.model.RecordingPhase
import com.framelock.recorder.model.RecordingTelemetry
import com.framelock.recorder.model.ResolutionPreset
import com.framelock.recorder.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ScreenRecordService : Service() {

    companion object {
        const val ACTION_START = "com.framelock.recorder.ACTION_START"
        const val ACTION_PAUSE = "com.framelock.recorder.ACTION_PAUSE"
        const val ACTION_RESUME = "com.framelock.recorder.ACTION_RESUME"
        const val ACTION_STOP = "com.framelock.recorder.ACTION_STOP"

        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"
        const val EXTRA_RESOLUTION = "extra_resolution"
        const val EXTRA_FPS = "extra_fps"
        const val EXTRA_AUDIO_MODE = "extra_audio_mode"
        const val EXTRA_SEPARATE_MIC_STEM = "extra_separate_mic_stem"
        const val EXTRA_PREFER_HEVC = "extra_prefer_hevc"
        const val EXTRA_PRIORITIZE_GAME = "extra_prioritize_game"

        private const val CHANNEL_ID = "framelock_recording_channel"
        private const val NOTIFICATION_ID = 4091

        private val _telemetry = MutableStateFlow(RecordingTelemetry())
        val telemetry: StateFlow<RecordingTelemetry> = _telemetry.asStateFlow()
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var telemetryJob: Job? = null

    private var mediaProjection: MediaProjection? = null
    private var videoEncoder: HardwareVideoEncoder? = null
    private var audioEngine: DualAudioCaptureEngine? = null
    private var governor: PerformanceGovernor? = null

    private var muxer: MediaMuxer? = null
    private val muxerLock = Any()
    private var muxerStarted = false
    private var expectedAudioTracks = 0
    private var registeredVideoTrack = -1
    private var registeredAudioTracks = 0
    private var bytesWritten = 0L

    private var videoOutputUri: Uri? = null
    private var videoPfd: ParcelFileDescriptor? = null
    private var micStemUri: Uri? = null
    private var micStemPfd: ParcelFileDescriptor? = null

    private var activeConfig = RecordingConfig()
    private var sessionStartMs = 0L
    private var pauseStartMs = 0L
    private var accumulatedPausedMs = 0L

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            stopRecordingSession()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
                val resultData = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_RESULT_DATA)
                }
                if (resultCode == Activity.RESULT_OK && resultData != null) {
                    activeConfig = parseConfig(intent)
                    startRecordingSession(resultCode, resultData)
                } else {
                    stopSelf()
                }
            }
            ACTION_PAUSE -> pauseRecordingSession()
            ACTION_RESUME -> resumeRecordingSession()
            ACTION_STOP -> stopRecordingSession()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopRecordingSession()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun parseConfig(intent: Intent): RecordingConfig {
        val resName = intent.getStringExtra(EXTRA_RESOLUTION)
        val fpsName = intent.getStringExtra(EXTRA_FPS)
        val audioName = intent.getStringExtra(EXTRA_AUDIO_MODE)
        return RecordingConfig(
            resolution = ResolutionPreset.entries.firstOrNull { it.name == resName }
                ?: ResolutionPreset.FHD_1080P,
            fps = FpsPreset.entries.firstOrNull { it.name == fpsName }
                ?: FpsPreset.FPS_60,
            audioMode = AudioCaptureMode.entries.firstOrNull { it.name == audioName }
                ?: AudioCaptureMode.INTERNAL_AND_SEPARATE_MIC,
            separateMicStemFile = intent.getBooleanExtra(EXTRA_SEPARATE_MIC_STEM, true),
            preferHevc = intent.getBooleanExtra(EXTRA_PREFER_HEVC, false),
            prioritizeGameFps = intent.getBooleanExtra(EXTRA_PRIORITIZE_GAME, true)
        )
    }

    private fun startRecordingSession(resultCode: Int, resultData: Intent) {
        ensureNotificationChannel()
        val notification = buildNotification("Recording Mobile Legends gameplay", isPaused = false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val fgType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            } else {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            }
            startForeground(NOTIFICATION_ID, notification, fgType)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        val projectionMgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val projection = projectionMgr.getMediaProjection(resultCode, resultData) ?: run {
    stopForeground(STOP_FOREGROUND_REMOVE)
    stopSelf()
    return
        projection.registerCallback(projectionCallback, Handler(Looper.getMainLooper()))
        mediaProjection = projection

        val caps = DeviceCapabilityInspector.inspect(this)
        val (outW, outH) = DeviceCapabilityInspector.computeOutputDimensions(
            caps.nativeWidth,
            caps.nativeHeight,
            activeConfig.resolution
        )
        val densityDpi = resources.displayMetrics.densityDpi

        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val baseName = "MLBB_${activeConfig.resolution.label}_${activeConfig.fps.fps}fps_$stamp"

        val createdVideo = createMediaStoreVideo("$baseName.mp4") ?: run {
            stopSelf()
            return
        }
        videoOutputUri = createdVideo.first
        videoPfd = createdVideo.second
        muxer = MediaMuxer(createdVideo.second.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

        val wantsSeparateStem = activeConfig.audioMode == AudioCaptureMode.INTERNAL_AND_SEPARATE_MIC &&
            activeConfig.separateMicStemFile
        if (wantsSeparateStem) {
            createMediaStoreAudioStem("${baseName}_mic.m4a")?.let { (uri, pfd) ->
                micStemUri = uri
                micStemPfd = pfd
            }
        }

        expectedAudioTracks = when {
            activeConfig.audioMode == AudioCaptureMode.INTERNAL_AND_SEPARATE_MIC && micStemPfd == null -> 2
            else -> 1
        }
        registeredVideoTrack = -1
        registeredAudioTracks = 0
        muxerStarted = false
        bytesWritten = 0L

        val encoder = HardwareVideoEncoder(
            width = outW,
            height = outH,
            densityDpi = densityDpi,
            config = activeConfig,
            onFormatChanged = { format -> registerTrack(format, isVideo = true) },
            onEncodedSample = { trackIdx, buf, info -> writeSampleToMuxer(trackIdx, buf, info) }
        )
        videoEncoder = encoder

        governor = PerformanceGovernor(
            context = this,
            baseBitrateBps = encoder.baseBitrateBps,
            prioritizeGameplay = activeConfig.prioritizeGameFps
        )

        val audio = DualAudioCaptureEngine(
            mode = activeConfig.audioMode,
            separateMicStemFd = micStemPfd?.fileDescriptor,
            onStatusUpdate = { state, notice ->
                _telemetry.update { it.copy(audioState = state, limitationNotice = notice) }
            },
            onPrimaryAudioFormat = { format -> registerTrack(format, isVideo = false) },
            onSecondaryMicFormat = { format -> registerTrack(format, isVideo = false) },
            onMuxPrimarySample = { trackIdx, buf, info -> writeSampleToMuxer(trackIdx, buf, info) },
            onMuxSecondarySample = { trackIdx, buf, info -> writeSampleToMuxer(trackIdx, buf, info) }
        )
        audioEngine = audio

        sessionStartMs = System.currentTimeMillis()
        accumulatedPausedMs = 0L
        pauseStartMs = 0L

        encoder.start(projection)
        audio.start(projection)

        _telemetry.value = RecordingTelemetry(
            phase = RecordingPhase.RECORDING,
            elapsedMillis = 0L,
            audioState = AudioSourceState.IDLE,
            currentBitrateMbps = encoder.baseBitrateBps / 1_000_000f,
            availableStorageBytes = caps.availableStorageBytes,
            outputFileName = "$baseName.mp4",
            micStemFileName = if (micStemPfd != null) "${baseName}_mic.m4a" else null
        )

        startFloatingOverlay()
        startTelemetryLoop()
    }

    private fun pauseRecordingSession() {
        if (_telemetry.value.phase != RecordingPhase.RECORDING) return
        pauseStartMs = System.currentTimeMillis()
        videoEncoder?.pause()
        audioEngine?.pause()
        _telemetry.update { it.copy(phase = RecordingPhase.PAUSED) }
        updateNotification("Paused · Mobile Legends recording", isPaused = true)
    }

    private fun resumeRecordingSession() {
        if (_telemetry.value.phase != RecordingPhase.PAUSED) return
        accumulatedPausedMs += (System.currentTimeMillis() - pauseStartMs).coerceAtLeast(0L)
        videoEncoder?.resume()
        audioEngine?.resume()
        _telemetry.update { it.copy(phase = RecordingPhase.RECORDING) }
        updateNotification("Recording Mobile Legends gameplay", isPaused = false)
    }

    private fun stopRecordingSession() {
        if (_telemetry.value.phase == RecordingPhase.IDLE) return
        _telemetry.update { it.copy(phase = RecordingPhase.FINALIZING) }
        telemetryJob?.cancel()

        videoEncoder?.stop()
        audioEngine?.stop()

        synchronized(muxerLock) {
            try {
                if (muxerStarted) muxer?.stop()
                muxer?.release()
            } catch (_: Throwable) {
            }
            muxer = null
            muxerStarted = false
        }

        try {
            mediaProjection?.unregisterCallback(projectionCallback)
            mediaProjection?.stop()
        } catch (_: Throwable) {
        }
        mediaProjection = null

        finalizeMediaStoreFile(videoOutputUri, videoPfd, isVideo = true)
        finalizeMediaStoreFile(micStemUri, micStemPfd, isVideo = false)
        videoOutputUri = null
        videoPfd = null
        micStemUri = null
        micStemPfd = null

        stopService(Intent(this, FloatingOverlayService::class.java))
        _telemetry.update { it.copy(phase = RecordingPhase.IDLE) }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun registerTrack(format: MediaFormat, isVideo: Boolean): Int {
        synchronized(muxerLock) {
            val m = muxer ?: return -1
            val index = m.addTrack(format)
            if (isVideo) {
                registeredVideoTrack = index
            } else {
                registeredAudioTracks++
            }
            // Start muxer once video + at least 1 audio track (or after 400ms grace) are ready
            if (!muxerStarted && registeredVideoTrack >= 0 && registeredAudioTracks >= 1) {
                m.start()
                muxerStarted = true
            }
            return index
        }
    }

    private fun writeSampleToMuxer(trackIndex: Int, buffer: ByteBuffer, info: MediaCodec.BufferInfo) {
        synchronized(muxerLock) {
            val m = muxer ?: return
            if (!muxerStarted) {
                // Fallback: if audio failed completely, start muxer on first video keyframe
                if (trackIndex == registeredVideoTrack &&
                    (info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0
                ) {
                    m.start()
                    muxerStarted = true
                } else {
                    return
                }
            }
            try {
                m.writeSampleData(trackIndex, buffer, info)
                bytesWritten += info.size
            } catch (_: Throwable) {
            }
        }
    }

    private fun startTelemetryLoop() {
        telemetryJob?.cancel()
        telemetryJob = serviceScope.launch {
            while (isActive) {
                delay(500L)
                val state = _telemetry.value
                val elapsed = if (state.phase == RecordingPhase.RECORDING) {
                    (System.currentTimeMillis() - sessionStartMs - accumulatedPausedMs).coerceAtLeast(0L)
                } else {
                    state.elapsedMillis
                }
                val adaptive = governor?.evaluateAndAdapt(videoEncoder?.getCodecInstance())
                    ?: state.adaptiveState
                val bitrateMbps = (governor?.currentBitrateBps ?: 0) / 1_000_000f

                _telemetry.update {
                    it.copy(
                        elapsedMillis = elapsed,
                        adaptiveState = adaptive,
                        currentBitrateMbps = bitrateMbps,
                        estimatedSizeBytes = bytesWritten,
                        availableStorageBytes = DeviceCapabilityInspector.queryAvailableStorageBytes(),
                        internalAudioPeakDb = audioEngine?.internalPeakDb ?: -96f,
                        micAudioPeakDb = audioEngine?.micPeakDb ?: -96f
                    )
                }
            }
        }
    }

    private fun startFloatingOverlay() {
        val intent = Intent(this, FloatingOverlayService::class.java)
        startService(intent)
    }

    private fun createMediaStoreVideo(fileName: String): Pair<Uri, ParcelFileDescriptor>? {
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/FrameLock")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
        }
        val uri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: return null
        val pfd = contentResolver.openFileDescriptor(uri, "rw") ?: return null
        return Pair(uri, pfd)
    }

    private fun createMediaStoreAudioStem(fileName: String): Pair<Uri, ParcelFileDescriptor>? {
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Audio.Media.MIME_TYPE, "audio/mp4")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Audio.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MUSIC}/FrameLock")
                put(MediaStore.Audio.Media.IS_PENDING, 1)
            }
        }
        val uri = contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values) ?: return null
        val pfd = contentResolver.openFileDescriptor(uri, "rw") ?: return null
        return Pair(uri, pfd)
    }

    private fun finalizeMediaStoreFile(uri: Uri?, pfd: ParcelFileDescriptor?, isVideo: Boolean) {
        try {
            pfd?.close()
        } catch (_: Throwable) {
        }
        if (uri != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                if (isVideo) {
                    put(MediaStore.Video.Media.IS_PENDING, 0)
                } else {
                    put(MediaStore.Audio.Media.IS_PENDING, 0)
                }
            }
            contentResolver.update(uri, values, null, null)
        }
    }

    private fun ensureNotificationChannel() {
        val mgr = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Gameplay Recording",
            NotificationManager.IMPORTANCE_LOW
        )
        mgr.createNotificationChannel(channel)
    }

    private fun updateNotification(content: String, isPaused: Boolean) {
        val mgr = getSystemService(NotificationManager::class.java)
        mgr.notify(NOTIFICATION_ID, buildNotification(content, isPaused))
    }

    private fun buildNotification(content: String, isPaused: Boolean): Notification {
        val openAppIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val toggleIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, ScreenRecordService::class.java).apply {
                action = if (isPaused) ACTION_RESUME else ACTION_PAUSE
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopIntent = PendingIntent.getService(
            this,
            2,
            Intent(this, ScreenRecordService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setContentTitle("FrameLock Recorder")
            .setContentText(content)
            .setOngoing(true)
            .setContentIntent(openAppIntent)
            .addAction(0, if (isPaused) "Resume" else "Pause", toggleIntent)
            .addAction(0, "Stop", stopIntent)
            .build()
    }
}
