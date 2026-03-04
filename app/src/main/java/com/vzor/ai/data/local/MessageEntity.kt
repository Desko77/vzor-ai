package com.vzor.ai.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.vzor.ai.domain.model.Message
import com.vzor.ai.domain.model.MessageRole

@Entity(tableName = "messages")
data class MessageEntity(
    @PrimaryKey val id: String,
    val conversationId: String,
    val role: String,
    val content: String,
    val timestamp: Long
) {
    fun toDomain() = Message(
        id = id,
        conversationId = conversationId,
        role = MessageRole.valueOf(role),
        content = content,
        timestamp = timestamp
    )

    companion object {
        fun fromDomain(message: Message) = MessageEntity(
            id = message.id,
            conversationId = message.conversationId,
            role = message.role.name,
            content = message.content,
            timestamp = message.timestamp
        )
    }
}

@Entity(tableName = "conversations")
data class ConversationEntity(
    @PrimaryKey val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long
)
