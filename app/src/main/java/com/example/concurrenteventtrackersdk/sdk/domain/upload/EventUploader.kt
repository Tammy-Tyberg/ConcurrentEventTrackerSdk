package com.example.concurrenteventtrackersdk.sdk.domain.upload

import java.io.File

internal interface EventUploader {
    suspend fun upload(file: File): UploadResponse
}