package com.vzor.ai.domain.repository

import com.vzor.ai.domain.model.Conversation
import com.vzor.ai.domain.model.Message
import kotlinx.coroutines.flow.Flow

interface ConversationRepository {
    fun getConversations(): Flow<List<Conversation>>
    fun getMessages(conversationId: String): Flow<List<Message>>
    suspend fun createConversation(title: String): Conversation
    suspend fun saveMessage(message: Message)
    suspend fun deleteConversation(conversationId: String)
}
