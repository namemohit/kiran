package com.example.cameraapp

import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.slider.Slider
import com.google.android.material.switchmaterial.SwitchMaterial

class CameraSettingsBottomSheet : BottomSheetDialogFragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val root = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 48, 48, 64)
            setBackgroundColor(Color.parseColor("#1E1E1E"))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        val title = TextView(requireContext()).apply {
            text = "Camera Configuration"
            setTextColor(Color.WHITE)
            textSize = 20f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, 32)
        }
        root.addView(title)

        // OBB Confidence Threshold
        root.addView(createSliderSection("OBB Confidence", AppSettings.obbThreshold, 0.01f, 1.0f, 0.01f) {
            AppSettings.obbThreshold = it
        })

        // Similarity Confidence (EfficientNet)
        root.addView(createSliderSection("Similarity Confidence", AppSettings.classThreshold, 0.01f, 1.0f, 0.01f) {
            AppSettings.classThreshold = it
        })

        return root
    }

    private fun createSliderSection(label: String, value: Float, min: Float, max: Float, stepSize: Float = 0f, onUpdate: (Float) -> Unit): View {
        val context = requireContext()
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 16, 0, 16)
        }

        val headerRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val tvLabel = TextView(context).apply {
            text = label
            setTextColor(Color.parseColor("#AAAAAA"))
            textSize = 12f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val tvValue = TextView(context).apply {
            text = if (max > 1.0f) value.toInt().toString() else String.format("%.2f", value)
            setTextColor(Color.parseColor("#4ECDC4"))
            textSize = 14f
            gravity = Gravity.END
        }

        headerRow.addView(tvLabel)
        headerRow.addView(tvValue)

        val slider = Slider(context).apply {
            valueFrom = min
            valueTo = max
            this.value = value
            if (stepSize > 0f) {
                this.stepSize = stepSize
            }
            thumbTintList = ColorStateList.valueOf(Color.parseColor("#4ECDC4"))
            trackActiveTintList = ColorStateList.valueOf(Color.parseColor("#4ECDC4"))
            trackInactiveTintList = ColorStateList.valueOf(Color.parseColor("#333333"))
            addOnChangeListener { _, v, _ ->
                onUpdate(v)
                tvValue.text = if (max > 1.0f) v.toInt().toString() else String.format("%.2f", v)
            }
        }

        container.addView(headerRow)
        container.addView(slider)
        return container
    }

    companion object {
        const val TAG = "CameraSettingsBottomSheet"
        fun newInstance() = CameraSettingsBottomSheet()
    }
}
