package com.vzor.ai.orchestrator

import com.vzor.ai.domain.model.Message
import com.vzor.ai.domain.model.RoutingContext
import java.util.UUID

data class ConversationSession(
    val sessionId: String = UUID.randomUUID().toString(),
    val startedAt: Long = System.currentTimeMillis(),
    val messages: MutableList<Message> = mutableListOf(),
    val activeSceneId: String? = null,
    val pendingToolCalls: MutableList<ToolCall> = mutableListOf(),
    val routingContext: RoutingContext? = null
)

data class ToolCall(
    val id: String,
    val name: String,
    val arguments: Map<String, String>,
    val status: ToolCallStatus = ToolCallStatus.PENDING
)

enum class ToolCallStatus {
    PENDING,
    EXECUTING,
    COMPLETED,
    FAILED
}
