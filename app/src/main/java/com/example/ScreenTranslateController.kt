package com.example

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
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
 * Zero-screenshot Screen Translate controller powered by SmartDotAccessibilityService
 * and 100% free offline Google ML Kit On-Device Translation.
 * Scans visible UI nodes, shows clickable transparent highlight boxes over the live screen,
 * and displays a floating translated bubble + Copy button next to whichever text is tapped.
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
    private val nodeExtractor: () -> List<SmartDotAccessibilityService.ScreenTextNode>? = {
        SmartDotAccessibilityService.instance?.extractVisibleScreenTextNodes()
    },
    private val translateHelperFactory: () -> ScreenTranslateHelper = { ScreenTranslateHelper() }
) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var translateHelper: ScreenTranslateHelper? = null
    private var overlayController: InlineTextOverlayController? = null

    private var isActive = false
    private var activeTargetLanguage: String = "Hindi"
    private var translationRequestToken: Long = 0L
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

    fun start(targetLanguage: String? = null) {
        if (isActive || isOverlayShowing()) {
            Toast.makeText(context, "Screen Translate is already active.", Toast.LENGTH_SHORT).show()
            return
        }

        if (SmartDotAccessibilityService.instance == null && nodeExtractor === DEFAULT_EXTRACTOR) {
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
        isActive = true
        dismissActivePopup()
        setHostOverlaysVisible(false)

        mainHandler.postDelayed({
            if (!isActive) return@postDelayed
            scanAndShowOverlay()
        }, SCAN_SETTLE_DELAY_MS)
    }

    private fun triggerRescan() {
        isActive = true
        setHostOverlaysVisible(false)
        mainHandler.postDelayed({
            if (!isActive) return@postDelayed
            scanAndShowOverlay()
        }, SCAN_SETTLE_DELAY_MS)
    }

    internal fun scanAndShowOverlay() {
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
            onTargetLanguageChanged = { newLang ->
                activeTargetLanguage = newLang
                saveTargetLanguage(newLang)
                runBulkTranslation(lastScannedNodes, newLang)
            },
            onTranslatedLabelTapped = { translatedText ->
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
                translationRequestToken++
                lastScannedNodes = emptyList()
                isActive = false
                setHostOverlaysVisible(true)
            }
        )

        runBulkTranslation(visibleTextNodes, activeTargetLanguage)
    }

    private fun runBulkTranslation(
        nodes: List<SmartDotAccessibilityService.ScreenTextNode>,
        targetLanguage: String
    ) {
        if (nodes.isEmpty()) return
        val requestId = ++translationRequestToken
        serviceScope.launch {
            try {
                val helper = getTranslateHelper()
                val translatedNodes = helper.translateNodesBulk(
                    nodes = nodes,
                    targetLanguageName = targetLanguage,
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
                Log.e(TAG, "Offline ML Kit bulk translation failed", e)
                withContext(Dispatchers.Main.immediate) {
                    if (requestId == translationRequestToken && isOverlayShowing()) {
                        overlayController?.showTranslatingIndicator(
                            "Offline model unavailable: ${e.localizedMessage ?: "Connect once to download"}"
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
        translationRequestToken++
        mainHandler.removeCallbacksAndMessages(null)
        overlayController?.dismiss()
        overlayController = null
        translateHelper?.close()
        translateHelper = null
        isActive = false
    }

    companion object {
        private const val TAG = "ScreenTranslateCtrl"
        private const val SCAN_SETTLE_DELAY_MS = 110L
        private val DEFAULT_EXTRACTOR: () -> List<SmartDotAccessibilityService.ScreenTextNode>? = {
            SmartDotAccessibilityService.instance?.extractVisibleScreenTextNodes()
        }
    }
}
