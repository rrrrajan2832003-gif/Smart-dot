package com.example

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat

/**
 * Translucent helper activity that requests MediaProjection consent (and optional mic audio)
 * and forwards the granted result back to FloatingService.
 */
class ScreenRecordPermissionActivity : ComponentActivity() {

    private var includeMicAudio = false

    private val audioPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        includeMicAudio = granted
        launchMediaProjectionPrompt()
    }

    private val mediaProjectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val serviceIntent = Intent(this, FloatingService::class.java).apply {
            action = FloatingService.ACTION_SCREEN_RECORD_RESULT
            putExtra(FloatingService.EXTRA_RECORD_RESULT_CODE, result.resultCode)
            putExtra(FloatingService.EXTRA_RECORD_DATA, result.data)
            putExtra(FloatingService.EXTRA_RECORD_WITH_AUDIO, includeMicAudio)
        }
        ContextCompat.startForegroundService(this, serviceIntent)
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val hasAudio = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        if (hasAudio) {
            includeMicAudio = true
            launchMediaProjectionPrompt()
        } else {
            audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun launchMediaProjectionPrompt() {
        val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjectionLauncher.launch(projectionManager.createScreenCaptureIntent())
    }
}
