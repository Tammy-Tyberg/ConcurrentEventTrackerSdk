package com.example.concurrenteventtrackersdk.sdk.data.db

import com.example.concurrenteventtrackersdk.sdk.domain.model.Event
import com.example.concurrenteventtrackersdk.sdk.domain.model.TrackedEvent

internal fun TrackedEvent.toEntity() = EventEntity(
    name = event.name,
    timestamp = event.timestamp,
    sequence = sequence,
    metadata = event.metadata
)

internal fun EventEntity.toTrackedEvent() = TrackedEvent(
    event = Event(name = name, timestamp = timestamp, metadata = metadata),
    sequence = sequence
)