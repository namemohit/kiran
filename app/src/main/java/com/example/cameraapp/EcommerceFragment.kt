package com.example.cameraapp

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.cameraapp.databinding.FragmentEcommerceBinding
import com.example.cameraapp.databinding.ItemProductBinding
import androidx.core.content.ContextCompat
import kotlinx.coroutines.launch

class EcommerceFragment : Fragment() {
    private var _binding: FragmentEcommerceBinding? = null
    private val binding get() = _binding!!
    
    private lateinit var repository: ProductRepository
    private lateinit var adapter: ProductAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentEcommerceBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        repository = ProductRepository(requireContext())
        setupRecyclerView()
        
        binding.addProductButton.setOnClickListener {
            showAddProductDialog()
        }
        
        loadProducts()
    }

    private fun setupRecyclerView() {
        adapter = ProductAdapter(
            onDelete = { product ->
                lifecycleScope.launch {
                    repository.deleteProduct(product.id)
                    loadProducts()
                }
            }
        )
        binding.productRecyclerView.layoutManager = LinearLayoutManager(context)
        binding.productRecyclerView.adapter = adapter
    }

    override fun onResume() {
        super.onResume()
        loadProducts()
    }

    private fun loadProducts() {
        lifecycleScope.launch {
            val db = AppDatabase.getDatabase(requireContext())
            val productsWithEmbeddings = db.productDao().getAllProductsWithEmbeddings()
            adapter.submitList(productsWithEmbeddings)
        }
    }

    private fun showAddProductDialog() {
        val dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_add_product, null)
        val nameInput = dialogView.findViewById<EditText>(R.id.nameInput)
        val priceInput = dialogView.findViewById<EditText>(R.id.priceInput)

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("New SKU")
            .setView(dialogView)
            .setPositiveButton("Add") { _, _ ->
                val name = nameInput.text.toString().trim()
                val price = priceInput.text.toString().toDoubleOrNull() ?: 0.0
                if (name.isNotEmpty()) {
                    lifecycleScope.launch {
                        repository.addProduct(name, price, null, emptyList())
                        loadProducts()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    inner class ProductAdapter(
        private val onDelete: (Product) -> Unit
    ) : RecyclerView.Adapter<ProductAdapter.ViewHolder>() {
        
        private var items = listOf<ProductWithEmbeddings>()

        fun submitList(newList: List<ProductWithEmbeddings>) {
            items = newList
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val binding = ItemProductBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return ViewHolder(binding)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]
            holder.bind(item)
        }

        override fun getItemCount() = items.size

        inner class ViewHolder(private val binding: ItemProductBinding) : RecyclerView.ViewHolder(binding.root) {
            fun bind(item: ProductWithEmbeddings) {
                val context = binding.root.context
                binding.productName.text = item.product.name
                binding.productPrice.text = "Rs ${String.format("%.2f", item.product.price)}"
                
                val count = item.embeddings.size
                binding.embeddingCount.text = "$count Samples"
                
                binding.embeddingCount.setTextColor(
                    if (count > 0) ContextCompat.getColor(context, R.color.success)
                    else ContextCompat.getColor(context, R.color.warning)
                )
                
                binding.deleteButton.setOnClickListener {
                    onDelete(item.product)
                }
            }
        }
    }

    companion object {
        fun newInstance() = EcommerceFragment()
    }
}
