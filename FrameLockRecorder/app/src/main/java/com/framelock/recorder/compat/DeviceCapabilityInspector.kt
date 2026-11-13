package com.framelock.recorder.compat

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.util.DisplayMetrics
import android.view.Display
import android.view.WindowManager
import com.framelock.recorder.model.DeviceCapabilities
import com.framelock.recorder.model.FpsPreset
import com.framelock.recorder.model.ResolutionPreset
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

object DeviceCapabilityInspector {

    private const val MLBB_PACKAGE = "com.mobile.legends"
    private const val MLBB_HWAG_PACKAGE = "com.mobile.legends.hwag"

    fun inspect(context: Context): DeviceCapabilities {
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val defaultDisplay = displayManager.getDisplay(Display.DEFAULT_DISPLAY)

        val (nativeW, nativeH) = readPhysicalDisplaySize(windowManager, defaultDisplay)
        val refreshRates = readSupportedRefreshRates(defaultDisplay)

        val avcInfo = findHardwareEncoder(MediaFormat.MIMETYPE_VIDEO_AVC)
        val hevcInfo = findHardwareEncoder(MediaFormat.MIMETYPE_VIDEO_HEVC)

        val supportedResolutions = computeSupportedResolutions(nativeW, nativeH, avcInfo)
        val supportedFps = computeSupportedFps(refreshRates, avcInfo)
        val (mlbbInstalled, mlbbCaptureAllowed) = inspectMlbbCapturePolicy(context)

        return DeviceCapabilities(
            deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}".trim(),
            osVersion = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            isXiaomiHyperOS = XiaomiHyperOSHelper.isXiaomiDevice(),
            hyperOsVersion = XiaomiHyperOSHelper.getSystemUiVersionLabel(),
            nativeWidth = nativeW,
            nativeHeight = nativeH,
            displayRefreshRates = refreshRates,
            supportedResolutions = supportedResolutions,
            supportedFps = supportedFps,
            hardwareAvcEncoderName = avcInfo?.name,
            hardwareHevcEncoderName = hevcInfo?.name,
            mlbbInstalled = mlbbInstalled,
            mlbbAllowsPlaybackCapture = mlbbCaptureAllowed,
            availableStorageBytes = queryAvailableStorageBytes()
        )
    }

    /**
     * Computes exact even-aligned landscape dimensions matching the target device's aspect ratio.
     * Example on Xiaomi Pad 6 (2880x1800, 16:10):
     * - 720p -> 1152 x 720
     * - 1080p -> 1728 x 1080
     * - 1440p -> 2304 x 1440
     */
    fun computeOutputDimensions(
        nativeWidth: Int,
        nativeHeight: Int,
        preset: ResolutionPreset
    ): Pair<Int, Int> {
        val longNative = max(nativeWidth, nativeHeight).coerceAtLeast(1280)
        val shortNative = min(nativeWidth, nativeHeight).coerceAtLeast(720)
        val aspect = longNative.toFloat() / shortNative.toFloat()

        val targetShort = min(preset.shortEdge, shortNative)
        val rawLong = (targetShort * aspect).roundToInt()
        // H.264 / HEVC hardware encoders require macroblock-aligned (16px or 2px even) widths/heights
        val alignedShort = (targetShort / 2) * 2
        val alignedLong = ((rawLong + 8) / 16) * 16
        return Pair(alignedLong, alignedShort)
    }

    fun queryAvailableStorageBytes(): Long {
        return try {
            val path = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
            val stat = StatFs(path.path)
            stat.availableBytes
        } catch (_: Throwable) {
            0L
        }
    }

    private fun readPhysicalDisplaySize(
        windowManager: WindowManager,
        display: Display?
    ): Pair<Int, Int> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.maximumWindowMetrics.bounds
            return Pair(bounds.width(), bounds.height())
        }
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        display?.getRealMetrics(metrics)
        return Pair(metrics.widthPixels.coerceAtLeast(1280), metrics.heightPixels.coerceAtLeast(720))
    }

    private fun readSupportedRefreshRates(display: Display?): List<Int> {
        if (display == null) return listOf(60)
        return display.supportedModes
            .map { it.refreshRate.roundToInt() }
            .distinct()
            .sorted()
    }

    private fun findHardwareEncoder(mimeType: String): MediaCodecInfo? {
        val list = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        return list.codecInfos.firstOrNull { info ->
            info.isEncoder &&
                isHardwareAccelerated(info) &&
                info.supportedTypes.any { it.equals(mimeType, ignoreCase = true) }
        }
    }

    private fun isHardwareAccelerated(info: MediaCodecInfo): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return info.isHardwareAccelerated
        }
        val name = info.name.lowercase()
        return !name.startsWith("omx.google.") && !name.startsWith("c2.android.")
    }

    private fun computeSupportedResolutions(
        nativeW: Int,
        nativeH: Int,
        avcInfo: MediaCodecInfo?
    ): List<ResolutionPreset> {
        val shortEdge = min(nativeW, nativeH)
        val videoCaps = avcInfo
            ?.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC)
            ?.videoCapabilities

        return ResolutionPreset.entries.filter { preset ->
            if (preset.shortEdge > shortEdge) return@filter false
            if (videoCaps == null) return@filter true
            val (w, h) = computeOutputDimensions(nativeW, nativeH, preset)
            videoCaps.isSizeSupported(w, h)
        }.ifEmpty { listOf(ResolutionPreset.HD_720P) }
    }

    private fun computeSupportedFps(
        refreshRates: List<Int>,
        avcInfo: MediaCodecInfo?
    ): List<FpsPreset> {
        val maxRefresh = refreshRates.maxOrNull() ?: 60
        val videoCaps = avcInfo
            ?.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC)
            ?.videoCapabilities

        return FpsPreset.entries.filter { preset ->
            val refreshOk = maxRefresh >= preset.fps - 2
            val encoderOk = videoCaps?.supportedFrameRates?.upper?.let { it >= preset.fps } ?: true
            refreshOk && encoderOk
        }.ifEmpty { listOf(FpsPreset.FPS_30, FpsPreset.FPS_60) }
    }

    private fun inspectMlbbCapturePolicy(context: Context): Pair<Boolean, Boolean?> {
        val pm = context.packageManager
        val appInfo = getPackageInfoCompat(pm, MLBB_PACKAGE)
            ?: getPackageInfoCompat(pm, MLBB_HWAG_PACKAGE)
            ?: return Pair(false, null)

        val allowed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Public ApplicationInfo.isAudioPlaybackCaptureAllowed flag exists on API 29+
            appInfo.flags and ApplicationInfo.FLAG_ALLOW_CLEAR_USER_DATA >= 0 &&
                checkAudioCaptureFlag(appInfo)
        } else {
            false
        }
        return Pair(true, allowed)
    }

    private fun checkAudioCaptureFlag(appInfo: ApplicationInfo): Boolean {
        return try {
            // Note: Even if manifest sets allowAudioPlaybackCapture="true" (default on targetSdk >= 29),
            // apps can restrict audio per-stream via AudioAttributes.setAllowedCapturePolicy(ALLOW_CAPTURE_BY_NONE).
            val method = ApplicationInfo::class.java.getMethod("isAudioPlaybackCaptureAllowed")
            method.invoke(appInfo) as? Boolean ?: true
        } catch (_: Throwable) {
            true
        }
    }

    private fun getPackageInfoCompat(pm: PackageManager, pkg: String): ApplicationInfo? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getApplicationInfo(pkg, PackageManager.ApplicationInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.getApplicationInfo(pkg, 0)
            }
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }
    }
}
