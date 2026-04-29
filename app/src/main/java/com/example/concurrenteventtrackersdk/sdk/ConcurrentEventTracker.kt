package com.example.concurrenteventtrackersdk.sdk

import com.example.concurrenteventtrackersdk.sdk.domain.model.Event

interface ConcurrentEventTracker {
    /** Thread-safe. Can be called from any thread. */
    fun trackEvent(event: Event)

    /** Reads all persisted events, compresses to GZIP, and POSTs to the upload endpoint. */
    suspend fun uploadFlushedEvents()

    /**
     * Requests a graceful shutdown: cancels the flush timer, drains the in-memory buffer
     * to Room, then releases the coroutine scope.
     *
     * Non-blocking by design. Any events tracked after this call are silently dropped.
     * In production, prefer `lifecycleScope.launch { }` rather than calling this directly
     * on the main thread if the final DB flush could be slow.
     */
    fun shutdown()
}