package com.vzor.ai.speech

import android.content.Context
import android.util.Log
import com.vzor.ai.data.local.PreferencesManager
import com.vzor.ai.data.remote.OpenAiApiService
import com.vzor.ai.glasses.AudioStreamHandler
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Batch STT via OpenAI Whisper API.
 * Used for home Wi-Fi and offline scenarios.
 *
 * Records audio from AudioStreamHandler, waits for speech to end (VAD detection),
 * then sends the complete audio batch to Whisper API for transcription.
 * Returns SttResult with isFinal=true once transcription is complete.
 * Language: "ru" (Russian), Audio format: WAV PCM 16kHz.
 */
@Singleton
class WhisperSttService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val openAiApi: OpenAiApiService,
    private val prefs: PreferencesManager,
    private val audioStreamHandler: AudioStreamHandler
) : SttService {

    companion object {
        private const val TAG = "WhisperSttService"
    }

    @Volatile
    private var _isListening = false

    override val isListening: Boolean
        get() = _isListening

    override fun startListening(): Flow<SttResult> = flow {
        _isListening = true

        try {
            // Record audio until VAD detects end of speech
            val pcmData = audioStreamHandler.recordUntilSilence()

            if (!_isListening) {
                // Stopped externally
                return@flow
            }

            if (pcmData.isEmpty()) {
                Log.w(TAG, "No audio data recorded")
                return@flow
            }

            // Convert PCM to WAV format for Whisper API
            val wavData = audioStreamHandler.pcmToWav(pcmData)

            // Send to Whisper API for transcription
            val text = transcribeWithWhisper(wavData)
            if (text.isNotBlank()) {
                emit(
                    SttResult(
                        text = text,
                        isFinal = true,
                        confidence = 1.0f,
                        language = "ru"
                    )
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Whisper STT error", e)
            throw e
        } finally {
            _isListening = false
        }
    }.flowOn(Dispatchers.IO)

    override fun stopListening() {
        _isListening = false
        audioStreamHandler.stop()
    }

    /**
     * Send WAV audio data to OpenAI Whisper API for transcription.
     */
    private suspend fun transcribeWithWhisper(wavData: ByteArray): String {
        val apiKey = prefs.openAiApiKey.first()
        if (apiKey.isBlank()) {
            throw IllegalStateException("OpenAI API ключ не указан для Whisper")
        }

        val audioBody = wavData.toRequestBody("audio/wav".toMediaTypeOrNull())
        val audioPart = MultipartBody.Part.createFormData("file", "audio.wav", audioBody)
        val modelBody = "whisper-1".toRequestBody("text/plain".toMediaTypeOrNull())
        val languageBody = "ru".toRequestBody("text/plain".toMediaTypeOrNull())

        val response = openAiApi.transcribeAudio(
            auth = "Bearer $apiKey",
            file = audioPart,
            model = modelBody,
            language = languageBody
        )

        return response.text
    }
}
