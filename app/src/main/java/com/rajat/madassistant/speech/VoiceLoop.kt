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
 * Manual-trigger lifecycle (Fix #2):
 *  - Tap to Speak / orb tap: background wake recognizer ko IMMEDIATELY cancel+destroy,
 *    300ms wait (Google service purana session release kare), phir FRESH foreground
 *    command session — isliye ERROR_RECOGNIZER_BUSY / SERVER_DISCONNECTED nahi aata.
 *  - Command capture ya error par session CLEANLY destroy hota hai; background wake
 *    listening uske BAAD resume hoti hai (continuous wake-word toggle ON ho to).
 *  - Session counter: purani session ke pending restarts naye session ko disturb nahi karte.
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

    /** Har start* naya session id banata hai — stale restart callbacks cancel ho jaate hain. */
    private var session = 0

    private val prefs = context.getSharedPreferences(MadSettings.PREFS, Context.MODE_PRIVATE)

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

            // Command session ke errors: session CLEANLY destroy karo,
            // phir hisaab se resume — input-ending error par wake resume,
            // infra error par fresh command retry (mic dead nahi hota).
            if (isCommand) {
                when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH,
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
                    SpeechRecognizer.ERROR_AUDIO,
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                        report("error $name — command session closed")
                        destroyRecognizer()
                        resumeWakeIfEnabled()
                        return
                    }
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY,
                    SpeechRecognizer.ERROR_CLIENT,
                    ERR_SERVER_DISCONNECTED -> {
                        report("error $name — recreating command session")
                        destroyRecognizer()
                        restartAfter(500)
                        return
                    }
                    else -> {
                        report("error $name — recreating command session")
                        destroyRecognizer()
                        restartAfter(500)
                        return
                    }
                }
            }

            // Wake-session errors (background loop)
            when (error) {
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY,
                SpeechRecognizer.ERROR_CLIENT,
                ERR_SERVER_DISCONNECTED -> {
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

            if (isCommand) {
                // Command capture: session CLEANLY destroy karo.
                // Background wake resume ENGINE karta hai (TTS bolne ke baad,
                // wake toggle ON ho tab) — yahan se resume karne par MAD
                // apni hi awaaz sun leta.
                destroyRecognizer()
                report(if (text.isEmpty()) "command captured (empty)" else "command captured")
                if (text.isNotEmpty()) onHeard(text)
                return
            }

            if (text.isNotEmpty()) onHeard(text)
            restartAfter(250)
        }
    }

    fun startWake() {
        isCommand = false
        beginSession(0)
    }

    /**
     * Fix #2: Tap to Speak / orb tap — background wake listener IMMEDIATELY
     * cancel+destroy, 300ms wait, phir FRESH foreground command session.
     */
    fun startCommand() {
        isCommand = true
        beginSession(COMMAND_START_DELAY_MS)
    }

    /**
     * Naya listening session: purane recognizer ko turant cancel+destroy,
     * purani session ke pending restarts cancel, phir (delay ke baad) FRESH start.
     */
    private fun beginSession(startDelayMs: Long) {
        paused = false
        stopped = false
        session++
        main.removeCallbacksAndMessages(null)
        destroyRecognizer()
        val mode = if (isCommand) "command" else "wake"
        report(if (startDelayMs > 0) "starting ($mode, ${startDelayMs}ms delay)" else "starting ($mode)")
        if (startDelayMs > 0) {
            val my = session
            main.postDelayed({ if (my == session && !paused && !stopped) startListening() }, startDelayMs)
        } else {
            startListening()
        }
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
        session++
        main.removeCallbacksAndMessages(null)
        destroyRecognizer()
        report("idle (stopped)")
    }

    /** Command session khatam hone par: wake toggle ON ho to background listening resume. */
    private fun resumeWakeIfEnabled() {
        if (stopped) return
        if (prefs.getBoolean(MadSettings.KEY_WAKE_ENABLED, true)) {
            startWake()
        } else {
            report("idle (command done)")
        }
    }

    private fun startListening() {
        if (paused || stopped) return
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            report("speech service missing — retry in 5s")
            restartAfter(5000)
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
            restartAfter(500)
        }
    }

    private fun restartAfter(delay: Long) {
        val my = session
        main.postDelayed({ if (my == session && !paused && !stopped) startListening() }, delay)
    }

    /** Session ko cleanly khatam karo: stopListening -> cancel -> destroy. */
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
        ERR_SERVER_DISCONNECTED -> "SERVER_DISCONNECTED"
        else -> "UNKNOWN($error)"
    }

    private fun listenIntent(): Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        // Language mode Settings se live — restart nahi chahiye
        val lang = prefs.getString(MadSettings.KEY_LANG, "hinglish") ?: "hinglish"
        val loc = if (lang == "english") "en-IN" else "hi-IN"

        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, loc)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, loc)
        putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        putExtra("android.speech.extras.SPEECH_INPUT_COMPLETE_SILENCE_THRESHOLD_MILLIS", 10000)
        putExtra("android.speech.extras.SPEECH_INPUT_MINIMUM_LENGTH_MILLIS", 10000)
    }

    companion object {
        /**
         * SpeechRecognizer.ERROR_SERVER_DISCONNECTED — API 31 me add hua tha; constant
         * ko directly reference karne se minSdk < 31 devices par compile theek chalta hai
         * (value inline ho jaati hai), par safe rahne ke liye literal 11 use kar rahe hain.
         */
        private const val ERR_SERVER_DISCONNECTED = 11

        /** Fix #2: manual tap par purana session chhodne se pehle Google service ko itna ms milta hai. */
        private const val COMMAND_START_DELAY_MS = 300L
    }
}
