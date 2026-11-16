package com.rajat.madassistant.core

import android.content.Context
import android.content.Intent
import com.rajat.madassistant.brain.AiBrainClient
import com.rajat.madassistant.data.ChatEntity
import com.rajat.madassistant.data.FactEntity
import com.rajat.madassistant.data.MemoryDb
import com.rajat.madassistant.skill.PhoneSkills
import com.rajat.madassistant.skill.ReminderManager
import com.rajat.madassistant.speech.TtsSpeaker
import com.rajat.madassistant.speech.VoiceLoop
import com.rajat.madassistant.ui.OverlayBubble
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * The orchestrator: wake word -> listen -> decide -> speak (+ show bubble).
 * Offline skills: remember/recall facts, open apps, WhatsApp/call/browser/email,
 * time/date, reminders.
 * Online skill: full AI conversation (Gemini/OpenAI/Claude/Grok — needs API key).
 */
class AssistantEngine(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val dao = MemoryDb.get(context).dao()
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val targetManager = TargetAppManager(context)
    private val tts = TtsSpeaker(context)
    private val loop = VoiceLoop(context) { heard -> scope.launch { onHeard(heard) } }
    private val phoneSkills = PhoneSkills(context)
    private val reminders = ReminderManager(context)

    fun start() {
        tts.start()
        loop.startWake()
        // Re-schedule any reminders that survived a reboot
        runCatching { reminders.rescheduleAll() }
    }

    fun stop() {
        loop.stop()
        tts.stop()
        scope.cancel()
    }

    private suspend fun onHeard(text: String) {
        if (loop.isCommand) {
            handleCommand(text)
        } else {
            val t = text.lowercase(Locale.getDefault())
            if (t.contains("mad") || t.contains("मद") || t.contains("मैड")) {
                loop.pause()
                tts.speak(WAKE_REPLY) { loop.startCommand() }
            }
        }
    }

    private suspend fun handleCommand(text: String) {
        loop.pause()
        dao.addChat(ChatEntity(role = ROLE_USER, content = text))
        val reply = decide(text)
        dao.addChat(ChatEntity(role = ROLE_MAD, content = reply))
        showBubble(reply)
        tts.speak(reply) { loop.startWake() }
    }

    private fun showBubble(reply: String) {
        runCatching { OverlayBubble.show(context, reply) }
    }

    private suspend fun decide(text: String): String {
        val t = text.lowercase(Locale.getDefault())
        return when {
            // Memory skills
            t.contains("याद रख") || t.contains("remember") -> remember(text)
            t.contains("क्या याद") || t.contains("याद है") || t.contains("what do you remember") -> recall()
            // Reminder skill (before खोल/open so "yaad dilana" isn't caught by app-open)
            reminders.handle(text) != null -> reminders.handle(text)!!
            // Phone skills: WhatsApp/call/browser/email/time/date/open apps
            phoneSkills.handle(text) != null -> phoneSkills.handle(text)!!
            // Target app (REMS) shortcut
            t.contains("खोल") || (t.contains("open") && (t.contains("app") || t.contains("target"))) ->
                openTargetApp()
            else -> askBrain(text)
        }
    }

    private suspend fun remember(rawText: String): String {
        val lower = rawText.lowercase(Locale.getDefault())
        val keys = listOf(
            "याद रखो कि", "याद रखना कि", "याद रखो", "याद रखना",
            "remember that", "remember"
        )
        for (key in keys) {
            val idx = lower.indexOf(key)
            if (idx >= 0) {
                var content = rawText.substring(idx + key.length).trim()
                if (content.startsWith("कि")) content = content.removePrefix("कि").trim()
                content = content.trim('.')
                if (content.isNotBlank()) {
                    dao.addFact(FactEntity(content = content))
                    return "ठीक है, याद रख लिया: $content"
                }
            }
        }
        return "क्या याद रखना है? फिर से बोलिए।"
    }

    private suspend fun recall(): String {
        val facts = dao.recentFacts().take(5)
        return if (facts.isEmpty()) {
            "अभी कुछ याद नहीं है।"
        } else {
            "आपने कहा था: " + facts.joinToString("; ") { it.content }
        }
    }

    private fun openTargetApp(): String {
        val pkg = targetManager.targetPackage
        if (pkg.isEmpty()) return "पहले target app select कीजिए।"
        return try {
            val launch = context.packageManager.getLaunchIntentForPackage(pkg)
            if (launch == null) {
                "यह app नहीं खुल पाई।"
            } else {
                context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                "${targetManager.labelFor(pkg)} खोल रहा हूँ।"
            }
        } catch (_: Exception) {
            "app खोलने में दिक्कत आई।"
        }
    }

    private suspend fun askBrain(text: String): String {
        val key = prefs.getString(KEY_API, "").orEmpty()
        if (key.isBlank()) {
            return "अभी मैं ये काम कर सकता हूँ: WhatsApp या कोई भी app खोलना, call करना, " +
                "browser और email खोलना, time और date बताना, reminder लगाना, और याद रखना। " +
                "पूरी बातचीत के लिए Settings में API key डालिए।"
        }
        val provider = prefs.getString(KEY_PROVIDER, AiBrainClient.defaultProvider())
            .orEmpty().ifBlank { AiBrainClient.defaultProvider() }
        val model = prefs.getString(KEY_MODEL, "")
            .orEmpty().ifBlank { AiBrainClient.defaultModel(provider) }
        val facts = dao.recentFacts().joinToString("\n") { "- ${it.content}" }
        return AiBrainClient.ask(provider, key, model, text, facts)
    }

    companion object {
        private const val PREFS = "mad_prefs"
        private const val KEY_API = "gemini_api_key"
        private const val KEY_PROVIDER = "ai_provider"
        private const val KEY_MODEL = "ai_model"
        private const val ROLE_USER = "user"
        private const val ROLE_MAD = "mad"
        private const val WAKE_REPLY = "हाँ, बोलिए।"
    }
}
