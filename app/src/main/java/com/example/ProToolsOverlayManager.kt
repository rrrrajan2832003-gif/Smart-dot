package com.example

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.Typeface
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
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import kotlin.math.abs

/**
 * Manages SmartDot's 3 Pro WindowManager tools:
 * 1) True Pure-Black OLED Pocket Mode (edge-to-edge #000000 touch-blocking overlay + tiny dim lock icon)
 * 2) Hands-Free Auto-Scroller (floating Play/Pause + 1x/2x/3x speed + Close pill)
 * 3) Auto-Clicker (draggable target crosshair + floating Play/Pause & 100ms–2000ms speed bar)
 */
class ProToolsOverlayManager(
    private val context: Context,
    private val windowManager: WindowManager
) {
    private val density = context.resources.displayMetrics.density.coerceAtLeast(1f)
    private val mainHandler = Handler(Looper.getMainLooper())

    // 1. True Pure-Black OLED Pocket Mode
    private var blackScreenView: View? = null
    private var lastUnlockTapAtMs: Long = 0L

    // 2. Hands-Free Auto-Scroller
    private var scrollerView: View? = null
    private var scrollerPlaying = false
    private var scrollerSpeed = 1
    private val scrollerRunnable = object : Runnable {
        override fun run() {
            if (!scrollerPlaying || scrollerView == null || shieldHidden) return
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

    // 3. Multi-Point Sequential Auto-Clicker (1, 2, 3, 4...)
    data class AutoClickerTarget(
        val number: Int,
        val view: FrameLayout,
        val labelView: TextView,
        val params: WindowManager.LayoutParams
    )

    private val autoClickerTargets = ArrayList<AutoClickerTarget>()
    private var autoClickerBarView: View? = null
    private var autoClickerBarParams: WindowManager.LayoutParams? = null
    private var autoClickerAccentColor: Int = Color.parseColor("#00E5FF")
    private var autoClickerPlaying = false
    private var autoClickerStepIdx = 0
    private val autoClickerIntervalsMs = longArrayOf(100L, 250L, 500L, 1000L, 1500L, 2000L)
    private var autoClickerIntervalIdx = 2 // 500ms default
    private val autoClickerRunnable = object : Runnable {
        override fun run() {
            if (!autoClickerPlaying || autoClickerTargets.isEmpty() || shieldHidden) return
            val service = SmartDotAccessibilityService.instance
            val currentIdx = autoClickerStepIdx % autoClickerTargets.size
            val target = autoClickerTargets[currentIdx]
            autoClickerStepIdx = (currentIdx + 1) % autoClickerTargets.size
            if (service != null && !SmartDotAccessibilityService.isUserPaused) {
                val targetW = target.view.width.takeIf { it > 0 } ?: dp(48f)
                val targetH = target.view.height.takeIf { it > 0 } ?: dp(48f)
                val tapX = target.params.x + (targetW / 2f)
                val tapY = target.params.y + (targetH / 2f)
                service.performTapAt(tapX, tapY)
            }
            mainHandler.postDelayed(this, getAutoClickerIntervalMs())
        }
    }

    private var shieldHidden = false

    fun isBlackScreenActive(): Boolean = blackScreenView != null
    fun isAutoScrollerActive(): Boolean = scrollerView != null
    fun isAutoClickerActive(): Boolean = autoClickerTargets.isNotEmpty()
    fun isAutoClickerPlaying(): Boolean = autoClickerPlaying
    fun getAutoClickerIntervalMs(): Long = autoClickerIntervalsMs[autoClickerIntervalIdx]
    fun getAutoClickerTargetCount(): Int = autoClickerTargets.size
    fun getAutoClickerTargets(): List<AutoClickerTarget> = autoClickerTargets.toList()

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
            setOnTouchListener { _, _ -> true }
        }

        val unlockLockIcon = ImageView(context).apply {
            tag = "pro_black_screen_unlock_badge"
            setImageResource(android.R.drawable.ic_lock_idle_lock)
            setColorFilter(Color.parseColor("#94A3B8"), PorterDuff.Mode.SRC_IN)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(dp(12f), dp(12f), dp(12f), dp(12f))
            alpha = 0.20f
            contentDescription = context.getString(R.string.pocket_mode_unlock_hint)
            isClickable = true
            isFocusable = false
        }

        root.addView(
            unlockLockIcon,
            FrameLayout.LayoutParams(
                dp(48f),
                dp(48f),
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            ).apply {
                bottomMargin = dp(64f)
            }
        )

        unlockLockIcon.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                val now = SystemClock.uptimeMillis()
                if (now - lastUnlockTapAtMs <= 450L) {
                    lastUnlockTapAtMs = 0L
                    hideBlackScreenMode()
                } else {
                    lastUnlockTapAtMs = now
                }
            }
            true
        }

        @Suppress("DEPRECATION")
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            0,
            0,
            overlayWindowType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_FULLSCREEN,
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
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                fitInsetsTypes = 0
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
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
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
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
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
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
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

        pill.setOnTouchListener(createDragTouchListener(pill, lp))

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

    fun toggleAutoClicker(accentColor: Int = Color.parseColor("#00E5FF")) {
        if (autoClickerTargets.isNotEmpty() || autoClickerBarView != null) {
            hideAutoClicker()
        } else {
            showAutoClicker(accentColor)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    fun addAutoClickerTarget(accentColor: Int = autoClickerAccentColor): AutoClickerTarget? {
        val number = autoClickerTargets.size + 1
        val metrics = context.resources.displayMetrics
        val targetSizePx = dp(48f)
        val centerX = ((metrics.widthPixels - targetSizePx) / 2).coerceAtLeast(16)
        val centerY = ((metrics.heightPixels - targetSizePx) / 2).coerceAtLeast(64)
        val offsetStep = dp(54f)
        val staggerIndex = (number - 1) % 6
        val maxX = (metrics.widthPixels - targetSizePx - 8).coerceAtLeast(16)
        val maxY = (metrics.heightPixels - targetSizePx - 32).coerceAtLeast(64)
        val initialTargetX = (centerX + ((staggerIndex % 2) * offsetStep) - offsetStep / 2).coerceIn(16, maxX)
        val initialTargetY = (centerY + ((staggerIndex - 1) * offsetStep)).coerceIn(64, maxY)

        val targetRing = FrameLayout(context).apply {
            tag = "pro_auto_clicker_target_$number"
            contentDescription = "Auto-Clicker Tap Target $number"
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#660F172A"))
                setStroke(dp(2.5f).coerceAtLeast(2), accentColor)
            }
            elevation = dp(12f).toFloat()
            alpha = if (autoClickerPlaying) 0.65f else 1.0f
        }
        val numberLabel = TextView(context).apply {
            tag = "pro_auto_clicker_label_$number"
            text = number.toString()
            textSize = 16f
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            setTextColor(accentColor)
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        targetRing.addView(numberLabel)

        var baseFlags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        if (autoClickerPlaying) {
            baseFlags = baseFlags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }
        val targetLp = WindowManager.LayoutParams(
            targetSizePx,
            targetSizePx,
            initialTargetX,
            initialTargetY,
            overlayWindowType(),
            baseFlags,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }

        targetRing.setOnTouchListener(
            createDragTouchListener(targetRing, targetLp, clampW = targetSizePx, clampH = targetSizePx)
        )

        val added = runCatching {
            windowManager.addView(targetRing, targetLp)
            if (shieldHidden) targetRing.visibility = View.GONE
            true
        }.getOrElse { e ->
            Log.e(TAG, "Failed adding Auto-Clicker target #$number", e)
            false
        }
        if (!added) return null

        val target = AutoClickerTarget(
            number = number,
            view = targetRing,
            labelView = numberLabel,
            params = targetLp
        )
        autoClickerTargets.add(target)
        return target
    }

    fun removeLastAutoClickerTarget(): Boolean {
        if (autoClickerTargets.size <= 1) return false
        val removed = autoClickerTargets.removeAt(autoClickerTargets.lastIndex)
        runCatching { windowManager.removeView(removed.view) }
        if (autoClickerTargets.isNotEmpty()) {
            autoClickerStepIdx %= autoClickerTargets.size
        } else {
            autoClickerStepIdx = 0
        }
        return true
    }

    @SuppressLint("ClickableViewAccessibility")
    fun showAutoClicker(accentColor: Int = Color.parseColor("#00E5FF")) {
        if (autoClickerTargets.isNotEmpty() || autoClickerBarView != null) return
        if (SmartDotAccessibilityService.instance == null) {
            Toast.makeText(
                context,
                context.getString(R.string.auto_clicker_accessibility_required),
                Toast.LENGTH_LONG
            ).show()
            return
        }

        autoClickerAccentColor = accentColor
        autoClickerStepIdx = 0
        autoClickerPlaying = false

        val firstTarget = addAutoClickerTarget(accentColor) ?: return

        val bar = LinearLayout(context).apply {
            tag = "pro_auto_clicker_bar"
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8f), dp(6f), dp(8f), dp(6f))
            background = GradientDrawable().apply {
                cornerRadius = dp(24f).toFloat()
                setColor(Color.parseColor("#E60F172A"))
                setStroke(dp(1.5f), accentColor)
            }
            elevation = dp(12f).toFloat()
        }

        val btnPlayPause = TextView(context).apply {
            tag = "pro_auto_clicker_play_pause"
            text = "▶"
            textSize = 13f
            gravity = Gravity.CENTER
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            setTextColor(Color.BLACK)
            minWidth = dp(42f)
            minHeight = dp(36f)
            setPadding(dp(10f), dp(6f), dp(10f), dp(6f))
            contentDescription = "Play or Pause Auto-Clicker"
            background = GradientDrawable().apply {
                cornerRadius = dp(16f).toFloat()
                setColor(accentColor)
            }
        }

        val btnAddPoint = TextView(context).apply {
            tag = "pro_auto_clicker_add"
            text = "+"
            textSize = 15f
            gravity = Gravity.CENTER
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            setTextColor(Color.WHITE)
            minWidth = dp(36f)
            minHeight = dp(36f)
            setPadding(dp(8f), dp(4f), dp(8f), dp(4f))
            contentDescription = "Add Tap Point"
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

        val btnRemovePoint = TextView(context).apply {
            tag = "pro_auto_clicker_remove"
            text = "-"
            textSize = 15f
            gravity = Gravity.CENTER
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            setTextColor(Color.WHITE)
            minWidth = dp(36f)
            minHeight = dp(36f)
            setPadding(dp(8f), dp(4f), dp(8f), dp(4f))
            contentDescription = "Remove Last Tap Point"
            background = GradientDrawable().apply {
                cornerRadius = dp(14f).toFloat()
                setColor(Color.parseColor("#1E293B"))
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginStart = dp(4f)
            }
        }

        val btnSpeed = TextView(context).apply {
            tag = "pro_auto_clicker_speed"
            text = "${getAutoClickerIntervalMs()}ms"
            textSize = 12f
            gravity = Gravity.CENTER
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            setTextColor(Color.WHITE)
            minHeight = dp(36f)
            setPadding(dp(10f), dp(6f), dp(10f), dp(6f))
            contentDescription = "Cycle Auto-Clicker Tap Interval"
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
            tag = "pro_auto_clicker_close"
            text = "✕"
            textSize = 13f
            gravity = Gravity.CENTER
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            setTextColor(Color.parseColor("#EF4444"))
            minWidth = dp(36f)
            minHeight = dp(36f)
            setPadding(dp(8f), dp(6f), dp(8f), dp(6f))
            contentDescription = "Close Auto-Clicker"
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginStart = dp(4f)
            }
        }

        fun updateTargetsPassThrough(playing: Boolean) {
            autoClickerTargets.forEach { target ->
                if (playing) {
                    target.params.flags = target.params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                    target.view.alpha = 0.65f
                } else {
                    target.params.flags = target.params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
                    target.view.alpha = 1.0f
                }
                runCatching { windowManager.updateViewLayout(target.view, target.params) }
            }
        }

        btnPlayPause.setOnClickListener {
            autoClickerPlaying = !autoClickerPlaying
            mainHandler.removeCallbacks(autoClickerRunnable)
            updateTargetsPassThrough(autoClickerPlaying)
            if (autoClickerPlaying) {
                autoClickerStepIdx = 0
                btnPlayPause.text = "⏸"
                mainHandler.post(autoClickerRunnable)
            } else {
                btnPlayPause.text = "▶"
            }
        }

        btnAddPoint.setOnClickListener {
            addAutoClickerTarget(accentColor)
        }

        btnRemovePoint.setOnClickListener {
            removeLastAutoClickerTarget()
        }

        btnSpeed.setOnClickListener {
            autoClickerIntervalIdx = (autoClickerIntervalIdx + 1) % autoClickerIntervalsMs.size
            btnSpeed.text = "${getAutoClickerIntervalMs()}ms"
        }

        btnClose.setOnClickListener { hideAutoClicker() }

        bar.addView(btnPlayPause)
        bar.addView(btnAddPoint)
        bar.addView(btnRemovePoint)
        bar.addView(btnSpeed)
        bar.addView(btnClose)

        val barLp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            dp(20f),
            dp(140f),
            overlayWindowType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }

        bar.setOnTouchListener(createDragTouchListener(bar, barLp))

        runCatching {
            windowManager.addView(bar, barLp)
            autoClickerBarView = bar
            autoClickerBarParams = barLp
            if (shieldHidden) {
                firstTarget.view.visibility = View.GONE
                bar.visibility = View.GONE
            }
        }.onFailure { e ->
            Log.e(TAG, "Failed adding Auto-Clicker bar overlay", e)
            runCatching { windowManager.removeView(firstTarget.view) }
            runCatching { windowManager.removeView(bar) }
            autoClickerTargets.clear()
            autoClickerBarView = null
            autoClickerBarParams = null
        }
    }

    fun hideAutoClicker() {
        autoClickerPlaying = false
        autoClickerStepIdx = 0
        mainHandler.removeCallbacks(autoClickerRunnable)
        autoClickerTargets.forEach { target ->
            runCatching { windowManager.removeView(target.view) }
        }
        autoClickerTargets.clear()
        autoClickerBarView?.let { v -> runCatching { windowManager.removeView(v) } }
        autoClickerBarView = null
        autoClickerBarParams = null
    }

    fun setShieldHidden(hidden: Boolean) {
        shieldHidden = hidden
        val vis = if (hidden) View.GONE else View.VISIBLE
        blackScreenView?.visibility = vis
        scrollerView?.visibility = vis
        autoClickerTargets.forEach { it.view.visibility = vis }
        autoClickerBarView?.visibility = vis
        if (hidden) {
            mainHandler.removeCallbacks(scrollerRunnable)
            mainHandler.removeCallbacks(autoClickerRunnable)
        } else {
            if (scrollerPlaying && scrollerView != null) mainHandler.post(scrollerRunnable)
            if (autoClickerPlaying && autoClickerTargets.isNotEmpty()) mainHandler.post(autoClickerRunnable)
        }
    }

    fun releaseAll() {
        hideBlackScreenMode()
        hideAutoScroller()
        hideAutoClicker()
        mainHandler.removeCallbacksAndMessages(null)
    }

    private fun createDragTouchListener(
        view: View,
        lp: WindowManager.LayoutParams,
        clampW: Int = 0,
        clampH: Int = 0
    ): View.OnTouchListener {
        return object : View.OnTouchListener {
            private var startX = 0
            private var startY = 0
            private var downX = 0f
            private var downY = 0f
            private var moved = false

            @SuppressLint("ClickableViewAccessibility")
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
                            val metrics = context.resources.displayMetrics
                            val maxX = if (clampW > 0) (metrics.widthPixels - clampW).coerceAtLeast(0) else Int.MAX_VALUE
                            val maxY = if (clampH > 0) (metrics.heightPixels - clampH).coerceAtLeast(0) else Int.MAX_VALUE
                            lp.x = (startX + dx.toInt()).coerceIn(0, maxX)
                            lp.y = (startY + dy.toInt()).coerceIn(0, maxY)
                            runCatching { windowManager.updateViewLayout(view, lp) }
                        }
                        return true
                    }
                }
                return false
            }
        }
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

    companion object {
        private const val TAG = "ProToolsOverlay"
    }
}
