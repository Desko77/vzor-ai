package com.vzor.ai.vision

import com.vzor.ai.domain.repository.VisionRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * High-level vision service that combines glasses camera capture
 * with AI image analysis. Used for "what do you see?" commands.
 */
@Singleton
class VisionService @Inject constructor(
    private val visionRepository: VisionRepository
) {
    suspend fun analyzePhoto(
        imageBytes: ByteArray,
        prompt: String = "Опиши подробно что ты видишь на этом изображении. Отвечай на русском языке."
    ): Result<String> {
        return visionRepository.analyzeImage(imageBytes, prompt)
    }

    suspend fun readText(imageBytes: ByteArray): Result<String> {
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
