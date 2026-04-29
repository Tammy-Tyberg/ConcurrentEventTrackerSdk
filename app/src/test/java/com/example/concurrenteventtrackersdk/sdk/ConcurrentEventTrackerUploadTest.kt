package com.example.concurrenteventtrackersdk.sdk

import com.example.concurrenteventtrackersdk.sdk.data.upload.EventPayloadWriter
import com.example.concurrenteventtrackersdk.sdk.data.upload.GzipCompressor
import com.example.concurrenteventtrackersdk.sdk.data.upload.TempFileManager
import com.example.concurrenteventtrackersdk.sdk.domain.model.Event
import com.example.concurrenteventtrackersdk.sdk.domain.model.TrackedEvent
import com.example.concurrenteventtrackersdk.sdk.domain.upload.UploadFlushedEventsUseCase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.GZIPInputStream

/**
 * End-to-end integration tests for ConcurrentEventTrackerImpl.uploadFlushedEvents().
 *
 * These tests exercise the full pipeline from trackEvent() through
 * flushAndAwait() → DB → JSON → GZIP → upload → delete.
 *
 * Key invariant under test: uploadFlushedEvents() MUST force-flush the in-memory
 * buffer before reading the DB, so buffered events are never missed in the upload.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConcurrentEventTrackerUploadTest {

    private val testScheduler = TestCoroutineScheduler()
    private val testDispatcher = UnconfinedTestDispatcher(testScheduler)
    private lateinit var fakeRepository: FakeEventRepository
    private lateinit var fakeUploader: FakeEventUploader
    private lateinit var trackerScope: CoroutineScope
    private lateinit var tempDir: File

    @Before
    fun setUp() {
        fakeRepository = FakeEventRepository()
        fakeUploader = FakeEventUploader()
        trackerScope = CoroutineScope(testDispatcher + SupervisorJob())
        tempDir = createTempDir("upload-integration-test")
    }

    @After
    fun tearDown() {
        trackerScope.cancel()
        tempDir.deleteRecursively()
    }

    private fun makeTracker(flushInterval: Long = 10_000L): ConcurrentEventTrackerImpl {
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        val uploadUseCase = UploadFlushedEventsUseCase(
            repository = fakeRepository,
            payloadWriter = EventPayloadWriter(json),
            compressor = GzipCompressor(),
            uploader = fakeUploader,
            fileManager = TempFileManager(tempDir),
            ioDispatcher = testDispatcher
        )
        return ConcurrentEventTrackerImpl(
            repository = fakeRepository,
            uploadUseCase = uploadUseCase,
            scope = trackerScope,
            flushIntervalMillis = flushInterval
        )
    }

    private fun decompressAndParseEvents(gzipBytes: ByteArray): List<Event> {
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        val text = GZIPInputStream(ByteArrayInputStream(gzipBytes)).bufferedReader().readText()
        return json.decodeFromString(text)
    }

    // -------------------------------------------------------------------------
    // MOST IMPORTANT: buffer is flushed before DB read
    // -------------------------------------------------------------------------

    @Test
    fun `uploadFlushedEvents flushes in-memory buffer before reading DB`() = runTest(testDispatcher) {
        val tracker = makeTracker()
        try {
            // Track 3 events — below the threshold of 5, so they stay in memory
            repeat(3) { i -> tracker.trackEvent(Event("e$i")) }
            assertEquals("should still be in memory, not yet in DB", 0, fakeRepository.insertCallCount)

            tracker.uploadFlushedEvents()

            assertEquals(1, fakeUploader.uploadCallCount)
            val events = decompressAndParseEvents(fakeUploader.lastUploadedGzipBytes!!)
            assertEquals(3, events.size)
            assertEquals(listOf(0L, 1L, 2L), fakeRepository.deletedSequences)
        } finally {
            trackerScope.cancel()
        }
    }

    @Test
    fun `track 3 events then upload - payload contains exactly 3 events in order`() = runTest(testDispatcher) {
        val tracker = makeTracker()
        try {
            tracker.trackEvent(Event("click"))
            tracker.trackEvent(Event("swipe"))
            tracker.trackEvent(Event("back"))

            tracker.uploadFlushedEvents()

            val events = decompressAndParseEvents(fakeUploader.lastUploadedGzipBytes!!)
            assertEquals(3, events.size)
            assertEquals(listOf("click", "swipe", "back"), events.map { it.name })
        } finally {
            trackerScope.cancel()
        }
    }

    // -------------------------------------------------------------------------
    // COUNT-BASED FLUSH (confirm 5-event auto-flush)
    // -------------------------------------------------------------------------

    @Test
    fun `track 5 events - auto-flushes to DB by count before timer fires`() = runTest(testDispatcher) {
        val tracker = makeTracker(flushInterval = 10_000L)
        try {
            repeat(5) { i -> tracker.trackEvent(Event("e$i")) }
            assertEquals(5, fakeRepository.events.size)
            assertEquals(1, fakeRepository.insertCallCount)
        } finally {
            trackerScope.cancel()
        }
    }

    @Test
    fun `track 10 events - all persisted without duplicates`() = runTest(testDispatcher) {
        val tracker = makeTracker(flushInterval = 10_000L)
        try {
            repeat(10) { i -> tracker.trackEvent(Event("e$i")) }
            assertEquals(10, fakeRepository.events.size)
            val sequences = fakeRepository.events.map { it.sequence }
            assertEquals("no duplicates", sequences.distinct().size, sequences.size)
        } finally {
            trackerScope.cancel()
        }
    }

    // -------------------------------------------------------------------------
    // UPLOAD SNAPSHOT SAFETY
    // -------------------------------------------------------------------------

    @Test
    fun `events tracked after upload are not deleted by that upload`() = runTest(testDispatcher) {
        val tracker = makeTracker(flushInterval = 10_000L)
        try {
            // 3 buffered events
            repeat(3) { i -> tracker.trackEvent(Event("before$i")) }

            tracker.uploadFlushedEvents() // flushes [0,1,2], uploads, deletes [0,1,2]

            assertEquals(listOf(0L, 1L, 2L), fakeRepository.deletedSequences)

            // Track 2 more after upload — these get new sequences [3, 4]
            tracker.trackEvent(Event("after0"))
            tracker.trackEvent(Event("after1"))
            advanceTimeBy(10_001L) // timer flush

            // The 2 post-upload events persisted but NOT deleted
            assertEquals(2, fakeRepository.events.size)
            assertEquals(listOf("after0", "after1"), fakeRepository.events.map { it.event.name })
        } finally {
            trackerScope.cancel()
        }
    }

    @Test
    fun `events flushed to DB while upload is in-flight are not deleted`() = runTest(testDispatcher) {
        val tracker = makeTracker()
        try {
            repeat(3) { i -> tracker.trackEvent(Event("initial$i")) }

            // Simulate events hitting DB while HTTP upload is in-flight
            fakeUploader.onUploadCallback = {
                fakeRepository.insertDirectly(listOf(
                    TrackedEvent(Event("concurrent0"), 10L),
                    TrackedEvent(Event("concurrent1"), 11L)
                ))
            }

            tracker.uploadFlushedEvents()

            // Only original snapshot [0,1,2] deleted — concurrent [10,11] untouched
            assertEquals(listOf(0L, 1L, 2L), fakeRepository.deletedSequences)
            assertEquals(2, fakeRepository.events.size)
        } finally {
            trackerScope.cancel()
        }
    }

    // -------------------------------------------------------------------------
    // CONCURRENCY: track while upload is running
    // -------------------------------------------------------------------------

    @Test
    fun `100 concurrent trackEvent calls all persisted without duplicates`() = runTest(testDispatcher) {
        val tracker = makeTracker(flushInterval = 10_000L)
        try {
            (1..100).forEach { i -> launch { tracker.trackEvent(Event("e$i")) } }
            runCurrent()
            tracker.uploadFlushedEvents()

            val events = decompressAndParseEvents(fakeUploader.lastUploadedGzipBytes!!)
            assertEquals(100, events.size)
            assertEquals("no duplicates", events.map { it.name }.distinct().size, 100)
        } finally {
            trackerScope.cancel()
        }
    }

    // -------------------------------------------------------------------------
    // SHUTDOWN INTERACTION
    // -------------------------------------------------------------------------

    @Test
    fun `upload after shutdown - shutdown flushes events to DB`() = runTest(testDispatcher) {
        val tracker = makeTracker(flushInterval = 10_000L)
        try {
            tracker.trackEvent(Event("shutdown_event"))
            tracker.shutdown()
            // Shutdown flushes the buffer; upload must be called before shutdown.
            assertEquals(1, fakeRepository.events.size)
        } finally {
            trackerScope.cancel()
        }
    }

    @Test
    fun `uploadFlushedEvents after shutdown throws`() = runTest(testDispatcher) {
        val tracker = makeTracker(flushInterval = 10_000L)
        try {
            tracker.shutdown()
            // With UnconfinedTestDispatcher shutdown runs inline, so the channel is already
            // closed by the time uploadFlushedEvents() is called.
            var threw = false
            try {
                tracker.uploadFlushedEvents()
            } catch (e: Exception) {
                threw = true
            }
            assertTrue("uploadFlushedEvents() after shutdown must throw", threw)
        } finally {
            trackerScope.cancel()
        }
    }
}