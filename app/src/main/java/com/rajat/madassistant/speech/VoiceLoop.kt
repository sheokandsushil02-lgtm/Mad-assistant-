package com.rajat.madassistant.speech

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * Always-on listening loop built on Android's built-in SpeechRecognizer.
 *
 * Post-wake command flow (naya):
 *  - ONE-SHOT ("hey mad <command>"): wake session ke FINAL result me wake ke
 *    baad extra words milte hain to prefix strip karke seedha onCommand() fire
 *    hota hai — "haan boliye" ka intezaar nahi, Gemini turant.
 *  - TWO-STEP: sirf wake word bola → onWake() fire; ENGINE "haan boliye" bolta
 *    hai aur TTS onDone + 250ms ke BAAD hi startCommand() se fresh command
 *    session banata hai (WAITING_FOR_COMMAND state engine me).
 *  - Wake trigger ab sirf FINAL result (onResults) se hota hai; partials sirf
 *    console feedback hain — warna one-shot phrase beech me kat jaati thi
 *    (pichla "haan boliye ke baad sunta nahi" bug isi se tha).
 *  - Command mode me wake check NAHI hota — poora onResults text command hai.
 *
 * Manual-trigger lifecycle (Fix #2) + explicit Google service binding (Fix #3)
 * + ERROR_RECOGNIZER_BUSY/CLIENT/SERVER_DISCONNECTED(11) destroy+recreate — sab pehle se hain.
 */
class VoiceLoop(
    private val context: Context,
    private val onWake: (String) -> Unit,
    private val onCommand: (String) -> Unit
) {

    /** WAITING_FOR_COMMAND / command session active — engine isi se flow decide karta hai. */
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

    /** Fix #3: resolved Google recognition service — null component kabhi pass nahi hota. */
    @Volatile
    private var serviceComponent: ComponentName? = null

    private fun report(s: String) {
        lastStatus = s
        val i = Intent(MadEvents.STATUS).setPackage(context.packageName)
            .putExtra(MadEvents.EXTRA_TEXT, "Mic: $s")
        runCatching { context.sendBroadcast(i) }
    }

    /**
     * Fix #3: active RecognitionService dhundo — googlequicksearchbox (Google app) pehle
     * prefer, fir com.google.android.tts. Na mile to pehla available service.
     */
    private fun resolveRecognitionService(): ComponentName? {
        val pm = context.packageManager
        val resolved = runCatching {
            pm.queryIntentServices(Intent(RecognitionService.SERVICE_INTERFACE), PackageManager.MATCH_ALL)
        }.getOrNull().orEmpty()
        if (resolved.isEmpty()) return null

        for (pkg in PREFERRED_RECOGNITION_PACKAGES) {
            resolved.firstOrNull { it.serviceInfo?.packageName == pkg }?.let { ri ->
                val si = ri.serviceInfo
                return ComponentName(si.packageName, si.name)
            }
        }
        val si = resolved.first().serviceInfo ?: return null
        return ComponentName(si.packageName, si.name)
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            report(if (isCommand) "listening (command)" else "listening (wake)")
        }

        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}

        /**
         * Partials ab sirf console feedback hain — ACTION kabhi nahi.
         * Pehle partial "hey mad" turant wake fire karta tha: user one-shot
         * phrase ("hey mad what is 2 plus 2") bol raha hota to "haan boliye"
         * beech me bol deti thi aur command words kat jaate the.
         */
        override fun onPartialResults(partialResults: Bundle?) {
            if (stopped) return
            val partial = partialResults
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull().orEmpty().trim()
            if (partial.isNotEmpty()) report("partial: $partial")
        }

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

            // COMMAND MODE: wake check NAHI — poora text hi command hai.
            // WAITING_FOR_COMMAND state engine handleCommand me reset hota hai.
            if (isCommand) {
                destroyRecognizer()
                report(if (text.isEmpty()) "command captured (empty)" else "command captured")
                if (text.isNotEmpty()) onCommand(text)
                return
            }

            // WAKE MODE: final result me wake word check karo.
            if (text.isNotEmpty()) {
                val customWake = prefs.getString(MadSettings.KEY_WAKE, "hey mad")
                if (MadSettings.matchesWake(text, customWake)) {
                    // ONE-SHOT: wake ke baad extra words hain to prefix strip
                    // karke seedha command — koi "haan boliye" nahi, koi wait nahi.
                    val command = MadSettings.stripWake(text, customWake)
                    destroyRecognizer()
                    if (command.isNotBlank()) {
                        report("one-shot command: $command")
                        onCommand(command)
                    } else {
                        // TWO-STEP: sirf wake word — engine "haan boliye" bolega,
                        // TTS onDone + 250ms baad startCommand() hoga.
                        report("wake: $text")
                        onWake(text)
                    }
                    return
                }
            }
            restartAfter(250)
        }
    }

    fun startWake() {
        isCommand = false
        beginSession(0)
    }

    /**
     * Fix #2: manual tap / post-wake command session — purana recognizer
     * IMMEDIATELY cancel+destroy, 300ms wait, phir FRESH command session.
     * (Engine ise TTS "haan boliye" ke onDone + 250ms ke baad call karta hai.)
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
        val sr = recognizer ?: createRecognizer().also {
            recognizer = it
        }
        try {
            sr.startListening(listenIntent(isCommand))
        } catch (_: Exception) {
            report("start failed — recreating recognizer")
            destroyRecognizer()
            restartAfter(500)
        }
    }

    /** Fix #3: explicit ComponentName ke saath fresh recognizer (Google service prefer). */
    private fun createRecognizer(): SpeechRecognizer {
        if (serviceComponent == null) serviceComponent = resolveRecognitionService()
        val component = serviceComponent
        val sr = if (component != null) {
            report("binding ${component.packageName}")
            SpeechRecognizer.createSpeechRecognizer(context, component)
        } else {
            report("binding system default service")
            SpeechRecognizer.createSpeechRecognizer(context)
        }
        sr.setRecognitionListener(listener)
        return sr
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

    /**
     * Command mode me chhote silence timeouts (user chhota command bolega, jaldi
     * finalize ho), wake mode me lambe (background continuously sunta rahe).
     */
    private fun listenIntent(commandMode: Boolean): Intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            val lang = prefs.getString(MadSettings.KEY_LANG, "hinglish") ?: "hinglish"
            val loc = if (lang == "english") "en-IN" else "hi-IN"

            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, loc)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, loc)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)   // console feedback
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false)   // Google network engine
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)

            if (commandMode) {
                // Command session: bolna khatam + 2.5s silence par finalize —
                // wake session ke 10s lambe timeouts se reply turant milta hai.
                putExtra("android.speech.extras.SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS", 2500L)
                putExtra("android.speech.extras.SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS", 2000L)
            } else {
                putExtra("android.speech.extras.SPEECH_INPUT_COMPLETE_SILENCE_THRESHOLD_MILLIS", 10000)
                putExtra("android.speech.extras.SPEECH_INPUT_MINIMUM_LENGTH_MILLIS", 10000)
            }
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

        /** Fix #3: Google services is order me prefer hote hain. */
        private val PREFERRED_RECOGNITION_PACKAGES = listOf(
            "com.google.android.googlequicksearchbox",
            "com.google.android.tts"
        )
    }
}
