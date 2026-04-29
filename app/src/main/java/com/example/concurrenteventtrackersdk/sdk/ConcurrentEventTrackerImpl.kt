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

// One worker coroutine owns the buffer and sequence counter — no locking needed.
// All commands (track, flush, shutdown) are serialized through the channel.
// Sequences are seeded from DB on startup to avoid collisions after a process restart.
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
                // only reachable after shutdown; event is intentionally dropped
            }
    }

    private fun startWorker(): Job = scope.launch {
        // Seed from DB so sequences don't restart at 0 after a process restart.
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
            // Buffer stays intact so the next flush can retry.
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
        // Lets the worker flush the buffer before closing the scope.
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