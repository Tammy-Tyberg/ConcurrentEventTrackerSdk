package com.example.concurrenteventtrackersdk.sdk.domain.repository

import com.example.concurrenteventtrackersdk.sdk.domain.model.TrackedEvent

internal interface EventRepository {
    suspend fun insertEvents(events: List<TrackedEvent>)
    suspend fun getAllEventsOrdered(): List<TrackedEvent>
    suspend fun deleteAllEvents()
    /** Deletes only the rows whose sequence numbers match the uploaded snapshot. */
    suspend fun deleteEventsBySequences(sequences: List<Long>)

    /** Returns the highest sequence number in the DB, or null if the table is empty. */
    suspend fun getMaxSequence(): Long?
}