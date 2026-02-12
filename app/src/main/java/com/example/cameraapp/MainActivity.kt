package com.example.cameraapp

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.lifecycle.lifecycleScope
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.example.cameraapp.databinding.ActivityMainBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import android.media.AudioManager
import android.media.ToneGenerator
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var imageCapture: ImageCapture? = null
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var objectDetector: ObjectDetector
    private lateinit var obbDetector: OBBDetector
    private lateinit var pipelineAnalyzer: AIPipelineAnalyzer
    private lateinit var productRepository: ProductRepository
    private var cameraProvider: ProcessCameraProvider? = null
    
    // Tracking and Audio Feedback
    private var toneGenerator = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 100)
    private var lastInferenceTime = 0L
    private val processedTrackIds = mutableSetOf<Int>()

    companion object {
        private const val TAG = "CameraApp"
        private const val FILENAME_FORMAT = "yyyy-MM-dd-HH-mm-ss-SSS"
    }

    // Permission request launcher
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            startCamera()
        } else {
            Toast.makeText(
                this,
                "Camera permission is required for this app to work",
                Toast.LENGTH_LONG
            ).show()
            AppLogger.e(TAG, "Camera permission denied")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Initialize settings first to apply theme
        AppSettings.init(this)
        applyTheme()

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        AppLogger.i(TAG, "MainActivity created")

        // Model manager depends on AppSettings
        ModelManager.init(this)

        // Initialize camera executor
        cameraExecutor = Executors.newSingleThreadExecutor()

        // Initialize local TFLite inference
        objectDetector = ObjectDetector(this)
        objectDetector.initialize()

        obbDetector = OBBDetector(this)
        obbDetector.initialize()
        
        AppLogger.i(TAG, "Detectors initialized. Active flow: ${if (AppSettings.useFlow2) "Flow 2 (OBB)" else "Flow 1 (Standard)"}")

        // Update UI with model info
        val modelInfo = StringBuilder()
        if (AppSettings.useFlow2) {
            modelInfo.append("OBB: ${ModelManager.YOLO_OBB_MODEL}\n")
        } else {
            modelInfo.append("Scout: ${ModelManager.YOLO_SCOUT}\n")
            modelInfo.append("Seg: ${ModelManager.YOLO_SPECIALIST}\n")
        }
        modelInfo.append("Matcher: ${ModelManager.EFFICIENTNET_MODEL}")
        
        binding.modelInfoText.text = modelInfo.toString()

        pipelineAnalyzer = AIPipelineAnalyzer(this)
        pipelineAnalyzer.initialize()

        productRepository = ProductRepository(this)

        // Check for model updates in background
        CoroutineScope(Dispatchers.IO).launch {
            ModelManager.checkForUpdates()
        }

        // Set up bottom navigation
        setupBottomNavigation()

        if (hasCameraPermission()) {
            startCamera()
        } else {
            requestCameraPermission()
        }

        // Set up capture button click listener
        binding.captureButton.setOnClickListener {
            takePhoto()
        }

        binding.cameraSettingsButton.setOnClickListener {
            CameraSettingsBottomSheet.newInstance().show(supportFragmentManager, CameraSettingsBottomSheet.TAG)
        }
    }

    private val statusListener = object : AILogManager.StatusChangeListener {
        override fun onStatusChanged(entry: AILogManager.LogEntry, status: AILogManager.DetectionStatus) {
            Log.i(TAG, "onStatusChanged: [${entry.label}] -> $status")
            if (status == AILogManager.DetectionStatus.ACCEPTED) {
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "Onboarding detected: ${entry.label}", Toast.LENGTH_SHORT).show()
                }
                handleAcceptedDetection(entry)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        AILogManager.addStatusListener(statusListener)
        Log.d(TAG, "Status listener registered in onStart")
    }

    override fun onStop() {
        super.onStop()
        AILogManager.removeStatusListener(statusListener)
        Log.d(TAG, "Status listener removed in onStop")
    }

    private fun applyTheme() {
        val mode = if (AppSettings.isDarkMode) {
            AppCompatDelegate.MODE_NIGHT_YES
        } else {
            AppCompatDelegate.MODE_NIGHT_NO
        }
        AppCompatDelegate.setDefaultNightMode(mode)
    }

    private fun handleAcceptedDetection(entry: AILogManager.LogEntry) {
        val label = entry.label.trim()
        val bitmap = entry.croppedImage
        
        if (bitmap == null || bitmap.isRecycled) {
            Log.e(TAG, "Cannot process acceptance: croppedImage is null or recycled")
            Toast.makeText(this, "Error: Image lost! Try scanning again.", Toast.LENGTH_SHORT).show()
            return
        }

        if (label.isEmpty()) {
            Log.w(TAG, "Ignoring acceptance for empty label")
            return
        }
        
        Log.i(TAG, ">>> handleAcceptedDetection started for: [$label]")
        Toast.makeText(this, "Processing SKU: $label", Toast.LENGTH_SHORT).show()
        
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                Log.d(TAG, "Extracting embedding for $label...")
                val segmentedBitmap = if (entry.maskImage != null) {
                    applyMask(bitmap, entry.maskImage)
                } else {
                    bitmap
                }

                val embedding = pipelineAnalyzer.extractEmbedding(segmentedBitmap)
                
                if (entry.maskImage != null && segmentedBitmap != bitmap) {
                    segmentedBitmap.recycle()
                }

                if (embedding.isEmpty()) {
                    Log.e(TAG, "Embedding extraction failed (empty array) for $label")
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@MainActivity, "Failed to analyze $label", Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }
                
                Log.d(TAG, "Embedding extracted (size: ${embedding.size}). Looking for product...")

                // 3. Determine which product to tag
                val product = productRepository.getOrCreateProductByName(label)
                Log.d(TAG, "Product found/created: ${product.name} (ID: ${product.id})")

                // 4. Save embedding (Tagged to SKU)
                Log.d(TAG, "Adding embedding to product ID: ${product.id}...")
                productRepository.addEmbedding(product.id, embedding)
                Log.i(TAG, "Successfully saved embedding for ${product.name}")
                
                withContext(Dispatchers.Main) {
                    Log.i(TAG, "SUCCESS: SKU List should now show updated count for $label")
                    Toast.makeText(this@MainActivity, "SKU Updated: ${product.name}", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                Log.e(TAG, "CRITICAL: Failed to save accepted detection for $label", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Failed to save ${entry.label}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    /**
     * Applies a binary mask to the source bitmap (Blacks out non-white pixels).
     */
    private fun applyMask(source: Bitmap, mask: Bitmap): Bitmap {
        val result = source.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = android.graphics.Canvas(result)
        val paint = android.graphics.Paint()
        
        // Use PorterDuff to mask the source with the mask bitmap
        paint.xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DST_IN)
        canvas.drawBitmap(mask, 0f, 0f, paint)
        
        return result
    }

    private fun setupBottomNavigation() {
        binding.bottomNavigation.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_catalog -> {
                    showFragment(CatalogFragment.newInstance())
                    AppLogger.d(TAG, "Switched to Catalog tab")
                    true
                }
                R.id.nav_detections -> {
                    showFragment(AILogFragment.newInstance())
                    AppLogger.d(TAG, "Switched to AI Detections tab")
                    true
                }
                R.id.nav_camera -> {
                    showCameraView()
                    AppLogger.d(TAG, "Switched to Camera tab")
                    true
                }
                R.id.nav_biz -> {
                    showFragment(BizFragment.newInstance())
                    AppLogger.d(TAG, "Switched to Biz tab")
                    true
                }
                R.id.nav_profile -> {
                    showFragment(ProfileFragment.newInstance())
                    AppLogger.d(TAG, "Switched to Profile tab")
                    true
                }
                else -> false
            }
        }

        // Set camera as default selected
        binding.bottomNavigation.selectedItemId = R.id.nav_camera

        // Training Mode UI Observer
        VisualSearchManager.trainingProduct.observe(this) { product ->
            if (product != null) {
                binding.trainingOverlay.visibility = View.VISIBLE
                binding.trainingStatusText.text = "Training: ${product.name}"
                binding.captureButton.visibility = View.GONE // Hide standard photo button
            } else {
                binding.trainingOverlay.visibility = View.GONE
                binding.captureButton.visibility = View.VISIBLE
            }
        }

        binding.stopTrainingButton.setOnClickListener {
            VisualSearchManager.stopTraining()
        }

        binding.saveSampleButton.setOnClickListener {
            captureTrainingSample()
        }
    }

    private var currentTrainingEmbedding: FloatArray? = null

    private fun captureTrainingSample() {
        val embedding = currentTrainingEmbedding
        val productId = VisualSearchManager.trainingProductId
        
        if (embedding != null && productId != null) {
            lifecycleScope.launch(Dispatchers.IO) {
                val db = AppDatabase.getDatabase(this@MainActivity)
                db.productDao().insertEmbedding(ProductEmbedding(productId = productId, vector = embedding))
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Sample captured! Move camera for another angle.", Toast.LENGTH_SHORT).show()
                }
            }
        } else {
            Toast.makeText(this, "No object detected to capture!", Toast.LENGTH_SHORT).show()
        }
    }

    fun showCameraView() {
        binding.cameraContainer.visibility = View.VISIBLE
        binding.fragmentContainer.visibility = View.GONE
        
        // Resume camera if it was stopped
        if (cameraProvider == null && hasCameraPermission()) {
            startCamera()
        }
    }

    fun showFragment(fragment: Fragment) {
        binding.cameraContainer.visibility = View.GONE
        binding.fragmentContainer.visibility = View.VISIBLE

        supportFragmentManager.beginTransaction()
            .setCustomAnimations(
                R.anim.slide_in_right,  // enter
                R.anim.slide_out_left,  // exit
                R.anim.slide_in_left,   // popEnter
                R.anim.slide_out_right  // popExit
            )
            .replace(R.id.fragmentContainer, fragment)
            .commit()
    }

    private fun hasCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestCameraPermission() {
        requestPermissionLauncher.launch(Manifest.permission.CAMERA)
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            // Get camera provider
            cameraProvider = cameraProviderFuture.get()
            
            // Configure PreviewView scale type
            binding.previewView.scaleType = androidx.camera.view.PreviewView.ScaleType.FILL_CENTER

            // Set up Preview use case
            val preview = Preview.Builder()
                .build()
                .also {
                    it.setSurfaceProvider(binding.previewView.surfaceProvider)
                }

            // Set up ImageCapture use case
            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build()

            // Set up ImageAnalysis use case for object detection
            val imageAnalysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                // Use default YUV format; ImageProxy.toBitmap() handles conversion efficiently
                .build()
                .also {
                    it.setAnalyzer(cameraExecutor) { imageProxy ->
                        processImage(imageProxy)
                    }
                }

            // Select rear camera
            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                // Unbind all use cases before rebinding
                cameraProvider?.unbindAll()

                // Bind use cases to camera lifecycle
                cameraProvider?.bindToLifecycle(
                    this,
                    cameraSelector,
                    preview,
                    imageCapture,
                    imageAnalysis
                )

                Log.d(TAG, "Camera started successfully with object detection")
                AppLogger.i(TAG, "Camera started successfully")

            } catch (e: Exception) {
                Log.e(TAG, "Failed to bind camera use cases", e)
                AppLogger.e(TAG, "Failed to start camera: ${e.message}", e)
                Toast.makeText(
                    this,
                    "Failed to start camera: ${e.message}",
                    Toast.LENGTH_SHORT
                ).show()
            }

        }, ContextCompat.getMainExecutor(this))
    }

    private fun processImage(imageProxy: ImageProxy) {
        currentTrainingEmbedding = null
        try {
            // Selective Inference: Throttle processing based on Target FPS
            val currentTime = System.currentTimeMillis()
            val targetFps = AppSettings.targetDetectionFps.coerceIn(1, 60)
            val minInterval = 1000L / targetFps
            
            if (currentTime - lastInferenceTime < minInterval) {
                // Skip frame to maintain target FPS
                imageProxy.close()
                return
            }
            lastInferenceTime = currentTime

            val startTime = System.currentTimeMillis()

            // Convert ImageProxy to Bitmap
            val bitmap = imageProxy.toBitmap()
            val rotation = imageProxy.imageInfo.rotationDegrees
            
            // Release ImageProxy IMMEDIATELY to free camera buffer
            imageProxy.close()

            // Rotate bitmap if needed
            val rotatedBitmap = rotateBitmap(bitmap, rotation)
            val imgWidth = rotatedBitmap.width
            val imgHeight = rotatedBitmap.height

            // 1. Run YOLO detection on the full frame
            Log.v(TAG, "Running inference. useFlow2=${AppSettings.useFlow2}")
            val rawDetections = if (AppSettings.useFlow2) {
                obbDetector.detect(rotatedBitmap)
            } else {
                objectDetector.detect(rotatedBitmap)
            }
            val endTime = System.currentTimeMillis()
            
            // Update performance monitor
            PerformanceMonitor.updateInferenceSpeed(endTime - startTime)
            
            // STRICT frame filtering: Only keep detections FULLY contained within the frame
            // We use a 10-pixel safety margin to ensure no partial boxes are drawn
            val detections = rawDetections.filter { det ->
                det.boundingBox.left >= 10f && 
                det.boundingBox.top >= 10f && 
                det.boundingBox.right <= (imgWidth - 10f) && 
                det.boundingBox.bottom <= (imgHeight - 10f)
            }
            
            if (rawDetections.size > detections.size) {
                Log.d(TAG, "Filtered out ${rawDetections.size - detections.size} partial detections at edge")
            }
            
            // 2. Audio Feedback & Stability Trigger
            detections.forEach { det ->
                // Audio Assurance: Beep on first detection
                val stabilityThreshold = AppSettings.beepStability 
                if (det.framesTracked >= stabilityThreshold && !processedTrackIds.contains(det.trackingId)) {
                    processedTrackIds.add(det.trackingId)
                    
                    // Audio Assurance: Beep ONLY when stabilized and captured for uniqueness
                    toneGenerator.startTone(ToneGenerator.TONE_PROP_BEEP, 100)
                    
                    // Capture a high-res frame for background analysis
                    val highResFrame = rotatedBitmap.copy(rotatedBitmap.config ?: Bitmap.Config.ARGB_8888, false)
                    
                    lifecycleScope.launch(Dispatchers.Default) {
                        try {
                            val result = pipelineAnalyzer.analyzeROI(highResFrame, det.boundingBox, det.angle)
                            if (result != null) {
                                withContext(Dispatchers.Main) {
                                    det.mask = result.mask
                                    det.isLogged = true
                                    
                                    // Stage 3: SKU Matching result handling
                                    if (result.embedding != null) {
                                        val match = productRepository.findBestMatch(result.embedding)
                                        if (match != null) {
                                            val (product, _) = match
                                            det.label = product.name
                                            det.classifierResult = ClassificationResult(product.name, 1.0f)
                                        }
                                    }
                                    
                                    // Log the refined detection with full frame, crop, mask, and bounding box
                                    // Pass highResFrame as the fullFrame for the 3-image gallery
                                    val fullFrameCopy = highResFrame.copy(highResFrame.config ?: Bitmap.Config.ARGB_8888, false)
                                    AILogManager.addEntry(
                                        label = det.label,
                                        confidence = det.confidence,
                                        inferenceTimeMs = System.currentTimeMillis() - startTime,
                                        fullFrame = fullFrameCopy,
                                        image = result.croppedImage,
                                        mask = result.mask,
                                        boundingBox = det.boundingBox,
                                        isFlow2 = AppSettings.useFlow2
                                    )
                                    
                                    binding.overlayView.invalidate()
                                }
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Analysis failed for ID ${det.trackingId}", e)
                        } finally {
                            highResFrame.recycle()
                        }
                    }
                }
            }

            // Update UI on main thread
            runOnUiThread {
                binding.overlayView.setDetections(detections, imgWidth, imgHeight)
            }


            // Clean up
            if (rotatedBitmap != bitmap) {
                rotatedBitmap.recycle()
            }
            bitmap.recycle()

        } catch (e: Exception) {
            Log.e(TAG, "Image processing failed", e)
            // Safety close in case of error before the early close
            try { imageProxy.close() } catch(f: Exception){}
        }
    }

    private fun rotateBitmap(bitmap: Bitmap, rotationDegrees: Int): Bitmap {
        if (rotationDegrees == 0) return bitmap

        val matrix = Matrix()
        matrix.postRotate(rotationDegrees.toFloat())
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    private fun takePhoto() {
        // Get a stable reference of the modifiable image capture use case
        val imageCapture = imageCapture ?: return

        AppLogger.i(TAG, "Capturing photo...")

        // Create time-stamped name for the photo
        val timestamp = SimpleDateFormat(FILENAME_FORMAT, Locale.US)
            .format(System.currentTimeMillis())
        val displayName = "IMG_$timestamp"

        // Create content values for MediaStore
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/CameraApp")
            }
        }

        // Create output options for image capture
        val outputOptions = ImageCapture.OutputFileOptions.Builder(
            contentResolver,
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            contentValues
        ).build()

        // Take the photo
        imageCapture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onError(exception: ImageCaptureException) {
                    Log.e(TAG, "Photo capture failed: ${exception.message}", exception)
                    AppLogger.e(TAG, "Photo capture failed: ${exception.message}", exception)
                    Toast.makeText(
                        this@MainActivity,
                        "Failed to capture photo: ${exception.message}",
                        Toast.LENGTH_SHORT
                    ).show()
                }

                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    val savedUri = output.savedUri
                    Log.d(TAG, "Photo saved successfully: $savedUri")
                    AppLogger.i(TAG, "Photo saved to gallery: $savedUri")
                    Toast.makeText(
                        this@MainActivity,
                        "Photo saved to gallery",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        objectDetector.close()
        pipelineAnalyzer.close()
        toneGenerator.release()
        AppLogger.d(TAG, "MainActivity destroyed")
    }
}

