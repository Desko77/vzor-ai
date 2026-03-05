package com.vzor.ai.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "session_log")
data class SessionLogEntity(
    @PrimaryKey val sessionId: String,
    val startedAt: Long,
    val endedAt: Long? = null,
    val messageCount: Int = 0,
    val provider: String,
    val routingMode: String,
    val summary: String? = null
)
