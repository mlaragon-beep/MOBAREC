package com.framelock.recorder.compat

import android.annotation.SuppressLint
import android.app.AppOpsManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.provider.Settings

/**
 * Compatibility utilities for Xiaomi HyperOS and MIUI (including Xiaomi Pad 6 Android 14).
 *
 * HyperOS enforces stricter background execution and overlay rules than AOSP:
 * 1. OP_BACKGROUND_START_ACTIVITY (10021) blocks starting services/overlays from background.
 * 2. Aggressive MIUI Battery Saver pauses MediaCodec threads when screen touches shift to MLBB.
 * 3. Game Turbo memory cleaner can terminate foreground services unless FrameLock is whitelisted.
 */
object XiaomiHyperOSHelper {

    private const val OP_BACKGROUND_START_ACTIVITY = 10021

    fun isXiaomiDevice(): Boolean {
        val manufacturer = Build.MANUFACTURER.lowercase()
        val brand = Build.BRAND.lowercase()
        return manufacturer.contains("xiaomi") ||
            brand.contains("xiaomi") ||
            brand.contains("redmi") ||
            brand.contains("poco")
    }

    fun getSystemUiVersionLabel(): String {
        val osName = getSystemProperty("ro.mi.os.version.name")
        if (!osName.isNullOrBlank()) return "HyperOS $osName"
        val miuiName = getSystemProperty("ro.miui.ui.version.name")
        if (!miuiName.isNullOrBlank()) return "MIUI $miuiName"
        return if (isXiaomiDevice()) "Xiaomi OS" else "AOSP / OEM"
    }

    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    /**
     * Checks Xiaomi's hidden AppOps permission for background pop-up windows (10021).
     * Returns true on non-Xiaomi devices or when explicitly granted.
     */
    fun isBackgroundPopupAllowed(context: Context): Boolean {
        if (!isXiaomiDevice()) return true
        return try {
            val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
            val method = AppOpsManager::class.java.getMethod(
                "checkOpNoThrow",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                String::class.java
            )
            val mode = method.invoke(
                appOps,
                OP_BACKGROUND_START_ACTIVITY,
                Process.myUid(),
                context.packageName
            ) as Int
            mode == AppOpsManager.MODE_ALLOWED
        } catch (_: Throwable) {
            true
        }
    }

    fun openXiaomiPermissionEditor(context: Context) {
        val candidates = listOf(
            Intent("miui.intent.action.APP_PERM_EDITOR").apply {
                setClassName(
                    "com.miui.securitycenter",
                    "com.miui.permcenter.permissions.PermissionsEditorActivity"
                )
                putExtra("extra_pkgname", context.packageName)
            },
            Intent("miui.intent.action.APP_PERM_EDITOR").apply {
                putExtra("extra_pkgname", context.packageName)
            },
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", context.packageName, null)
            }
        )
        launchFirstAvailable(context, candidates)
    }

    fun openBatteryOptimizationSettings(context: Context) {
        val candidates = listOf(
            Intent().apply {
                component = ComponentName(
                    "com.miui.powerkeeper",
                    "com.miui.powerkeeper.ui.HiddenAppsConfigActivity"
                )
                putExtra("package_name", context.packageName)
                putExtra("package_label", "FrameLock Recorder")
            },
            @SuppressLint("BatteryLife")
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:${context.packageName}")
            },
            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        )
        launchFirstAvailable(context, candidates)
    }

    fun openOverlayPermissionSettings(context: Context) {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${context.packageName}")
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    private fun launchFirstAvailable(context: Context, intents: List<Intent>) {
        for (intent in intents) {
            try {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                return
            } catch (_: Throwable) {
                // Try next fallback intent
            }
        }
    }

    @SuppressLint("PrivateApi")
    private fun getSystemProperty(key: String): String? {
        return try {
            val clazz = Class.forName("android.os.SystemProperties")
            val method = clazz.getMethod("get", String::class.java)
            (method.invoke(null, key) as? String)?.takeIf { it.isNotBlank() }
        } catch (_: Throwable) {
            null
        }
    }
}
