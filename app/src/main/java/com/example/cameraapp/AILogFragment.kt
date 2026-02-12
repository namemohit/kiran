package com.example.cameraapp

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.cameraapp.databinding.ItemScanCardBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Fragment that displays a card-based "Scan Wall".
 * Users can accept (Green) or reject (Red) items directly in the list.
 */
class AILogFragment : Fragment() {

    private lateinit var rvScans: RecyclerView
    private lateinit var tvEmptyMessage: TextView
    private lateinit var tvItemCount: TextView
    private lateinit var tvTotalScans: TextView
    private lateinit var tvAccepted: TextView
    private lateinit var tvPending: TextView

    private lateinit var scanAdapter: ScanAdapter

    private val logListener: (List<AILogManager.LogEntry>) -> Unit = { entries ->
        activity?.runOnUiThread {
            updateDisplay(entries)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_ai_log, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        rvScans = view.findViewById(R.id.rvScans)
        tvEmptyMessage = view.findViewById(R.id.tvEmptyMessage)
        tvItemCount = view.findViewById(R.id.tvItemCount)
        tvTotalScans = view.findViewById(R.id.tvTotalScans)
        tvAccepted = view.findViewById(R.id.tvAccepted)
        tvPending = view.findViewById(R.id.tvPending)

        setupRecyclerView()

        // Register for log updates
        AILogManager.addListener(logListener)

        // Display current entries
        updateDisplay(AILogManager.getEntries())
    }

    private fun setupRecyclerView() {
        scanAdapter = ScanAdapter()
        rvScans.layoutManager = LinearLayoutManager(context)
        rvScans.adapter = scanAdapter
    }

    override fun onDestroyView() {
        super.onDestroyView()
        AILogManager.removeListener(logListener)
    }

    private fun updateDisplay(entries: List<AILogManager.LogEntry>) {
        updateStats(entries)
        
        if (entries.isEmpty()) {
            tvEmptyMessage.visibility = View.VISIBLE
            rvScans.visibility = View.GONE
            tvItemCount.text = "0 items"
            return
        }

        tvEmptyMessage.visibility = View.GONE
        rvScans.visibility = View.VISIBLE
        tvItemCount.text = "${entries.size} items"

        scanAdapter.submitList(entries.reversed()) // Show latest at top
    }

    private fun updateStats(entries: List<AILogManager.LogEntry>) {
        tvTotalScans.text = entries.size.toString()
        tvAccepted.text = entries.count { it.status == AILogManager.DetectionStatus.ACCEPTED }.toString()
        tvPending.text = entries.count { it.status == AILogManager.DetectionStatus.PENDING }.toString()
    }

    inner class ScanAdapter : RecyclerView.Adapter<ScanAdapter.ViewHolder>() {
        private var items = listOf<AILogManager.LogEntry>()

        fun submitList(newList: List<AILogManager.LogEntry>) {
            items = newList
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val binding = ItemScanCardBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return ViewHolder(binding)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.bind(items[position])
        }

        override fun getItemCount() = items.size

        inner class ViewHolder(private val binding: ItemScanCardBinding) : RecyclerView.ViewHolder(binding.root) {
            fun bind(entry: AILogManager.LogEntry) {
                binding.tvLabel.text = entry.label
                binding.tvConf.text = "Confidence: ${entry.getConfidencePercent()}"
                
                // Price loading logic
                lifecycleScope.launch(Dispatchers.IO) {
                    val repository = ProductRepository(binding.root.context)
                    val product = repository.findProductByName(entry.label)
                    withContext(Dispatchers.Main) {
                        binding.tvPrice.text = if (product != null) "₹${product.price.toInt()}" else "₹10"
                    }
                }

                // Image with annotation
                val annotated = createAnnotatedThumbnail(entry.fullFrameImage, entry.boundingBox)
                binding.ivThumbnail.setImageBitmap(annotated ?: entry.croppedImage)

                // Status-based background colors
                val context = binding.root.context
                when (entry.status) {
                    AILogManager.DetectionStatus.ACCEPTED -> {
                        binding.scanCard.setCardBackgroundColor(ContextCompat.getColor(context, R.color.success_light))
                        binding.btnAccept.alpha = 1.0f
                        binding.btnReject.alpha = 0.3f
                    }
                    AILogManager.DetectionStatus.REJECTED -> {
                        binding.scanCard.setCardBackgroundColor(ContextCompat.getColor(context, R.color.error_light))
                        binding.btnAccept.alpha = 0.3f
                        binding.btnReject.alpha = 1.0f
                    }
                    else -> {
                        binding.scanCard.setCardBackgroundColor(ContextCompat.getColor(context, R.color.background_card))
                        binding.btnAccept.alpha = 1.0f
                        binding.btnReject.alpha = 1.0f
                    }
                }

                binding.btnAccept.setOnClickListener {
                    AILogManager.updateStatus(entry.id, AILogManager.DetectionStatus.ACCEPTED)
                    Toast.makeText(context, "Accepted ${entry.label}", Toast.LENGTH_SHORT).show()
                }

                binding.btnReject.setOnClickListener {
                    AILogManager.updateStatus(entry.id, AILogManager.DetectionStatus.REJECTED)
                    Toast.makeText(context, "Rejected ${entry.label}", Toast.LENGTH_SHORT).show()
                }

                binding.ivThumbnail.setOnClickListener {
                    if (entry.isFlow2) {
                        val dialog = DetectionDetailDialogFragment.newInstance(entry.id)
                        dialog.show(parentFragmentManager, "detail_dialog")
                    }
                }
            }
        }
    }

    private fun createAnnotatedThumbnail(
        fullFrame: Bitmap?, 
        boundingBox: RectF?
    ): Bitmap? {
        if (fullFrame == null || boundingBox == null) return fullFrame
        
        val result = fullFrame.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(result)
        
        val yoloInputSize = maxOf(
            boundingBox.right.coerceAtLeast(boundingBox.left),
            boundingBox.bottom.coerceAtLeast(boundingBox.top)
        ).coerceAtLeast(320f)
        
        val scaleX = result.width.toFloat() / yoloInputSize
        val scaleY = result.height.toFloat() / yoloInputSize
        
        val scaledBox = RectF(
            boundingBox.left * scaleX,
            boundingBox.top * scaleY,
            boundingBox.right * scaleX,
            boundingBox.bottom * scaleY
        )
        
        val boxPaint = Paint().apply {
            color = Color.parseColor("#4CAF50")
            style = Paint.Style.STROKE
            strokeWidth = 14f
            isAntiAlias = true
        }
        canvas.drawRect(scaledBox, boxPaint)
        
        return result
    }

    companion object {
        fun newInstance(): AILogFragment = AILogFragment()
    }
}
