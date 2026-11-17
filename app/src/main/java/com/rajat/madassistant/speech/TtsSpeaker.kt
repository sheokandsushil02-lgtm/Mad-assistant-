package com.rajat.madassistant.speech

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import com.rajat.madassistant.MainActivity
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
 * Natural human voice TTS:
 *  - Google engine (com.google.android.tts) forced; missing/fail hone par default engine fallback.
 *  - hi-IN/en-IN network/HD voices (hie-network/hid-network, QUALITY_VERY_HIGH/HIGH) auto-preferred.
 *  - Init complete hone se PEHLE request aaye to speech queue ho jaati hai — pehla
 *    reply drop nahi hota ("not ready — speech dropped" bug fix).
 *  - Pitch 1.0 / rate 0.95 defaults — robotic feel khatam.
 */
class TtsSpeaker(private val context: Context) {

    private var tts: TextToSpeech? = null
    private var ready = false
    private val main = Handler(Looper.getMainLooper())
    private val prefs = context.getSharedPreferences(MadSettings.PREFS, Context.MODE_PRIVATE)

    @Volatile
    var lastStatus: String = "idle"
        private set

    /** Init complete hone tak aayi hui speech (text + onDone callback). */
    private var pending: Pair<String, (() -> Unit)?>? = null
    private var triedFallbackEngine = false

    private fun report(s: String) {
        lastStatus = s
        val i = Intent(MadEvents.STATUS).setPackage(context.packageName)
            .putExtra(MadEvents.EXTRA_TEXT, "TTS: $s")
        runCatching { context.sendBroadcast(i) }
    }

    fun start() {
        if (tts != null) return
        triedFallbackEngine = false
        report("init Google TTS...")
        tts = TextToSpeech(context, { status ->
            ready = status == TextToSpeech.SUCCESS
            if (ready) {
                applyVoice()
                flushPending()
            } else {
                onInitFailed()
            }
        }, GOOGLE_TTS_ENGINE)
    }

    /** Google engine na ho to system default engine se ek baar retry. */
    private fun onInitFailed() {
        if (!triedFallbackEngine) {
            triedFallbackEngine = true
            report("Google TTS engine fail — default engine se try...")
            tts = null
            tts = TextToSpeech(context) { status ->
                ready = status == TextToSpeech.SUCCESS
                if (ready) {
                    applyVoice()
                    flushPending()
                } else {
                    ready = false
                    report("init failed (err $status)")
                    flushPending()
                }
            }
        } else {
            report("init failed")
            flushPending()
        }
    }

    private fun flushPending() {
        val p = pending ?: return
        pending = null
        if (ready) speak(p.first, p.second)
        else main.post { p.second?.invoke() }
    }

    /** Settings Save ke baad service ise bulata hai — naye voice turant lag jaate hain. */
    fun reload() = applyVoice()

    private fun applyVoice() {
        val engine = tts ?: return
        if (!ready) return

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

        val voices = runCatching { engine.voices.toList() }.getOrNull().orEmpty()
        // Settings dropdown data is published from this same initialized engine.
        MainActivity.onVoicesReady(voices.filter {
            it.locale == Locale("hi", "IN") || it.locale == Locale("en", "IN")
        }.sortedWith(compareByDescending<Voice> { voiceScore(it) }.thenBy { it.name }))
        val voice = pickVoice(voices, locale)
        if (voice != null) runCatching { engine.voice = voice }

        val pitch = (prefs.getString(MadSettings.KEY_PITCH, "1.0") ?: "1.0").toFloatOrNull() ?: 1.0f
        val rate = (prefs.getString(MadSettings.KEY_RATE, "0.95") ?: "0.95").toFloatOrNull() ?: 0.95f
        runCatching { engine.setPitch(pitch) }
        runCatching { engine.setSpeechRate(rate) }
        report("ready — ${voice?.name ?: "default"} ($locale)")
    }

    /** hi-IN / en-IN me sabse natural (network/HD/VERY_HIGH) voice chunta hai. */
    private fun pickVoice(all: List<Voice>, primary: Locale): Voice? {
        if (all.isEmpty()) return null
        val pool = all.filter {
            it.locale == primary || it.locale == Locale("hi", "IN") || it.locale == Locale("en", "IN") ||
                it.locale.language == primary.language
        }.ifEmpty { all }

        // a) Settings dropdown se exact selected voice
        val saved = prefs.getString(MadSettings.KEY_VOICE_NAME, null).orEmpty()
        if (saved.isNotBlank()) {
            pool.firstOrNull { it.name.equals(saved, ignoreCase = true) }?.let { return it }
        }

        // b) Gender preference (Google TTS voice-name patterns)
        val gender = prefs.getString(MadSettings.KEY_VOICE_GENDER, "system") ?: "system"
        if (gender != "system") {
            val patterns = if (gender == "male") {
                listOf("x-ghi", "x-rjs", "x-iom", "x-gbb", "#male")
            } else {
                listOf("x-ihc", "x-fnf", "x-sfg", "x-ced", "x-kla", "#female")
            }
            pool.firstOrNull { v -> patterns.any { v.name.lowercase(Locale.ROOT).contains(it) } }
                ?.let { return it }
        }

        // c) Quality scoring: network voices (hie-network/hid-network) top pe
        return pool.maxByOrNull { voiceScore(it) }
    }

    /** Network voices (hie-network/hid-network) preferred; then VERY_HIGH/HIGH quality. */
    private fun voiceScore(v: Voice): Int {
        val n = v.name.lowercase(Locale.ROOT)
        var s = 0
        if (n.endsWith("-network")) s += 100
        if (n.contains("network") || n.contains("enhanced") || n.contains("premium")) s += 50
        if (n.startsWith("hie") || n.startsWith("hid")) s += 20
        if (n.startsWith("en-in")) s += 10
        when (v.quality) {
            Voice.QUALITY_VERY_HIGH -> s += 40
            Voice.QUALITY_HIGH -> s += 30
        }
        return s
    }

    /** Speaks the text, then invokes [onDone] on the main thread. */
    fun speak(text: String, onDone: (() -> Unit)? = null) {
        // Engine abhi initialize ho raha hai: speech QUEUE karo — drop nahi.
        if (!ready && tts != null) {
            report("queued (init in progress)")
            pending = text to onDone
            return
        }
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
        pending = null
        report("stopped")
    }

    companion object {
        const val GOOGLE_TTS_ENGINE = "com.google.android.tts"
    }
}
