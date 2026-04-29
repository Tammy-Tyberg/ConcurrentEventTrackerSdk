package com.example.concurrenteventtrackersdk.sdk.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.TypeConverters

@Entity(tableName = "events")
@TypeConverters(MetadataConverter::class)
data class EventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val timestamp: Long,
    val sequence: Long,
    val metadata: Map<String, String>
)