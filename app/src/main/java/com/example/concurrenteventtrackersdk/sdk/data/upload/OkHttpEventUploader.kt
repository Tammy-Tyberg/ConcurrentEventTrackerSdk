package com.example.concurrenteventtrackersdk.sdk.data.upload

import android.util.Log
import com.example.concurrenteventtrackersdk.sdk.domain.upload.EventUploader
import com.example.concurrenteventtrackersdk.sdk.domain.upload.UploadResponse
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.io.IOException

internal class OkHttpEventUploader(
    private val client: OkHttpClient,
    private val json: Json,
    private val ioDispatcher: CoroutineDispatcher,
    private val uploadUrl: String = "https://api.escuelajs.co/api/v1/files/upload"
) : EventUploader {

    override suspend fun upload(file: File): UploadResponse = withContext(ioDispatcher) {
        val multipart = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                name = "file",
                filename = file.name,
                body = file.asRequestBody("application/gzip".toMediaType())
            )
            .build()

        val request = Request.Builder()
            .url(uploadUrl)
            .post(multipart)
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Upload failed: HTTP ${response.code}")
            }
            val body = response.body?.string()?.takeIf { it.isNotBlank() }
                ?: throw IOException("Empty response body from upload endpoint")
            json.decodeFromString<UploadResponse>(body).also { result ->
                Log.d(TAG, "Upload complete: ${result.location}")
            }
        }
    }

    private companion object {
        const val TAG = "ConcurrentEventTracker"
    }
}