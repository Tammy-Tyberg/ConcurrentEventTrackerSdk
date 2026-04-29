package com.example.concurrenteventtrackersdk.sdk

import com.example.concurrenteventtrackersdk.sdk.domain.upload.EventUploader
import com.example.concurrenteventtrackersdk.sdk.domain.upload.UploadResponse
import java.io.File
import java.io.IOException

internal class FakeEventUploader : EventUploader {

    var shouldThrow = false
    var uploadCallCount = 0
    var lastUploadedFilePath: String? = null
    var lastUploadedGzipBytes: ByteArray? = null
    var onUploadCallback: (suspend () -> Unit)? = null

    override suspend fun upload(file: File): UploadResponse {
        uploadCallCount++
        lastUploadedFilePath = file.path
        lastUploadedGzipBytes = file.readBytes()
        onUploadCallback?.invoke()
        if (shouldThrow) throw IOException("Upload failed: simulated")
        return UploadResponse(
            originalname = file.name,
            filename = file.name,
            location = "https://example.com/${file.name}"
        )
    }
}