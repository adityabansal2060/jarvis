package com.jarvis.assistant

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import java.util.UUID

/**
 * Android Native Speech Engine for JARVIS
 * Supports:
 * - Natural vocalization with British/Indian English/Hindi accent
 * - Instant Barge-In / Force-Stopping
 * - Pitch and rate tuning
 */
class SpeechEngine(context: Context, onReady: (() -> Unit)? = null) : TextToSpeech.OnInitListener {

    private var tts: TextToSpeech? = TextToSpeech(context.applicationContext, this)
    private var isReady = false

    var pitch: Float = 0.95f
    var rate: Float = 1.05f
    var isMuted: Boolean = false

    private var onSpeechStart: (() -> Unit)? = null
    private var onSpeechDone: (() -> Unit)? = null

    init {
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                onSpeechStart?.invoke()
            }

            override fun onDone(utteranceId: String?) {
                onSpeechDone?.invoke()
            }

            override fun onError(utteranceId: String?) {
                onSpeechDone?.invoke()
            }
        })
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            isReady = true
            // Attempt British English first for classic Jarvis, fallback to default English
            val result = tts?.setLanguage(Locale.UK)
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                tts?.setLanguage(Locale.US)
            }
            tts?.setPitch(pitch)
            tts?.setSpeechRate(rate)
        }
    }

    fun speak(
        text: String,
        onStart: (() -> Unit)? = null,
        onDone: (() -> Unit)? = null
    ) {
        if (isMuted || !isReady || tts == null) {
            onDone?.invoke()
            return
        }

        // Clean markdown characters (asterisks, hashtags, backticks, brackets)
        val clean = text
            .replace(Regex("[*#`_~>]"), "")
            .replace(Regex("\\[(.*?)\\]\\(.*?\\)"), "$1")
            .trim()

        if (clean.isEmpty()) {
            onDone?.invoke()
            return
        }

        this.onSpeechStart = onStart
        this.onSpeechDone = onDone

        tts?.setPitch(pitch)
        tts?.setSpeechRate(rate)

        val utteranceId = UUID.randomUUID().toString()
        tts?.speak(clean, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
    }

    /**
     * Instantly stops any vocalization (Force Stop / Barge-In)
     */
    fun stop() {
        try {
            if (tts?.isSpeaking == true) {
                tts?.stop()
            }
            onSpeechDone?.invoke()
        } catch (_: Exception) {}
    }

    fun isSpeaking(): Boolean {
        return tts?.isSpeaking == true
    }

    fun shutdown() {
        try {
            tts?.stop()
            tts?.shutdown()
            tts = null
            isReady = false
        } catch (_: Exception) {}
    }
}
