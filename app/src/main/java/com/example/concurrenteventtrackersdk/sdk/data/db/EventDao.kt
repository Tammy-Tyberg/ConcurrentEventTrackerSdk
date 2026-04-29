package com.example.concurrenteventtrackersdk.sdk.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface EventDao {

    @Insert
    suspend fun insertAll(events: List<EventEntity>)

    @Query("SELECT * FROM events ORDER BY sequence ASC")
    suspend fun getAllOrdered(): List<EventEntity>

    @Query("DELETE FROM events")
    suspend fun deleteAll()

    @Query("DELETE FROM events WHERE sequence IN (:sequences)")
    suspend fun deleteBySequences(sequences: List<Long>)

    @Query("SELECT MAX(sequence) FROM events")
    suspend fun getMaxSequence(): Long?
}