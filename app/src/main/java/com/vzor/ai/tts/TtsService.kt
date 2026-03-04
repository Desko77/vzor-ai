package com.vzor.ai.tts

import android.content.Context
import android.speech.tts.TextToSpeech
import com.vzor.ai.data.local.PreferencesManager
import com.vzor.ai.domain.model.TtsProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * Text-to-Speech service supporting Google TTS and Yandex SpeechKit.
 * Synthesizes Russian speech from AI responses.
 */
@Singleton
class TtsService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val prefs: PreferencesManager
) {
    private var googleTts: TextToSpeech? = null
    private var isInitialized = false

    private suspend fun ensureGoogleTts(): TextToSpeech {
        if (googleTts != null && isInitialized) return googleTts!!

        return suspendCancellableCoroutine { cont ->
            googleTts = TextToSpeech(context) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    googleTts?.language = Locale("ru", "RU")
                    googleTts?.setSpeechRate(1.0f)
                    isInitialized = true
                    cont.resume(googleTts!!)
                } else {
                    cont.resume(googleTts!!)
                }
            }
        }
    }

    suspend fun speak(text: String) {
        when (prefs.ttsProvider.first()) {
            TtsProvider.GOOGLE -> speakWithGoogle(text)
            TtsProvider.YANDEX -> speakWithYandex(text)
        }
    }

    private suspend fun speakWithGoogle(text: String) {
        val tts = ensureGoogleTts()
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "vzor_utterance")
    }

    private suspend fun speakWithYandex(text: String) {
        val apiKey = prefs.yandexApiKey.first()
        if (apiKey.isBlank()) {
            // Fallback to Google TTS
            speakWithGoogle(text)
            return
        }

        // TODO: Implement Yandex SpeechKit API
        // POST https://tts.api.cloud.yandex.net/speech/v1/tts:synthesize
        // Headers: Authorization: Api-Key {apiKey}
        // Body: text={text}&lang=ru-RU&voice=alena&format=lpcm&sampleRateHertz=48000
        // Play resulting audio bytes

        // Fallback to Google TTS for now
        speakWithGoogle(text)
    }

    fun stop() {
        googleTts?.stop()
    }

    fun shutdown() {
        googleTts?.shutdown()
        googleTts = null
        isInitialized = false
    }
}
