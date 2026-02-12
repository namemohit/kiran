package com.example.cameraapp

import android.graphics.Bitmap
import android.graphics.RectF

/**
 * Classification result from EfficientNet.
 */
data class ClassificationResult(
    val label: String,
    val confidence: Float
)

/**
 * Detection result from YOLO models.
 */
data class Detection(
    val boundingBox: RectF,
    var label: String,
    val labelIndex: Int,
    val confidence: Float,
    var trackingId: Int = -1,
    var framesTracked: Int = 0,
    var mask: Bitmap? = null,
    var angle: Float = 0f, // For OBB (in degrees)
    var isLogged: Boolean = false,
    var classifierResult: ClassificationResult? = null
)
