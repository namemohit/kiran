package com.example.cameraapp

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import androidx.fragment.app.Fragment

/**
 * Fragment for displaying application logs with positive/negative feedback logging.
 */
class LogsFragment : Fragment() {

    private lateinit var tvLogs: TextView
    private lateinit var tvLogCount: TextView
    private lateinit var scrollView: ScrollView
    private lateinit var btnClear: Button

    private val logListener: (List<AppLogger.LogEntry>) -> Unit = { logs ->
        activity?.runOnUiThread {
            updateLogsDisplay(logs)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_logs, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        tvLogs = view.findViewById(R.id.tvLogs)
        tvLogCount = view.findViewById(R.id.tvLogCount)
        scrollView = view.findViewById(R.id.scrollView)
        btnClear = view.findViewById(R.id.btnClear)

        btnClear.setOnClickListener {
            AppLogger.clear()
            AppLogger.i("LogsFragment", "Logs cleared by user")
        }

        // Register for log updates
        AppLogger.addListener(logListener)

        // Display current logs
        updateLogsDisplay(AppLogger.getLogs())

        // Log that the fragment was opened
        AppLogger.d("LogsFragment", "Logs view opened")
    }

    override fun onDestroyView() {
        super.onDestroyView()
        AppLogger.removeListener(logListener)
    }

    private fun updateLogsDisplay(logs: List<AppLogger.LogEntry>) {
        val logText = logs.joinToString("\n") { entry ->
            val colorPrefix = when (entry.level) {
                "E" -> "❌ "  // Negative/Error
                "W" -> "⚠️ "  // Warning
                "I" -> "ℹ️ "  // Info
                "D" -> "✅ "  // Positive/Debug
                else -> ""
            }
            colorPrefix + entry.format()
        }
        
        tvLogs.text = if (logText.isEmpty()) "No logs yet" else logText
        tvLogCount.text = "${logs.size} logs"

        // Auto-scroll to bottom
        scrollView.post {
            scrollView.fullScroll(View.FOCUS_DOWN)
        }
    }

    companion object {
        fun newInstance(): LogsFragment = LogsFragment()
    }
}
