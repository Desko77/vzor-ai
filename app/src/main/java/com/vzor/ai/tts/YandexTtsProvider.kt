package com.vzor.ai.tts

import android.util.Log
import com.vzor.ai.data.local.PreferencesManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Yandex SpeechKit TTS provider — primary Russian voice synthesis.
 *
 * Uses Yandex SpeechKit REST API:
 * POST https://tts.api.cloud.yandex.net/speech/v1/tts:synthesize
 * Authorization: Api-Key {yandexApiKey}
 * Body: text={text}&lang=ru-RU&voice=alena&format=lpcm&sampleRateHertz=48000
 * Returns: audio/x-pcm bytes (raw PCM 48kHz 16-bit mono)
 */
@Singleton
class YandexTtsProvider @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val prefs: PreferencesManager
) : TtsProvider {

    companion object {
        private const val TAG = "YandexTtsProvider"
        private const val YANDEX_TTS_URL =
            "https://tts.api.cloud.yandex.net/speech/v1/tts:synthesize"

        /** Default voice for Russian — Alena (neutral, female) */
        const val DEFAULT_VOICE = "alena"

        /** Default sample rate for output audio */
        const val SAMPLE_RATE = 48000
    }

    /**
     * Synthesize text to audio bytes using Yandex SpeechKit.
     *
     * @param text Text to synthesize (max 5000 characters per request).
     * @param voice Yandex voice identifier (alena, filipp, ermil, jane, madirus, omazh, zahar).
     * @param lang Language code: "ru-RU", "en-US", "tr-TR".
     * @return Raw PCM audio bytes (48kHz 16-bit mono), or null on failure.
     */
    override suspend fun synthesize(
        text: String,
        voice: String,
        lang: String
    ): ByteArray? = withContext(Dispatchers.IO) {
        val apiKey = prefs.yandexApiKey.first()
        if (apiKey.isBlank()) {
            Log.w(TAG, "Yandex API key is not configured")
            return@withContext null
        }

        if (text.isBlank()) {
            return@withContext null
        }

        val effectiveVoice = voice.ifBlank { DEFAULT_VOICE }

        try {
            val formBody = FormBody.Builder()
                .add("text", text)
                .add("lang", lang)
                .add("voice", effectiveVoice)
                .add("format", "lpcm")
                .add("sampleRateHertz", SAMPLE_RATE.toString())
                .add("emotion", "neutral")
                .build()

            val request = Request.Builder()
                .url(YANDEX_TTS_URL)
                .addHeader("Authorization", "Api-Key $apiKey")
                .post(formBody)
                .build()

            val response = okHttpClient.newCall(request).execute()

            if (!response.isSuccessful) {
                val errorBody = response.body?.string() ?: "Unknown error"
                Log.e(TAG, "Yandex TTS error ${response.code}: $errorBody")
                return@withContext null
            }

            response.body?.bytes()
        } catch (e: Exception) {
            Log.e(TAG, "Yandex TTS synthesis failed", e)
            null
        }
    }

    /**
     * Synthesize text as a stream of audio chunks.
     * Splits long text into sentences and synthesizes each one individually,
     * emitting audio chunks as they become available.
     *
     * @param text Full text to synthesize.
     * @return Flow of PCM audio byte arrays.
     */
    fun synthesizeStream(text: String): Flow<ByteArray> = flow {
        // Split text into sentences for streaming synthesis
        val sentences = splitIntoSentences(text)

        for (sentence in sentences) {
            if (sentence.isBlank()) continue
            val audio = synthesize(sentence)
            if (audio != null) {
                emit(audio)
            }
        }
    }.flowOn(Dispatchers.IO)

    override fun stop() {
        // OkHttp calls are cancelled via coroutine cancellation
    }

    /**
     * Split text into sentence-sized chunks for streaming synthesis.
     */
    private fun splitIntoSentences(text: String): List<String> {
        // Split on sentence-ending punctuation, keeping the punctuation
        val sentences = mutableListOf<String>()
        val current = StringBuilder()

        for (char in text) {
            current.append(char)
            if (char in charArrayOf('.', '!', '?', ';') && current.length > 1) {
                sentences.add(current.toString().trim())
                current.clear()
            }
        }

        // Add remaining text
        val remaining = current.toString().trim()
        if (remaining.isNotEmpty()) {
            sentences.add(remaining)
        }

        return sentences
    }
}
