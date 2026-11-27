package com.example

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.speech.RecognizerIntent
import android.webkit.MimeTypeMap
import android.webkit.ValueCallback
import androidx.activity.ComponentActivity
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import java.io.File
import java.util.Locale

/**
 * Play-Policy-compliant zero-permission translucent picker for Floating File/Image Viewer,
 * WebView file uploads (caching picked URIs via FileProvider so Chrome/WebView never fails reading SAF URIs),
 * and Voice Search recognition fallback.
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
        val resultArray = if (uris.isNullOrEmpty()) {
            null
        } else {
            resolveWebUploadUris(uris).toTypedArray()
        }
        pendingWebFileCallback?.onReceiveValue(resultArray)
        pendingWebFileCallback = null
        finish()
    }

    private val voiceSearchLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { res ->
        val spoken = if (res.resultCode == Activity.RESULT_OK) {
            res.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.trim()
        } else {
            null
        }
        pendingVoiceCallback?.invoke(spoken)
        pendingVoiceCallback = null
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
            MODE_VOICE_SEARCH -> {
                val speechIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(
                        RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                        RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                    )
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
                    putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak or hum song / video title")
                }
                val launched = runCatching { voiceSearchLauncher.launch(speechIntent) }.isSuccess
                if (!launched) {
                    pendingVoiceCallback?.invoke(null)
                    pendingVoiceCallback = null
                    finish()
                }
            }
            else -> pickImageLauncher.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
            )
        }
    }

    /**
     * Copies each user-picked SAF content URI into our local app cache and returns a readable
     * FileProvider URI (`content://${packageName}.fileprovider/shared_cache/...`). WebView
     * frequently fails reading transient DocumentProvider URIs after the picker finishes,
     * causing "Upload Failed" in Google AI Mode / ChatGPT unless cached locally first.
     */
    internal fun resolveWebUploadUris(uris: List<Uri>): List<Uri> {
        val out = ArrayList<Uri>(uris.size)
        val ts = System.currentTimeMillis()
        uris.forEachIndexed { idx, srcUri ->
            val cached = copyUriToUploadCache(srcUri, ts, idx)
            out.add(cached ?: srcUri)
        }
        return out
    }

    private fun copyUriToUploadCache(srcUri: Uri, timestampMs: Long, index: Int): Uri? {
        return runCatching {
            val mime = contentResolver.getType(srcUri)
            val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime)
                ?.takeIf { it.isNotBlank() }
                ?: inferExtensionFromUri(srcUri)
                ?: "bin"
            val destFile = File(cacheDir, "upload_${timestampMs}_$index.$ext")
            contentResolver.openInputStream(srcUri)?.use { input ->
                destFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            } ?: return null
            if (!destFile.exists() || destFile.length() == 0L) return null
            FileProvider.getUriForFile(this, "${packageName}.fileprovider", destFile)
        }.getOrNull()
    }

    private fun inferExtensionFromUri(uri: Uri): String? {
        val seg = uri.lastPathSegment ?: return null
        val dot = seg.lastIndexOf('.')
        if (dot < 0 || dot >= seg.length - 1) return null
        return seg.substring(dot + 1).lowercase(Locale.ROOT).takeIf { it.length in 1..6 }
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
        const val MODE_VOICE_SEARCH = "voice_search"

        @Volatile
        internal var pendingCallback: ((Uri, Boolean) -> Unit)? = null

        @Volatile
        internal var pendingFolderCallback: ((Uri) -> Unit)? = null

        @Volatile
        internal var pendingWebFileCallback: ValueCallback<Array<Uri>>? = null

        @Volatile
        internal var pendingVoiceCallback: ((String?) -> Unit)? = null
    }
}
