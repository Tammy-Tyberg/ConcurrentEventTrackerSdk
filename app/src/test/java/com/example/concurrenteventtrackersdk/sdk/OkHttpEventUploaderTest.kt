package com.example.concurrenteventtrackersdk.sdk

import com.example.concurrenteventtrackersdk.sdk.data.upload.GzipCompressor
import com.example.concurrenteventtrackersdk.sdk.data.upload.OkHttpEventUploader
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException

/**
 * Tests for OkHttpEventUploader using MockWebServer.
 *
 * Covers: HTTP method, multipart shape (field name, filename, content-type),
 * response parsing, error handling (4xx/5xx, empty body, malformed JSON,
 * and network failure).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OkHttpEventUploaderTest {

    private val server = MockWebServer()
    private val testDispatcher = UnconfinedTestDispatcher()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val client = OkHttpClient()
    private lateinit var tempDir: File

    @Before
    fun setUp() {
        server.start()
        tempDir = createTempDir("okhttp-uploader-test")
    }

    @After
    fun tearDown() {
        server.shutdown()
        tempDir.deleteRecursively()
    }

    private fun makeUploader() = OkHttpEventUploader(
        client = client,
        json = json,
        ioDispatcher = testDispatcher,
        uploadUrl = server.url("/upload").toString()
    )

    /** Creates a minimal valid gzip file containing an empty JSON array. */
    private fun testGzipFile(name: String = "events.json.gz"): File {
        val jsonFile = File(tempDir, "events.json").also { it.writeText("[]") }
        val gzipFile = File(tempDir, name)
        GzipCompressor().compress(jsonFile, gzipFile)
        return gzipFile
    }

    private val successResponseBody = """
        {
          "originalname": "events.json.gz",
          "filename": "server-f3a5.gz",
          "location": "https://api.escuelajs.co/api/v1/files/server-f3a5.gz"
        }
    """.trimIndent()

    // -------------------------------------------------------------------------
    // 1. HTTP METHOD
    // -------------------------------------------------------------------------

    @Test
    fun `sendsPostRequest`() = runTest(testDispatcher) {
        server.enqueue(MockResponse().setResponseCode(200).setBody(successResponseBody))
        makeUploader().upload(testGzipFile())
        assertEquals("POST", server.takeRequest().method)
    }

    // -------------------------------------------------------------------------
    // 2. MULTIPART SHAPE
    // -------------------------------------------------------------------------

    @Test
    fun `usesMultipartFormData`() = runTest(testDispatcher) {
        server.enqueue(MockResponse().setResponseCode(200).setBody(successResponseBody))
        makeUploader().upload(testGzipFile())
        val contentType = server.takeRequest().headers["Content-Type"] ?: ""
        assertTrue("Content-Type should contain multipart/form-data: $contentType",
            contentType.contains("multipart/form-data"))
    }

    @Test
    fun `usesFieldNameFile`() = runTest(testDispatcher) {
        server.enqueue(MockResponse().setResponseCode(200).setBody(successResponseBody))
        makeUploader().upload(testGzipFile())
        val bodyText = server.takeRequest().body.readByteArray().toString(Charsets.ISO_8859_1)
        assertTrue("""body should contain name="file": $bodyText""",
            bodyText.contains("""name="file""""))
    }

    @Test
    fun `usesGzipFilename`() = runTest(testDispatcher) {
        server.enqueue(MockResponse().setResponseCode(200).setBody(successResponseBody))
        makeUploader().upload(testGzipFile("my-events.json.gz"))
        val bodyText = server.takeRequest().body.readByteArray().toString(Charsets.ISO_8859_1)
        assertTrue("body should contain the gzip filename: $bodyText",
            bodyText.contains("my-events.json.gz"))
    }

    @Test
    fun `usesApplicationGzipMediaType`() = runTest(testDispatcher) {
        server.enqueue(MockResponse().setResponseCode(200).setBody(successResponseBody))
        makeUploader().upload(testGzipFile())
        val bodyText = server.takeRequest().body.readByteArray().toString(Charsets.ISO_8859_1)
        assertTrue("body should declare application/gzip content-type: $bodyText",
            bodyText.contains("application/gzip"))
    }

    // -------------------------------------------------------------------------
    // 3. RESPONSE PARSING
    // -------------------------------------------------------------------------

    @Test
    fun `parsesSuccessfulResponse`() = runTest(testDispatcher) {
        server.enqueue(MockResponse().setResponseCode(200).setBody(successResponseBody))
        val response = makeUploader().upload(testGzipFile())
        assertEquals("events.json.gz", response.originalname)
        assertEquals("server-f3a5.gz", response.filename)
        assertEquals("https://api.escuelajs.co/api/v1/files/server-f3a5.gz", response.location)
    }

    // -------------------------------------------------------------------------
    // 4. ERROR CASES
    // -------------------------------------------------------------------------

    @Test
    fun `non2xxThrowsIOException`() = runTest(testDispatcher) {
        server.enqueue(MockResponse().setResponseCode(500).setBody("Internal Server Error"))
        var threw = false
        try {
            makeUploader().upload(testGzipFile())
        } catch (e: IOException) {
            threw = true
            assertTrue("exception message should mention HTTP code", e.message?.contains("500") == true)
        }
        assertTrue(threw)
    }

    @Test
    fun `http404ThrowsIOException`() = runTest(testDispatcher) {
        server.enqueue(MockResponse().setResponseCode(404).setBody("Not Found"))
        var threw = false
        try { makeUploader().upload(testGzipFile()) } catch (e: IOException) { threw = true }
        assertTrue(threw)
    }

    @Test
    fun `emptyBodyThrowsIOException`() = runTest(testDispatcher) {
        server.enqueue(MockResponse().setResponseCode(200).setBody(""))
        var threw = false
        try {
            makeUploader().upload(testGzipFile())
        } catch (e: IOException) {
            threw = true
        }
        assertTrue("empty body should throw IOException", threw)
    }

    @Test
    fun `malformedJsonThrows`() = runTest(testDispatcher) {
        server.enqueue(MockResponse().setResponseCode(200).setBody("not valid json {{{"))
        var threw = false
        try { makeUploader().upload(testGzipFile()) } catch (e: Exception) { threw = true }
        assertTrue("malformed JSON should throw", threw)
    }

    @Test
    fun `networkFailureThrowsIOException`() = runTest(testDispatcher) {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        var threw = false
        try {
            makeUploader().upload(testGzipFile())
        } catch (e: IOException) {
            threw = true
        }
        assertTrue("network disconnect should throw IOException", threw)
    }
}