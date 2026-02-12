package com.example.cameraapp

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.example.cameraapp.databinding.FragmentBizBinding
import kotlinx.coroutines.launch

class BizFragment : Fragment() {
    private var _binding: FragmentBizBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentBizBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        loadMetrics()
    }

    override fun onResume() {
        super.onResume()
        loadMetrics()
    }

    private fun loadMetrics() {
        lifecycleScope.launch {
            val db = AppDatabase.getDatabase(requireContext())
            val products = db.productDao().getAllProducts()
            val embeddings = db.productDao().getAllEmbeddings()
            
            binding.totalProductsText.text = products.size.toString()
            binding.totalSamplesText.text = embeddings.size.toString()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        fun newInstance() = BizFragment()
    }
}
