package com.example

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.webkit.ValueCallback
import androidx.activity.ComponentActivity
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts

/**
 * Play-Policy-compliant zero-permission translucent picker for Floating File/Image Viewer
 * and Floating AI WebView file uploads (Gemini / ChatGPT).
 * Uses Android Photo Picker (PickVisualMedia) and Storage Access Framework (OpenDocument /
 * OpenMultipleDocuments) without requesting READ_EXTERNAL_STORAGE or READ_MEDIA_* permissions.
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

    private val openFolderLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { treeUri ->
        if (treeUri != null) {
            runCatching {
                contentResolver.takePersistableUriPermission(
                    treeUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            pendingFolderCallback?.invoke(treeUri)
        }
        pendingFolderCallback = null
        finish()
    }

    private val webFileChooserLauncher = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        val resultArray = if (uris.isNullOrEmpty()) null else uris.toTypedArray()
        pendingWebFileCallback?.onReceiveValue(resultArray)
        pendingWebFileCallback = null
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        when (intent.getStringExtra(EXTRA_PICK_MODE) ?: MODE_IMAGE) {
            MODE_FILE -> openFileLauncher.launch(arrayOf("*/*"))
            MODE_MEDIA -> openFileLauncher.launch(arrayOf("image/*", "video/*", "audio/*", "*/*"))
            MODE_FOLDER -> openFolderLauncher.launch(null)
            MODE_WEB_FILE_CHOOSER -> {
                val acceptTypes = intent.getStringArrayExtra(EXTRA_ACCEPT_MIME_TYPES)
                    ?.filter { it.isNotBlank() }
                    ?.toTypedArray()
                    ?.takeIf { it.isNotEmpty() }
                    ?: arrayOf("*/*")
                webFileChooserLauncher.launch(acceptTypes)
            }
            else -> pickImageLauncher.launch(
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
        const val EXTRA_ACCEPT_MIME_TYPES = "extra_accept_mime_types"
        const val MODE_IMAGE = "image"
        const val MODE_FILE = "file"
        const val MODE_MEDIA = "media"
        const val MODE_FOLDER = "folder"
        const val MODE_WEB_FILE_CHOOSER = "web_file_chooser"

        @Volatile
        internal var pendingCallback: ((Uri, Boolean) -> Unit)? = null

        @Volatile
        internal var pendingFolderCallback: ((Uri) -> Unit)? = null

        @Volatile
        internal var pendingWebFileCallback: ValueCallback<Array<Uri>>? = null
    }
}
