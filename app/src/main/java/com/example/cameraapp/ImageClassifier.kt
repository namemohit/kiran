package com.example.cameraapp

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import org.tensorflow.lite.Interpreter
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

/**
 * Image classifier using EfficientNet-Lite TFLite model.
 * Supports dynamic model loading from ModelManager.
 */
class ImageClassifier(private val context: Context) {

    private var interpreter: Interpreter? = null
    private var labels: List<String> = emptyList()
    
    // EfficientNet-Lite0 input size
    private val inputSize = 224
    private val topK = 5

    companion object {
        private const val TAG = "ImageClassifier"
    }

    private var isModelQuantized = true

    fun initialize(): Boolean {
        return try {
            val modelSource = ModelManager.getModelFile(ModelManager.EFFICIENTNET_MODEL)
            val modelBuffer = when (modelSource) {
                is ModelManager.ModelSource.Bundled -> loadModelFromAssets(modelSource.assetName)
                is ModelManager.ModelSource.Cached -> loadModelFromFile(modelSource.file)
            }
            
            val options = Interpreter.Options().apply {
                setNumThreads(4)
                setUseXNNPACK(false) // Disable XNNPACK for stability (fixes runtime crash)
            }
            val interp = Interpreter(modelBuffer, options)
            interpreter = interp
            
            // Detect if model is quantized (UINT8) or floating point (FLOAT32)
            val inputTensor = interp.getInputTensor(0)
            val outputTensor = interp.getOutputTensor(0)
            isModelQuantized = inputTensor.dataType() == org.tensorflow.lite.DataType.UINT8
            
            Log.i(TAG, "ImageClassifier initialized. Quantized=$isModelQuantized")
            Log.i(TAG, "Input: ${inputTensor.dataType()}, Shape=${inputTensor.shape().contentToString()}")
            Log.i(TAG, "Output: ${outputTensor.dataType()}, Shape=${outputTensor.shape().contentToString()}")
            
            labels = loadLabels()
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize ImageClassifier", e)
            false
        }
    }

    private fun loadModelFromAssets(assetName: String): java.nio.MappedByteBuffer {
        val assetManager = context.assets
        val fileDescriptor = assetManager.openFd(assetName)
        val inputStream = java.io.FileInputStream(fileDescriptor.fileDescriptor)
        val fileChannel = inputStream.channel
        return fileChannel.map(
            java.nio.channels.FileChannel.MapMode.READ_ONLY,
            fileDescriptor.startOffset,
            fileDescriptor.declaredLength
        )
    }
    
    private fun loadModelFromFile(file: java.io.File): java.nio.MappedByteBuffer {
        val inputStream = java.io.FileInputStream(file)
        val fileChannel = inputStream.channel
        return fileChannel.map(java.nio.channels.FileChannel.MapMode.READ_ONLY, 0, file.length())
    }

    private fun loadLabels(): List<String> {
        return try {
            context.assets.open("imagenet_labels.txt").bufferedReader().readLines()
        } catch (e: Exception) {
            Log.w(TAG, "Could not load imagenet_labels.txt", e)
            emptyList()
        }
    }

    @Synchronized
    fun classify(bitmap: Bitmap): List<ClassificationResult> {
        val currentInterpreter = interpreter ?: return emptyList()
        if (labels.isEmpty()) return emptyList()

        val resizedBitmap = Bitmap.createScaledBitmap(bitmap, inputSize, inputSize, true)
        val inputBuffer = preprocessImage(resizedBitmap)
        
        val outputShape = currentInterpreter.getOutputTensor(0).shape()
        val numLabels = outputShape[1]
        
        val results = if (isModelQuantized) {
            val outputBuffer = Array(1) { ByteArray(numLabels) }
            currentInterpreter.run(inputBuffer, outputBuffer)
            parseOutputQuantized(outputBuffer[0])
        } else {
            val outputBuffer = Array(1) { FloatArray(numLabels) }
            currentInterpreter.run(inputBuffer, outputBuffer)
            parseOutputFloat(outputBuffer[0])
        }
        
        // Log top results for debugging (always show top 3 even if below threshold)
        if (results.isEmpty()) {
            Log.d(TAG, "No results above threshold ${AppSettings.classThreshold}")
        } else {
            val topR = results.first()
            Log.d(TAG, "Winner: ${topR.label} (${topR.confidence})")
        }
        
        if (resizedBitmap != bitmap) {
            resizedBitmap.recycle()
        }
        return results
    }

    /**
     * Extracts a raw embedding vector from the image.
     */
    @Synchronized
    fun extractEmbedding(bitmap: Bitmap): FloatArray {
        val currentInterpreter = interpreter ?: return floatArrayOf()
        
        val resizedBitmap = Bitmap.createScaledBitmap(bitmap, inputSize, inputSize, true)
        val inputBuffer = preprocessImage(resizedBitmap)
        
        val outputShape = currentInterpreter.getOutputTensor(0).shape()
        val numLabels = outputShape[1]
        
        val embedding = if (isModelQuantized) {
            val outputBuffer = Array(1) { ByteArray(numLabels) }
            currentInterpreter.run(inputBuffer, outputBuffer)
            // Convert quantized uint8 [0, 255] to float [0, 1]
            FloatArray(numLabels) { i -> (outputBuffer[0][i].toInt() and 0xFF) / 255.0f }
        } else {
            val outputBuffer = Array(1) { FloatArray(numLabels) }
            currentInterpreter.run(inputBuffer, outputBuffer)
            outputBuffer[0].copyOf()
        }
        
        if (resizedBitmap != bitmap) {
            resizedBitmap.recycle()
        }
        
        return embedding
    }

    private fun preprocessImage(bitmap: Bitmap): ByteBuffer {
        val numBytesPerChannel = if (isModelQuantized) 1 else 4
        val buffer = ByteBuffer.allocateDirect(1 * inputSize * inputSize * 3 * numBytesPerChannel)
        buffer.order(ByteOrder.nativeOrder())
        buffer.rewind()
        
        val pixels = IntArray(inputSize * inputSize)
        bitmap.getPixels(pixels, 0, inputSize, 0, 0, inputSize, inputSize)
        
        for (pixel in pixels) {
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF
            
            if (isModelQuantized) {
                buffer.put(r.toByte())
                buffer.put(g.toByte())
                buffer.put(b.toByte())
            } else {
                buffer.putFloat((r - 127.5f) / 127.5f)
                buffer.putFloat((g - 127.5f) / 127.5f)
                buffer.putFloat((b - 127.5f) / 127.5f)
            }
        }
        buffer.rewind()
        return buffer
    }

    private fun parseOutputQuantized(probabilities: ByteArray): List<ClassificationResult> {
        val sortedList = probabilities.mapIndexed { index, prob -> 
            val unsignedProb = prob.toInt() and 0xFF
            index to (unsignedProb / 255.0f)
        }.sortedByDescending { it.second }
        
        // Log Top 3 for every call to help debug low scores
        val top3 = sortedList.take(3).joinToString { "${if (it.first < labels.size) labels[it.first] else it.first}(${it.second})" }
        Log.v(TAG, "Quantized Raw Top 3: $top3")

        return sortedList.filter { it.second >= AppSettings.classThreshold }
            .take(topK)
            .map { (index, prob) ->
                val label = if (index < labels.size) labels[index] else "class_$index"
                ClassificationResult(label, prob)
            }
    }

    private fun parseOutputFloat(probabilities: FloatArray): List<ClassificationResult> {
        val sortedList = probabilities.mapIndexed { index, prob -> index to prob }
            .sortedByDescending { it.second }
            
        // Log Top 3 for every call
        val top3 = sortedList.take(3).joinToString { "${if (it.first < labels.size) labels[it.first] else it.first}(${it.second})" }
        Log.v(TAG, "Float Raw Top 3: $top3")

        return sortedList.filter { it.second >= AppSettings.classThreshold }
            .take(topK)
            .map { (index, prob) ->
                val label = if (index < labels.size) labels[index] else "class_$index"
                ClassificationResult(label, prob)
            }
    }

    fun close() {
        interpreter?.close()
        interpreter = null
    }
}


// Removed redundant ClassificationResult data class as it is now shared in Detection.kt

