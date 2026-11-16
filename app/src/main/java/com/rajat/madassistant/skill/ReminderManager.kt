package com.rajat.madassistant.skill

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.rajat.madassistant.R
import java.util.Calendar
import java.util.Locale

/**
 * "10 baje yaad dilana" style reminders.
 * Stores the reminder text + time, schedules an exact alarm,
 * and shows a notification + speaks the reminder when it fires.
 */
class ReminderManager(private val context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Parses speech like "10 baje pani pina yaad dilana". Returns spoken reply or null. */
    fun handle(text: String): String? {
        val t = text.lowercase(Locale.getDefault()).trim()
        if (!t.contains("याद दिला") && !t.contains("याद दिलाना") && !t.contains("reminder") &&
            !t.contains("remind") && !t.contains("alarm") && !t.contains("अलार्म")
        ) return null

        // ---- Time: "10 baje", "10:30 baje", "शाम 6 बजे" ----
        val hour: Int
        val minute: Int
        val timeMatch = Regex("(\\d{1,2})[:.]?(\\d{2})?\\s*(बजे|baje|bje|o'clock|बज)") .find(t)
        val hourMatch = Regex("(\\d{1,2})\\s*(बजे|baje|बज)").find(t)
        when {
            timeMatch != null -> {
                hour = (timeMatch.groupValues[1].toIntOrNull() ?: return null).coerceIn(1, 12)
                minute = (timeMatch.groupValues[2]?.toIntOrNull() ?: 0).coerceIn(0, 59)
            }
            hourMatch != null -> {
                hour = (hourMatch.groupValues[1].toIntOrNull() ?: return null).coerceIn(1, 12)
                minute = 0
            }
            else -> return null
        }

        // AM/PM: "शाम", "evening", "रात" => +12 (unless already 12)
        val isPm = t.contains("शाम") || t.contains("evening") || t.contains("रात") ||
                t.contains("pm") || t.contains("afternoon") || t.contains("दोपहर")
        val isAm = t.contains("सुबह") || t.contains("morning") || t.contains("am") ||
                t.contains("भोर")

        val hour24 = when {
            isPm && hour < 12 -> hour + 12
            isAm && hour == 12 -> 0
            isAm -> hour
            // No am/pm spoken: assume next occurrence (today if still ahead, else tomorrow)
            else -> hour
        }

        // ---- Message: text after "yaad dilana ki" / "reminder to" etc. ----
        val msgKeys = listOf(
            "याद दिलाना कि", "याद दिलाना", "याद दिला कि", "याद दिला", "reminder to", "remind me to", "remind me", "reminder", "remind"
        )
        var message: String? = null
        for (key in msgKeys) {
            val idx = t.indexOf(key)
            if (idx >= 0) {
                val after = t.substring(idx + key.length).trim()
                if (after.isNotBlank()) { message = after; break }
            }
        }
        // If message came before time, take everything before the time phrase
        if (message == null) {
            val cutIdx = timeMatch?.range?.first ?: hourMatch?.range?.first ?: -1
            if (cutIdx > 0) message = t.substring(0, cutIdx).trim()
        }
        if (message.isNullOrBlank()) message = "आपका reminder"

        val fireAt = nextOccurrence(hour24, minute, hasExplicitAmPm = isPm || isAm)
        schedule(message, fireAt)

        val ampm = if (hour24 < 12) "सुबह" else if (hour24 < 16) "दोपहर" else if (hour24 < 20) "शाम" else "रात"
        return "ठीक है, ${fireAt.get(Calendar.HOUR_OF_DAY)} बजे याद दिला दूँगा: $message"
    }

    /** If no am/pm was given, schedule the NEXT time this clock time occurs. */
    private fun nextOccurrence(hour24: Int, minute: Int, hasExplicitAmPm: Boolean): Calendar {
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour24)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (hasExplicitAmPm) {
            // If pm explicitly asked but that time already passed today, still today (user intent)
            if (cal.timeInMillis <= System.currentTimeMillis()) cal.add(Calendar.DAY_OF_YEAR, 1)
        } else {
            if (cal.timeInMillis <= System.currentTimeMillis()) cal.add(Calendar.DAY_OF_YEAR, 1)
        }
        return cal
    }

    /** Schedules the exact alarm and persists it so BootReceiver can reschedule. */
    fun schedule(message: String, fireAt: Calendar) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val id = System.currentTimeMillis().toInt()
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            action = ReminderReceiver.ACTION_FIRE
            putExtra(ReminderReceiver.EXTRA_MESSAGE, message)
        }
        val pending = PendingIntent.getBroadcast(
            context, id, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        try {
            if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) {
                am.setWindow(AlarmManager.RTC_WAKEUP, fireAt.timeInMillis, 60_000L, pending)
            } else {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, fireAt.timeInMillis, pending)
            }
        } catch (_: SecurityException) {
            am.setWindow(AlarmManager.RTC_WAKEUP, fireAt.timeInMillis, 60_000L, pending)
        }
        // Persist for reboot rescheduling
        prefs.edit()
            .putLong("$id.at", fireAt.timeInMillis)
            .putString("$id.msg", message)
            .putInt(KEY_LAST_ID, id)
            .apply()
    }

    /** Called from BootReceiver after reboot to reschedule pending reminders. */
    fun rescheduleAll() {
        val lastId = prefs.getInt(KEY_LAST_ID, -1)
        if (lastId == -1) return
        val at = prefs.getLong("$lastId.at", -1)
        val msg = prefs.getString("$lastId.msg", null) ?: return
        if (at < System.currentTimeMillis()) return // expired, skip
        val cal = Calendar.getInstance().apply { timeInMillis = at }
        schedule(msg, cal)
    }

    companion object {
        private const val PREFS = "mad_reminders"
        private const val KEY_LAST_ID = "last_id"
    }
}

/** Fires when a reminder alarm goes off: shows notification. */
class ReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_FIRE) return
        val message = intent.getStringExtra(EXTRA_MESSAGE) ?: "आपका reminder"
        showNotification(context, message)
    }

    private fun showNotification(context: Context, message: String) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Reminders", NotificationManager.IMPORTANCE_HIGH)
            )
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("MAD Reminder ⏰")
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        manager.notify(System.currentTimeMillis().toInt(), notification)
    }

    companion object {
        const val ACTION_FIRE = "com.rajat.madassistant.REMINDER_FIRE"
        const val EXTRA_MESSAGE = "message"
        private const val CHANNEL_ID = "mad_reminder_channel"
    }
}
