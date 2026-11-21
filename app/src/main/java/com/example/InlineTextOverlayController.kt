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
import android.graphics.Typeface
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
import android.view.Surface
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

/**
 * Manages the 100% transparent WindowManager overlay directly over the live screen:
 * - Zero unselected outlines/boxes/borders in both modes.
 * - COPY mode: invisible hit-areas; tapping text highlights only that item and shows a floating "Copy" pill.
 * - TRANSLATE mode: immediately translates all visible screen text in bulk via offline ML Kit and draws
 *   borderless background-matched translated labels in place (plus language switcher and tap-to-copy).
 */
class InlineTextOverlayController(
    private val context: Context,
    private val windowManager: WindowManager
) {

    enum class OverlayMode {
        COPY,
        TRANSLATE
    }

    private var overlayView: View? = null
    private var selectionView: InlineTextSelectionView? = null
    private var controlsPillView: LinearLayout? = null
    private var targetLangChipView: TextView? = null
    private var langDropdownView: LinearLayout? = null
    private var statusPillView: TextView? = null
    private var copyPillView: LinearLayout? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private var displayListener: DisplayManager.DisplayListener? = null
    private var screenOffReceiver: BroadcastReceiver? = null
    private var activeDismissCallback: (() -> Unit)? = null

    private var currentMode: OverlayMode = OverlayMode.COPY
    private var currentTargetLanguage: String = "Hindi"
    private var currentAnchorRect: RectF? = null
    private var activeCopyableText: String = ""
    private var capturedRotation: Int = Surface.ROTATION_0
    private val safeInsets = Rect()

    fun isShowing(): Boolean = overlayView != null

    fun showCopyOverlay(
        nodes: List<SmartDotAccessibilityService.ScreenTextNode>,
        accentColor: Int,
        onCopyClicked: (textToCopy: String) -> Unit,
        onRescanRequested: () -> Unit,
        onDismiss: () -> Unit
    ) {
        val refs = setupBaseOverlay(
            mode = OverlayMode.COPY,
            accentColor = accentColor,
            initialTargetLanguage = "Hindi",
            onTargetLanguageChanged = null,
            onCopyClicked = onCopyClicked,
            onRescanRequested = onRescanRequested,
            onDismiss = onDismiss
        ) ?: return

        val (root, selView, controlsPill, copyPill, tvCopyLabel) = refs

        selView.configureCopyMode(
            nodes = nodes,
            accentColor = accentColor,
            onCopySelectionChanged = { combinedText, anchorRect ->
                val clean = combinedText.trim()
                if (clean.isEmpty() || anchorRect == null) {
                    currentAnchorRect = null
                    activeCopyableText = ""
                    copyPill.visibility = View.GONE
                    return@configureCopyMode
                }
                currentAnchorRect = RectF(anchorRect)
                activeCopyableText = clean
                tvCopyLabel.text = context.getString(R.string.screen_copy_action)
                copyPill.visibility = View.VISIBLE
                positionCopyPill(root, controlsPill, copyPill, anchorRect, context.resources.displayMetrics.density)
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
    }

    fun showTranslateOverlay(
        accentColor: Int,
        initialTargetLanguage: String,
        onTargetLanguageChanged: (newTargetLang: String) -> Unit,
        onCopyClicked: (translatedTextToCopy: String) -> Unit,
        onRescanRequested: () -> Unit,
        onDismiss: () -> Unit
    ) {
        val refs = setupBaseOverlay(
            mode = OverlayMode.TRANSLATE,
            accentColor = accentColor,
            initialTargetLanguage = initialTargetLanguage,
            onTargetLanguageChanged = onTargetLanguageChanged,
            onCopyClicked = onCopyClicked,
            onRescanRequested = onRescanRequested,
            onDismiss = onDismiss
        ) ?: return

        val (root, selView, controlsPill, copyPill, tvCopyLabel) = refs

        statusPillView?.apply {
            text = context.getString(R.string.screen_translate_translating)
            visibility = View.VISIBLE
        }

        selView.configureTranslateMode(
            accentColor = accentColor,
            onCopySelectionChanged = { combinedText, anchorRect ->
                langDropdownView?.visibility = View.GONE
                val clean = combinedText.trim()
                if (clean.isEmpty() || anchorRect == null) {
                    currentAnchorRect = null
                    activeCopyableText = ""
                    copyPill.visibility = View.GONE
                    return@configureTranslateMode
                }
                currentAnchorRect = RectF(anchorRect)
                activeCopyableText = clean
                tvCopyLabel.text = context.getString(R.string.screen_copy_action)
                copyPill.visibility = View.VISIBLE
                positionCopyPill(root, controlsPill, copyPill, anchorRect, context.resources.displayMetrics.density)
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
    }

    fun showBulkTranslations(items: List<ScreenTranslateHelper.TranslatedNode>) {
        if (overlayView == null || currentMode != OverlayMode.TRANSLATE) return
        statusPillView?.visibility = View.GONE
        selectionView?.setBulkTranslations(items)
    }

    fun showTranslatingIndicator(message: String) {
        if (overlayView == null || currentMode != OverlayMode.TRANSLATE) return
        statusPillView?.apply {
            text = message
            visibility = View.VISIBLE
        }
    }

    private data class BaseOverlayRefs(
        val root: View,
        val selectionView: InlineTextSelectionView,
        val controlsPill: LinearLayout,
        val copyPill: LinearLayout,
        val copyLabel: TextView
    )

    private fun setupBaseOverlay(
        mode: OverlayMode,
        accentColor: Int,
        initialTargetLanguage: String,
        onTargetLanguageChanged: ((String) -> Unit)?,
        onCopyClicked: (String) -> Unit,
        onRescanRequested: () -> Unit,
        onDismiss: () -> Unit
    ): BaseOverlayRefs? {
        dismissInternal()

        currentMode = mode
        currentTargetLanguage = initialTargetLanguage
        currentAnchorRect = null
        activeCopyableText = ""
        activeDismissCallback = onDismiss
        safeInsets.set(0, 0, 0, 0)
        capturedRotation = readCurrentDisplayRotation()

        val root = LayoutInflater.from(context).inflate(R.layout.overlay_inline_ocr, null)
        val selView = root.findViewById<InlineTextSelectionView>(R.id.inline_ocr_selection_view)
        val controlsPill = root.findViewById<LinearLayout>(R.id.inline_ocr_controls_pill)
        val btnLangChip = root.findViewById<TextView>(R.id.btn_inline_target_lang)
        val langDropdown = root.findViewById<LinearLayout>(R.id.inline_lang_dropdown)
        val statusPill = root.findViewById<TextView>(R.id.tv_inline_status_pill)
        val btnRescan = root.findViewById<ImageButton>(R.id.btn_inline_ocr_rescan)
        val btnClose = root.findViewById<LinearLayout>(R.id.btn_inline_ocr_close)
        val copyPill = root.findViewById<LinearLayout>(R.id.btn_inline_ocr_copy)
        val ivCopyIcon = root.findViewById<ImageView>(R.id.iv_inline_ocr_copy_icon)
        val tvCopyLabel = root.findViewById<TextView>(R.id.tv_inline_ocr_copy_label)

        selectionView = selView
        controlsPillView = controlsPill
        targetLangChipView = btnLangChip
        langDropdownView = langDropdown
        statusPillView = statusPill
        copyPillView = copyPill

        val density = context.resources.displayMetrics.density

        controlsPill.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 24f * density
            setColor(Color.argb(220, 15, 23, 42))
        }

        statusPill.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 18f * density
            setColor(Color.argb(220, 15, 23, 42))
        }

        if (mode == OverlayMode.TRANSLATE) {
            btnLangChip.visibility = View.VISIBLE
            btnLangChip.text = "$currentTargetLanguage ▾"
            btnLangChip.setTextColor(accentColor)

            langDropdown.background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 14f * density
                setColor(Color.argb(238, 15, 23, 42))
            }
            populateLanguageDropdown(langDropdown, btnLangChip, accentColor, density) { selectedLang ->
                currentTargetLanguage = selectedLang
                btnLangChip.text = "$selectedLang ▾"
                langDropdown.visibility = View.GONE
                selView.clearSelection()
                statusPill.text = context.getString(R.string.screen_translate_translating)
                statusPill.visibility = View.VISIBLE
                onTargetLanguageChanged?.invoke(selectedLang)
            }

            btnLangChip.setOnClickListener {
                langDropdown.visibility = if (langDropdown.visibility == View.VISIBLE) {
                    View.GONE
                } else {
                    View.VISIBLE
                }
            }
        } else {
            btnLangChip.visibility = View.GONE
            langDropdown.visibility = View.GONE
            statusPill.visibility = View.GONE
        }

        val copyTextColor = if (isColorLight(accentColor)) Color.parseColor("#0F172A") else Color.WHITE
        ivCopyIcon.setColorFilter(copyTextColor, PorterDuff.Mode.SRC_IN)
        tvCopyLabel.setTextColor(copyTextColor)
        copyPill.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 24f * density
            setColor(accentColor)
        }

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
            (langDropdown.layoutParams as? FrameLayout.LayoutParams)?.let { lp ->
                lp.topMargin = sysBars.top + (62 * density).toInt()
                lp.marginEnd = sysBars.right + (12 * density).toInt()
                langDropdown.layoutParams = lp
            }
            (statusPill.layoutParams as? FrameLayout.LayoutParams)?.let { lp ->
                lp.topMargin = sysBars.top + (64 * density).toInt()
                statusPill.layoutParams = lp
            }
            insets
        }

        selView.addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            val oldW = oldRight - oldLeft
            val oldH = oldBottom - oldTop
            val newW = right - left
            val newH = bottom - top
            if (oldW > 0 && oldH > 0 && newW > 0 && newH > 0 && (oldW > oldH) != (newW > newH)) {
                dismissWithCallback()
                return@addOnLayoutChangeListener
            }
            currentAnchorRect?.let { anchor ->
                if (copyPill.visibility == View.VISIBLE) {
                    positionCopyPill(root, controlsPill, copyPill, anchor, density)
                }
            }
        }

        copyPill.setOnClickListener {
            val chosen = activeCopyableText.trim()
            if (chosen.isNotEmpty()) {
                dismissInternal()
                onCopyClicked(chosen)
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
                if (langDropdown.visibility == View.VISIBLE) {
                    langDropdown.visibility = View.GONE
                } else {
                    dismissWithCallback()
                }
            }
        }
        root.isFocusableInTouchMode = true
        root.requestFocus()
        root.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                if (langDropdown.visibility == View.VISIBLE) {
                    langDropdown.visibility = View.GONE
                } else {
                    dismissWithCallback()
                }
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
        return try {
            windowManager.addView(root, overlayParams)
            ViewCompat.requestApplyInsets(root)
            registerRotationAndScreenOffWatchers()
            BaseOverlayRefs(root, selView, controlsPill, copyPill, tvCopyLabel)
        } catch (e: Exception) {
            Log.e(TAG, "Failed showing transparent overlay", e)
            dismissInternal()
            onDismiss()
            null
        }
    }

    private fun populateLanguageDropdown(
        dropdown: LinearLayout,
        langChip: TextView,
        accentColor: Int,
        density: Float,
        onLanguageSelected: (String) -> Unit
    ) {
        dropdown.removeAllViews()
        for (lang in ScreenTranslateHelper.SUPPORTED_LANGUAGES) {
            val row = TextView(context).apply {
                text = lang
                tag = "lang_option_$lang"
                textSize = 13f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(if (lang == currentTargetLanguage) accentColor else Color.parseColor("#F8FAFC"))
                gravity = Gravity.CENTER_VERTICAL
                minHeight = (40 * density).toInt()
                setPadding(
                    (12 * density).toInt(),
                    (8 * density).toInt(),
                    (12 * density).toInt(),
                    (8 * density).toInt()
                )
                setOnClickListener {
                    for (i in 0 until dropdown.childCount) {
                        (dropdown.getChildAt(i) as? TextView)?.let { tv ->
                            tv.setTextColor(if (tv.text == lang) accentColor else Color.parseColor("#F8FAFC"))
                        }
                    }
                    langChip.text = "$lang ▾"
                    onLanguageSelected(lang)
                }
            }
            dropdown.addView(row)
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

        val rootW = root.width.takeIf { it > 0 } ?: context.resources.displayMetrics.widthPixels
        val rootH = root.height.takeIf { it > 0 } ?: context.resources.displayMetrics.heightPixels

        copyPill.measure(
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val pillW = copyPill.measuredWidth.coerceAtLeast((88 * density).toInt())
        val pillH = copyPill.measuredHeight.coerceAtLeast((48 * density).toInt())

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

        if (controlsPill.width > 0 && controlsPill.height > 0) {
            val ctrlRect = Rect(controlsPill.left, controlsPill.top, controlsPill.right, controlsPill.bottom)
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

    private fun readCurrentDisplayRotation(): Int {
        val dm = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
        return dm?.getDisplay(Display.DEFAULT_DISPLAY)?.rotation ?: Surface.ROTATION_0
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
                Log.w(TAG, "Failed removing transparent overlay", e)
            }
        }
        overlayView = null
        selectionView = null
        controlsPillView = null
        targetLangChipView = null
        langDropdownView = null
        statusPillView = null
        copyPillView = null
        currentAnchorRect = null
        activeCopyableText = ""
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
