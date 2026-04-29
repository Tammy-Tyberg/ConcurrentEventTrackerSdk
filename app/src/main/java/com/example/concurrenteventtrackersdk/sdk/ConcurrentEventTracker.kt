package com.example.concurrenteventtrackersdk.sdk

import com.example.concurrenteventtrackersdk.sdk.domain.model.Event

interface ConcurrentEventTracker {
    /** Safe to call from any thread. */
    fun trackEvent(event: Event)

    /** Flushes the in-memory buffer, then reads all persisted events, compresses to GZIP, and POSTs. */
    suspend fun uploadFlushedEvents()

    /** Non-blocking. Flushes remaining events and cancels background work. Events tracked after this are dropped. */
    fun shutdown()
}