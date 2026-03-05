package com.vzor.ai.domain.repository

import com.vzor.ai.domain.model.Message
import kotlinx.coroutines.flow.Flow

interface AiRepository {
    suspend fun sendMessage(messages: List<Message>): Result<String>
    fun streamMessage(messages: List<Message>): Flow<String>
}
