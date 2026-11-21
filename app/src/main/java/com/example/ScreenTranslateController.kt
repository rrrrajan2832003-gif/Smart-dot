package com.example

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.WindowManager
import android.widget.Toast
import com.example.database.ClipEntry
import com.example.database.SmartDotDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * In-memory Screen Translate controller powered by:
 * 1) API 30+ AccessibilityService.takeScreenshot() into memory ONLY (zero gallery/storage writes, zero MediaProjection)
 * 2) Free on-device ML Kit Text Recognition (Latin + Devanagari) extracting screen text (including photos/images/apps)
 * 3) Free on-device ML Kit Translation rendering translated lines directly in place
 * 4) Thin 6dp highlighter pen + word tap over translated text to copy selected translated words.
 */
class ScreenTranslateController(
    private val context: Context,
    private val serviceScope: CoroutineScope,
    private val clipboardManager: ClipboardManager,
    private val getAccentColor: () -> Int,
    private val getSavedTargetLanguage: () -> String,
    private val saveTargetLanguage: (String) -> Unit,
    private val dismissActivePopup: () -> Unit,
    private val setHostOverlaysVisible: (Boolean) -> Unit,
    private val onAccessibilityRequired: () -> Unit,
    private val getSavedOnlineMode: () -> Boolean = { true },
    private val saveOnlineMode: (Boolean) -> Unit = {},
    private val nodeExtractor: () -> List<SmartDotAccessibilityService.ScreenTextNode>? = DEFAULT_EXTRACTOR,
    private val translateHelperFactory: () -> ScreenTranslateHelper = { ScreenTranslateHelper() },
    private val ocrHelperFactory: () -> ScreenOcrHelper = { ScreenOcrHelper() },
    private val screenBitmapCapturer: ((Bitmap?) -> Unit) -> Unit = DEFAULT_BITMAP_CAPTURER
) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var translateHelper: ScreenTranslateHelper? = null
    private var ocrHelper: ScreenOcrHelper? = null
    private var overlayController: InlineTextOverlayController? = null

    private var isActive = false
    private var activeTargetLanguage: String = "Hindi"
    private var activeOnlineMode: Boolean = true
    private var translationRequestToken: Long = 0L
    private var ocrRequestToken: Long = 0L
    private var lastScannedNodes: List<SmartDotAccessibilityService.ScreenTextNode> = emptyList()

    @Volatile
    private var lastSavedText: String? = null

    @Volatile
    private var lastSavedTimestampMs: Long = 0L

    fun isOverlayShowing(): Boolean = overlayController?.isShowing() == true

    fun shouldSkipClipboardEcho(text: String): Boolean {
        val now = System.currentTimeMillis()
        return text == lastSavedText && now - lastSavedTimestampMs < 2000L
    }

    fun getTranslateHelper(): ScreenTranslateHelper {
        return translateHelper ?: translateHelperFactory().also { translateHelper = it }
    }

    private fun getOrCreateOcrHelper(): ScreenOcrHelper {
        return ocrHelper ?: ocrHelperFactory().also { ocrHelper = it }
    }

    fun start(targetLanguage: String? = null) {
        if (isActive || isOverlayShowing()) {
            Toast.makeText(context, "Screen Translate is already active.", Toast.LENGTH_SHORT).show()
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

        activeTargetLanguage = targetLanguage ?: getSavedTargetLanguage()
        activeOnlineMode = getSavedOnlineMode()
        isActive = true
        dismissActivePopup()
        setHostOverlaysVisible(false)

        mainHandler.postDelayed({
            if (!isActive) return@postDelayed
            scanAndShowOverlay()
        }, CAPTURE_HIDE_DELAY_MS)
    }

    private fun triggerRescan() {
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

        if (screenBitmapCapturer === DEFAULT_BITMAP_CAPTURER && nodeExtractor !== DEFAULT_EXTRACTOR) {
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

            val metrics = context.resources.displayMetrics
            val targetW = metrics.widthPixels
            val targetH = metrics.heightPixels

            serviceScope.launch {
                val ocrLineNodes = try {
                    getOrCreateOcrHelper()
                        .recognizeInMemoryBitmap(capturedBitmap, targetW, targetH)
                        .lineNodes
                } catch (e: Exception) {
                    Log.w(TAG, "ML Kit OCR for translation failed; falling back to accessibility nodes", e)
                    emptyList()
                } finally {
                    runCatching {
                        if (!capturedBitmap.isRecycled) {
                            capturedBitmap.recycle()
                        }
                    }
                }

                withContext(Dispatchers.Main.immediate) {
                    if (!isActive || requestId != ocrRequestToken) return@withContext
                    if (ocrLineNodes.isNotEmpty()) {
                        presentTranslateNodes(ocrLineNodes)
                    } else {
                        fallbackToAccessibilityNodes()
                    }
                }
            }
        }
    }

    private fun fallbackToAccessibilityNodes() {
        val nodes = nodeExtractor()
        if (nodes == null) {
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

        val visibleTextNodes = nodes.filter { it.hasVisibleText }.ifEmpty { nodes }
        presentTranslateNodes(visibleTextNodes)
    }

    private fun presentTranslateNodes(visibleTextNodes: List<SmartDotAccessibilityService.ScreenTextNode>) {
        if (visibleTextNodes.isEmpty()) {
            isActive = false
            setHostOverlaysVisible(true)
            Toast.makeText(
                context,
                context.getString(R.string.screen_translate_no_text),
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        val wm = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        if (wm == null) {
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

        lastScannedNodes = visibleTextNodes
        controller.showTranslateOverlay(
            accentColor = accentColor,
            initialTargetLanguage = activeTargetLanguage,
            initialOnlineMode = activeOnlineMode,
            onTranslationModeToggled = { useOnline ->
                activeOnlineMode = useOnline
                saveOnlineMode(useOnline)
                runBulkTranslation(lastScannedNodes, activeTargetLanguage, useOnline)
            },
            onTargetLanguageChanged = { newLang ->
                activeTargetLanguage = newLang
                saveTargetLanguage(newLang)
                runBulkTranslation(lastScannedNodes, newLang, activeOnlineMode)
            },
            onCopyClicked = { translatedText ->
                copyAndSaveText(translatedText)
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
                translationRequestToken++
                lastScannedNodes = emptyList()
                isActive = false
                setHostOverlaysVisible(true)
            }
        )

        runBulkTranslation(visibleTextNodes, activeTargetLanguage, activeOnlineMode)
    }

    private fun runBulkTranslation(
        nodes: List<SmartDotAccessibilityService.ScreenTextNode>,
        targetLanguage: String,
        preferOnline: Boolean
    ) {
        if (nodes.isEmpty()) return
        val requestId = ++translationRequestToken
        serviceScope.launch {
            try {
                val helper = getTranslateHelper()
                val translatedNodes = helper.translateNodesBulk(
                    nodes = nodes,
                    targetLanguageName = targetLanguage,
                    preferOnline = preferOnline,
                    onModelDownloading = {
                        mainHandler.post {
                            if (requestId == translationRequestToken && isOverlayShowing()) {
                                overlayController?.showTranslatingIndicator(
                                    context.getString(R.string.screen_translate_downloading_model)
                                )
                            }
                        }
                    }
                )
                withContext(Dispatchers.Main.immediate) {
                    if (requestId == translationRequestToken && isOverlayShowing()) {
                        overlayController?.showBulkTranslations(translatedNodes)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Screen bulk translation failed", e)
                withContext(Dispatchers.Main.immediate) {
                    if (requestId == translationRequestToken && isOverlayShowing()) {
                        overlayController?.showTranslatingIndicator(
                            "Translation unavailable: ${e.localizedMessage ?: "Check internet or connect once"}"
                        )
                    }
                }
            }
        }
    }

    private fun copyAndSaveText(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return

        lastSavedText = trimmed
        lastSavedTimestampMs = System.currentTimeMillis()

        val clipData = ClipData.newPlainText("Translated text", trimmed)
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

    fun release() {
        ocrRequestToken++
        translationRequestToken++
        mainHandler.removeCallbacksAndMessages(null)
        overlayController?.dismiss()
        overlayController = null
        translateHelper?.close()
        translateHelper = null
        ocrHelper?.close()
        ocrHelper = null
        isActive = false
    }

    companion object {
        private const val TAG = "ScreenTranslateCtrl"
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
