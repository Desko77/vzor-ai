package com.vzor.ai.domain.model

enum class NoiseProfile(
    val vadThreshold: Float,
    val sttConfidenceMin: Float,
    val ttsVolumeBoost: Float
) {
    QUIET(0.3f, 0.7f, 0.0f),       // < 40 dB
    INDOOR(0.5f, 0.75f, 0.0f),     // 40-60 dB
    OUTDOOR(0.7f, 0.8f, 0.2f),     // 60-75 dB
    LOUD(0.85f, 0.9f, 0.35f);      // > 75 dB

    companion object {
        fun fromDbLevel(db: Float): NoiseProfile = when {
            db < 40f -> QUIET
            db < 60f -> INDOOR
            db < 75f -> OUTDOOR
            else -> LOUD
        }
    }
}
