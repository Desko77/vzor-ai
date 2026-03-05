package com.vzor.ai.domain.model

import java.util.UUID

data class Message(
    val id: String = UUID.randomUUID().toString(),
    val role: MessageRole,
    val content: String,
    val imageData: ByteArray? = null,
    val timestamp: Long = System.currentTimeMillis(),
    val conversationId: String = ""
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Message) return false
        return id == other.id
    }

    override fun hashCode(): Int = id.hashCode()
}

enum class MessageRole {
    USER,
    ASSISTANT,
    SYSTEM
}
