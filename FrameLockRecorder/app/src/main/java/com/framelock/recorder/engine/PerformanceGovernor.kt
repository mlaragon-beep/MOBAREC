package com.framelock.recorder.engine

import android.app.ActivityManager
import android.content.Context
import android.media.MediaCodec
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import com.framelock.recorder.model.AdaptiveQualityState

/**
 * Monitors device thermal status and memory headroom to protect Mobile Legends frame pacing.
 * If the SoC (e.g. Snapdragon 870) heats up or free RAM tightens during 5v5 team fights,
 * PerformanceGovernor dynamically steps down video encoder bitrate via MediaCodec.setParameters()
 * without restarting the codec or dropping gameplay frames.
 */
class PerformanceGovernor(
    context: Context,
    private val baseBitrateBps: Int,
    private val prioritizeGameplay: Boolean
) {
    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    private val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    private val memInfo = ActivityManager.MemoryInfo()

    @Volatile
    var currentBitrateBps: Int = baseBitrateBps
        private set

    @Volatile
    var currentState: AdaptiveQualityState = AdaptiveQualityState.OPTIMAL
        private set

    fun evaluateAndAdapt(videoCodec: MediaCodec?): AdaptiveQualityState {
        if (!prioritizeGameplay || videoCodec == null) {
            currentState = AdaptiveQualityState.OPTIMAL
            return currentState
        }

        activityManager.getMemoryInfo(memInfo)
        val lowMemory = memInfo.lowMemory || memInfo.availMem < 450L * 1024L * 1024L

        val thermalStatus = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            powerManager.currentThermalStatus
        } else {
            PowerManager.THERMAL_STATUS_NONE
        }

        val (targetRatio, nextState) = when {
            thermalStatus >= PowerManager.THERMAL_STATUS_SEVERE -> 0.55f to AdaptiveQualityState.THERMAL_SCALING
            lowMemory -> 0.65f to AdaptiveQualityState.MEMORY_RELIEF
            thermalStatus >= PowerManager.THERMAL_STATUS_MODERATE -> 0.78f to AdaptiveQualityState.THERMAL_SCALING
            else -> 1.0f to AdaptiveQualityState.OPTIMAL
        }

        val targetBitrate = (baseBitrateBps * targetRatio)
            .toInt()
            .coerceAtLeast(3_000_000)

        if (kotlin.math.abs(targetBitrate - currentBitrateBps) > 750_000) {
            currentBitrateBps = targetBitrate
            try {
                val bundle = Bundle().apply {
                    putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, targetBitrate)
                }
                videoCodec.setParameters(bundle)
            } catch (_: Throwable) {
                // Ignore if codec is transitioning states
            }
        }

        currentState = nextState
        return nextState
    }
}
