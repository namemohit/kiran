package com.example.cameraapp

import android.content.Context
import android.content.SharedPreferences

/**
 * Manages app settings including inference mode (local vs cloud).
 */
object AppSettings {
    
    private const val PREFS_NAME = "camera_app_settings"
    private const val KEY_YOLO_THRESHOLD = "yolo_threshold"
    private const val KEY_YOLO_SEG_THRESHOLD = "yolo_seg_threshold"
    private const val KEY_CLASS_THRESHOLD = "class_threshold"
    private const val KEY_BEEP_STABILITY = "beep_stability"
    private const val KEY_TARGET_FPS = "target_fps"
    private const val KEY_YOLO_INPUT_SIZE = "yolo_input_size"
    private const val KEY_INFERENCE_MODE = "inference_mode"
    private const val KEY_BACKEND_URL = "backend_url"
    private const val KEY_DARK_MODE = "dark_mode"
    private const val KEY_USE_FLOW2 = "use_flow_2"
    
    enum class InferenceMode {
        LOCAL,   // Use on-device TensorFlow Lite models
        CLOUD    // Use backend API for inference
    }
    
    private var prefs: SharedPreferences? = null
    
    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }
    
    var yoloThreshold: Float
        get() = prefs?.getFloat(KEY_YOLO_THRESHOLD, 0.40f) ?: 0.40f
        set(value) { prefs?.edit()?.putFloat(KEY_YOLO_THRESHOLD, value)?.apply() }

    var yoloSegThreshold: Float
        get() = prefs?.getFloat(KEY_YOLO_SEG_THRESHOLD, 0.30f) ?: 0.30f
        set(value) { prefs?.edit()?.putFloat(KEY_YOLO_SEG_THRESHOLD, value)?.apply() }

    private const val KEY_OBB_THRESHOLD = "obb_threshold"
    var obbThreshold: Float
        get() = prefs?.getFloat(KEY_OBB_THRESHOLD, 0.25f) ?: 0.25f
        set(value) { prefs?.edit()?.putFloat(KEY_OBB_THRESHOLD, value)?.apply() }

    var classThreshold: Float
        get() = prefs?.getFloat(KEY_CLASS_THRESHOLD, 0.10f) ?: 0.10f
        set(value) { prefs?.edit()?.putFloat(KEY_CLASS_THRESHOLD, value)?.apply() }

    var beepStability: Int
        get() = prefs?.getInt(KEY_BEEP_STABILITY, 3) ?: 3
        set(value) { prefs?.edit()?.putInt(KEY_BEEP_STABILITY, value)?.apply() }

    var targetDetectionFps: Int
        get() = prefs?.getInt(KEY_TARGET_FPS, 30) ?: 30
        set(value) { prefs?.edit()?.putInt(KEY_TARGET_FPS, value)?.apply() }

    var yoloInputSize: Int
        get() = prefs?.getInt(KEY_YOLO_INPUT_SIZE, 320) ?: 320
        set(value) { prefs?.edit()?.putInt(KEY_YOLO_INPUT_SIZE, value)?.apply() }


    var inferenceMode: InferenceMode
        get() {
            val value = prefs?.getString(KEY_INFERENCE_MODE, InferenceMode.LOCAL.name)
            return try {
                InferenceMode.valueOf(value ?: InferenceMode.LOCAL.name)
            } catch (e: Exception) {
                InferenceMode.LOCAL
            }
        }
        set(value) {
            prefs?.edit()?.putString(KEY_INFERENCE_MODE, value.name)?.apply()
        }
    
    var backendUrl: String
        get() = prefs?.getString(KEY_BACKEND_URL, "http://10.0.2.2:8000/") ?: "http://10.0.2.2:8000/"
        set(value) {
            prefs?.edit()?.putString(KEY_BACKEND_URL, value)?.apply()
        }

    var isDarkMode: Boolean
        get() = prefs?.getBoolean(KEY_DARK_MODE, false) ?: false
        set(value) {
            prefs?.edit()?.putBoolean(KEY_DARK_MODE, value)?.apply()
        }

    var useFlow2: Boolean
        get() = prefs?.getBoolean(KEY_USE_FLOW2, true) ?: true
        set(value) {
            prefs?.edit()?.putBoolean(KEY_USE_FLOW2, value)?.apply()
        }
    
    fun isCloudMode(): Boolean = inferenceMode == InferenceMode.CLOUD
    fun isLocalMode(): Boolean = inferenceMode == InferenceMode.LOCAL
}
