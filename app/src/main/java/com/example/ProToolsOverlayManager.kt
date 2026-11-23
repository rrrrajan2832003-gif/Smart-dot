package com.example

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import kotlin.math.abs

/**
 * Manages SmartDot's 3 Pro WindowManager tools:
 * 1) Black Screen Pocket Mode (OLED pure #000000 touch-blocking screen + double-tap unlock badge)
 * 2) Hands-Free Auto-Scroller (floating Play/Pause + 1x/2x/3x speed + Close pill using Accessibility gestures)
 * 3) Floating Magnifying Glass (draggable circular 2.5x screen zoom lens)
 */
class ProToolsOverlayManager(
    private val context: Context,
    private val windowManager: WindowManager
) {
    private val density = context.resources.displayMetrics.density.coerceAtLeast(1f)
    private val mainHandler = Handler(Looper.getMainLooper())

    // 1. Black Screen Pocket Mode
    private var blackScreenView: View? = null
    private var lastUnlockTapAtMs: Long = 0L

    // 2. Hands-Free Auto-Scroller
    private var scrollerView: View? = null
    private var scrollerPlaying = false
    private var scrollerSpeed = 1
    private val scrollerRunnable = object : Runnable {
        override fun run() {
            if (!scrollerPlaying || scrollerView == null) return
            val service = SmartDotAccessibilityService.instance
            if (service != null && !SmartDotAccessibilityService.isUserPaused) {
                service.performAutoScrollStep(scrollerSpeed)
            }
            val intervalMs = when (scrollerSpeed) {
                3 -> 650L
                2 -> 1100L
                else -> 1750L
            }
            mainHandler.postDelayed(this, intervalMs)
        }
    }

    // 3. Floating Magnifying Glass
    private var magnifierView: View? = null
    private var magnifierParams: WindowManager.LayoutParams? = null
    private var magnifierBitmap: Bitmap? = null
    private var lensSurfaceView: MagnifierLensView? = null
    private var shieldHidden = false

    fun isBlackScreenActive(): Boolean = blackScreenView != null
    fun isAutoScrollerActive(): Boolean = scrollerView != null
    fun isMagnifierActive(): Boolean = magnifierView != null

    fun toggleBlackScreenMode() {
        if (blackScreenView != null) {
            hideBlackScreenMode()
        } else {
            showBlackScreenMode()
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    fun showBlackScreenMode() {
        if (blackScreenView != null) return
        val root = FrameLayout(context).apply {
            tag = "pro_black_screen_overlay"
            setBackgroundColor(Color.BLACK)
            isClickable = true
            isFocusable = false
        }

        val unlockBadge = TextView(context).apply {
            tag = "pro_black_screen_unlock_badge"
            text = context.getString(R.string.pocket_mode_unlock_hint)
            textSize = 11.5f
            setTextColor(Color.parseColor("#94A3B8"))
            setPadding(dp(16f), dp(10f), dp(16f), dp(10f))
            background = GradientDrawable().apply {
                cornerRadius = dp(24f).toFloat()
                setColor(Color.parseColor("#1E293B"))
                setStroke(1, Color.parseColor("#334155"))
            }
            alpha = 0.65f
        }

        root.addView(
            unlockBadge,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            ).apply {
                bottomMargin = dp(72f)
            }
        )

        unlockBadge.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                val now = SystemClock.uptimeMillis()
                if (now - lastUnlockTapAtMs <= 400L) {
                    lastUnlockTapAtMs = 0L
                    hideBlackScreenMode()
                } else {
                    lastUnlockTapAtMs = now
                }
            }
            true
        }

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            0,
            0,
            overlayWindowType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.OPAQUE
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                } else {
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
            }
        }

        runCatching {
            windowManager.addView(root, lp)
            blackScreenView = root
            if (shieldHidden) root.visibility = View.GONE
        }.onFailure { e ->
            Log.e(TAG, "Failed adding Pocket Mode black overlay", e)
        }
    }

    fun hideBlackScreenMode() {
        lastUnlockTapAtMs = 0L
        blackScreenView?.let { v ->
            runCatching { windowManager.removeView(v) }
        }
        blackScreenView = null
    }

    fun toggleAutoScroller() {
        if (scrollerView != null) {
            hideAutoScroller()
        } else {
            showAutoScroller()
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    fun showAutoScroller(accentColor: Int = Color.parseColor("#00E5FF")) {
        if (scrollerView != null) return
        if (SmartDotAccessibilityService.instance == null) {
            Toast.makeText(
                context,
                context.getString(R.string.auto_scroller_accessibility_required),
                Toast.LENGTH_LONG
            ).show()
            return
        }

        val pill = LinearLayout(context).apply {
            tag = "pro_auto_scroller_pill"
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10f), dp(6f), dp(10f), dp(6f))
            background = GradientDrawable().apply {
                cornerRadius = dp(24f).toFloat()
                setColor(Color.parseColor("#E60F172A"))
                setStroke(dp(1.5f), accentColor)
            }
            elevation = dp(10f).toFloat()
        }

        val btnPlayPause = TextView(context).apply {
            tag = "pro_auto_scroller_play_pause"
            text = "▶ Play"
            textSize = 12f
            setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
            setTextColor(Color.BLACK)
            setPadding(dp(12f), dp(6f), dp(12f), dp(6f))
            background = GradientDrawable().apply {
                cornerRadius = dp(16f).toFloat()
                setColor(accentColor)
            }
        }

        val btnSpeed = TextView(context).apply {
            tag = "pro_auto_scroller_speed"
            text = "1x"
            textSize = 12f
            setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
            setTextColor(Color.WHITE)
            setPadding(dp(10f), dp(6f), dp(10f), dp(6f))
            background = GradientDrawable().apply {
                cornerRadius = dp(14f).toFloat()
                setColor(Color.parseColor("#1E293B"))
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginStart = dp(6f)
            }
        }

        val btnClose = TextView(context).apply {
            tag = "pro_auto_scroller_close"
            text = "✕"
            textSize = 13f
            setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
            setTextColor(Color.parseColor("#EF4444"))
            setPadding(dp(10f), dp(6f), dp(8f), dp(6f))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginStart = dp(4f)
            }
        }

        btnPlayPause.setOnClickListener {
            scrollerPlaying = !scrollerPlaying
            mainHandler.removeCallbacks(scrollerRunnable)
            if (scrollerPlaying) {
                btnPlayPause.text = "⏸ Pause"
                mainHandler.post(scrollerRunnable)
            } else {
                btnPlayPause.text = "▶ Play"
            }
        }

        btnSpeed.setOnClickListener {
            scrollerSpeed = when (scrollerSpeed) {
                1 -> 2
                2 -> 3
                else -> 1
            }
            btnSpeed.text = "${scrollerSpeed}x"
        }

        btnClose.setOnClickListener { hideAutoScroller() }

        pill.addView(btnPlayPause)
        pill.addView(btnSpeed)
        pill.addView(btnClose)

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            dp(24f),
            dp(160f),
            overlayWindowType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }

        pill.setOnTouchListener(object : View.OnTouchListener {
            private var startX = 0
            private var startY = 0
            private var downX = 0f
            private var downY = 0f
            private var moved = false

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        startX = lp.x
                        startY = lp.y
                        downX = event.rawX
                        downY = event.rawY
                        moved = false
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = event.rawX - downX
                        val dy = event.rawY - downY
                        if (abs(dx) > dp(6f) || abs(dy) > dp(6f)) moved = true
                        if (moved) {
                            lp.x = (startX + dx.toInt()).coerceAtLeast(0)
                            lp.y = (startY + dy.toInt()).coerceAtLeast(0)
                            runCatching { windowManager.updateViewLayout(pill, lp) }
                        }
                        return true
                    }
                }
                return false
            }
        })

        runCatching {
            windowManager.addView(pill, lp)
            scrollerView = pill
            scrollerPlaying = true
            btnPlayPause.text = "⏸ Pause"
            mainHandler.post(scrollerRunnable)
            if (shieldHidden) pill.visibility = View.GONE
        }.onFailure { e ->
            Log.e(TAG, "Failed adding Auto-Scroller pill", e)
        }
    }

    fun hideAutoScroller() {
        scrollerPlaying = false
        mainHandler.removeCallbacks(scrollerRunnable)
        scrollerView?.let { v ->
            runCatching { windowManager.removeView(v) }
        }
        scrollerView = null
    }

    fun toggleMagnifier(accentColor: Int = Color.parseColor("#00E5FF")) {
        if (magnifierView != null) {
            hideMagnifier()
        } else {
            showMagnifier(accentColor)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    fun showMagnifier(accentColor: Int = Color.parseColor("#00E5FF")) {
        if (magnifierView != null) return
        val metrics = context.resources.displayMetrics
        val lensSizePx = dp(148f)
        val initialX = ((metrics.widthPixels - lensSizePx) / 2).coerceAtLeast(16)
        val initialY = ((metrics.heightPixels - lensSizePx) / 3).coerceAtLeast(48)

        val container = FrameLayout(context).apply {
            tag = "pro_magnifier_overlay"
        }

        val lens = MagnifierLensView(context, accentColor).apply {
            tag = "pro_magnifier_lens_view"
            layoutParams = FrameLayout.LayoutParams(lensSizePx, lensSizePx)
        }
        val closeBadge = TextView(context).apply {
            tag = "pro_magnifier_close"
            text = "✕"
            textSize = 11f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#DC2626"))
            }
            layoutParams = FrameLayout.LayoutParams(dp(26f), dp(26f), Gravity.TOP or Gravity.END).apply {
                topMargin = dp(4f)
                marginEnd = dp(4f)
            }
            setOnClickListener { hideMagnifier() }
        }

        container.addView(lens)
        container.addView(closeBadge)

        val lp = WindowManager.LayoutParams(
            lensSizePx,
            lensSizePx,
            initialX,
            initialY,
            overlayWindowType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }

        lens.setLensCenterOnScreen(initialX + lensSizePx / 2f, initialY + lensSizePx / 2f)

        container.setOnTouchListener(object : View.OnTouchListener {
            private var startX = 0
            private var startY = 0
            private var downX = 0f
            private var downY = 0f

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        startX = lp.x
                        startY = lp.y
                        downX = event.rawX
                        downY = event.rawY
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val maxX = (context.resources.displayMetrics.widthPixels - lensSizePx).coerceAtLeast(0)
                        val maxY = (context.resources.displayMetrics.heightPixels - lensSizePx).coerceAtLeast(0)
                        lp.x = (startX + (event.rawX - downX).toInt()).coerceIn(0, maxX)
                        lp.y = (startY + (event.rawY - downY).toInt()).coerceIn(0, maxY)
                        runCatching { windowManager.updateViewLayout(container, lp) }
                        lens.setLensCenterOnScreen(lp.x + lensSizePx / 2f, lp.y + lensSizePx / 2f)
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        refreshMagnifierFrame()
                        return true
                    }
                }
                return false
            }
        })

        runCatching {
            windowManager.addView(container, lp)
            magnifierView = container
            magnifierParams = lp
            lensSurfaceView = lens
            if (shieldHidden) container.visibility = View.GONE
            refreshMagnifierFrame()
        }.onFailure { e ->
            Log.e(TAG, "Failed adding Magnifier lens overlay", e)
        }
    }

    fun refreshMagnifierFrame() {
        val service = SmartDotAccessibilityService.instance ?: return
        val container = magnifierView ?: return
        val wasVisible = container.visibility == View.VISIBLE
        if (wasVisible) container.visibility = View.INVISIBLE

        mainHandler.postDelayed({
            if (magnifierView == null) return@postDelayed
            service.captureScreenBitmapInMemory { bmp ->
                mainHandler.post {
                    if (magnifierView == null) {
                        runCatching { bmp?.recycle() }
                        return@post
                    }
                    if (!shieldHidden) container.visibility = View.VISIBLE
                    if (bmp != null) {
                        magnifierBitmap?.let { old -> runCatching { if (!old.isRecycled) old.recycle() } }
                        magnifierBitmap = bmp
                        lensSurfaceView?.updateScreenBitmap(bmp)
                    }
                }
            }
        }, 60L)
    }

    fun hideMagnifier() {
        magnifierView?.let { v ->
            runCatching { windowManager.removeView(v) }
        }
        magnifierView = null
        magnifierParams = null
        lensSurfaceView = null
        magnifierBitmap?.let { bmp -> runCatching { if (!bmp.isRecycled) bmp.recycle() } }
        magnifierBitmap = null
    }

    fun setShieldHidden(hidden: Boolean) {
        shieldHidden = hidden
        val vis = if (hidden) View.GONE else View.VISIBLE
        blackScreenView?.visibility = vis
        scrollerView?.visibility = vis
        magnifierView?.visibility = vis
    }

    fun releaseAll() {
        hideBlackScreenMode()
        hideAutoScroller()
        hideMagnifier()
        mainHandler.removeCallbacksAndMessages(null)
    }

    private fun dp(value: Float): Int = (value * density).toInt()

    private fun overlayWindowType(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
    }

    private class MagnifierLensView(
        context: Context,
        private val accentColor: Int
    ) : View(context) {
        private var screenBmp: Bitmap? = null
        private var centerX = 0f
        private var centerY = 0f
        private val clipPath = Path()
        private val srcRect = Rect()
        private val dstRect = RectF()
        private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#CC0F172A")
        }
        private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 6f
            color = accentColor
        }
        private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = accentColor
            textSize = 26f
            textAlign = Paint.Align.CENTER
        }

        fun updateScreenBitmap(bmp: Bitmap) {
            screenBmp = bmp
            invalidate()
        }

        fun setLensCenterOnScreen(x: Float, y: Float) {
            centerX = x
            centerY = y
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0f || h <= 0f) return
            val radius = (minOf(w, h) * 0.5f) - 4f

            clipPath.reset()
            clipPath.addCircle(w * 0.5f, h * 0.5f, radius, Path.Direction.CW)

            canvas.save()
            canvas.clipPath(clipPath)
            canvas.drawPaint(bgPaint)

            val bmp = screenBmp?.takeIf { !it.isRecycled }
            if (bmp != null) {
                val zoom = 2.5f
                val metrics = resources.displayMetrics
                val scaleX = bmp.width.toFloat() / metrics.widthPixels.coerceAtLeast(1)
                val scaleY = bmp.height.toFloat() / metrics.heightPixels.coerceAtLeast(1)
                val halfSrcW = ((w / zoom) * scaleX * 0.5f).toInt().coerceAtLeast(8)
                val halfSrcH = ((h / zoom) * scaleY * 0.5f).toInt().coerceAtLeast(8)
                val bmpCenterX = (centerX * scaleX).toInt().coerceIn(halfSrcW, (bmp.width - halfSrcW).coerceAtLeast(halfSrcW))
                val bmpCenterY = (centerY * scaleY).toInt().coerceIn(halfSrcH, (bmp.height - halfSrcH).coerceAtLeast(halfSrcH))

                srcRect.set(
                    (bmpCenterX - halfSrcW).coerceAtLeast(0),
                    (bmpCenterY - halfSrcH).coerceAtLeast(0),
                    (bmpCenterX + halfSrcW).coerceAtMost(bmp.width),
                    (bmpCenterY + halfSrcH).coerceAtMost(bmp.height)
                )
                dstRect.set(0f, 0f, w, h)
                canvas.drawBitmap(bmp, srcRect, dstRect, bitmapPaint)
            } else {
                canvas.drawText("2.5x Lens", w * 0.5f, h * 0.53f, labelPaint)
            }
            canvas.restore()

            canvas.drawCircle(w * 0.5f, h * 0.5f, radius, ringPaint)
        }
    }

    companion object {
        private const val TAG = "ProToolsOverlay"
    }
}
