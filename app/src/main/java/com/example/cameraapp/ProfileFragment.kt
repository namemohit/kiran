package com.example.cameraapp

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AnimationUtils
import androidx.core.content.ContextCompat
import androidx.appcompat.app.AppCompatDelegate
import androidx.fragment.app.Fragment
import com.example.cameraapp.databinding.FragmentProfileBinding

class ProfileFragment : Fragment() {
    private var _binding: FragmentProfileBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentProfileBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        binding.darkModeSwitch.isChecked = AppSettings.isDarkMode
        updateDarkModeIcon(AppSettings.isDarkMode)
        
        setupMenuItems()
        animateItems()
    }
    
    private fun setupMenuItems() {
        // SKU List
        binding.menuSkuList.setOnClickListener { view ->
            animateClick(view)
            (activity as? MainActivity)?.showFragment(EcommerceFragment.newInstance())
        }
        
        // Performance Monitor
        binding.menuPerformance.setOnClickListener { view ->
            animateClick(view)
            (activity as? MainActivity)?.showFragment(PerformanceFragment.newInstance())
        }
        
        // App Logs
        binding.menuLogs.setOnClickListener { view ->
            animateClick(view)
            (activity as? MainActivity)?.showFragment(LogsFragment.newInstance())
        }
        
        // Dark Mode
        binding.menuDarkMode.setOnClickListener {
            binding.darkModeSwitch.toggle()
        }
        
        binding.darkModeSwitch.setOnCheckedChangeListener { _, isChecked ->
            AppSettings.isDarkMode = isChecked
            updateDarkModeEffect(isChecked)
        }

        // About
        binding.menuAbout.setOnClickListener { view ->
            animateClick(view)
        }
    }
    
    private fun updateDarkModeEffect(isDark: Boolean) {
        updateDarkModeIcon(isDark)
        val mode = if (isDark) {
            AppCompatDelegate.MODE_NIGHT_YES
        } else {
            AppCompatDelegate.MODE_NIGHT_NO
        }
        AppCompatDelegate.setDefaultNightMode(mode)
    }

    private fun updateDarkModeIcon(isDark: Boolean) {
        val iconRes = if (isDark) {
            android.R.drawable.ic_menu_recent_history // Moon-like if available, or stay consistent
        } else {
            android.R.drawable.ic_menu_day
        }
        binding.darkModeIcon.setImageResource(iconRes)
    }
    
    private fun animateClick(view: View) {
        view.startAnimation(AnimationUtils.loadAnimation(context, R.anim.press_scale))
    }
    
    private fun animateItems() {
        val views = listOf(
            binding.profileHeader,
            binding.statsRow,
            binding.menuDarkMode,
            binding.menuSkuList,
            binding.menuPerformance,
            binding.menuLogs,
            binding.menuAbout
        )
        
        views.forEachIndexed { index, view ->
            view.alpha = 0f
            view.translationY = 50f
            view.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(300)
                .setStartDelay((index * 50).toLong())
                .start()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        fun newInstance() = ProfileFragment()
    }
}
