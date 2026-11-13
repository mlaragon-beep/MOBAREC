package com.framelock.recorder.model

enum class ResolutionPreset(
    val label: String,
    val shortEdge: Int,
    val baseBitrateMbps: Int
) {
    HD_720P("720p", 720, 8),
    FHD_1080P("1080p", 1080, 14),
    QHD_1440P("1440p", 1440, 24)
}

enum class FpsPreset(val fps: Int, val label: String) {
    FPS_30(30, "30 FPS"),
    FPS_60(60, "60 FPS"),
    FPS_90(90, "90 FPS")
}

enum class AudioCaptureMode(val title: String, val subtitle: String) {
    INTERNAL_AND_SEPARATE_MIC(
        title = "Internal + Separate Mic Track",
        subtitle = "Captures MLBB game audio + microphone on isolated tracks"
    ),
    INTERNAL_ONLY(
        title = "Internal Game Audio Only",
        subtitle = "Captures AudioPlaybackCapture (USAGE_GAME) without mic"
    ),
    MIC_ONLY(
        title = "Microphone Only",
        subtitle = "Bypasses internal audio capture; lowest overhead"
    )
}

enum class AudioSourceState(val label: String) {
    IDLE("Standby"),
    ACTIVE_INTERNAL_AND_MIC("Game Audio + Mic Active"),
    ACTIVE_INTERNAL_ONLY("Game Audio Active"),
    ACTIVE_MIC_ONLY("Mic Active"),
    INTERNAL_BLOCKED_MIC_BACKUP("Internal Blocked · Mic Backup Active"),
    INTERNAL_FAILED_MIC_BACKUP("Internal Error · Mic Backup Active"),
    SILENT_NO_AUDIO("Video Only (Audio Unavailable)")
}

enum class RecordingPhase {
    IDLE,
    RECORDING,
    PAUSED,
    FINALIZING
}

enum class AdaptiveQualityState(val label: String) {
    OPTIMAL("Locked Target Quality"),
    THERMAL_SCALING("Thermal Bitrate Step-Down"),
    MEMORY_RELIEF("Low-Memory Bitrate Relief")
}

data class RecordingConfig(
    val resolution: ResolutionPreset = ResolutionPreset.FHD_1080P,
    val fps: FpsPreset = FpsPreset.FPS_60,
    val audioMode: AudioCaptureMode = AudioCaptureMode.INTERNAL_AND_SEPARATE_MIC,
    val separateMicStemFile: Boolean = true,
    val preferHevc: Boolean = false,
    val prioritizeGameFps: Boolean = true
)

data class DeviceCapabilities(
    val deviceModel: String,
    val osVersion: String,
    val isXiaomiHyperOS: Boolean,
    val hyperOsVersion: String,
    val nativeWidth: Int,
    val nativeHeight: Int,
    val displayRefreshRates: List<Int>,
    val supportedResolutions: List<ResolutionPreset>,
    val supportedFps: List<FpsPreset>,
    val hardwareAvcEncoderName: String?,
    val hardwareHevcEncoderName: String?,
    val mlbbInstalled: Boolean,
    val mlbbAllowsPlaybackCapture: Boolean?,
    val availableStorageBytes: Long
)

data class RecordingTelemetry(
    val phase: RecordingPhase = RecordingPhase.IDLE,
    val elapsedMillis: Long = 0L,
    val audioState: AudioSourceState = AudioSourceState.IDLE,
    val adaptiveState: AdaptiveQualityState = AdaptiveQualityState.OPTIMAL,
    val currentBitrateMbps: Float = 0f,
    val estimatedSizeBytes: Long = 0L,
    val availableStorageBytes: Long = 0L,
    val internalAudioPeakDb: Float = -96f,
    val micAudioPeakDb: Float = -96f,
    val limitationNotice: String? = null,
    val outputFileName: String? = null,
    val micStemFileName: String? = null
)
