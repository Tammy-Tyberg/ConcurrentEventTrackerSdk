package com.example.concurrenteventtrackersdk.sdk

import com.example.concurrenteventtrackersdk.sdk.domain.model.TrackedEvent
import kotlinx.coroutines.CompletableDeferred

internal sealed interface TrackerCommand {
    data class Track(val event: TrackedEvent) : TrackerCommand
    data object Flush : TrackerCommand
    /** Flush with acknowledgment — sender awaits [ack] to know the flush is committed to DB. */
    data class FlushAndAck(val ack: CompletableDeferred<Unit>) : TrackerCommand
    data object Shutdown : TrackerCommand
}