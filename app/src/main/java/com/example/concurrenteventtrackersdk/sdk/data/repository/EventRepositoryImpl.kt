package com.example.concurrenteventtrackersdk.sdk.data.repository

import com.example.concurrenteventtrackersdk.sdk.data.db.EventDao
import com.example.concurrenteventtrackersdk.sdk.data.db.toEntity
import com.example.concurrenteventtrackersdk.sdk.data.db.toTrackedEvent
import com.example.concurrenteventtrackersdk.sdk.domain.model.TrackedEvent
import com.example.concurrenteventtrackersdk.sdk.domain.repository.EventRepository
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
internal class EventRepositoryImpl @Inject constructor(
    private val dao: EventDao
) : EventRepository {

    override suspend fun insertEvents(events: List<TrackedEvent>) =
        dao.insertAll(events.map { it.toEntity() })

    override suspend fun getAllEventsOrdered(): List<TrackedEvent> =
        dao.getAllOrdered().map { it.toTrackedEvent() }

    override suspend fun deleteAllEvents() = dao.deleteAll()


    override suspend fun deleteEventsBySequences(sequences: List<Long>) =
        dao.deleteBySequences(sequences)
}