package com.vzor.ai.domain.repository

interface VisionRepository {
    suspend fun analyzeImage(imageBytes: ByteArray, prompt: String): Result<String>
}
