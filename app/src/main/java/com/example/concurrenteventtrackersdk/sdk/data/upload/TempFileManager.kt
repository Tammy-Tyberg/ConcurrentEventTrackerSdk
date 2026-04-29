package com.example.concurrenteventtrackersdk.sdk.data.upload

import java.io.File

// cacheDir as File (not Context) keeps this testable without the Android runtime.
internal class TempFileManager(private val cacheDir: File) {

    fun createTempFile(prefix: String, suffix: String): File =
        File.createTempFile(prefix, suffix, cacheDir)

    fun deleteQuietly(file: File) {
        runCatching { file.delete() }
    }
}