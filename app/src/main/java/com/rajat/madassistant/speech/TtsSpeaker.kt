package com.rajat.madassistant.speech

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import java.util.Locale

/** Shared settings keys + broadcast events — pura app isi se padhta hai. */
object MadSettings {
    const val PREFS = "mad_prefs"
    const val KEY_API = "gemini_api_key"
    const val KEY_PROVIDER = "ai_provider"
    const val KEY_MODEL = "ai_model"
    const val KEY_VOICE_GENDER = "voice_gender"   // male | female | system
    const val KEY_VOICE_NAME = "voice_name"       // exact engine voice (Settings dropdown); khaali = auto best
    const val KEY_PITCH = "voice_pitch"           // 0.7 .. 1.4 (default 1.0)
    const val KEY_RATE = "voice_rate"             // 0.7 .. 1.5 (default 0.95)
    const val KEY_LANG = "language_mode"          // hinglish | hindi | english
    const val KEY_WAKE = "wake_word"
    const val KEY_WAKE_ENABLED = "wake_enabled"
    const val KEY_TTS_ENABLED = "tts_enabled"
    const val KEY_BEEP_ENABLED = "beep_enabled"
    const val KEY_PERSONA = "persona"

    fun personaDefault(): String =
        "You are MAD — Sushil ka personal AI assistant (Jarvis/Maya/Lia style). " +
            "Sushil ki business REMS Business hai (electrical, welding, DJ aur Khata management). " +
            "User ki bhasha me (Hinglish/Hindi/English) short, crisp aur actionable jawab do (max 2 sentence). " +
            "Business ke kaam ke commands samajh ke REMS/Business tasks perform karo."
}

object MadEvents {
    const val STATUS = "com.rajat.madassistant.STATUS"
    const val EXTRA_TEXT = "text"
}

/**
 * Natural human voice ke liye TTS:
 *  - Google TTS engine (com.google.android.tts) forced — hi-IN/en-IN ke
 *    network/HD voices (hie-network, hid-network, QUALITY_VERY_HIGH/HIGH)
 *    automatically prefer hote hain.
 *  - Default pitch 1.0 / rate 0.95 — robotic feel khatam.
 *  - Settings ke voice dropdown ka exact selection KEY_VOICE_NAME se lagta hai.
 *  - "TTS: ..." status broadcasts live debug console ke liye.
 */
class TtsSpeaker(private val context: Context) {

    private var tts: TextToSpeech? = null
    private var ready = false
    private val main = Handler(Looper.getMainLooper())
    private val prefs = context.getSharedPreferences(MadSettings.PREFS, Context.MODE_PRIVATE)

    /** Debug console ke liye TTS ka current state. */
    @Volatile
    var lastStatus: String = "idle"
        private set

    private fun report(s: String) {
        lastStatus = s
        val i = Intent(MadEvents.STATUS).setPackage(context.packageName)
            .putExtra(MadEvents.EXTRA_TEXT, "TTS: $s")
        runCatching { context.sendBroadcast(i) }
    }

    fun start() {
        if (tts != null) return
        report("init Google TTS...")
        tts = TextToSpeech(context, { status ->
            ready = status == TextToSpeech.SUCCESS
            if (ready) applyVoice() else report("init failed (err $status)")
        }, GOOGLE_TTS_ENGINE)
    }

    /** Settings Save ke baad service ise bulata hai — naye voice turant lag jaate hain. */
    fun reload() = applyVoice()

    private fun applyVoice() {
        val engine = tts ?: return
        if (!ready) return

        // 1) Language mode (fallback chain ke saath)
        val lang = prefs.getString(MadSettings.KEY_LANG, "hinglish") ?: "hinglish"
        val wanted = when (lang) {
            "english" -> listOf(Locale("en", "IN"), Locale("en", "US"), Locale.US)
            "hindi" -> listOf(Locale("hi", "IN"))
            else -> listOf(Locale("hi", "IN"), Locale("en", "IN")) // hinglish
        }
        val locale = wanted.firstOrNull { l ->
            val r = runCatching { engine.isLanguageAvailable(l) }.getOrNull()
            r == TextToSpeech.LANG_AVAILABLE || r == TextToSpeech.LANG_COUNTRY_AVAILABLE ||
                r == TextToSpeech.LANG_COUNTRY_VAR_AVAILABLE
        } ?: Locale("en", "IN")
        runCatching { engine.language = locale }

        // 2) Voice: saved exact voice > gender pattern > network/HD quality scoring
        val voice = pickVoice(engine, locale)
        if (voice != null) runCatching { engine.voice = voice }

        // 3) Pitch + speed — natural defaults (1.0 / 0.95)
        val pitch = (prefs.getString(MadSettings.KEY_PITCH, "1.0") ?: "1.0").toFloatOrNull() ?: 1.0f
        val rate = (prefs.getString(MadSettings.KEY_RATE, "0.95") ?: "0.95").toFloatOrNull() ?: 0.95f
        runCatching { engine.setPitch(pitch) }
        runCatching { engine.setSpeechRate(rate) }
        report("ready — ${voice?.name ?: "default"} ($locale)")
    }

    /** hi-IN / en-IN me sabse natural (network/HD/VERY_HIGH) voice chunta hai. */
    private fun pickVoice(engine: TextToSpeech, primary: Locale): Voice? {
        val all = runCatching { engine.voices }.getOrNull().orEmpty()
        if (all.isEmpty()) return null
        val pool = all.filter {
            it.locale == primary || it.locale == Locale("hi", "IN") || it.locale == Locale("en", "IN") ||
                it.locale.language == primary.language
        }.ifEmpty { all }
        val lower = pool.map { it to it.name.lowercase(Locale.ROOT) }

        // a) Settings dropdown se exact selected voice
        val saved = prefs.getString(MadSettings.KEY_VOICE_NAME, null).orEmpty()
        if (saved.isNotBlank()) {
            lower.firstOrNull { it.second == saved.lowercase(Locale.ROOT) }?.first?.let { return it }
        }

        // b) Gender preference (Google TTS voice-name patterns)
        val gender = prefs.getString(MadSettings.KEY_VOICE_GENDER, "system") ?: "system"
        if (gender != "system") {
            val patterns = if (gender == "male") {
                listOf("x-ghi", "x-rjs", "x-iom", "x-gbb", "#male")
            } else {
                listOf("x-ihc", "x-fnf", "x-sfg", "x-ced", "x-kla", "#female")
            }
            lower.firstOrNull { p -> patterns.any { p.second.contains(it) } }?.first?.let { return it }
        }

        // c) Quality scoring: network voices (hie-network/hid-network) + HD quality top pe
        return lower.maxByOrNull { (v, n) ->
            var s = 0
            if (n.endsWith("-network")) s += 100
            if (n.contains("network") || n.contains("enhanced") || n.contains("premium")) s += 50
            if (n.startsWith("hie") || n.startsWith("hid")) s += 20
            if (n.startsWith("en-in")) s += 10
            when (v.quality) {
                Voice.QUALITY_VERY_HIGH -> s += 40
                Voice.QUALITY_HIGH -> s += 30
                else -> {}
            }
            s
        }?.first
    }

    /** Speaks the text, then invokes [onDone] on the main thread. */
    fun speak(text: String, onDone: (() -> Unit)? = null) {
        if (!ready || tts == null) {
            report("not ready — speech dropped")
            main.post { onDone?.invoke() }
            return
        }
        report("speaking...")
        val id = "mad_${System.currentTimeMillis()}"
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) {
                if (utteranceId == id) {
                    report("idle")
                    main.post { onDone?.invoke() }
                }
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                if (utteranceId == id) {
                    report("utterance error")
                    main.post { onDone?.invoke() }
                }
            }
        })
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
    }

    fun stop() {
        try {
            tts?.stop()
            tts?.shutdown()
        } catch (_: Exception) {
        }
        tts = null
        ready = false
        report("stopped")
    }

    companion object {
        const val GOOGLE_TTS_ENGINE = "com.google.android.tts"
    }
}
