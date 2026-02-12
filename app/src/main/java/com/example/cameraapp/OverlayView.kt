package com.example.cameraapp

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.util.Log
import android.view.View

/**
 * Custom View for drawing detection bounding boxes over the camera preview.
 */
class OverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var detections: List<Detection> = emptyList()
    private var classifications: List<ClassificationResult> = emptyList()
    private var scaleX: Float = 1f
    private var scaleY: Float = 1f
    private var offsetX: Float = 0f
    private var offsetY: Float = 0f
    private var imageWidth: Int = 0
    private var imageHeight: Int = 0
    private var lastUpdateTime: Long = 0
    private var fps: Int = 0
    private var frameCount: Int = 0
    private var fpsStartTime: Long = System.currentTimeMillis()

    // Color palette for different classes
    private val colors = listOf(
        Color.parseColor("#FF6B6B"), // Red
        Color.parseColor("#4ECDC4"), // Teal
        Color.parseColor("#45B7D1"), // Blue
        Color.parseColor("#96CEB4"), // Green
        Color.parseColor("#FFEAA7"), // Yellow
        Color.parseColor("#DDA0DD"), // Plum
        Color.parseColor("#98D8C8"), // Mint
        Color.parseColor("#F7DC6F"), // Gold
        Color.parseColor("#BB8FCE"), // Purple
        Color.parseColor("#85C1E9")  // Sky Blue
    )

    private val boxPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 8f
        isAntiAlias = true
    }

    private val boxFillPaint = Paint().apply {
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val textBackgroundPaint = Paint().apply {
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val textPaint = Paint().apply {
        color = Color.WHITE
        textSize = 48f
        isAntiAlias = true
        isFakeBoldText = true
        setShadowLayer(4f, 2f, 2f, Color.BLACK)
    }

    private val statusBackgroundPaint = Paint().apply {
        style = Paint.Style.FILL
        color = Color.argb(180, 0, 0, 0)
        isAntiAlias = true
    }

    private val statusTextPaint = Paint().apply {
        color = Color.WHITE
        textSize = 36f
        isAntiAlias = true
    }

    private val activeIndicatorPaint = Paint().apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#4ECDC4")
        isAntiAlias = true
    }

    // Pre-allocated paints for detections to avoid allocations in onDraw
    private val classPaint = Paint().apply {
        color = Color.WHITE
        textSize = 28f
        isAntiAlias = true
        isFakeBoldText = true
    }

    private val detailPaint = Paint().apply {
        color = Color.argb(255, 255, 255, 255)
        textSize = 22f
        isAntiAlias = true
    }

    private val classBgPaint = Paint().apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#CC4ECDC4")
        isAntiAlias = true
    }

    private val maskPaint = Paint().apply {
        isAntiAlias = true
    }

    /**
     * Update detections to be drawn.
     */
    fun setDetections(detections: List<Detection>, imageWidth: Int, imageHeight: Int) {
        // Recycle old masks to prevent memory leaks
        this.detections.forEach { it.mask?.recycle() }
        
        this.detections = detections
        this.imageWidth = imageWidth
        this.imageHeight = imageHeight
        this.lastUpdateTime = System.currentTimeMillis()
        
        // Calculate FPS
        frameCount++
        val elapsed = System.currentTimeMillis() - fpsStartTime
        if (elapsed >= 1000) {
            fps = frameCount
            frameCount = 0
            fpsStartTime = System.currentTimeMillis()
        }
        
        // Calculate scale factors for FILL_CENTER (scale to fill, then center-crop)
        // FILL_CENTER uses the LARGER scale factor to ensure the image fills the view
        if (width > 0 && height > 0 && imageWidth > 0 && imageHeight > 0) {
            val viewWidth = width.toFloat()
            val viewHeight = height.toFloat()
            
            val scaleByWidth = viewWidth / imageWidth.toFloat()
            val scaleByHeight = viewHeight / imageHeight.toFloat()
            
            // FILL_CENTER uses max scale (fill the view, crop excess)
            val scale = maxOf(scaleByWidth, scaleByHeight)
            scaleX = scale
            scaleY = scale
            
            // Calculate how much of the scaled image extends beyond the view (cropped parts)
            val scaledImageWidth = imageWidth * scale
            val scaledImageHeight = imageHeight * scale
            
            // The offset is negative because FILL_CENTER crops the excess
            // (image is larger than view, so we shift it back to center)
            offsetX = (viewWidth - scaledImageWidth) / 2f
            offsetY = (viewHeight - scaledImageHeight) / 2f
            
            Log.d("OverlayView", "setDetections: viewSize=${width}x${height}, " +
                    "imageSize=${imageWidth}x${imageHeight}, scale=$scale, " +
                    "offset=($offsetX,$offsetY), detections=${detections.size}")
        }
        
        invalidate()
    }

    /**
     * Clear all detections.
     */
    fun clear() {
        detections = emptyList()
        classifications = emptyList()
        invalidate()
    }
    
    /**
     * Update classifications to be displayed.
     */
    fun setClassifications(classifications: List<ClassificationResult>) {
        this.classifications = classifications
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // Detections only, no status header as requested

        // Draw bounding boxes for each detection
        for (detection in detections) {
            drawDetection(canvas, detection)
        }
        
        // Removed: drawClassificationBanner(canvas) - Now shown below each box
    }

    // Detections only, no status header as requested

    private fun drawDetection(canvas: Canvas, detection: Detection) {
        // Scale bounding box to view size with proper offset for centering
        val scaledBox = RectF(
            detection.boundingBox.left * scaleX + offsetX,
            detection.boundingBox.top * scaleY + offsetY,
            detection.boundingBox.right * scaleX + offsetX,
            detection.boundingBox.bottom * scaleY + offsetY
        )

        // --- Boundary Check: Only draw if FULLY inside the visible view area ---
        if (scaledBox.left < 0 || scaledBox.top < 0 || 
            scaledBox.right > width || scaledBox.bottom > height) {
            return
        }

        val mainGreen = Color.parseColor("#4CAF50") 
        val loggedBlue = Color.parseColor("#45B7D1")
        val accentColor = if (detection.isLogged) loggedBlue else mainGreen
        
        boxPaint.color = accentColor
        textBackgroundPaint.color = accentColor

        // 1. Draw Segmentation Mask or Box Fill
        detection.mask?.let { mask ->
            if (!mask.isRecycled) {
                maskPaint.colorFilter = android.graphics.PorterDuffColorFilter(
                    Color.argb(160, 76, 175, 80), 
                    android.graphics.PorterDuff.Mode.SRC_IN
                )
                canvas.drawBitmap(mask, null, scaledBox, maskPaint)
            }
        } ?: run {
            boxFillPaint.color = Color.argb(40, 76, 175, 80)
            canvas.drawRect(scaledBox, boxFillPaint)
        }

        // 2. Draw Bounding Box Border (with rotation if OBB)
        val hasRotation = detection.angle != 0f
        if (hasRotation) {
            canvas.save()
            canvas.rotate(detection.angle, scaledBox.centerX(), scaledBox.centerY())
        }

        boxPaint.strokeWidth = if (detection.mask != null) 4f else 8f
        canvas.drawRect(scaledBox, boxPaint)
        
        // 3. Prepare Metadata Labels
        // YOLO Label (Model: YOLOv8n - Scout or OBB)
        val modelName = if (AppSettings.useFlow2) "YOLOv8-OBB" else "YOLOv8n"
        val idStr = if (detection.trackingId != -1) "Temp ID: ${detection.trackingId}" else "New Object"
        val yoloLabel = "$modelName: ${(detection.confidence * 100).toInt()}%"
        
        // Logged Badge
        val logLabel = if (detection.isLogged) " [LOGGED]" else ""
        
        val topTitle = "$idStr$logLabel"
        val subTitle = yoloLabel

        // 4. Draw Label Header (Top)
        textPaint.textSize = 32f
        val topTitleWidth = textPaint.measureText(topTitle)
        val subTitleWidth = textPaint.measureText(subTitle)
        val headerWidth = maxOf(topTitleWidth, subTitleWidth) + 24f
        val headerHeight = textPaint.textSize * 2.2f + 16f

        val headerRect = RectF(
            scaledBox.left,
            scaledBox.top - headerHeight,
            scaledBox.left + headerWidth,
            scaledBox.top
        )
        canvas.drawRoundRect(headerRect, 12f, 12f, textBackgroundPaint)

        // Draw texts in header
        textPaint.color = Color.WHITE
        canvas.drawText(topTitle, scaledBox.left + 12f, scaledBox.top - (headerHeight / 1.8f) - 4f, textPaint)
        
        textPaint.textSize = 24f
        textPaint.color = Color.argb(220, 255, 255, 255)
        canvas.drawText(subTitle, scaledBox.left + 12f, scaledBox.top - 12f, textPaint)

        // 5. Draw EfficientNet Classification (Bottom)
        val classification = detection.classifierResult
        if (classification != null) {
            val classLabel = "EfficientNet: ${(classification.confidence * 100).toInt()}%"
            val classDetail = classification.label.uppercase()
            
            val classWidth = maxOf(classPaint.measureText(classLabel), classPaint.measureText(classDetail)) + 24f
            val classRect = RectF(
                scaledBox.left,
                scaledBox.bottom,
                scaledBox.left + classWidth,
                scaledBox.bottom + 80f
            )
            canvas.drawRoundRect(classRect, 12f, 12f, classBgPaint)
            
            canvas.drawText(classDetail, scaledBox.left + 12f, scaledBox.bottom + 35f, classPaint)
            canvas.drawText(classLabel, scaledBox.left + 12f, scaledBox.bottom + 65f, detailPaint)
        }

        if (hasRotation) {
            canvas.restore()
        }
    }
}
