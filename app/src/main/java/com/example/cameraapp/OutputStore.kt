package com.example.cameraapp

import android.graphics.Bitmap
import android.net.Uri

/**
 * Stores processed media results with detection overlays.
 */
object OutputStore {
    
    data class OutputItem(
        val id: Long,
        val sourceUri: Uri?,
        val resultBitmap: Bitmap,
        val detections: List<Detection>,
        val timestamp: Long
    )
    
    private val items = mutableListOf<OutputItem>()
    private val listeners = mutableListOf<() -> Unit>()
    private var nextId = 1L
    
    fun addOutput(sourceUri: Uri?, resultBitmap: Bitmap, detections: List<Detection>) {
        synchronized(items) {
            items.add(0, OutputItem(
                id = nextId++,
                sourceUri = sourceUri,
                resultBitmap = resultBitmap,
                detections = detections,
                timestamp = System.currentTimeMillis()
            ))
            // Keep only last 50 items
            if (items.size > 50) {
                items.removeAt(items.size - 1)
            }
        }
        notifyListeners()
    }
    
    fun getItems(): List<OutputItem> {
        synchronized(items) {
            return items.toList()
        }
    }
    
    fun clear() {
        synchronized(items) {
            items.forEach { it.resultBitmap.recycle() }
            items.clear()
        }
        notifyListeners()
    }
    
    fun addListener(listener: () -> Unit) {
        listeners.add(listener)
    }
    
    fun removeListener(listener: () -> Unit) {
        listeners.remove(listener)
    }
    
    private fun notifyListeners() {
        listeners.forEach { it() }
    }
}
