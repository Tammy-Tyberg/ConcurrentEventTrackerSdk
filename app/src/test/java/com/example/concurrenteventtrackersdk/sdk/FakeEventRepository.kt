package com.example.concurrenteventtrackersdk.sdk

import com.example.concurrenteventtrackersdk.sdk.domain.model.TrackedEvent
import com.example.concurrenteventtrackersdk.sdk.domain.repository.EventRepository

internal class FakeEventRepository : EventRepository {

    private val _events = mutableListOf<TrackedEvent>()
    val events: List<TrackedEvent> get() = _events.toList()

    var insertCallCount = 0
    var shouldThrowOnInsert = false

    override suspend fun insertEvents(events: List<TrackedEvent>) {
        insertCallCount++
        if (shouldThrowOnInsert) throw RuntimeException("DB write failed")
        _events.addAll(events)
    }

    override suspend fun getAllEventsOrdered(): List<TrackedEvent> = _events.toList()

    override suspend fun deleteAllEvents() {
        _events.clear()
    }

    val deletedSequences = mutableListOf<Long>()
    var shouldThrowOnDelete = false

    override suspend fun deleteEventsBySequences(sequences: List<Long>) {
        if (shouldThrowOnDelete) throw RuntimeException("DB delete failed: simulated")
        deletedSequences.addAll(sequences)
        _events.removeAll { it.sequence in sequences }
    }

    fun insertDirectly(events: List<TrackedEvent>) {
        _events.addAll(events)
    }
}
