package com.example

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat

/**
 * Translucent Activity used exclusively to request per-session MediaProjection consent
 * from the system when triggered by FloatingService.
 */
class ScreenCaptureConsentActivity : ComponentActivity() {

    private var hasLaunchedPrompt = false
    private var hasDeliveredResult = false

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        hasDeliveredResult = true
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            val captureIntent = Intent(this, ScreenCaptureService::class.java).apply {
                putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, data)
            }
            ContextCompat.startForegroundService(this, captureIntent)
        } else {
            notifyCaptureCanceled(getString(R.string.screen_copy_permission_denied))
        }
        finishSilently()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        hasLaunchedPrompt = savedInstanceState?.getBoolean(KEY_LAUNCHED_PROMPT, false) ?: false
        if (!hasLaunchedPrompt) {
            hasLaunchedPrompt = true
            val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager
            if (projectionManager == null) {
                hasDeliveredResult = true
                notifyCaptureCanceled("MediaProjection service is unavailable on this device.")
                finishSilently()
                return
            }
            try {
                projectionLauncher.launch(projectionManager.createScreenCaptureIntent())
            } catch (e: Exception) {
                hasDeliveredResult = true
                notifyCaptureCanceled("Unable to request screen capture permission.")
                finishSilently()
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_LAUNCHED_PROMPT, hasLaunchedPrompt)
    }

    override fun onDestroy() {
        if (isFinishing && !hasDeliveredResult) {
            hasDeliveredResult = true
            notifyCaptureCanceled(getString(R.string.screen_copy_permission_denied))
        }
        super.onDestroy()
    }

    private fun notifyCaptureCanceled(message: String) {
        val cancelIntent = Intent(ScreenCaptureService.ACTION_CAPTURE_FAILED).apply {
            setPackage(packageName)
            putExtra(ScreenCaptureService.EXTRA_ERROR_MESSAGE, message)
        }
        sendBroadcast(cancelIntent)
    }

    private fun finishSilently() {
        finish()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
    }

    companion object {
        private const val KEY_LAUNCHED_PROMPT = "launched_prompt"
    }
}
