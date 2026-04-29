package com.example.concurrenteventtrackersdk.sdk

import com.example.concurrenteventtrackersdk.sdk.data.upload.EventPayloadWriter
import com.example.concurrenteventtrackersdk.sdk.data.upload.GzipCompressor
import com.example.concurrenteventtrackersdk.sdk.data.upload.TempFileManager
import com.example.concurrenteventtrackersdk.sdk.domain.model.Event
import com.example.concurrenteventtrackersdk.sdk.domain.model.TrackedEvent
import com.example.concurrenteventtrackersdk.sdk.domain.upload.UploadFlushedEventsUseCase
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.util.zip.GZIPInputStream

@OptIn(ExperimentalCoroutinesApi::class)
class UploadFlushedEventsUseCaseTest {

    // Throwing subclasses — smallest change to simulate collaborator failures.
    private inner class ThrowingEventPayloadWriter : EventPayloadWriter(json) {
        override fun write(events: List<TrackedEvent>, file: File): Unit =
            throw IOException("write failed: simulated")
    }

    private inner class ThrowingGzipCompressor : GzipCompressor() {
        override fun compress(inputFile: File, outputFile: File): Unit =
            throw IOException("compress failed: simulated")
    }

    private val testDispatcher = UnconfinedTestDispatcher()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private lateinit var fakeRepository: FakeEventRepository
    private lateinit var fakeUploader: FakeEventUploader
    private lateinit var tempDir: File
    private lateinit var useCase: UploadFlushedEventsUseCase

    @Before
    fun setUp() {
        fakeRepository = FakeEventRepository()
        fakeUploader = FakeEventUploader()
        tempDir = createTempDir("sdk-upload-test")
        useCase = makeUseCase()
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    private fun makeUseCase(
        writer: EventPayloadWriter = EventPayloadWriter(json),
        compressor: GzipCompressor = GzipCompressor()
    ) = UploadFlushedEventsUseCase(
        repository = fakeRepository,
        payloadWriter = writer,
        compressor = compressor,
        uploader = fakeUploader,
        fileManager = TempFileManager(tempDir),
        ioDispatcher = testDispatcher
    )

    private fun event(name: String) = TrackedEvent(Event(name), 0L)

    private fun decompressGzip(bytes: ByteArray): String =
        GZIPInputStream(ByteArrayInputStream(bytes)).bufferedReader().readText()

    // -------------------------------------------------------------------------
    // 1. NO-EVENTS EARLY EXIT
    // -------------------------------------------------------------------------

    @Test
    fun `whenNoEvents doesNotCallUploader`() = runTest(testDispatcher) {
        useCase()
        assertEquals(0, fakeUploader.uploadCallCount)
    }

    @Test
    fun `whenNoEvents doesNotCreateTempFiles`() = runTest(testDispatcher) {
        useCase()
        assertEquals(0, tempDir.listFiles()?.size ?: 0)
    }

    @Test
    fun `whenNoEvents doesNotCallDelete`() = runTest(testDispatcher) {
        useCase()
        assertTrue(fakeRepository.deletedSequences.isEmpty())
    }

    // -------------------------------------------------------------------------
    // 2. HAPPY PATH
    // -------------------------------------------------------------------------

    @Test
    fun `uploadsGzipFile`() = runTest(testDispatcher) {
        fakeRepository.insertEvents(listOf(TrackedEvent(Event("click"), 0L)))
        useCase()
        assertEquals(1, fakeUploader.uploadCallCount)
        assertTrue(fakeUploader.lastUploadedFilePath?.endsWith(".json.gz") == true)
    }

    @Test
    fun `onSuccess deletesOnlyUploadedSequences`() = runTest(testDispatcher) {
        val events = listOf(
            TrackedEvent(Event("e1"), 0L),
            TrackedEvent(Event("e2"), 1L)
        )
        fakeRepository.insertEvents(events)
        useCase()
        assertEquals(listOf(0L, 1L), fakeRepository.deletedSequences)
        assertTrue(fakeRepository.events.isEmpty())
    }

    @Test
    fun `alwaysDeletesTempFiles onSuccess`() = runTest(testDispatcher) {
        fakeRepository.insertEvents(listOf(TrackedEvent(Event("e1"), 0L)))
        useCase()
        assertEquals(0, tempDir.listFiles()?.size ?: 0)
    }

    // -------------------------------------------------------------------------
    // 3. WRITER FAILURE
    // -------------------------------------------------------------------------

    @Test
    fun `whenWriterFails doesNotCallUploader`() = runTest(testDispatcher) {
        fakeRepository.insertEvents(listOf(event("e1")))
        runCatching { makeUseCase(writer = ThrowingEventPayloadWriter())() }
        assertEquals(0, fakeUploader.uploadCallCount)
    }

    @Test
    fun `whenWriterFails doesNotDeleteDbRows`() = runTest(testDispatcher) {
        fakeRepository.insertEvents(listOf(event("e1")))
        runCatching { makeUseCase(writer = ThrowingEventPayloadWriter())() }
        assertTrue(fakeRepository.deletedSequences.isEmpty())
        assertEquals(1, fakeRepository.events.size)
    }

    @Test
    fun `whenWriterFails deletesTempFiles`() = runTest(testDispatcher) {
        fakeRepository.insertEvents(listOf(event("e1")))
        runCatching { makeUseCase(writer = ThrowingEventPayloadWriter())() }
        assertEquals(0, tempDir.listFiles()?.size ?: 0)
    }

    @Test
    fun `whenWriterFails exceptionSurfaces`() = runTest(testDispatcher) {
        fakeRepository.insertEvents(listOf(event("e1")))
        var threw = false
        try { makeUseCase(writer = ThrowingEventPayloadWriter())() } catch (e: IOException) { threw = true }
        assertTrue(threw)
    }

    // -------------------------------------------------------------------------
    // 4. COMPRESSOR FAILURE
    // -------------------------------------------------------------------------

    @Test
    fun `whenCompressorFails doesNotCallUploader`() = runTest(testDispatcher) {
        fakeRepository.insertEvents(listOf(event("e1")))
        runCatching { makeUseCase(compressor = ThrowingGzipCompressor())() }
        assertEquals(0, fakeUploader.uploadCallCount)
    }

    @Test
    fun `whenCompressorFails doesNotDeleteDbRows`() = runTest(testDispatcher) {
        fakeRepository.insertEvents(listOf(event("e1")))
        runCatching { makeUseCase(compressor = ThrowingGzipCompressor())() }
        assertTrue(fakeRepository.deletedSequences.isEmpty())
        assertEquals(1, fakeRepository.events.size)
    }

    @Test
    fun `whenCompressorFails deletesTempFiles`() = runTest(testDispatcher) {
        fakeRepository.insertEvents(listOf(event("e1")))
        runCatching { makeUseCase(compressor = ThrowingGzipCompressor())() }
        assertEquals(0, tempDir.listFiles()?.size ?: 0)
    }

    // -------------------------------------------------------------------------
    // 5. UPLOAD FAILURE
    // -------------------------------------------------------------------------

    @Test
    fun `onUploadFailure doesNotDeleteEvents`() = runTest(testDispatcher) {
        fakeRepository.insertEvents(listOf(TrackedEvent(Event("e1"), 0L)))
        fakeUploader.shouldThrow = true
        var threw = false
        try { useCase() } catch (e: Exception) { threw = true }
        assertTrue(threw)
        assertEquals(1, fakeRepository.events.size)
        assertTrue(fakeRepository.deletedSequences.isEmpty())
    }

    @Test
    fun `alwaysDeletesTempFiles onFailure`() = runTest(testDispatcher) {
        fakeRepository.insertEvents(listOf(TrackedEvent(Event("e1"), 0L)))
        fakeUploader.shouldThrow = true
        runCatching { useCase() }
        assertEquals(0, tempDir.listFiles()?.size ?: 0)
    }

    // -------------------------------------------------------------------------
    // 6. DELETE FAILURE AFTER SUCCESSFUL UPLOAD
    // -------------------------------------------------------------------------

    @Test
    fun `whenDeleteFails afterSuccessfulUpload exceptionSurfaces`() = runTest(testDispatcher) {
        fakeRepository.insertEvents(listOf(TrackedEvent(Event("e1"), 0L)))
        fakeRepository.shouldThrowOnDelete = true
        var threw = false
        try { useCase() } catch (e: Exception) { threw = true }
        // Upload succeeded but delete failed — exception surfaces so caller knows retry is needed
        assertTrue(threw)
        assertEquals(1, fakeUploader.uploadCallCount)
    }

    @Test
    fun `whenDeleteFails afterSuccessfulUpload tempFilesStillDeleted`() = runTest(testDispatcher) {
        fakeRepository.insertEvents(listOf(TrackedEvent(Event("e1"), 0L)))
        fakeRepository.shouldThrowOnDelete = true
        runCatching { useCase() }
        assertEquals(0, tempDir.listFiles()?.size ?: 0)
    }

    // -------------------------------------------------------------------------
    // 7. SNAPSHOT SAFETY — events flushed during upload are not deleted
    // -------------------------------------------------------------------------

    @Test
    fun `upload deletesOnlySnapshotSequences notEventsAddedDuringUpload`() = runTest(testDispatcher) {
        fakeRepository.insertEvents(listOf(
            TrackedEvent(Event("e1"), 0L),
            TrackedEvent(Event("e2"), 1L),
            TrackedEvent(Event("e3"), 2L)
        ))

        // Simulate new events arriving in DB while the HTTP upload is in-flight
        fakeUploader.onUploadCallback = {
            fakeRepository.insertDirectly(listOf(
                TrackedEvent(Event("concurrent1"), 10L),
                TrackedEvent(Event("concurrent2"), 11L)
            ))
        }

        useCase()

        // Only the original snapshot sequences were deleted
        assertEquals(listOf(0L, 1L, 2L), fakeRepository.deletedSequences)
        // The 2 concurrent events remain in the DB
        assertEquals(2, fakeRepository.events.size)
        assertEquals(
            listOf("concurrent1", "concurrent2"),
            fakeRepository.events.map { it.event.name }
        )
    }

    // -------------------------------------------------------------------------
    // 8. EVENT ORDER IN JSON PAYLOAD
    // -------------------------------------------------------------------------

    @Test
    fun `preservesEventOrder inGzipPayload`() = runTest(testDispatcher) {
        val events = (0L..3L).map { TrackedEvent(Event("event-$it"), it) }
        fakeRepository.insertEvents(events)
        useCase()
        val text = decompressGzip(fakeUploader.lastUploadedGzipBytes!!)
        val decoded = json.decodeFromString<List<Event>>(text)
        assertEquals(listOf("event-0", "event-1", "event-2", "event-3"), decoded.map { it.name })
    }

    // -------------------------------------------------------------------------
    // 9. PAYLOAD SHAPE — no internal DB fields
    // -------------------------------------------------------------------------

    @Test
    fun `jsonPayload containsOnlyEventFields notSequenceOrId`() = runTest(testDispatcher) {
        fakeRepository.insertEvents(listOf(TrackedEvent(Event("click", 12345L, mapOf("k" to "v")), 99L)))
        useCase()
        val text = decompressGzip(fakeUploader.lastUploadedGzipBytes!!)
        // Payload should NOT contain the internal sequence field (99)
        assertTrue(!text.contains("\"sequence\""))
        // Should contain event fields
        assertTrue(text.contains("\"click\""))
        assertTrue(text.contains("12345"))
        assertTrue(text.contains("\"k\""))
    }
}