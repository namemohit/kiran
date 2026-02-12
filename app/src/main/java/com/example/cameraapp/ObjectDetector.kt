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

/**
 * Object detector using YOLOv8 TFLite model.
 * Supports dynamic model loading from ModelManager.
 */
class ObjectDetector(private val context: Context) {

    var interpreter: Interpreter? = null
        private set

    val isSegmentationSupported: Boolean
        get() = (interpreter?.outputTensorCount ?: 0) >= 2
    private var labels: List<String> = emptyList()
    private val tracker = ObjectTracker()
    
    // YOLOv8 input size for the Scout (typically 320px for speed)
    private var currentInputSize = AppSettings.yoloInputSize
    
    // Detection thresholds
    private var confidenceThreshold = 0.10f
    private val iouThreshold = 0.45f

    companion object {
        private const val TAG = "ObjectDetector"
    }

    fun initialize(): Boolean {
        return try {
            val modelSource = ModelManager.getModelFile(ModelManager.YOLO_SCOUT)

            val modelBuffer = when (modelSource) {
                is ModelManager.ModelSource.Bundled -> loadModelFromAssets(modelSource.assetName)
                is ModelManager.ModelSource.Cached -> loadModelFromFile(modelSource.file)
            }
            
            // Use CPU inference (GPU not reliable on all devices/emulators)
            val options = Interpreter.Options().apply {
                setNumThreads(4)
                setUseXNNPACK(false) // Disable XNNPACK for stability (fixes runtime crash)
            }
            val interp = Interpreter(modelBuffer, options)
            
            // CRITICAL: Allocate tensors immediately after interpreter creation
            interp.allocateTensors()
            
            interpreter = interp
            
            val outputCount = interp.outputTensorCount
            val inputShape = interp.getInputTensor(0).shape()
            currentInputSize = inputShape[1]
            Log.i(TAG, "YOLO Initialized. Model: ${if (modelSource is ModelManager.ModelSource.Bundled) modelSource.assetName else "Cached"}, Outputs: $outputCount, InputSize: $currentInputSize")
            
            // Log shapes for diagnostics
            for (i in 0 until outputCount) {
                val shape = interp.getOutputTensor(i).shape()
                Log.d(TAG, "Output $i shape: ${shape.contentToString()}")
            }
            
            // Load labels
            labels = loadLabels()
            
            Log.i(TAG, "ObjectDetector initialized with ${labels.size} labels")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize ObjectDetector", e)
            false
        }
    }

    private fun loadModelFromAssets(assetName: String): MappedByteBuffer {
        val assetManager = context.assets
        val fileDescriptor = assetManager.openFd(assetName)
        val inputStream = FileInputStream(fileDescriptor.fileDescriptor)
        val fileChannel = inputStream.channel
        return fileChannel.map(
            FileChannel.MapMode.READ_ONLY,
            fileDescriptor.startOffset,
            fileDescriptor.declaredLength
        )
    }
    
    private fun loadModelFromFile(file: File): MappedByteBuffer {
        val inputStream = FileInputStream(file)
        val fileChannel = inputStream.channel
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, 0, file.length())
    }

    private fun loadLabels(): List<String> {
        return try {
            context.assets.open("labels.txt").bufferedReader().readLines()
        } catch (e: Exception) {
            Log.w(TAG, "Could not load labels.txt, using default COCO labels")
            getDefaultCocoLabels()
        }
    }

    @Synchronized
    fun detect(bitmap: Bitmap): List<Detection> {
        val currentInterpreter = interpreter ?: return emptyList()

        // Use the model's native input size (determined at initialization)
        // Dynamic resize has been removed due to tensor allocation instability
        val resizedBitmap = Bitmap.createScaledBitmap(bitmap, currentInputSize, currentInputSize, true)
        val inputBuffer = preprocessImage(resizedBitmap, currentInputSize)
        
        // Run detection
        val outputShape = currentInterpreter.getOutputTensor(0).shape()
        val outputBuffer = Array(1) { Array(outputShape[1]) { FloatArray(outputShape[2]) } }
        
        val detections = try {
            currentInterpreter.run(inputBuffer, outputBuffer)
            parseYoloOutput(outputBuffer[0], bitmap.width, bitmap.height)
        } catch (e: Exception) {
            Log.e(TAG, "Scout inference failed: ${e.message}")
            emptyList<Detection>()
        }
        
        // Track objects
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

    private fun parseYoloOutput(
        output: Array<FloatArray>,
        imgWidth: Int,
        imgHeight: Int
    ): List<Detection> {
        val detections = mutableListOf<Detection>()
        val numClasses = output.size - 4
        val numDetections = output[0].size
        
        for (i in 0 until numDetections) {
            var maxScore = 0f
            var maxClassIdx = 0
            for (c in 0 until numClasses) {
                val score = output[c + 4][i]
                if (score > maxScore) {
                    maxScore = score
                    maxClassIdx = c
                }
            }
            if (maxScore < AppSettings.yoloThreshold) continue
            
            val cx = output[0][i]
            val cy = output[1][i]
            val w = output[2][i]
            val h = output[3][i]
            
            val x1 = (cx - w / 2) * imgWidth
            val y1 = (cy - h / 2) * imgHeight
            val x2 = (cx + w / 2) * imgWidth
            val y2 = (cy + h / 2) * imgHeight
            
            val box = RectF(max(0f, x1), max(0f, y1), min(imgWidth.toFloat(), x2), min(imgHeight.toFloat(), y2))
            val label = if (maxClassIdx < labels.size) labels[maxClassIdx] else "class_$maxClassIdx"
            
            detections.add(Detection(box, label, maxClassIdx, maxScore, mask = null))
        }
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
                if (calculateIoU(sorted[i].boundingBox, sorted[j].boundingBox) > iouThreshold) {
                    used[j] = true
                }
            }
        }
        
        // Recycle masks of suppressed detections
        for (i in sorted.indices) {
            if (used[i] && !result.contains(sorted[i])) {
                sorted[i].mask?.recycle()
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
    
    private fun getDefaultCocoLabels(): List<String> = listOf(
        "person", "bicycle", "car", "motorcycle", "airplane", "bus", "train", "truck", "boat",
        "traffic light", "fire hydrant", "stop sign", "parking meter", "bench", "bird", "cat",
        "dog", "horse", "sheep", "cow", "elephant", "bear", "zebra", "giraffe", "backpack",
        "umbrella", "handbag", "tie", "suitcase", "frisbee", "skis", "snowboard", "sports ball",
        "kite", "baseball bat", "baseball glove", "skateboard", "surfboard", "tennis racket",
        "bottle", "wine glass", "cup", "fork", "knife", "spoon", "bowl", "banana", "apple",
        "sandwich", "orange", "broccoli", "carrot", "hot dog", "pizza", "donut", "cake", "chair",
        "couch", "potted plant", "bed", "dining table", "toilet", "tv", "laptop", "mouse",
        "remote", "keyboard", "cell phone", "microwave", "oven", "toaster", "sink", "refrigerator",
        "book", "clock", "vase", "scissors", "teddy bear", "hair drier", "toothbrush"
    )
}


