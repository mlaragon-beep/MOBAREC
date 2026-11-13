package com.framelock.recorder.service

import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.framelock.recorder.model.AudioSourceState
import com.framelock.recorder.model.RecordingPhase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Lightweight native Android WindowManager overlay (zero Compose/WebView overhead).
 * Occupies minimal screen area, drags smoothly, snaps to left/right edge, and dims
 * when idle so it never obstructs Mobile Legends minimap, skill wheels, or shop.
 */
class FloatingOverlayService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var windowManager: WindowManager? = null
    private var rootView: LinearLayout? = null
    private var timerText: TextView? = null
    private var pauseButton: TextView? = null
    private var audioBadge: TextView? = null
    private var layoutParams: WindowManager.LayoutParams? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        createOverlayView()
        observeTelemetry()
    }

    override fun onDestroy() {
        scope.cancel()
        rootView?.let {
            try {
                windowManager?.removeView(it)
            } catch (_: Throwable) {
            }
        }
        rootView = null
        super.onDestroy()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun createOverlayView() {
        val density = resources.displayMetrics.density
        val padH = (12 * density).toInt()
        val padV = (8 * density).toInt()

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(padH, padV, padH, padV)
            alpha = 0.88f
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#E60F172A"))
                cornerRadius = 18 * density
                setStroke((1 * density).toInt(), Color.parseColor("#475569"))
            }
        }

        val timer = TextView(this).apply {
            text = "00:00"
            setTextColor(Color.parseColor("#F8FAFC"))
            textSize = 13f
            typeface = Typeface.MONOSPACE
            setPadding(0, 0, (10 * density).toInt(), 0)
        }

        val audio = TextView(this).apply {
            text = "INT+MIC"
            setTextColor(Color.parseColor("#38BDF8"))
            textSize = 10f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, (10 * density).toInt(), 0)
        }

        val pauseBtn = TextView(this).apply {
            text = "Pause"
            setTextColor(Color.parseColor("#E2E8F0"))
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setPadding((8 * density).toInt(), (4 * density).toInt(), (8 * density).toInt(), (4 * density).toInt())
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#1E293B"))
                cornerRadius = 10 * density
            }
            setOnClickListener {
                val paused = ScreenRecordService.telemetry.value.phase == RecordingPhase.PAUSED
                val action = if (paused) ScreenRecordService.ACTION_RESUME else ScreenRecordService.ACTION_PAUSE
                startService(Intent(this@FloatingOverlayService, ScreenRecordService::class.java).apply {
                    this.action = action
                })
            }
        }

        val stopBtn = TextView(this).apply {
            text = "Stop"
            setTextColor(Color.WHITE)
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setPadding((8 * density).toInt(), (4 * density).toInt(), (8 * density).toInt(), (4 * density).toInt())
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#DC2626"))
                cornerRadius = 10 * density
            }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginStart = (6 * density).toInt()
            }
            layoutParams = lp
            setOnClickListener {
                startService(Intent(this@FloatingOverlayService, ScreenRecordService::class.java).apply {
                    action = ScreenRecordService.ACTION_STOP
                })
            }
        }

        container.addView(timer)
        container.addView(audio)
        container.addView(pauseBtn)
        container.addView(stopBtn)

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (24 * density).toInt()
            y = (160 * density).toInt()
        }

        var initialX = 0
        var initialY = 0
        var touchX = 0f
        var touchY = 0f

        container.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    touchX = event.rawX
                    touchY = event.rawY
                    container.alpha = 1f
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = initialX + (event.rawX - touchX).toInt()
                    params.y = initialY + (event.rawY - touchY).toInt()
                    windowManager?.updateViewLayout(container, params)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    container.alpha = 0.78f
                    true
                }
                else -> false
            }
        }

        windowManager?.addView(container, params)
        rootView = container
        timerText = timer
        pauseButton = pauseBtn
        audioBadge = audio
        layoutParams = params
    }

    private fun observeTelemetry() {
        scope.launch {
            ScreenRecordService.telemetry.collect { t ->
                val totalSec = t.elapsedMillis / 1000L
                val min = totalSec / 60L
                val sec = totalSec % 60L
                timerText?.text = String.format(Locale.US, "%02d:%02d", min, sec)
                pauseButton?.text = if (t.phase == RecordingPhase.PAUSED) "Resume" else "Pause"

                val (label, color) = when (t.audioState) {
                    AudioSourceState.ACTIVE_INTERNAL_AND_MIC -> "INT+MIC" to "#38BDF8"
                    AudioSourceState.ACTIVE_INTERNAL_ONLY -> "INT" to "#22C55E"
                    AudioSourceState.ACTIVE_MIC_ONLY -> "MIC" to "#38BDF8"
                    AudioSourceState.INTERNAL_BLOCKED_MIC_BACKUP,
                    AudioSourceState.INTERNAL_FAILED_MIC_BACKUP -> "MIC BKUP" to "#F59E0B"
                    AudioSourceState.SILENT_NO_AUDIO -> "MUTED" to "#EF4444"
                    AudioSourceState.IDLE -> "READY" to "#94A3B8"
                }
                audioBadge?.text = label
                audioBadge?.setTextColor(Color.parseColor(color))
            }
        }
    }
}
