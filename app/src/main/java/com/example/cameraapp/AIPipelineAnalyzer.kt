package com.example.cameraapp

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RectF
import android.util.Log
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import kotlin.math.max
import kotlin.math.min

/**
 * Stage 2 & 3 of the AI Pipeline.
 * Stage 2: High-resolution segmentation on a cropped ROI.
 * Stage 3: Embedding extraction for SKU matching.
 */
class AIPipelineAnalyzer(private val context: Context) {

    private var segmentationInterpreter: Interpreter? = null
    private var embeddingInterpreter: Interpreter? = null
    
    private var segInputSize = 640 // Default, will be updated in initialize
    private var embInputSize = 224 // Default, will be updated in initialize
    
    private val TAG = "AIPipelineAnalyzer"

    fun initialize(): Boolean {
        return try {
            // Initialize Stage 2: YOLO Specialist (Segmentation)
            val segSource = ModelManager.getModelFile(ModelManager.YOLO_SPECIALIST)
            val segBuffer = when (segSource) {
                is ModelManager.ModelSource.Bundled -> loadModelFromAssets(segSource.assetName)
                is ModelManager.ModelSource.Cached -> loadModelFromFile(segSource.file)
            }
            segmentationInterpreter = Interpreter(segBuffer, Interpreter.Options().apply {
                setNumThreads(4)
                setUseXNNPACK(false)
            })
            
            // Detect segmentation input size
            segmentationInterpreter?.let {
                val inputShape = it.getInputTensor(0).shape()
                // Assuming [1, size, size, 3] or [1, 3, size, size]
                segInputSize = if (inputShape[1] > 10) inputShape[1] else inputShape[2]
                Log.i(TAG, "Detected Segmentation Input Size: $segInputSize")
            }

            // Initialize Stage 3: EfficientNet (Embeddings)
            val embSource = ModelManager.getModelFile(ModelManager.EFFICIENTNET_MODEL)
            val embBuffer = when (embSource) {
                is ModelManager.ModelSource.Bundled -> loadModelFromAssets(embSource.assetName)
                is ModelManager.ModelSource.Cached -> loadModelFromFile(embSource.file)
            }
            embeddingInterpreter = Interpreter(embBuffer, Interpreter.Options().apply {
                setNumThreads(4)
                setUseXNNPACK(false)
            })
            
            // Detect embedding input size
            embeddingInterpreter?.let {
                val inputShape = it.getInputTensor(0).shape()
                embInputSize = if (inputShape[1] > 10) inputShape[1] else inputShape[2]
                Log.i(TAG, "Detected Embedding Input Size: $embInputSize")
            }

            Log.i(TAG, "AIPipelineAnalyzer initialized successfully (Stages 2 & 3)")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize AIPipelineAnalyzer", e)
            false
        }
    }

    /**
     * Performs Stage 2 (Segmentation) and Stage 3 (Embedding) on a high-res ROI.
     */
    @Synchronized
    fun analyzeROI(fullFrame: Bitmap, roi: RectF, angle: Float = 0f): AnalysisResult? {
        val segInterp = segmentationInterpreter ?: return null
        
        // 1. Crop the ROI from the full frame (High-res)
        val cropX = roi.left.toInt().coerceIn(0, fullFrame.width - 1)
        val cropY = roi.top.toInt().coerceIn(0, fullFrame.height - 1)
        val cropW = roi.width().toInt().coerceAtLeast(1).coerceAtMost(fullFrame.width - cropX)
        val cropH = roi.height().toInt().coerceAtLeast(1).coerceAtMost(fullFrame.height - cropY)
        
        val croppedBitmap = if (angle != 0f) {
            extractRotatedCrop(fullFrame, roi, angle)
        } else {
            Bitmap.createBitmap(fullFrame, cropX, cropY, cropW, cropH)
        }

        // 2. Stage 2: Segmentation (Flow 1 ONLY)
        var mask: Bitmap? = null
        if (!AppSettings.useFlow2) {
            // YOLOv8-seg expects square input 
            val resizedForSeg = Bitmap.createScaledBitmap(croppedBitmap, segInputSize, segInputSize, true)
            val segInputBuffer = preprocessImage(resizedForSeg, segInputSize)
            
            val out0Shape = segInterp.getOutputTensor(0).shape() // [1, 37, 8400]
            val out1Shape = segInterp.getOutputTensor(1).shape() // [1, 32, 160, 160]
            
            val output0 = Array(1) { Array(out0Shape[1]) { FloatArray(out0Shape[2]) } }
            val output1 = Array(1) { Array(out1Shape[1]) { Array(out1Shape[2]) { FloatArray(out1Shape[3]) } } }
            
            val outputs = mutableMapOf<Int, Any>(0 to output0, 1 to output1)
            
            try {
                segInterp.runForMultipleInputsOutputs(arrayOf(segInputBuffer), outputs)
                // Parse mask (finding the highest confidence detection in this ROI crop)
                mask = parseBestMask(output0[0], output1[0], croppedBitmap.width, croppedBitmap.height)
            } catch (e: Exception) {
                Log.e(TAG, "Segmentation failed on ROI", e)
            }
            if (resizedForSeg != croppedBitmap) resizedForSeg.recycle()
        }

        // 3. Stage 3: Embedding
        var embedding: FloatArray? = null
        if (embeddingInterpreter != null) {
            val maskedBitmap = if (mask != null) applyMask(croppedBitmap, mask) else croppedBitmap
            val resizedForEmb = Bitmap.createScaledBitmap(maskedBitmap, embInputSize, embInputSize, true)
            val embInputBuffer = preprocessImage(resizedForEmb, embInputSize)
            
            val outputShape = embeddingInterpreter!!.getOutputTensor(0).shape()
            val outputBuffer = Array(1) { FloatArray(outputShape[1]) }
            
            try {
                embeddingInterpreter!!.run(embInputBuffer, outputBuffer)
                embedding = outputBuffer[0]
            } catch (e: Exception) {
                Log.e(TAG, "Embedding failed", e)
            }
            
            if (maskedBitmap != croppedBitmap) maskedBitmap.recycle()
            if (resizedForEmb != maskedBitmap) resizedForEmb.recycle()
        }

        // Cleanup
        // In OBB mode, we don't have resizedForSeg, so we just return
        // We return the croppedBitmap and mask for UI/Logging, let the caller handle recycling
        
        return AnalysisResult(croppedBitmap, mask, embedding)
    }

    /**
     * Public helper to extract embedding from a pre-cropped/masked image.
     */
    @Synchronized
    fun extractEmbedding(bitmap: Bitmap): FloatArray {
        val interp = embeddingInterpreter ?: return FloatArray(0)
        
        val resized = Bitmap.createScaledBitmap(bitmap, embInputSize, embInputSize, true)
        val inputBuffer = preprocessImage(resized, embInputSize)
        
        val outputShape = interp.getOutputTensor(0).shape()
        val outputBuffer = Array(1) { FloatArray(outputShape[1]) }
        
        return try {
            interp.run(inputBuffer, outputBuffer)
            outputBuffer[0]
        } catch (e: Exception) {
            Log.e(TAG, "Standalone embedding failed", e)
            FloatArray(0)
        } finally {
            if (resized != bitmap) resized.recycle()
        }
    }

    private fun parseBestMask(
        output0: Array<FloatArray>,
        prototypes: Array<Array<FloatArray>>,
        width: Int,
        height: Int
    ): Bitmap? {
        // Since we are looking at a cropped ROI, the "best" detection should be centered
        val numDetections = output0[0].size
        val numClasses = output0.size - 4 - 32
        
        var bestIdx = -1
        var maxScore = 0.5f // Minimum confidence
        
        for (i in 0 until numDetections) {
            for (c in 0 until numClasses) {
                val score = output0[c + 4][i]
                if (score > maxScore) {
                    maxScore = score
                    bestIdx = i
                }
            }
        }
        
        if (bestIdx == -1) return null
        
        // Extract 32 mask coefficients
        val coefficients = FloatArray(32)
        for (j in 0 until 32) {
            coefficients[j] = output0[numClasses + 4 + j][bestIdx]
        }
        
        // Bounding box of the detection WITHIN the crop
        val bx = output0[0][bestIdx] * width
        val by = output0[1][bestIdx] * height
        val bw = output0[2][bestIdx] * width
        val bh = output0[3][bestIdx] * height
        val box = RectF(bx - bw/2, by - bh/2, bx + bw/2, by + bh/2)
        
        return processMask(coefficients, prototypes, box, width, height)
    }

    private fun processMask(
        coefficients: FloatArray,
        prototypes: Array<Array<FloatArray>>,
        box: RectF,
        width: Int,
        height: Int
    ): Bitmap? {
        val protoSize = prototypes.size
        val mask = Bitmap.createBitmap(protoSize, protoSize, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(protoSize * protoSize)
        
        for (y in 0 until protoSize) {
            for (x in 0 until protoSize) {
                var sum = 0f
                for (i in 0 until 32) {
                    sum += coefficients[i] * prototypes[y][x][i]
                }
                val prob = 1.0f / (1.0f + Math.exp(-sum.toDouble()).toFloat())
                if (prob > 0.5f) {
                    pixels[y * protoSize + x] = Color.WHITE
                } else {
                    pixels[y * protoSize + x] = Color.TRANSPARENT
                }
            }
        }
        mask.setPixels(pixels, 0, protoSize, 0, 0, protoSize, protoSize)
        
        // Scale and crop the mask to the detected box
        val scaleX = protoSize.toFloat() / width
        val scaleY = protoSize.toFloat() / height
        
        val cL = (box.left * scaleX).toInt().coerceIn(0, protoSize - 1)
        val cT = (box.top * scaleY).toInt().coerceIn(0, protoSize - 1)
        val cW = (box.width() * scaleX).toInt().coerceAtLeast(1).coerceAtMost(protoSize - cL)
        val cH = (box.height() * scaleY).toInt().coerceAtLeast(1).coerceAtMost(protoSize - cT)
        
        val cropped = Bitmap.createBitmap(mask, cL, cT, cW, cH)
        mask.recycle()
        
        val final = Bitmap.createScaledBitmap(cropped, width, height, true)
        cropped.recycle()
        return final
    }

    private fun applyMask(original: Bitmap, mask: Bitmap): Bitmap {
        val result = Bitmap.createBitmap(original.width, original.height, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(result)
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        
        canvas.drawBitmap(original, 0f, 0f, null)
        paint.xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DST_IN)
        canvas.drawBitmap(mask, 0f, 0f, paint)
        
        return result
    }

    private fun preprocessImage(bitmap: Bitmap, size: Int): ByteBuffer {
        val buffer = ByteBuffer.allocateDirect(1 * size * size * 3 * 4)
        buffer.order(ByteOrder.nativeOrder())
        buffer.rewind()
        val pixels = IntArray(size * size)
        bitmap.getPixels(pixels, 0, size, 0, 0, size, size)
        for (pixel in pixels) {
            buffer.putFloat(((pixel shr 16) and 0xFF) / 255f)
            buffer.putFloat(((pixel shr 8) and 0xFF) / 255f)
            buffer.putFloat((pixel and 0xFF) / 255f)
        }
        buffer.rewind()
        return buffer
    }

    private fun loadModelFromAssets(assetName: String): MappedByteBuffer {
        val fd = context.assets.openFd(assetName)
        val fis = FileInputStream(fd.fileDescriptor)
        return fis.channel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
    }

    private fun loadModelFromFile(file: java.io.File): MappedByteBuffer {
        val fis = FileInputStream(file)
        return fis.channel.map(FileChannel.MapMode.READ_ONLY, 0, file.length())
    }

    private fun extractRotatedCrop(fullFrame: Bitmap, roi: RectF, angle: Float): Bitmap {
        // Enlarge ROI slightly to avoid black corners after rotation
        val padding = 1.2f
        val cx = roi.centerX()
        val cy = roi.centerY()
        val w = roi.width() * padding
        val h = roi.height() * padding
        
        val left = (cx - w / 2).toInt().coerceIn(0, fullFrame.width - 1)
        val top = (cy - h / 2).toInt().coerceIn(0, fullFrame.height - 1)
        val width = w.toInt().coerceAtMost(fullFrame.width - left)
        val height = h.toInt().coerceAtMost(fullFrame.height - top)
        
        val baseCrop = Bitmap.createBitmap(fullFrame, left, top, width, height)
        val matrix = android.graphics.Matrix()
        matrix.postRotate(-angle) // Counter-rotate to straighten
        
        val rotated = Bitmap.createBitmap(baseCrop, 0, 0, baseCrop.width, baseCrop.height, matrix, true)
        if (rotated != baseCrop) baseCrop.recycle()
        
        // Final crop to exact (straightened) ROI size
        val finalW = roi.width().toInt().coerceAtMost(rotated.width)
        val finalH = roi.height().toInt().coerceAtMost(rotated.height)
        val finalX = (rotated.width - finalW) / 2
        val finalY = (rotated.height - finalH) / 2
        
        val finalResult = Bitmap.createBitmap(rotated, finalX, finalY, finalW, finalH)
        if (finalResult != rotated) rotated.recycle()
        
        return finalResult
    }

    fun close() {
        segmentationInterpreter?.close()
        embeddingInterpreter?.close()
    }

    data class AnalysisResult(
        val croppedImage: Bitmap,
        val mask: Bitmap?,
        val embedding: FloatArray?
    )
}
