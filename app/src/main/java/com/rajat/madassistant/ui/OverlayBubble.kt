package com.rajat.madassistant.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView

/**
 * Jarvis-style floating answer bubble: shows MAD's spoken reply as a
 * draggable pill over any app. Falls back silently when the
 * "Display over other apps" permission is not granted.
 */
object OverlayBubble {

    private var bubbleView: View? = null
    private var hideRunnable: Runnable? = null

    fun canDraw(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= 23) Settings.canDrawOverlays(context) else true

    /** Shows the reply as a bubble; auto-hides after a few seconds. */
    @SuppressLint("ClickableViewAccessibility")
    fun show(context: Context, text: String) {
        if (!canDraw(context)) return
        val wm = context.getSystemService(WindowManager::class.java) ?: return
        // Replace any existing bubble
        bubbleView?.let { try { wm.removeView(it) } catch (_: Exception) {} }

        val dp = { v: Int ->
            TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(),
                context.resources.displayMetrics).toInt()
        }

        val label = TextView(context).apply {
            this.text = text
            setTextColor(Color.WHITE)
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 4
            val pad = dp(14)
            setPadding(pad, dp(10), pad, dp(10))
            background = GradientDrawable().apply {
                setColor(0xE6101A2A.toInt())
                cornerRadius = dp(20).toFloat()
                setStroke(dp(1), 0x503B82F6.toInt())
            }
        }

        val params = WindowManager.LayoutParams().apply {
            type = if (Build.VERSION.SDK_INT >= 26) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }
            format = PixelFormat.TRANSLUCENT
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
            width = WindowManager.LayoutParams.WRAP_CONTENT
            height = WindowManager.LayoutParams.WRAP_CONTENT
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = dp(64)
        }

        // Drag support
        var lastX = 0f
        var lastY = 0f
        var startX = 0
        var startY = 0
        var moved = false
        label.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    lastX = e.rawX; lastY = e.rawY
                    startX = params.x; startY = params.y
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - lastX; val dy = e.rawY - lastY
                    if (kotlin.math.abs(dx) > 4 || kotlin.math.abs(dy) > 4) moved = true
                    params.x = startX + dx.toInt()
                    params.y = startY + dy.toInt()
                    params.gravity = Gravity.TOP or Gravity.START
                    try { wm.updateViewLayout(label, params) } catch (_: Exception) {}
                    true
                }
                else -> false
            }
        }
        // Tap to dismiss
        label.setOnClickListener {
            bubbleView?.let { v -> try { wm.removeView(v) } catch (_: Exception) {} }
            bubbleView = null
        }

        try {
            wm.addView(label, params)
            bubbleView = label
        } catch (_: Exception) {
            return
        }

        // Auto-hide after 6 seconds
        hideRunnable?.let { label.removeCallbacks(it) }
        val hide = Runnable {
            bubbleView?.let { v ->
                try { wm.removeView(v) } catch (_: Exception) {}
            }
            bubbleView = null
        }
        hideRunnable = hide
        label.postDelayed(hide, 6000)
    }

    fun hide(context: Context) {
        val wm = context.getSystemService(WindowManager::class.java) ?: return
        bubbleView?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        bubbleView = null
    }
}
