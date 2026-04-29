package com.example.concurrenteventtrackersdk.sdk

import com.example.concurrenteventtrackersdk.sdk.data.upload.EventPayloadWriter
import com.example.concurrenteventtrackersdk.sdk.data.upload.GzipCompressor
import com.example.concurrenteventtrackersdk.sdk.data.upload.TempFileManager
import com.example.concurrenteventtrackersdk.sdk.domain.model.Event
import com.example.concurrenteventtrackersdk.sdk.domain.upload.UploadFlushedEventsUseCase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Test dispatcher choice: UnconfinedTestDispatcher
 *
 * 1. The tracker scope shares testScheduler with runTest, so advanceTimeBy() drives
 *    the 10-second timer without real waits.
 * 2. scope.launch inside shutdown() dispatches inline, so shutdown completes
 *    synchronously in tests even though it is non-blocking in production.
 *
 * Why the test() helper?
 *
 * runTest drains testScheduler after the body completes. The timer loops forever, so
 * if trackerScope is still active when the body returns the drain never ends. The
 * finally block inside test() cancels the scope before runTest starts draining.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConcurrentEventTrackerImplTest {

    // JUnit 4 creates a fresh test-class instance per @Test, so these are reset each test.
    private val testScheduler = TestCoroutineScheduler()
    private val testDispatcher = UnconfinedTestDispatcher(testScheduler)
    private lateinit var fakeRepository: FakeEventRepository
    private lateinit var trackerScope: CoroutineScope

    @Before
    fun setUp() {
        fakeRepository = FakeEventRepository()
        trackerScope = CoroutineScope(testDispatcher + SupervisorJob())
    }

    @After
    fun tearDown() {
        trackerScope.cancel() // safety net if test throws before finally
    }

    /**
     * Wraps runTest and guarantees trackerScope is cancelled inside a finally block.
     * This prevents the infinite timer loop from hanging runTest's post-body scheduler drain.
     *
     * With UnconfinedTestDispatcher, shutdown()'s internal scope.launch runs inline, so
     * assertions placed immediately after tracker.shutdown() are safe.
     */
    private fun makeUploadUseCase(): UploadFlushedEventsUseCase {
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        val tempDir = kotlin.io.path.createTempDirectory("tracker-impl-test").toFile()
        return UploadFlushedEventsUseCase(
            repository = fakeRepository,
            payloadWriter = EventPayloadWriter(json),
            compressor = GzipCompressor(),
            uploader = FakeEventUploader(),
            fileManager = TempFileManager(tempDir),
            ioDispatcher = testDispatcher
        )
    }

    private fun test(
        flushInterval: Long = 100L,
        block: suspend TestScope.(ConcurrentEventTrackerImpl) -> Unit
    ) = runTest(testDispatcher) {
        val tracker = ConcurrentEventTrackerImpl(
            repository = fakeRepository,
            uploadUseCase = makeUploadUseCase(),
            scope = trackerScope,
            flushIntervalMillis = flushInterval
        )
        try {
            block(tracker)
        } finally {
            trackerScope.cancel()
        }
    }

    private fun event(name: String, metadata: Map<String, String> = emptyMap()) =
        Event(name = name, metadata = metadata)

    // -------------------------------------------------------------------------
    // 1. BASIC PUBLIC API
    // -------------------------------------------------------------------------

    @Test
    fun `trackEvent single call does not crash`() = test { tracker ->
        tracker.trackEvent(event("click"))
    }

    @Test
    fun `events are not flushed before batch size is reached or timer fires`() =
        test(flushInterval = 10_000L) { tracker ->
            repeat(4) { tracker.trackEvent(event("e$it")) }
            advanceTimeBy(9_999L)
            assertEquals(0, fakeRepository.insertCallCount)
        }

    @Test
    fun `trackEvent with empty metadata is persisted correctly`() = test { tracker ->
        tracker.trackEvent(event("click", emptyMap()))
        advanceTimeBy(101L)
        assertEquals(1, fakeRepository.events.size)
        assertEquals(emptyMap<String, String>(), fakeRepository.events.first().event.metadata)
    }

    @Test
    fun `trackEvent with non-empty metadata persists all key-value pairs`() = test { tracker ->
        val meta = mapOf("screen" to "home", "userId" to "42")
        tracker.trackEvent(event("screen_view", meta))
        advanceTimeBy(101L)
        assertEquals(meta, fakeRepository.events.first().event.metadata)
    }

    // -------------------------------------------------------------------------
    // 2. COUNT-BASED (BATCH) FLUSH
    // -------------------------------------------------------------------------

    @Test
    fun `4 events do not trigger a count flush`() = test(flushInterval = 10_000L) { tracker ->
        repeat(4) { tracker.trackEvent(event("e$it")) }
        assertEquals(0, fakeRepository.insertCallCount)
    }

    @Test
    fun `exactly 5 events triggers an immediate count flush`() =
        test(flushInterval = 10_000L) { tracker ->
            repeat(5) { tracker.trackEvent(event("e$it")) }
            assertEquals(1, fakeRepository.insertCallCount)
            assertEquals(5, fakeRepository.events.size)
        }

    @Test
    fun `6th event stays in buffer after the first batch of 5 is flushed`() =
        test(flushInterval = 10_000L) { tracker ->
            repeat(6) { tracker.trackEvent(event("e$it")) }
            assertEquals(1, fakeRepository.insertCallCount)
            assertEquals(5, fakeRepository.events.size) // 6th still buffered
        }

    @Test
    fun `10 events produce exactly two count-based batches of 5`() =
        test(flushInterval = 10_000L) { tracker ->
            repeat(10) { tracker.trackEvent(event("e$it")) }
            assertEquals(2, fakeRepository.insertCallCount)
            assertEquals(10, fakeRepository.events.size)
        }

    @Test
    fun `count flush and timer flush are both serialised through the worker - no race`() =
        test { tracker ->
            repeat(5) { tracker.trackEvent(event("e$it")) }
            assertEquals(1, fakeRepository.insertCallCount) // count flush happened

            advanceTimeBy(101L) // timer fires but buffer is empty
            assertEquals(1, fakeRepository.insertCallCount) // no redundant insert
            assertEquals(5, fakeRepository.events.size)
        }

    // -------------------------------------------------------------------------
    // 3. TIMER FLUSH
    // -------------------------------------------------------------------------

    @Test
    fun `timer flushes fewer-than-batch events after interval`() = test { tracker ->
        tracker.trackEvent(event("a"))
        tracker.trackEvent(event("b"))
        assertEquals(0, fakeRepository.insertCallCount)

        advanceTimeBy(101L)

        assertEquals(1, fakeRepository.insertCallCount)
        assertEquals(2, fakeRepository.events.size)
    }

    @Test
    fun `timer flush with empty buffer does not call insertEvents`() = test { _ ->
        advanceTimeBy(101L)
        assertEquals(0, fakeRepository.insertCallCount)
    }

    @Test
    fun `repeated timer ticks flush only new events each interval`() = test { tracker ->
        tracker.trackEvent(event("first"))
        advanceTimeBy(101L)
        assertEquals(1, fakeRepository.events.size)

        tracker.trackEvent(event("second"))
        advanceTimeBy(101L)
        assertEquals(2, fakeRepository.events.size)
        assertEquals(2, fakeRepository.insertCallCount)
    }

    @Test
    fun `multiple timer ticks with no events between them do not call insertEvents`() =
        test { _ ->
            advanceTimeBy(500L) // 5 potential ticks, all with an empty buffer
            assertEquals(0, fakeRepository.insertCallCount)
        }

    @Test
    fun `flushIntervalMillis is injectable - short interval works in tests`() =
        test(flushInterval = 50L) { tracker ->
            tracker.trackEvent(event("fast"))
            advanceTimeBy(51L)
            assertEquals(1, fakeRepository.events.size)
        }

    // -------------------------------------------------------------------------
    // 4. CONCURRENCY
    // -------------------------------------------------------------------------

    @Test
    fun `100 concurrent trackEvent calls do not lose any events`() = test { tracker ->
        (1..100).forEach { i -> launch { tracker.trackEvent(event("e$i")) } }
        runCurrent()        // drain the 100 launched trackEvent calls
        advanceTimeBy(101L) // trigger timer flush
        assertEquals(100, fakeRepository.events.size)
    }

    @Test
    fun `1000 sequential trackEvent calls all persisted after shutdown`() =
        test(flushInterval = 100_000L) { tracker ->
            (1..1000).forEach { i -> tracker.trackEvent(event("e$i")) }
            tracker.shutdown()
            advanceUntilIdle()
            assertEquals(1000, fakeRepository.events.size)
        }

    @Test
    fun `sequence numbers are unique across all tracked events`() = test { tracker ->
        (1..50).forEach { tracker.trackEvent(event("e$it")) }
        advanceTimeBy(101L)
        val sequences = fakeRepository.events.map { it.sequence }
        assertEquals("all sequences must be unique", sequences.distinct().size, sequences.size)
    }

    @Test
    fun `sequence numbers are strictly monotonically increasing`() = test { tracker ->
        (1..20).forEach { tracker.trackEvent(event("e$it")) }
        advanceTimeBy(101L)
        val sequences = fakeRepository.events.map { it.sequence }
        assertEquals(sequences.sorted(), sequences)
    }

    @Test
    fun `concurrent calls produce no duplicate events in repository`() = test { tracker ->
        (1..100).forEach { i -> launch { tracker.trackEvent(event("e$i")) } }
        runCurrent()        // drain the 100 launched trackEvent calls
        advanceTimeBy(101L) // trigger timer flush
        val sequences = fakeRepository.events.map { it.sequence }
        assertEquals("no duplicates", sequences.distinct().size, sequences.size)
    }

    // -------------------------------------------------------------------------
    // 5. ORDERING
    // -------------------------------------------------------------------------

    @Test
    fun `same-timestamp events are ordered by sequence not timestamp`() = test { tracker ->
        val fixedTime = 1_000_000L
        tracker.trackEvent(Event("first", fixedTime))
        tracker.trackEvent(Event("second", fixedTime))
        tracker.trackEvent(Event("third", fixedTime))
        advanceTimeBy(101L)
        val names = fakeRepository.events.sortedBy { it.sequence }.map { it.event.name }
        assertEquals(listOf("first", "second", "third"), names)
    }

    @Test
    fun `events from separate batches maintain correct cross-batch sequence order`() =
        test { tracker ->
            tracker.trackEvent(event("a"))
            tracker.trackEvent(event("b"))
            advanceTimeBy(101L)

            tracker.trackEvent(event("c"))
            tracker.trackEvent(event("d"))
            advanceTimeBy(101L)

            val names = fakeRepository.events.sortedBy { it.sequence }.map { it.event.name }
            assertEquals(listOf("a", "b", "c", "d"), names)
        }

    // -------------------------------------------------------------------------
    // 6. SHUTDOWN
    // -------------------------------------------------------------------------

    @Test
    fun `shutdown flushes remaining in-memory events`() = test(flushInterval = 100_000L) { tracker ->
        tracker.trackEvent(event("a"))
        tracker.trackEvent(event("b"))
        assertEquals(0, fakeRepository.events.size)

        tracker.shutdown()
        advanceUntilIdle() // let scope.launch inside shutdown() complete
        assertEquals(2, fakeRepository.events.size)
    }

    @Test
    fun `trackEvent after shutdown is silently dropped`() = test(flushInterval = 100_000L) { tracker ->
        tracker.trackEvent(event("before"))
        tracker.shutdown()
        advanceUntilIdle()
        tracker.trackEvent(event("after"))

        assertEquals(1, fakeRepository.events.size)
        assertEquals("before", fakeRepository.events.first().event.name)
    }

    @Test
    fun `shutdown is idempotent - second call is a no-op`() =
        test(flushInterval = 100_000L) { tracker ->
            tracker.trackEvent(event("a"))
            tracker.shutdown()
            advanceUntilIdle()
            tracker.shutdown()

            assertEquals(1, fakeRepository.events.size)
        }

    @Test
    fun `timer is cancelled after shutdown - no further flushes`() = test { tracker ->
        tracker.trackEvent(event("a"))
        tracker.shutdown()
        advanceUntilIdle()
        val countAfterShutdown = fakeRepository.events.size

        advanceTimeBy(500L) // would trigger 5 ticks if timer still ran
        assertEquals(countAfterShutdown, fakeRepository.events.size)
    }

    @Test
    fun `no events lost when trackEvent races with shutdown`() =
        test(flushInterval = 100_000L) { tracker ->
            repeat(10) { tracker.trackEvent(event("race_$it")) }
            tracker.shutdown()
            advanceUntilIdle()
            assertEquals(10, fakeRepository.events.size)
        }

    // -------------------------------------------------------------------------
    // 7. REPOSITORY FAILURE / RETRY
    // -------------------------------------------------------------------------

    @Test
    fun `buffer is retained when insertEvents throws so events retry on next flush`() =
        test { tracker ->
            tracker.trackEvent(event("retry_me"))
            fakeRepository.shouldThrowOnInsert = true
            advanceTimeBy(101L)
            assertEquals(0, fakeRepository.events.size) // nothing persisted

            fakeRepository.shouldThrowOnInsert = false
            advanceTimeBy(101L) // buffer was retained — retried and succeeds
            assertEquals(1, fakeRepository.events.size)
        }

    @Test
    fun `repository failure does not kill the worker - tracking continues afterward`() =
        test { tracker ->
            tracker.trackEvent(event("first"))
            fakeRepository.shouldThrowOnInsert = true
            advanceTimeBy(101L) // fails; "first" stays in buffer

            fakeRepository.shouldThrowOnInsert = false
            tracker.trackEvent(event("second"))
            advanceTimeBy(101L) // "first" (retained) + "second" both flushed

            assertEquals(2, fakeRepository.events.size)
        }

    // -------------------------------------------------------------------------
    // 8. ANDROID LIFECYCLE SIMULATION
    // -------------------------------------------------------------------------

    @Test
    fun `lifecycle - events between onCreate and onDestroy are all persisted`() =
        test(flushInterval = 100_000L) { tracker ->
            tracker.trackEvent(event("screen_view"))
            tracker.trackEvent(event("button_click"))
            tracker.trackEvent(event("scroll"))
            tracker.shutdown() // lifecycleScope.launch { tracker.shutdown() } in onDestroy
            advanceUntilIdle()
            assertEquals(3, fakeRepository.events.size)
        }

    @Test
    fun `lifecycle - timer-flushed and shutdown-flushed events together are complete`() =
        test { tracker ->
            tracker.trackEvent(event("early_1"))
            tracker.trackEvent(event("early_2"))
            advanceTimeBy(101L)
            assertEquals(2, fakeRepository.events.size)

            tracker.trackEvent(event("late_1"))
            tracker.trackEvent(event("late_2"))
            tracker.shutdown()
            advanceUntilIdle()
            assertEquals(4, fakeRepository.events.size)
        }

    @Test
    fun `lifecycle - concurrent UI events from multiple coroutines survive shutdown`() =
        test(flushInterval = 100_000L) { tracker ->
            listOf("click", "swipe", "long_press", "back", "scroll").forEach { name ->
                launch { tracker.trackEvent(event(name)) }
            }
            runCurrent()        // drain the launched trackEvent calls
            tracker.shutdown()
            advanceUntilIdle()  // safe: scope is cancelled, drain returns immediately
            assertEquals(5, fakeRepository.events.size)
        }

    @Test
    fun `lifecycle - sequence order is preserved across a full app session`() = test { tracker ->
        tracker.trackEvent(event("launch"))
        advanceTimeBy(101L)
        tracker.trackEvent(event("interact"))
        advanceTimeBy(101L)
        tracker.trackEvent(event("exit"))
        tracker.shutdown()
        advanceUntilIdle()

        val names = fakeRepository.events.sortedBy { it.sequence }.map { it.event.name }
        assertEquals(listOf("launch", "interact", "exit"), names)
        assertTrue(
            "sequences must be monotonic",
            fakeRepository.events.map { it.sequence } ==
                fakeRepository.events.map { it.sequence }.sorted()
        )
    }
}
