package com.rajat.madassistant.speech

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * Always-on listening loop built on Android's built-in SpeechRecognizer.
 *
 * WAKE mode listens continuously until something is heard; the engine decides
 * if it contained the wake word. COMMAND mode captures a single user command.
 * The loop restarts itself on every error/result so it runs 24/7.
 */
class VoiceLoop(
    private val context: Context,
    private val onHeard: (String) -> Unit
) {

    @Volatile
    var isCommand = false
        private set

    @Volatile
    private var paused = false

    @Volatile
    private var stopped = false

    private var recognizer: SpeechRecognizer? = null
    private val main = Handler(Looper.getMainLooper())

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onPartialResults(partialResults: Bundle?) {}
        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onError(error: Int) {
            if (stopped) return
            val delay = when (error) {
                SpeechRecognizer.ERROR_NO_MATCH,
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> 400L
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> 1500L
                else -> 1000L
            }
            restartAfter(delay)
        }

        override fun onResults(results: Bundle?) {
            if (stopped) return
            val list = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val text = list?.firstOrNull().orEmpty().trim()
            if (text.isNotEmpty()) onHeard(text)
            restartAfter(400)
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
        startListening()
    }

    /** Pause listening (e.g. while the assistant is speaking, to avoid hearing itself). */
    fun pause() {
        paused = true
        destroyRecognizer()
    }

    fun stop() {
        stopped = true
        paused = true
        destroyRecognizer()
    }

    private fun startListening() {
        if (paused || stopped) return
        destroyRecognizer()
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            main.postDelayed({ startListening() }, 5000)
            return
        }
        recognizer = SpeechRecognizer.createSpeechRecognizer(context).also { sr ->
            sr.setRecognitionListener(listener)
            sr.startListening(listenIntent())
        }
    }

    private fun restartAfter(delay: Long) {
        destroyRecognizer()
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

    private fun listenIntent(): Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        if (Build.VERSION.SDK_INT >= 23) {
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false)
        }
    }
}
