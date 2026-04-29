package com.example.concurrenteventtrackersdk.sdk.data.upload

import java.io.File

// Takes cacheDir as a File rather than Context so it can be unit-tested without Android runtime.
internal class TempFileManager(private val cacheDir: File) {

    fun createTempFile(prefix: String, suffix: String): File =
        File.createTempFile(prefix, suffix, cacheDir)

    fun deleteQuietly(file: File) {
        runCatching { file.delete() }
    }
}