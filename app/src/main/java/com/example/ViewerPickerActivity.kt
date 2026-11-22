package com.example

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts

/**
 * Play-Policy-compliant zero-permission translucent picker for the Floating File/Image Viewer.
 * Uses Android Photo Picker (PickVisualMedia) for images and Storage Access Framework (OpenDocument)
 * for files/documents without requesting READ_EXTERNAL_STORAGE or READ_MEDIA_* permissions.
 */
class ViewerPickerActivity : ComponentActivity() {

    private val pickImageLauncher = registerForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        deliverResult(uri, isImage = true)
    }

    private val openFileLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            runCatching {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
        }
        val mime = uri?.let { runCatching { contentResolver.getType(it) }.getOrNull() }.orEmpty()
        deliverResult(uri, isImage = mime.startsWith("image/"))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val mode = intent.getStringExtra(EXTRA_PICK_MODE) ?: MODE_IMAGE
        if (mode == MODE_FILE) {
            openFileLauncher.launch(arrayOf("*/*"))
        } else {
            pickImageLauncher.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
            )
        }
    }

    private fun deliverResult(uri: Uri?, isImage: Boolean) {
        if (uri != null) {
            pendingCallback?.invoke(uri, isImage)
        }
        pendingCallback = null
        finish()
    }

    companion object {
        const val EXTRA_PICK_MODE = "extra_pick_mode"
        const val MODE_IMAGE = "image"
        const val MODE_FILE = "file"

        @Volatile
        internal var pendingCallback: ((Uri, Boolean) -> Unit)? = null
    }
}
