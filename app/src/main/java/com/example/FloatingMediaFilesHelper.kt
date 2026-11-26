package com.example

import android.annotation.SuppressLint
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.drawable.GradientDrawable
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.text.TextUtils
import android.text.format.Formatter
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import android.widget.VideoView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/**
 * All-in-One Floating Media Player & File Manager:
 * - Browses Downloads, Pictures, Movies, Music, and Documents via MediaStore on Dispatchers.IO,
 *   public directory reads where permitted, Android Photo Picker, and SAF (OpenDocument / OpenDocumentTree).
 * - Built-in Image Viewer with pinch-to-zoom & fit-to-window.
 * - Built-in Video Player (VideoView + Play/Pause, SeekBar, Mute).
 * - Built-in Audio/Voice Player (MediaPlayer + Play/Pause, Next/Prev, SeekBar, background playback).
 */
object FloatingMediaFilesHelper {

    enum class MediaFolderCategory(val label: String, val folderSegment: String) {
        DOWNLOADS("Downloads", Environment.DIRECTORY_DOWNLOADS),
        PICTURES("Pictures", Environment.DIRECTORY_PICTURES),
        MOVIES("Movies", Environment.DIRECTORY_MOVIES),
        MUSIC("Music", Environment.DIRECTORY_MUSIC),
        DOCUMENTS("Documents", Environment.DIRECTORY_DOCUMENTS)
    }

    enum class MediaFileType {
        IMAGE,
        VIDEO,
        AUDIO,
        DOCUMENT
    }

    data class MediaItem(
        val displayName: String,
        val uri: Uri,
        val mimeType: String,
        val sizeBytes: Long,
        val fileType: MediaFileType,
        val folderLabel: String
    )

    private const val PREF_SAF_TREES = "media_saf_folder_uris"

    private val IMAGE_EXTS = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "heic")
    private val VIDEO_EXTS = setOf("mp4", "mkv", "webm", "3gp", "mov", "avi")
    private val AUDIO_EXTS = setOf("mp3", "m4a", "wav", "ogg", "aac", "flac", "opus")

    private fun dp(context: Context, value: Float): Int =
        (value * context.resources.displayMetrics.density.coerceAtLeast(1f)).toInt()

    fun classifyFileType(displayName: String, mimeType: String = ""): MediaFileType {
        val lowerMime = mimeType.lowercase(Locale.ROOT)
        if (lowerMime.startsWith("image/")) return MediaFileType.IMAGE
        if (lowerMime.startsWith("video/")) return MediaFileType.VIDEO
        if (lowerMime.startsWith("audio/")) return MediaFileType.AUDIO
        val ext = displayName.substringAfterLast('.', "").lowercase(Locale.ROOT)
        return when {
            ext in IMAGE_EXTS -> MediaFileType.IMAGE
            ext in VIDEO_EXTS -> MediaFileType.VIDEO
            ext in AUDIO_EXTS -> MediaFileType.AUDIO
            else -> MediaFileType.DOCUMENT
        }
    }

    fun formatDurationMs(ms: Long): String {
        val totalSec = (ms.coerceAtLeast(0L) / 1000L).toInt()
        val mins = totalSec / 60
        val secs = totalSec % 60
        return String.format(Locale.US, "%02d:%02d", mins, secs)
    }

    fun saveSafTreeUri(context: Context, treeUri: Uri) {
        val prefs = context.getSharedPreferences(SelectionToolbarHelper.PREFS_NAME, Context.MODE_PRIVATE)
        val existing = prefs.getStringSet(PREF_SAF_TREES, emptySet())?.toMutableSet() ?: mutableSetOf()
        existing.add(treeUri.toString())
        prefs.edit().putStringSet(PREF_SAF_TREES, existing).apply()
    }

    private fun collectFilesRecursive(
        dir: File,
        maxDepth: Int = 2,
        currentDepth: Int = 0,
        out: MutableList<File>
    ) {
        val children = runCatching { dir.listFiles() }.getOrNull() ?: return
        for (child in children) {
            if (child.isHidden) continue
            if (child.isFile) {
                out.add(child)
            } else if (child.isDirectory && currentDepth < maxDepth && child.canRead()) {
                collectFilesRecursive(child, maxDepth, currentDepth + 1, out)
            }
        }
    }

    fun listFilesForFolder(context: Context, category: MediaFolderCategory): List<MediaItem> {
        val items = ArrayList<MediaItem>()
        val seenUris = HashSet<String>()

        fun addUnique(item: MediaItem) {
            val key = "${item.displayName.lowercase(Locale.ROOT)}|${item.sizeBytes}|${item.uri}"
            if (seenUris.add(key)) {
                items.add(item)
            }
        }

        // 1. Direct accessible directory listing (works on devices/emulators where folder is readable)
        @Suppress("DEPRECATION")
        val publicDirs = listOfNotNull(
            runCatching { Environment.getExternalStoragePublicDirectory(category.folderSegment) }.getOrNull(),
            runCatching { File(Environment.getExternalStorageDirectory(), category.folderSegment) }.getOrNull()
        )
        publicDirs.distinctBy { it.absolutePath }.forEach { dir ->
            if (dir.exists() && dir.isDirectory && dir.canRead()) {
                val collected = ArrayList<File>()
                collectFilesRecursive(dir, maxDepth = 2, out = collected)
                collected.sortedByDescending { it.lastModified() }.forEach { file ->
                    val type = classifyFileType(file.name)
                    addUnique(
                        MediaItem(
                            displayName = file.name,
                            uri = Uri.fromFile(file),
                            mimeType = guessMimeForType(type, file.name),
                            sizeBytes = file.length(),
                            fileType = type,
                            folderLabel = file.parentFile?.name ?: category.label
                        )
                    )
                }
            }
        }

        // 2. Scoped-storage safe MediaStore queries filtered by folder segment
        queryMediaStoreFolder(context, category).forEach { addUnique(it) }

        // 3. Any user-granted SAF document tree folders
        val prefs = context.getSharedPreferences(SelectionToolbarHelper.PREFS_NAME, Context.MODE_PRIVATE)
        val treeUris = prefs.getStringSet(PREF_SAF_TREES, emptySet()).orEmpty()
        treeUris.forEach { raw ->
            runCatching {
                val treeUri = Uri.parse(raw)
                querySafTree(context, treeUri, category.label).forEach { addUnique(it) }
            }
        }

        return items.sortedWith(compareBy<MediaItem> { it.fileType.ordinal }.thenBy { it.displayName.lowercase(Locale.ROOT) })
    }

    private fun guessMimeForType(type: MediaFileType, name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        return when (type) {
            MediaFileType.IMAGE -> if (ext == "png") "image/png" else "image/jpeg"
            MediaFileType.VIDEO -> "video/mp4"
            MediaFileType.AUDIO -> "audio/mpeg"
            MediaFileType.DOCUMENT -> if (ext == "pdf") "application/pdf" else "text/plain"
        }
    }

    private fun queryMediaStoreFolder(context: Context, category: MediaFolderCategory): List<MediaItem> {
        val result = ArrayList<MediaItem>()
        val targetCollections: List<Uri> = buildList {
            when (category) {
                MediaFolderCategory.PICTURES -> add(MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
                MediaFolderCategory.MOVIES -> add(MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
                MediaFolderCategory.MUSIC -> add(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI)
                MediaFolderCategory.DOWNLOADS -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        add(MediaStore.Downloads.EXTERNAL_CONTENT_URI)
                    }
                    add(MediaStore.Files.getContentUri("external"))
                }
                MediaFolderCategory.DOCUMENTS -> add(MediaStore.Files.getContentUri("external"))
            }
        }

        val segmentLower = category.folderSegment.lowercase(Locale.ROOT)
        for (collectionUri in targetCollections) {
            runCatching {
                val projection = buildList {
                    add(MediaStore.MediaColumns._ID)
                    add(MediaStore.MediaColumns.DISPLAY_NAME)
                    add(MediaStore.MediaColumns.MIME_TYPE)
                    add(MediaStore.MediaColumns.SIZE)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        add(MediaStore.MediaColumns.RELATIVE_PATH)
                    } else {
                        @Suppress("DEPRECATION")
                        add(MediaStore.MediaColumns.DATA)
                    }
                }.toTypedArray()

                context.contentResolver.query(
                    collectionUri,
                    projection,
                    null,
                    null,
                    "${MediaStore.MediaColumns.DATE_MODIFIED} DESC"
                )?.use { cursor ->
                    val idIdx = cursor.getColumnIndex(MediaStore.MediaColumns._ID)
                    val nameIdx = cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
                    val mimeIdx = cursor.getColumnIndex(MediaStore.MediaColumns.MIME_TYPE)
                    val sizeIdx = cursor.getColumnIndex(MediaStore.MediaColumns.SIZE)
                    val pathIdx = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        cursor.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH)
                    } else {
                        @Suppress("DEPRECATION")
                        cursor.getColumnIndex(MediaStore.MediaColumns.DATA)
                    }

                    while (cursor.moveToNext()) {
                        val id = if (idIdx >= 0) cursor.getLong(idIdx) else continue
                        val name = if (nameIdx >= 0) cursor.getString(nameIdx).orEmpty() else ""
                        if (name.isBlank()) continue
                        val mime = if (mimeIdx >= 0) cursor.getString(mimeIdx).orEmpty() else ""
                        val size = if (sizeIdx >= 0) cursor.getLong(sizeIdx) else 0L
                        val relOrPath = if (pathIdx >= 0) cursor.getString(pathIdx).orEmpty().lowercase(Locale.ROOT) else ""
                        if (relOrPath.isNotEmpty() && !relOrPath.contains(segmentLower)) {
                            continue
                        }
                        val itemUri = ContentUris.withAppendedId(collectionUri, id)
                        val type = classifyFileType(name, mime)
                        result.add(
                            MediaItem(
                                displayName = name,
                                uri = itemUri,
                                mimeType = mime.ifBlank { guessMimeForType(type, name) },
                                sizeBytes = size,
                                fileType = type,
                                folderLabel = category.label
                            )
                        )
                    }
                }
            }
        }
        return result
    }

    private fun querySafTree(context: Context, treeUri: Uri, folderLabel: String): List<MediaItem> {
        val result = ArrayList<MediaItem>()
        val docId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull() ?: return result
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE
        )
        runCatching {
            context.contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
                val idIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val mimeIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
                val sizeIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
                while (cursor.moveToNext()) {
                    val childDocId = if (idIdx >= 0) cursor.getString(idIdx) else continue
                    val name = if (nameIdx >= 0) cursor.getString(nameIdx).orEmpty() else continue
                    val mime = if (mimeIdx >= 0) cursor.getString(mimeIdx).orEmpty() else ""
                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) continue
                    val size = if (sizeIdx >= 0) cursor.getLong(sizeIdx) else 0L
                    val fileUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, childDocId)
                    val type = classifyFileType(name, mime)
                    result.add(
                        MediaItem(
                            displayName = name,
                            uri = fileUri,
                            mimeType = mime.ifBlank { guessMimeForType(type, name) },
                            sizeBytes = size,
                            fileType = type,
                            folderLabel = folderLabel
                        )
                    )
                }
            }
        }
        return result
    }

    /**
     * Fit-to-window ImageView supporting pinch-to-zoom, pan when zoomed, and double-tap zoom reset.
     */
    @SuppressLint("AppCompatCustomView", "ClickableViewAccessibility")
    class PinchZoomImageView(context: Context) : ImageView(context) {
        private val matrixValues = FloatArray(9)
        private val drawMatrix = Matrix()
        private var currentScale = 1f
        private var lastX = 0f
        private var lastY = 0f
        private var isDragging = false

        private val scaleDetector = ScaleGestureDetector(
            context,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    val prevScale = currentScale
                    val factor = detector.scaleFactor
                    val nextScale = (currentScale * factor).coerceIn(1f, 5f)
                    val applied = nextScale / prevScale
                    if (nextScale > 1f) {
                        if (scaleType != ScaleType.MATRIX) {
                            drawMatrix.set(imageMatrix)
                            scaleType = ScaleType.MATRIX
                        }
                        drawMatrix.postScale(applied, applied, detector.focusX, detector.focusY)
                        imageMatrix = drawMatrix
                    } else {
                        resetZoom()
                    }
                    currentScale = nextScale
                    return true
                }
            }
        )

        private val gestureDetector = GestureDetector(
            context,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onDoubleTap(e: MotionEvent): Boolean {
                    if (currentScale > 1.05f) {
                        resetZoom()
                    } else {
                        drawMatrix.set(imageMatrix)
                        scaleType = ScaleType.MATRIX
                        drawMatrix.postScale(2.2f, 2.2f, e.x, e.y)
                        imageMatrix = drawMatrix
                        currentScale = 2.2f
                    }
                    return true
                }
            }
        )

        init {
            scaleType = ScaleType.FIT_CENTER
        }

        fun resetZoom() {
            currentScale = 1f
            drawMatrix.reset()
            scaleType = ScaleType.FIT_CENTER
        }

        fun getZoomScale(): Float {
            drawMatrix.getValues(matrixValues)
            return currentScale
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            scaleDetector.onTouchEvent(event)
            gestureDetector.onTouchEvent(event)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    lastX = event.x
                    lastY = event.y
                    isDragging = true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (isDragging && currentScale > 1.02f && !scaleDetector.isInProgress) {
                        val dx = event.x - lastX
                        val dy = event.y - lastY
                        drawMatrix.postTranslate(dx, dy)
                        imageMatrix = drawMatrix
                        lastX = event.x
                        lastY = event.y
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    isDragging = false
                }
            }
            return true
        }
    }

    /**
     * Opens the unified All-in-One Floating Media Player & File Manager window.
     */
    fun openFloatingMediaFiles(
        context: Context,
        manager: FloatingWindowManager,
        accentColor: Int
    ): FloatingWindowManager.ManagedWindow? {
        return manager.openOrFocusWindow(
            windowId = "floating_media_files",
            title = context.getString(R.string.floating_window_title_media_files),
            accentColor = accentColor,
            iconRes = android.R.drawable.ic_menu_gallery,
            requiresTextInput = false
        ) { managed ->
            val container = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(context, 6f), dp(context, 6f), dp(context, 6f), dp(context, 6f))
            }

            val mainHandler = Handler(Looper.getMainLooper())
            val mediaJob = SupervisorJob()
            val mediaScope = CoroutineScope(Dispatchers.Main.immediate + mediaJob)
            var currentCategory = MediaFolderCategory.DOWNLOADS
            var currentFolderItems: List<MediaItem> = emptyList()
            var audioQueue: List<MediaItem> = emptyList()
            var currentAudioIndex = -1
            var mediaPlayer: MediaPlayer? = null
            var isVideoMuted = false
            var activeVideoPlayer: android.media.MediaPlayer? = null
            var isReleased = false
            var folderLoadSeq = 0

            // --- 1. File Browser View ---
            val browserView = LinearLayout(context).apply {
                tag = "fw_media_browser_view"
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    0,
                    1f
                )
            }

            val folderChipsStrip = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val folderChipsScroll = HorizontalScrollView(context).apply {
                isHorizontalScrollBarEnabled = false
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = dp(context, 6f)
                }
                addView(folderChipsStrip)
            }

            val fileListRecycler = RecyclerView(context).apply {
                tag = "fw_media_file_list"
                layoutManager = LinearLayoutManager(context)
                setHasFixedSize(false)
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            }
            val emptyCard = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                visibility = View.GONE
                setPadding(dp(context, 12f), dp(context, 16f), dp(context, 12f), dp(context, 16f))
                background = GradientDrawable().apply {
                    cornerRadius = dp(context, 10f).toFloat()
                    setColor(Color.parseColor("#1E293B"))
                }
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.TOP
                )
            }
            val fileListHost = FrameLayout(context).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    0,
                    1f
                )
                addView(fileListRecycler)
                addView(emptyCard)
            }

            val miniAudioBar = LinearLayout(context).apply {
                tag = "fw_media_mini_audio_bar"
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                visibility = View.GONE
                setPadding(dp(context, 8f), dp(context, 6f), dp(context, 8f), dp(context, 6f))
                background = GradientDrawable().apply {
                    cornerRadius = dp(context, 10f).toFloat()
                    setColor(Color.parseColor("#1E293B"))
                    setStroke(1, accentColor)
                }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = dp(context, 6f)
                }
            }
            val tvMiniAudioTitle = TextView(context).apply {
                text = "Playing audio..."
                textSize = 11.5f
                setTextColor(Color.WHITE)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val btnMiniPlayPause = TextView(context).apply {
                tag = "fw_media_mini_play_pause"
                text = "⏸"
                textSize = 14f
                setTextColor(accentColor)
                setPadding(dp(context, 10f), dp(context, 4f), dp(context, 10f), dp(context, 4f))
            }
            val btnMiniExpand = TextView(context).apply {
                tag = "fw_media_mini_expand"
                text = "Player"
                textSize = 11f
                setTextColor(Color.BLACK)
                setPadding(dp(context, 8f), dp(context, 4f), dp(context, 8f), dp(context, 4f))
                background = GradientDrawable().apply {
                    cornerRadius = dp(context, 6f).toFloat()
                    setColor(accentColor)
                }
            }
            miniAudioBar.addView(tvMiniAudioTitle)
            miniAudioBar.addView(btnMiniPlayPause)
            miniAudioBar.addView(btnMiniExpand)

            val btnGrantMediaPerm = TextView(context).apply {
                tag = "fw_media_grant_perm"
                text = "🔓 Grant Media Permission"
                textSize = 12f
                setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
                setTextColor(Color.BLACK)
                gravity = Gravity.CENTER
                setPadding(dp(context, 12f), dp(context, 9f), dp(context, 12f), dp(context, 9f))
                background = GradientDrawable().apply {
                    cornerRadius = dp(context, 10f).toFloat()
                    setColor(accentColor)
                }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = dp(context, 6f)
                }
                visibility = if (MediaPermissionActivity.hasAllMediaPermissions(context)) View.GONE else View.VISIBLE
            }

            browserView.addView(folderChipsScroll)
            browserView.addView(btnGrantMediaPerm)
            browserView.addView(fileListHost)
            browserView.addView(miniAudioBar)

            // --- 2. Image Viewer ---
            val imageViewerView = LinearLayout(context).apply {
                tag = "fw_media_image_viewer"
                orientation = LinearLayout.VERTICAL
                visibility = View.GONE
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    0,
                    1f
                )
            }
            val imageTopRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = dp(context, 4f)
                }
            }
            val btnImageBack = TextView(context).apply {
                tag = "fw_media_image_back"
                text = "← Files"
                textSize = 12f
                setTextColor(accentColor)
                setPadding(dp(context, 8f), dp(context, 4f), dp(context, 8f), dp(context, 4f))
                background = GradientDrawable().apply {
                    cornerRadius = dp(context, 8f).toFloat()
                    setColor(Color.parseColor("#1E293B"))
                }
            }
            val tvImageTitle = TextView(context).apply {
                textSize = 12f
                setTextColor(Color.WHITE)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = dp(context, 8f)
                }
            }
            imageTopRow.addView(btnImageBack)
            imageTopRow.addView(tvImageTitle)

            val zoomImageView = PinchZoomImageView(context).apply {
                tag = "fw_media_zoom_image"
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    0,
                    1f
                )
            }
            imageViewerView.addView(imageTopRow)
            imageViewerView.addView(zoomImageView)

            // --- 3. Video Player View ---
            val videoPlayerView = LinearLayout(context).apply {
                tag = "fw_media_video_player"
                orientation = LinearLayout.VERTICAL
                visibility = View.GONE
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    0,
                    1f
                )
            }
            val videoTopRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = dp(context, 4f)
                }
            }
            val btnVideoBack = TextView(context).apply {
                tag = "fw_media_video_back"
                text = "← Files"
                textSize = 12f
                setTextColor(accentColor)
                setPadding(dp(context, 8f), dp(context, 4f), dp(context, 8f), dp(context, 4f))
                background = GradientDrawable().apply {
                    cornerRadius = dp(context, 8f).toFloat()
                    setColor(Color.parseColor("#1E293B"))
                }
            }
            val tvVideoTitle = TextView(context).apply {
                textSize = 12f
                setTextColor(Color.WHITE)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = dp(context, 8f)
                }
            }
            videoTopRow.addView(btnVideoBack)
            videoTopRow.addView(tvVideoTitle)

            val videoHost = FrameLayout(context).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    0,
                    1f
                )
                setBackgroundColor(Color.BLACK)
            }
            val videoView = VideoView(context).apply {
                tag = "fw_media_video_view"
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    Gravity.CENTER
                )
            }
            videoHost.addView(videoView)

            val videoSeekBar = SeekBar(context).apply {
                tag = "fw_video_seekbar"
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = dp(context, 4f)
                }
            }
            val videoControlsRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(context, 4f), dp(context, 2f), dp(context, 4f), dp(context, 2f))
            }
            val btnVideoPlayPause = TextView(context).apply {
                tag = "fw_video_play_pause"
                text = "⏸ Pause"
                textSize = 11.5f
                setTextColor(Color.BLACK)
                setPadding(dp(context, 10f), dp(context, 5f), dp(context, 10f), dp(context, 5f))
                background = GradientDrawable().apply {
                    cornerRadius = dp(context, 8f).toFloat()
                    setColor(accentColor)
                }
            }
            val btnVideoMute = TextView(context).apply {
                tag = "fw_video_mute"
                text = "🔊 Sound"
                textSize = 11.5f
                setTextColor(Color.WHITE)
                setPadding(dp(context, 10f), dp(context, 5f), dp(context, 10f), dp(context, 5f))
                background = GradientDrawable().apply {
                    cornerRadius = dp(context, 8f).toFloat()
                    setColor(Color.parseColor("#1E293B"))
                }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    marginStart = dp(context, 6f)
                }
            }
            val tvVideoTime = TextView(context).apply {
                text = "00:00 / 00:00"
                textSize = 11f
                setTextColor(Color.parseColor("#94A3B8"))
                gravity = Gravity.END
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            videoControlsRow.addView(btnVideoPlayPause)
            videoControlsRow.addView(btnVideoMute)
            videoControlsRow.addView(tvVideoTime)

            videoPlayerView.addView(videoTopRow)
            videoPlayerView.addView(videoHost)
            videoPlayerView.addView(videoSeekBar)
            videoPlayerView.addView(videoControlsRow)

            // --- 4. Audio / Voice Player View ---
            val audioPlayerView = LinearLayout(context).apply {
                tag = "fw_media_audio_player"
                orientation = LinearLayout.VERTICAL
                visibility = View.GONE
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    0,
                    1f
                )
            }
            val audioTopRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = dp(context, 10f)
                }
            }
            val btnAudioBack = TextView(context).apply {
                tag = "fw_media_audio_back"
                text = "← Files"
                textSize = 12f
                setTextColor(accentColor)
                setPadding(dp(context, 8f), dp(context, 4f), dp(context, 8f), dp(context, 4f))
                background = GradientDrawable().apply {
                    cornerRadius = dp(context, 8f).toFloat()
                    setColor(Color.parseColor("#1E293B"))
                }
            }
            val tvAudioHeader = TextView(context).apply {
                text = "Music & Voice Player"
                textSize = 12f
                setTextColor(Color.parseColor("#94A3B8"))
                gravity = Gravity.END
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            audioTopRow.addView(btnAudioBack)
            audioTopRow.addView(tvAudioHeader)

            val audioCard = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(dp(context, 14f), dp(context, 16f), dp(context, 14f), dp(context, 16f))
                background = GradientDrawable().apply {
                    cornerRadius = dp(context, 12f).toFloat()
                    setColor(Color.parseColor("#1E293B"))
                }
            }
            val tvAudioIcon = TextView(context).apply {
                text = "🎵"
                textSize = 36f
            }
            val tvAudioTitle = TextView(context).apply {
                tag = "fw_audio_title"
                text = "No Track Loaded"
                textSize = 14f
                setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = dp(context, 8f)
                    bottomMargin = dp(context, 10f)
                }
            }
            val audioSeekBar = SeekBar(context).apply {
                tag = "fw_audio_seekbar"
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }
            val tvAudioTime = TextView(context).apply {
                text = "00:00 / 00:00"
                textSize = 11f
                setTextColor(Color.parseColor("#94A3B8"))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = dp(context, 4f)
                    bottomMargin = dp(context, 12f)
                }
            }
            val audioButtonsRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
            }
            fun makeAudioBtn(label: String, btnTag: String, primary: Boolean = false): TextView {
                return TextView(context).apply {
                    tag = btnTag
                    text = label
                    textSize = if (primary) 13f else 12.5f
                    setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
                    setTextColor(if (primary) Color.BLACK else Color.WHITE)
                    gravity = Gravity.CENTER
                    setPadding(dp(context, 14f), dp(context, 8f), dp(context, 14f), dp(context, 8f))
                    background = GradientDrawable().apply {
                        cornerRadius = dp(context, 10f).toFloat()
                        setColor(if (primary) accentColor else Color.parseColor("#0F172A"))
                    }
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        marginStart = dp(context, 6f)
                        marginEnd = dp(context, 6f)
                    }
                }
            }
            val btnAudioPrev = makeAudioBtn("⏮ Prev", "fw_audio_prev")
            val btnAudioPlayPause = makeAudioBtn("▶ Play", "fw_audio_play_pause", primary = true)
            val btnAudioNext = makeAudioBtn("Next ⏭", "fw_audio_next")
            audioButtonsRow.addView(btnAudioPrev)
            audioButtonsRow.addView(btnAudioPlayPause)
            audioButtonsRow.addView(btnAudioNext)

            audioCard.addView(tvAudioIcon)
            audioCard.addView(tvAudioTitle)
            audioCard.addView(audioSeekBar)
            audioCard.addView(tvAudioTime)
            audioCard.addView(audioButtonsRow)
            audioPlayerView.addView(audioTopRow)
            audioPlayerView.addView(audioCard)

            // --- 5. Document Text Viewer ---
            val docViewerView = LinearLayout(context).apply {
                tag = "fw_media_doc_viewer"
                orientation = LinearLayout.VERTICAL
                visibility = View.GONE
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    0,
                    1f
                )
            }
            val docTopRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = dp(context, 4f)
                }
            }
            val btnDocBack = TextView(context).apply {
                tag = "fw_media_doc_back"
                text = "← Files"
                textSize = 12f
                setTextColor(accentColor)
                setPadding(dp(context, 8f), dp(context, 4f), dp(context, 8f), dp(context, 4f))
                background = GradientDrawable().apply {
                    cornerRadius = dp(context, 8f).toFloat()
                    setColor(Color.parseColor("#1E293B"))
                }
            }
            val tvDocTitle = TextView(context).apply {
                textSize = 12f
                setTextColor(Color.WHITE)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = dp(context, 8f)
                }
            }
            docTopRow.addView(btnDocBack)
            docTopRow.addView(tvDocTitle)
            val tvDocBody = TextView(context).apply {
                tag = "fw_media_doc_text"
                textSize = 12f
                setTextColor(Color.parseColor("#E2E8F0"))
                setPadding(dp(context, 10f), dp(context, 10f), dp(context, 10f), dp(context, 10f))
            }
            val docScroll = ScrollView(context).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    0,
                    1f
                )
                background = GradientDrawable().apply {
                    cornerRadius = dp(context, 10f).toFloat()
                    setColor(Color.parseColor("#1E293B"))
                }
                addView(tvDocBody)
            }
            docViewerView.addView(docTopRow)
            docViewerView.addView(docScroll)

            fun showMode(target: View) {
                browserView.visibility = if (target === browserView) View.VISIBLE else View.GONE
                imageViewerView.visibility = if (target === imageViewerView) View.VISIBLE else View.GONE
                videoPlayerView.visibility = if (target === videoPlayerView) View.VISIBLE else View.GONE
                audioPlayerView.visibility = if (target === audioPlayerView) View.VISIBLE else View.GONE
                docViewerView.visibility = if (target === docViewerView) View.VISIBLE else View.GONE
                if (target !== videoPlayerView) {
                    runCatching { if (videoView.isPlaying) videoView.pause() }
                }
            }

            fun updateMiniAudioState() {
                val mp = mediaPlayer
                val track = audioQueue.getOrNull(currentAudioIndex)
                if (mp != null && track != null) {
                    miniAudioBar.visibility = View.VISIBLE
                    tvMiniAudioTitle.text = "🎵 ${track.displayName}"
                    val playing = runCatching { mp.isPlaying }.getOrDefault(false)
                    btnMiniPlayPause.text = if (playing) "⏸" else "▶"
                    btnAudioPlayPause.text = if (playing) "⏸ Pause" else "▶ Play"
                } else {
                    miniAudioBar.visibility = View.GONE
                }
            }

            fun playAudioTrackAt(idx: Int) {
                val item = audioQueue.getOrNull(idx) ?: return
                currentAudioIndex = idx
                showMode(audioPlayerView)
                tvAudioTitle.text = item.displayName
                tvAudioTime.text = "00:00 / 00:00"
                audioSeekBar.progress = 0

                runCatching {
                    mediaPlayer?.stop()
                    mediaPlayer?.release()
                }
                mediaPlayer = null

                val player = MediaPlayer().apply {
                    setAudioAttributes(
                        AudioAttributes.Builder()
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .build()
                    )
                    setOnPreparedListener { mp ->
                        audioSeekBar.max = mp.duration.coerceAtLeast(1)
                        mp.start()
                        updateMiniAudioState()
                    }
                    setOnCompletionListener {
                        if (currentAudioIndex + 1 < audioQueue.size) {
                            playAudioTrackAt(currentAudioIndex + 1)
                        } else {
                            updateMiniAudioState()
                        }
                    }
                    setOnErrorListener { _, _, _ ->
                        Toast.makeText(context, "Unable to play ${item.displayName}", Toast.LENGTH_SHORT).show()
                        true
                    }
                }
                val loaded = runCatching {
                    player.setDataSource(context, item.uri)
                    player.prepareAsync()
                    true
                }.getOrDefault(false)
                if (loaded) {
                    mediaPlayer = player
                    updateMiniAudioState()
                } else {
                    runCatching { player.release() }
                    Toast.makeText(context, "Unable to open audio file.", Toast.LENGTH_SHORT).show()
                }
            }

            fun openMediaItem(item: MediaItem) {
                when (item.fileType) {
                    MediaFileType.IMAGE -> {
                        showMode(imageViewerView)
                        zoomImageView.resetZoom()
                        tvImageTitle.text = item.displayName
                        val bmp = runCatching {
                            context.contentResolver.openInputStream(item.uri)?.use { stream ->
                                BitmapFactory.decodeStream(stream)
                            }
                        }.getOrNull()
                        if (bmp != null) {
                            zoomImageView.setImageBitmap(bmp)
                        } else {
                            zoomImageView.setImageURI(item.uri)
                        }
                    }
                    MediaFileType.VIDEO -> {
                        showMode(videoPlayerView)
                        tvVideoTitle.text = item.displayName
                        isVideoMuted = false
                        btnVideoMute.text = "🔊 Sound"
                        btnVideoPlayPause.text = "⏸ Pause"
                        videoView.setOnPreparedListener { mp ->
                            activeVideoPlayer = mp
                            mp.isLooping = false
                            videoSeekBar.max = mp.duration.coerceAtLeast(1)
                            videoView.start()
                        }
                        videoView.setOnErrorListener { _, _, _ ->
                            Toast.makeText(context, "Cannot play video ${item.displayName}", Toast.LENGTH_SHORT).show()
                            true
                        }
                        videoView.setVideoURI(item.uri)
                    }
                    MediaFileType.AUDIO -> {
                        val audioFromFolder = currentFolderItems.filter { it.fileType == MediaFileType.AUDIO }
                        audioQueue = if (audioFromFolder.any { it.uri == item.uri }) {
                            audioFromFolder
                        } else {
                            listOf(item) + audioFromFolder
                        }
                        val idx = audioQueue.indexOfFirst { it.uri == item.uri }.coerceAtLeast(0)
                        playAudioTrackAt(idx)
                    }
                    MediaFileType.DOCUMENT -> {
                        showMode(docViewerView)
                        tvDocTitle.text = item.displayName
                        val textContent = runCatching {
                            context.contentResolver.openInputStream(item.uri)?.use { stream ->
                                val buf = ByteArray(64 * 1024)
                                val read = stream.read(buf)
                                if (read > 0) String(buf, 0, read, Charsets.UTF_8) else "(Empty document)"
                            } ?: "Preview unavailable for ${item.displayName}"
                        }.getOrElse { "Preview unavailable for ${item.displayName}" }
                        tvDocBody.text = textContent
                    }
                }
            }

            lateinit var renderFolderView: () -> Unit

            fun openPicker(mode: String) {
                if (mode == ViewerPickerActivity.MODE_FOLDER) {
                    ViewerPickerActivity.pendingFolderCallback = { treeUri ->
                        saveSafTreeUri(context, treeUri)
                        mainHandler.post {
                            if (!isReleased) renderFolderView()
                        }
                    }
                } else {
                    ViewerPickerActivity.pendingCallback = { uri, isImg ->
                        val name = uri.lastPathSegment?.substringAfterLast('/') ?: "Selected Media"
                        val mime = runCatching { context.contentResolver.getType(uri) }.getOrNull().orEmpty()
                        val type = if (isImg) MediaFileType.IMAGE else classifyFileType(name, mime)
                        openMediaItem(
                            MediaItem(
                                displayName = name,
                                uri = uri,
                                mimeType = mime,
                                sizeBytes = 0L,
                                fileType = type,
                                folderLabel = "Picked"
                            )
                        )
                    }
                }
                context.startActivity(
                    Intent(context, ViewerPickerActivity::class.java).apply {
                        putExtra(ViewerPickerActivity.EXTRA_PICK_MODE, mode)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                )
            }

            fun renderFolderChips() {
                folderChipsStrip.removeAllViews()
                MediaFolderCategory.entries.forEach { cat ->
                    val selected = cat == currentCategory
                    val chip = TextView(context).apply {
                        tag = "fw_media_folder_${cat.name.lowercase(Locale.ROOT)}"
                        text = cat.label
                        textSize = 11.5f
                        setTypeface(android.graphics.Typeface.DEFAULT, if (selected) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
                        setTextColor(if (selected) Color.BLACK else Color.WHITE)
                        setPadding(dp(context, 10f), dp(context, 6f), dp(context, 10f), dp(context, 6f))
                        background = GradientDrawable().apply {
                            cornerRadius = dp(context, 10f).toFloat()
                            setColor(if (selected) accentColor else Color.parseColor("#1E293B"))
                        }
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        ).apply {
                            marginEnd = dp(context, 6f)
                        }
                        setOnClickListener {
                            currentCategory = cat
                            renderFolderChips()
                            renderFolderView()
                        }
                    }
                    folderChipsStrip.addView(chip)
                }
            }

            val tvEmptyState = TextView(context).apply {
                tag = "fw_media_empty_state"
                textSize = 12f
                setTextColor(Color.parseColor("#CBD5E1"))
                gravity = Gravity.CENTER
            }
            val actionsRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = dp(context, 10f)
                }
            }
            val btnPickImg = Button(context).apply {
                tag = "fw_media_pick_photo"
                text = "Pick Photo"
                textSize = 11f
                setTextColor(Color.BLACK)
                backgroundTintList = null
                background = GradientDrawable().apply {
                    cornerRadius = dp(context, 8f).toFloat()
                    setColor(accentColor)
                }
                layoutParams = LinearLayout.LayoutParams(0, dp(context, 38f), 1f).apply {
                    marginEnd = dp(context, 6f)
                }
                setOnClickListener { openPicker(ViewerPickerActivity.MODE_IMAGE) }
            }
            val btnOpenMedia = Button(context).apply {
                tag = "fw_media_open_file"
                text = "Open Media/File"
                textSize = 11f
                setTextColor(Color.WHITE)
                backgroundTintList = null
                background = GradientDrawable().apply {
                    cornerRadius = dp(context, 8f).toFloat()
                    setColor(Color.parseColor("#334155"))
                }
                layoutParams = LinearLayout.LayoutParams(0, dp(context, 38f), 1f)
                setOnClickListener { openPicker(ViewerPickerActivity.MODE_MEDIA) }
            }
            actionsRow.addView(btnPickImg)
            actionsRow.addView(btnOpenMedia)
            emptyCard.addView(tvEmptyState)
            emptyCard.addView(actionsRow)

            class MediaFileViewHolder(
                val row: LinearLayout,
                val tvIcon: TextView,
                val tvName: TextView,
                val tvMeta: TextView
            ) : RecyclerView.ViewHolder(row)

            val mediaFileAdapter = object : RecyclerView.Adapter<MediaFileViewHolder>() {
                private var items: List<MediaItem> = emptyList()

                fun submitList(newItems: List<MediaItem>) {
                    items = newItems
                    notifyDataSetChanged()
                }

                override fun getItemCount(): Int = items.size

                override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MediaFileViewHolder {
                    val row = LinearLayout(context).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(dp(context, 10f), dp(context, 8f), dp(context, 10f), dp(context, 8f))
                        background = GradientDrawable().apply {
                            cornerRadius = dp(context, 8f).toFloat()
                            setColor(Color.parseColor("#1E293B"))
                        }
                        layoutParams = RecyclerView.LayoutParams(
                            RecyclerView.LayoutParams.MATCH_PARENT,
                            RecyclerView.LayoutParams.WRAP_CONTENT
                        ).apply {
                            bottomMargin = dp(context, 6f)
                        }
                    }
                    val tvIcon = TextView(context).apply {
                        textSize = 18f
                        setPadding(0, 0, dp(context, 10f), 0)
                    }
                    val infoCol = LinearLayout(context).apply {
                        orientation = LinearLayout.VERTICAL
                        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    }
                    val tvName = TextView(context).apply {
                        textSize = 12.5f
                        setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
                        setTextColor(Color.WHITE)
                        maxLines = 1
                        ellipsize = TextUtils.TruncateAt.END
                    }
                    val tvMeta = TextView(context).apply {
                        textSize = 10.5f
                        setTextColor(Color.parseColor("#94A3B8"))
                    }
                    infoCol.addView(tvName)
                    infoCol.addView(tvMeta)
                    row.addView(tvIcon)
                    row.addView(infoCol)
                    return MediaFileViewHolder(row, tvIcon, tvName, tvMeta)
                }

                override fun onBindViewHolder(holder: MediaFileViewHolder, position: Int) {
                    val item = items[position]
                    holder.row.tag = "fw_media_file_row_$position"
                    holder.tvIcon.text = when (item.fileType) {
                        MediaFileType.IMAGE -> "🖼"
                        MediaFileType.VIDEO -> "🎬"
                        MediaFileType.AUDIO -> "🎵"
                        MediaFileType.DOCUMENT -> "📄"
                    }
                    holder.tvName.text = item.displayName
                    val sizeStr = if (item.sizeBytes > 0) Formatter.formatShortFileSize(context, item.sizeBytes) else item.folderLabel
                    holder.tvMeta.text = "${item.fileType.name} • $sizeStr"
                    holder.row.setOnClickListener { openMediaItem(item) }
                }
            }
            fileListRecycler.adapter = mediaFileAdapter

            renderFolderView = {
                btnGrantMediaPerm.visibility = if (MediaPermissionActivity.hasAllMediaPermissions(context)) {
                    View.GONE
                } else {
                    View.VISIBLE
                }
                val seq = ++folderLoadSeq
                val activeCat = currentCategory
                mediaScope.launch {
                    val queried = withContext(Dispatchers.IO) {
                        listFilesForFolder(context, activeCat)
                    }
                    withContext(Dispatchers.Main) {
                        if (isReleased || seq != folderLoadSeq) return@withContext
                        btnGrantMediaPerm.visibility = if (MediaPermissionActivity.hasAllMediaPermissions(context)) {
                            View.GONE
                        } else {
                            View.VISIBLE
                        }
                        currentFolderItems = queried
                        mediaFileAdapter.submitList(currentFolderItems)
                        if (currentFolderItems.isEmpty()) {
                            fileListRecycler.visibility = View.GONE
                            emptyCard.visibility = View.VISIBLE
                            tvEmptyState.text = "No accessible files listed in ${activeCat.label}. Grant media permission or open via SAF:"
                        } else {
                            emptyCard.visibility = View.GONE
                            fileListRecycler.visibility = View.VISIBLE
                        }
                    }
                }
            }

            btnGrantMediaPerm.setOnClickListener {
                MediaPermissionActivity.launchPermissionPrompt(
                    context = context,
                    permissions = MediaPermissionActivity.requiredPermissionsForAllMedia()
                ) {
                    mainHandler.post {
                        if (!isReleased) renderFolderView()
                    }
                }
            }

            // Wire Back buttons & Player Controls
            btnImageBack.setOnClickListener { showMode(browserView) }
            btnVideoBack.setOnClickListener { showMode(browserView) }
            btnAudioBack.setOnClickListener { showMode(browserView) }
            btnDocBack.setOnClickListener { showMode(browserView) }
            btnMiniExpand.setOnClickListener { showMode(audioPlayerView) }

            val toggleAudioPlayback = {
                val mp = mediaPlayer
                if (mp != null) {
                    runCatching {
                        if (mp.isPlaying) mp.pause() else mp.start()
                    }
                    updateMiniAudioState()
                }
            }
            btnAudioPlayPause.setOnClickListener { toggleAudioPlayback() }
            btnMiniPlayPause.setOnClickListener { toggleAudioPlayback() }
            btnAudioPrev.setOnClickListener {
                if (audioQueue.isNotEmpty()) {
                    val prev = (currentAudioIndex - 1).coerceAtLeast(0)
                    playAudioTrackAt(prev)
                }
            }
            btnAudioNext.setOnClickListener {
                if (audioQueue.isNotEmpty()) {
                    val next = (currentAudioIndex + 1) % audioQueue.size
                    playAudioTrackAt(next)
                }
            }
            audioSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) runCatching { mediaPlayer?.seekTo(progress) }
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })

            btnVideoPlayPause.setOnClickListener {
                if (videoView.isPlaying) {
                    videoView.pause()
                    btnVideoPlayPause.text = "▶ Play"
                } else {
                    videoView.start()
                    btnVideoPlayPause.text = "⏸ Pause"
                }
            }
            btnVideoMute.setOnClickListener {
                isVideoMuted = !isVideoMuted
                val vol = if (isVideoMuted) 0f else 1f
                runCatching { activeVideoPlayer?.setVolume(vol, vol) }
                btnVideoMute.text = if (isVideoMuted) "🔇 Muted" else "🔊 Sound"
            }
            videoSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) runCatching { videoView.seekTo(progress) }
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })

            // Progress ticker for Video & Audio seekbars
            val ticker = object : Runnable {
                override fun run() {
                    if (isReleased) return
                    runCatching {
                        if (videoPlayerView.visibility == View.VISIBLE && videoView.duration > 0) {
                            val pos = videoView.currentPosition
                            val dur = videoView.duration
                            videoSeekBar.max = dur
                            videoSeekBar.progress = pos
                            tvVideoTime.text = "${formatDurationMs(pos.toLong())} / ${formatDurationMs(dur.toLong())}"
                        }
                        val mp = mediaPlayer
                        if (mp != null && mp.duration > 0) {
                            val pos = mp.currentPosition
                            val dur = mp.duration
                            audioSeekBar.max = dur
                            audioSeekBar.progress = pos
                            tvAudioTime.text = "${formatDurationMs(pos.toLong())} / ${formatDurationMs(dur.toLong())}"
                        }
                    }
                    mainHandler.postDelayed(this, 500L)
                }
            }
            mainHandler.postDelayed(ticker, 500L)

            // Configure Left Hamburger (☰) Menu Items
            managed.setMenuItems(
                listOf(
                    FloatingWindowManager.WindowMenuItem("files", "📁 File Browser", "fw_media_menu_files") {
                        showMode(browserView)
                    },
                    FloatingWindowManager.WindowMenuItem("pick_photo", "🖼 Pick Photo (Photo Picker)", "fw_media_menu_pick_photo") {
                        openPicker(ViewerPickerActivity.MODE_IMAGE)
                    },
                    FloatingWindowManager.WindowMenuItem("open_file", "📂 Open Media / File (SAF)", "fw_media_menu_open_file") {
                        openPicker(ViewerPickerActivity.MODE_MEDIA)
                    },
                    FloatingWindowManager.WindowMenuItem("link_folder", "🗂 Link Folder (SAF)", "fw_media_menu_link_folder") {
                        openPicker(ViewerPickerActivity.MODE_FOLDER)
                    },
                    FloatingWindowManager.WindowMenuItem("refresh", "↻ Refresh Folder", "fw_media_menu_refresh") {
                        renderFolderView()
                        showMode(browserView)
                    }
                )
            )

            renderFolderChips()
            renderFolderView()
            showMode(browserView)

            container.addView(browserView)
            container.addView(imageViewerView)
            container.addView(videoPlayerView)
            container.addView(audioPlayerView)
            container.addView(docViewerView)

            val cleanup: () -> Unit = {
                isReleased = true
                mediaJob.cancel()
                mainHandler.removeCallbacks(ticker)
                ViewerPickerActivity.pendingCallback = null
                ViewerPickerActivity.pendingFolderCallback = null
                runCatching { videoView.stopPlayback() }
                runCatching {
                    mediaPlayer?.stop()
                    mediaPlayer?.release()
                }
                mediaPlayer = null
                activeVideoPlayer = null
            }
            container to cleanup
        }
    }

    fun openFloatingMusicPlayer(
        context: Context,
        manager: FloatingWindowManager,
        accentColor: Int
    ): FloatingWindowManager.ManagedWindow? =
        FloatingGalleryMusicHelper.openFloatingMusicPlayer(context, manager, accentColor)

    fun openFloatingPhotosVideosGallery(
        context: Context,
        manager: FloatingWindowManager,
        accentColor: Int,
        windowId: String = "floating_photos_videos",
        windowTitle: String = context.getString(R.string.floating_window_title_gallery)
    ): FloatingWindowManager.ManagedWindow? =
        FloatingGalleryMusicHelper.openFloatingPhotosVideosGallery(
            context = context,
            manager = manager,
            accentColor = accentColor,
            windowId = windowId,
            windowTitle = windowTitle
        )
}
