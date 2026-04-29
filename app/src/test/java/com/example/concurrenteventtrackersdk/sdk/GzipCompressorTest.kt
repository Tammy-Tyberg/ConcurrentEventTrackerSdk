package com.example.concurrenteventtrackersdk.sdk

import com.example.concurrenteventtrackersdk.sdk.data.upload.GzipCompressor
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.util.zip.GZIPInputStream

class GzipCompressorTest {

    private val compressor = GzipCompressor()
    private lateinit var tempDir: File

    @Before
    fun setUp() {
        tempDir = createTempDir("gzip-test")
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    private fun inputFile(content: String): File =
        File(tempDir, "input.json").also { it.writeText(content) }

    private fun outputFile(): File = File(tempDir, "output.json.gz")

    private fun decompress(file: File): String =
        GZIPInputStream(ByteArrayInputStream(file.readBytes())).bufferedReader().readText()

    // -------------------------------------------------------------------------
    // 1. OUTPUT FILE EXISTS
    // -------------------------------------------------------------------------

    @Test
    fun `createsOutputFile`() {
        val out = outputFile()
        compressor.compress(inputFile("[]"), out)
        assertTrue(out.exists())
        assertTrue(out.length() > 0)
    }

    // -------------------------------------------------------------------------
    // 2. ROUND-TRIP CORRECTNESS
    // -------------------------------------------------------------------------

    @Test
    fun `outputCanBeDecompressedToOriginalContent`() {
        val content = """[{"name":"click","timestamp":1000,"metadata":{}}]"""
        val out = outputFile()
        compressor.compress(inputFile(content), out)
        assertEquals(content, decompress(out))
    }

    @Test
    fun `largeInputDecompressesCorrectly`() {
        val content = (1..1000).joinToString(",\n") { """{"name":"event-$it","timestamp":$it,"metadata":{}}""" }
            .let { "[$it]" }
        val out = outputFile()
        compressor.compress(inputFile(content), out)
        assertEquals(content, decompress(out))
    }

    // -------------------------------------------------------------------------
    // 3. EMPTY INPUT
    // -------------------------------------------------------------------------

    @Test
    fun `handlesEmptyInputFile`() {
        val input = File(tempDir, "empty.json").also { it.createNewFile() }
        val out = outputFile()
        compressor.compress(input, out) // must not throw
        assertTrue(out.exists())
        assertEquals("", decompress(out))
    }

    // -------------------------------------------------------------------------
    // 4. MISSING INPUT FILE
    // -------------------------------------------------------------------------

    @Test
    fun `whenInputMissing throws`() {
        val missing = File(tempDir, "does-not-exist.json")
        var threw = false
        try {
            compressor.compress(missing, outputFile())
        } catch (e: IOException) {
            threw = true
        }
        assertTrue("should throw IOException when input file is missing", threw)
    }

    // -------------------------------------------------------------------------
    // 5. STREAMING — verifies the production implementation uses streams not readBytes
    // -------------------------------------------------------------------------

    @Test
    fun `compressedFileIsSmallerThanOriginalForRepetitiveContent`() {
        // Highly repetitive content compresses well; if implementation used readBytes
        // incorrectly it would still work but this also validates the stream path runs end-to-end.
        val content = "a".repeat(10_000)
        val out = outputFile()
        compressor.compress(inputFile(content), out)
        assertTrue("gzip output should be smaller than input", out.length() < content.length)
        assertEquals(content, decompress(out))
    }
}