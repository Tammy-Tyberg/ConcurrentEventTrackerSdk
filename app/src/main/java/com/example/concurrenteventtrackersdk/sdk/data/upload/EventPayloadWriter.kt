package com.example.concurrenteventtrackersdk.sdk.data.upload

import com.example.concurrenteventtrackersdk.sdk.domain.model.Event
import com.example.concurrenteventtrackersdk.sdk.domain.model.TrackedEvent
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

internal open class EventPayloadWriter(private val json: Json) {

    open fun write(events: List<TrackedEvent>, file: File) {
        val payload: List<Event> = events.map { it.event }
        file.writeText(json.encodeToString(payload))
    }
}