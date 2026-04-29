package com.example.concurrenteventtrackersdk

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.concurrenteventtrackersdk.sdk.ConcurrentEventTracker
import com.example.concurrenteventtrackersdk.sdk.domain.model.Event
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class MainViewModel @Inject constructor(
    private val tracker: ConcurrentEventTracker
) : ViewModel() {

    data class UiState(
        val statusMessage: String = "Idle",
        val submittedCount: Int = 0,
        val isUploading: Boolean = false,
        val isShutDown: Boolean = false
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    fun trackSingleEvent() {
        if (_uiState.value.isShutDown) return
        tracker.trackEvent(
            Event(
                name = "button_tap",
                metadata = mapOf("source" to "demo_ui", "index" to "1")
            )
        )
        _uiState.update { it.copy(submittedCount = it.submittedCount + 1, statusMessage = "Submitted 1 event to tracker (total: ${it.submittedCount + 1})") }
    }

    fun trackBatch() {
        if (_uiState.value.isShutDown) return
        repeat(5) { i ->
            tracker.trackEvent(
                Event(
                    name = "batch_event",
                    metadata = mapOf("source" to "demo_ui", "index" to "$i")
                )
            )
        }
        _uiState.update { it.copy(submittedCount = it.submittedCount + 5, statusMessage = "Tracked 5 events — count threshold should trigger flush") }
    }

    fun uploadFlushedEvents() {
        if (_uiState.value.isUploading || _uiState.value.isShutDown) return
        viewModelScope.launch {
            _uiState.update { it.copy(isUploading = true, statusMessage = "Uploading...") }
            try {
                tracker.uploadFlushedEvents()
                _uiState.update { it.copy(isUploading = false, statusMessage = "Upload complete — GZIP URL logged") }
            } catch (e: Exception) {
                _uiState.update { it.copy(isUploading = false, statusMessage = "Upload failed: ${e.message}") }
            }
        }
    }

    fun shutdown() {
        if (_uiState.value.isShutDown) return
        tracker.shutdown()
        _uiState.update { it.copy(statusMessage = "Tracker shut down — close and reopen the app to create a new SDK instance.", isShutDown = true) }
    }
}