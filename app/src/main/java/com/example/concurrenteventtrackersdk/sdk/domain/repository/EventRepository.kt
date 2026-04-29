package com.example.concurrenteventtrackersdk.sdk.domain.repository

import com.example.concurrenteventtrackersdk.sdk.domain.model.TrackedEvent

internal interface EventRepository {
    suspend fun insertEvents(events: List<TrackedEvent>)
    suspend fun getAllEventsOrdered(): List<TrackedEvent>
    suspend fun deleteAllEvents()
    // Deletes only the snapshot — events flushed during upload are not affected.
    suspend fun deleteEventsBySequences(sequences: List<Long>)

    suspend fun getMaxSequence(): Long?
}