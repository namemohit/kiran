package com.example.cameraapp

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.os.Bundle
import android.view.*
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.DialogFragment
import androidx.viewpager2.widget.ViewPager2
import androidx.recyclerview.widget.RecyclerView

/**
 * A DialogFragment that shows a full-screen image gallery with 3 views:
 * 1. Full Frame (original camera image)
 * 2. Cropped Image (YOLOv8n bounding box)
 * 3. Segmented Image (crop with mask applied)
 */
class ZoomableImageDialogFragment : DialogFragment() {

    private var images: List<Pair<String, Bitmap?>> = emptyList()

    companion object {
        fun newInstance(
            fullFrame: Bitmap?,
            cropped: Bitmap?,
            segmented: Bitmap?
        ): ZoomableImageDialogFragment {
            val fragment = ZoomableImageDialogFragment()
            fragment.images = listOf(
                "Full Frame" to fullFrame,
                "Cropped (YOLOv8n)" to cropped,
                "Segmented" to segmented
            ).filter { it.second != null }
            return fragment
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NORMAL, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val layout = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        // Title/Label
        val titleView = TextView(requireContext()).apply {
            text = if (images.isNotEmpty()) images[0].first else "Image"
            setTextColor(Color.WHITE)
            textSize = 18f
            gravity = Gravity.CENTER
            setPadding(16, 32, 16, 16)
        }

        // ViewPager for swipeable images
        val viewPager = ViewPager2(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
            adapter = ImagePagerAdapter(images) { dismiss() }
            registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
                override fun onPageSelected(position: Int) {
                    titleView.text = images.getOrNull(position)?.first ?: "Image"
                }
            })
        }

        // Page Indicator
        val indicator = TextView(requireContext()).apply {
            text = "Swipe to see all ${images.size} views • Tap to close"
            setTextColor(Color.GRAY)
            textSize = 12f
            gravity = Gravity.CENTER
            setPadding(16, 16, 16, 32)
        }

        layout.addView(titleView)
        layout.addView(viewPager)
        layout.addView(indicator)

        return layout
    }

    /**
     * Adapter for the ViewPager2 image carousel.
     */
    inner class ImagePagerAdapter(
        private val items: List<Pair<String, Bitmap?>>,
        private val onTap: () -> Unit
    ) : RecyclerView.Adapter<ImagePagerAdapter.ImageViewHolder>() {

        inner class ImageViewHolder(val imageView: ZoomableImageView) : RecyclerView.ViewHolder(imageView)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ImageViewHolder {
            val imageView = ZoomableImageView(parent.context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                setBackgroundColor(Color.BLACK)
            }
            
            // Close on single tap
            val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
                override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                    onTap()
                    return true
                }
            })
            imageView.setOnTouchListener { v, event ->
                gestureDetector.onTouchEvent(event)
                false // allow zoom gestures too
            }
            
            return ImageViewHolder(imageView)
        }

        override fun onBindViewHolder(holder: ImageViewHolder, position: Int) {
            items[position].second?.let { bitmap ->
                holder.imageView.setImageBitmap(bitmap)
            }
        }

        override fun getItemCount(): Int = items.size
    }

    /**
     * Custom ImageView for pinch-to-zoom, panning, and double-tap logic.
     */
    class ZoomableImageView(context: android.content.Context) : androidx.appcompat.widget.AppCompatImageView(context), 
        View.OnTouchListener, ScaleGestureDetector.OnScaleGestureListener, GestureDetector.OnDoubleTapListener {

        private val imgMatrix = Matrix()
        private var mode = NONE

        private var lastTouchX = 0f
        private var lastTouchY = 0f
        private var scaleFactor = 1f
        private var minScale = 1f
        private var maxScale = 5f

        private val scaleDetector = ScaleGestureDetector(context, this)
        private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean = this@ZoomableImageView.onDoubleTap(e)
        })

        companion object {
            private const val NONE = 0
            private const val DRAG = 1
            private const val ZOOM = 2
        }

        init {
            scaleType = ScaleType.MATRIX
            setOnTouchListener(this)
        }

        override fun setImageBitmap(bm: Bitmap?) {
            super.setImageBitmap(bm)
            bm?.let { setupInitialMatrix(it) }
        }

        private fun setupInitialMatrix(bm: Bitmap) {
            val viewWidth = width.toFloat()
            val viewHeight = height.toFloat()
            if (viewWidth == 0f || viewHeight == 0f) {
                post { setupInitialMatrix(bm) }
                return
            }

            val drawableWidth = bm.width.toFloat()
            val drawableHeight = bm.height.toFloat()

            val scale: Float
            val dx: Float
            val dy: Float

            if (drawableWidth * viewHeight > viewWidth * drawableHeight) {
                scale = viewWidth / drawableWidth
                dx = 0f
                dy = (viewHeight - drawableHeight * scale) * 0.5f
            } else {
                scale = viewHeight / drawableHeight
                dx = (viewWidth - drawableWidth * scale) * 0.5f
                dy = 0f
            }

            minScale = scale
            scaleFactor = scale
            imgMatrix.setScale(scale, scale)
            imgMatrix.postTranslate(dx, dy)
            imageMatrix = imgMatrix
        }

        override fun onTouch(v: View?, event: MotionEvent): Boolean {
            gestureDetector.onTouchEvent(event)
            scaleDetector.onTouchEvent(event)

            val curr = arrayOf(event.x, event.y)
            
            when (event.action and MotionEvent.ACTION_MASK) {
                MotionEvent.ACTION_DOWN -> {
                    lastTouchX = curr[0]
                    lastTouchY = curr[1]
                    mode = DRAG
                }
                MotionEvent.ACTION_MOVE -> {
                    if (mode == DRAG) {
                        val deltaX = curr[0] - lastTouchX
                        val deltaY = curr[1] - lastTouchY
                        imgMatrix.postTranslate(deltaX, deltaY)
                        fixTranslation()
                        lastTouchX = curr[0]
                        lastTouchY = curr[1]
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                    mode = NONE
                }
                MotionEvent.ACTION_POINTER_DOWN -> {
                    mode = ZOOM
                }
            }
            imageMatrix = imgMatrix
            return true
        }

        override fun onScale(detector: ScaleGestureDetector): Boolean {
            var factor = detector.scaleFactor
            val origScale = scaleFactor
            scaleFactor *= factor

            if (scaleFactor > maxScale) {
                scaleFactor = maxScale
                factor = maxScale / origScale
            } else if (scaleFactor < minScale) {
                scaleFactor = minScale
                factor = minScale / origScale
            }

            imgMatrix.postScale(factor, factor, detector.focusX, detector.focusY)
            fixTranslation()
            return true
        }

        private fun fixTranslation() {
            val i = FloatArray(9)
            imgMatrix.getValues(i)
            val transX = i[Matrix.MTRANS_X]
            val transY = i[Matrix.MTRANS_Y]
            
            val drawableWidth = drawable?.intrinsicWidth?.toFloat() ?: 0f
            val drawableHeight = drawable?.intrinsicHeight?.toFloat() ?: 0f
            
            val scaledWidth = drawableWidth * scaleFactor
            val scaledHeight = drawableHeight * scaleFactor

            var fixX = 0f
            var fixY = 0f

            val viewWidth = width.toFloat()
            val viewHeight = height.toFloat()

            if (scaledWidth <= viewWidth) {
                fixX = (viewWidth - scaledWidth) / 2 - transX
            } else if (transX > 0) {
                fixX = -transX
            } else if (transX < viewWidth - scaledWidth) {
                fixX = viewWidth - scaledWidth - transX
            }

            if (scaledHeight <= viewHeight) {
                fixY = (viewHeight - scaledHeight) / 2 - transY
            } else if (transY > 0) {
                fixY = -transY
            } else if (transY < viewHeight - scaledHeight) {
                fixY = viewHeight - scaledHeight - transY
            }

            if (fixX != 0f || fixY != 0f) {
                imgMatrix.postTranslate(fixX, fixY)
            }
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            val targetScale = if (scaleFactor > minScale) minScale else minScale * 2f
            val factor = targetScale / scaleFactor
            imgMatrix.postScale(factor, factor, e.x, e.y)
            scaleFactor = targetScale
            fixTranslation()
            imageMatrix = imgMatrix
            return true
        }

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean = false
        override fun onDoubleTapEvent(e: MotionEvent): Boolean = false
        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
            mode = ZOOM
            return true
        }
        override fun onScaleEnd(detector: ScaleGestureDetector) {
            mode = NONE
        }
    }
}
