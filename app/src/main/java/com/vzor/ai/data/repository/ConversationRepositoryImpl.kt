package com.vzor.ai.data.repository

import com.vzor.ai.data.local.ConversationDao
import com.vzor.ai.data.local.ConversationEntity
import com.vzor.ai.data.local.MessageDao
import com.vzor.ai.data.local.MessageEntity
import com.vzor.ai.domain.model.Conversation
import com.vzor.ai.domain.model.Message
import com.vzor.ai.domain.repository.ConversationRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ConversationRepositoryImpl @Inject constructor(
    private val conversationDao: ConversationDao,
    private val messageDao: MessageDao
) : ConversationRepository {

    override fun getConversations(): Flow<List<Conversation>> {
        return conversationDao.getAll().map { entities ->
            entities.map { Conversation(it.id, it.title, it.createdAt, it.updatedAt) }
        }
    }

    override fun getMessages(conversationId: String): Flow<List<Message>> {
        return messageDao.getMessages(conversationId).map { entities ->
            entities.map { it.toDomain() }
        }
    }

    override suspend fun createConversation(title: String): Conversation {
        val now = System.currentTimeMillis()
        val conversation = Conversation(
            id = UUID.randomUUID().toString(),
            title = title,
            createdAt = now,
            updatedAt = now
        )
        conversationDao.insert(
            ConversationEntity(conversation.id, conversation.title, now, now)
        )
        return conversation
    }

    override suspend fun saveMessage(message: Message) {
        messageDao.insertMessage(MessageEntity.fromDomain(message))
        conversationDao.update(
            id = message.conversationId,
            title = message.content.take(50),
            updatedAt = System.currentTimeMillis()
        )
    }

    override suspend fun deleteConversation(conversationId: String) {
        messageDao.deleteMessages(conversationId)
        conversationDao.delete(conversationId)
    }
}
