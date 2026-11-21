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
    private var translateModeChipView: TextView? = null
    private var langDropdownView: LinearLayout? = null
    private var statusPillView: TextView? = null
    private var wordActionBarView: LinearLayout? = null
    private var copyPillView: LinearLayout? = null
    private var wordTranslateBubbleView: LinearLayout? = null
    private var wordTranslatedTextView: TextView? = null
    private var cutoutActionBarView: LinearLayout? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private var displayListener: DisplayManager.DisplayListener? = null
    private var screenOffReceiver: BroadcastReceiver? = null
    private var activeDismissCallback: (() -> Unit)? = null

    private var currentMode: OverlayMode = OverlayMode.COPY
    private var currentTargetLanguage: String = "Hindi"
    private var currentOnlineMode: Boolean = true
    private var currentAnchorRect: RectF? = null
    private var currentCutoutRect: RectF? = null
    private var activeCopyableText: String = ""
    private var capturedRotation: Int = Surface.ROTATION_0
    private val safeInsets = Rect()

    fun isShowing(): Boolean = overlayView != null

    fun showCopyOverlay(
        nodes: List<SmartDotAccessibilityService.ScreenTextNode>,
        accentColor: Int,
        onCopyClicked: (textToCopy: String) -> Unit,
        onRescanRequested: () -> Unit,
        onDismiss: () -> Unit,
        onSpeakClicked: ((textToSpeak: String) -> Unit)? = null,
        onTranslateWordRequested: ((text: String, onTranslated: (String) -> Unit) -> Unit)? = null,
        onSearchClicked: ((query: String) -> Unit)? = null,
        onSaveFullImageClicked: (() -> Unit)? = null,
        onCutoutSaveClicked: ((cutoutRect: RectF, viewW: Int, viewH: Int) -> Unit)? = null,
        onCutoutVisualSearchClicked: ((cutoutRect: RectF, viewW: Int, viewH: Int) -> Unit)? = null,
        onCutoutCopyTextClicked: ((cutoutRect: RectF, overlappingNodes: List<SmartDotAccessibilityService.ScreenTextNode>, viewW: Int, viewH: Int) -> Unit)? = null
    ) {
        val refs = setupBaseOverlay(
            mode = OverlayMode.COPY,
            accentColor = accentColor,
            initialTargetLanguage = "Hindi",
            onTargetLanguageChanged = null,
            onCopyClicked = onCopyClicked,
            onRescanRequested = onRescanRequested,
            onDismiss = onDismiss,
            onSpeakClicked = onSpeakClicked,
            onTranslateWordRequested = onTranslateWordRequested,
            onSearchClicked = onSearchClicked,
            onSaveFullImageClicked = onSaveFullImageClicked,
            onCutoutSaveClicked = onCutoutSaveClicked,
            onCutoutVisualSearchClicked = onCutoutVisualSearchClicked,
            onCutoutCopyTextClicked = onCutoutCopyTextClicked
        ) ?: return

        val (root, selView, controlsPill, wordActionBar, copyPill, tvCopyLabel) = refs

        selView.configureCopyMode(
            nodes = nodes,
            accentColor = accentColor,
            onCopySelectionChanged = { combinedText, anchorRect ->
                wordTranslateBubbleView?.visibility = View.GONE
                val clean = combinedText.trim()
                if (clean.isEmpty() || anchorRect == null) {
                    currentAnchorRect = null
                    activeCopyableText = ""
                    copyPill.visibility = View.GONE
                    wordActionBar.visibility = View.GONE
                    return@configureCopyMode
                }
                currentAnchorRect = RectF(anchorRect)
                activeCopyableText = clean
                tvCopyLabel.text = context.getString(R.string.screen_copy_action)
                copyPill.visibility = View.VISIBLE
                wordActionBar.visibility = View.VISIBLE
                positionFloatingBar(root, controlsPill, wordActionBar, anchorRect, preferBelow = false, density = context.resources.displayMetrics.density)
            },
            onScrollDismissRequested = {
                dismissWithCallback()
                Toast.makeText(
                    context,
                    context.getString(R.string.screen_copy_scroll_dismissed),
                    Toast.LENGTH_SHORT
                ).show()
            },
            onCutoutChanged = { cutoutRect ->
                val cutoutBar = cutoutActionBarView ?: return@configureCopyMode
                wordTranslateBubbleView?.visibility = View.GONE
                if (cutoutRect == null) {
                    currentCutoutRect = null
                    cutoutBar.visibility = View.GONE
                    return@configureCopyMode
                }
                currentCutoutRect = RectF(cutoutRect)
                wordActionBar.visibility = View.GONE
                copyPill.visibility = View.GONE
                cutoutBar.visibility = View.VISIBLE
                positionFloatingBar(root, controlsPill, cutoutBar, cutoutRect, preferBelow = true, density = context.resources.displayMetrics.density)
            }
        )
    }

    fun showTranslateOverlay(
        accentColor: Int,
        initialTargetLanguage: String,
        initialOnlineMode: Boolean = true,
        onTranslationModeToggled: ((useOnlineMode: Boolean) -> Unit)? = null,
        onTargetLanguageChanged: (newTargetLang: String) -> Unit,
        onCopyClicked: (translatedTextToCopy: String) -> Unit,
        onRescanRequested: () -> Unit,
        onDismiss: () -> Unit
    ) {
        val refs = setupBaseOverlay(
            mode = OverlayMode.TRANSLATE,
            accentColor = accentColor,
            initialTargetLanguage = initialTargetLanguage,
            initialOnlineMode = initialOnlineMode,
            onTranslationModeToggled = onTranslationModeToggled,
            onTargetLanguageChanged = onTargetLanguageChanged,
            onCopyClicked = onCopyClicked,
            onRescanRequested = onRescanRequested,
            onDismiss = onDismiss
        ) ?: return

        val (root, selView, controlsPill, wordActionBar, copyPill, tvCopyLabel) = refs

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
                    wordActionBar.visibility = View.GONE
                    return@configureTranslateMode
                }
                currentAnchorRect = RectF(anchorRect)
                activeCopyableText = clean
                tvCopyLabel.text = context.getString(R.string.screen_copy_action)
                copyPill.visibility = View.VISIBLE
                wordActionBar.visibility = View.VISIBLE
                positionFloatingBar(root, controlsPill, wordActionBar, anchorRect, preferBelow = false, density = context.resources.displayMetrics.density)
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
        val wordActionBar: LinearLayout,
        val copyPill: LinearLayout,
        val copyLabel: TextView
    )

    private fun setupBaseOverlay(
        mode: OverlayMode,
        accentColor: Int,
        initialTargetLanguage: String,
        initialOnlineMode: Boolean = true,
        onTranslationModeToggled: ((Boolean) -> Unit)? = null,
        onTargetLanguageChanged: ((String) -> Unit)?,
        onCopyClicked: (String) -> Unit,
        onRescanRequested: () -> Unit,
        onDismiss: () -> Unit,
        onSpeakClicked: ((String) -> Unit)? = null,
        onTranslateWordRequested: ((String, (String) -> Unit) -> Unit)? = null,
        onSearchClicked: ((String) -> Unit)? = null,
        onSaveFullImageClicked: (() -> Unit)? = null,
        onCutoutSaveClicked: ((RectF, Int, Int) -> Unit)? = null,
        onCutoutVisualSearchClicked: ((RectF, Int, Int) -> Unit)? = null,
        onCutoutCopyTextClicked: ((RectF, List<SmartDotAccessibilityService.ScreenTextNode>, Int, Int) -> Unit)? = null
    ): BaseOverlayRefs? {
        dismissInternal()

        currentMode = mode
        currentTargetLanguage = initialTargetLanguage
        currentOnlineMode = initialOnlineMode
        currentAnchorRect = null
        currentCutoutRect = null
        activeCopyableText = ""
        activeDismissCallback = onDismiss
        safeInsets.set(0, 0, 0, 0)
        capturedRotation = readCurrentDisplayRotation()

        val root = LayoutInflater.from(context).inflate(R.layout.overlay_inline_ocr, null)
        val selView = root.findViewById<InlineTextSelectionView>(R.id.inline_ocr_selection_view)
        val controlsPill = root.findViewById<LinearLayout>(R.id.inline_ocr_controls_pill)
        val btnModeToggle = root.findViewById<TextView>(R.id.btn_inline_translate_mode)
        val btnLangChip = root.findViewById<TextView>(R.id.btn_inline_target_lang)
        val langDropdown = root.findViewById<LinearLayout>(R.id.inline_lang_dropdown)
        val statusPill = root.findViewById<TextView>(R.id.tv_inline_status_pill)
        val btnRescan = root.findViewById<ImageButton>(R.id.btn_inline_ocr_rescan)
        val btnClose = root.findViewById<LinearLayout>(R.id.btn_inline_ocr_close)
        val wordActionBar = root.findViewById<LinearLayout>(R.id.inline_word_action_bar)
        val copyPill = root.findViewById<LinearLayout>(R.id.btn_inline_ocr_copy)
        val ivCopyIcon = root.findViewById<ImageView>(R.id.iv_inline_ocr_copy_icon)
        val tvCopyLabel = root.findViewById<TextView>(R.id.tv_inline_ocr_copy_label)
        val btnSpeak = root.findViewById<TextView>(R.id.btn_inline_word_speak)
        val btnWordTranslate = root.findViewById<TextView>(R.id.btn_inline_word_translate)
        val btnWordSearch = root.findViewById<TextView>(R.id.btn_inline_word_search)
        val btnSaveFull = root.findViewById<TextView>(R.id.btn_inline_word_save_full)
        val wordTranslateBubble = root.findViewById<LinearLayout>(R.id.inline_word_translate_bubble)
        val tvWordTranslated = root.findViewById<TextView>(R.id.tv_inline_word_translated_text)
        val btnCopyTranslated = root.findViewById<TextView>(R.id.btn_inline_word_translate_copy)
        val cutoutBar = root.findViewById<LinearLayout>(R.id.inline_cutout_action_bar)
        val btnCutoutSave = root.findViewById<TextView>(R.id.btn_cutout_save)
        val btnCutoutVisualSearch = root.findViewById<TextView>(R.id.btn_cutout_visual_search)
        val btnCutoutCopyText = root.findViewById<TextView>(R.id.btn_cutout_copy_text)
        val btnCutoutClose = root.findViewById<ImageButton>(R.id.btn_cutout_close)

        selectionView = selView
        controlsPillView = controlsPill
        translateModeChipView = btnModeToggle
        targetLangChipView = btnLangChip
        langDropdownView = langDropdown
        statusPillView = statusPill
        wordActionBarView = wordActionBar
        copyPillView = copyPill
        wordTranslateBubbleView = wordTranslateBubble
        wordTranslatedTextView = tvWordTranslated
        cutoutActionBarView = cutoutBar

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

        fun applyModeChipStyle(online: Boolean) {
            if (online) {
                btnModeToggle.text = context.getString(R.string.screen_translate_mode_online)
                btnModeToggle.setTextColor(Color.parseColor("#34D399"))
            } else {
                btnModeToggle.text = context.getString(R.string.screen_translate_mode_offline)
                btnModeToggle.setTextColor(Color.parseColor("#94A3B8"))
            }
        }

        if (mode == OverlayMode.TRANSLATE) {
            btnModeToggle.visibility = View.VISIBLE
            applyModeChipStyle(currentOnlineMode)
            btnModeToggle.setOnClickListener {
                langDropdown.visibility = View.GONE
                currentOnlineMode = !currentOnlineMode
                applyModeChipStyle(currentOnlineMode)
                selView.clearSelection()
                statusPill.text = context.getString(R.string.screen_translate_translating)
                statusPill.visibility = View.VISIBLE
                onTranslationModeToggled?.invoke(currentOnlineMode)
            }

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
            btnModeToggle.visibility = View.GONE
            btnLangChip.visibility = View.GONE
            langDropdown.visibility = View.GONE
            statusPill.visibility = View.GONE
        }

        val copyTextColor = if (isColorLight(accentColor)) Color.parseColor("#0F172A") else Color.WHITE
        ivCopyIcon.setColorFilter(copyTextColor, PorterDuff.Mode.SRC_IN)
        tvCopyLabel.setTextColor(copyTextColor)
        copyPill.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 20f * density
            setColor(accentColor)
        }
        wordActionBar.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 24f * density
            setColor(Color.argb(236, 15, 23, 42))
            setStroke((1 * density).toInt(), Color.argb(90, 148, 163, 184))
        }
        wordTranslateBubble.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 16f * density
            setColor(Color.argb(242, 15, 23, 42))
            setStroke((1.5f * density).toInt(), accentColor)
        }
        cutoutBar.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 24f * density
            setColor(Color.argb(238, 15, 23, 42))
            setStroke((1.5f * density).toInt(), accentColor)
        }

        // In Translate mode keep Copy focused; in Copy mode show the full 5-action pill
        val wordExtrasVis = if (mode == OverlayMode.COPY) View.VISIBLE else View.GONE
        btnSpeak.visibility = wordExtrasVis
        btnWordTranslate.visibility = wordExtrasVis
        btnWordSearch.visibility = wordExtrasVis
        btnSaveFull.visibility = wordExtrasVis

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
                if (wordActionBar.visibility == View.VISIBLE) {
                    positionFloatingBar(root, controlsPill, wordActionBar, anchor, preferBelow = false, density = density)
                }
            }
            currentCutoutRect?.let { box ->
                if (cutoutBar.visibility == View.VISIBLE) {
                    positionFloatingBar(root, controlsPill, cutoutBar, box, preferBelow = true, density = density)
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

        btnSpeak.setOnClickListener {
            val chosen = activeCopyableText.trim()
            if (chosen.isNotEmpty()) {
                onSpeakClicked?.invoke(chosen)
            }
        }

        btnWordTranslate.setOnClickListener {
            val chosen = activeCopyableText.trim()
            if (chosen.isEmpty()) return@setOnClickListener
            tvWordTranslated.text = context.getString(R.string.screen_translate_translating)
            wordTranslateBubble.visibility = View.VISIBLE
            currentAnchorRect?.let { anchor ->
                positionFloatingBar(root, controlsPill, wordTranslateBubble, anchor, preferBelow = true, density = density)
            }
            onTranslateWordRequested?.invoke(chosen) { translated ->
                if (overlayView != null) {
                    tvWordTranslated.text = translated
                    wordTranslateBubble.visibility = View.VISIBLE
                    currentAnchorRect?.let { anchor ->
                        positionFloatingBar(root, controlsPill, wordTranslateBubble, anchor, preferBelow = true, density = density)
                    }
                }
            }
        }

        btnCopyTranslated.setOnClickListener {
            val txt = tvWordTranslated.text?.toString()?.trim().orEmpty()
            if (txt.isNotEmpty()) {
                dismissInternal()
                onCopyClicked(txt)
                onDismiss()
            }
        }

        btnWordSearch.setOnClickListener {
            val chosen = activeCopyableText.trim()
            if (chosen.isNotEmpty()) {
                dismissInternal()
                onSearchClicked?.invoke(chosen)
                onDismiss()
            }
        }

        btnSaveFull.setOnClickListener {
            onSaveFullImageClicked?.invoke()
        }

        btnCutoutSave.setOnClickListener {
            val box = currentCutoutRect ?: selView.getActiveCutoutRect() ?: return@setOnClickListener
            onCutoutSaveClicked?.invoke(box, selView.width, selView.height)
        }

        btnCutoutVisualSearch.setOnClickListener {
            val box = currentCutoutRect ?: selView.getActiveCutoutRect() ?: return@setOnClickListener
            dismissInternal()
            onCutoutVisualSearchClicked?.invoke(box, selView.width, selView.height)
            onDismiss()
        }

        btnCutoutCopyText.setOnClickListener {
            val box = currentCutoutRect ?: selView.getActiveCutoutRect() ?: return@setOnClickListener
            val overlapping = selView.getWordsInsideCutout()
            onCutoutCopyTextClicked?.invoke(box, overlapping, selView.width, selView.height)
        }

        btnCutoutClose.setOnClickListener {
            currentCutoutRect = null
            cutoutBar.visibility = View.GONE
            selView.clearCutout()
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
                when {
                    langDropdown.visibility == View.VISIBLE -> langDropdown.visibility = View.GONE
                    wordTranslateBubble.visibility == View.VISIBLE -> wordTranslateBubble.visibility = View.GONE
                    selView.getActiveCutoutRect() != null -> selView.clearCutout()
                    else -> dismissWithCallback()
                }
            }
        }
        root.isFocusableInTouchMode = true
        root.requestFocus()
        root.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                when {
                    langDropdown.visibility == View.VISIBLE -> langDropdown.visibility = View.GONE
                    wordTranslateBubble.visibility == View.VISIBLE -> wordTranslateBubble.visibility = View.GONE
                    selView.getActiveCutoutRect() != null -> selView.clearCutout()
                    else -> dismissWithCallback()
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
            BaseOverlayRefs(root, selView, controlsPill, wordActionBar, copyPill, tvCopyLabel)
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

    private fun positionFloatingBar(
        root: View,
        controlsPill: View,
        floatingBar: View,
        anchorRect: RectF,
        preferBelow: Boolean,
        density: Float
    ) {
        val margin = (10 * density).toInt()
        val gap = (8 * density).toInt()

        val rootW = root.width.takeIf { it > 0 } ?: context.resources.displayMetrics.widthPixels
        val rootH = root.height.takeIf { it > 0 } ?: context.resources.displayMetrics.heightPixels

        floatingBar.measure(
            View.MeasureSpec.makeMeasureSpec((rootW - margin * 2).coerceAtLeast(100), View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val pillW = floatingBar.measuredWidth.coerceAtLeast((88 * density).toInt())
        val pillH = floatingBar.measuredHeight.coerceAtLeast((48 * density).toInt())

        val minX = safeInsets.left + margin
        val maxX = (rootW - safeInsets.right - pillW - margin).coerceAtLeast(minX)
        val minY = safeInsets.top + margin
        val maxY = (rootH - safeInsets.bottom - pillH - margin).coerceAtLeast(minY)

        val targetX = (anchorRect.centerX() - pillW / 2f).toInt().coerceIn(minX, maxX)

        val aboveY = (anchorRect.top - pillH - gap).toInt()
        val belowY = (anchorRect.bottom + gap).toInt()
        var targetY = if (preferBelow) {
            when {
                belowY <= maxY -> belowY
                aboveY >= minY -> aboveY
                else -> belowY.coerceIn(minY, maxY)
            }
        } else {
            when {
                aboveY >= minY -> aboveY
                belowY <= maxY -> belowY
                else -> aboveY.coerceIn(minY, maxY)
            }
        }

        if (controlsPill.width > 0 && controlsPill.height > 0) {
            val ctrlRect = Rect(controlsPill.left, controlsPill.top, controlsPill.right, controlsPill.bottom)
            val pillRect = Rect(targetX, targetY, targetX + pillW, targetY + pillH)
            if (Rect.intersects(ctrlRect, pillRect) && belowY <= maxY) {
                targetY = belowY
            }
        }

        val lp = (floatingBar.layoutParams as? FrameLayout.LayoutParams)
            ?: FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.START
            )
        lp.gravity = Gravity.TOP or Gravity.START
        lp.leftMargin = targetX
        lp.topMargin = targetY
        floatingBar.layoutParams = lp
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
        translateModeChipView = null
        targetLangChipView = null
        langDropdownView = null
        statusPillView = null
        wordActionBarView = null
        copyPillView = null
        wordTranslateBubbleView = null
        wordTranslatedTextView = null
        cutoutActionBarView = null
        currentAnchorRect = null
        currentCutoutRect = null
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
