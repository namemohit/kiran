package com.example.cameraapp

import android.graphics.RectF
import kotlin.math.max
import kotlin.math.min

/**
 * Simple IOU-based object tracker to assign unique IDs across frames.
 */
class ObjectTracker {
    private var nextId = 1
    private val trackedObjects = mutableListOf<TrackedObject>()
    private val iouThreshold = 0.3f
    private val maxFramesLost = 10

    private class TrackedObject(
        val id: Int,
        val labelIndex: Int,
        var lastBox: RectF
    ) {
        var framesLost = 0
        var framesTracked = 0
    }

    fun track(newDetections: List<Detection>): List<Detection> {
        val results = mutableListOf<Detection>()
        val unmatchedNew = newDetections.toMutableList()

        // Match existing tracks to new detections
        for (tracked in trackedObjects) {
            tracked.framesLost++
            
            var bestMatch: Detection? = null
            var bestIou = iouThreshold

            for (detection in unmatchedNew) {
                if (detection.labelIndex == tracked.labelIndex) {
                    val iou = calculateIoU(tracked.lastBox, detection.boundingBox)
                    if (iou > bestIou) {
                        bestIou = iou
                        bestMatch = detection
                    }
                }
            }

            if (bestMatch != null) {
                tracked.lastBox = bestMatch.boundingBox
                tracked.framesLost = 0
                tracked.framesTracked++
                bestMatch.trackingId = tracked.id
                bestMatch.framesTracked = tracked.framesTracked
                results.add(bestMatch)
                unmatchedNew.remove(bestMatch)
            }
        }

        // Create new tracks for unmatched detections
        for (newDet in unmatchedNew) {
            val newTrack = TrackedObject(nextId++, newDet.labelIndex, newDet.boundingBox)
            newTrack.framesTracked = 1
            newDet.trackingId = newTrack.id
            newDet.framesTracked = 1
            trackedObjects.add(newTrack)
            results.add(newDet)
        }

        // Remove lost tracks
        trackedObjects.removeAll { it.framesLost > maxFramesLost }
        
        return results
    }

    private fun calculateIoU(box1: RectF, box2: RectF): Float {
        val x1 = max(box1.left, box2.left)
        val y1 = max(box1.top, box2.top)
        val x2 = min(box1.right, box2.right)
        val y2 = min(box1.bottom, box2.bottom)
        
        val intersection = max(0f, x2 - x1) * max(0f, y2 - y1)
        val area1 = box1.width() * box1.height()
        val area2 = box2.width() * box2.height()
        val union = area1 + area2 - intersection
        
        return if (union > 0) intersection / union else 0f
    }
}
