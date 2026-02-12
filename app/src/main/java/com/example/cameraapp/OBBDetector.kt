package com.example.cameraapp

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.util.Log
import org.tensorflow.lite.Interpreter
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import kotlin.math.max
import kotlin.math.min
import kotlin.math.PI

/**
 * Object detector using YOLOv8-OBB TFLite model.
 * Provides oriented bounding boxes (rotation support).
 */
class OBBDetector(private val context: Context) {

    private var interpreter: Interpreter? = null
    private var labels: List<String> = emptyList()
    private val tracker = ObjectTracker()
    private var currentInputSize = 320 // Default, will be updated from model
    
    // Detection thresholds
    private val confidenceThreshold: Float
        get() = AppSettings.obbThreshold
    private val iouThreshold = 0.45f

    companion object {
        private const val TAG = "OBBDetector"
    }

    fun initialize(): Boolean {
        return try {
            val modelSource = ModelManager.getModelFile(ModelManager.YOLO_OBB_MODEL)

            val modelBuffer = when (modelSource) {
                is ModelManager.ModelSource.Bundled -> loadModelFromAssets(modelSource.assetName)
                is ModelManager.ModelSource.Cached -> loadModelFromFile(modelSource.file)
            }
            
            val options = Interpreter.Options().apply {
                setNumThreads(4)
                setUseXNNPACK(false)
            }
            val interp = Interpreter(modelBuffer, options)
            interp.allocateTensors()
            
            val inputShape = interp.getInputTensor(0).shape()
            currentInputSize = inputShape[1]
            
            interpreter = interp
            labels = loadLabels()
            
            Log.i(TAG, "OBBDetector initialized. InputSize: $currentInputSize, Classes: ${labels.size}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize OBBDetector", e)
            false
        }
    }

    private fun loadModelFromAssets(assetName: String): MappedByteBuffer {
        val assetManager = context.assets
        val fileDescriptor = assetManager.openFd(assetName)
        val inputStream = FileInputStream(fileDescriptor.fileDescriptor)
        val fileChannel = inputStream.channel
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, fileDescriptor.startOffset, fileDescriptor.declaredLength)
    }
    
    private fun loadModelFromFile(file: File): MappedByteBuffer {
        val inputStream = FileInputStream(file)
        val fileChannel = inputStream.channel
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, 0, file.length())
    }

    private fun loadLabels(): List<String> {
        return try {
            context.assets.open("labels_obb.txt").bufferedReader().readLines()
        } catch (e: Exception) {
            // If labels_obb.txt doesn't exist, try labels.txt
            try {
                context.assets.open("labels.txt").bufferedReader().readLines()
            } catch (e2: Exception) {
                listOf("item") // Fallback
            }
        }
    }

    @Synchronized
    fun detect(bitmap: Bitmap): List<Detection> {
        val currentInterpreter = interpreter ?: return emptyList()

        val resizedBitmap = Bitmap.createScaledBitmap(bitmap, currentInputSize, currentInputSize, true)
        val inputBuffer = preprocessImage(resizedBitmap, currentInputSize)
        
        val outputShape = currentInterpreter.getOutputTensor(0).shape() // [1, num_channels, N]
        val outputBuffer = Array(1) { Array(outputShape[1]) { FloatArray(outputShape[2]) } }
        
        val detections = try {
            currentInterpreter.run(inputBuffer, outputBuffer)
            parseOBBOutput(outputBuffer[0], bitmap.width, bitmap.height)
        } catch (e: Exception) {
            Log.e(TAG, "OBB inference failed: ${e.message}")
            emptyList<Detection>()
        }
        
        val trackedDetections = tracker.track(detections)
        
        if (resizedBitmap != bitmap) {
            resizedBitmap.recycle()
        }
        
        return trackedDetections
    }

    private fun preprocessImage(bitmap: Bitmap, size: Int): ByteBuffer {
        val buffer = ByteBuffer.allocateDirect(1 * size * size * 3 * 4)
        buffer.order(ByteOrder.nativeOrder())
        buffer.rewind()
        
        val pixels = IntArray(size * size)
        bitmap.getPixels(pixels, 0, size, 0, 0, size, size)
        
        for (pixel in pixels) {
            buffer.putFloat(((pixel shr 16) and 0xFF) / 255.0f)
            buffer.putFloat(((pixel shr 8) and 0xFF) / 255.0f)
            buffer.putFloat((pixel and 0xFF) / 255.0f)
        }
        
        buffer.rewind()
        return buffer
    }

    private fun parseOBBOutput(
        output: Array<FloatArray>,
        imgWidth: Int,
        imgHeight: Int
    ): List<Detection> {
        val detections = mutableListOf<Detection>()
        
        // Log shapes to debug
        val dim1 = output.size
        val dim2 = if (dim1 > 0) output[0].size else 0
        Log.v(TAG, "OBB Output Shape: [1, $dim1, $dim2], InputSize: $currentInputSize")

        // CASE 2: TFLite Object Detection API format [detections, channels]
        // Often [300, 6] or [300, 7].
        if (dim1 == 300 && (dim2 == 6 || dim2 == 7)) {
            Log.i(TAG, "Detected TFLite Object Detection API format [300, $dim2]")
            
            // Debug: print the first detection to see values
            if (dim1 > 0) {
                val d0 = output[0]
                val rawStr = d0.joinToString(", ") { String.format("%.3f", it) }
                Log.d(TAG, "Raw Det[0]: [$rawStr]")
            }

            for (i in 0 until dim1) {
                val detection = output[i] // [channels]
                
                // Assuming format: [cx, cy, w, h, score, class, angle]
                // If 6 channels: [cx, cy, w, h, score, class]
                
                val score = detection[4]
                if (score < confidenceThreshold) continue
                
                val classIdx = detection[5].toInt()
                val angleRad = if (dim2 == 7) detection[6] else 0f
                
                // Coordinates
                val v0 = detection[0]
                val v1 = detection[1]
                val v2 = detection[2]
                val v3 = detection[3]
                
                // Check if normalized (0..1) or pixel (0..256)
                // If values are > 1.0, they are pixels.
                val isPixel = (v0 > 1.0f || v2 > 1.0f)
                val normFactor = if (isPixel) currentInputSize.toFloat() else 1.0f

                val cx = v0 / normFactor
                val cy = v1 / normFactor
                val w = v2 / normFactor
                val h = v3 / normFactor

                val nx = cx * imgWidth
                val ny = cy * imgHeight
                val nw = w * imgWidth
                val nh = h * imgHeight
                
                val angleDeg = (angleRad * 180.0 / PI).toFloat()
                
                val box = RectF(
                    nx - nw / 2, 
                    ny - nh / 2, 
                    nx + nw / 2, 
                    ny + nh / 2
                )
                
                val label = if (classIdx >= 0 && classIdx < labels.size) labels[classIdx] else "Class $classIdx"
                
                detections.add(Detection(
                    boundingBox = box,
                    label = label,
                    labelIndex = classIdx,
                    confidence = score,
                    angle = angleDeg
                ))
            }
        } 
        // CASE 1: Standard YOLOv8 [channels, detections] e.g. [20, 8400]
        else if (dim1 > dim2 && dim1 > 6) { 
             val numChannels = dim1
             val numDetections = dim2
            
             for (i in 0 until numDetections) {
                var maxScore = 0f
                var bestClassIdx = -1
                
                // Identify best class score
                // OBB format: [cx, cy, w, h, class_0...class_N, angle]
                // angle is at LAST index: numChannels - 1
                
                val classStart = 4
                val angleIdx = numChannels - 1
                val numClasses = numChannels - 5 
                
                for (c in 0 until numClasses) {
                     val score = output[c + 4][i]
                     if (score > maxScore) {
                         maxScore = score
                         bestClassIdx = c
                     }
                }
                
                if (maxScore < confidenceThreshold) continue
                
                val cx = output[0][i]
                val cy = output[1][i]
                val w = output[2][i]
                val h = output[3][i]
                val angleRad = output[angleIdx][i] 
                
                val sx = if (cx > 1.1f) 1.0f / currentInputSize else 1.0f
                val sy = if (cy > 1.1f) 1.0f / currentInputSize else 1.0f
                
                val nx = cx * sx * imgWidth
                val ny = cy * sy * imgHeight
                val nw = w * sx * imgWidth
                val nh = h * sy * imgHeight
                
                val angleDeg = (angleRad * 180.0 / PI).toFloat()
                val box = RectF(nx - nw / 2, ny - nh / 2, nx + nw / 2, ny + nh / 2)
                val label = if (bestClassIdx != -1 && bestClassIdx < labels.size) labels[bestClassIdx] else "item_$bestClassIdx"
                
                detections.add(Detection(box, label, bestClassIdx, maxScore, angle = angleDeg))
             }
        }
        
        Log.d(TAG, "OBB Parsed Detections: ${detections.size}")
        return applyNMS(detections)
    }

    private fun applyNMS(detections: List<Detection>): List<Detection> {
        if (detections.isEmpty()) return emptyList()
        
        val sorted = detections.sortedByDescending { it.confidence }
        val result = mutableListOf<Detection>()
        val used = BooleanArray(sorted.size)
        
        for (i in sorted.indices) {
            if (used[i]) continue
            result.add(sorted[i])
            
            for (j in i + 1 until sorted.size) {
                if (used[j]) continue
                // Simple IoU on AABB for now, true OBB IoU is complex in pure Kotlin
                if (calculateIoU(sorted[i].boundingBox, sorted[j].boundingBox) > iouThreshold) {
                    used[j] = true
                }
            }
        }
        return result
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

    fun close() {
        interpreter?.close()
        interpreter = null
    }
}
