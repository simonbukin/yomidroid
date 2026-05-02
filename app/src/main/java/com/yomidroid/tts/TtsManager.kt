package com.yomidroid.tts

import android.content.Context
import android.speech.tts.TextToSpeech
import android.util.Log
import android.widget.Toast
import java.util.Locale

/**
 * Simple TTS manager that uses Android's system TextToSpeech API.
 * The user configures their preferred TTS engine externally (e.g. Sherpa-ONNX VOICEVOX).
 *
 * Requires `<queries><intent><action android:name="android.intent.action.TTS_SERVICE"/></intent></queries>`
 * in AndroidManifest.xml on Android 11+ for the engine to be visible to the app.
 */
class TtsManager private constructor(private val appContext: Context) : TextToSpeech.OnInitListener {

    companion object {
        private const val TAG = "TtsManager"

        @Volatile
        private var INSTANCE: TtsManager? = null

        fun getInstance(context: Context): TtsManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: TtsManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    private val tts = TextToSpeech(appContext, this)

    @Volatile private var isReady = false
    @Volatile private var lastErrorMessage: String? = "TTS is still initializing"

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) {
            lastErrorMessage = "TTS engine init failed (status=$status). " +
                    "Set a TTS engine as system default in Settings > System > Languages > Text-to-speech."
            Log.e(TAG, lastErrorMessage!!)
            return
        }

        Log.d(TAG, "TTS engine: ${tts.defaultEngine}")
        runCatching {
            val available = tts.availableLanguages?.joinToString { "${it.language}-${it.country}" }
            Log.d(TAG, "Available languages: $available")
            val voices = tts.voices?.joinToString {
                "${it.name}(${it.locale.language}-${it.locale.country})"
            }
            Log.d(TAG, "Voices: $voices")
        }

        // Try ja-JP first (most engines register the country variant), then language-only ja.
        val candidates = listOf(Locale.JAPAN, Locale.JAPANESE, Locale("ja", "JP"))
        var bestResult = TextToSpeech.LANG_NOT_SUPPORTED
        for (locale in candidates) {
            val r = tts.setLanguage(locale)
            Log.d(TAG, "setLanguage($locale) -> $r")
            if (r == TextToSpeech.LANG_AVAILABLE ||
                r == TextToSpeech.LANG_COUNTRY_AVAILABLE ||
                r == TextToSpeech.LANG_COUNTRY_VAR_AVAILABLE) {
                isReady = true
                lastErrorMessage = null
                Log.d(TAG, "TTS ready with locale=$locale (result=$r)")
                return
            }
            bestResult = maxOf(bestResult, r)
        }

        // Last-resort fallback: pick any voice whose locale.language == "ja".
        val jaVoice = runCatching {
            tts.voices?.firstOrNull { it.locale.language == "ja" || it.locale.isO3Language == "jpn" }
        }.getOrNull()
        if (jaVoice != null) {
            val voiceResult = tts.setVoice(jaVoice)
            Log.d(TAG, "setVoice(${jaVoice.name}) -> $voiceResult")
            if (voiceResult == TextToSpeech.SUCCESS) {
                isReady = true
                lastErrorMessage = null
                Log.d(TAG, "TTS ready via voice ${jaVoice.name}")
                return
            }
        }

        lastErrorMessage = when (bestResult) {
            TextToSpeech.LANG_MISSING_DATA ->
                "Japanese voice data missing for engine '${tts.defaultEngine}'. Install it from the engine's settings."
            else ->
                "Engine '${tts.defaultEngine}' does not advertise Japanese. " +
                        "It may need a manifest <meta-data android:name=\"android.speech.tts\"> declaring locale jpn-JPN, " +
                        "or it isn't returning LANG_AVAILABLE from onIsLanguageAvailable."
        }
        Log.w(TAG, lastErrorMessage!!)
    }

    /**
     * Speak [text]. If [showErrorToast] is true and TTS isn't ready, surface the reason
     * to the user as a Toast (use this for direct user-tap entry points).
     */
    fun speak(text: String, showErrorToast: Boolean = false) {
        if (text.isBlank()) return
        if (!isReady) {
            val msg = lastErrorMessage ?: "TTS not ready"
            Log.w(TAG, "Ignoring speak: $msg")
            if (showErrorToast) {
                Toast.makeText(appContext, msg, Toast.LENGTH_LONG).show()
            }
            return
        }
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "yomidroid_tts")
    }

    fun stop() {
        tts.stop()
    }
}
