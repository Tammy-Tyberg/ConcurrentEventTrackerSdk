package com.example.concurrenteventtrackersdk.sdk.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class Event(
    val name: String,
    val timestamp: Long = System.currentTimeMillis(),
    val metadata: Map<String, String> = emptyMap()
)