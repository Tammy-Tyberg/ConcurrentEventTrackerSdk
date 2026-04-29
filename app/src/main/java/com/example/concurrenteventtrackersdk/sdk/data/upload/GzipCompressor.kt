package com.example.concurrenteventtrackersdk.sdk.data.upload

import java.io.File
import java.util.zip.GZIPOutputStream

internal open class GzipCompressor {
    open fun compress(inputFile: File, outputFile: File) {
        inputFile.inputStream().buffered().use { input ->
            GZIPOutputStream(outputFile.outputStream().buffered()).use { output ->
                input.copyTo(output)
            }
        }
    }
}