package com.example.concurrenteventtrackersdk.sdk.domain.upload

import com.example.concurrenteventtrackersdk.sdk.data.upload.EventPayloadWriter
import com.example.concurrenteventtrackersdk.sdk.data.upload.GzipCompressor
import com.example.concurrenteventtrackersdk.sdk.data.upload.TempFileManager
import com.example.concurrenteventtrackersdk.sdk.di.IoDispatcher
import com.example.concurrenteventtrackersdk.sdk.domain.repository.EventRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import javax.inject.Inject

internal class UploadFlushedEventsUseCase @Inject constructor(
    private val repository: EventRepository,
    private val payloadWriter: EventPayloadWriter,
    private val compressor: GzipCompressor,
    private val uploader: EventUploader,
    private val fileManager: TempFileManager,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher
) {
    suspend operator fun invoke(): UploadResponse? = withContext(ioDispatcher) {
        val events = repository.getAllEventsOrdered()
        if (events.isEmpty()) return@withContext null

        val sequences = events.map { it.sequence }
        val jsonFile = fileManager.createTempFile("events-", ".json")
        val gzipFile = fileManager.createTempFile("events-", ".json.gz")

        try {
            payloadWriter.write(events, jsonFile)
            compressor.compress(jsonFile, gzipFile)
            val response = uploader.upload(gzipFile)
            repository.deleteEventsBySequences(sequences)
            response
        } finally {
            fileManager.deleteQuietly(jsonFile)
            fileManager.deleteQuietly(gzipFile)
        }
    }
}