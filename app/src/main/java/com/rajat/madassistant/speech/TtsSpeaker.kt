package com.rajat.madassistant.speech

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/** Shared settings keys + broadcast events — pura app isi se padhta hai. */
object MadSettings {
    const val PREFS = "mad_prefs"
    const val KEY_API = "gemini_api_key"
    const val KEY_PROVIDER = "ai_provider"
    const val KEY_MODEL = "ai_model"
    const val KEY_VOICE_GENDER = "voice_gender"   // male | female | system
    const val KEY_PITCH = "voice_pitch"           // 0.7 .. 1.4
    const val KEY_RATE = "voice_rate"             // 0.7 .. 1.5
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
 * Speaks text aloud using Android's built-in TextToSpeech engine.
 * Voice gender (Jarvis/Maya), pitch, speed aur language sab Settings se.
 * reload() se Save ke turant baad naye settings lag jaate hain (app restart nahi chahiye).
 */
class TtsSpeaker(private val context: Context) {

    private var tts: TextToSpeech? = null
    private var ready = false
    private val main = Handler(Looper.getMainLooper())
    private val prefs = context.getSharedPreferences(MadSettings.PREFS, Context.MODE_PRIVATE)

    fun start() {
        if (tts != null) return
        tts = TextToSpeech(context) { status ->
            ready = status == TextToSpeech.SUCCESS
            if (ready) applyVoice()
        }
    }

    /** Settings Save ke baad service ise bulata hai — naye voice turant lag jaate hain. */
    fun reload() = applyVoice()

    private fun applyVoice() {
        val engine = tts ?: return
        if (!ready) return

        // 1) Language mode
        val lang = prefs.getString(MadSettings.KEY_LANG, "hinglish") ?: "hinglish"
        val locale = when (lang) {
            "english" -> Locale("en", "IN")
            "hindi" -> Locale("hi", "IN")
            else -> Locale("hi", "IN") // hinglish
        }
        runCatching { engine.language = locale }

        // 2) Gender voice (Google TTS voice-name patterns; device ke hisaab se match hota hai)
        val gender = prefs.getString(MadSettings.KEY_VOICE_GENDER, "system") ?: "system"
        if (gender != "system") {
            val wanted = if (gender == "male") {
                listOf("#male", "x-ghi", "x-rjs", "x-iom", "x-gbb")
            } else {
                listOf("#female", "x-ihc", "x-fnf", "x-sfg", "x-ced", "x-kla")
            }
            val voices = runCatching { engine.voices }.getOrNull() ?: emptyList()
            val match = voices.firstOrNull { v ->
                val n = v.name.lowercase(Locale.ROOT)
                wanted.any { n.contains(it) }
            }
            if (match != null) runCatching { engine.voice = match }
        }

        // 3) Pitch + speed
        val pitch = (prefs.getString(MadSettings.KEY_PITCH, "1.0") ?: "1.0").toFloatOrNull() ?: 1.0f
        val rate = (prefs.getString(MadSettings.KEY_RATE, "1.0") ?: "1.0").toFloatOrNull() ?: 1.0f
        runCatching { engine.setPitch(pitch) }
        runCatching { engine.setSpeechRate(rate) }
    }

    /** Speaks the text, then invokes [onDone] on the main thread. */
    fun speak(text: String, onDone: (() -> Unit)? = null) {
        if (!ready || tts == null) {
            main.post { onDone?.invoke() }
            return
        }
        val id = "mad_${System.currentTimeMillis()}"
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) {
                if (utteranceId == id) main.post { onDone?.invoke() }
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                if (utteranceId == id) main.post { onDone?.invoke() }
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
    }
}
