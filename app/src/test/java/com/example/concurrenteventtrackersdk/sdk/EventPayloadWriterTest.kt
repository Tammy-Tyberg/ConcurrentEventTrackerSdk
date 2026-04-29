package com.example.concurrenteventtrackersdk.sdk

import com.example.concurrenteventtrackersdk.sdk.data.upload.EventPayloadWriter
import com.example.concurrenteventtrackersdk.sdk.domain.model.Event
import com.example.concurrenteventtrackersdk.sdk.domain.model.TrackedEvent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class EventPayloadWriterTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val writer = EventPayloadWriter(json)
    private lateinit var outputFile: File

    @Before
    fun setUp() {
        outputFile = File.createTempFile("events-test-", ".json")
    }

    @After
    fun tearDown() {
        outputFile.delete()
    }

    private fun tracked(name: String, timestamp: Long = 0L, metadata: Map<String, String> = emptyMap()) =
        TrackedEvent(Event(name, timestamp, metadata), sequence = 0L)

    private fun parseJson(): JsonArray = json.parseToJsonElement(outputFile.readText()).jsonArray

    // -------------------------------------------------------------------------
    // 1. ROOT IS AN ARRAY
    // -------------------------------------------------------------------------

    @Test
    fun `writesJsonArray - root element is an array`() {
        writer.write(listOf(tracked("click")), outputFile)
        val root = json.parseToJsonElement(outputFile.readText())
        assertTrue("root should be a JSON array", root is JsonArray)
    }

    @Test
    fun `writesJsonArray - empty list produces empty array`() {
        writer.write(emptyList(), outputFile)
        val array = parseJson()
        assertEquals(0, array.size)
    }

    // -------------------------------------------------------------------------
    // 2. FIELD PRESENCE
    // -------------------------------------------------------------------------

    @Test
    fun `includesNameTimestampAndMetadata`() {
        writer.write(listOf(tracked("page_view", timestamp = 1_000_000L, metadata = mapOf("screen" to "home"))), outputFile)
        val obj = parseJson()[0].jsonObject
        assertEquals("page_view", obj["name"]!!.jsonPrimitive.content)
        assertEquals(1_000_000L, obj["timestamp"]!!.jsonPrimitive.content.toLong())
        assertEquals("home", obj["metadata"]!!.jsonObject["screen"]!!.jsonPrimitive.content)
    }

    // -------------------------------------------------------------------------
    // 3. EMPTY METADATA
    // -------------------------------------------------------------------------

    @Test
    fun `preservesEmptyMetadataAsEmptyObject`() {
        writer.write(listOf(tracked("click", metadata = emptyMap())), outputFile)
        val obj = parseJson()[0].jsonObject
        val metadata = obj["metadata"]!!.jsonObject
        assertEquals("metadata should be empty object {}", 0, metadata.size)
    }

    // -------------------------------------------------------------------------
    // 4. SPECIAL CHARACTERS
    // -------------------------------------------------------------------------

    @Test
    fun `handlesSpecialCharacters - quotes newlines slashes unicode emoji`() {
        val specialName = "\"hello\"\nhello\\nworld\npath/to/file\nשלום\n🚀"
        writer.write(listOf(tracked(specialName)), outputFile)
        val parsed = json.decodeFromString<List<Event>>(outputFile.readText())
        assertEquals(specialName, parsed[0].name)
    }

    @Test
    fun `handlesSpecialCharacters - special chars in metadata values`() {
        val meta = mapOf(
            "quote" to "say \"hello\"",
            "newline" to "line1\nline2",
            "unicode" to "שלום"
        )
        writer.write(listOf(tracked("e", metadata = meta)), outputFile)
        val parsed = json.decodeFromString<List<Event>>(outputFile.readText())
        assertEquals(meta, parsed[0].metadata)
    }

    // -------------------------------------------------------------------------
    // 5. NO INTERNAL DB FIELDS
    // -------------------------------------------------------------------------

    @Test
    fun `doesNotIncludeInternalDbFields - sequence not in output`() {
        // The TrackedEvent has a sequence field used for DB ordering.
        // The upload payload should only contain Event fields (name, timestamp, metadata).
        writer.write(listOf(TrackedEvent(Event("click"), sequence = 99L)), outputFile)
        val text = outputFile.readText()
        assertFalse("'sequence' field must not appear in upload payload", text.contains("\"sequence\""))
        assertFalse("'id' field must not appear in upload payload", text.contains("\"id\""))
    }

    @Test
    fun `writesMultipleEvents - all items present`() {
        val events = (1..5).map { tracked("e$it") }
        writer.write(events, outputFile)
        val array = parseJson()
        assertEquals(5, array.size)
        assertEquals(
            listOf("e1", "e2", "e3", "e4", "e5"),
            array.map { it.jsonObject["name"]!!.jsonPrimitive.content }
        )
    }
}
