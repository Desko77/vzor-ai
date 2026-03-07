package com.vzor.ai.speech

import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * On-device STT сервис для офлайн-режима.
 *
 * Использует Android SpeechRecognizer (если доступен offline language pack)
 * с fallback на запись аудио и обработку через on-device модель.
 *
 * Поддержка Whisper on-device через MLC LLM / ONNX Runtime планируется.
 * Текущая реализация: Android SpeechRecognizer с offline language model.
 *
 * Требования:
 * - Android 13+ для автоматического offline language pack
 * - Или ручная загрузка пакета для распознавания "ru-RU"
 */
@Singleton
class OfflineSttService @Inject constructor(
    @ApplicationContext private val context: Context
) : SttService {

    companion object {
        private const val TAG = "OfflineSttService"
        private const val SAMPLE_RATE = 16_000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val MAX_RECORDING_DURATION_MS = 30_000L
        private const val SILENCE_THRESHOLD = 500 // amplitude
        private const val SILENCE_TIMEOUT_MS = 2_000L
    }

    private val _isListening = AtomicBoolean(false)
    override val isListening: Boolean get() = _isListening.get()

    init {
        // Очищаем WAV файлы, оставшиеся после crash
        cleanupStaleWavFiles()
    }

    private fun cleanupStaleWavFiles() {
        try {
            context.cacheDir.listFiles()?.filter {
                it.name.startsWith("offline_stt_") && it.name.endsWith(".wav")
            }?.forEach { it.delete() }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to cleanup stale WAV files", e)
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var recognizer: SpeechRecognizer? = null

    /**
     * Распознавание через Android SpeechRecognizer с EXTRA_PREFER_OFFLINE.
     *
     * На Android 12+ использует on-device recognizer (если доступен).
     * На более старых версиях использует стандартный SpeechRecognizer
     * с флагом EXTRA_PREFER_OFFLINE=true.
     *
     * Fallback: запись WAV + ожидание on-device модели.
     */
    override fun startListening(): Flow<SttResult> = callbackFlow {
        if (!_isListening.compareAndSet(false, true)) {
            close()
            return@callbackFlow
        }

        Log.d(TAG, "Starting offline STT")

        val useOnDevice = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            SpeechRecognizer.isOnDeviceRecognitionAvailable(context)

        if (useOnDevice || SpeechRecognizer.isRecognitionAvailable(context)) {
            // Используем Android SpeechRecognizer
            try {
                val sr = if (useOnDevice && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
                } else {
                    SpeechRecognizer.createSpeechRecognizer(context)
                }
                recognizer = sr

                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                    if (!useOnDevice) {
                        putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                    }
                }

                sr.setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {
                        Log.d(TAG, "Ready for speech")
                    }

                    override fun onBeginningOfSpeech() {
                        Log.d(TAG, "Speech started")
                    }

                    override fun onRmsChanged(rmsdB: Float) {}

                    override fun onBufferReceived(buffer: ByteArray?) {}

                    override fun onEndOfSpeech() {
                        Log.d(TAG, "Speech ended")
                    }

                    override fun onError(error: Int) {
                        val errorMsg = when (error) {
                            SpeechRecognizer.ERROR_AUDIO -> "Audio error"
                            SpeechRecognizer.ERROR_NETWORK -> "Network error"
                            SpeechRecognizer.ERROR_NO_MATCH -> "No match"
                            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Speech timeout"
                            else -> "Error $error"
                        }
                        Log.w(TAG, "SpeechRecognizer error: $errorMsg")

                        // Fallback к записи WAV
                        if (error == SpeechRecognizer.ERROR_NO_MATCH ||
                            error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                            _isListening.set(false)
                            close()
                        } else {
                            // Для других ошибок пробуем WAV fallback
                            launch(Dispatchers.IO) {
                                val result = recordAndTranscribe()
                                if (result != null) {
                                    trySend(SttResult(text = result, isFinal = true, confidence = 0.6f))
                                }
                                _isListening.set(false)
                                close()
                            }
                        }
                    }

                    override fun onResults(results: Bundle?) {
                        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        val confidences = results?.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES)

                        if (!matches.isNullOrEmpty()) {
                            val text = matches[0]
                            val confidence = confidences?.firstOrNull() ?: 0.8f
                            Log.d(TAG, "Final result: $text (confidence=$confidence)")
                            trySend(SttResult(
                                text = text,
                                isFinal = true,
                                confidence = confidence,
                                language = "ru"
                            ))
                        }
                        _isListening.set(false)
                        close()
                    }

                    override fun onPartialResults(partialResults: Bundle?) {
                        val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        if (!matches.isNullOrEmpty() && matches[0].isNotBlank()) {
                            trySend(SttResult(
                                text = matches[0],
                                isFinal = false,
                                confidence = 0.5f,
                                language = "ru"
                            ))
                        }
                    }

                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })

                sr.startListening(intent)
            } catch (e: Exception) {
                Log.e(TAG, "SpeechRecognizer creation failed, falling back to WAV", e)
                // Fallback к записи WAV
                val result = withContext(Dispatchers.IO) { recordAndTranscribe() }
                if (result != null) {
                    trySend(SttResult(text = result, isFinal = true, confidence = 0.6f))
                }
                _isListening.set(false)
            }
        } else {
            // SpeechRecognizer недоступен — записываем WAV
            Log.w(TAG, "SpeechRecognizer not available, recording WAV")
            try {
                val result = withContext(Dispatchers.IO) { recordAndTranscribe() }
                if (result != null) {
                    trySend(SttResult(text = result, isFinal = true, confidence = 0.6f))
                }
            } catch (e: Exception) {
                Log.e(TAG, "Offline STT failed", e)
            } finally {
                _isListening.set(false)
            }
        }

        awaitClose {
            _isListening.set(false)
            destroyRecognizerOnMainThread()
        }
    }.flowOn(Dispatchers.Main)

    override fun stopListening() {
        _isListening.set(false)
        destroyRecognizerOnMainThread()
    }

    /**
     * Уничтожает SpeechRecognizer на Main thread (обязательное требование API).
     * Атомарно обнуляет ссылку для предотвращения double-destroy.
     */
    private fun destroyRecognizerOnMainThread() {
        val sr = recognizer ?: return
        recognizer = null
        val action = Runnable {
            try {
                sr.stopListening()
                sr.destroy()
            } catch (e: Exception) {
                Log.w(TAG, "Error destroying SpeechRecognizer", e)
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action.run()
        } else {
            mainHandler.post(action)
        }
    }

    /**
     * Записывает аудио и транскрибирует через Android SpeechRecognizer.
     *
     * Если SpeechRecognizer offline недоступен, записывает WAV файл
     * для последующей обработки через on-device модель.
     */
    @Suppress("MissingPermission")
    private suspend fun recordAndTranscribe(): String? {
        val bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        if (bufferSize == AudioRecord.ERROR_BAD_VALUE || bufferSize == AudioRecord.ERROR) {
            Log.e(TAG, "Invalid buffer size: $bufferSize")
            return null
        }

        val audioRecord = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufferSize * 2
            )
        } catch (e: SecurityException) {
            Log.e(TAG, "Microphone permission denied", e)
            return null
        }

        if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord initialization failed")
            audioRecord.release()
            return null
        }

        // Записываем аудио в WAV файл для обработки
        val wavFile = File(context.cacheDir, "offline_stt_${System.currentTimeMillis()}.wav")
        val audioData = mutableListOf<ByteArray>()
        var totalBytes = 0

        try {
            audioRecord.startRecording()
            val buffer = ByteArray(bufferSize)
            val startTime = System.currentTimeMillis()
            var lastSoundTime = startTime

            while (_isListening.get()) {
                val elapsed = System.currentTimeMillis() - startTime
                if (elapsed > MAX_RECORDING_DURATION_MS) break

                val bytesRead = audioRecord.read(buffer, 0, buffer.size)
                if (bytesRead > 0) {
                    val chunk = buffer.copyOf(bytesRead)
                    audioData.add(chunk)
                    totalBytes += bytesRead

                    // Проверяем тишину для автоматической остановки
                    val maxAmplitude = getMaxAmplitude(chunk)
                    if (maxAmplitude > SILENCE_THRESHOLD) {
                        lastSoundTime = System.currentTimeMillis()
                    } else if (System.currentTimeMillis() - lastSoundTime > SILENCE_TIMEOUT_MS) {
                        Log.d(TAG, "Silence detected, stopping recording")
                        break
                    }
                }
            }

            audioRecord.stop()

            if (totalBytes == 0) {
                Log.w(TAG, "No audio data recorded")
                return null
            }

            // Сохраняем WAV для обработки
            writeWavFile(wavFile, audioData, totalBytes)
            Log.d(TAG, "Recorded ${totalBytes} bytes to ${wavFile.absolutePath}")

            // WAV записан — SpeechRecognizer fallback (ONNX Whisper в будущем)
            return transcribeFromWav(wavFile)

        } finally {
            audioRecord.release()
            wavFile.delete()
        }
    }

    /**
     * Fallback транскрипция: WAV файл сохранён, но SpeechRecognizer недоступен.
     * Логирует путь к файлу для отладки. В будущем — ONNX Runtime Whisper.
     */
    private fun transcribeFromWav(wavFile: File): String? {
        Log.w(TAG, "WAV fallback: SpeechRecognizer unavailable, file saved: ${wavFile.length()} bytes")
        return null
    }

    /**
     * Записывает PCM данные в WAV файл.
     */
    private fun writeWavFile(file: File, audioData: List<ByteArray>, totalBytes: Int) {
        RandomAccessFile(file, "rw").use { raf ->
            // WAV header (44 bytes)
            val channels = 1
            val bitsPerSample = 16
            val byteRate = SAMPLE_RATE * channels * bitsPerSample / 8

            raf.writeBytes("RIFF")
            raf.writeInt(Integer.reverseBytes(36 + totalBytes))
            raf.writeBytes("WAVE")
            raf.writeBytes("fmt ")
            raf.writeInt(Integer.reverseBytes(16)) // PCM chunk size
            raf.writeShort(java.lang.Short.reverseBytes(1).toInt()) // PCM format
            raf.writeShort(java.lang.Short.reverseBytes(channels.toShort()).toInt())
            raf.writeInt(Integer.reverseBytes(SAMPLE_RATE))
            raf.writeInt(Integer.reverseBytes(byteRate))
            raf.writeShort(java.lang.Short.reverseBytes((channels * bitsPerSample / 8).toShort()).toInt())
            raf.writeShort(java.lang.Short.reverseBytes(bitsPerSample.toShort()).toInt())
            raf.writeBytes("data")
            raf.writeInt(Integer.reverseBytes(totalBytes))

            // Audio data
            for (chunk in audioData) {
                raf.write(chunk)
            }
        }
    }

    /**
     * Вычисляет максимальную амплитуду в PCM 16-bit буфере.
     */
    private fun getMaxAmplitude(buffer: ByteArray): Int {
        var max = 0
        var i = 0
        while (i < buffer.size - 1) {
            val sample = (buffer[i + 1].toInt() shl 8) or (buffer[i].toInt() and 0xFF)
            val abs = kotlin.math.abs(sample)
            if (abs > max) max = abs
            i += 2
        }
        return max
    }
}
