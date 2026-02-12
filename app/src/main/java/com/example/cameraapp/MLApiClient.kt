package com.example.cameraapp

import android.graphics.Bitmap
import android.util.Log
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.*
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

/**
 * API client for cloud-based ML inference.
 * Handles communication with the backend server for YOLOv8 and EfficientNet.
 */
object MLApiClient {
    
    private const val TAG = "MLApiClient"
    
    // Backend server URL - change this for production
    // For local testing with Android emulator, use 10.0.2.2 instead of localhost
    private const val BASE_URL = "http://10.0.2.2:8000/"
    
    // Response data classes matching server API
    data class DetectionResult(
        @SerializedName("label") val label: String,
        @SerializedName("confidence") val confidence: Float,
        @SerializedName("bbox") val bbox: List<Float> // [x1, y1, x2, y2] normalized 0-1
    )
    
    data class ClassificationResult(
        @SerializedName("label") val label: String,
        @SerializedName("confidence") val confidence: Float
    )
    
    data class DetectionResponse(
        @SerializedName("success") val success: Boolean,
        @SerializedName("detections") val detections: List<DetectionResult>,
        @SerializedName("inference_time_ms") val inferenceTimeMs: Float
    )
    
    data class ClassificationResponse(
        @SerializedName("success") val success: Boolean,
        @SerializedName("classifications") val classifications: List<ClassificationResult>,
        @SerializedName("inference_time_ms") val inferenceTimeMs: Float
    )
    
    data class ConfigResponse(
        @SerializedName("app_version") val appVersion: String,
        @SerializedName("min_confidence_detection") val minConfidenceDetection: Float,
        @SerializedName("min_confidence_classification") val minConfidenceClassification: Float,
        @SerializedName("models_available") val modelsAvailable: List<String>,
        @SerializedName("features") val features: Map<String, Boolean>
    )
    
    // Retrofit API interface
    interface MLApiService {
        @GET("api/config")
        suspend fun getConfig(): Response<ConfigResponse>
        
        @Multipart
        @POST("api/detect")
        suspend fun detectObjects(
            @Part file: MultipartBody.Part
        ): Response<DetectionResponse>
        
        @Multipart
        @POST("api/classify")
        suspend fun classifyImage(
            @Part file: MultipartBody.Part
        ): Response<ClassificationResponse>
    }
    
    // Retrofit instance
    private val retrofit: Retrofit by lazy {
        val logging = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BASIC
        }
        
        val client = OkHttpClient.Builder()
            .addInterceptor(logging)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
        
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
    }
    
    private val apiService: MLApiService by lazy {
        retrofit.create(MLApiService::class.java)
    }
    
    /**
     * Check if server is available and get configuration.
     */
    suspend fun getConfig(): ConfigResponse? = withContext(Dispatchers.IO) {
        try {
            val response = apiService.getConfig()
            if (response.isSuccessful) {
                response.body()
            } else {
                Log.e(TAG, "Config request failed: ${response.code()}")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Config request error", e)
            null
        }
    }
    
    /**
     * Send image to server for object detection (YOLOv8).
     */
    suspend fun detectObjects(bitmap: Bitmap): List<Detection> = withContext(Dispatchers.IO) {
        try {
            val imagePart = bitmapToMultipart(bitmap, "file")
            val response = apiService.detectObjects(imagePart)
            
            if (response.isSuccessful && response.body()?.success == true) {
                val body = response.body()!!
                Log.d(TAG, "Detection: ${body.detections.size} objects in ${body.inferenceTimeMs}ms")
                
                // Convert API response to app's Detection format
                body.detections.map { apiDetection ->
                    val bbox = apiDetection.bbox
                    val box = android.graphics.RectF(
                        bbox[0] * bitmap.width,
                        bbox[1] * bitmap.height,
                        bbox[2] * bitmap.width,
                        bbox[3] * bitmap.height
                    )
                    Detection(
                        boundingBox = box,
                        label = apiDetection.label,
                        labelIndex = 0,
                        confidence = apiDetection.confidence
                    )
                }
            } else {
                Log.e(TAG, "Detection failed: ${response.code()} ${response.message()}")
                emptyList()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Detection error", e)
            emptyList()
        }
    }
    
    /**
     * Send image to server for classification (EfficientNet).
     */
    suspend fun classifyImage(bitmap: Bitmap): List<ClassificationResult> = withContext(Dispatchers.IO) {
        try {
            val imagePart = bitmapToMultipart(bitmap, "file")
            val response = apiService.classifyImage(imagePart)
            
            if (response.isSuccessful && response.body()?.success == true) {
                val body = response.body()!!
                Log.d(TAG, "Classification: ${body.classifications.size} results in ${body.inferenceTimeMs}ms")
                body.classifications
            } else {
                Log.e(TAG, "Classification failed: ${response.code()} ${response.message()}")
                emptyList()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Classification error", e)
            emptyList()
        }
    }
    
    /**
     * Convert Bitmap to MultipartBody.Part for upload.
     */
    private fun bitmapToMultipart(bitmap: Bitmap, name: String): MultipartBody.Part {
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 85, stream)
        val byteArray = stream.toByteArray()
        
        val requestBody = byteArray.toRequestBody("image/jpeg".toMediaType())
        return MultipartBody.Part.createFormData(name, "image.jpg", requestBody)
    }
    
    /**
     * Update the base URL (for switching between dev/prod servers).
     */
    fun setBaseUrl(url: String) {
        // Would need to rebuild Retrofit instance for URL change
        Log.i(TAG, "URL change requested to: $url")
    }
}
