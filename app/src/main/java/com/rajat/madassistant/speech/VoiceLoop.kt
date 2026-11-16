package com.rajat.madassistant.speech

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * Always-on listening loop built on Android's built-in SpeechRecognizer.
 *
 * ERROR_RECOGNIZER_BUSY / ERROR_CLIENT (Google app ke known deadlocks) par
 * recognizer instance ko destroy + recreate kiya jaata hai (500ms delay ke
 * saath) — isse assistant "sunna band kar deta" bug fix hota hai.
 * NO_MATCH / SPEECH_TIMEOUT par soft-restart, aur sab kuch "Mic: ..." debug
 * broadcasts karta hai.
 */
class VoiceLoop(
    private val context: Context,
    private val onHeard: (String) -> Unit
) {

    @Volatile
    var isCommand = false
        private set

    /** Debug console ke liye mic ka current state ("listening"/"idle"/"recreating"...). */
    @Volatile
    var lastStatus: String = "idle"
        private set

    @Volatile
    private var paused = false

    @Volatile
    private var stopped = false

    private var recognizer: SpeechRecognizer? = null
    private val main = Handler(Looper.getMainLooper())

    private fun report(s: String) {
        lastStatus = s
        val i = Intent(MadEvents.STATUS).setPackage(context.packageName)
            .putExtra(MadEvents.EXTRA_TEXT, "Mic: $s")
        runCatching { context.sendBroadcast(i) }
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            report(if (isCommand) "listening (command)" else "listening (wake)")
        }

        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}

        override fun onPartialResults(partialResults: Bundle?) {}

        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onError(error: Int) {
            if (stopped) return
            val name = errName(error)
            when (error) {
                // Google recognizer ke fatal errors — soft restart kaam nahi karta:
                // purana instance destroy karke NAYA instance banao (500ms baad).
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY,
                SpeechRecognizer.ERROR_CLIENT -> {
                    report("error $name — recreating recognizer")
                    destroyRecognizer()
                    restartAfter(500)
                }
                SpeechRecognizer.ERROR_NO_MATCH,
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                    report("error $name")
                    restartAfter(250)
                }
                else -> {
                    report("error $name")
                    restartAfter(500)
                }
            }
        }

        override fun onResults(results: Bundle?) {
            if (stopped) return
            val list = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val text = list?.firstOrNull().orEmpty().trim()
            if (text.isNotEmpty()) onHeard(text)
            restartAfter(250)
        }
    }

    fun startWake() {
        isCommand = false
        paused = false
        stopped = false
        startListening()
    }

    fun startCommand() {
        isCommand = true
        paused = false
        stopped = false
        startListening()
    }

    /** Pause listening (e.g. while the assistant is speaking, to avoid hearing itself). */
    fun pause() {
        paused = true
        try {
            recognizer?.stopListening()
        } catch (_: Exception) {
        }
    }

    fun stop() {
        stopped = true
        paused = true
        destroyRecognizer()
        report("idle (stopped)")
    }

    private fun startListening() {
        if (paused || stopped) return
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            report("speech service missing — retry in 5s")
            main.postDelayed({ startListening() }, 5000)
            return
        }
        val sr = recognizer ?: SpeechRecognizer.createSpeechRecognizer(context).also {
            it.setRecognitionListener(listener)
            recognizer = it
        }
        try {
            sr.startListening(listenIntent())
        } catch (_: Exception) {
            report("start failed — recreating recognizer")
            destroyRecognizer()
            restartAfter(1000)
        }
    }

    private fun restartAfter(delay: Long) {
        main.postDelayed({ if (!paused && !stopped) startListening() }, delay)
    }

    private fun destroyRecognizer() {
        recognizer?.apply {
            try {
                stopListening()
            } catch (_: Exception) {
            }
            try {
                cancel()
            } catch (_: Exception) {
            }
            try {
                destroy()
            } catch (_: Exception) {
            }
        }
        recognizer = null
    }

    private fun errName(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "NETWORK_TIMEOUT"
        SpeechRecognizer.ERROR_NETWORK -> "NETWORK"
        SpeechRecognizer.ERROR_AUDIO -> "AUDIO"
        SpeechRecognizer.ERROR_SERVER -> "SERVER"
        SpeechRecognizer.ERROR_CLIENT -> "CLIENT"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "SPEECH_TIMEOUT"
        SpeechRecognizer.ERROR_NO_MATCH -> "NO_MATCH"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "RECOGNIZER_BUSY"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "INSUFFICIENT_PERMISSIONS"
        else -> "UNKNOWN($error)"
    }

    private fun listenIntent(): Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        // Language mode Settings se live — restart nahi chahiye
        val lang = context
            .getSharedPreferences(MadSettings.PREFS, Context.MODE_PRIVATE)
            .getString(MadSettings.KEY_LANG, "hinglish") ?: "hinglish"
        val loc = if (lang == "english") "en-IN" else "hi-IN"

        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, loc)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, loc)
        putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        putExtra("android.speech.extras.SPEECH_INPUT_COMPLETE_SILENCE_THRESHOLD_MILLIS", 10000)
        putExtra("android.speech.extras.SPEECH_INPUT_MINIMUM_LENGTH_MILLIS", 10000)
    }
}
