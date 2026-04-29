package com.example.concurrenteventtrackersdk.sdk.domain.repository

import com.example.concurrenteventtrackersdk.sdk.domain.model.TrackedEvent

internal interface EventRepository {
    suspend fun insertEvents(events: List<TrackedEvent>)
    suspend fun getAllEvents(): List<TrackedEvent>
    suspend fun deleteAllEvents()
}