package com.vzor.ai.vision

import android.util.Log
import com.vzor.ai.domain.repository.VisionRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * High-level vision service that combines glasses camera capture
 * with AI image analysis. Used for "what do you see?" commands.
 * Uses on-device OCR (ML Kit) for text queries before falling back to cloud VLM.
 */
@Singleton
class VisionService @Inject constructor(
    private val visionRepository: VisionRepository,
    private val onDeviceProcessor: OnDeviceVisionProcessor
) {
    companion object {
        private const val TAG = "VisionService"
    }

    suspend fun analyzePhoto(
        imageBytes: ByteArray,
        prompt: String = "Опиши подробно что ты видишь на этом изображении. Отвечай на русском языке."
    ): Result<String> {
        return visionRepository.analyzeImage(imageBytes, prompt)
    }

    suspend fun readText(imageBytes: ByteArray): Result<String> {
        // Try on-device OCR first for fast, offline text recognition
        try {
            val ocrResult = onDeviceProcessor.recognizeText(imageBytes)
            if (ocrResult != null && ocrResult.fullText.isNotBlank() &&
                ocrResult.confidence >= OnDeviceVisionProcessor.MIN_CONFIDENCE) {
                Log.d(TAG, "On-device OCR succeeded: ${ocrResult.blocks.size} blocks, confidence=${ocrResult.confidence}")
                return Result.success(ocrResult.fullText)
            }
        } catch (e: Exception) {
            Log.w(TAG, "On-device OCR failed, falling back to cloud VLM", e)
        }

        // Fall back to cloud VLM
        return visionRepository.analyzeImage(
            imageBytes,
            "Прочитай весь текст на изображении. Если текст на иностранном языке, переведи на русский."
        )
    }

    suspend fun identifyObject(imageBytes: ByteArray): Result<String> {
        return visionRepository.analyzeImage(
            imageBytes,
            "Определи главный объект на изображении и дай подробную информацию о нём на русском языке."
        )
    }
}
