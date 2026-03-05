package com.vzor.ai.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

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

    companion object {
        /**
         * Миграции базы данных.
         * При изменении схемы добавлять новую миграцию вместо destructive fallback,
         * чтобы не терять данные пользователя при обновлении.
         */
        val MIGRATIONS: Array<Migration> = arrayOf(
            // v1→v2: добавлены таблицы session_log и memory_facts
            object : Migration(1, 2) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("""
                        CREATE TABLE IF NOT EXISTS `session_log` (
                            `sessionId` TEXT NOT NULL PRIMARY KEY,
                            `startedAt` INTEGER NOT NULL,
                            `endedAt` INTEGER,
                            `messageCount` INTEGER NOT NULL DEFAULT 0,
                            `provider` TEXT NOT NULL,
                            `routingMode` TEXT NOT NULL,
                            `summary` TEXT
                        )
                    """.trimIndent())
                    db.execSQL("""
                        CREATE TABLE IF NOT EXISTS `memory_facts` (
                            `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                            `fact` TEXT NOT NULL,
                            `category` TEXT NOT NULL,
                            `importance` INTEGER NOT NULL,
                            `createdAt` INTEGER NOT NULL,
                            `lastAccessedAt` INTEGER NOT NULL,
                            `accessCount` INTEGER NOT NULL DEFAULT 0
                        )
                    """.trimIndent())
                }
            }
        )
    }
}
