package com.example.cameraapp

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Singleton to track and share real-time performance metrics across the app.
 */
object PerformanceMonitor {
    
    private val _inferenceSpeed = MutableStateFlow(0L)
    val inferenceSpeed: StateFlow<Long> = _inferenceSpeed.asStateFlow()
    
    private val _averageInferenceSpeed = MutableStateFlow(0L)
    val averageInferenceSpeed: StateFlow<Long> = _averageInferenceSpeed.asStateFlow()
    
    private val speedHistory = mutableListOf<Long>()
    private const val HISTORY_SIZE = 10

    private val _acceptedDetections = MutableStateFlow(0)
    val acceptedDetections: StateFlow<Int> = _acceptedDetections.asStateFlow()
    
    private val _rejectedDetections = MutableStateFlow(0)
    val rejectedDetections: StateFlow<Int> = _rejectedDetections.asStateFlow()

    fun updateInferenceSpeed(speedMs: Long) {
        _inferenceSpeed.value = speedMs
        
        // Calculate moving average
        speedHistory.add(speedMs)
        if (speedHistory.size > HISTORY_SIZE) {
            speedHistory.removeAt(0)
        }
        _averageInferenceSpeed.value = speedHistory.average().toLong()
    }

    fun incrementAccepted() {
        _acceptedDetections.value++
    }

    fun incrementRejected() {
        _rejectedDetections.value++
    }
}
