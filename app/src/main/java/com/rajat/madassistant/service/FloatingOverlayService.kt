package com.rajat.madassistant.service

import android.annotation.SuppressLint
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Jarvis-style floating orb HUD.
 *  - Pulsing glow jab listening/speaking ho
 *  - Single tap  : turant command sunne lagta hai (wake word ki zaroorat nahi)
 *  - Long press  : mini chat bubble — last command, AI reply, text input box
 */
class FloatingOverlayService : Service() {

    private lateinit var wm: WindowManager
    private var orb: View? = null
    private var chat: View? = null
    private val main = Handler(Looper.getMainLooper())

    private var lastStatus = "MAD taiyar hai"
    private var chatText: TextView? = null
    private var lastShown: String = ""

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            val text = i?.getStringExtra("text") ?: return
            lastStatus = text
            chatText?.text = text
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        runCatching {
            registerReceiver(statusReceiver, IntentFilter("com.rajat.madassistant.STATUS"))
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (orb == null) showOrb()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(statusReceiver) }
        orb?.let { runCatching { wm.removeView(it) } }
        chat?.let { runCatching { wm.removeView(it) } }
        super.onDestroy()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun showOrb() {
        val size = dp(60)
        val view = View(this)
        val bg = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            colors = intArrayOf(Color.parseColor("#1E88E5"), Color.parseColor("#7B1FA2"))
            gradientType = GradientDrawable.LINEAR_GRADIENT
            orientation = GradientDrawable.Orientation.TL_BR
        }
        view.background = bg

        val params = WindowManager.LayoutParams(
            size, size,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(12)
            y = dp(160)
        }

        // Pulsing glow animation
        val pulse = object : Runnable {
            override fun run() {
                view.animate()
                    .scaleX(1.15f).scaleY(1.15f).alpha(0.85f)
                    .setDuration(600)
                    .withEndAction {
                        view.animate()
                            .scaleX(1f).scaleY(1f).alpha(1f)
                            .setDuration(600)
                            .withEndAction { run() }
                            .start()
                    }
                    .start()
            }
        }
        main.post(pulse)

        // Tap = listen now, Long press = chat bubble, Drag = move
        view.setOnTouchListener(object : View.OnTouchListener {
            var downX = 0f
            var downY = 0f
            var startX = 0
            var startY = 0
            var moved = false
            var longPressed = false

            override fun onTouch(v: View, e: MotionEvent): Boolean {
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = e.rawX
                        downY = e.rawY
                        startX = params.x
                        startY = params.y
                        moved = false
                        longPressed = false
                        main.postDelayed({
                            if (!moved) {
                                longPressed = true
                                showChat()
                            }
                        }, 600)
                    }
                    MotionEvent.ACTION_MOVE -> {
                        if (kotlin.math.abs(e.rawX - downX) > dp(8) ||
                            kotlin.math.abs(e.rawY - downY) > dp(8)
                        ) moved = true
                        if (moved) {
                            params.x = startX + (e.rawX - downX).toInt()
                            params.y = startY + (e.rawY - downY).toInt()
                            runCatching { wm.updateViewLayout(v, params) }
                        }
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        main.removeCallbacksAndMessages(null)
                        if (!moved && !longPressed) listenNow()
                    }
                }
                return true
            }
        })

        runCatching { wm.addView(view, params) }
        orb = view
        main.post(pulse)
    }

    private fun overlayType(): Int =
        if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

    private fun listenNow() {
        val i = Intent(this, AssistantForegroundService::class.java)
            .setAction(AssistantForegroundService.ACTION_LISTEN_NOW)
        startService(i)
    }

    /** Mini chat bubble — last command, AI reply + text input. */
    @SuppressLint("ClickableViewAccessibility")
    private fun showChat() {
        if (chat != null) {
            chat?.visibility = View.VISIBLE
            return
        }
        val pad = dp(12)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#F0121A2E"))
            setPadding(pad, pad, pad, pad)
        }

        val title = TextView(this).apply {
            text = "MAD Assistant"
            setTextColor(Color.parseColor("#4FC3F7"))
            textSize = 15f
        }
        val body = TextView(this).apply {
            text = if (lastShown.isNotEmpty()) lastShown else lastStatus
            setTextColor(Color.WHITE)
            textSize = 14f
            setPadding(0, pad, 0, pad)
        }
        chatText = body

        val input = EditText(this).apply {
            hint = "Type karo..."
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            textSize = 14f
        }
        val send = Button(this).apply { text = "Send" }
        val close = Button(this).apply { text = "X" }

        send.setOnClickListener {
            val q = input.text.toString().trim()
            if (q.isEmpty()) return@setOnClickListener
            body.text = "⏳ Thinking..."
            lastShown = q
            sendToBrain(q)
            input.setText("")
        }
        close.setOnClickListener { chat?.visibility = View.GONE }

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(input, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(send)
        root.addView(title)
        root.addView(body)
        root.addView(row)
        root.addView(close)

        val params = WindowManager.LayoutParams(
            dp(300), WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
        }

        runCatching { wm.addView(root, params) }
        chat = root
    }

    private fun sendToBrain(q: String) {
        val i = Intent(this, AssistantForegroundService::class.java)
            .setAction(AssistantForegroundService.ACTION_TEXT_COMMAND)
            .putExtra("text", q)
        startService(i)
    }

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()
}
