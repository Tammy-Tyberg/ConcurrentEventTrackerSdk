package com.example.concurrenteventtrackersdk.sdk

import com.example.concurrenteventtrackersdk.sdk.di.TrackerScope
import com.example.concurrenteventtrackersdk.sdk.domain.model.Event
import com.example.concurrenteventtrackersdk.sdk.domain.model.TrackedEvent
import com.example.concurrenteventtrackersdk.sdk.domain.repository.EventRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.onFailure
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

/**
 * Channel/actor architecture:
 *
 * - trackEvent() stamps a monotonic sequence number and sends a Track command.
 *   It never touches the buffer directly.
 * - One worker coroutine owns the mutable buffer — no locking needed.
 * - Timer and shutdown send commands instead of touching shared state.
 * - Count flush, timer flush, and shutdown flush are all serialised through the worker.
 * - Room reads ORDER BY sequence ASC, so multi-batch ordering is always correct.
 * - Channel.UNLIMITED avoids back-pressure blocking callers; production should tune capacity.
 * - shutdown() is non-suspending per the assignment requirement. In production, prefer
 *   suspend fun shutdown() or returning a Job so callers can await completion.
 *
 * The SDK exposes ConcurrentEventTracker as the public contract while keeping this class
 * internal. Consumers depend on a stable interface; the implementation strategy can change
 * without breaking callers.
 *
 * @TrackerScope is a dedicated CoroutineScope (SupervisorJob + Dispatchers.IO). It is safe
 * to cancel it from within this class because no other SDK component shares it.
 */
@Singleton
internal class ConcurrentEventTrackerImpl @Inject constructor(
    private val repository: EventRepository,
    @param:TrackerScope private val scope: CoroutineScope,
    @param:Named("flushInterval") private val flushIntervalMillis: Long
) : ConcurrentEventTracker {

    private val sequence = AtomicLong(0L)
    private val isShutdown = AtomicBoolean(false)
    private val channel = Channel<TrackerCommand>(Channel.UNLIMITED)

    private val workerJob: Job
    private val timerJob: Job

    init {
        workerJob = startWorker()
        timerJob = startTimer()
    }

    override fun trackEvent(event: Event) {
        if (isShutdown.get()) return

        val tracked = TrackedEvent(
            event = event,
            sequence = sequence.getAndIncrement()
        )

        channel.trySend(TrackerCommand.Track(tracked))
            .onFailure {
                // Channel is closed only after shutdown; event is intentionally dropped.
            }
    }

    private fun startWorker(): Job = scope.launch {
        val buffer = mutableListOf<TrackedEvent>()

        loop@ for (command in channel) {
            when (command) {
                is TrackerCommand.Track -> {
                    buffer.add(command.event)
                    if (buffer.size >= MAX_BUFFER_SIZE) flushBuffer(buffer)
                }
                TrackerCommand.Flush -> flushBuffer(buffer)
                TrackerCommand.Shutdown -> {
                    flushBuffer(buffer)
                    channel.close()
                    break@loop
                }
            }
        }
    }

    private fun startTimer(): Job = scope.launch {
        while (isActive) {
            delay(flushIntervalMillis)
            val result = channel.trySend(TrackerCommand.Flush)
            if (result.isFailure) break
        }
    }

    private suspend fun flushBuffer(buffer: MutableList<TrackedEvent>) {
        if (buffer.isEmpty()) return
        try {
            val batch = buffer.toList()
            repository.insertEvents(batch)
            buffer.clear()
        } catch (exception: Exception) {
            // Keep buffer intact so a future Flush/Shutdown can retry.
            // TODO: log/report exception in production via callback or metrics.
        }
    }

    override suspend fun uploadFlushedEvents() {
        // TODO Step 2: force-flush buffer → DB, read all, JSON, GZIP, POST, delete on success.
    }

    override fun shutdown() {
        if (!isShutdown.compareAndSet(false, true)) return

        timerJob.cancel()

        // Async shutdown: sends Shutdown through the worker so it flushes the buffer first,
        // then cancels the scope. Non-blocking by design — the assignment requires fun shutdown().
        // In production, expose suspend fun shutdown() or return the Job for deterministic await.
        scope.launch {
            channel.send(TrackerCommand.Shutdown)
            workerJob.join()
            scope.cancel()
        }
    }

    private companion object {
        const val MAX_BUFFER_SIZE = 5
    }
}