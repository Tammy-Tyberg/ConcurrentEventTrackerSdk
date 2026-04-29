package com.example.concurrenteventtrackersdk.sdk

import com.example.concurrenteventtrackersdk.sdk.di.TrackerScope
import com.example.concurrenteventtrackersdk.sdk.domain.model.Event
import com.example.concurrenteventtrackersdk.sdk.domain.model.TrackedEvent
import com.example.concurrenteventtrackersdk.sdk.domain.repository.EventRepository
import com.example.concurrenteventtrackersdk.sdk.domain.upload.UploadFlushedEventsUseCase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.onFailure
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

/**
 * Channel/actor architecture:
 *
 * - trackEvent() sends the raw Event through the channel. The worker owns all mutable
 *   state: the in-memory buffer and the sequence counter.
 * - One worker coroutine assigns sequence numbers, so they are always monotonically
 *   increasing without any locking.
 * - On startup the worker reads MAX(sequence) from the DB so sequence numbers never
 *   collide with rows persisted in a previous session. Without this, a process restart
 *   would generate sequences starting at 0 again, causing deleteEventsBySequences to
 *   silently delete newly-tracked events that share a sequence with old DB rows.
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
    private val uploadUseCase: UploadFlushedEventsUseCase,
    @param:TrackerScope private val scope: CoroutineScope,
    @param:Named("flushInterval") private val flushIntervalMillis: Long
) : ConcurrentEventTracker {

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
        channel.trySend(TrackerCommand.Track(event))
            .onFailure {
                // Channel is closed only after shutdown; event is intentionally dropped.
            }
    }

    private fun startWorker(): Job = scope.launch {
        // Initialize from the highest persisted sequence so new events never collide with
        // DB rows from a previous session.
        var nextSequence = (repository.getMaxSequence() ?: -1L) + 1L
        val buffer = mutableListOf<TrackedEvent>()

        loop@ for (command in channel) {
            when (command) {
                is TrackerCommand.Track -> {
                    buffer.add(TrackedEvent(event = command.event, sequence = nextSequence++))
                    if (buffer.size >= MAX_BUFFER_SIZE) flushBuffer(buffer)
                }
                TrackerCommand.Flush -> flushBuffer(buffer)
                is TrackerCommand.FlushAndAck -> {
                    if (flushBuffer(buffer)) {
                        command.ack.complete(Unit)
                    } else {
                        command.ack.completeExceptionally(
                            IllegalStateException("Room insert failed; events retained for retry")
                        )
                    }
                }
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

    private suspend fun flushBuffer(buffer: MutableList<TrackedEvent>): Boolean {
        if (buffer.isEmpty()) return true
        return try {
            val batch = buffer.toList()
            repository.insertEvents(batch)
            buffer.clear()
            true
        } catch (e: CancellationException) {
            throw e
        } catch (exception: Exception) {
            // Keep buffer intact so a future Flush/Shutdown can retry.
            // TODO: log/report exception in production via callback or metrics.
            false
        }
    }

    override suspend fun uploadFlushedEvents() {
        flushAndAwait()
        uploadUseCase()
    }

    private suspend fun flushAndAwait() {
        val ack = CompletableDeferred<Unit>()
        channel.send(TrackerCommand.FlushAndAck(ack))
        ack.await()
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
