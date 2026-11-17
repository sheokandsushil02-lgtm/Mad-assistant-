package com.rajat.madassistant.core

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper
import com.rajat.madassistant.brain.AiBrainClient
import com.rajat.madassistant.data.ChatEntity
import com.rajat.madassistant.data.FactEntity
import com.rajat.madassistant.data.MemoryDb
import com.rajat.madassistant.skill.PhoneSkills
import com.rajat.madassistant.skill.ReminderManager
import com.rajat.madassistant.speech.MadEvents
import com.rajat.madassistant.speech.MadSettings
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
 * Voice assistant orchestrator:
 *   wake word -> ONE-SHOT command ya "haan boliye" -> command -> Gemini reply -> TTS
 *   -> background wake listening resume.
 */
class AssistantEngine(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val dao = MemoryDb.get(context).dao()
    private val prefs = context.getSharedPreferences(MadSettings.PREFS, Context.MODE_PRIVATE)
    private val targetManager = TargetAppManager(context)
    private val tts = TtsSpeaker(context)
    private val main = Handler(Looper.getMainLooper())

    /**
     * VoiceLoop do callbacks deta hai:
     *  - onWake: sirf wake word bola (two-step mode shuru karo)
     *  - onCommand: poora command text (one-shot ya command session ya text input)
     */
    private val loop = VoiceLoop(
        context,
        onWake = { heard -> scope.launch { onWakeWord(heard) } },
        onCommand = { command -> scope.launch { handleCommand(command) } }
    )
    private val phoneSkills = PhoneSkills(context)
    private val reminders = ReminderManager(context)
    private var tone: ToneGenerator? = null

    /** Two-step mode: "haan boliye" bolne ke baad agla poora phrase command hai. */
    @Volatile
    private var waitingForCommand = false

    /** Wake partial + final dono se trigger ho sakta hai — duplicate block. */
    @Volatile
    private var wakeTriggered = false

    fun start() {
        tts.start()
        loop.startWake()
        runCatching { reminders.rescheduleAll() }
        status("AI: ready")
    }

    fun stop() {
        loop.stop()
        tts.stop()
        tone?.release()
        tone = null
        scope.cancel()
    }

    fun reload() = tts.reload()

    /** Manual mic/orb tap: skip wake word and capture the next phrase — turant. */
    fun listenNow() {
        beep()
        status("AI: listening request...")
        wakeTriggered = false
        waitingForCommand = false
        loop.startCommand()
    }

    /** Text typed into the floating chat / fallback popup. */
    fun handleText(text: String) {
        if (text.isNotBlank()) scope.launch { handleCommand(text.trim()) }
    }

    fun testVoice() {
        val name = if (prefs.getString(MadSettings.KEY_VOICE_GENDER, "system") == "female") "Maya" else "Jarvis"
        tts.speak("Namaste Sushil, main $name hoon. MAD taiyar hai.")
    }

    private fun beep() {
        if (!prefs.getBoolean(MadSettings.KEY_BEEP_ENABLED, true)) return
        runCatching {
            if (tone == null) tone = ToneGenerator(AudioManager.STREAM_MUSIC, 100)
            tone?.startTone(ToneGenerator.TONE_PROP_BEEP2, 180)
        }
    }

    private fun status(text: String) {
        val i = Intent(MadEvents.STATUS).setPackage(context.packageName)
            .putExtra(MadEvents.EXTRA_TEXT, text)
        runCatching { context.sendBroadcast(i) }
    }

    /**
     * TWO-STEP mode: sirf wake word bola.
     * "Haan boliye" TTS se poora bolne ke BAAD (UtteranceProgressListener onDone
     * ke andar) 250ms delay ke saath fresh command session start hota hai —
     * taaki MAD apni hi awaaz na sune aur user turant bol sakta hai.
     */
    private suspend fun onWakeWord(heard: String) {
        if (!prefs.getBoolean(MadSettings.KEY_WAKE_ENABLED, true)) return
        if (wakeTriggered) return   // partial + final duplicate wake block
        wakeTriggered = true
        loop.pause()
        beep()
        status("AI: wake word detected — $heard")
        waitingForCommand = true
        status("AI: waiting for command")
        tts.speak(WAKE_REPLY) {
            // Yahan UtteranceProgressListener ke onDone/onError ke andar se aaye hain —
            // speech POORA khatam ho chuki hai. Ab 250ms ka gap do (mic settle +
            // tail echo), phir FRESH command session start (Fix #2 lifecycle:
            // cancel+destroy -> 300ms -> fresh; total ~550ms user ko natural lagta hai).
            main.postDelayed({
                wakeTriggered = false
                if (waitingForCommand) loop.startCommand()
            }, COMMAND_START_DELAY_AFTER_TTS_MS)
        }
    }

    /**
     * Command flow (one-shot "hey mad <command>", two-step ka command session,
     * manual tap, text input, fallback popup — sab isi se guzarte hain):
     *  - WAITING_FOR_COMMAND reset, "You: <command>" display
     *  - Gemini (saved provider/model/key) se reply, "MAD: <reply>" display
     *  - TTS se reply bolo, POORA bolne ke baad background wake listening resume.
     * Crash-safe: exception par bhi error reply bolo, listening resume karo.
     */
    private suspend fun handleCommand(text: String) {
        waitingForCommand = false
        loop.pause()
        status("You: $text")
        status("AI: thinking...")
        dao.addChat(ChatEntity(role = ROLE_USER, content = text))
        val reply = try {
            decide(text)
        } catch (e: Exception) {
            "Sorry, kuch gadbad ho gayi. ${e.message?.take(60).orEmpty().ifBlank { "Dobara boliye." }}"
        }
        dao.addChat(ChatEntity(role = ROLE_MAD, content = reply))
        showBubble(reply)
        status("MAD: $reply")
        if (prefs.getBoolean(MadSettings.KEY_TTS_ENABLED, true)) {
            tts.speak(reply) {
                status(if (prefs.getBoolean(MadSettings.KEY_WAKE_ENABLED, true)) "AI: idle — say hey mad" else "AI: idle")
                if (prefs.getBoolean(MadSettings.KEY_WAKE_ENABLED, true)) loop.startWake()
            }
        } else if (prefs.getBoolean(MadSettings.KEY_WAKE_ENABLED, true)) {
            loop.startWake()
        }
    }

    private fun showBubble(reply: String) {
        runCatching { OverlayBubble.show(context, reply) }
    }

    private suspend fun decide(text: String): String {
        val t = text.lowercase(Locale.getDefault())
        return when {
            t.contains("याद रख") || t.contains("remember") -> remember(text)
            t.contains("क्या याद") || t.contains("याद है") || t.contains("what do you remember") -> recall()
            else -> {
                // Ek hi baar call karo — pehle handle() se result lo, null ho to agla skill try karo.
                val reminderReply = runCatching { reminders.handle(text) }.getOrNull()
                if (reminderReply != null) return reminderReply
                val skillReply = runCatching { phoneSkills.handle(text) }.getOrNull()
                if (skillReply != null) return skillReply
                if (t.contains("खोल") || (t.contains("open") && (t.contains("app") || t.contains("target")))) openTargetApp()
                else askBrain(text)
            }
        }
    }

    private suspend fun remember(rawText: String): String {
        val lower = rawText.lowercase(Locale.getDefault())
        val keys = listOf("याद रखो कि", "याद रखना कि", "याद रखो", "याद रखना", "remember that", "remember")
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
        return if (facts.isEmpty()) "अभी कुछ याद नहीं है।"
        else "आपने कहा था: " + facts.joinToString("; ") { it.content }
    }

    private fun openTargetApp(): String {
        val pkg = targetManager.targetPackage
        if (pkg.isEmpty()) return "पहले target app select कीजिए।"
        return try {
            val launch = context.packageManager.getLaunchIntentForPackage(pkg)
            if (launch == null) "यह app नहीं खुल पाई।"
            else {
                context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                "${targetManager.labelFor(pkg)} खोल रहा हूँ।"
            }
        } catch (_: Exception) { "app खोलने में दिक्कत आई।" }
    }

    private suspend fun askBrain(text: String): String {
        val key = prefs.getString(MadSettings.KEY_API, "").orEmpty()
        if (key.isBlank()) return "पूरी बातचीत के लिए Settings में AI provider की API key डालिए।"
        val provider = prefs.getString(MadSettings.KEY_PROVIDER, AiBrainClient.defaultProvider())
            .orEmpty().ifBlank { AiBrainClient.defaultProvider() }
        val model = prefs.getString(MadSettings.KEY_MODEL, "").orEmpty()
            .ifBlank { AiBrainClient.defaultModel(provider) }
        val persona = prefs.getString(MadSettings.KEY_PERSONA, "")
            ?.ifBlank { MadSettings.personaDefault() } ?: MadSettings.personaDefault()
        val facts = dao.recentFacts().joinToString("\n") { "- ${it.content}" }
        val contextText = "Assistant persona:\n$persona" + if (facts.isNotBlank()) "\nKnown facts:\n$facts" else ""
        return AiBrainClient.ask(provider, key, model, text, contextText)
    }

    companion object {
        private const val ROLE_USER = "user"
        private const val ROLE_MAD = "mad"
        private const val WAKE_REPLY = "हाँ, बोलिए।"
        private const val COMMAND_START_DELAY_AFTER_TTS_MS = 250L
    }
}
