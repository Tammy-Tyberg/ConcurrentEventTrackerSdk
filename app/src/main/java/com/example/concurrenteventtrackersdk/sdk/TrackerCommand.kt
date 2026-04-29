package com.example.concurrenteventtrackersdk.sdk

import com.example.concurrenteventtrackersdk.sdk.domain.model.TrackedEvent

internal sealed interface TrackerCommand {
    data class Track(val event: TrackedEvent) : TrackerCommand
    data object Flush : TrackerCommand
    data object Shutdown : TrackerCommand
}