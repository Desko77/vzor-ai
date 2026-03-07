package com.vzor.ai.speech

import ai.picovoice.porcupine.Porcupine
import ai.picovoice.porcupine.PorcupineException
import android.content.Context
import android.util.Log

/**
 * Wake word engine на основе Picovoice Porcupine SDK.
 *
 * Обнаруживает кастомный wake word "Взор" с высокой точностью:
 * - False accept rate: < 1 в 10^6 (по документации Picovoice)
 * - Латентность: < 20ms на Snapdragon 8 Elite
 * - Поддержка кастомных keyword моделей (.ppn файлы)
 *
 * Требует:
 * - Picovoice Access Key (бесплатный tier: 3 месяца)
 * - Keyword файл "vzor_ru.ppn" в assets (генерируется в Picovoice Console)
 *
 * Если keyword файл отсутствует — использует встроенный keyword "computer"
 * как placeholder для тестирования pipeline.
 *
 * Frame size: Porcupine.getFrameLength() (обычно 512 samples при 16kHz = 32ms).
 */
class PorcupineWakeWordEngine(
    private val context: Context,
    private val accessKey: String,
    private val keywordPath: String? = null,
    private val sensitivity: Float = 0.7f
) : WakeWordEngine {

    companion object {
        private const val TAG = "PorcupineWakeWordEngine"

        /** Путь к кастомному keyword файлу в assets. */
        const val DEFAULT_KEYWORD_ASSET = "vzor_ru.ppn"
    }

    private var porcupine: Porcupine? = null
    private var _isReady = false

    init {
        try {
            val builder = Porcupine.Builder()
                .setAccessKey(accessKey)
                .setSensitivities(floatArrayOf(sensitivity))

            if (keywordPath != null) {
                // Кастомный keyword из файловой системы
                builder.setKeywordPaths(arrayOf(keywordPath))
            } else {
                // Попробуем загрузить из assets
                try {
                    builder.setKeywordPaths(arrayOf(getAssetKeywordPath()))
                } catch (e: Exception) {
                    // Assets не найден — используем встроенный keyword "computer" как placeholder
                    Log.w(TAG, "Custom keyword not found, using built-in 'computer' as placeholder")
                    builder.setKeywords(arrayOf(Porcupine.BuiltInKeyword.COMPUTER))
                }
            }

            porcupine = builder.build(context)
            _isReady = true
            Log.d(TAG, "Porcupine initialized (frameLength=${porcupine?.frameLength}, " +
                "sampleRate=${porcupine?.sampleRate})")
        } catch (e: PorcupineException) {
            Log.e(TAG, "Failed to initialize Porcupine: ${e.message}", e)
            _isReady = false
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error initializing Porcupine", e)
            _isReady = false
        }
    }

    /**
     * Размер аудио фрейма в samples, ожидаемый Porcupine.
     * Обычно 512 samples (32ms при 16kHz).
     */
    val frameLength: Int
        get() = porcupine?.frameLength ?: 512

    override fun process(pcmData: ShortArray): Boolean {
        val engine = porcupine ?: return false

        return try {
            val keywordIndex = engine.process(pcmData)
            if (keywordIndex >= 0) {
                Log.i(TAG, "Wake word detected! (keywordIndex=$keywordIndex)")
                true
            } else {
                false
            }
        } catch (e: PorcupineException) {
            Log.e(TAG, "Porcupine process error: ${e.message}")
            false
        }
    }

    override fun release() {
        try {
            porcupine?.delete()
            porcupine = null
            _isReady = false
            Log.d(TAG, "Porcupine released")
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing Porcupine", e)
        }
    }

    override fun isReady(): Boolean = _isReady

    /**
     * Возвращает путь к keyword файлу из assets.
     * Picovoice SDK требует путь на файловой системе — копируем из assets при необходимости.
     */
    private fun getAssetKeywordPath(): String {
        val assetDir = context.filesDir.resolve("porcupine")
        assetDir.mkdirs()
        val keywordFile = assetDir.resolve(DEFAULT_KEYWORD_ASSET)

        if (!keywordFile.exists()) {
            context.assets.open(DEFAULT_KEYWORD_ASSET).use { input ->
                keywordFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
        }

        return keywordFile.absolutePath
    }
}
