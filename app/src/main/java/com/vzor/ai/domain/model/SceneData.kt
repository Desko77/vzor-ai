package com.vzor.ai.domain.model

data class SceneData(
    val sceneId: String,
    val timestamp: Long,
    val sceneSummary: String,       // English summary from VLM
    val objects: List<DetectedObject> = emptyList(),
    val text: List<String> = emptyList(),  // OCR text
    val stability: Float = 0f,      // 0-1, scene stability score
    val ttlMs: Long = 5000          // Time-to-live
) {
    fun isExpired(): Boolean = System.currentTimeMillis() - timestamp > ttlMs
}

data class DetectedObject(
    val label: String,
    val confidence: Float,
    val boundingBox: BoundingBox? = null
)

data class BoundingBox(
    val x: Float, val y: Float,
    val width: Float, val height: Float
)
