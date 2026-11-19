package com.example

import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.example.database.ClipEntry
import com.example.database.SmartDotDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Isolated controller for Phase 1 Screen Text Copy (MediaProjection + ML Kit OCR).
 * Reuses an active MediaProjection session when valid and only requests consent again
 * if no session is active or Android terminates the active session.
 */
class ScreenTextCopyController(
    private val context: Context,
    private val serviceScope: CoroutineScope,
    private val clipboardManager: ClipboardManager,
    private val getAccentColor: () -> Int,
    private val dismissActivePopup: () -> Unit,
    private val setHostOverlaysVisible: (Boolean) -> Unit,
    private val ocrHelperFactory: () -> ScreenOcrHelper = { ScreenOcrHelper() }
) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var ocrHelper: ScreenOcrHelper? = null
    private var overlayController: InlineTextOverlayController? = null
    private var isCaptureInProgress = false
    private var isReceiverRegistered = false
    private var hasRetriedConsentForAttempt = false

    @Volatile
    private var lastSavedText: String? = null

    @Volatile
    private var lastSavedTimestampMs: Long = 0L

    private val consentTimeoutRunnable = Runnable {
        if (isCaptureInProgress && overlayController?.isShowing() != true) {
            Log.w(TAG, "Resetting stale Screen Text Copy state after timeout.")
            isCaptureInProgress = false
            hasRetriedConsentForAttempt = false
            setHostOverlaysVisible(true)
        }
    }

    private val screenCaptureReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            when (intent?.action) {
                ScreenCaptureService.ACTION_CAPTURE_READY -> {
                    mainHandler.removeCallbacks(consentTimeoutRunnable)
                    hasRetriedConsentForAttempt = false
                    handleCapturedFrame()
                }
                ScreenCaptureService.ACTION_CAPTURE_FAILED -> {
                    mainHandler.removeCallbacks(consentTimeoutRunnable)
                    isCaptureInProgress = false
                    hasRetriedConsentForAttempt = false
                    setHostOverlaysVisible(true)
                    val message = intent.getStringExtra(ScreenCaptureService.EXTRA_ERROR_MESSAGE)
                        ?: context.getString(R.string.screen_copy_permission_denied)
                    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                }
                ScreenCaptureService.ACTION_SESSION_EXPIRED -> {
                    mainHandler.removeCallbacks(consentTimeoutRunnable)
                    if (!isCaptureInProgress) return
                    if (!hasRetriedConsentForAttempt) {
                        hasRetriedConsentForAttempt = true
                        mainHandler.postDelayed(consentTimeoutRunnable, STALE_SESSION_TIMEOUT_MS)
                        launchConsentActivity()
                    } else {
                        isCaptureInProgress = false
                        hasRetriedConsentForAttempt = false
                        setHostOverlaysVisible(true)
                        Toast.makeText(
                            context,
                            context.getString(R.string.screen_copy_permission_denied),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
        }
    }

    fun isOverlayShowing(): Boolean = overlayController?.isShowing() == true

    fun shouldSkipClipboardEcho(text: String): Boolean {
        val now = System.currentTimeMillis()
        return text == lastSavedText && now - lastSavedTimestampMs < 2000L
    }

    fun start() {
        if (isCaptureInProgress || isOverlayShowing()) {
            Toast.makeText(context, "Screen Text Copy is already active.", Toast.LENGTH_SHORT).show()
            return
        }

        ensureReceiverRegistered()
        isCaptureInProgress = true
        hasRetriedConsentForAttempt = false

        dismissActivePopup()
        setHostOverlaysVisible(false)

        mainHandler.removeCallbacks(consentTimeoutRunnable)
        mainHandler.postDelayed(consentTimeoutRunnable, STALE_SESSION_TIMEOUT_MS)

        mainHandler.postDelayed({
            if (!isCaptureInProgress) return@postDelayed
            if (ScreenCaptureService.isSessionActive) {
                requestCaptureOnExistingSession()
            } else {
                launchConsentActivity()
            }
        }, 120L)
    }

    private fun triggerRescan() {
        ensureReceiverRegistered()
        isCaptureInProgress = true
        hasRetriedConsentForAttempt = false
        setHostOverlaysVisible(false)

        mainHandler.removeCallbacks(consentTimeoutRunnable)
        mainHandler.postDelayed(consentTimeoutRunnable, STALE_SESSION_TIMEOUT_MS)

        mainHandler.postDelayed({
            if (!isCaptureInProgress) return@postDelayed
            if (ScreenCaptureService.isSessionActive) {
                requestCaptureOnExistingSession()
            } else {
                launchConsentActivity()
            }
        }, 120L)
    }

    private fun requestCaptureOnExistingSession() {
        try {
            val captureIntent = Intent(context, ScreenCaptureService::class.java).apply {
                action = ScreenCaptureService.ACTION_CAPTURE_NEXT
            }
            ContextCompat.startForegroundService(context, captureIntent)
        } catch (e: Exception) {
            Log.w(TAG, "Failed reusing ScreenCaptureService; falling back to consent", e)
            hasRetriedConsentForAttempt = true
            launchConsentActivity()
        }
    }

    private fun launchConsentActivity() {
        try {
            val consentIntent = Intent(context, ScreenCaptureConsentActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(consentIntent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed launching ScreenCaptureConsentActivity", e)
            mainHandler.removeCallbacks(consentTimeoutRunnable)
            isCaptureInProgress = false
            hasRetriedConsentForAttempt = false
            setHostOverlaysVisible(true)
            Toast.makeText(
                context,
                "Unable to launch screen capture permission.",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun ensureReceiverRegistered() {
        if (isReceiverRegistered) return
        try {
            val captureFilter = IntentFilter().apply {
                addAction(ScreenCaptureService.ACTION_CAPTURE_READY)
                addAction(ScreenCaptureService.ACTION_CAPTURE_FAILED)
                addAction(ScreenCaptureService.ACTION_SESSION_EXPIRED)
            }
            ContextCompat.registerReceiver(
                context,
                screenCaptureReceiver,
                captureFilter,
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            isReceiverRegistered = true
        } catch (e: Exception) {
            Log.e(TAG, "Failed registering screenCaptureReceiver", e)
        }
    }

    private fun handleCapturedFrame() {
        val frame = ScreenCaptureHolder.consumeFrame()
        if (frame == null) {
            isCaptureInProgress = false
            setHostOverlaysVisible(true)
            Toast.makeText(context, context.getString(R.string.screen_copy_no_text), Toast.LENGTH_SHORT).show()
            return
        }

        Toast.makeText(context, context.getString(R.string.screen_copy_scanning), Toast.LENGTH_SHORT).show()

        serviceScope.launch {
            try {
                val helper = ocrHelper ?: ocrHelperFactory().also { ocrHelper = it }
                val blocks = try {
                    helper.recognizeEnglishAndHindi(frame.bitmap)
                } finally {
                    // Never keep or render a screenshot bitmap: release it immediately after OCR.
                    if (!frame.bitmap.isRecycled) {
                        frame.bitmap.recycle()
                    }
                }

                if (blocks.isEmpty()) {
                    isCaptureInProgress = false
                    setHostOverlaysVisible(true)
                    val msgRes = if (frame.isAllBlackOrProtected) {
                        R.string.screen_copy_protected_screen
                    } else {
                        R.string.screen_copy_no_text
                    }
                    Toast.makeText(context, context.getString(msgRes), Toast.LENGTH_LONG).show()
                    return@launch
                }

                val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as? android.view.WindowManager
                if (windowManager == null) {
                    isCaptureInProgress = false
                    setHostOverlaysVisible(true)
                    return@launch
                }

                val controller = overlayController
                    ?: InlineTextOverlayController(context, windowManager).also { overlayController = it }

                val accentColor = try {
                    getAccentColor()
                } catch (_: Exception) {
                    Color.parseColor("#00E5FF")
                }

                controller.show(
                    blocks = blocks,
                    captureWidth = frame.captureWidth,
                    captureHeight = frame.captureHeight,
                    displayRotation = frame.displayRotation,
                    accentColor = accentColor,
                    onCopyText = { copiedText, selectedCount ->
                        saveOcrTextToClipboardAndDb(copiedText)
                        val msg = if (selectedCount <= 1) {
                            "Copied text to clipboard"
                        } else {
                            "Copied $selectedCount text selections to clipboard"
                        }
                        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                    },
                    onRescanRequested = {
                        triggerRescan()
                    },
                    onDismiss = {
                        isCaptureInProgress = false
                        setHostOverlaysVisible(true)
                    }
                )
            } catch (e: Exception) {
                Log.e(TAG, "OCR processing failed", e)
                if (!frame.bitmap.isRecycled) {
                    frame.bitmap.recycle()
                }
                isCaptureInProgress = false
                setHostOverlaysVisible(true)
                Toast.makeText(
                    context,
                    "OCR failed: ${e.localizedMessage ?: "Unknown error"}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun saveOcrTextToClipboardAndDb(text: String) {
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
        if (isReceiverRegistered) {
            try {
                context.unregisterReceiver(screenCaptureReceiver)
            } catch (e: Exception) {
                Log.w(TAG, "Capture receiver clean failed", e)
            }
            isReceiverRegistered = false
        }
        if (ScreenCaptureService.isSessionActive) {
            runCatching {
                context.stopService(Intent(context, ScreenCaptureService::class.java))
            }
            ScreenCaptureService.isSessionActive = false
        }
        overlayController?.dismiss()
        overlayController = null
        ocrHelper?.close()
        ocrHelper = null
        ScreenCaptureHolder.clear()
        isCaptureInProgress = false
        hasRetriedConsentForAttempt = false
    }

    companion object {
        private const val TAG = "ScreenTextCopyCtrl"
        private const val STALE_SESSION_TIMEOUT_MS = 20000L
    }
}
