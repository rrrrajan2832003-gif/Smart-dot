package com.example

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import android.widget.Toast
import com.example.database.ClipEntry
import com.example.database.SmartDotDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Zero-screenshot Screen Text Copy controller powered by SmartDotAccessibilityService.
 * Scans visible AccessibilityNodeInfo bounds on the live screen, shows a transparent
 * overlay with clickable text boxes, and copies the tapped text on "Copy".
 */
class ScreenTextCopyController(
    private val context: Context,
    private val serviceScope: CoroutineScope,
    private val clipboardManager: ClipboardManager,
    private val getAccentColor: () -> Int,
    private val dismissActivePopup: () -> Unit,
    private val setHostOverlaysVisible: (Boolean) -> Unit,
    private val onAccessibilityRequired: () -> Unit,
    private val nodeExtractor: () -> List<SmartDotAccessibilityService.ScreenTextNode>? = {
        SmartDotAccessibilityService.instance?.extractVisibleScreenTextNodes()
    }
) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var overlayController: InlineTextOverlayController? = null
    private var isActive = false

    @Volatile
    private var lastSavedText: String? = null

    @Volatile
    private var lastSavedTimestampMs: Long = 0L

    fun isOverlayShowing(): Boolean = overlayController?.isShowing() == true

    fun shouldSkipClipboardEcho(text: String): Boolean {
        val now = System.currentTimeMillis()
        return text == lastSavedText && now - lastSavedTimestampMs < 2000L
    }

    fun start() {
        if (isActive || isOverlayShowing()) {
            Toast.makeText(context, "Screen Text Copy is already active.", Toast.LENGTH_SHORT).show()
            return
        }

        val testServiceCheck = SmartDotAccessibilityService.instance
        if (testServiceCheck == null && nodeExtractor === DEFAULT_EXTRACTOR) {
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
        val rawNodes = nodeExtractor()
        if (rawNodes == null) {
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

        if (wordNodes.isEmpty()) {
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
                isActive = false
                setHostOverlaysVisible(true)
            }
        )
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

    fun release() {
        mainHandler.removeCallbacksAndMessages(null)
        overlayController?.dismiss()
        overlayController = null
        isActive = false
    }

    companion object {
        private const val SCAN_SETTLE_DELAY_MS = 110L
        private val DEFAULT_EXTRACTOR: () -> List<SmartDotAccessibilityService.ScreenTextNode>? = {
            SmartDotAccessibilityService.instance?.extractVisibleScreenTextNodes()
        }
    }
}
