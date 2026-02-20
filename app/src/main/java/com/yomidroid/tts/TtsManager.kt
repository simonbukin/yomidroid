package com.yomidroid.tts

import android.content.Context
import android.speech.tts.TextToSpeech
import android.util.Log
import java.util.Locale

/**
 * Simple TTS manager that uses Android's system TextToSpeech API.
 * The user configures their preferred TTS engine externally (e.g. Sherpa-ONNX VOICEVOX).
 */
class TtsManager(context: Context) : TextToSpeech.OnInitListener {

    companion object {
        private const val TAG = "TtsManager"
    }

    private val tts = TextToSpeech(context.applicationContext, this)
    private var isReady = false

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts.setLanguage(Locale.JAPANESE)
            isReady = result != TextToSpeech.LANG_MISSING_DATA &&
                    result != TextToSpeech.LANG_NOT_SUPPORTED
            if (!isReady) {
                Log.w(TAG, "Japanese TTS not available (result=$result)")
            } else {
                Log.d(TAG, "TTS ready, engine: ${tts.defaultEngine}")
            }
        } else {
            Log.e(TAG, "TTS init failed with status $status")
        }
    }

    fun speak(text: String) {
        if (!isReady) {
            Log.w(TAG, "TTS not ready, ignoring speak request")
            return
        }
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "yomidroid_tts")
    }

    fun stop() {
        tts.stop()
    }

    fun shutdown() {
        tts.stop()
        tts.shutdown()
    }
}
