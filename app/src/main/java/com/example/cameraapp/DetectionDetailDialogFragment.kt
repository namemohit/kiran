package com.example.cameraapp

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * A DialogFragment that shows a detailed view for OBB detections (Flow 2).
 * Shows both the full frame and the rectified cropped image with controls.
 */
class DetectionDetailDialogFragment : DialogFragment() {

    private var entryId: Int = -1
    private var qty: Int = 1
    
    // UI components
    private lateinit var tvQty: TextView
    private lateinit var tvPrice: TextView
    private lateinit var tvLabel: TextView

    companion object {
        private const val ARG_ENTRY_ID = "entry_id"

        fun newInstance(entryId: Int): DetectionDetailDialogFragment {
            val fragment = DetectionDetailDialogFragment()
            val args = Bundle()
            args.putInt(ARG_ENTRY_ID, entryId)
            fragment.arguments = args
            return fragment
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        entryId = arguments?.getInt(ARG_ENTRY_ID) ?: -1
        // Using a semi-transparent dark theme
        setStyle(STYLE_NO_TITLE, android.R.style.Theme_DeviceDefault_Dialog_MinWidth)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val context = requireContext()
        val entry = AILogManager.getEntries().find { it.id == entryId } ?: run {
            dismiss()
            return View(context)
        }

        // Main Container
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
            setBackgroundColor(Color.parseColor("#1A1A1A"))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            // Rounded corners
            clipToOutline = true
            outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
            background = GradientDrawable().apply {
                cornerRadius = 40f
                setColor(Color.parseColor("#1A1A1A"))
            }
        }

        // Row 1: Full Frame + Accept/Reject
        val row1 = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, 32)
        }

        // 1.1 Full Frame Image (Annotated)
        val ivFullFrame = ImageView(context).apply {
            layoutParams = LinearLayout.LayoutParams(300, 300).apply {
                marginEnd = 32
            }
            scaleType = ImageView.ScaleType.CENTER_CROP
            val annotated = createAnnotatedFullFrame(entry.fullFrameImage, entry.boundingBox, entry.isFlow2)
            setImageBitmap(annotated ?: entry.fullFrameImage)
            
            clipToOutline = true
            outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
            background = GradientDrawable().apply {
                cornerRadius = 24f
                setColor(Color.parseColor("#333333"))
            }
        }

        // 1.2 Accept/Reject Buttons
        val buttonsLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }

        // Accept Button Row
        val acceptBtn = createCircleButton("✔", "#4CAF50", "Accept") {
            AILogManager.updateStatus(entryId, AILogManager.DetectionStatus.ACCEPTED)
            dismiss()
        }
        
        // Reject Button Row
        val rejectBtn = createCircleButton("✖", "#FF5252", "Reject") {
            AILogManager.updateStatus(entryId, AILogManager.DetectionStatus.REJECTED)
            dismiss()
        }

        buttonsLayout.addView(acceptBtn)
        buttonsLayout.addView(rejectBtn)

        row1.addView(ivFullFrame)
        row1.addView(buttonsLayout)

        // Row 2: Cropped Image + Product Info
        val row2 = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        // 2.1 Cropped Image
        val ivCropped = ImageView(context).apply {
            layoutParams = LinearLayout.LayoutParams(300, 300).apply {
                marginEnd = 32
            }
            scaleType = ImageView.ScaleType.CENTER_CROP
            setImageBitmap(entry.croppedImage)
            
            clipToOutline = true
            outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
            background = GradientDrawable().apply {
                cornerRadius = 24f
                setColor(Color.parseColor("#333333"))
            }
        }

        // 2.2 Info Layout
        val infoLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }

        tvLabel = TextView(context).apply {
            text = entry.label
            setTextColor(Color.WHITE)
            textSize = 18f
            setTypeface(null, android.graphics.Typeface.BOLD)
        }

        tvPrice = TextView(context).apply {
            text = "Loading price..."
            setTextColor(Color.parseColor("#4ECDC4"))
            textSize = 16f
            setPadding(0, 8, 0, 16)
        }

        // Qty Layout
        val qtyLayout = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val tvQtyLabel = TextView(context).apply {
            text = "Qty - "
            setTextColor(Color.GRAY)
            textSize = 16f
        }

        val btnMinus = TextView(context).apply {
            text = "-"
            setTextColor(Color.WHITE)
            textSize = 24f
            setPadding(24, 8, 24, 8)
            setOnClickListener { if (qty > 1) { qty--; updateQtyDisplay() } }
        }

        tvQty = TextView(context).apply {
            text = qty.toString()
            setTextColor(Color.WHITE)
            textSize = 18f
            setPadding(16, 0, 16, 0)
        }

        val btnPlus = TextView(context).apply {
            text = "+"
            setTextColor(Color.WHITE)
            textSize = 24f
            setPadding(24, 8, 24, 8)
            setOnClickListener { qty++; updateQtyDisplay() }
        }

        qtyLayout.addView(tvQtyLabel)
        qtyLayout.addView(btnMinus)
        qtyLayout.addView(tvQty)
        qtyLayout.addView(btnPlus)

        infoLayout.addView(tvLabel)
        infoLayout.addView(tvPrice)
        infoLayout.addView(qtyLayout)

        row2.addView(ivCropped)
        row2.addView(infoLayout)

        root.addView(row1)
        root.addView(row2)

        // Load price from repository
        loadProductPrice(entry.label)

        return root
    }

    private fun createCircleButton(icon: String, colorStr: String, label: String, onClick: () -> Unit): View {
        val container = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(16, 16, 16, 16)
            setOnClickListener { onClick() }
        }

        val circle = TextView(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(80, 80)
            text = icon
            textSize = 24f
            setTextColor(Color.parseColor(colorStr))
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#1A" + colorStr.removePrefix("#")))
                setStroke(3, Color.parseColor(colorStr))
            }
        }

        val tvLabel = TextView(requireContext()).apply {
            text = label
            setTextColor(Color.GRAY)
            textSize = 10f
            setPadding(0, 4, 0, 0)
        }

        container.addView(circle)
        container.addView(tvLabel)
        return container
    }

    private fun updateQtyDisplay() {
        tvQty.text = qty.toString()
    }

    private fun loadProductPrice(name: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            val repository = ProductRepository(requireContext())
            val product = repository.findProductByName(name)
            withContext(Dispatchers.Main) {
                if (product != null) {
                    tvPrice.text = "₹${product.price.toInt()}"
                } else {
                    tvPrice.text = "₹10" // Default/Placeholder as per sketch
                }
            }
        }
    }

    /**
     * Replicating annotation logic from AILogFragment.
     */
    private fun createAnnotatedFullFrame(
        fullFrame: Bitmap?, 
        boundingBox: android.graphics.RectF?,
        isFlow2: Boolean
    ): Bitmap? {
        if (fullFrame == null) return null
        if (boundingBox == null) return fullFrame
        
        val result = fullFrame.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = android.graphics.Canvas(result)
        
        // Estimate input size or use common defaults
        val yoloInputSize = maxOf(
            boundingBox.right.coerceAtLeast(boundingBox.left),
            boundingBox.bottom.coerceAtLeast(boundingBox.top)
        ).coerceAtLeast(320f)
        
        val scaleX = result.width.toFloat() / yoloInputSize
        val scaleY = result.height.toFloat() / yoloInputSize
        
        val scaledBox = android.graphics.RectF(
            boundingBox.left * scaleX,
            boundingBox.top * scaleY,
            boundingBox.right * scaleX,
            boundingBox.bottom * scaleY
        )
        
        val boxPaint = android.graphics.Paint().apply {
            color = Color.parseColor("#4CAF50")
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = 12f
            isAntiAlias = true
        }
        canvas.drawRect(scaledBox, boxPaint)
        
        return result
    }
}
