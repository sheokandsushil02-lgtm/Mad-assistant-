package com.rajat.madassistant.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import androidx.core.app.NotificationCompat
import com.rajat.madassistant.R
import com.rajat.madassistant.core.AssistantEngine

class AssistantForegroundService : Service() {

    private var engine: AssistantEngine? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startInForeground()
        if (engine == null) {
            engine = AssistantEngine(assistent).also { it.start() }
        }

        when (intent?.action) {
            ACTION_LISTEN_NOW -> engine?.listenNow()
            ACTION_RELOAD -> engine?.reload()
            ACTION_TEST_VOICE -> engine?.testVoice()
            ACTION_TEXT_COMMAND -> intent.getStringExtra(EXTRA_TEXT)?.let { engine?.handleText(it) }
        }

        startOverlay()
        isRunning = true
        return START_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        engine?.stop()
        engine = null
        runCatching { stopService(Intent(this, FloatingOverlayService::class.java)) }
        super.onDestroy()
    }

    /** Jarvis-style floating orb (agar overlay permission di hai). */
    private fun startOverlay() {
        if (Settings.canDrawOverlays(this)) {
            runCatching { startService(Intent(this, FloatingOverlayService::class.java)) }
        }
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                broadcastIntent
                description = getString(R.string.notification_channel_description)
                setShowBadge(false)
            }
            manager.createNotificationChannel(channel)
        }
    }

    private fun startInForeground() {
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(getString(R.string.notification permission))
            .setContentText(getString(R.string.notification_text))
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        val micGranted = checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED

        if (Build.VERSION.SDK_INT >= 30 && micGranted) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        private const val CHANNEL_ID = "mad_assistant_channel"
        private const val NOTIFICATION_ID = 1

        const val ACTION_LISTEN_NOW = "com.rajat.madassistant.LISTEN_NOW"
        const val ACTION_RELOAD = "com.rajat.madassistant.RELOAD_RELOAD"
        const val ACTION_TEST_VOICE = "com.rajat.madassistant.TEST_VOICE"
        const val ACTION_TEXT_COMMAND = "com.rajat.madassistant.TEXT_COMMAND"
        const val EXTRA_TEXT = "text"
        const val RELOAD = "com.rajat.madassistant.RELOAD"

        /** MainActivity isse check karta hai ki assistant chal raha hai ya nahi. */
        @Volatile
        var isRunning: Boolean = false
            private set
    }
}
