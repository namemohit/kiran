package com.example.cameraapp

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import com.google.android.material.button.MaterialButton
import java.util.concurrent.Executors

/**
 * Fragment for uploading images from gallery and running detection.
 * Uses local TFLite inference with ModelManager for dynamic model updates.
 */
class UploadFragment : Fragment() {

    private lateinit var btnSelectImage: MaterialButton
    private lateinit var previewContainer: FrameLayout
    private lateinit var ivPreview: ImageView
    private lateinit var overlayView: OverlayView
    private lateinit var progressBar: ProgressBar
    private lateinit var tvDetectionInfo: TextView

    private var objectDetector: ObjectDetector? = null
    private val executor = Executors.newSingleThreadExecutor()

    private val pickImageLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { processImage(it) }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_upload, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        btnSelectImage = view.findViewById(R.id.btnSelectImage)
        previewContainer = view.findViewById(R.id.previewContainer)
        ivPreview = view.findViewById(R.id.ivPreview)
        overlayView = view.findViewById(R.id.overlayView)
        progressBar = view.findViewById(R.id.progressBar)
        tvDetectionInfo = view.findViewById(R.id.tvDetectionInfo)

        // Initialize object detector with ModelManager
        ModelManager.init(requireContext())
        objectDetector = ObjectDetector(requireContext())
        val initialized = objectDetector?.initialize() ?: false
        if (!initialized) {
            AppLogger.e("UploadFragment", "Failed to initialize object detector")
        } else {
            AppLogger.i("UploadFragment", "Object detector initialized")
        }

        btnSelectImage.setOnClickListener {
            pickImageLauncher.launch("image/*")
        }

        AppLogger.d("UploadFragment", "Upload view opened")
    }

    private fun processImage(uri: Uri) {
        AppLogger.i("UploadFragment", "Processing image: $uri")
        
        previewContainer.visibility = View.VISIBLE
        progressBar.visibility = View.VISIBLE
        overlayView.setDetections(emptyList(), 0, 0)

        executor.execute {
            try {
                // Load bitmap from URI
                val bitmap = loadBitmapFromUri(uri)
                if (bitmap == null) {
                    AppLogger.e("UploadFragment", "Failed to load image")
                    activity?.runOnUiThread {
                        progressBar.visibility = View.GONE
                        tvDetectionInfo.visibility = View.VISIBLE
                        tvDetectionInfo.text = "Failed to load image"
                    }
                    return@execute
                }

                AppLogger.d("UploadFragment", "Image loaded: ${bitmap.width}x${bitmap.height}")

                // Run local TFLite detection
                val detections = objectDetector?.detect(bitmap) ?: emptyList()
                
                // Create result bitmap with detections drawn
                val resultBitmap = createResultBitmap(bitmap, detections)

                activity?.runOnUiThread {
                    progressBar.visibility = View.GONE
                    
                    // Display image
                    ivPreview.setImageBitmap(bitmap)
                    
                    // Set detections on overlay
                    ivPreview.post {
                        overlayView.setDetections(detections, bitmap.width, bitmap.height)
                    }

                    // Show detection info
                    tvDetectionInfo.visibility = View.VISIBLE
                    if (detections.isEmpty()) {
                        tvDetectionInfo.text = "No objects detected"
                        AppLogger.w("UploadFragment", "No objects detected in image")
                    } else {
                        val labels = detections.groupBy { it.label }
                            .map { "${it.value.size}x ${it.key}" }
                            .joinToString(", ")
                        tvDetectionInfo.text = "Detected: $labels"
                        AppLogger.i("UploadFragment", "Detected ${detections.size} objects: $labels")
                    }

                    // Store result
                    OutputStore.addOutput(uri, resultBitmap, detections)
                    AppLogger.d("UploadFragment", "Result saved to output store")
                }

            } catch (e: Exception) {
                AppLogger.e("UploadFragment", "Error processing image", e)
                activity?.runOnUiThread {
                    progressBar.visibility = View.GONE
                    tvDetectionInfo.visibility = View.VISIBLE
                    tvDetectionInfo.text = "Error: ${e.message}"
                }
            }
        }
    }

    private fun loadBitmapFromUri(uri: Uri): Bitmap? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val source = ImageDecoder.createSource(requireContext().contentResolver, uri)
                ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    decoder.isMutableRequired = true
                }
            } else {
                @Suppress("DEPRECATION")
                MediaStore.Images.Media.getBitmap(requireContext().contentResolver, uri)
            }
        } catch (e: Exception) {
            AppLogger.e("UploadFragment", "Failed to load bitmap", e)
            null
        }
    }

    private fun createResultBitmap(original: Bitmap, detections: List<Detection>): Bitmap {
        val result = original.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(result)
        
        // Draw detections using OverlayView's drawing logic
        val tempOverlay = OverlayView(requireContext())
        tempOverlay.layout(0, 0, original.width, original.height)
        tempOverlay.setDetections(detections, original.width, original.height)
        tempOverlay.draw(canvas)
        
        return result
    }

    override fun onDestroyView() {
        super.onDestroyView()
        objectDetector?.close()
        executor.shutdown()
    }

    companion object {
        fun newInstance(): UploadFragment = UploadFragment()
    }
}
