package com.vzor.ai.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update

@Dao
interface SessionLogDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(sessionLog: SessionLogEntity)

    @Query("SELECT * FROM session_log ORDER BY startedAt DESC")
    suspend fun getAll(): List<SessionLogEntity>

    @Query("SELECT * FROM session_log WHERE sessionId = :id")
    suspend fun getById(id: String): SessionLogEntity?

    @Update
    suspend fun update(sessionLog: SessionLogEntity)

    @Query("DELETE FROM session_log WHERE startedAt < :timestamp")
    suspend fun deleteOlderThan(timestamp: Long)
}
