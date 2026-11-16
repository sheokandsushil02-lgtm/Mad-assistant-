package com.rajat.madassistant.speech

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/**
 * Speaks text aloud using Android's built-in TextToSpeech engine.
 * Prefers Hindi (hi-IN) voice and falls back to US English if unavailable.
 */
class TtsSpeaker(private val context: Context) {

    private var tts: TextToSpeech? = null
    private var ready = false
    private val main = Handler(Looper.getMainLooper())

    fun start() {
        if (tts != null) return
        tts = TextToSpeech(context) { status ->
            ready = status == TextToSpeech.SUCCESS
            if (!ready) return@TextToSpeech
            val hindi = Locale("hi", "IN")
            val result = tts?.setLanguage(hindi)
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                tts?.language = Locale.US
            }
        }
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
