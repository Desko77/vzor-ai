package com.vzor.ai.domain.model

data class RoutingContext(
    val networkType: NetworkType,
    val batteryLevel: Int,          // 0-100
    val x2Available: Boolean,       // Local AI server reachable
    val x2QueueWaitMs: Long,        // Current queue wait time on EVO X2
    val latencyBudgetMs: Long = 2000 // Max acceptable latency
)

enum class NetworkType {
    WIFI,
    LTE,
    OFFLINE
}

enum class RoutingDecision {
    LOCAL_AI,       // EVO X2 via Wi-Fi
    CLOUD,          // Claude/GPT-4o/Gemini/GLM-5 via LTE
    OFFLINE         // On-device Qwen3.5-4B
}
