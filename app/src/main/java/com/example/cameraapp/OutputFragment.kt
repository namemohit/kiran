package com.example.cameraapp

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/**
 * Fragment for displaying processed images with detection results.
 */
class OutputFragment : Fragment() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var tvEmpty: TextView
    private lateinit var btnClear: Button
    private lateinit var adapter: OutputAdapter

    private val storeListener: () -> Unit = {
        activity?.runOnUiThread {
            updateDisplay()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_output, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        recyclerView = view.findViewById(R.id.recyclerView)
        tvEmpty = view.findViewById(R.id.tvEmpty)
        btnClear = view.findViewById(R.id.btnClear)

        // Setup RecyclerView with linear layout for Facebook-style feed
        adapter = OutputAdapter()
        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.adapter = adapter

        btnClear.setOnClickListener {
            OutputStore.clear()
            AppLogger.i("OutputFragment", "Output cleared by user")
        }

        // Register for updates
        OutputStore.addListener(storeListener)

        // Display current items
        updateDisplay()

        AppLogger.d("OutputFragment", "Output view opened")
    }

    override fun onDestroyView() {
        super.onDestroyView()
        OutputStore.removeListener(storeListener)
    }

    private fun updateDisplay() {
        val items = OutputStore.getItems()
        adapter.submitList(items)

        if (items.isEmpty()) {
            recyclerView.visibility = View.GONE
            tvEmpty.visibility = View.VISIBLE
        } else {
            recyclerView.visibility = View.VISIBLE
            tvEmpty.visibility = View.GONE
        }
    }

    companion object {
        fun newInstance(): OutputFragment = OutputFragment()
    }
}
