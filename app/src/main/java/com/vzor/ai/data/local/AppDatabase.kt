package com.vzor.ai.data.local

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        MessageEntity::class,
        ConversationEntity::class,
        SessionLogEntity::class,
        MemoryFactEntity::class
    ],
    version = 2,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun messageDao(): MessageDao
    abstract fun conversationDao(): ConversationDao
    abstract fun sessionLogDao(): SessionLogDao
    abstract fun memoryFactDao(): MemoryFactDao
}
