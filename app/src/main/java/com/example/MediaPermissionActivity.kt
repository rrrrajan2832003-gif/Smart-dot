package com.example

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat

/**
 * Lightweight transparent helper activity used by Floating Gallery, Floating Music,
 * and Floating Media & Files overlays to request runtime media permissions without
 * leaving the user's current context, then immediately reloading the media list.
 */
class MediaPermissionActivity : ComponentActivity() {

    private val requestPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val allGranted = results.isNotEmpty() && results.values.all { it }
        val anyGranted = results.values.any { it }
        val granted = allGranted || anyGranted
        val cb = pendingCallback
        pendingCallback = null
        cb?.invoke(granted)
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val requested = intent.getStringArrayExtra(EXTRA_PERMISSIONS)
            ?.filter { it.isNotBlank() }
            ?.toTypedArray()
            ?.takeIf { it.isNotEmpty() }
            ?: requiredPermissionsForAllMedia()

        val missing = requested.filter { perm ->
            ContextCompat.checkSelfPermission(this, perm) != PackageManager.PERMISSION_GRANTED
        }.toTypedArray()

        if (missing.isEmpty()) {
            val cb = pendingCallback
            pendingCallback = null
            cb?.invoke(true)
            finish()
        } else {
            requestPermissionsLauncher.launch(missing)
        }
    }

    override fun onDestroy() {
        if (isFinishing) {
            // Ensure stale callback isn't leaked if user backed out without result
            pendingCallback = null
        }
        super.onDestroy()
    }

    companion object {
        const val EXTRA_PERMISSIONS = "extra_media_permissions"

        @Volatile
        internal var pendingCallback: ((Boolean) -> Unit)? = null

        fun requiredPermissionsForAllMedia(): Array<String> {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                arrayOf(
                    Manifest.permission.READ_MEDIA_IMAGES,
                    Manifest.permission.READ_MEDIA_VIDEO,
                    Manifest.permission.READ_MEDIA_AUDIO
                )
            } else {
                arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        }

        fun requiredPermissionsForGallery(): Array<String> {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                arrayOf(
                    Manifest.permission.READ_MEDIA_IMAGES,
                    Manifest.permission.READ_MEDIA_VIDEO
                )
            } else {
                arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        }

        fun requiredPermissionsForAudio(): Array<String> {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                arrayOf(Manifest.permission.READ_MEDIA_AUDIO)
            } else {
                arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        }

        fun hasImagesAndVideoPermission(context: Context): Boolean {
            return requiredPermissionsForGallery().any { perm ->
                ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED
            }
        }

        fun hasAudioPermission(context: Context): Boolean {
            return requiredPermissionsForAudio().all { perm ->
                ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED
            }
        }

        fun hasAllMediaPermissions(context: Context): Boolean {
            return requiredPermissionsForAllMedia().any { perm ->
                ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED
            }
        }

        fun launchPermissionPrompt(
            context: Context,
            permissions: Array<String> = requiredPermissionsForAllMedia(),
            onResult: (Boolean) -> Unit
        ) {
            pendingCallback = onResult
            val intent = Intent(context, MediaPermissionActivity::class.java).apply {
                putExtra(EXTRA_PERMISSIONS, permissions)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            runCatching {
                context.startActivity(intent)
            }.onFailure {
                pendingCallback = null
                onResult(false)
            }
        }
    }
}
