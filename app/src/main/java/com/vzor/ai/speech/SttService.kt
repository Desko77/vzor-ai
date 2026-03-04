package com.vzor.ai.speech

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.vzor.ai.data.local.PreferencesManager
import com.vzor.ai.data.remote.OpenAiApiService
import com.vzor.ai.domain.model.SttProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext

/**
 * Speech-to-Text service supporting Whisper API and Google STT.
 * Records audio from device microphone (or glasses via GlassesManager)
 * and transcribes to Russian text.
 */
@Singleton
class SttService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val openAiApi: OpenAiApiService,
    private val prefs: PreferencesManager
) {
    @Volatile
    private var isListening = false

    private val sampleRate = 16000
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT

    fun startListening(): Flow<String> = flow {
        isListening = true

        when (prefs.sttProvider.first()) {
            SttProvider.WHISPER -> {
                val audioData = recordAudio()
                if (audioData.isNotEmpty()) {
                    val text = transcribeWithWhisper(audioData)
                    emit(text)
                }
            }
            SttProvider.GOOGLE -> {
                // Google STT via Android SpeechRecognizer
                // Uses on-device recognition when available
                val audioData = recordAudio()
                if (audioData.isNotEmpty()) {
                    // Fallback to Whisper for now
                    val text = transcribeWithWhisper(audioData)
                    emit(text)
                }
            }
        }
    }.flowOn(Dispatchers.IO)

    fun stopListening() {
        isListening = false
    }

    private suspend fun recordAudio(): ByteArray {
        val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
        val audioRecord = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                channelConfig,
                audioFormat,
                bufferSize
            )
        } catch (e: SecurityException) {
            return ByteArray(0)
        }

        val outputStream = ByteArrayOutputStream()
        val buffer = ByteArray(bufferSize)

        try {
            audioRecord.startRecording()
            while (isListening && coroutineContext.isActive) {
                val read = audioRecord.read(buffer, 0, buffer.size)
                if (read > 0) {
                    outputStream.write(buffer, 0, read)
                }
            }
        } finally {
            audioRecord.stop()
            audioRecord.release()
        }

        return outputStream.toByteArray()
    }

    private suspend fun transcribeWithWhisper(audioData: ByteArray): String {
        val apiKey = prefs.openAiApiKey.first()
        if (apiKey.isBlank()) throw IllegalStateException("OpenAI API ключ не указан для Whisper")

        val audioBody = audioData.toRequestBody("audio/wav".toMediaTypeOrNull())
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
