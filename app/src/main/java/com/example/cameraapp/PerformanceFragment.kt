package com.example.cameraapp

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class PerformanceFragment : Fragment() {

    private lateinit var tvInference: TextView
    private lateinit var tvAverage: TextView
    private lateinit var tvQuality: TextView
    private lateinit var tvValidRatio: TextView

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val root = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 64, 48, 64)
            setBackgroundColor(Color.parseColor("#121212"))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        val title = TextView(requireContext()).apply {
            text = "Performance Monitor"
            setTextColor(Color.WHITE)
            textSize = 28f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, 48)
        }
        root.addView(title)

        tvInference = TextView(requireContext()).apply {
            text = "Inference: --ms"
            setTextColor(Color.parseColor("#4ECDC4"))
            textSize = 20f
            setPadding(0, 32, 0, 16)
        }
        root.addView(tvInference)

        tvAverage = TextView(requireContext()).apply {
            text = "Average (10 frames): --ms"
            setTextColor(Color.parseColor("#AAAAAA"))
            textSize = 14f
        }
        root.addView(tvAverage)

        val qualityTitle = TextView(requireContext()).apply {
            text = "Inference Quality"
            setTextColor(Color.WHITE)
            textSize = 18f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, 64, 0, 16)
        }
        root.addView(qualityTitle)

        tvQuality = TextView(requireContext()).apply {
            text = "Pending Validations: --"
            setTextColor(Color.parseColor("#FFD93D"))
            textSize = 16f
        }
        root.addView(tvQuality)

        tvValidRatio = TextView(requireContext()).apply {
            text = "Acceptance Rate: --%"
            setTextColor(Color.parseColor("#4CAF50"))
            textSize = 16f
            setPadding(0, 8, 0, 0)
        }
        root.addView(tvValidRatio)

        val description = TextView(requireContext()).apply {
            text = "This screen shows the real-time performance of the YOLOv8 model running on your device hardware."
            setTextColor(Color.parseColor("#666666"))
            textSize = 12f
            setPadding(0, 64, 0, 0)
        }
        root.addView(description)

        return root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        observePerformance()
    }

    private fun observePerformance() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    PerformanceMonitor.inferenceSpeed.collectLatest { speed ->
                        tvInference.text = "Inference: ${speed}ms"
                    }
                }
                launch {
                    PerformanceMonitor.averageInferenceSpeed.collectLatest { speed ->
                        tvAverage.text = "Average (10 frames): ${speed}ms"
                    }
                }
                launch {
                    kotlinx.coroutines.flow.combine(
                        PerformanceMonitor.acceptedDetections,
                        PerformanceMonitor.rejectedDetections
                    ) { accepted, rejected ->
                        val total = accepted + rejected
                        val rate = if (total > 0) (accepted.toFloat() / total * 100).toInt() else 0
                        Triple(accepted, rejected, rate)
                    }.collectLatest { (accepted, rejected, rate) ->
                        tvQuality.text = "Validated: $accepted Accepted, $rejected Rejected"
                        tvValidRatio.text = "Acceptance Rate: $rate%"
                    }
                }
            }
        }
    }

    private fun spToPx(sp: Float): Float = sp

    companion object {
        fun newInstance() = PerformanceFragment()
    }
}
