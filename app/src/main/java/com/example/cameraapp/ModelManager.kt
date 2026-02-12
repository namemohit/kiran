package com.example.cameraapp

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

/**
 * Manages TFLite model files with support for OTA updates.
 * - Uses bundled models from assets as baseline
 * - Checks server for newer versions
 * - Downloads and caches updated models to internal storage
 * - Provides the newest available model file for inference
 */
object ModelManager {
    
    private const val TAG = "ModelManager"
    
    // Model file names
    const val YOLO_SCOUT = "yolov8n.tflite"          // Model 1: Trigger/Tracker
    const val YOLO_SPECIALIST = "best_yolov8n_v0_256_float16.tflite" // Model 2: Segmenter
    const val YOLO_OBB_MODEL = "best_yolov8n_obb_v0_256_float16.tflite" // Model 4: OBB
    const val EFFICIENTNET_MODEL = "efficientnet_lite0.tflite" // Model 3: Matcher
    
    @Deprecated("Use YOLO_SCOUT or YOLO_SPECIALIST")
    const val YOLO_MODEL = YOLO_SCOUT
    @Deprecated("Use YOLO_SPECIALIST")
    const val YOLO_SEG_MODEL = YOLO_SPECIALIST
    
    // Bundled model versions (update these when shipping new APK)
    private const val BUNDLED_YOLO_VERSION = "1.0.0"
    private const val BUNDLED_EFFICIENTNET_VERSION = "1.0.0"
    
    // Server URL for model updates
    private const val MODEL_SERVER_URL = "http://10.0.2.2:8000"
    
    private var context: Context? = null
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()
    
    // Cached model info
    private var cachedYoloVersion: String? = null
    private var cachedEfficientNetVersion: String? = null
    
    fun init(context: Context) {
        this.context = context.applicationContext
        loadCachedVersions()
        Log.i(TAG, "ModelManager initialized")
        Log.i(TAG, "YOLO: bundled=$BUNDLED_YOLO_VERSION, cached=$cachedYoloVersion")
        Log.i(TAG, "EfficientNet: bundled=$BUNDLED_EFFICIENTNET_VERSION, cached=$cachedEfficientNetVersion")
    }
    
    /**
     * Get the best available model file path.
     * Returns cached model if newer than bundled, otherwise uses bundled from assets.
     */
    fun getModelFile(modelName: String): ModelSource {
        val ctx = context ?: throw IllegalStateException("ModelManager not initialized")
        
        val cachedFile = File(ctx.filesDir, "models/$modelName")
        
        return if (cachedFile.exists() && isCachedNewer(modelName)) {
            Log.i(TAG, "Using cached model: $modelName")
            ModelSource.Cached(cachedFile)
        } else {
            Log.i(TAG, "Using bundled model: $modelName")
            ModelSource.Bundled(modelName)
        }
    }
    
    /**
     * Check server for model updates and download if available.
     * Call this in background on app startup.
     */
    suspend fun checkForUpdates() = withContext(Dispatchers.IO) {
        try {
            Log.i(TAG, "Checking for model updates...")
            
            // Get model info from server
            val modelInfo = fetchModelInfo()
            if (modelInfo == null) {
                Log.w(TAG, "Could not fetch model info from server")
                return@withContext
            }
            
            // Check YOLO model
            val currentYoloVersion = cachedYoloVersion ?: BUNDLED_YOLO_VERSION
            if (isNewer(modelInfo.yoloVersion, currentYoloVersion)) {
                Log.i(TAG, "New YOLO model available: ${modelInfo.yoloVersion} > $currentYoloVersion")
                downloadModel(YOLO_MODEL, modelInfo.yoloVersion)
            }
            
            // Check EfficientNet model
            val currentEffVersion = cachedEfficientNetVersion ?: BUNDLED_EFFICIENTNET_VERSION
            if (isNewer(modelInfo.efficientNetVersion, currentEffVersion)) {
                Log.i(TAG, "New EfficientNet model available: ${modelInfo.efficientNetVersion} > $currentEffVersion")
                downloadModel(EFFICIENTNET_MODEL, modelInfo.efficientNetVersion)
            }
            
            Log.i(TAG, "Model update check complete")
            
        } catch (e: Exception) {
            Log.e(TAG, "Error checking for updates", e)
        }
    }
    
    private fun fetchModelInfo(): ModelInfo? {
        return try {
            val request = Request.Builder()
                .url("$MODEL_SERVER_URL/api/models/info")
                .build()
            
            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val json = response.body?.string() ?: return null
                parseModelInfo(json)
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch model info", e)
            null
        }
    }
    
    private fun parseModelInfo(json: String): ModelInfo? {
        return try {
            // Simple JSON parsing
            val yoloVersion = Regex("\"yolo_version\"\\s*:\\s*\"([^\"]+)\"")
                .find(json)?.groupValues?.get(1) ?: return null
            val effVersion = Regex("\"efficientnet_version\"\\s*:\\s*\"([^\"]+)\"")
                .find(json)?.groupValues?.get(1) ?: return null
            ModelInfo(yoloVersion, effVersion)
        } catch (e: Exception) {
            null
        }
    }
    
    private fun downloadModel(modelName: String, version: String) {
        val ctx = context ?: return
        
        try {
            val request = Request.Builder()
                .url("$MODEL_SERVER_URL/api/models/download/$modelName")
                .build()
            
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                Log.e(TAG, "Failed to download $modelName: ${response.code}")
                return
            }
            
            val modelsDir = File(ctx.filesDir, "models")
            modelsDir.mkdirs()
            
            val modelFile = File(modelsDir, modelName)
            val tempFile = File(modelsDir, "$modelName.tmp")
            
            // Write to temp file first
            response.body?.byteStream()?.use { input ->
                FileOutputStream(tempFile).use { output ->
                    input.copyTo(output)
                }
            }
            
            // Rename to final file
            tempFile.renameTo(modelFile)
            
            // Save version
            saveModelVersion(modelName, version)
            
            Log.i(TAG, "Downloaded $modelName version $version (${modelFile.length()} bytes)")
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to download $modelName", e)
        }
    }
    
    private fun loadCachedVersions() {
        val ctx = context ?: return
        val prefs = ctx.getSharedPreferences("model_versions", Context.MODE_PRIVATE)
        cachedYoloVersion = prefs.getString("yolo_version", null)
        cachedEfficientNetVersion = prefs.getString("efficientnet_version", null)
    }
    
    private fun saveModelVersion(modelName: String, version: String) {
        val ctx = context ?: return
        val prefs = ctx.getSharedPreferences("model_versions", Context.MODE_PRIVATE)
        
        when (modelName) {
            YOLO_MODEL -> {
                prefs.edit().putString("yolo_version", version).apply()
                cachedYoloVersion = version
            }
            EFFICIENTNET_MODEL -> {
                prefs.edit().putString("efficientnet_version", version).apply()
                cachedEfficientNetVersion = version
            }
        }
    }
    
    private fun isCachedNewer(modelName: String): Boolean {
        val bundledVersion = when (modelName) {
            YOLO_MODEL -> BUNDLED_YOLO_VERSION
            EFFICIENTNET_MODEL -> BUNDLED_EFFICIENTNET_VERSION
            else -> return false
        }
        
        val cachedVersion = when (modelName) {
            YOLO_MODEL -> cachedYoloVersion
            EFFICIENTNET_MODEL -> cachedEfficientNetVersion
            else -> return false
        }
        
        return cachedVersion != null && isNewer(cachedVersion, bundledVersion)
    }
    
    /**
     * Compare semantic versions. Returns true if v1 > v2.
     */
    private fun isNewer(v1: String, v2: String): Boolean {
        val parts1 = v1.split(".").mapNotNull { it.toIntOrNull() }
        val parts2 = v2.split(".").mapNotNull { it.toIntOrNull() }
        
        for (i in 0 until maxOf(parts1.size, parts2.size)) {
            val p1 = parts1.getOrElse(i) { 0 }
            val p2 = parts2.getOrElse(i) { 0 }
            if (p1 > p2) return true
            if (p1 < p2) return false
        }
        return false
    }
    
    // Data classes
    data class ModelInfo(
        val yoloVersion: String,
        val efficientNetVersion: String
    )
    
    sealed class ModelSource {
        data class Bundled(val assetName: String) : ModelSource()
        data class Cached(val file: File) : ModelSource()
    }
}
