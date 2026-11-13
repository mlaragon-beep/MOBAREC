package com.framelock.recorder.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.framelock.recorder.compat.DeviceCapabilityInspector
import com.framelock.recorder.compat.WebViewIsolationGuard
import com.framelock.recorder.compat.XiaomiHyperOSHelper
import com.framelock.recorder.model.AudioCaptureMode
import com.framelock.recorder.model.FpsPreset
import com.framelock.recorder.model.RecordingConfig
import com.framelock.recorder.model.RecordingPhase
import com.framelock.recorder.model.ResolutionPreset
import com.framelock.recorder.service.ScreenRecordService
import com.framelock.recorder.ui.theme.FrameLockTheme
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            FrameLockTheme {
                RecorderDashboard()
            }
        }
    }
}

@Composable
private fun RecorderDashboard() {
    val context = LocalContext.current
    val caps = remember { DeviceCapabilityInspector.inspect(context) }
    val telemetry by ScreenRecordService.telemetry.collectAsState()

    var config by remember {
        mutableStateOf(
            RecordingConfig(
                resolution = if (ResolutionPreset.FHD_1080P in caps.supportedResolutions) {
                    ResolutionPreset.FHD_1080P
                } else caps.supportedResolutions.first(),
                fps = if (FpsPreset.FPS_60 in caps.supportedFps) {
                    FpsPreset.FPS_60
                } else caps.supportedFps.first()
            )
        )
    }

    var hasAudioPerm by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    var hasOverlayPerm by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    var hyperOsBatteryOk by remember { mutableStateOf(XiaomiHyperOSHelper.isIgnoringBatteryOptimizations(context)) }
    var hyperOsPopupOk by remember { mutableStateOf(XiaomiHyperOSHelper.isBackgroundPopupAllowed(context)) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        hasAudioPerm = grants[Manifest.permission.RECORD_AUDIO] ?: hasAudioPerm
    }

    val projectionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val intent = Intent(context, ScreenRecordService::class.java).apply {
                action = ScreenRecordService.ACTION_START
                putExtra(ScreenRecordService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(ScreenRecordService.EXTRA_RESULT_DATA, result.data)
                putExtra(ScreenRecordService.EXTRA_RESOLUTION, config.resolution.name)
                putExtra(ScreenRecordService.EXTRA_FPS, config.fps.name)
                putExtra(ScreenRecordService.EXTRA_AUDIO_MODE, config.audioMode.name)
                putExtra(ScreenRecordService.EXTRA_SEPARATE_MIC_STEM, config.separateMicStemFile)
                putExtra(ScreenRecordService.EXTRA_PREFER_HEVC, config.preferHevc)
                putExtra(ScreenRecordService.EXTRA_PRIORITIZE_GAME, config.prioritizeGameFps)
            }
            ContextCompat.startForegroundService(context, intent)
        }
    }

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "FrameLock Recorder",
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Text(
                        text = "${caps.deviceModel} · ${caps.osVersion} · ${caps.hyperOsVersion}",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    text = if (WebViewIsolationGuard.verifyWebViewNotLoadedInProcess()) {
                        "Native Isolated"
                    } else "WebView Detected",
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            // Live Session Card
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    val isIdle = telemetry.phase == RecordingPhase.IDLE
                    val isPaused = telemetry.phase == RecordingPhase.PAUSED
                    val (outW, outH) = DeviceCapabilityInspector.computeOutputDimensions(
                        caps.nativeWidth,
                        caps.nativeHeight,
                        config.resolution
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = when (telemetry.phase) {
                                    RecordingPhase.IDLE -> "Ready for Mobile Legends"
                                    RecordingPhase.RECORDING -> "Recording Gameplay"
                                    RecordingPhase.PAUSED -> "Recording Paused"
                                    RecordingPhase.FINALIZING -> "Finalizing MP4..."
                                },
                                fontSize = 16.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = "Output ${outW}x${outH} · ${config.fps.label} · ${telemetry.audioState.label}",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Text(
                            text = formatDuration(telemetry.elapsedMillis),
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                    }

                    telemetry.limitationNotice?.let { notice ->
                        Text(
                            text = notice,
                            fontSize = 12.sp,
                            color = Color(0xFFF59E0B)
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        if (isIdle) {
                            Button(
                                modifier = Modifier.weight(1f),
                                onClick = {
                                    val needed = buildList {
                                        if (!hasAudioPerm) add(Manifest.permission.RECORD_AUDIO)
                                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                            add(Manifest.permission.POST_NOTIFICATIONS)
                                        }
                                    }
                                    if (needed.isNotEmpty()) {
                                        permissionLauncher.launch(needed.toTypedArray())
                                    } else {
                                        val mpm = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE)
                                            as MediaProjectionManager
                                        projectionLauncher.launch(mpm.createScreenCaptureIntent())
                                    }
                                }
                            ) {
                                Text("Start Recording")
                            }
                        } else {
                            OutlinedButton(
                                modifier = Modifier.weight(1f),
                                onClick = {
                                    val action = if (isPaused) {
                                        ScreenRecordService.ACTION_RESUME
                                    } else {
                                        ScreenRecordService.ACTION_PAUSE
                                    }
                                    context.startService(
                                        Intent(context, ScreenRecordService::class.java).apply {
                                            this.action = action
                                        }
                                    )
                                }
                            ) {
                                Text(if (isPaused) "Resume" else "Pause")
                            }
                            Button(
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDC2626)),
                                onClick = {
                                    context.startService(
                                        Intent(context, ScreenRecordService::class.java).apply {
                                            action = ScreenRecordService.ACTION_STOP
                                        }
                                    )
                                }
                            ) {
                                Text("Stop & Save", color = Color.White)
                            }
                        }
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outline)

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        MetricCell("Free Storage", formatBytes(caps.availableStorageBytes))
                        MetricCell("Clip Size", formatBytes(telemetry.estimatedSizeBytes))
                        MetricCell("Bitrate", String.format(Locale.US, "%.1f Mbps", telemetry.currentBitrateMbps))
                        MetricCell("Governor", telemetry.adaptiveState.label)
                    }
                }
            }

            // Video Settings
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text("Video Capture Settings", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)

                    Text("Resolution", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ResolutionPreset.entries.forEach { preset ->
                            val supported = preset in caps.supportedResolutions
                            val selected = config.resolution == preset
                            OutlinedButton(
                                enabled = supported && telemetry.phase == RecordingPhase.IDLE,
                                onClick = { config = config.copy(resolution = preset) },
                                border = BorderStroke(
                                    1.dp,
                                    if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                                )
                            ) {
                                Text(
                                    preset.label,
                                    color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }

                    Text("Frame Rate", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FpsPreset.entries.forEach { preset ->
                            val supported = preset in caps.supportedFps
                            val selected = config.fps == preset
                            OutlinedButton(
                                enabled = supported && telemetry.phase == RecordingPhase.IDLE,
                                onClick = { config = config.copy(fps = preset) },
                                border = BorderStroke(
                                    1.dp,
                                    if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                                )
                            ) {
                                Text(
                                    preset.label,
                                    color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Prioritize Mobile Legends FPS", fontSize = 14.sp)
                            Text(
                                "Yields GPU scheduling & dynamically scales bitrate under thermal load",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = config.prioritizeGameFps,
                            enabled = telemetry.phase == RecordingPhase.IDLE,
                            onCheckedChange = { config = config.copy(prioritizeGameFps = it) }
                        )
                    }
                }
            }

            // Audio Routing & Backup
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text("Audio Capture & Microphone Backup", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)

                    AudioCaptureMode.entries.forEach { mode ->
                        val selected = config.audioMode == mode
                        Surface(
                            onClick = {
                                if (telemetry.phase == RecordingPhase.IDLE) {
                                    config = config.copy(audioMode = mode)
                                }
                            },
                            shape = RoundedCornerShape(10.dp),
                            color = if (selected) Color(0xFF1A283B) else MaterialTheme.colorScheme.surfaceVariant,
                            border = BorderStroke(
                                1.dp,
                                if (selected) MaterialTheme.colorScheme.primary else Color.Transparent
                            )
                        ) {
                            Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
                                Text(mode.title, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                                Text(mode.subtitle, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Save Isolated Mic Stem (.m4a)", fontSize = 14.sp)
                            Text(
                                "Writes microphone audio separately in Music/FrameLock for clean editing",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = config.separateMicStemFile,
                            enabled = telemetry.phase == RecordingPhase.IDLE,
                            onCheckedChange = { config = config.copy(separateMicStemFile = it) }
                        )
                    }
                }
            }

            // Xiaomi HyperOS & Permission Readiness
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text("Device & Xiaomi HyperOS Readiness", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                    PermissionRow("Floating Overlay (Display Over Apps)", hasOverlayPerm) {
                        XiaomiHyperOSHelper.openOverlayPermissionSettings(context)
                        hasOverlayPerm = Settings.canDrawOverlays(context)
                    }
                    PermissionRow("Battery Saver: No Restrictions", hyperOsBatteryOk) {
                        XiaomiHyperOSHelper.openBatteryOptimizationSettings(context)
                        hyperOsBatteryOk = XiaomiHyperOSHelper.isIgnoringBatteryOptimizations(context)
                    }
                    if (caps.isXiaomiHyperOS) {
                        PermissionRow("HyperOS Background Pop-up Windows", hyperOsPopupOk) {
                            XiaomiHyperOSHelper.openXiaomiPermissionEditor(context)
                            hyperOsPopupOk = XiaomiHyperOSHelper.isBackgroundPopupAllowed(context)
                        }
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Hardware AVC: ${caps.hardwareAvcEncoderName ?: "Software"} · MLBB Detected: ${if (caps.mlbbInstalled) "Yes" else "Not Installed"}",
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun MetricCell(label: String, value: String) {
    Column {
        Text(label, fontSize = 11.sp, color = Color(0xFF94A3B8))
        Text(value, fontSize = 13.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun PermissionRow(label: String, granted: Boolean, onConfigure: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, fontSize = 13.sp)
            Text(
                if (granted) "Configured" else "Action required for stable gameplay capture",
                fontSize = 11.sp,
                color = if (granted) Color(0xFF22C55E) else Color(0xFFF59E0B)
            )
        }
        if (!granted) {
            OutlinedButton(onClick = onConfigure) {
                Text("Allow", fontSize = 12.sp)
            }
        }
    }
}

private fun formatDuration(ms: Long): String {
    val totalSec = ms / 1000L
    val min = totalSec / 60L
    val sec = totalSec % 60L
    return String.format(Locale.US, "%02d:%02d", min, sec)
}

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0L) return "0 MB"
    val mb = bytes / (1024.0 * 1024.0)
    return if (mb >= 1024.0) {
        String.format(Locale.US, "%.1f GB", mb / 1024.0)
    } else {
        String.format(Locale.US, "%.0f MB", mb)
    }
}
