package com.example.cameraapp

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * RecyclerView adapter for displaying output items in a Facebook-style feed.
 */
class OutputAdapter : ListAdapter<OutputStore.OutputItem, OutputAdapter.ViewHolder>(DiffCallback()) {

    // Color palette matching OverlayView
    private val colors = listOf(
        Color.parseColor("#FF6B6B"), // Red
        Color.parseColor("#4ECDC4"), // Teal
        Color.parseColor("#45B7D1"), // Blue
        Color.parseColor("#96CEB4"), // Green
        Color.parseColor("#FFEAA7"), // Yellow
        Color.parseColor("#DDA0DD"), // Plum
        Color.parseColor("#98D8C8"), // Mint
        Color.parseColor("#F7DC6F"), // Gold
        Color.parseColor("#BB8FCE"), // Purple
        Color.parseColor("#85C1E9")  // Sky Blue
    )

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_output, parent, false)
        return ViewHolder(view, colors)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class ViewHolder(
        itemView: View,
        private val colors: List<Int>
    ) : RecyclerView.ViewHolder(itemView) {
        private val ivImage: ImageView = itemView.findViewById(R.id.ivImage)
        private val tvTitle: TextView = itemView.findViewById(R.id.tvTitle)
        private val tvTime: TextView = itemView.findViewById(R.id.tvTime)
        private val tvObjectCount: TextView = itemView.findViewById(R.id.tvObjectCount)
        private val llDetectionDetails: LinearLayout = itemView.findViewById(R.id.llDetectionDetails)

        private val dateFormat = SimpleDateFormat("MMM dd, yyyy", Locale.US)
        private val timeFormat = SimpleDateFormat("hh:mm a", Locale.US)

        fun bind(item: OutputStore.OutputItem) {
            // Set image with detection boxes already drawn
            ivImage.setImageBitmap(item.resultBitmap)
            
            // Set timestamp
            val date = Date(item.timestamp)
            tvTitle.text = "Detection • ${dateFormat.format(date)}"
            tvTime.text = timeFormat.format(date)
            
            // Set detection count
            val detectionCount = item.detections.size
            tvObjectCount.text = when (detectionCount) {
                0 -> "No objects detected"
                1 -> "1 object detected"
                else -> "$detectionCount objects detected"
            }

            // Build detection details list
            llDetectionDetails.removeAllViews()
            
            if (item.detections.isEmpty()) {
                addDetectionRow("No objects found in this image", "#888888", -1)
            } else {
                // Group detections by label
                val grouped = item.detections.groupBy { it.label }
                    .mapValues { entry -> 
                        entry.value.sortedByDescending { it.confidence }
                    }
                    .toList()
                    .sortedByDescending { it.second.size }

                for ((label, detections) in grouped) {
                    val firstDetection = detections.first()
                    val color = colors[firstDetection.labelIndex % colors.size]
                    
                    if (detections.size == 1) {
                        val confidence = (firstDetection.confidence * 100).toInt()
                        addDetectionRow("• $label — $confidence%", colorToHex(color), firstDetection.labelIndex)
                    } else {
                        val avgConfidence = (detections.map { it.confidence }.average() * 100).toInt()
                        addDetectionRow("• ${detections.size}× $label — avg $avgConfidence%", colorToHex(color), firstDetection.labelIndex)
                    }
                }
            }
        }

        private fun addDetectionRow(text: String, colorHex: String, labelIndex: Int) {
            val textView = TextView(itemView.context).apply {
                this.text = text
                textSize = 14f
                setTextColor(Color.parseColor(colorHex))
                setPadding(0, 6, 0, 6)
                
                if (labelIndex >= 0) {
                    // Add colored dot indicator
                    val dot = "●  "
                    val fullText = dot + text.removePrefix("• ")
                    this.text = fullText
                }
            }
            llDetectionDetails.addView(textView)
        }

        private fun colorToHex(color: Int): String {
            return String.format("#%06X", 0xFFFFFF and color)
        }
    }

    class DiffCallback : DiffUtil.ItemCallback<OutputStore.OutputItem>() {
        override fun areItemsTheSame(
            oldItem: OutputStore.OutputItem,
            newItem: OutputStore.OutputItem
        ): Boolean = oldItem.id == newItem.id

        override fun areContentsTheSame(
            oldItem: OutputStore.OutputItem,
            newItem: OutputStore.OutputItem
        ): Boolean = oldItem.id == newItem.id && oldItem.timestamp == newItem.timestamp
    }
}
