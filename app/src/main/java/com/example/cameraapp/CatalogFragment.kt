package com.example.cameraapp

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AnimationUtils
import android.widget.ImageView
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.example.cameraapp.databinding.FragmentCatalogBinding
import com.example.cameraapp.databinding.ItemBannerBinding
import com.example.cameraapp.databinding.ItemCatalogProductBinding
import com.example.cameraapp.databinding.ItemCategoryBinding
import com.google.android.material.card.MaterialCardView

class CatalogFragment : Fragment() {
    private var _binding: FragmentCatalogBinding? = null
    private val binding get() = _binding!!
    
    private val autoScrollHandler = Handler(Looper.getMainLooper())
    private var currentBannerPosition = 0
    private var bannerPageChangeCallback: ViewPager2.OnPageChangeCallback? = null
    
    private val autoScrollRunnable = object : Runnable {
        override fun run() {
            if (_binding != null) {
                currentBannerPosition = (currentBannerPosition + 1) % banners.size
                binding.bannerViewPager.setCurrentItem(currentBannerPosition, true)
                autoScrollHandler.postDelayed(this, 4000)
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentCatalogBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        setupCategories()
        setupBanners()
        setupProducts()
    }
    
    private fun setupCategories() {
        binding.categoryRecyclerView.layoutManager = 
            LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
        binding.categoryRecyclerView.adapter = CategoryAdapter(categories)
    }
    
    private fun setupBanners() {
        binding.bannerViewPager.adapter = BannerAdapter(banners)
        binding.bannerViewPager.offscreenPageLimit = 1
        
        // Setup page indicator
        updateBannerIndicator(0)
        
        bannerPageChangeCallback = object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                currentBannerPosition = position
                updateBannerIndicator(position)
            }
        }
        
        bannerPageChangeCallback?.let {
            binding.bannerViewPager.registerOnPageChangeCallback(it)
        }
        
        // Auto-scroll
        autoScrollHandler.postDelayed(autoScrollRunnable, 4000)
    }
    
    private fun updateBannerIndicator(position: Int) {
        val binding = _binding ?: return
        if (!isAdded) return
        
        // Post to main thread to avoid modifying view hierarchy during layout pass
        binding.root.post {
            val indicatorContainer = binding.bannerIndicator
            val density = resources.displayMetrics.density
            
            for (i in 0 until indicatorContainer.childCount) {
                val dot = indicatorContainer.getChildAt(i)
                val isSelected = i == position
                
                val size = if (isSelected) 8 else 6
                val params = dot.layoutParams as android.widget.LinearLayout.LayoutParams
                params.width = (size * density).toInt()
                params.height = (size * density).toInt()
                dot.layoutParams = params
                
                dot.setBackgroundResource(
                    if (isSelected) R.drawable.indicator_active 
                    else R.drawable.indicator_inactive
                )
            }
        }
    }
    
    private fun setupProducts() {
        binding.catalogRecyclerView.layoutManager = GridLayoutManager(context, 2)
        binding.catalogRecyclerView.adapter = CatalogAdapter(products)
        
        // Add item animation
        binding.catalogRecyclerView.itemAnimator?.apply {
            addDuration = 300
            changeDuration = 300
        }
    }

    override fun onResume() {
        super.onResume()
        autoScrollHandler.postDelayed(autoScrollRunnable, 4000)
    }

    override fun onPause() {
        super.onPause()
        autoScrollHandler.removeCallbacks(autoScrollRunnable)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        autoScrollHandler.removeCallbacks(autoScrollRunnable)
        bannerPageChangeCallback?.let {
            _binding?.bannerViewPager?.unregisterOnPageChangeCallback(it)
        }
        bannerPageChangeCallback = null
        _binding = null
    }

    // Data classes
    data class Category(val name: String, val iconRes: Int)
    data class Banner(val title: String, val subtitle: String, val bgColor: Int)
    data class CatalogProduct(
        val name: String, 
        val brand: String, 
        val price: String,
        val originalPrice: String? = null,
        val discount: String? = null,
        var quantity: Int = 0
    )

    // Sample data
    private val categories = listOf(
        Category("Groceries", android.R.drawable.ic_menu_gallery),
        Category("Dairy", android.R.drawable.ic_menu_gallery),
        Category("Snacks", android.R.drawable.ic_menu_gallery),
        Category("Beverages", android.R.drawable.ic_menu_gallery),
        Category("Personal Care", android.R.drawable.ic_menu_gallery),
        Category("Household", android.R.drawable.ic_menu_gallery)
    )
    
    private val banners = listOf(
        Banner("Fresh Groceries", "Delivered in 10 mins", android.graphics.Color.parseColor("#1A1A1A")),
        Banner("Weekend Offers", "Up to 40% OFF", android.graphics.Color.parseColor("#2D2D2D")),
        Banner("New Arrivals", "Check what's new", android.graphics.Color.parseColor("#1A1A1A"))
    )
    
    private val products = listOf(
        CatalogProduct("Maggi 2-Minute Noodles", "Nestlé • 70g", "₹14", "₹16", "12% OFF"),
        CatalogProduct("Coca-Cola 500ml", "Coca-Cola", "₹40"),
        CatalogProduct("Oreo Vanilla Cream", "Mondelez • 120g", "₹35", "₹40", "10% OFF"),
        CatalogProduct("Amul Taaza Milk", "Amul • 1L", "₹54"),
        CatalogProduct("Lays Classic Salted", "PepsiCo • 52g", "₹20"),
        CatalogProduct("Surf Excel Quick Wash", "HUL • 1kg", "₹215", "₹250", "14% OFF"),
        CatalogProduct("Dettol Original Soap", "Reckitt • 125g", "₹52"),
        CatalogProduct("Parle-G Gold", "Parle • 1kg", "₹120"),
        CatalogProduct("Good Day Cookies", "Britannia", "₹30"),
        CatalogProduct("Tata Salt", "Tata • 1kg", "₹28")
    )

    // Category Adapter
    inner class CategoryAdapter(private val items: List<Category>) : 
        RecyclerView.Adapter<CategoryAdapter.ViewHolder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val binding = ItemCategoryBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return ViewHolder(binding)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.bind(items[position])
            val context = holder.itemView.context
            holder.itemView.startAnimation(
                AnimationUtils.loadAnimation(context, R.anim.scale_up)
            )
        }

        override fun getItemCount() = items.size

        inner class ViewHolder(private val itemBinding: ItemCategoryBinding) : 
            RecyclerView.ViewHolder(itemBinding.root) {
            
            fun bind(item: Category) {
                itemBinding.categoryName.text = item.name
                itemBinding.categoryIcon.setImageResource(item.iconRes)
                
                itemBinding.root.setOnClickListener {
                    val context = it.context
                    it.startAnimation(AnimationUtils.loadAnimation(context, R.anim.press_scale))
                }
            }
        }
    }
    
    // Banner Adapter
    inner class BannerAdapter(private val items: List<Banner>) : 
        RecyclerView.Adapter<BannerAdapter.ViewHolder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val binding = ItemBannerBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return ViewHolder(binding)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.bind(items[position])
        }

        override fun getItemCount() = items.size

        inner class ViewHolder(private val itemBinding: ItemBannerBinding) : 
            RecyclerView.ViewHolder(itemBinding.root) {
            
            fun bind(item: Banner) {
                itemBinding.bannerTitle.text = item.title
                itemBinding.bannerSubtitle.text = item.subtitle
                (itemBinding.root as MaterialCardView).setCardBackgroundColor(item.bgColor)
            }
        }
    }

    // Product Adapter
    inner class CatalogAdapter(private val items: List<CatalogProduct>) : 
        RecyclerView.Adapter<CatalogAdapter.ViewHolder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val binding = ItemCatalogProductBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return ViewHolder(binding)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.bind(items[position])
        }

        override fun getItemCount() = items.size

        inner class ViewHolder(private val itemBinding: ItemCatalogProductBinding) : 
            RecyclerView.ViewHolder(itemBinding.root) {
            
            fun bind(item: CatalogProduct) {
                itemBinding.productName.text = item.name
                itemBinding.productBrand.text = item.brand
                itemBinding.productPrice.text = item.price
                
                // Discount badge
                if (item.discount != null) {
                    itemBinding.discountBadge.visibility = View.VISIBLE
                    itemBinding.discountBadge.text = item.discount
                } else {
                    itemBinding.discountBadge.visibility = View.GONE
                }
                
                // Original price (strikethrough)
                if (item.originalPrice != null) {
                    itemBinding.originalPrice.visibility = View.VISIBLE
                    itemBinding.originalPrice.text = item.originalPrice
                    itemBinding.originalPrice.paintFlags = 
                        itemBinding.originalPrice.paintFlags or android.graphics.Paint.STRIKE_THRU_TEXT_FLAG
                } else {
                    itemBinding.originalPrice.visibility = View.GONE
                }

                updateQtyUI(item)
                
                // Add button click
                itemBinding.addButton.setOnClickListener {
                    item.quantity = 1
                    updateQtyUI(item)
                    it.startAnimation(AnimationUtils.loadAnimation(it.context, R.anim.bounce))
                }

                // Plus click
                itemBinding.btnPlus.setOnClickListener {
                    item.quantity++
                    updateQtyUI(item)
                    it.startAnimation(AnimationUtils.loadAnimation(it.context, R.anim.press_scale))
                }

                // Minus click
                itemBinding.btnMinus.setOnClickListener {
                    if (item.quantity > 0) {
                        item.quantity--
                        updateQtyUI(item)
                        it.startAnimation(AnimationUtils.loadAnimation(it.context, R.anim.press_scale))
                    }
                }
                
                // Card click animation
                itemBinding.root.setOnClickListener { view ->
                    val context = view.context
                    view.startAnimation(AnimationUtils.loadAnimation(context, R.anim.press_scale))
                }
            }

            private fun updateQtyUI(item: CatalogProduct) {
                if (item.quantity > 0) {
                    itemBinding.addButton.visibility = View.GONE
                    itemBinding.qtyAdjustor.visibility = View.VISIBLE
                    itemBinding.tvQty.text = item.quantity.toString()
                } else {
                    itemBinding.addButton.visibility = View.VISIBLE
                    itemBinding.qtyAdjustor.visibility = View.GONE
                }
            }
        }
    }

    companion object {
        fun newInstance() = CatalogFragment()
    }
}
