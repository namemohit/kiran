package com.example.cameraapp

import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Manages AI detection log entries.
 * Stores detection history for display in the AI Detections screen.
 */
object AILogManager {
    
    enum class DetectionStatus {
        PENDING,
        ACCEPTED,
        REJECTED
    }

    interface StatusChangeListener {
        fun onStatusChanged(entry: LogEntry, status: DetectionStatus)
    }

    data class LogEntry(
        val id: Int,
        val label: String,
        val confidence: Float,
        val inferenceTimeMs: Long = 0,
        val timestamp: Long = System.currentTimeMillis(),
        val fullFrameImage: android.graphics.Bitmap? = null,
        val croppedImage: android.graphics.Bitmap? = null,
        val maskImage: android.graphics.Bitmap? = null,
        val boundingBox: android.graphics.RectF? = null,
        var status: DetectionStatus = DetectionStatus.PENDING,
        val isFlow2: Boolean = false
    ) {
        fun getFormattedTime(): String {
            val sdf = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
            return sdf.format(Date(timestamp))
        }
        
        fun getConfidencePercent(): String {
            return "${(confidence * 100).toInt()}%"
        }
    }
    
    private val entries = mutableListOf<LogEntry>()
    private val listeners = mutableListOf<(List<LogEntry>) -> Unit>()
    private val statusListeners = mutableListOf<StatusChangeListener>()
    private var nextId = 1
    
    /**
     * Add a new detection entry to the log.
     * @return true if added, false if throttled/rejected
     */
    @Synchronized
    fun addEntry(
        label: String, 
        confidence: Float, 
        inferenceTimeMs: Long = 0,
        fullFrame: android.graphics.Bitmap? = null,
        image: android.graphics.Bitmap? = null,
        mask: android.graphics.Bitmap? = null,
        boundingBox: android.graphics.RectF? = null,
        isFlow2: Boolean = false
    ): Boolean {
        // Avoid duplicate consecutive entries (same label within 3 seconds for better stability)
        val lastEntry = entries.lastOrNull()
        if (lastEntry != null && 
            lastEntry.label == label && 
            System.currentTimeMillis() - lastEntry.timestamp < 3000) {
            return false
        }
        
        // Limit total entries to avoid memory issues (Lowered to 30 for stability)
        if (entries.size >= 30) {
            val removed = entries.removeAt(0)
            removed.fullFrameImage?.recycle()
            removed.croppedImage?.recycle() 
            removed.maskImage?.recycle()
        }
        
        // Copy bitmaps to prevent use-after-recycle crashes
        val fullFrameCopy = fullFrame?.copy(fullFrame.config ?: android.graphics.Bitmap.Config.ARGB_8888, false)
        val croppedCopy = image?.copy(image.config ?: android.graphics.Bitmap.Config.ARGB_8888, false)
        val maskCopy = mask?.copy(mask.config ?: android.graphics.Bitmap.Config.ARGB_8888, false)
        
        val entry = LogEntry(nextId++, label, confidence, inferenceTimeMs, System.currentTimeMillis(), fullFrameCopy, croppedCopy, maskCopy, boundingBox, DetectionStatus.PENDING, isFlow2)
        entries.add(entry)
        notifyListeners()
        return true
    }
    
    /**
     * Get all logged entries.
     */
    @Synchronized
    fun getEntries(): List<LogEntry> = entries.toList()
    
    /**
     * Update the validation status of a log entry.
     */
    @Synchronized
    fun updateStatus(id: Int, status: DetectionStatus) {
        val entry = entries.find { it.id == id }
        Log.d("AILogManager", "updateStatus: id=$id, status=$status, found=${entry != null}, currentStatus=${entry?.status}")
        
        if (entry != null && entry.status == DetectionStatus.PENDING) {
            entry.status = status
            
            // Update performance monitor
            if (status == DetectionStatus.ACCEPTED) {
                PerformanceMonitor.incrementAccepted()
            } else if (status == DetectionStatus.REJECTED) {
                PerformanceMonitor.incrementRejected()
            }
            
            notifyListeners()
            Log.d("AILogManager", "Notifying ${statusListeners.size} status listeners")
            statusListeners.forEach { 
                try {
                    it.onStatusChanged(entry, status)
                } catch (e: Exception) {
                    Log.e("AILogManager", "Error in status listener", e)
                }
            }
        } else if (entry != null && entry.status == status) {
            Log.i("AILogManager", "Entry already has status $status, but re-notifying listeners for safety.")
            statusListeners.forEach { it.onStatusChanged(entry, status) }
        }
    }

    /**
     * Clear all entries.
     */
    @Synchronized
    fun clear() {
        entries.forEach { 
            it.croppedImage?.recycle()
            it.maskImage?.recycle()
        }
        entries.clear()
        nextId = 1
        notifyListeners()
    }
    
    /**
     * Get total entry count.
     */
    @Synchronized
    fun getCount(): Int = entries.size
    
    /**
     * Export entries as CSV text.
     */
    @Synchronized
    fun exportToCsv(): String {
        val sb = StringBuilder()
        sb.appendLine("ID,Label,Confidence,InferenceTime,Time")
        entries.forEach { entry ->
            val infSecs = String.format("%.1fs", entry.inferenceTimeMs / 1000.0)
            sb.appendLine("${entry.id},\"${entry.label}\",${entry.getConfidencePercent()},$infSecs,${entry.getFormattedTime()}")
        }
        return sb.toString()
    }
    
    /**
     * Add a listener for log updates.
     */
    fun addListener(listener: (List<LogEntry>) -> Unit) {
        listeners.add(listener)
    }
    
    /**
     * Remove a listener.
     */
    fun removeListener(listener: (List<LogEntry>) -> Unit) {
        listeners.remove(listener)
    }

    fun addStatusListener(listener: StatusChangeListener) {
        statusListeners.add(listener)
    }

    fun removeStatusListener(listener: StatusChangeListener) {
        statusListeners.remove(listener)
    }
    
    private fun notifyListeners() {
        val entriesCopy = entries.toList()
        listeners.forEach { it(entriesCopy) }
    }
}
