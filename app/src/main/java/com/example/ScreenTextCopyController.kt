package com.example

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RectF
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.util.Log
import android.view.WindowManager
import android.widget.Toast
import com.example.database.ClipEntry
import com.example.database.SmartDotDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * In-memory Screen Text Copy & Circle-to-Search controller powered by:
 * 1) API 30+ AccessibilityService.takeScreenshot() in memory (retained only while overlay is open)
 * 2) Free on-device ML Kit Text Recognition (Latin + Devanagari) for exact `Text.Element` word boxes
 * 3) Unified 10-icon customizable Selection & Cutout Toolbar (Copy, Speak, Translate, Google App Search,
 *    Save Image, Google AI Mode, Gemini, ChatGPT, Share, Full Screen Translate)
 * 4) Graceful fallback to AccessibilityNodeInfo text scanning if in-memory capture is unavailable.
 */
class ScreenTextCopyController(
    private val context: Context,
    private val serviceScope: CoroutineScope,
    private val clipboardManager: ClipboardManager,
    private val getAccentColor: () -> Int,
    private val dismissActivePopup: () -> Unit,
    private val setHostOverlaysVisible: (Boolean) -> Unit,
    private val onAccessibilityRequired: () -> Unit,
    private val nodeExtractor: () -> List<SmartDotAccessibilityService.ScreenTextNode>? = DEFAULT_EXTRACTOR,
    private val ocrHelperFactory: () -> ScreenOcrHelper = { ScreenOcrHelper() },
    private val screenBitmapCapturer: ((Bitmap?) -> Unit) -> Unit = DEFAULT_BITMAP_CAPTURER,
    private val translateHelperFactory: () -> ScreenTranslateHelper = { ScreenTranslateHelper() },
    private val customSpeakHandler: ((String) -> Unit)? = null,
    private val customGallerySaver: ((Bitmap, String) -> Uri?)? = null,
    private val customVisualSearchLauncher: ((Bitmap) -> Boolean)? = null,
    private val onFullScreenTranslateRequested: (() -> Unit)? = null,
    private val getToolbarOrder: () -> List<SelectionToolbarHelper.ToolbarAction> = {
        SelectionToolbarHelper.getVisibleToolbarOrder(context)
    },
    private val customShareTextHandler: ((String) -> Unit)? = null,
    private val customShareImageHandler: ((Bitmap) -> Unit)? = null,
    private val customFloatingAiBrowserLauncher: ((providerId: String, textOrQuery: String, question: String, cutoutBitmap: Bitmap?) -> Boolean)? = null
) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var overlayController: InlineTextOverlayController? = null
    private var ocrHelper: ScreenOcrHelper? = null
    private var translateHelper: ScreenTranslateHelper? = null
    private var ttsEngine: TextToSpeech? = null
    private var ttsReady = false
    private var pendingSpeakText: String? = null

    private var retainedScreenBitmap: Bitmap? = null
    private var isActive = false
    private var ocrRequestToken: Long = 0L

    @Volatile
    private var lastSavedText: String? = null

    @Volatile
    private var lastSavedTimestampMs: Long = 0L

    fun isOverlayShowing(): Boolean = overlayController?.isShowing() == true

    fun shouldSkipClipboardEcho(text: String): Boolean {
        val now = System.currentTimeMillis()
        return text == lastSavedText && now - lastSavedTimestampMs < 2000L
    }

    private fun getOrCreateOcrHelper(): ScreenOcrHelper {
        return ocrHelper ?: ocrHelperFactory().also { ocrHelper = it }
    }

    private fun getOrCreateTranslateHelper(): ScreenTranslateHelper {
        return translateHelper ?: translateHelperFactory().also { translateHelper = it }
    }

    fun start() {
        if (isActive || isOverlayShowing()) {
            Toast.makeText(context, "Screen Text Copy is already active.", Toast.LENGTH_SHORT).show()
            return
        }

        if (SmartDotAccessibilityService.instance == null &&
            nodeExtractor === DEFAULT_EXTRACTOR &&
            screenBitmapCapturer === DEFAULT_BITMAP_CAPTURER
        ) {
            dismissActivePopup()
            Toast.makeText(
                context,
                context.getString(R.string.screen_copy_accessibility_required),
                Toast.LENGTH_LONG
            ).show()
            onAccessibilityRequired()
            return
        }

        isActive = true
        dismissActivePopup()
        setHostOverlaysVisible(false)

        mainHandler.postDelayed({
            if (!isActive) return@postDelayed
            scanAndShowOverlay()
        }, CAPTURE_HIDE_DELAY_MS)
    }

    private fun triggerRescan() {
        releaseRetainedBitmap()
        isActive = true
        setHostOverlaysVisible(false)
        mainHandler.postDelayed({
            if (!isActive) return@postDelayed
            scanAndShowOverlay()
        }, CAPTURE_HIDE_DELAY_MS)
    }

    internal fun scanAndShowOverlay() {
        isActive = true
        val requestId = ++ocrRequestToken
        releaseRetainedBitmap()

        if (screenBitmapCapturer === DEFAULT_BITMAP_CAPTURER && nodeExtractor !== DEFAULT_EXTRACTOR) {
            // Test injection path using accessibility nodeExtractor directly
            fallbackToAccessibilityNodes()
            return
        }

        screenBitmapCapturer { capturedBitmap ->
            if (!isActive || requestId != ocrRequestToken) {
                runCatching { capturedBitmap?.recycle() }
                return@screenBitmapCapturer
            }

            if (capturedBitmap == null) {
                fallbackToAccessibilityNodes()
                return@screenBitmapCapturer
            }

            // Retain a clean software copy for Save Image / Cutouts / Visual Search while freeing caller buffer
            val backupCopy = runCatching {
                capturedBitmap.copy(Bitmap.Config.ARGB_8888, false)
            }.getOrNull()

            val metrics = context.resources.displayMetrics
            val targetW = metrics.widthPixels
            val targetH = metrics.heightPixels

            serviceScope.launch {
                val ocrWordNodes = try {
                    getOrCreateOcrHelper()
                        .recognizeInMemoryBitmap(capturedBitmap, targetW, targetH)
                        .wordNodes
                } catch (e: Exception) {
                    Log.w(TAG, "ML Kit in-memory OCR failed; falling back to accessibility nodes", e)
                    emptyList()
                } finally {
                    runCatching {
                        if (!capturedBitmap.isRecycled) {
                            capturedBitmap.recycle()
                        }
                    }
                }

                withContext(Dispatchers.Main.immediate) {
                    if (!isActive || requestId != ocrRequestToken) {
                        runCatching { backupCopy?.recycle() }
                        return@withContext
                    }
                    retainedScreenBitmap = backupCopy
                    if (ocrWordNodes.isNotEmpty()) {
                        presentWordNodes(ocrWordNodes)
                    } else {
                        fallbackToAccessibilityNodes()
                    }
                }
            }
        }
    }

    private fun fallbackToAccessibilityNodes() {
        val rawNodes = nodeExtractor()
        if (rawNodes == null) {
            releaseRetainedBitmap()
            isActive = false
            setHostOverlaysVisible(true)
            Toast.makeText(
                context,
                context.getString(R.string.screen_copy_accessibility_required),
                Toast.LENGTH_LONG
            ).show()
            onAccessibilityRequired()
            return
        }

        val wordNodes = SmartDotAccessibilityService.splitNodesIntoWordNodes(
            nodes = rawNodes,
            density = context.resources.displayMetrics.density
        )
        presentWordNodes(wordNodes)
    }

    private fun presentWordNodes(wordNodes: List<SmartDotAccessibilityService.ScreenTextNode>) {
        // Allow overlay to open if we have either word nodes OR a retained screenshot for Circle-to-Search cutouts
        if (wordNodes.isEmpty() && retainedScreenBitmap == null) {
            isActive = false
            setHostOverlaysVisible(true)
            Toast.makeText(
                context,
                context.getString(R.string.screen_copy_no_text),
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        val wm = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        if (wm == null) {
            releaseRetainedBitmap()
            isActive = false
            setHostOverlaysVisible(true)
            return
        }

        val controller = overlayController
            ?: InlineTextOverlayController(context, wm).also { overlayController = it }

        val accentColor = try {
            getAccentColor()
        } catch (_: Exception) {
            Color.parseColor("#00E5FF")
        }

        controller.showCopyOverlay(
            nodes = wordNodes,
            accentColor = accentColor,
            onCopyClicked = { copiedText ->
                copyAndSaveText(copiedText)
                Toast.makeText(
                    context,
                    context.getString(R.string.screen_copy_copied),
                    Toast.LENGTH_SHORT
                ).show()
            },
            onRescanRequested = {
                triggerRescan()
            },
            onDismiss = {
                ocrRequestToken++
                isActive = false
                releaseRetainedBitmap()
                stopSpeaking()
                setHostOverlaysVisible(true)
            },
            onSpeakClicked = { textToSpeak ->
                speakText(textToSpeak)
            },
            onTranslateWordRequested = { textToTranslate, onTranslated ->
                translateHighlightedText(textToTranslate, onTranslated)
            },
            onSearchClicked = { query, question ->
                launchGoogleSearch(query, question)
            },
            onSaveFullImageClicked = {
                saveFullScreenToGallery()
            },
            onCutoutSaveClicked = { boxRect, viewW, viewH ->
                saveCutoutToGallery(boxRect, viewW, viewH)
            },
            onCutoutVisualSearchClicked = { boxRect, viewW, viewH ->
                launchVisualSearchCutout(boxRect, viewW, viewH)
            },
            onCutoutCopyTextClicked = { boxRect, overlappingNodes, viewW, viewH ->
                copyTextInCutoutBox(boxRect, overlappingNodes, viewW, viewH)
            },
            toolbarOrder = getToolbarOrder(),
            onGoogleAiModeTextClicked = { query, question ->
                openAiInFloatingBrowser("google_ai_mode", query, question, cutoutBitmap = null)
            },
            onGeminiTextClicked = { text, question ->
                SelectionToolbarHelper.launchGeminiText(context, text, question)
            },
            onChatGptTextClicked = { text, question ->
                openAiInFloatingBrowser("chatgpt", text, question, cutoutBitmap = null)
            },
            onShareTextClicked = { text ->
                shareSelectedText(text)
            },
            onFullScreenTranslateClicked = {
                switchToFullScreenTranslate()
            },
            onCutoutSpeakClicked = { boxRect, overlappingNodes, viewW, viewH ->
                speakTextInCutoutBox(boxRect, overlappingNodes, viewW, viewH)
            },
            onCutoutTranslateRequested = { boxRect, overlappingNodes, viewW, viewH, onTranslated ->
                translateTextInCutoutBox(boxRect, overlappingNodes, viewW, viewH, onTranslated)
            },
            onCutoutSearchWithFallback = { boxRect, overlappingNodes, viewW, viewH, question ->
                searchCutoutBox(boxRect, overlappingNodes, viewW, viewH, question)
            },
            onCutoutGoogleAiModeClicked = { boxRect, overlappingNodes, viewW, viewH, question ->
                launchGoogleAiModeCutout(boxRect, overlappingNodes, viewW, viewH, question)
            },
            onCutoutGeminiClicked = { boxRect, overlappingNodes, viewW, viewH, question ->
                launchGeminiCutout(boxRect, overlappingNodes, viewW, viewH, question)
            },
            onCutoutChatGptClicked = { boxRect, overlappingNodes, viewW, viewH, question ->
                launchChatGptCutout(boxRect, overlappingNodes, viewW, viewH, question)
            },
            onCutoutShareClicked = { boxRect, overlappingNodes, viewW, viewH ->
                shareCutout(boxRect, overlappingNodes, viewW, viewH)
            },
            onWordLensClicked = {
                launchLensOnFullScreen()
            },
            onCutoutLensClicked = { boxRect, viewW, viewH ->
                launchLensOnCutout(boxRect, viewW, viewH)
            },
            onWordBrowserAiClicked = { query, question ->
                openAiInFloatingBrowser("google_ai_mode", query, question, cutoutBitmap = null)
            },
            onCutoutBrowserAiClicked = { boxRect, overlappingNodes, viewW, viewH, question ->
                launchGoogleAiModeCutout(boxRect, overlappingNodes, viewW, viewH, question)
            }
        )
    }

    private fun openAiInFloatingBrowser(
        providerId: String,
        textOrQuery: String,
        question: String,
        cutoutBitmap: Bitmap?
    ) {
        val customHandled = customFloatingAiBrowserLauncher?.invoke(providerId, textOrQuery, question, cutoutBitmap) == true
        if (customHandled) return
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return
        val fwm = FloatingWindowManager(context, wm)
        val accent = runCatching { getAccentColor() }.getOrDefault(Color.parseColor("#00E5FF"))
        if (providerId == "chatgpt") {
            FloatingMiniApps.openChatGptInBrowser(context, fwm, accent, textOrQuery, question, cutoutBitmap)
        } else {
            FloatingMiniApps.openGoogleAiModeInBrowser(context, fwm, accent, textOrQuery, question, cutoutBitmap)
        }
    }

    private fun switchToFullScreenTranslate() {
        ocrRequestToken++
        isActive = false
        releaseRetainedBitmap()
        stopSpeaking()
        overlayController?.dismiss()
        val handler = onFullScreenTranslateRequested
        if (handler != null) {
            handler()
        } else {
            setHostOverlaysVisible(true)
        }
    }

    private fun speakText(text: String) {
        val clean = text.trim()
        if (clean.isEmpty()) return
        customSpeakHandler?.let { handler ->
            handler(clean)
            return
        }

        val existing = ttsEngine
        if (existing != null && ttsReady) {
            existing.speak(clean, TextToSpeech.QUEUE_FLUSH, null, "smartdot_tts_${System.currentTimeMillis()}")
            return
        }

        pendingSpeakText = clean
        if (existing == null) {
            ttsEngine = TextToSpeech(context.applicationContext) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    ttsEngine?.language = Locale.getDefault()
                    ttsReady = true
                    val queued = pendingSpeakText
                    pendingSpeakText = null
                    if (!queued.isNullOrBlank()) {
                        ttsEngine?.speak(queued, TextToSpeech.QUEUE_FLUSH, null, "smartdot_tts_${System.currentTimeMillis()}")
                    }
                } else {
                    pendingSpeakText = null
                    mainHandler.post {
                        Toast.makeText(context, "Text-to-Speech unavailable on this device.", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    private fun stopSpeaking() {
        pendingSpeakText = null
        runCatching { ttsEngine?.stop() }
    }

    private fun translateHighlightedText(text: String, onTranslated: (String) -> Unit) {
        val prefs = context.getSharedPreferences(SelectionToolbarHelper.PREFS_NAME, Context.MODE_PRIVATE)
        val targetLang = prefs.getString("translate_target_language", "Hindi") ?: "Hindi"
        val preferOnline = prefs.getBoolean("translate_online_mode", false)

        serviceScope.launch {
            val resultText = try {
                getOrCreateTranslateHelper()
                    .translateTextDetailed(
                        text = text,
                        targetLanguageName = targetLang,
                        autoFlipIfSameAsTarget = true,
                        preferOnline = preferOnline
                    )
                    .translatedText
            } catch (e: Exception) {
                Log.w(TAG, "Inline word translation failed", e)
                "Translation unavailable"
            }
            withContext(Dispatchers.Main.immediate) {
                onTranslated(resultText)
            }
        }
    }

    private fun launchGoogleSearch(query: String, question: String = "") {
        val finalQuery = SelectionToolbarHelper.combineQuestionForAiSearch(question, query)
        SelectionToolbarHelper.launchGoogleSearch(context, finalQuery)
    }

    private fun shareSelectedText(text: String) {
        val clean = text.trim()
        if (clean.isEmpty()) return
        customShareTextHandler?.let {
            it(clean)
            return
        }
        SelectionToolbarHelper.launchShareText(context, clean)
    }

    private fun saveFullScreenToGallery() {
        val bitmap = retainedScreenBitmap?.takeIf { !it.isRecycled }
        if (bitmap == null) {
            Toast.makeText(context, context.getString(R.string.cutout_save_failed), Toast.LENGTH_SHORT).show()
            return
        }
        serviceScope.launch {
            val uri = withContext(Dispatchers.IO) {
                customGallerySaver?.invoke(bitmap, "SmartDot_Screen")
                    ?: CircleCutoutAndMediaHelper.saveBitmapToGallery(context, bitmap, "SmartDot_Screen")
            }
            withContext(Dispatchers.Main.immediate) {
                val msgRes = if (uri != null) R.string.cutout_saved_gallery else R.string.cutout_save_failed
                Toast.makeText(context, context.getString(msgRes), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun cropCutoutBitmap(boxRect: RectF, viewW: Int, viewH: Int): Bitmap? {
        val source = retainedScreenBitmap?.takeIf { !it.isRecycled } ?: return null
        return CircleCutoutAndMediaHelper.cropBitmapToScreenRect(source, boxRect, viewW, viewH)
    }

    private fun saveCutoutToGallery(boxRect: RectF, viewW: Int, viewH: Int) {
        val cropped = cropCutoutBitmap(boxRect, viewW, viewH)
        if (cropped == null) {
            Toast.makeText(context, context.getString(R.string.cutout_save_failed), Toast.LENGTH_SHORT).show()
            return
        }
        serviceScope.launch {
            val uri = try {
                withContext(Dispatchers.IO) {
                    customGallerySaver?.invoke(cropped, "SmartDot_Cutout")
                        ?: CircleCutoutAndMediaHelper.saveBitmapToGallery(context, cropped, "SmartDot_Cutout")
                }
            } finally {
                runCatching {
                    if (!cropped.isRecycled) cropped.recycle()
                }
            }
            withContext(Dispatchers.Main.immediate) {
                val msgRes = if (uri != null) R.string.cutout_saved_gallery else R.string.cutout_save_failed
                Toast.makeText(context, context.getString(msgRes), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun copyQuestionToClipboardIfPresent(question: String) {
        val clean = question.trim()
        if (clean.isEmpty()) return
        lastSavedText = clean
        lastSavedTimestampMs = System.currentTimeMillis()
        runCatching {
            clipboardManager.setPrimaryClip(ClipData.newPlainText("SmartDot Question", clean))
        }
    }

    private fun launchLensOnFullScreen() {
        val copiedBmp = retainedScreenBitmap?.takeIf { !it.isRecycled }?.let { bmp ->
            runCatching { bmp.copy(Bitmap.Config.ARGB_8888, false) }.getOrNull()
        }
        dismissActivePopup()
        setHostOverlaysVisible(false)
        overlayController?.dismiss()
        releaseRetainedBitmap()
        ocrRequestToken++
        isActive = false
        stopSpeaking()

        mainHandler.postDelayed({
            if (copiedBmp != null) {
                setHostOverlaysVisible(true)
                try {
                    SelectionToolbarHelper.launchGoogleLensImage(context, copiedBmp)
                } finally {
                    runCatching { if (!copiedBmp.isRecycled) copiedBmp.recycle() }
                }
            } else if (screenBitmapCapturer !== DEFAULT_BITMAP_CAPTURER || SmartDotAccessibilityService.instance != null) {
                screenBitmapCapturer { freshBmp ->
                    mainHandler.post {
                        setHostOverlaysVisible(true)
                        if (freshBmp != null) {
                            try {
                                SelectionToolbarHelper.launchGoogleLensImage(context, freshBmp)
                            } finally {
                                runCatching { if (!freshBmp.isRecycled) freshBmp.recycle() }
                            }
                        } else {
                            SelectionToolbarHelper.launchNativeGoogleLens(context)
                        }
                    }
                }
            } else {
                setHostOverlaysVisible(true)
                SelectionToolbarHelper.launchNativeGoogleLens(context)
            }
        }, 180L)
    }

    private fun launchLensOnCutout(boxRect: RectF, viewW: Int, viewH: Int) {
        val cropped = cropCutoutBitmap(boxRect, viewW, viewH)
        dismissActivePopup()
        setHostOverlaysVisible(false)
        overlayController?.dismiss()
        releaseRetainedBitmap()
        ocrRequestToken++
        isActive = false
        stopSpeaking()

        mainHandler.postDelayed({
            setHostOverlaysVisible(true)
            if (cropped != null) {
                try {
                    SelectionToolbarHelper.launchGoogleLensImage(context, cropped)
                } finally {
                    runCatching { if (!cropped.isRecycled) cropped.recycle() }
                }
            } else {
                SelectionToolbarHelper.launchNativeGoogleLens(context)
            }
        }, 180L)
    }

    private fun searchCutoutBox(
        boxRect: RectF,
        overlappingNodes: List<SmartDotAccessibilityService.ScreenTextNode>,
        viewW: Int,
        viewH: Int,
        question: String = ""
    ) {
        val cleanQuestion = question.trim()
        if (cleanQuestion.isNotEmpty()) {
            copyQuestionToClipboardIfPresent(cleanQuestion)
        }

        val fallbackText = overlappingNodes.joinToString(" ") { it.text }.trim()
        val cropped = cropCutoutBitmap(boxRect, viewW, viewH)
        if (cropped != null) {
            try {
                if (cleanQuestion.isNotEmpty()) {
                    SelectionToolbarHelper.launchGoogleSearchImageAndText(
                        context = context,
                        croppedBitmap = cropped,
                        question = cleanQuestion,
                        fallbackText = fallbackText
                    )
                } else {
                    val handledByCustom = customVisualSearchLauncher?.invoke(cropped) == true
                    if (!handledByCustom) {
                        val intent = CircleCutoutAndMediaHelper.createVisualSearchIntent(context, cropped)
                        if (intent != null) {
                            context.startActivity(intent)
                        } else {
                            Toast.makeText(context, "Visual Search is unavailable on this device.", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Cutout search launch failed", e)
                Toast.makeText(context, "Could not open Google Search / Lens.", Toast.LENGTH_SHORT).show()
            } finally {
                runCatching { if (!cropped.isRecycled) cropped.recycle() }
            }
            return
        }

        if (fallbackText.isNotEmpty() || cleanQuestion.isNotEmpty()) {
            launchGoogleSearch(fallbackText, cleanQuestion)
        } else {
            Toast.makeText(context, context.getString(R.string.cutout_save_failed), Toast.LENGTH_SHORT).show()
        }
    }

    private fun launchVisualSearchCutout(boxRect: RectF, viewW: Int, viewH: Int) {
        searchCutoutBox(boxRect, emptyList(), viewW, viewH, "")
    }

    private fun launchGoogleAiModeCutout(
        boxRect: RectF,
        overlappingNodes: List<SmartDotAccessibilityService.ScreenTextNode>,
        viewW: Int,
        viewH: Int,
        question: String = ""
    ) {
        val cleanQuestion = question.trim()
        val cropped = cropCutoutBitmap(boxRect, viewW, viewH)
        try {
            // Never convert cutout image to OCR text for Google AI Mode — upload the actual cutout bitmap
            openAiInFloatingBrowser("google_ai_mode", "", cleanQuestion, cropped)
        } finally {
            runCatching { if (cropped != null && !cropped.isRecycled) cropped.recycle() }
        }
    }

    private fun launchGeminiCutout(
        boxRect: RectF,
        overlappingNodes: List<SmartDotAccessibilityService.ScreenTextNode>,
        viewW: Int,
        viewH: Int,
        question: String = ""
    ) {
        val cleanQuestion = question.trim()
        if (cleanQuestion.isNotEmpty()) {
            copyQuestionToClipboardIfPresent(cleanQuestion)
        }
        val cropped = cropCutoutBitmap(boxRect, viewW, viewH)
        if (cropped != null) {
            try {
                SelectionToolbarHelper.launchGeminiImage(context, cropped, cleanQuestion)
            } finally {
                runCatching { if (!cropped.isRecycled) cropped.recycle() }
            }
            return
        }
        val fallbackText = overlappingNodes.joinToString(" ") { it.text }.trim()
        if (fallbackText.isNotEmpty() || cleanQuestion.isNotEmpty()) {
            SelectionToolbarHelper.launchGeminiText(context, fallbackText, cleanQuestion)
        } else {
            Toast.makeText(context, context.getString(R.string.cutout_save_failed), Toast.LENGTH_SHORT).show()
        }
    }

    private fun launchChatGptCutout(
        boxRect: RectF,
        overlappingNodes: List<SmartDotAccessibilityService.ScreenTextNode>,
        viewW: Int,
        viewH: Int,
        question: String = ""
    ) {
        val cleanQuestion = question.trim()
        val cropped = cropCutoutBitmap(boxRect, viewW, viewH)
        try {
            // Never convert cutout image to OCR text for ChatGPT — upload the actual cutout bitmap
            openAiInFloatingBrowser("chatgpt", "", cleanQuestion, cropped)
        } finally {
            runCatching { if (cropped != null && !cropped.isRecycled) cropped.recycle() }
        }
    }

    private fun shareCutout(
        boxRect: RectF,
        overlappingNodes: List<SmartDotAccessibilityService.ScreenTextNode>,
        viewW: Int,
        viewH: Int
    ) {
        val cropped = cropCutoutBitmap(boxRect, viewW, viewH)
        if (cropped != null) {
            try {
                val handler = customShareImageHandler
                if (handler != null) {
                    handler(cropped)
                } else {
                    SelectionToolbarHelper.launchShareImage(context, cropped)
                }
            } finally {
                runCatching { if (!cropped.isRecycled) cropped.recycle() }
            }
            return
        }
        val fallbackText = overlappingNodes.joinToString(" ") { it.text }.trim()
        if (fallbackText.isNotEmpty()) {
            shareSelectedText(fallbackText)
        } else {
            Toast.makeText(context, context.getString(R.string.cutout_save_failed), Toast.LENGTH_SHORT).show()
        }
    }

    private fun extractCutoutText(
        boxRect: RectF,
        overlappingNodes: List<SmartDotAccessibilityService.ScreenTextNode>,
        viewW: Int,
        viewH: Int,
        onResult: (String) -> Unit
    ) {
        val source = retainedScreenBitmap?.takeIf { !it.isRecycled }
        if (source == null) {
            onResult(overlappingNodes.joinToString(" ") { it.text }.trim())
            return
        }

        val cropped = CircleCutoutAndMediaHelper.cropBitmapToScreenRect(source, boxRect, viewW, viewH)
        serviceScope.launch {
            val extracted = try {
                if (cropped != null) {
                    val result = getOrCreateOcrHelper().recognizeInMemoryBitmap(cropped, cropped.width, cropped.height)
                    result.lineNodes.joinToString("\n") { it.text }.ifBlank {
                        result.wordNodes.joinToString(" ") { it.text }
                    }
                } else {
                    ""
                }
            } catch (e: Exception) {
                Log.w(TAG, "Cutout box OCR failed", e)
                ""
            } finally {
                runCatching {
                    if (cropped != null && !cropped.isRecycled) cropped.recycle()
                }
            }

            val finalText = extracted.ifBlank {
                overlappingNodes.joinToString(" ") { it.text }.trim()
            }.trim()

            withContext(Dispatchers.Main.immediate) {
                onResult(finalText)
            }
        }
    }

    private fun speakTextInCutoutBox(
        boxRect: RectF,
        overlappingNodes: List<SmartDotAccessibilityService.ScreenTextNode>,
        viewW: Int,
        viewH: Int
    ) {
        extractCutoutText(boxRect, overlappingNodes, viewW, viewH) { finalText ->
            if (finalText.isNotEmpty()) {
                speakText(finalText)
            } else {
                Toast.makeText(context, context.getString(R.string.cutout_no_text), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun translateTextInCutoutBox(
        boxRect: RectF,
        overlappingNodes: List<SmartDotAccessibilityService.ScreenTextNode>,
        viewW: Int,
        viewH: Int,
        onTranslated: (String?) -> Unit
    ) {
        extractCutoutText(boxRect, overlappingNodes, viewW, viewH) { finalText ->
            if (finalText.isEmpty()) {
                Toast.makeText(context, context.getString(R.string.cutout_no_text), Toast.LENGTH_SHORT).show()
                onTranslated(null)
            } else {
                translateHighlightedText(finalText) { translated ->
                    onTranslated(translated)
                }
            }
        }
    }

    private fun copyTextInCutoutBox(
        boxRect: RectF,
        overlappingNodes: List<SmartDotAccessibilityService.ScreenTextNode>,
        viewW: Int,
        viewH: Int
    ) {
        extractCutoutText(boxRect, overlappingNodes, viewW, viewH) { finalText ->
            if (finalText.isNotEmpty()) {
                copyAndSaveText(finalText)
                overlayController?.dismiss()
                ocrRequestToken++
                isActive = false
                releaseRetainedBitmap()
                stopSpeaking()
                setHostOverlaysVisible(true)
                Toast.makeText(context, context.getString(R.string.screen_copy_copied), Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(context, context.getString(R.string.cutout_no_text), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun copyAndSaveText(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return

        lastSavedText = trimmed
        lastSavedTimestampMs = System.currentTimeMillis()

        val clipData = ClipData.newPlainText("Screen Text Copy", trimmed)
        clipboardManager.setPrimaryClip(clipData)

        val dbText = if (trimmed.length > 20000) trimmed.take(20000) else trimmed
        serviceScope.launch {
            withContext(Dispatchers.IO) {
                SmartDotDatabase.getDatabase(context)
                    .smartDotDao()
                    .insertClip(ClipEntry(text = dbText))
            }
        }
    }

    private fun releaseRetainedBitmap() {
        retainedScreenBitmap?.let { bmp ->
            runCatching {
                if (!bmp.isRecycled) bmp.recycle()
            }
        }
        retainedScreenBitmap = null
    }

    fun dismissOverlayIfShowing() {
        ocrRequestToken++
        isActive = false
        overlayController?.dismiss()
        releaseRetainedBitmap()
        stopSpeaking()
        setHostOverlaysVisible(true)
    }

    fun release() {
        ocrRequestToken++
        mainHandler.removeCallbacksAndMessages(null)
        releaseRetainedBitmap()
        stopSpeaking()
        runCatching { ttsEngine?.shutdown() }
        ttsEngine = null
        ttsReady = false
        overlayController?.dismiss()
        overlayController = null
        ocrHelper?.close()
        ocrHelper = null
        translateHelper?.close()
        translateHelper = null
        isActive = false
    }

    companion object {
        private const val TAG = "ScreenTextCopyCtrl"
        internal const val CAPTURE_HIDE_DELAY_MS = 50L
        private val DEFAULT_EXTRACTOR: () -> List<SmartDotAccessibilityService.ScreenTextNode>? = {
            SmartDotAccessibilityService.instance?.extractVisibleScreenTextNodes()
        }
        private val DEFAULT_BITMAP_CAPTURER: ((Bitmap?) -> Unit) -> Unit = { callback ->
            val service = SmartDotAccessibilityService.instance
            if (service != null) {
                service.captureScreenBitmapInMemory(callback)
            } else {
                callback(null)
            }
        }
    }
}
