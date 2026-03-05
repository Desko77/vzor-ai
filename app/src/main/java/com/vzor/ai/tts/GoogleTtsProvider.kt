package com.vzor.ai.tts

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * Google TTS provider — uses Android built-in TextToSpeech engine.
 * Primary use: English speech and fallback for when Yandex is unavailable.
 */
@Singleton
class GoogleTtsProvider @Inject constructor(
    @ApplicationContext private val context: Context
) : TtsProvider {

    companion object {
        private const val TAG = "GoogleTtsProvider"
    }

    private var tts: TextToSpeech? = null
    private val isInitialized = AtomicBoolean(false)
    private val isInitializing = AtomicBoolean(false)

    /**
     * Ensure the TextToSpeech engine is initialized.
     * Suspends until initialization is complete.
     */
    private suspend fun ensureTts(): TextToSpeech? {
        if (isInitialized.get() && tts != null) return tts

        if (isInitializing.compareAndSet(false, true)) {
            return suspendCancellableCoroutine { cont ->
                tts = TextToSpeech(context) { status ->
                    if (status == TextToSpeech.SUCCESS) {
                        isInitialized.set(true)
                        Log.d(TAG, "Google TTS initialized successfully")
                        cont.resume(tts)
                    } else {
                        Log.e(TAG, "Google TTS initialization failed with status: $status")
                        isInitializing.set(false)
                        cont.resume(null)
                    }
                }

                cont.invokeOnCancellation {
                    isInitializing.set(false)
                }
            }
        }

        // Another coroutine is initializing, wait briefly
        var attempts = 0
        while (!isInitialized.get() && attempts < 50) {
            kotlinx.coroutines.delay(50)
            attempts++
        }
        return if (isInitialized.get()) tts else null
    }

    /**
     * Synthesize text using Android TextToSpeech engine.
     * This plays audio directly through the device speaker / Bluetooth.
     *
     * Note: Android TTS doesn't easily return raw bytes, so this returns null.
     * Use [speakDirect] for direct playback.
     *
     * @param text Text to synthesize.
     * @param voice Not used for Google TTS (uses system default).
     * @param lang Language code (e.g. "en-US", "ru-RU").
     * @return null — Google TTS plays audio directly, doesn't return bytes.
     */
    override suspend fun synthesize(text: String, voice: String, lang: String): ByteArray? {
        speakDirect(text, lang)
        return null
    }

    /**
     * Speak text directly through Android TextToSpeech.
     * Suspends until utterance is complete.
     *
     * @param text Text to speak.
     * @param lang Language code (e.g. "en-US", "ru-RU").
     */
    suspend fun speakDirect(text: String, lang: String = "en-US"): Unit =
        suspendCancellableCoroutine { cont ->
            val engine = tts
            if (engine == null || !isInitialized.get()) {
                Log.w(TAG, "TTS not initialized, cannot speak")
                cont.resume(Unit)
                return@suspendCancellableCoroutine
            }

            // Set language
            val locale = parseLocale(lang)
            val langResult = engine.setLanguage(locale)
            if (langResult == TextToSpeech.LANG_MISSING_DATA ||
                langResult == TextToSpeech.LANG_NOT_SUPPORTED
            ) {
                Log.w(TAG, "Language $lang not supported, falling back to default")
            }

            engine.setSpeechRate(1.0f)
            engine.setPitch(1.0f)

            val utteranceId = UUID.randomUUID().toString()

            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) {}

                override fun onDone(id: String?) {
                    if (id == utteranceId) {
                        cont.resume(Unit)
                    }
                }

                @Deprecated("Deprecated in API")
                override fun onError(id: String?) {
                    if (id == utteranceId) {
                        Log.e(TAG, "TTS utterance error for: $id")
                        cont.resume(Unit)
                    }
                }

                override fun onError(id: String?, errorCode: Int) {
                    if (id == utteranceId) {
                        Log.e(TAG, "TTS utterance error $errorCode for: $id")
                        cont.resume(Unit)
                    }
                }
            })

            val params = Bundle().apply {
                putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId)
            }

            engine.speak(text, TextToSpeech.QUEUE_FLUSH, params, utteranceId)

            cont.invokeOnCancellation {
                engine.stop()
            }
        }

    override fun stop() {
        tts?.stop()
    }

    /**
     * Shut down the TextToSpeech engine and release resources.
     */
    fun shutdown() {
        tts?.shutdown()
        tts = null
        isInitialized.set(false)
        isInitializing.set(false)
    }

    /**
     * Initialize the TTS engine eagerly (e.g., at app startup).
     */
    suspend fun initialize() {
        ensureTts()
    }

    private fun parseLocale(lang: String): Locale {
        val parts = lang.split("-", "_")
        return when (parts.size) {
            1 -> Locale(parts[0])
            2 -> Locale(parts[0], parts[1])
            else -> Locale(parts[0], parts[1], parts[2])
        }
    }
}
