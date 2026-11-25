package com.example

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.text.TextUtils
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import kotlin.math.abs

/**
 * Multi-window floating mini-apps engine using Android WindowManager overlays.
 * Clean "Floating Apps" window design:
 * - Sleek Top Title Bar: Left Hamburger Menu (☰) + Icon + Title on left;
 *   Minimize (_), Maximize (□), Close (✕) on right.
 * - Left Hamburger (☰) Dropdown Side Menu with per-window tools + bottom Opacity Slider.
 * - Bottom-right corner drag resize grip & minimize-to-bubble support.
 */
class FloatingWindowManager(
    private val context: Context,
    private val windowManager: WindowManager
) {
    data class WindowBounds(
        var x: Int,
        var y: Int,
        var width: Int,
        var height: Int
    )

    data class WindowMenuItem(
        val id: String,
        val label: String,
        val tag: String? = null,
        val closeOnTap: Boolean = true,
        val textColor: Int? = null,
        val onClick: () -> Unit
    )

    data class ManagedWindow(
        val windowId: String,
        var title: String,
        val accentColor: Int,
        val iconRes: Int,
        val rootView: LinearLayout,
        val contentHost: FrameLayout,
        val titleView: TextView,
        val menuButton: TextView,
        val menuDrawer: LinearLayout,
        val menuItemsContainer: LinearLayout,
        val opacitySeekBar: SeekBar,
        val opacityValueLabel: TextView,
        val layoutParams: WindowManager.LayoutParams,
        val savedBounds: WindowBounds,
        var isMaximized: Boolean = false,
        var isMinimized: Boolean = false,
        var windowAlpha: Float = 1.0f,
        var bubbleView: FrameLayout? = null,
        var bubbleParams: WindowManager.LayoutParams? = null,
        var onCloseCleanup: (() -> Unit)? = null
    ) {
        private val density = rootView.resources.displayMetrics.density.coerceAtLeast(1f)

        fun updateTitle(newTitle: String) {
            title = newTitle
            titleView.text = newTitle
        }

        fun toggleMenu() {
            menuDrawer.visibility = if (menuDrawer.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            if (menuDrawer.visibility == View.VISIBLE) {
                menuDrawer.bringToFront()
            }
        }

        fun closeMenu() {
            menuDrawer.visibility = View.GONE
        }

        fun setWindowOpacityPercent(percent: Int) {
            val clamped = percent.coerceIn(25, 100)
            windowAlpha = clamped / 100f
            rootView.alpha = windowAlpha
            opacityValueLabel.text = "Opacity: $clamped%"
            val targetProgress = clamped - 25
            if (opacitySeekBar.progress != targetProgress) {
                opacitySeekBar.progress = targetProgress
            }
        }

        fun setMenuItems(items: List<WindowMenuItem>) {
            menuItemsContainer.removeAllViews()
            val ctx = menuItemsContainer.context
            items.forEach { item ->
                val tv = TextView(ctx).apply {
                    tag = item.tag ?: "fw_menu_item_${windowId}_${item.id}"
                    text = item.label
                    textSize = 12.5f
                    setTextColor(item.textColor ?: Color.parseColor("#F8FAFC"))
                    gravity = Gravity.CENTER_VERTICAL
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    minHeight = (38 * density).toInt()
                    setPadding(
                        (12 * density).toInt(),
                        (8 * density).toInt(),
                        (12 * density).toInt(),
                        (8 * density).toInt()
                    )
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        cornerRadius = 8f * density
                        setColor(Color.parseColor("#1E293B"))
                    }
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        bottomMargin = (4 * density).toInt()
                    }
                    setOnClickListener {
                        if (item.closeOnTap) {
                            closeMenu()
                        }
                        item.onClick()
                    }
                }
                menuItemsContainer.addView(tv)
            }
        }

        fun updateMenuItemLabel(tag: String, newLabel: String, newColor: Int? = null) {
            val tv = menuItemsContainer.findViewWithTag<TextView>(tag) ?: return
            tv.text = newLabel
            if (newColor != null) {
                tv.setTextColor(newColor)
            }
        }
    }

    private val density = context.resources.displayMetrics.density.coerceAtLeast(1f)
    private val activeWindows = LinkedHashMap<String, ManagedWindow>()

    fun getOpenWindowIds(): List<String> = activeWindows.keys.toList()

    fun getWindow(windowId: String): ManagedWindow? = activeWindows[windowId]

    fun hasWindow(windowId: String): Boolean = activeWindows.containsKey(windowId)

    @SuppressLint("ClickableViewAccessibility")
    fun openOrFocusWindow(
        windowId: String,
        title: String,
        accentColor: Int,
        iconRes: Int,
        requiresTextInput: Boolean = true,
        contentBuilder: (ManagedWindow) -> Pair<View, (() -> Unit)?>
    ): ManagedWindow? {
        activeWindows[windowId]?.let { existing ->
            if (existing.isMinimized) {
                restoreFromBubble(existing)
            } else {
                bringToFront(existing)
            }
            return existing
        }

        val dm = context.resources.displayMetrics
        val screenW = dm.widthPixels.coerceAtLeast((360 * density).toInt())
        val screenH = dm.heightPixels.coerceAtLeast((520 * density).toInt())

        val defaultW = (310 * density).toInt().coerceIn((220 * density).toInt(), (screenW * 0.92f).toInt())
        val defaultH = (430 * density).toInt().coerceIn((240 * density).toInt(), (screenH * 0.78f).toInt())
        val cascadeOffset = ((activeWindows.size % 5) * 22 * density).toInt()
        val initialX = (((screenW - defaultW) / 2) + cascadeOffset).coerceIn(8, (screenW - defaultW - 8).coerceAtLeast(8))
        val initialY = (((screenH - defaultH) / 3) + cascadeOffset).coerceIn(24, (screenH - defaultH - 24).coerceAtLeast(24))

        val lpFlags = if (requiresTextInput) {
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        } else {
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        }

        val lp = WindowManager.LayoutParams(
            defaultW,
            defaultH,
            initialX,
            initialY,
            overlayWindowType(),
            lpFlags,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            tag = "fw_window_$windowId"
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 16f * density
                setColor(Color.parseColor("#0F172A"))
                setStroke((1.5f * density).toInt().coerceAtLeast(1), accentColor)
            }
            elevation = 14f * density
        }

        // 1. Sleek Top Title Bar: [☰] [Icon] [Title]   [_] [□] [✕]
        val headerHeightPx = (40 * density).toInt()
        val titleBar = LinearLayout(context).apply {
            tag = "fw_title_bar_$windowId"
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                headerHeightPx
            )
            setPadding((4 * density).toInt(), 0, (4 * density).toInt(), 0)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                val r = 15f * density
                cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
                setColor(accentColor)
            }
        }

        val headerTextColor = if (isColorLight(accentColor)) Color.parseColor("#0F172A") else Color.WHITE

        val btnMenu = TextView(context).apply {
            tag = "fw_btn_menu_$windowId"
            text = "☰"
            textSize = 16f
            gravity = Gravity.CENTER
            setTextColor(headerTextColor)
            contentDescription = "Menu $title"
            layoutParams = LinearLayout.LayoutParams((36 * density).toInt(), (36 * density).toInt()).apply {
                marginEnd = (2 * density).toInt()
            }
        }

        val iconView = ImageView(context).apply {
            layoutParams = LinearLayout.LayoutParams((16 * density).toInt(), (16 * density).toInt()).apply {
                marginEnd = (6 * density).toInt()
            }
            setImageResource(iconRes)
            setColorFilter(headerTextColor, PorterDuff.Mode.SRC_IN)
            contentDescription = null
        }

        val titleView = TextView(context).apply {
            tag = "fw_title_text_$windowId"
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            text = title
            textSize = 13.5f
            setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
            setTextColor(headerTextColor)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }

        fun createChromeBtn(symbol: String, btnTag: String, desc: String): ImageButton {
            val sizePx = (34 * density).toInt()
            return ImageButton(context).apply {
                tag = btnTag
                contentDescription = desc
                layoutParams = LinearLayout.LayoutParams(sizePx, sizePx)
                setBackgroundColor(Color.TRANSPARENT)
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                setPadding((7 * density).toInt(), (7 * density).toInt(), (7 * density).toInt(), (7 * density).toInt())
                setColorFilter(headerTextColor, PorterDuff.Mode.SRC_IN)
                val res = when (symbol) {
                    "min" -> R.drawable.ic_fw_minimize
                    "max" -> R.drawable.ic_fw_maximize
                    else -> R.drawable.ic_fw_close
                }
                setImageResource(res)
            }
        }

        val btnMinimize = createChromeBtn("min", "fw_btn_minimize_$windowId", "Minimize $title")
        val btnMaximize = createChromeBtn("max", "fw_btn_maximize_$windowId", "Maximize or restore $title")
        val btnClose = createChromeBtn("close", "fw_btn_close_$windowId", "Close $title")

        titleBar.addView(btnMenu)
        titleBar.addView(iconView)
        titleBar.addView(titleView)
        titleBar.addView(btnMinimize)
        titleBar.addView(btnMaximize)
        titleBar.addView(btnClose)

        // 2. Content Host + Left Hamburger Dropdown Drawer + Bottom-Right Corner Resize Grip
        val bodyOverlay = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }

        val contentHost = FrameLayout(context).apply {
            tag = "fw_content_$windowId"
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }

        val menuItemsContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        val menuItemsScroll = ScrollView(context).apply {
            isVerticalScrollBarEnabled = true
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
            addView(menuItemsContainer)
        }

        val opacityValueLabel = TextView(context).apply {
            tag = "fw_opacity_label_$windowId"
            text = "Opacity: 100%"
            textSize = 11f
            setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
            setTextColor(accentColor)
        }

        val opacitySeekBar = SeekBar(context).apply {
            tag = "fw_opacity_slider_$windowId"
            contentDescription = "Window opacity slider"
            max = 75 // 25%..100%
            progress = 75
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = (2 * density).toInt()
            }
        }

        val opacityFooter = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((10 * density).toInt(), (8 * density).toInt(), (10 * density).toInt(), (8 * density).toInt())
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 8f * density
                setColor(Color.parseColor("#1E293B"))
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = (6 * density).toInt()
            }
            addView(opacityValueLabel)
            addView(opacitySeekBar)
        }

        val menuDrawer = LinearLayout(context).apply {
            tag = "fw_menu_drawer_$windowId"
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            isClickable = true
            isFocusable = true
            elevation = 10f * density
            setPadding((8 * density).toInt(), (8 * density).toInt(), (8 * density).toInt(), (8 * density).toInt())
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                val r = 12f * density
                cornerRadii = floatArrayOf(0f, 0f, r, r, r, r, 0f, 0f)
                setColor(Color.parseColor("#0F172A"))
                setStroke((1.2f * density).toInt().coerceAtLeast(1), accentColor)
            }
            layoutParams = FrameLayout.LayoutParams(
                (212 * density).toInt(),
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.START
            ).apply {
                bottomMargin = (18 * density).toInt()
            }
            addView(menuItemsScroll)
            addView(opacityFooter)
        }

        val gripSize = (24 * density).toInt()
        val resizeGrip = ImageView(context).apply {
            tag = "fw_resize_grip_$windowId"
            contentDescription = "Resize $title"
            layoutParams = FrameLayout.LayoutParams(gripSize, gripSize, Gravity.BOTTOM or Gravity.END)
            setImageResource(R.drawable.ic_fw_resize)
            setColorFilter(accentColor, PorterDuff.Mode.SRC_IN)
            setPadding((4 * density).toInt(), (4 * density).toInt(), (4 * density).toInt(), (4 * density).toInt())
            alpha = 0.85f
        }

        bodyOverlay.addView(contentHost)
        bodyOverlay.addView(menuDrawer)
        bodyOverlay.addView(resizeGrip)

        root.addView(titleBar)
        root.addView(bodyOverlay)

        val managed = ManagedWindow(
            windowId = windowId,
            title = title,
            accentColor = accentColor,
            iconRes = iconRes,
            rootView = root,
            contentHost = contentHost,
            titleView = titleView,
            menuButton = btnMenu,
            menuDrawer = menuDrawer,
            menuItemsContainer = menuItemsContainer,
            opacitySeekBar = opacitySeekBar,
            opacityValueLabel = opacityValueLabel,
            layoutParams = lp,
            savedBounds = WindowBounds(initialX, initialY, defaultW, defaultH)
        )

        opacitySeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val pct = (25 + progress).coerceIn(25, 100)
                managed.windowAlpha = pct / 100f
                managed.rootView.alpha = managed.windowAlpha
                managed.opacityValueLabel.text = "Opacity: $pct%"
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // Default minimal menu (always has Opacity slider footer; mini-apps customize items via setMenuItems)
        managed.setMenuItems(emptyList())

        val (innerView, cleanup) = contentBuilder(managed)
        managed.onCloseCleanup = cleanup
        contentHost.addView(
            innerView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        // Wire Chrome & Hamburger Controls
        btnMenu.setOnClickListener { managed.toggleMenu() }
        btnClose.setOnClickListener { closeWindow(windowId) }
        btnMinimize.setOnClickListener { minimizeToBubble(managed) }
        btnMaximize.setOnClickListener { toggleMaximize(managed) }

        // Title Bar Drag-to-Move
        titleBar.setOnTouchListener(object : View.OnTouchListener {
            private var startX = 0
            private var startY = 0
            private var downRawX = 0f
            private var downRawY = 0f

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        startX = lp.x
                        startY = lp.y
                        downRawX = event.rawX
                        downRawY = event.rawY
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        if (managed.isMaximized) return true
                        val maxX = (context.resources.displayMetrics.widthPixels - lp.width).coerceAtLeast(0)
                        val maxY = (context.resources.displayMetrics.heightPixels - lp.height).coerceAtLeast(0)
                        lp.x = (startX + (event.rawX - downRawX).toInt()).coerceIn(0, maxX)
                        lp.y = (startY + (event.rawY - downRawY).toInt()).coerceIn(0, maxY)
                        managed.savedBounds.x = lp.x
                        managed.savedBounds.y = lp.y
                        runCatching { windowManager.updateViewLayout(root, lp) }
                        return true
                    }
                }
                return false
            }
        })

        // Bottom-Right Corner Drag-to-Resize
        resizeGrip.setOnTouchListener(object : View.OnTouchListener {
            private var startW = 0
            private var startH = 0
            private var downRawX = 0f
            private var downRawY = 0f

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        startW = lp.width
                        startH = lp.height
                        downRawX = event.rawX
                        downRawY = event.rawY
                        managed.isMaximized = false
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val metrics = context.resources.displayMetrics
                        val minW = (210 * density).toInt()
                        val minH = (220 * density).toInt()
                        val maxW = (metrics.widthPixels - lp.x - 8).coerceAtLeast(minW)
                        val maxH = (metrics.heightPixels - lp.y - 16).coerceAtLeast(minH)
                        lp.width = (startW + (event.rawX - downRawX).toInt()).coerceIn(minW, maxW)
                        lp.height = (startH + (event.rawY - downRawY).toInt()).coerceIn(minH, maxH)
                        managed.savedBounds.width = lp.width
                        managed.savedBounds.height = lp.height
                        runCatching { windowManager.updateViewLayout(root, lp) }
                        return true
                    }
                }
                return false
            }
        })

        return try {
            windowManager.addView(root, lp)
            activeWindows[windowId] = managed
            managed
        } catch (e: Exception) {
            Log.e(TAG, "Failed adding floating mini-app window $windowId", e)
            runCatching { cleanup?.invoke() }
            null
        }
    }

    fun toggleMaximize(managed: ManagedWindow) {
        val metrics = context.resources.displayMetrics
        val lp = managed.layoutParams
        if (!managed.isMaximized) {
            managed.savedBounds.x = lp.x
            managed.savedBounds.y = lp.y
            managed.savedBounds.width = lp.width
            managed.savedBounds.height = lp.height
            lp.x = (8 * density).toInt()
            lp.y = (32 * density).toInt()
            lp.width = (metrics.widthPixels - (16 * density).toInt()).coerceAtLeast(240)
            lp.height = (metrics.heightPixels * 0.84f).toInt().coerceAtLeast(320)
            managed.isMaximized = true
        } else {
            lp.x = managed.savedBounds.x
            lp.y = managed.savedBounds.y
            lp.width = managed.savedBounds.width
            lp.height = managed.savedBounds.height
            managed.isMaximized = false
        }
        runCatching { windowManager.updateViewLayout(managed.rootView, lp) }
    }

    @SuppressLint("ClickableViewAccessibility")
    fun minimizeToBubble(managed: ManagedWindow) {
        if (managed.isMinimized) return
        managed.closeMenu()
        managed.isMinimized = true
        // Keep rootView attached as View.GONE so WebView / MediaPlayer audio continues cleanly!
        managed.rootView.visibility = View.GONE

        val bubbleSizePx = (52 * density).toInt()
        val bubble = FrameLayout(context).apply {
            tag = "fw_bubble_${managed.windowId}"
            contentDescription = "Restore ${managed.title}"
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#0F172A"))
                setStroke((2f * density).toInt().coerceAtLeast(2), managed.accentColor)
            }
            elevation = 12f * density
        }
        val icon = ImageView(context).apply {
            val pad = (12 * density).toInt()
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            setPadding(pad, pad, pad, pad)
            setImageResource(managed.iconRes)
            setColorFilter(managed.accentColor, PorterDuff.Mode.SRC_IN)
            contentDescription = "Restore ${managed.title}"
        }
        bubble.addView(icon)

        val bParams = WindowManager.LayoutParams(
            bubbleSizePx,
            bubbleSizePx,
            managed.layoutParams.x.coerceAtLeast(16),
            managed.layoutParams.y.coerceAtLeast(80),
            overlayWindowType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }

        bubble.setOnTouchListener(object : View.OnTouchListener {
            private var startX = 0
            private var startY = 0
            private var rawX0 = 0f
            private var rawY0 = 0f
            private var moved = false

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        startX = bParams.x
                        startY = bParams.y
                        rawX0 = event.rawX
                        rawY0 = event.rawY
                        moved = false
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = event.rawX - rawX0
                        val dy = event.rawY - rawY0
                        if (abs(dx) > 8 * density || abs(dy) > 8 * density) {
                            moved = true
                        }
                        if (moved) {
                            bParams.x = (startX + dx.toInt()).coerceAtLeast(0)
                            bParams.y = (startY + dy.toInt()).coerceAtLeast(0)
                            runCatching { windowManager.updateViewLayout(bubble, bParams) }
                        }
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (!moved) {
                            restoreFromBubble(managed)
                        }
                        return true
                    }
                }
                return false
            }
        })

        managed.bubbleView = bubble
        managed.bubbleParams = bParams
        runCatching { windowManager.addView(bubble, bParams) }
    }

    fun restoreFromBubble(managed: ManagedWindow) {
        managed.bubbleView?.let { bubble ->
            runCatching { windowManager.removeView(bubble) }
        }
        managed.bubbleView = null
        managed.bubbleParams = null
        managed.isMinimized = false
        managed.rootView.visibility = View.VISIBLE
        bringToFront(managed)
    }

    fun bringToFront(managed: ManagedWindow) {
        if (!activeWindows.containsKey(managed.windowId)) return
        if (activeWindows.size > 1) {
            runCatching {
                windowManager.removeView(managed.rootView)
                windowManager.addView(managed.rootView, managed.layoutParams)
            }
        }
        activeWindows.remove(managed.windowId)
        activeWindows[managed.windowId] = managed
    }

    fun closeWindow(windowId: String) {
        val managed = activeWindows.remove(windowId) ?: return
        managed.bubbleView?.let { bubble ->
            runCatching { windowManager.removeView(bubble) }
        }
        managed.bubbleView = null
        managed.bubbleParams = null
        runCatching { windowManager.removeView(managed.rootView) }
        runCatching { managed.onCloseCleanup?.invoke() }
        managed.onCloseCleanup = null
        destroyChildWebViews(managed.rootView)
    }

    fun closeAllWindows() {
        val keys = activeWindows.keys.toList()
        keys.forEach { closeWindow(it) }
    }

    fun setShieldHidden(hidden: Boolean) {
        activeWindows.values.forEach { managed ->
            if (hidden) {
                managed.rootView.visibility = View.GONE
                managed.bubbleView?.visibility = View.GONE
            } else {
                managed.rootView.visibility = if (managed.isMinimized) View.GONE else View.VISIBLE
                managed.bubbleView?.visibility = if (managed.isMinimized) View.VISIBLE else View.GONE
            }
        }
    }

    private fun destroyChildWebViews(view: View) {
        if (view is WebView) {
            runCatching {
                view.stopLoading()
                view.destroy()
            }
            return
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                destroyChildWebViews(view.getChildAt(i))
            }
        }
    }

    private fun overlayWindowType(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
    }

    private fun isColorLight(color: Int): Boolean {
        val luminance = (0.299 * Color.red(color) + 0.587 * Color.green(color) + 0.114 * Color.blue(color)) / 255.0
        return luminance > 0.65
    }

    companion object {
        private const val TAG = "FloatingWindowManager"
    }
}
