package com.example.concurrenteventtrackersdk.sdk.domain.repository

import com.example.concurrenteventtrackersdk.sdk.domain.model.TrackedEvent

internal interface EventRepository {
    suspend fun insertEvents(events: List<TrackedEvent>)
    suspend fun getAllEventsOrdered(): List<TrackedEvent>
    suspend fun deleteAllEvents()
    /** Deletes only the rows whose sequence numbers match the uploaded snapshot. */
    suspend fun deleteEventsBySequences(sequences: List<Long>)
}