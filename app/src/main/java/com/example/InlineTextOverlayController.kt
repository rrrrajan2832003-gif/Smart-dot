package com.example

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import kotlin.math.abs

/**
 * Displays a completely transparent WindowManager overlay directly over the user's live screen
 * (never replacing it with a screenshot or list page), allowing Google Lens-style inline tapping
 * of recognized text blocks, multi-selection, adjacent floating Copy action, and quick Close.
 */
class InlineTextOverlayController(
    private val context: Context,
    private val windowManager: WindowManager
) {

    private var overlayView: View? = null
    private var selectionView: InlineTextSelectionView? = null
    private var copyPillView: LinearLayout? = null
    private var controlsPillView: LinearLayout? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private var displayListener: DisplayManager.DisplayListener? = null
    private var screenOffReceiver: BroadcastReceiver? = null
    private var activeDismissCallback: (() -> Unit)? = null

    private var capturedRotation: Int = 0
    private val safeInsets = Rect()

    fun isShowing(): Boolean = overlayView != null

    fun show(
        blocks: List<ScreenOcrHelper.OcrTextBlock>,
        captureWidth: Int,
        captureHeight: Int,
        displayRotation: Int,
        accentColor: Int,
        onCopyText: (copiedText: String, selectedCount: Int) -> Unit,
        onRescanRequested: () -> Unit,
        onDismiss: () -> Unit
    ) {
        dismissInternal()

        capturedRotation = displayRotation
        activeDismissCallback = onDismiss
        safeInsets.set(0, 0, 0, 0)

        val root = LayoutInflater.from(context).inflate(R.layout.overlay_inline_ocr, null)
        val selView = root.findViewById<InlineTextSelectionView>(R.id.inline_ocr_selection_view)
        val controlsPill = root.findViewById<LinearLayout>(R.id.inline_ocr_controls_pill)
        val btnRescan = root.findViewById<ImageButton>(R.id.btn_inline_ocr_rescan)
        val btnClose = root.findViewById<LinearLayout>(R.id.btn_inline_ocr_close)
        val copyPill = root.findViewById<LinearLayout>(R.id.btn_inline_ocr_copy)
        val ivCopyIcon = root.findViewById<ImageView>(R.id.iv_inline_ocr_copy_icon)
        val tvCopyLabel = root.findViewById<TextView>(R.id.tv_inline_ocr_copy_label)

        selectionView = selView
        controlsPillView = controlsPill
        copyPillView = copyPill

        val density = context.resources.displayMetrics.density

        // Style floating pills cleanly while keeping the rest of the overlay 100% transparent.
        controlsPill.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 24f * density
            setColor(Color.argb(215, 15, 23, 42))
            setStroke((1f * density).toInt().coerceAtLeast(1), Color.argb(140, 148, 163, 184))
        }

        val copyTextColor = if (isColorLight(accentColor)) Color.parseColor("#0F172A") else Color.WHITE
        ivCopyIcon.setColorFilter(copyTextColor, PorterDuff.Mode.SRC_IN)
        tvCopyLabel.setTextColor(copyTextColor)
        copyPill.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 24f * density
            setColor(accentColor)
            setStroke((1.2f * density).toInt().coerceAtLeast(1), Color.argb(160, 255, 255, 255))
        }

        // Apply safe system-bar / cutout insets ONLY to floating pills — never pad selectionView,
        // so OCR bounding boxes align pixel-for-pixel with the underlying screen.
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val sysBars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            safeInsets.set(sysBars.left, sysBars.top, sysBars.right, sysBars.bottom)

            (controlsPill.layoutParams as? FrameLayout.LayoutParams)?.let { lp ->
                lp.topMargin = sysBars.top + (10 * density).toInt()
                lp.marginEnd = sysBars.right + (12 * density).toInt()
                controlsPill.layoutParams = lp
            }
            insets
        }

        var latestAnchorRect: RectF? = null

        fun updateCopyPill(selectedIds: Set<Int>, anchorRect: RectF?) {
            latestAnchorRect = anchorRect
            if (selectedIds.isEmpty() || anchorRect == null) {
                copyPill.visibility = View.GONE
                return
            }

            val count = selectedIds.size
            tvCopyLabel.text = if (count == 1) {
                context.getString(R.string.screen_copy_action)
            } else {
                "${context.getString(R.string.screen_copy_action)} ($count)"
            }
            copyPill.visibility = View.VISIBLE
            positionCopyPill(root, controlsPill, copyPill, anchorRect, density)
        }

        selView.setOcrData(
            blocks = blocks,
            captureWidth = captureWidth,
            captureHeight = captureHeight,
            accentColor = accentColor,
            onSelectionChanged = { selectedIds, anchorRect ->
                updateCopyPill(selectedIds, anchorRect)
            },
            onScrollDismissRequested = {
                dismissWithCallback()
                Toast.makeText(
                    context,
                    context.getString(R.string.screen_copy_scroll_dismissed),
                    Toast.LENGTH_SHORT
                ).show()
            }
        )

        selView.addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            val oldW = oldRight - oldLeft
            val oldH = oldBottom - oldTop
            val newW = right - left
            val newH = bottom - top
            if (oldW > 0 && oldH > 0 && (abs(newW - oldW) > 120 || abs(newH - oldH) > 120)) {
                Log.i(TAG, "Overlay dimensions changed (${oldW}x$oldH -> ${newW}x$newH); dismissing stale selection.")
                dismissWithCallback()
                return@addOnLayoutChangeListener
            }
            latestAnchorRect?.let { anchor ->
                if (copyPill.visibility == View.VISIBLE) {
                    positionCopyPill(root, controlsPill, copyPill, anchor, density)
                }
            }
        }

        copyPill.setOnClickListener {
            val selected = selView.getSelectedIds()
            val chosenText = ScreenOcrHelper.formatSelectedText(blocks, selected)
            if (chosenText.isNotEmpty()) {
                val count = selected.size
                dismissInternal()
                onCopyText(chosenText, count)
                onDismiss()
            }
        }

        btnRescan.setOnClickListener {
            dismissInternal()
            onRescanRequested()
        }

        btnClose.setOnClickListener {
            dismissWithCallback()
        }

        if (root is InlineOverlayRootLayout) {
            root.onBackKeyPressed = {
                dismissWithCallback()
            }
        }
        root.isFocusableInTouchMode = true
        root.requestFocus()
        root.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                dismissWithCallback()
                true
            } else {
                false
            }
        }

        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val overlayParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            0,
            0,
            layoutType,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
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

        overlayView = root
        try {
            windowManager.addView(root, overlayParams)
            ViewCompat.requestApplyInsets(root)
            registerRotationAndScreenOffWatchers()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to show inline OCR overlay", e)
            dismissInternal()
            onDismiss()
        }
    }

    private fun positionCopyPill(
        root: View,
        controlsPill: View,
        copyPill: LinearLayout,
        anchorRect: RectF,
        density: Float
    ) {
        val margin = (10 * density).toInt()
        val gap = (8 * density).toInt()

        copyPill.measure(
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val pillW = copyPill.measuredWidth.coerceAtLeast((88 * density).toInt())
        val pillH = copyPill.measuredHeight.coerceAtLeast((48 * density).toInt())

        val rootW = root.width.takeIf { it > 0 } ?: context.resources.displayMetrics.widthPixels
        val rootH = root.height.takeIf { it > 0 } ?: context.resources.displayMetrics.heightPixels

        val minX = safeInsets.left + margin
        val maxX = (rootW - safeInsets.right - pillW - margin).coerceAtLeast(minX)
        val minY = safeInsets.top + margin
        val maxY = (rootH - safeInsets.bottom - pillH - margin).coerceAtLeast(minY)

        val targetX = (anchorRect.centerX() - pillW / 2f).toInt().coerceIn(minX, maxX)

        val aboveY = (anchorRect.top - pillH - gap).toInt()
        val belowY = (anchorRect.bottom + gap).toInt()
        var targetY = when {
            aboveY >= minY -> aboveY
            belowY <= maxY -> belowY
            else -> aboveY.coerceIn(minY, maxY)
        }

        // Avoid overlapping the top-right Close/Rescan pill if the selected block is near top-right.
        if (controlsPill.width > 0 && controlsPill.height > 0) {
            val ctrlRect = Rect(
                controlsPill.left,
                controlsPill.top,
                controlsPill.right,
                controlsPill.bottom
            )
            val pillRect = Rect(targetX, targetY, targetX + pillW, targetY + pillH)
            if (Rect.intersects(ctrlRect, pillRect) && belowY <= maxY) {
                targetY = belowY
            }
        }

        val lp = (copyPill.layoutParams as? FrameLayout.LayoutParams)
            ?: FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.START
            )
        lp.gravity = Gravity.TOP or Gravity.START
        lp.leftMargin = targetX
        lp.topMargin = targetY
        copyPill.layoutParams = lp
    }

    private fun registerRotationAndScreenOffWatchers() {
        val dm = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
        if (dm != null && displayListener == null) {
            val listener = object : DisplayManager.DisplayListener {
                override fun onDisplayAdded(displayId: Int) {}
                override fun onDisplayRemoved(displayId: Int) {}
                override fun onDisplayChanged(displayId: Int) {
                    if (displayId != Display.DEFAULT_DISPLAY || overlayView == null) return
                    val currentRot = dm.getDisplay(Display.DEFAULT_DISPLAY)?.rotation ?: return
                    if (currentRot != capturedRotation) {
                        dismissWithCallback()
                        Toast.makeText(
                            context,
                            context.getString(R.string.screen_copy_rotated),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
            dm.registerDisplayListener(listener, mainHandler)
            displayListener = listener
        }

        if (screenOffReceiver == null) {
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(ctx: Context?, intent: Intent?) {
                    if (intent?.action == Intent.ACTION_SCREEN_OFF && overlayView != null) {
                        dismissWithCallback()
                    }
                }
            }
            try {
                ContextCompat.registerReceiver(
                    context,
                    receiver,
                    IntentFilter(Intent.ACTION_SCREEN_OFF),
                    ContextCompat.RECEIVER_NOT_EXPORTED
                )
                screenOffReceiver = receiver
            } catch (e: Exception) {
                Log.w(TAG, "Failed registering screen-off watcher", e)
            }
        }
    }

    private fun unregisterWatchers() {
        displayListener?.let { listener ->
            runCatching {
                val dm = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
                dm?.unregisterDisplayListener(listener)
            }
            displayListener = null
        }
        screenOffReceiver?.let { receiver ->
            runCatching { context.unregisterReceiver(receiver) }
            screenOffReceiver = null
        }
    }

    private fun dismissWithCallback() {
        val cb = activeDismissCallback
        dismissInternal()
        cb?.invoke()
    }

    fun dismiss() {
        dismissInternal()
    }

    private fun dismissInternal() {
        unregisterWatchers()
        activeDismissCallback = null
        overlayView?.let { view ->
            try {
                windowManager.removeView(view)
            } catch (e: Exception) {
                Log.w(TAG, "Failed removing inline OCR overlay", e)
            }
        }
        overlayView = null
        selectionView = null
        controlsPillView = null
        copyPillView = null
    }

    private fun isColorLight(color: Int): Boolean {
        val r = Color.red(color) / 255.0
        val g = Color.green(color) / 255.0
        val b = Color.blue(color) / 255.0
        val luminance = 0.299 * r + 0.587 * g + 0.114 * b
        return luminance > 0.58
    }

    companion object {
        private const val TAG = "InlineTextOverlay"
    }
}
