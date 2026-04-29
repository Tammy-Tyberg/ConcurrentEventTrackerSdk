package com.example.concurrenteventtrackersdk.sdk.domain.upload

import kotlinx.serialization.Serializable

@Serializable
internal data class UploadResponse(
    val originalname: String,
    val filename: String,
    val location: String
)