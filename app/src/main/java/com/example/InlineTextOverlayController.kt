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
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.Surface
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.widget.TooltipCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * Manages the 100% transparent WindowManager overlay directly over the live screen:
 * - Zero unselected outlines/boxes/borders in both modes.
 * - COPY mode: invisible hit-areas; highlighting words or drawing a closed circle cutout opens the
 *   unified icon-only 10-action horizontally scrollable pill toolbar in the user's saved custom order.
 * - TRANSLATE mode: immediately translates all visible screen text in bulk via on-device/online translation
 *   and draws borderless background-matched translated labels in place.
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
    private var wordCopyButtonView: ImageButton? = null
    private var wordTranslateBubbleView: LinearLayout? = null
    private var wordTranslatedTextView: TextView? = null
    private var cutoutActionBarView: LinearLayout? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private var displayListener: DisplayManager.DisplayListener? = null
    private var screenOffReceiver: BroadcastReceiver? = null
    private var activeDismissCallback: (() -> Unit)? = null

    private var currentMode: OverlayMode = OverlayMode.COPY
    private var currentTargetLanguage: String = "Hindi"
    private var currentOnlineMode: Boolean = false
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
        onSearchClicked: ((query: String, question: String) -> Unit)? = null,
        onSaveFullImageClicked: (() -> Unit)? = null,
        onCutoutSaveClicked: ((cutoutRect: RectF, viewW: Int, viewH: Int) -> Unit)? = null,
        onCutoutVisualSearchClicked: ((cutoutRect: RectF, viewW: Int, viewH: Int) -> Unit)? = null,
        onCutoutCopyTextClicked: ((cutoutRect: RectF, overlappingNodes: List<SmartDotAccessibilityService.ScreenTextNode>, viewW: Int, viewH: Int) -> Unit)? = null,
        toolbarOrder: List<SelectionToolbarHelper.ToolbarAction> = SelectionToolbarHelper.getVisibleToolbarOrder(context),
        onGoogleAiModeTextClicked: ((query: String, question: String) -> Unit)? = null,
        onGeminiTextClicked: ((text: String, question: String) -> Unit)? = null,
        onChatGptTextClicked: ((text: String, question: String) -> Unit)? = null,
        onShareTextClicked: ((text: String) -> Unit)? = null,
        onFullScreenTranslateClicked: (() -> Unit)? = null,
        onCutoutSpeakClicked: ((cutoutRect: RectF, overlappingNodes: List<SmartDotAccessibilityService.ScreenTextNode>, viewW: Int, viewH: Int) -> Unit)? = null,
        onCutoutTranslateRequested: ((cutoutRect: RectF, overlappingNodes: List<SmartDotAccessibilityService.ScreenTextNode>, viewW: Int, viewH: Int, onTranslated: (String?) -> Unit) -> Unit)? = null,
        onCutoutSearchWithFallback: ((cutoutRect: RectF, overlappingNodes: List<SmartDotAccessibilityService.ScreenTextNode>, viewW: Int, viewH: Int, question: String) -> Unit)? = null,
        onCutoutGoogleAiModeClicked: ((cutoutRect: RectF, overlappingNodes: List<SmartDotAccessibilityService.ScreenTextNode>, viewW: Int, viewH: Int, question: String) -> Unit)? = null,
        onCutoutGeminiClicked: ((cutoutRect: RectF, overlappingNodes: List<SmartDotAccessibilityService.ScreenTextNode>, viewW: Int, viewH: Int, question: String) -> Unit)? = null,
        onCutoutChatGptClicked: ((cutoutRect: RectF, overlappingNodes: List<SmartDotAccessibilityService.ScreenTextNode>, viewW: Int, viewH: Int, question: String) -> Unit)? = null,
        onCutoutShareClicked: ((cutoutRect: RectF, overlappingNodes: List<SmartDotAccessibilityService.ScreenTextNode>, viewW: Int, viewH: Int) -> Unit)? = null,
        onWordLensClicked: (() -> Unit)? = null,
        onCutoutLensClicked: ((cutoutRect: RectF, viewW: Int, viewH: Int) -> Unit)? = null,
        onWordBrowserAiClicked: ((query: String, question: String) -> Unit)? = null,
        onCutoutBrowserAiClicked: ((cutoutRect: RectF, overlappingNodes: List<SmartDotAccessibilityService.ScreenTextNode>, viewW: Int, viewH: Int, question: String) -> Unit)? = null
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
            onCutoutCopyTextClicked = onCutoutCopyTextClicked,
            toolbarOrder = toolbarOrder,
            onGoogleAiModeTextClicked = onGoogleAiModeTextClicked,
            onGeminiTextClicked = onGeminiTextClicked,
            onChatGptTextClicked = onChatGptTextClicked,
            onShareTextClicked = onShareTextClicked,
            onFullScreenTranslateClicked = onFullScreenTranslateClicked,
            onCutoutSpeakClicked = onCutoutSpeakClicked,
            onCutoutTranslateRequested = onCutoutTranslateRequested,
            onCutoutSearchWithFallback = onCutoutSearchWithFallback,
            onCutoutGoogleAiModeClicked = onCutoutGoogleAiModeClicked,
            onCutoutGeminiClicked = onCutoutGeminiClicked,
            onCutoutChatGptClicked = onCutoutChatGptClicked,
            onCutoutShareClicked = onCutoutShareClicked,
            onWordLensClicked = onWordLensClicked,
            onCutoutLensClicked = onCutoutLensClicked,
            onWordBrowserAiClicked = onWordBrowserAiClicked,
            onCutoutBrowserAiClicked = onCutoutBrowserAiClicked
        ) ?: return

        val (root, selView, controlsPill, wordActionBar, copyBtn) = refs

        selView.configureCopyMode(
            nodes = nodes,
            accentColor = accentColor,
            onCopySelectionChanged = { combinedText, anchorRect ->
                wordTranslateBubbleView?.visibility = View.GONE
                val clean = combinedText.trim()
                if (clean.isEmpty() || anchorRect == null) {
                    currentAnchorRect = null
                    activeCopyableText = ""
                    copyBtn.visibility = View.GONE
                    wordActionBar.visibility = View.GONE
                    return@configureCopyMode
                }
                currentAnchorRect = RectF(anchorRect)
                activeCopyableText = clean
                copyBtn.visibility = View.VISIBLE
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
                copyBtn.visibility = View.GONE
                cutoutBar.visibility = View.VISIBLE
                positionFloatingBar(root, controlsPill, cutoutBar, cutoutRect, preferBelow = true, density = context.resources.displayMetrics.density)
            }
        )
    }

    fun showTranslateOverlay(
        accentColor: Int,
        initialTargetLanguage: String,
        initialOnlineMode: Boolean = false,
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

        val (root, selView, controlsPill, wordActionBar, copyBtn) = refs

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
                    copyBtn.visibility = View.GONE
                    wordActionBar.visibility = View.GONE
                    return@configureTranslateMode
                }
                currentAnchorRect = RectF(anchorRect)
                activeCopyableText = clean
                copyBtn.visibility = View.VISIBLE
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
        val copyButton: ImageButton
    )

    private fun setupBaseOverlay(
        mode: OverlayMode,
        accentColor: Int,
        initialTargetLanguage: String,
        initialOnlineMode: Boolean = false,
        onTranslationModeToggled: ((Boolean) -> Unit)? = null,
        onTargetLanguageChanged: ((String) -> Unit)?,
        onCopyClicked: (String) -> Unit,
        onRescanRequested: () -> Unit,
        onDismiss: () -> Unit,
        onSpeakClicked: ((String) -> Unit)? = null,
        onTranslateWordRequested: ((String, (String) -> Unit) -> Unit)? = null,
        onSearchClicked: ((String, String) -> Unit)? = null,
        onSaveFullImageClicked: (() -> Unit)? = null,
        onCutoutSaveClicked: ((RectF, Int, Int) -> Unit)? = null,
        onCutoutVisualSearchClicked: ((RectF, Int, Int) -> Unit)? = null,
        onCutoutCopyTextClicked: ((RectF, List<SmartDotAccessibilityService.ScreenTextNode>, Int, Int) -> Unit)? = null,
        toolbarOrder: List<SelectionToolbarHelper.ToolbarAction> = SelectionToolbarHelper.getVisibleToolbarOrder(context),
        onGoogleAiModeTextClicked: ((String, String) -> Unit)? = null,
        onGeminiTextClicked: ((String, String) -> Unit)? = null,
        onChatGptTextClicked: ((String, String) -> Unit)? = null,
        onShareTextClicked: ((String) -> Unit)? = null,
        onFullScreenTranslateClicked: (() -> Unit)? = null,
        onCutoutSpeakClicked: ((RectF, List<SmartDotAccessibilityService.ScreenTextNode>, Int, Int) -> Unit)? = null,
        onCutoutTranslateRequested: ((RectF, List<SmartDotAccessibilityService.ScreenTextNode>, Int, Int, (String?) -> Unit) -> Unit)? = null,
        onCutoutSearchWithFallback: ((RectF, List<SmartDotAccessibilityService.ScreenTextNode>, Int, Int, String) -> Unit)? = null,
        onCutoutGoogleAiModeClicked: ((RectF, List<SmartDotAccessibilityService.ScreenTextNode>, Int, Int, String) -> Unit)? = null,
        onCutoutGeminiClicked: ((RectF, List<SmartDotAccessibilityService.ScreenTextNode>, Int, Int, String) -> Unit)? = null,
        onCutoutChatGptClicked: ((RectF, List<SmartDotAccessibilityService.ScreenTextNode>, Int, Int, String) -> Unit)? = null,
        onCutoutShareClicked: ((RectF, List<SmartDotAccessibilityService.ScreenTextNode>, Int, Int) -> Unit)? = null,
        onWordLensClicked: (() -> Unit)? = null,
        onCutoutLensClicked: ((RectF, Int, Int) -> Unit)? = null,
        onWordBrowserAiClicked: ((String, String) -> Unit)? = null,
        onCutoutBrowserAiClicked: ((RectF, List<SmartDotAccessibilityService.ScreenTextNode>, Int, Int, String) -> Unit)? = null
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
        val wordRow1 = root.findViewById<LinearLayout>(R.id.inline_word_row1)
        val wordRow2 = root.findViewById<LinearLayout>(R.id.inline_word_row2)
        val wordQuestionRow = root.findViewById<LinearLayout>(R.id.inline_word_question_row)
        val etWordQuestion = root.findViewById<EditText>(R.id.et_word_ai_question)
        val btnWordPreset1 = root.findViewById<ImageButton>(R.id.btn_word_preset_1)
        val btnWordPreset2 = root.findViewById<ImageButton>(R.id.btn_word_preset_2)
        val btnWordSend = root.findViewById<ImageButton>(R.id.btn_word_ai_send)
        val wordBrowserAiRow = root.findViewById<LinearLayout>(R.id.inline_word_browser_ai_row)
        val etWordBrowserAiQuestion = root.findViewById<EditText>(R.id.et_word_browser_ai_question)
        val btnWordBrowserPreset1 = root.findViewById<ImageButton>(R.id.btn_word_browser_preset_1)
        val btnWordBrowserPreset2 = root.findViewById<ImageButton>(R.id.btn_word_browser_preset_2)
        val btnWordBrowserAiSend = root.findViewById<ImageButton>(R.id.btn_word_browser_ai_send)
        val wordTranslateBubble = root.findViewById<LinearLayout>(R.id.inline_word_translate_bubble)
        val tvWordTranslated = root.findViewById<TextView>(R.id.tv_inline_word_translated_text)
        val btnCopyTranslated = root.findViewById<TextView>(R.id.btn_inline_word_translate_copy)

        val cutoutBar = root.findViewById<LinearLayout>(R.id.inline_cutout_action_bar)
        val cutoutRow1 = root.findViewById<LinearLayout>(R.id.inline_cutout_row1)
        val cutoutRow2 = root.findViewById<LinearLayout>(R.id.inline_cutout_row2)
        val cutoutQuestionRow = root.findViewById<LinearLayout>(R.id.inline_cutout_question_row)
        val etCutoutQuestion = root.findViewById<EditText>(R.id.et_cutout_ai_question)
        val btnCutoutPreset1 = root.findViewById<ImageButton>(R.id.btn_cutout_preset_1)
        val btnCutoutPreset2 = root.findViewById<ImageButton>(R.id.btn_cutout_preset_2)
        val btnCutoutSend = root.findViewById<ImageButton>(R.id.btn_cutout_ai_send)
        val btnCutoutClose = root.findViewById<ImageButton>(R.id.btn_cutout_close)
        val cutoutBrowserAiRow = root.findViewById<LinearLayout>(R.id.inline_cutout_browser_ai_row)
        val etCutoutBrowserAiQuestion = root.findViewById<EditText>(R.id.et_cutout_browser_ai_question)
        val btnCutoutBrowserPreset1 = root.findViewById<ImageButton>(R.id.btn_cutout_browser_preset_1)
        val btnCutoutBrowserPreset2 = root.findViewById<ImageButton>(R.id.btn_cutout_browser_preset_2)
        val btnCutoutBrowserAiSend = root.findViewById<ImageButton>(R.id.btn_cutout_browser_ai_send)

        selectionView = selView
        controlsPillView = controlsPill
        translateModeChipView = btnModeToggle
        targetLangChipView = btnLangChip
        langDropdownView = langDropdown
        statusPillView = statusPill
        wordActionBarView = wordActionBar
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

        wordActionBar.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 18f * density
            setColor(Color.argb(240, 15, 23, 42))
            setStroke((1.2f * density).toInt(), Color.argb(110, 148, 163, 184))
        }
        wordTranslateBubble.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 16f * density
            setColor(Color.argb(242, 15, 23, 42))
            setStroke((1.5f * density).toInt(), accentColor)
        }
        cutoutBar.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 18f * density
            setColor(Color.argb(242, 15, 23, 42))
            setStroke((1.5f * density).toInt(), accentColor)
        }

        val questionInputBg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 10f * density
            setColor(Color.argb(235, 30, 41, 59))
            setStroke((1f * density).toInt(), Color.argb(120, 100, 116, 139))
        }
        wordQuestionRow.background = questionInputBg
        cutoutQuestionRow.background = questionInputBg.constantState?.newDrawable()?.mutate() ?: questionInputBg
        wordBrowserAiRow.background = questionInputBg.constantState?.newDrawable()?.mutate() ?: questionInputBg
        cutoutBrowserAiRow.background = questionInputBg.constantState?.newDrawable()?.mutate() ?: questionInputBg
        btnWordSend.setColorFilter(accentColor, PorterDuff.Mode.SRC_IN)
        btnCutoutSend.setColorFilter(accentColor, PorterDuff.Mode.SRC_IN)
        btnWordBrowserAiSend.setColorFilter(accentColor, PorterDuff.Mode.SRC_IN)
        btnCutoutBrowserAiSend.setColorFilter(accentColor, PorterDuff.Mode.SRC_IN)
        btnCutoutClose.setColorFilter(Color.parseColor("#CBD5E1"), PorterDuff.Mode.SRC_IN)

        fun stylePresetButtons(btn1: ImageButton, btn2: ImageButton, enabled: Boolean) {
            btn1.isEnabled = enabled
            btn2.isEnabled = enabled
            val targetAlpha = if (enabled) 1f else 0.35f
            btn1.alpha = targetAlpha
            btn2.alpha = targetAlpha
            val tint = if (enabled) accentColor else Color.parseColor("#94A3B8")
            btn1.setColorFilter(tint, PorterDuff.Mode.SRC_IN)
            btn2.setColorFilter(if (enabled) Color.parseColor("#34D399") else Color.parseColor("#94A3B8"), PorterDuff.Mode.SRC_IN)
        }
        stylePresetButtons(btnWordPreset1, btnWordPreset2, enabled = true)
        stylePresetButtons(btnWordBrowserPreset1, btnWordBrowserPreset2, enabled = true)
        stylePresetButtons(btnCutoutPreset1, btnCutoutPreset2, enabled = true)
        stylePresetButtons(btnCutoutBrowserPreset1, btnCutoutBrowserPreset2, enabled = true)

        etWordQuestion.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                stylePresetButtons(btnWordPreset1, btnWordPreset2, enabled = s.isNullOrBlank())
            }
        })
        etWordBrowserAiQuestion.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                stylePresetButtons(btnWordBrowserPreset1, btnWordBrowserPreset2, enabled = s.isNullOrBlank())
            }
        })
        etCutoutQuestion.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                stylePresetButtons(btnCutoutPreset1, btnCutoutPreset2, enabled = s.isNullOrBlank())
            }
        })
        etCutoutBrowserAiQuestion.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                stylePresetButtons(btnCutoutBrowserPreset1, btnCutoutBrowserPreset2, enabled = s.isNullOrBlank())
            }
        })

        val box1Preset1Icon = SelectionToolbarHelper.getBox1Preset1Icon(context)
        val box1Preset2Icon = SelectionToolbarHelper.getBox1Preset2Icon(context)
        val box2Preset1Icon = SelectionToolbarHelper.getBox2Preset1Icon(context)
        val box2Preset2Icon = SelectionToolbarHelper.getBox2Preset2Icon(context)
        btnWordPreset1.setImageResource(box1Preset1Icon.iconRes)
        btnWordPreset2.setImageResource(box1Preset2Icon.iconRes)
        btnCutoutPreset1.setImageResource(box1Preset1Icon.iconRes)
        btnCutoutPreset2.setImageResource(box1Preset2Icon.iconRes)
        btnWordBrowserPreset1.setImageResource(box2Preset1Icon.iconRes)
        btnWordBrowserPreset2.setImageResource(box2Preset2Icon.iconRes)
        btnCutoutBrowserPreset1.setImageResource(box2Preset1Icon.iconRes)
        btnCutoutBrowserPreset2.setImageResource(box2Preset2Icon.iconRes)

        val showBox1 = SelectionToolbarHelper.isShowQuestionBox1Enabled(context)
        val showBox2 = SelectionToolbarHelper.isShowQuestionBox2Enabled(context)
        val normalizedOrder = normalizeToolbarOrder(toolbarOrder)

        // Populate Text Selection Icon-Only Grid (only visible icons, up to 5 per row)
        var wordCopyBtnRef: ImageButton? = null
        wordRow1.removeAllViews()
        wordRow2.removeAllViews()
        wordQuestionRow.visibility = if (mode == OverlayMode.COPY && showBox1) View.VISIBLE else View.GONE
        wordBrowserAiRow.visibility = if (mode == OverlayMode.COPY && showBox2) View.VISIBLE else View.GONE
        cutoutQuestionRow.visibility = if (showBox1) View.VISIBLE else View.GONE
        cutoutBrowserAiRow.visibility = if (showBox2) View.VISIBLE else View.GONE

        val wordActionsToRender = if (mode == OverlayMode.COPY) {
            normalizedOrder
        } else {
            listOf(SelectionToolbarHelper.ToolbarAction.COPY)
        }

        wordActionsToRender.forEachIndexed { index, action ->
            val btn = createToolbarIconButton(
                action = action,
                viewId = wordActionButtonId(action),
                accentColor = accentColor,
                density = density
            )
            if (action == SelectionToolbarHelper.ToolbarAction.COPY) {
                btn.visibility = View.GONE
                wordCopyBtnRef = btn
            } else {
                btn.visibility = if (mode == OverlayMode.COPY) View.VISIBLE else View.GONE
            }
            btn.setOnClickListener {
                val question = etWordQuestion.text?.toString()?.trim().orEmpty()
                handleWordToolbarAction(
                    action = action,
                    question = question,
                    root = root,
                    controlsPill = controlsPill,
                    wordTranslateBubble = wordTranslateBubble,
                    tvWordTranslated = tvWordTranslated,
                    density = density,
                    onCopyClicked = onCopyClicked,
                    onSpeakClicked = onSpeakClicked,
                    onTranslateWordRequested = onTranslateWordRequested,
                    onSearchClicked = onSearchClicked,
                    onSaveFullImageClicked = onSaveFullImageClicked,
                    onGoogleAiModeTextClicked = onGoogleAiModeTextClicked,
                    onGeminiTextClicked = onGeminiTextClicked,
                    onChatGptTextClicked = onChatGptTextClicked,
                    onShareTextClicked = onShareTextClicked,
                    onFullScreenTranslateClicked = onFullScreenTranslateClicked,
                    onWordLensClicked = onWordLensClicked
                )
            }
            if (index < 5) wordRow1.addView(btn) else wordRow2.addView(btn)
        }
        wordRow1.visibility = if (wordRow1.childCount > 0) View.VISIBLE else View.GONE
        wordRow2.visibility = if (mode == OverlayMode.COPY && wordRow2.childCount > 0) View.VISIBLE else View.GONE

        val copyBtn = wordCopyBtnRef ?: createToolbarIconButton(
            action = SelectionToolbarHelper.ToolbarAction.COPY,
            viewId = R.id.btn_inline_ocr_copy,
            accentColor = accentColor,
            density = density
        )
        wordCopyButtonView = copyBtn

        fun submitWordQuestion() {
            val question = etWordQuestion.text?.toString()?.trim().orEmpty()
            val selected = activeCopyableText.trim()
            if (question.isEmpty()) {
                Toast.makeText(context, "Type a question to ask AI", Toast.LENGTH_SHORT).show()
                etWordQuestion.requestFocus()
                return
            }
            val dismissCb = activeDismissCallback
            dismissInternal()
            onGoogleAiModeTextClicked?.invoke(selected, question)
            dismissCb?.invoke()
        }
        fun fireWordPreset(presetText: String) {
            val selected = activeCopyableText.trim()
            if (selected.isNotEmpty() || presetText.isNotBlank()) {
                val combined = SelectionToolbarHelper.buildPresetQuery(presetText, selected)
                val dismissCb = activeDismissCallback
                dismissInternal()
                onSearchClicked?.invoke(combined, "")
                dismissCb?.invoke()
            }
        }
        btnWordPreset1.setOnClickListener {
            if (!btnWordPreset1.isEnabled || !etWordQuestion.text.isNullOrBlank()) return@setOnClickListener
            fireWordPreset(SelectionToolbarHelper.getQuickPrompt1(context))
        }
        btnWordPreset2.setOnClickListener {
            if (!btnWordPreset2.isEnabled || !etWordQuestion.text.isNullOrBlank()) return@setOnClickListener
            fireWordPreset(SelectionToolbarHelper.getQuickPrompt2(context))
        }
        btnWordSend.setOnClickListener { submitWordQuestion() }
        etWordQuestion.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND || actionId == EditorInfo.IME_ACTION_DONE) {
                submitWordQuestion()
                true
            } else {
                false
            }
        }

        fun submitWordBrowserAi() {
            val question = etWordBrowserAiQuestion.text?.toString()?.trim().orEmpty()
            val selected = activeCopyableText.trim()
            if (question.isEmpty() && selected.isEmpty()) {
                Toast.makeText(context, "Type a question to ask AI", Toast.LENGTH_SHORT).show()
                etWordBrowserAiQuestion.requestFocus()
                return
            }
            val dismissCb = activeDismissCallback
            dismissInternal()
            val handler = onWordBrowserAiClicked ?: onGoogleAiModeTextClicked
            handler?.invoke(selected, question)
            dismissCb?.invoke()
        }
        fun fireWordBrowserPreset(presetText: String) {
            val selected = activeCopyableText.trim()
            if (selected.isNotEmpty() || presetText.isNotBlank()) {
                val dismissCb = activeDismissCallback
                dismissInternal()
                val handler = onWordBrowserAiClicked ?: onGoogleAiModeTextClicked
                handler?.invoke(selected, presetText.trim())
                dismissCb?.invoke()
            }
        }
        btnWordBrowserPreset1.setOnClickListener {
            if (!btnWordBrowserPreset1.isEnabled || !etWordBrowserAiQuestion.text.isNullOrBlank()) return@setOnClickListener
            fireWordBrowserPreset(SelectionToolbarHelper.getBox2Preset1Text(context))
        }
        btnWordBrowserPreset2.setOnClickListener {
            if (!btnWordBrowserPreset2.isEnabled || !etWordBrowserAiQuestion.text.isNullOrBlank()) return@setOnClickListener
            fireWordBrowserPreset(SelectionToolbarHelper.getBox2Preset2Text(context))
        }
        btnWordBrowserAiSend.setOnClickListener { submitWordBrowserAi() }
        etWordBrowserAiQuestion.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND || actionId == EditorInfo.IME_ACTION_DONE) {
                submitWordBrowserAi()
                true
            } else {
                false
            }
        }

        // Populate Circle-to-Search Cutout Icon-Only Grid (only visible icons, up to 5 per row)
        cutoutRow1.removeAllViews()
        cutoutRow2.removeAllViews()
        normalizedOrder.forEachIndexed { index, action ->
            val btn = createToolbarIconButton(
                action = action,
                viewId = cutoutActionButtonId(action),
                accentColor = accentColor,
                density = density
            )
            btn.setOnClickListener {
                val box = currentCutoutRect ?: selView.getActiveCutoutRect() ?: return@setOnClickListener
                val overlapping = selView.getWordsInsideCutout()
                val question = etCutoutQuestion.text?.toString()?.trim().orEmpty()
                handleCutoutToolbarAction(
                    action = action,
                    question = question,
                    box = box,
                    overlapping = overlapping,
                    viewW = selView.width,
                    viewH = selView.height,
                    root = root,
                    controlsPill = controlsPill,
                    wordTranslateBubble = wordTranslateBubble,
                    tvWordTranslated = tvWordTranslated,
                    density = density,
                    onCutoutCopyTextClicked = onCutoutCopyTextClicked,
                    onCutoutSpeakClicked = onCutoutSpeakClicked,
                    onCutoutTranslateRequested = onCutoutTranslateRequested,
                    onCutoutSearchWithFallback = onCutoutSearchWithFallback,
                    onCutoutVisualSearchClicked = onCutoutVisualSearchClicked,
                    onCutoutSaveClicked = onCutoutSaveClicked,
                    onCutoutGoogleAiModeClicked = onCutoutGoogleAiModeClicked,
                    onCutoutGeminiClicked = onCutoutGeminiClicked,
                    onCutoutChatGptClicked = onCutoutChatGptClicked,
                    onCutoutShareClicked = onCutoutShareClicked,
                    onFullScreenTranslateClicked = onFullScreenTranslateClicked,
                    onCutoutLensClicked = onCutoutLensClicked,
                    onDismiss = onDismiss
                )
            }
            if (index < 5) cutoutRow1.addView(btn) else cutoutRow2.addView(btn)
        }
        cutoutRow1.visibility = if (cutoutRow1.childCount > 0) View.VISIBLE else View.GONE
        cutoutRow2.visibility = if (cutoutRow2.childCount > 0) View.VISIBLE else View.GONE

        fun submitCutoutQuestion() {
            val question = etCutoutQuestion.text?.toString()?.trim().orEmpty()
            val box = currentCutoutRect ?: selView.getActiveCutoutRect() ?: return
            val overlapping = selView.getWordsInsideCutout()
            if (question.isEmpty()) {
                Toast.makeText(context, "Type a question to ask AI", Toast.LENGTH_SHORT).show()
                etCutoutQuestion.requestFocus()
                return
            }
            val w = selView.width
            val h = selView.height
            dismissInternal()
            onCutoutGoogleAiModeClicked?.invoke(box, overlapping, w, h, question)
            onDismiss()
        }
        fun fireCutoutPreset(presetText: String) {
            val box = currentCutoutRect ?: selView.getActiveCutoutRect() ?: return
            val overlapping = selView.getWordsInsideCutout()
            val w = selView.width
            val h = selView.height
            val overlapText = overlapping.joinToString(" ") { it.text }.trim()
            val finalPrompt = if (overlapText.isNotEmpty()) {
                SelectionToolbarHelper.buildPresetQuery(presetText, overlapText)
            } else {
                presetText.trim()
            }
            dismissInternal()
            onCutoutSearchWithFallback?.invoke(box, overlapping, w, h, finalPrompt)
            onDismiss()
        }
        btnCutoutPreset1.setOnClickListener {
            if (!btnCutoutPreset1.isEnabled || !etCutoutQuestion.text.isNullOrBlank()) return@setOnClickListener
            fireCutoutPreset(SelectionToolbarHelper.getQuickPrompt1(context))
        }
        btnCutoutPreset2.setOnClickListener {
            if (!btnCutoutPreset2.isEnabled || !etCutoutQuestion.text.isNullOrBlank()) return@setOnClickListener
            fireCutoutPreset(SelectionToolbarHelper.getQuickPrompt2(context))
        }
        btnCutoutSend.setOnClickListener { submitCutoutQuestion() }
        etCutoutQuestion.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND || actionId == EditorInfo.IME_ACTION_DONE) {
                submitCutoutQuestion()
                true
            } else {
                false
            }
        }

        fun submitCutoutBrowserAi() {
            val question = etCutoutBrowserAiQuestion.text?.toString()?.trim().orEmpty()
            val box = currentCutoutRect ?: selView.getActiveCutoutRect() ?: return
            val overlapping = selView.getWordsInsideCutout()
            val w = selView.width
            val h = selView.height
            dismissInternal()
            val handler = onCutoutBrowserAiClicked ?: onCutoutGoogleAiModeClicked
            handler?.invoke(box, overlapping, w, h, question)
            onDismiss()
        }
        fun fireCutoutBrowserPreset(presetText: String) {
            val box = currentCutoutRect ?: selView.getActiveCutoutRect() ?: return
            val overlapping = selView.getWordsInsideCutout()
            val w = selView.width
            val h = selView.height
            dismissInternal()
            val handler = onCutoutBrowserAiClicked ?: onCutoutGoogleAiModeClicked
            handler?.invoke(box, overlapping, w, h, presetText.trim())
            onDismiss()
        }
        btnCutoutBrowserPreset1.setOnClickListener {
            if (!btnCutoutBrowserPreset1.isEnabled || !etCutoutBrowserAiQuestion.text.isNullOrBlank()) return@setOnClickListener
            fireCutoutBrowserPreset(SelectionToolbarHelper.getBox2Preset1Text(context))
        }
        btnCutoutBrowserPreset2.setOnClickListener {
            if (!btnCutoutBrowserPreset2.isEnabled || !etCutoutBrowserAiQuestion.text.isNullOrBlank()) return@setOnClickListener
            fireCutoutBrowserPreset(SelectionToolbarHelper.getBox2Preset2Text(context))
        }
        btnCutoutBrowserAiSend.setOnClickListener { submitCutoutBrowserAi() }
        etCutoutBrowserAiQuestion.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND || actionId == EditorInfo.IME_ACTION_DONE) {
                submitCutoutBrowserAi()
                true
            } else {
                false
            }
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

        btnCopyTranslated.setOnClickListener {
            val txt = tvWordTranslated.text?.toString()?.trim().orEmpty()
            if (txt.isNotEmpty()) {
                dismissInternal()
                onCopyClicked(txt)
                onDismiss()
            }
        }

        btnCutoutClose.setOnClickListener {
            currentCutoutRect = null
            wordTranslateBubble.visibility = View.GONE
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
            root.onOutsidePopupTouch = { ev ->
                val tx = ev.x.toInt()
                val ty = ev.y.toInt()
                fun isInside(v: View): Boolean {
                    if (v.visibility != View.VISIBLE) return false
                    return tx in v.left..v.right && ty in v.top..v.bottom
                }
                if (langDropdown.visibility == View.VISIBLE &&
                    !isInside(langDropdown) &&
                    !isInside(btnLangChip)
                ) {
                    langDropdown.visibility = View.GONE
                }
                if (wordTranslateBubble.visibility == View.VISIBLE &&
                    !isInside(wordTranslateBubble)
                ) {
                    wordTranslateBubble.visibility = View.GONE
                }
            }
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
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN
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
            BaseOverlayRefs(root, selView, controlsPill, wordActionBar, copyBtn)
        } catch (e: Exception) {
            Log.e(TAG, "Failed showing transparent overlay", e)
            dismissInternal()
            onDismiss()
            null
        }
    }

    private fun handleWordToolbarAction(
        action: SelectionToolbarHelper.ToolbarAction,
        question: String,
        root: View,
        controlsPill: View,
        wordTranslateBubble: LinearLayout,
        tvWordTranslated: TextView,
        density: Float,
        onCopyClicked: (String) -> Unit,
        onSpeakClicked: ((String) -> Unit)?,
        onTranslateWordRequested: ((String, (String) -> Unit) -> Unit)?,
        onSearchClicked: ((String, String) -> Unit)?,
        onSaveFullImageClicked: (() -> Unit)?,
        onGoogleAiModeTextClicked: ((String, String) -> Unit)?,
        onGeminiTextClicked: ((String, String) -> Unit)?,
        onChatGptTextClicked: ((String, String) -> Unit)?,
        onShareTextClicked: ((String) -> Unit)?,
        onFullScreenTranslateClicked: (() -> Unit)?,
        onWordLensClicked: (() -> Unit)?
    ) {
        val chosen = activeCopyableText.trim()
        when (action) {
            SelectionToolbarHelper.ToolbarAction.COPY -> {
                if (chosen.isNotEmpty()) {
                    val dismissCb = activeDismissCallback
                    dismissInternal()
                    onCopyClicked(chosen)
                    dismissCb?.invoke()
                }
            }
            SelectionToolbarHelper.ToolbarAction.SPEAK -> {
                if (chosen.isNotEmpty()) {
                    onSpeakClicked?.invoke(chosen)
                }
            }
            SelectionToolbarHelper.ToolbarAction.TRANSLATE -> {
                if (chosen.isEmpty()) return
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
            SelectionToolbarHelper.ToolbarAction.SEARCH -> {
                if (chosen.isNotEmpty() || question.isNotEmpty()) {
                    val dismissCb = activeDismissCallback
                    dismissInternal()
                    onSearchClicked?.invoke(chosen, question)
                    dismissCb?.invoke()
                }
            }
            SelectionToolbarHelper.ToolbarAction.SAVE_IMAGE -> {
                onSaveFullImageClicked?.invoke()
            }
            SelectionToolbarHelper.ToolbarAction.GOOGLE_AI_MODE -> {
                if (chosen.isNotEmpty() || question.isNotEmpty()) {
                    val dismissCb = activeDismissCallback
                    dismissInternal()
                    onGoogleAiModeTextClicked?.invoke(chosen, question)
                    dismissCb?.invoke()
                }
            }
            SelectionToolbarHelper.ToolbarAction.GEMINI -> {
                if (chosen.isNotEmpty() || question.isNotEmpty()) {
                    val dismissCb = activeDismissCallback
                    dismissInternal()
                    onGeminiTextClicked?.invoke(chosen, question)
                    dismissCb?.invoke()
                }
            }
            SelectionToolbarHelper.ToolbarAction.CHATGPT -> {
                if (chosen.isNotEmpty() || question.isNotEmpty()) {
                    val dismissCb = activeDismissCallback
                    dismissInternal()
                    onChatGptTextClicked?.invoke(chosen, question)
                    dismissCb?.invoke()
                }
            }
            SelectionToolbarHelper.ToolbarAction.SHARE -> {
                if (chosen.isNotEmpty()) {
                    val dismissCb = activeDismissCallback
                    dismissInternal()
                    onShareTextClicked?.invoke(chosen)
                    dismissCb?.invoke()
                }
            }
            SelectionToolbarHelper.ToolbarAction.FULL_SCREEN_TRANSLATE -> {
                dismissInternal()
                onFullScreenTranslateClicked?.invoke()
            }
            SelectionToolbarHelper.ToolbarAction.GOOGLE_LENS -> {
                val dismissCb = activeDismissCallback
                dismissInternal()
                if (onWordLensClicked != null) {
                    onWordLensClicked.invoke()
                } else {
                    dismissCb?.invoke()
                    mainHandler.postDelayed({
                        if (!SelectionToolbarHelper.launchNativeGoogleLens(context)) {
                            Toast.makeText(context, "Google Lens unavailable.", Toast.LENGTH_SHORT).show()
                        }
                    }, 180L)
                }
            }
        }
    }

    private fun handleCutoutToolbarAction(
        action: SelectionToolbarHelper.ToolbarAction,
        question: String,
        box: RectF,
        overlapping: List<SmartDotAccessibilityService.ScreenTextNode>,
        viewW: Int,
        viewH: Int,
        root: View,
        controlsPill: View,
        wordTranslateBubble: LinearLayout,
        tvWordTranslated: TextView,
        density: Float,
        onCutoutCopyTextClicked: ((RectF, List<SmartDotAccessibilityService.ScreenTextNode>, Int, Int) -> Unit)?,
        onCutoutSpeakClicked: ((RectF, List<SmartDotAccessibilityService.ScreenTextNode>, Int, Int) -> Unit)?,
        onCutoutTranslateRequested: ((RectF, List<SmartDotAccessibilityService.ScreenTextNode>, Int, Int, (String?) -> Unit) -> Unit)?,
        onCutoutSearchWithFallback: ((RectF, List<SmartDotAccessibilityService.ScreenTextNode>, Int, Int, String) -> Unit)?,
        onCutoutVisualSearchClicked: ((RectF, Int, Int) -> Unit)?,
        onCutoutSaveClicked: ((RectF, Int, Int) -> Unit)?,
        onCutoutGoogleAiModeClicked: ((RectF, List<SmartDotAccessibilityService.ScreenTextNode>, Int, Int, String) -> Unit)?,
        onCutoutGeminiClicked: ((RectF, List<SmartDotAccessibilityService.ScreenTextNode>, Int, Int, String) -> Unit)?,
        onCutoutChatGptClicked: ((RectF, List<SmartDotAccessibilityService.ScreenTextNode>, Int, Int, String) -> Unit)?,
        onCutoutShareClicked: ((RectF, List<SmartDotAccessibilityService.ScreenTextNode>, Int, Int) -> Unit)?,
        onFullScreenTranslateClicked: (() -> Unit)?,
        onCutoutLensClicked: ((RectF, Int, Int) -> Unit)?,
        onDismiss: () -> Unit
    ) {
        when (action) {
            SelectionToolbarHelper.ToolbarAction.COPY -> {
                onCutoutCopyTextClicked?.invoke(box, overlapping, viewW, viewH)
            }
            SelectionToolbarHelper.ToolbarAction.SPEAK -> {
                onCutoutSpeakClicked?.invoke(box, overlapping, viewW, viewH)
            }
            SelectionToolbarHelper.ToolbarAction.TRANSLATE -> {
                tvWordTranslated.text = context.getString(R.string.screen_translate_translating)
                wordTranslateBubble.visibility = View.VISIBLE
                positionFloatingBar(root, controlsPill, wordTranslateBubble, box, preferBelow = false, density = density)
                onCutoutTranslateRequested?.invoke(box, overlapping, viewW, viewH) { translated ->
                    if (overlayView != null) {
                        if (translated.isNullOrBlank()) {
                            wordTranslateBubble.visibility = View.GONE
                        } else {
                            tvWordTranslated.text = translated
                            wordTranslateBubble.visibility = View.VISIBLE
                            positionFloatingBar(root, controlsPill, wordTranslateBubble, box, preferBelow = false, density = density)
                        }
                    }
                }
            }
            SelectionToolbarHelper.ToolbarAction.SEARCH -> {
                dismissInternal()
                if (onCutoutSearchWithFallback != null) {
                    onCutoutSearchWithFallback.invoke(box, overlapping, viewW, viewH, question)
                } else {
                    onCutoutVisualSearchClicked?.invoke(box, viewW, viewH)
                }
                onDismiss()
            }
            SelectionToolbarHelper.ToolbarAction.SAVE_IMAGE -> {
                onCutoutSaveClicked?.invoke(box, viewW, viewH)
            }
            SelectionToolbarHelper.ToolbarAction.GOOGLE_AI_MODE -> {
                dismissInternal()
                onCutoutGoogleAiModeClicked?.invoke(box, overlapping, viewW, viewH, question)
                onDismiss()
            }
            SelectionToolbarHelper.ToolbarAction.GEMINI -> {
                dismissInternal()
                onCutoutGeminiClicked?.invoke(box, overlapping, viewW, viewH, question)
                onDismiss()
            }
            SelectionToolbarHelper.ToolbarAction.CHATGPT -> {
                dismissInternal()
                onCutoutChatGptClicked?.invoke(box, overlapping, viewW, viewH, question)
                onDismiss()
            }
            SelectionToolbarHelper.ToolbarAction.SHARE -> {
                dismissInternal()
                onCutoutShareClicked?.invoke(box, overlapping, viewW, viewH)
                onDismiss()
            }
            SelectionToolbarHelper.ToolbarAction.FULL_SCREEN_TRANSLATE -> {
                dismissInternal()
                onFullScreenTranslateClicked?.invoke()
            }
            SelectionToolbarHelper.ToolbarAction.GOOGLE_LENS -> {
                dismissInternal()
                if (onCutoutLensClicked != null) {
                    onCutoutLensClicked.invoke(box, viewW, viewH)
                } else {
                    onDismiss()
                }
            }
        }
    }

    private fun createToolbarIconButton(
        action: SelectionToolbarHelper.ToolbarAction,
        viewId: Int,
        accentColor: Int,
        density: Float
    ): ImageButton {
        val btnSizePx = (42 * density).toInt()
        val btnHeightPx = (40 * density).toInt()
        val padPx = (10 * density).toInt()
        val marginPx = (2 * density).toInt()

        return ImageButton(context).apply {
            id = viewId
            tag = "toolbar_action_${action.id}"
            setImageResource(action.iconRes)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            contentDescription = action.contentDesc
            TooltipCompat.setTooltipText(this, action.label)
            isClickable = true
            isFocusable = true
            setPadding(padPx, (8 * density).toInt(), padPx, (8 * density).toInt())

            layoutParams = LinearLayout.LayoutParams(btnSizePx, btnHeightPx).apply {
                setMargins(marginPx, 0, marginPx, 0)
                gravity = Gravity.CENTER_VERTICAL
            }

            if (action == SelectionToolbarHelper.ToolbarAction.COPY) {
                val iconTint = if (isColorLight(accentColor)) Color.parseColor("#0F172A") else Color.WHITE
                setColorFilter(iconTint, PorterDuff.Mode.SRC_IN)
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = 20f * density
                    setColor(accentColor)
                }
            } else {
                val iconTint = when (action) {
                    SelectionToolbarHelper.ToolbarAction.SAVE_IMAGE -> Color.parseColor("#34D399")
                    SelectionToolbarHelper.ToolbarAction.GOOGLE_AI_MODE -> accentColor
                    SelectionToolbarHelper.ToolbarAction.GEMINI -> Color.parseColor("#60A5FA")
                    SelectionToolbarHelper.ToolbarAction.CHATGPT -> Color.parseColor("#10B981")
                    SelectionToolbarHelper.ToolbarAction.FULL_SCREEN_TRANSLATE -> Color.parseColor("#FBBF24")
                    else -> Color.parseColor("#F8FAFC")
                }
                setColorFilter(iconTint, PorterDuff.Mode.SRC_IN)
                setBackgroundColor(Color.TRANSPARENT)
            }
        }
    }

    private fun normalizeToolbarOrder(order: List<SelectionToolbarHelper.ToolbarAction>): List<SelectionToolbarHelper.ToolbarAction> {
        return order.distinct()
    }

    private fun wordActionButtonId(action: SelectionToolbarHelper.ToolbarAction): Int {
        return when (action) {
            SelectionToolbarHelper.ToolbarAction.COPY -> R.id.btn_inline_ocr_copy
            SelectionToolbarHelper.ToolbarAction.SPEAK -> R.id.btn_inline_word_speak
            SelectionToolbarHelper.ToolbarAction.TRANSLATE -> R.id.btn_inline_word_translate
            SelectionToolbarHelper.ToolbarAction.SEARCH -> R.id.btn_inline_word_search
            SelectionToolbarHelper.ToolbarAction.SAVE_IMAGE -> R.id.btn_inline_word_save_full
            SelectionToolbarHelper.ToolbarAction.GOOGLE_AI_MODE -> R.id.btn_inline_word_ai_mode
            SelectionToolbarHelper.ToolbarAction.GEMINI -> R.id.btn_inline_word_gemini
            SelectionToolbarHelper.ToolbarAction.CHATGPT -> R.id.btn_inline_word_chatgpt
            SelectionToolbarHelper.ToolbarAction.SHARE -> R.id.btn_inline_word_share
            SelectionToolbarHelper.ToolbarAction.FULL_SCREEN_TRANSLATE -> R.id.btn_inline_word_full_translate
            SelectionToolbarHelper.ToolbarAction.GOOGLE_LENS -> R.id.btn_inline_word_lens
        }
    }

    private fun cutoutActionButtonId(action: SelectionToolbarHelper.ToolbarAction): Int {
        return when (action) {
            SelectionToolbarHelper.ToolbarAction.COPY -> R.id.btn_cutout_copy_text
            SelectionToolbarHelper.ToolbarAction.SPEAK -> R.id.btn_cutout_speak
            SelectionToolbarHelper.ToolbarAction.TRANSLATE -> R.id.btn_cutout_translate
            SelectionToolbarHelper.ToolbarAction.SEARCH -> R.id.btn_cutout_visual_search
            SelectionToolbarHelper.ToolbarAction.SAVE_IMAGE -> R.id.btn_cutout_save
            SelectionToolbarHelper.ToolbarAction.GOOGLE_AI_MODE -> R.id.btn_cutout_ai_mode
            SelectionToolbarHelper.ToolbarAction.GEMINI -> R.id.btn_cutout_gemini
            SelectionToolbarHelper.ToolbarAction.CHATGPT -> R.id.btn_cutout_chatgpt
            SelectionToolbarHelper.ToolbarAction.SHARE -> R.id.btn_cutout_share
            SelectionToolbarHelper.ToolbarAction.FULL_SCREEN_TRANSLATE -> R.id.btn_cutout_full_translate
            SelectionToolbarHelper.ToolbarAction.GOOGLE_LENS -> R.id.btn_cutout_lens
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

        val maxBarW = (rootW - safeInsets.left - safeInsets.right - margin * 2).coerceAtLeast(100)

        floatingBar.measure(
            View.MeasureSpec.makeMeasureSpec(maxBarW, View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val pillW = floatingBar.measuredWidth.coerceAtLeast((56 * density).toInt())
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
            runCatching {
                val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                imm?.hideSoftInputFromWindow(view.windowToken, 0)
            }
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
        wordCopyButtonView = null
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
