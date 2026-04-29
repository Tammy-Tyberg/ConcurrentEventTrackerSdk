package com.example.concurrenteventtrackersdk.sdk

import com.example.concurrenteventtrackersdk.sdk.domain.model.Event
import kotlinx.coroutines.CompletableDeferred

internal sealed interface TrackerCommand {
    data class Track(val event: Event) : TrackerCommand
    data object Flush : TrackerCommand
    // Caller suspends on ack until the worker confirms the flush is committed to DB.
    data class FlushAndAck(val ack: CompletableDeferred<Unit>) : TrackerCommand
    data object Shutdown : TrackerCommand
}