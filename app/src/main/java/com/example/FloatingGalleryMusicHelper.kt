package com.example

import android.annotation.SuppressLint
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.util.LruCache
import android.util.Size
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import android.widget.VideoView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/**
 * In-app Floating Media libraries with background pre-caching and background audio playback:
 * 1. Dedicated Floating Music & Voice Player:
 *    - Pre-loads MediaStore.Audio.Media sorted by DATE_ADDED DESC so opening is instantaneous.
 *    - Keeps MediaPlayer alive in FloatingService even when the window is closed (mini-pill + notification).
 *    - Moves Search & "Hide Call Recordings" toggle into the ☰ Hamburger Menu.
 *    - Hides the bottom player bar until a track is tapped.
 * 2. Instant All-Files & Media Viewer:
 *    - Pre-loads Images, Videos, Audio, Documents (.pdf/.docx/.txt), and APKs sorted by DATE_ADDED DESC.
 *    - Clean 2-tab layout: "All Files — New to Old" and "Folders — New to Old".
 *    - Search, filter chips, and ↻ Sync live cleanly inside the ☰ Hamburger Menu.
 *    - Supports Left/Right horizontal swipe navigation in Photo & Video viewers.
 */
object FloatingGalleryMusicHelper {

    enum class GalleryFilter {
        ALL,
        PHOTOS,
        VIDEOS,
        AUDIO,
        DOCS,
        APK
    }

    enum class GalleryFileType {
        IMAGE,
        VIDEO,
        AUDIO,
        DOCUMENT,
        APK
    }

    data class AudioTrackItem(
        val displayName: String,
        val uri: Uri,
        val durationMs: Long,
        val folderName: String,
        val sizeBytes: Long,
        val rawPath: String = "",
        val dateAddedSec: Long = 0L
    )

    data class GalleryMediaItem(
        val id: Long,
        val displayName: String,
        val uri: Uri,
        val isVideo: Boolean,
        val durationMs: Long,
        val dateAddedSec: Long,
        val folderName: String,
        val fileType: GalleryFileType = if (isVideo) GalleryFileType.VIDEO else GalleryFileType.IMAGE,
        val sizeBytes: Long = 0L,
        val mimeType: String = ""
    )

    const val PREF_MUSIC_HIDE_CALL_REC = "music_hide_call_recordings"

    private val AUDIO_EXTS = setOf("mp3", "m4a", "wav", "ogg", "aac", "flac", "opus", "amr", "3ga")
    private val IMAGE_EXTS = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "heic")
    private val VIDEO_EXTS = setOf("mp4", "mkv", "webm", "3gp", "mov", "avi")
    private val DOC_EXTS = setOf("pdf", "doc", "docx", "txt", "csv", "xls", "xlsx", "ppt", "pptx", "rtf", "md")
    private val APK_EXTS = setOf("apk", "xapk", "apks")

    private val PHONE_CALL_FILENAME_REGEX = Regex("^\\+?\\d[\\d\\s\\-()]{5,}.*")

    private val preloadScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @Volatile
    var cachedAudioTracks: List<AudioTrackItem>? = null

    @Volatile
    var cachedGalleryMedia: List<GalleryMediaItem>? = null

    @Volatile
    var cachedAllFiles: List<GalleryMediaItem>? = null

    // Background Music Playback Singleton State
    @Volatile
    var onBackgroundPlaybackChanged: ((isPlaying: Boolean, trackTitle: String?, isMusicWindowOpen: Boolean) -> Unit)? = null

    @Volatile
    private var bgMediaPlayer: MediaPlayer? = null

    @Volatile
    private var bgQueue: List<AudioTrackItem> = emptyList()

    @Volatile
    private var bgCurrentIndex: Int = -1

    @Volatile
    private var bgCurrentTrack: AudioTrackItem? = null

    @Volatile
    private var isMusicWindowOpen: Boolean = false

    @Volatile
    private var musicWindowUiSync: (() -> Unit)? = null

    private val thumbnailCache = object : LruCache<String, Bitmap>(16 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount.coerceAtLeast(1)
    }

    private fun dp(context: Context, value: Float): Int =
        (value * context.resources.displayMetrics.density.coerceAtLeast(1f)).toInt()

    fun isBackgroundPlaying(): Boolean = runCatching { bgMediaPlayer?.isPlaying == true }.getOrDefault(false)

    fun getCurrentBackgroundTrackTitle(): String? = bgCurrentTrack?.displayName

    fun notifyPlaybackListeners() {
        musicWindowUiSync?.invoke()
        onBackgroundPlaybackChanged?.invoke(
            isBackgroundPlaying(),
            bgCurrentTrack?.displayName,
            isMusicWindowOpen
        )
    }

    fun toggleBackgroundPlayPause() {
        val mp = bgMediaPlayer
        if (mp != null) {
            runCatching {
                if (mp.isPlaying) mp.pause() else mp.start()
            }
            notifyPlaybackListeners()
        } else if (bgQueue.isNotEmpty()) {
            val targetIdx = if (bgCurrentIndex in bgQueue.indices) bgCurrentIndex else 0
            // Nothing to resume if player was stopped without Context; caller from window starts track
            notifyPlaybackListeners()
        }
    }

    fun stopBackgroundPlayback() {
        runCatching {
            bgMediaPlayer?.stop()
            bgMediaPlayer?.release()
        }
        bgMediaPlayer = null
        bgCurrentTrack = null
        bgCurrentIndex = -1
        notifyPlaybackListeners()
    }

    fun preloadAllMediaAsync(
        context: Context,
        scope: CoroutineScope = preloadScope
    ) {
        val appCtx = context.applicationContext ?: context
        val hasAudio = MediaPermissionActivity.hasAudioPermission(appCtx)
        val hasGallery = MediaPermissionActivity.hasImagesAndVideoPermission(appCtx)
        if (!hasAudio && !hasGallery) return
        scope.launch(Dispatchers.IO) {
            if (hasAudio && isActive) runCatching { queryAudioLibrary(appCtx) }
            if (hasGallery && isActive) {
                runCatching { queryPhotosAndVideos(appCtx, GalleryFilter.ALL) }
                if (isActive) runCatching { queryAllFiles(appCtx) }
            }
        }
    }

    fun isCallRecording(track: AudioTrackItem): Boolean {
        val lowerPath = track.rawPath.lowercase(Locale.ROOT).replace('\\', '/')
        val lowerFolder = track.folderName.lowercase(Locale.ROOT)
        val baseName = track.displayName.substringBeforeLast('.').trim().lowercase(Locale.ROOT)

        if (lowerPath.contains("call_rec") ||
            lowerPath.contains("callrec") ||
            lowerPath.contains("recordings/call") ||
            lowerPath.contains("sound_recorder/call") ||
            lowerPath.contains("/call/") ||
            lowerPath.endsWith("/call") ||
            lowerPath.contains("call recording")
        ) {
            return true
        }
        if (lowerFolder == "call" ||
            lowerFolder == "call_rec" ||
            lowerFolder == "callrec" ||
            lowerFolder.contains("call record") ||
            lowerFolder.contains("call_rec")
        ) {
            return true
        }
        if (baseName.startsWith("call_") ||
            baseName.startsWith("call-") ||
            baseName.startsWith("call ") ||
            baseName.startsWith("callrec") ||
            baseName.contains("call recording") ||
            baseName.contains("call_recording")
        ) {
            return true
        }
        return PHONE_CALL_FILENAME_REGEX.matches(baseName)
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

    fun queryAudioLibrary(context: Context): List<AudioTrackItem> {
        val result = ArrayList<AudioTrackItem>()
        val seenKeys = HashSet<String>()

        fun addUnique(item: AudioTrackItem) {
            val key = "${item.displayName.lowercase(Locale.ROOT)}|${item.uri}"
            if (seenKeys.add(key)) {
                result.add(item)
            }
        }

        val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        runCatching {
            val projection = buildList {
                add(MediaStore.Audio.Media._ID)
                add(MediaStore.Audio.Media.DISPLAY_NAME)
                add(MediaStore.Audio.Media.DURATION)
                add(MediaStore.Audio.Media.SIZE)
                add(MediaStore.Audio.Media.DATE_ADDED)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    add(MediaStore.Audio.Media.RELATIVE_PATH)
                } else {
                    @Suppress("DEPRECATION")
                    add(MediaStore.Audio.Media.DATA)
                }
            }.toTypedArray()

            context.contentResolver.query(
                collection,
                projection,
                null,
                null,
                "${MediaStore.Audio.Media.DATE_ADDED} DESC"
            )?.use { cursor ->
                val idIdx = cursor.getColumnIndex(MediaStore.Audio.Media._ID)
                val nameIdx = cursor.getColumnIndex(MediaStore.Audio.Media.DISPLAY_NAME)
                val durIdx = cursor.getColumnIndex(MediaStore.Audio.Media.DURATION)
                val sizeIdx = cursor.getColumnIndex(MediaStore.Audio.Media.SIZE)
                val dateIdx = cursor.getColumnIndex(MediaStore.Audio.Media.DATE_ADDED)
                val pathIdx = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    cursor.getColumnIndex(MediaStore.Audio.Media.RELATIVE_PATH)
                } else {
                    @Suppress("DEPRECATION")
                    cursor.getColumnIndex(MediaStore.Audio.Media.DATA)
                }

                while (cursor.moveToNext()) {
                    val id = if (idIdx >= 0) cursor.getLong(idIdx) else continue
                    val name = if (nameIdx >= 0) cursor.getString(nameIdx).orEmpty() else ""
                    if (name.isBlank()) continue
                    val duration = if (durIdx >= 0) cursor.getLong(durIdx).coerceAtLeast(0L) else 0L
                    val size = if (sizeIdx >= 0) cursor.getLong(sizeIdx).coerceAtLeast(0L) else 0L
                    val dateSec = if (dateIdx >= 0) cursor.getLong(dateIdx).coerceAtLeast(0L) else 0L
                    val rawPath = if (pathIdx >= 0) cursor.getString(pathIdx).orEmpty() else ""
                    val folder = extractFolderLabel(rawPath, "Music")
                    addUnique(
                        AudioTrackItem(
                            displayName = name,
                            uri = ContentUris.withAppendedId(collection, id),
                            durationMs = duration,
                            folderName = folder,
                            sizeBytes = size,
                            rawPath = rawPath,
                            dateAddedSec = dateSec
                        )
                    )
                }
            }
        }.onFailure {
            android.util.Log.w("FloatingGalleryMusic", "Audio MediaStore query failed", it)
        }

        @Suppress("DEPRECATION")
        val fallbackDirs = listOfNotNull(
            runCatching { Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC) }.getOrNull(),
            runCatching { Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS) }.getOrNull(),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                runCatching { Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_RECORDINGS) }.getOrNull()
            } else null,
            runCatching { File(Environment.getExternalStorageDirectory(), "Recordings") }.getOrNull()
        )
        fallbackDirs.distinctBy { it.absolutePath }.forEach { dir ->
            if (dir.exists() && dir.isDirectory && dir.canRead()) {
                val collected = ArrayList<File>()
                collectFilesRecursive(dir, maxDepth = 2, out = collected)
                collected
                    .filter { it.extension.lowercase(Locale.ROOT) in AUDIO_EXTS }
                    .sortedByDescending { it.lastModified() }
                    .forEach { file ->
                        addUnique(
                            AudioTrackItem(
                                displayName = file.name,
                                uri = Uri.fromFile(file),
                                durationMs = 0L,
                                folderName = file.parentFile?.name ?: dir.name,
                                sizeBytes = file.length(),
                                rawPath = file.absolutePath,
                                dateAddedSec = file.lastModified() / 1000L
                            )
                        )
                    }
            }
        }
        val sorted = result.sortedByDescending { it.dateAddedSec }
        cachedAudioTracks = sorted
        return sorted
    }

    fun queryPhotosAndVideos(
        context: Context,
        filter: GalleryFilter = GalleryFilter.ALL
    ): List<GalleryMediaItem> {
        val result = ArrayList<GalleryMediaItem>()
        val seenKeys = HashSet<String>()

        fun addUnique(item: GalleryMediaItem) {
            val key = "${item.fileType}|${item.displayName.lowercase(Locale.ROOT)}|${item.uri}"
            if (seenKeys.add(key)) {
                result.add(item)
            }
        }

        if (filter == GalleryFilter.ALL || filter == GalleryFilter.PHOTOS) {
            val imgUri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            runCatching {
                val projection = buildList {
                    add(MediaStore.Images.Media._ID)
                    add(MediaStore.Images.Media.DISPLAY_NAME)
                    add(MediaStore.Images.Media.DATE_ADDED)
                    add(MediaStore.Images.Media.SIZE)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        add(MediaStore.Images.Media.RELATIVE_PATH)
                    } else {
                        @Suppress("DEPRECATION")
                        add(MediaStore.Images.Media.DATA)
                    }
                }.toTypedArray()

                context.contentResolver.query(
                    imgUri,
                    projection,
                    null,
                    null,
                    "${MediaStore.Images.Media.DATE_ADDED} DESC"
                )?.use { cursor ->
                    val idIdx = cursor.getColumnIndex(MediaStore.Images.Media._ID)
                    val nameIdx = cursor.getColumnIndex(MediaStore.Images.Media.DISPLAY_NAME)
                    val dateIdx = cursor.getColumnIndex(MediaStore.Images.Media.DATE_ADDED)
                    val sizeIdx = cursor.getColumnIndex(MediaStore.Images.Media.SIZE)
                    val pathIdx = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        cursor.getColumnIndex(MediaStore.Images.Media.RELATIVE_PATH)
                    } else {
                        @Suppress("DEPRECATION")
                        cursor.getColumnIndex(MediaStore.Images.Media.DATA)
                    }
                    while (cursor.moveToNext()) {
                        val id = if (idIdx >= 0) cursor.getLong(idIdx) else continue
                        val name = if (nameIdx >= 0) cursor.getString(nameIdx).orEmpty() else ""
                        if (name.isBlank()) continue
                        val dateSec = if (dateIdx >= 0) cursor.getLong(dateIdx) else 0L
                        val size = if (sizeIdx >= 0) cursor.getLong(sizeIdx).coerceAtLeast(0L) else 0L
                        val rawPath = if (pathIdx >= 0) cursor.getString(pathIdx).orEmpty() else ""
                        addUnique(
                            GalleryMediaItem(
                                id = id,
                                displayName = name,
                                uri = ContentUris.withAppendedId(imgUri, id),
                                isVideo = false,
                                durationMs = 0L,
                                dateAddedSec = dateSec,
                                folderName = extractFolderLabel(rawPath, "Pictures"),
                                fileType = GalleryFileType.IMAGE,
                                sizeBytes = size,
                                mimeType = "image/*"
                            )
                        )
                    }
                }
            }.onFailure {
                android.util.Log.w("FloatingGalleryMusic", "Image MediaStore query failed", it)
            }
        }

        if (filter == GalleryFilter.ALL || filter == GalleryFilter.VIDEOS) {
            val vidUri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            runCatching {
                val projection = buildList {
                    add(MediaStore.Video.Media._ID)
                    add(MediaStore.Video.Media.DISPLAY_NAME)
                    add(MediaStore.Video.Media.DURATION)
                    add(MediaStore.Video.Media.DATE_ADDED)
                    add(MediaStore.Video.Media.SIZE)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        add(MediaStore.Video.Media.RELATIVE_PATH)
                    } else {
                        @Suppress("DEPRECATION")
                        add(MediaStore.Video.Media.DATA)
                    }
                }.toTypedArray()

                context.contentResolver.query(
                    vidUri,
                    projection,
                    null,
                    null,
                    "${MediaStore.Video.Media.DATE_ADDED} DESC"
                )?.use { cursor ->
                    val idIdx = cursor.getColumnIndex(MediaStore.Video.Media._ID)
                    val nameIdx = cursor.getColumnIndex(MediaStore.Video.Media.DISPLAY_NAME)
                    val durIdx = cursor.getColumnIndex(MediaStore.Video.Media.DURATION)
                    val dateIdx = cursor.getColumnIndex(MediaStore.Video.Media.DATE_ADDED)
                    val sizeIdx = cursor.getColumnIndex(MediaStore.Video.Media.SIZE)
                    val pathIdx = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        cursor.getColumnIndex(MediaStore.Video.Media.RELATIVE_PATH)
                    } else {
                        @Suppress("DEPRECATION")
                        cursor.getColumnIndex(MediaStore.Video.Media.DATA)
                    }
                    while (cursor.moveToNext()) {
                        val id = if (idIdx >= 0) cursor.getLong(idIdx) else continue
                        val name = if (nameIdx >= 0) cursor.getString(nameIdx).orEmpty() else ""
                        if (name.isBlank()) continue
                        val dur = if (durIdx >= 0) cursor.getLong(durIdx).coerceAtLeast(0L) else 0L
                        val dateSec = if (dateIdx >= 0) cursor.getLong(dateIdx) else 0L
                        val size = if (sizeIdx >= 0) cursor.getLong(sizeIdx).coerceAtLeast(0L) else 0L
                        val rawPath = if (pathIdx >= 0) cursor.getString(pathIdx).orEmpty() else ""
                        addUnique(
                            GalleryMediaItem(
                                id = id,
                                displayName = name,
                                uri = ContentUris.withAppendedId(vidUri, id),
                                isVideo = true,
                                durationMs = dur,
                                dateAddedSec = dateSec,
                                folderName = extractFolderLabel(rawPath, "Movies"),
                                fileType = GalleryFileType.VIDEO,
                                sizeBytes = size,
                                mimeType = "video/*"
                            )
                        )
                    }
                }
            }.onFailure {
                android.util.Log.w("FloatingGalleryMusic", "Video MediaStore query failed", it)
            }
        }

        @Suppress("DEPRECATION")
        val fallbackDirs = listOfNotNull(
            runCatching { Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES) }.getOrNull(),
            runCatching { Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM) }.getOrNull(),
            runCatching { Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES) }.getOrNull(),
            runCatching { Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS) }.getOrNull()
        )
        fallbackDirs.distinctBy { it.absolutePath }.forEach { dir ->
            if (dir.exists() && dir.isDirectory && dir.canRead()) {
                val collected = ArrayList<File>()
                collectFilesRecursive(dir, maxDepth = 2, out = collected)
                collected.forEach { file ->
                    val ext = file.extension.lowercase(Locale.ROOT)
                    val isImg = ext in IMAGE_EXTS
                    val isVid = ext in VIDEO_EXTS
                    if ((isImg && filter != GalleryFilter.VIDEOS) || (isVid && filter != GalleryFilter.PHOTOS)) {
                        addUnique(
                            GalleryMediaItem(
                                id = file.absolutePath.hashCode().toLong(),
                                displayName = file.name,
                                uri = Uri.fromFile(file),
                                isVideo = isVid,
                                durationMs = 0L,
                                dateAddedSec = file.lastModified() / 1000L,
                                folderName = file.parentFile?.name ?: dir.name,
                                fileType = if (isVid) GalleryFileType.VIDEO else GalleryFileType.IMAGE,
                                sizeBytes = file.length(),
                                mimeType = if (isVid) "video/*" else "image/*"
                            )
                        )
                    }
                }
            }
        }

        val sorted = result.sortedByDescending { it.dateAddedSec }
        if (filter == GalleryFilter.ALL) {
            cachedGalleryMedia = sorted
        }
        return sorted
    }

    private fun classifyGalleryFileType(name: String, mime: String): GalleryFileType? {
        val ext = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        val lowerMime = mime.lowercase(Locale.ROOT)
        return when {
            ext in APK_EXTS || lowerMime == "application/vnd.android.package-archive" -> GalleryFileType.APK
            ext in IMAGE_EXTS || lowerMime.startsWith("image/") -> GalleryFileType.IMAGE
            ext in VIDEO_EXTS || lowerMime.startsWith("video/") -> GalleryFileType.VIDEO
            ext in AUDIO_EXTS || lowerMime.startsWith("audio/") -> GalleryFileType.AUDIO
            ext in DOC_EXTS || lowerMime.startsWith("text/") || lowerMime == "application/pdf" ||
                lowerMime.contains("document") || lowerMime.contains("msword") ||
                lowerMime.contains("spreadsheet") || lowerMime.contains("presentation") -> GalleryFileType.DOCUMENT
            else -> null
        }
    }

    /**
     * Queries all device files (Images, Videos, Audio, Documents, APKs) sorted newest-to-oldest
     * (`DATE_ADDED DESC`) and caches the result for zero-delay opening.
     */
    fun queryAllFiles(context: Context): List<GalleryMediaItem> {
        val result = ArrayList<GalleryMediaItem>()
        val seenKeys = HashSet<String>()

        fun addUnique(item: GalleryMediaItem) {
            val key = "${item.fileType}|${item.displayName.lowercase(Locale.ROOT)}|${item.uri}"
            if (seenKeys.add(key)) {
                result.add(item)
            }
        }

        // 1. Photos & Videos
        queryPhotosAndVideos(context, GalleryFilter.ALL).forEach { addUnique(it) }

        // 2. Audio tracks
        queryAudioLibrary(context).forEach { track ->
            addUnique(
                GalleryMediaItem(
                    id = track.uri.toString().hashCode().toLong(),
                    displayName = track.displayName,
                    uri = track.uri,
                    isVideo = false,
                    durationMs = track.durationMs,
                    dateAddedSec = track.dateAddedSec,
                    folderName = track.folderName,
                    fileType = GalleryFileType.AUDIO,
                    sizeBytes = track.sizeBytes,
                    mimeType = "audio/*"
                )
            )
        }

        // 3. Documents & APKs from MediaStore.Files
        val filesUri = MediaStore.Files.getContentUri("external")
        runCatching {
            val projection = buildList {
                add(MediaStore.Files.FileColumns._ID)
                add(MediaStore.Files.FileColumns.DISPLAY_NAME)
                add(MediaStore.Files.FileColumns.MIME_TYPE)
                add(MediaStore.Files.FileColumns.SIZE)
                add(MediaStore.Files.FileColumns.DATE_ADDED)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    add(MediaStore.Files.FileColumns.RELATIVE_PATH)
                } else {
                    @Suppress("DEPRECATION")
                    add(MediaStore.Files.FileColumns.DATA)
                }
            }.toTypedArray()

            context.contentResolver.query(
                filesUri,
                projection,
                null,
                null,
                "${MediaStore.Files.FileColumns.DATE_ADDED} DESC"
            )?.use { cursor ->
                val idIdx = cursor.getColumnIndex(MediaStore.Files.FileColumns._ID)
                val nameIdx = cursor.getColumnIndex(MediaStore.Files.FileColumns.DISPLAY_NAME)
                val mimeIdx = cursor.getColumnIndex(MediaStore.Files.FileColumns.MIME_TYPE)
                val sizeIdx = cursor.getColumnIndex(MediaStore.Files.FileColumns.SIZE)
                val dateIdx = cursor.getColumnIndex(MediaStore.Files.FileColumns.DATE_ADDED)
                val pathIdx = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    cursor.getColumnIndex(MediaStore.Files.FileColumns.RELATIVE_PATH)
                } else {
                    @Suppress("DEPRECATION")
                    cursor.getColumnIndex(MediaStore.Files.FileColumns.DATA)
                }

                while (cursor.moveToNext()) {
                    val id = if (idIdx >= 0) cursor.getLong(idIdx) else continue
                    val name = if (nameIdx >= 0) cursor.getString(nameIdx).orEmpty() else ""
                    if (name.isBlank()) continue
                    val mime = if (mimeIdx >= 0) cursor.getString(mimeIdx).orEmpty() else ""
                    val fType = classifyGalleryFileType(name, mime) ?: continue
                    val size = if (sizeIdx >= 0) cursor.getLong(sizeIdx).coerceAtLeast(0L) else 0L
                    val dateSec = if (dateIdx >= 0) cursor.getLong(dateIdx).coerceAtLeast(0L) else 0L
                    val rawPath = if (pathIdx >= 0) cursor.getString(pathIdx).orEmpty() else ""
                    val fallbackFolder = when (fType) {
                        GalleryFileType.DOCUMENT -> "Documents"
                        GalleryFileType.APK -> "Downloads"
                        GalleryFileType.AUDIO -> "Music"
                        GalleryFileType.VIDEO -> "Movies"
                        GalleryFileType.IMAGE -> "Pictures"
                    }
                    addUnique(
                        GalleryMediaItem(
                            id = id,
                            displayName = name,
                            uri = ContentUris.withAppendedId(filesUri, id),
                            isVideo = fType == GalleryFileType.VIDEO,
                            durationMs = 0L,
                            dateAddedSec = dateSec,
                            folderName = extractFolderLabel(rawPath, fallbackFolder),
                            fileType = fType,
                            sizeBytes = size,
                            mimeType = mime
                        )
                    )
                }
            }
        }.onFailure {
            android.util.Log.w("FloatingGalleryMusic", "Files MediaStore query failed", it)
        }

        // 4. Direct accessible Downloads/Documents fallback
        @Suppress("DEPRECATION")
        val docDirs = listOfNotNull(
            runCatching { Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS) }.getOrNull(),
            runCatching { Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS) }.getOrNull()
        )
        docDirs.distinctBy { it.absolutePath }.forEach { dir ->
            if (dir.exists() && dir.isDirectory && dir.canRead()) {
                val collected = ArrayList<File>()
                collectFilesRecursive(dir, maxDepth = 2, out = collected)
                collected.forEach { file ->
                    val fType = classifyGalleryFileType(file.name, "") ?: return@forEach
                    addUnique(
                        GalleryMediaItem(
                            id = file.absolutePath.hashCode().toLong(),
                            displayName = file.name,
                            uri = Uri.fromFile(file),
                            isVideo = fType == GalleryFileType.VIDEO,
                            durationMs = 0L,
                            dateAddedSec = file.lastModified() / 1000L,
                            folderName = file.parentFile?.name ?: dir.name,
                            fileType = fType,
                            sizeBytes = file.length()
                        )
                    )
                }
            }
        }

        val sorted = result.sortedByDescending { it.dateAddedSec }
        cachedAllFiles = sorted
        return sorted
    }

    private fun extractFolderLabel(rawPath: String, defaultLabel: String): String {
        val clean = rawPath.trim().trimEnd('/')
        if (clean.isEmpty()) return defaultLabel
        return clean.substringAfterLast('/').ifBlank { defaultLabel }
    }

    private fun thumbnailCacheKey(item: GalleryMediaItem, sizePx: Int): String = "${item.uri}|$sizePx"

    private fun loadThumbnailBitmap(context: Context, item: GalleryMediaItem, sizePx: Int): Bitmap? {
        if (item.fileType != GalleryFileType.IMAGE && item.fileType != GalleryFileType.VIDEO) return null
        val key = thumbnailCacheKey(item, sizePx)
        thumbnailCache.get(key)?.let { cached ->
            if (!cached.isRecycled) return cached
        }
        val decoded: Bitmap? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching {
                context.contentResolver.loadThumbnail(item.uri, Size(sizePx, sizePx), null)
            }.getOrNull()
        } else {
            null
        } ?: if (item.isVideo) {
            null
        } else {
            runCatching {
                val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                context.contentResolver.openInputStream(item.uri)?.use { stream ->
                    BitmapFactory.decodeStream(stream, null, boundsOpts)
                }
                var sample = 1
                val maxDim = maxOf(boundsOpts.outWidth, boundsOpts.outHeight)
                if (maxDim > sizePx) {
                    while (maxDim / sample > sizePx * 2) {
                        sample *= 2
                    }
                }
                val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sample }
                context.contentResolver.openInputStream(item.uri)?.use { stream ->
                    BitmapFactory.decodeStream(stream, null, decodeOpts)
                }
            }.getOrNull()
        }
        if (decoded != null) {
            thumbnailCache.put(key, decoded)
        }
        return decoded
    }

    /**
     * Dedicated Floating Music & Voice Player window:
     * - Displays pre-cached MediaStore.Audio.Media immediately with zero wait.
     * - Keeps MediaPlayer playing in background when closed (driving FloatingService Mini-Pill).
     * - Search & "Hide Call Recordings" toggle live inside the ☰ Hamburger Menu.
     * - Bottom Player Card is hidden initially until a song is tapped.
     */
    fun openFloatingMusicPlayer(
        context: Context,
        manager: FloatingWindowManager,
        accentColor: Int
    ): FloatingWindowManager.ManagedWindow? {
        return manager.openOrFocusWindow(
            windowId = "floating_music",
            title = context.getString(R.string.floating_window_title_music),
            accentColor = accentColor,
            iconRes = android.R.drawable.ic_media_play,
            requiresTextInput = false
        ) { managed ->
            val container = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(context, 8f), dp(context, 6f), dp(context, 8f), dp(context, 8f))
            }

            val mainHandler = Handler(Looper.getMainLooper())
            val musicJob = SupervisorJob()
            val musicScope = CoroutineScope(Dispatchers.Main.immediate + musicJob)
            val prefs = context.getSharedPreferences(SelectionToolbarHelper.PREFS_NAME, Context.MODE_PRIVATE)
            var hideCallRecordings = prefs.getBoolean(PREF_MUSIC_HIDE_CALL_REC, true)
            var searchQuery = ""
            var allQueriedTracks: List<AudioTrackItem> = cachedAudioTracks ?: emptyList()
            var isReleased = false
            var refreshSeq = 0

            isMusicWindowOpen = true
            onBackgroundPlaybackChanged?.invoke(
                isBackgroundPlaying(),
                bgCurrentTrack?.displayName,
                true
            )

            // Slim count indicator (no cluttered search/filter bars on main screen)
            val tvCount = TextView(context).apply {
                tag = "fw_music_count"
                text = "Audio Library (${allQueriedTracks.size})"
                textSize = 11.5f
                setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
                setTextColor(Color.parseColor("#CBD5E1"))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = dp(context, 6f)
                }
            }

            // Player Card — hidden initially until user taps a track (or reopening while active in background)
            val playerCard = LinearLayout(context).apply {
                tag = "fw_music_player_card"
                orientation = LinearLayout.VERTICAL
                visibility = if (bgCurrentTrack != null) View.VISIBLE else View.GONE
                setPadding(dp(context, 10f), dp(context, 10f), dp(context, 10f), dp(context, 10f))
                background = GradientDrawable().apply {
                    cornerRadius = dp(context, 10f).toFloat()
                    setColor(Color.parseColor("#1E293B"))
                    setStroke(1, Color.argb(90, 148, 163, 184))
                }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = dp(context, 8f)
                }
            }
            val tvNowPlaying = TextView(context).apply {
                tag = "fw_music_now_playing"
                text = bgCurrentTrack?.let { "🎵 ${it.displayName}" } ?: "Select a track to play"
                textSize = 13f
                setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
                setTextColor(Color.WHITE)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }
            val seekBar = SeekBar(context).apply {
                tag = "fw_music_seekbar"
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = dp(context, 4f)
                }
            }
            val tvTime = TextView(context).apply {
                tag = "fw_music_time"
                text = "00:00 / 00:00"
                textSize = 10.5f
                setTextColor(Color.parseColor("#94A3B8"))
                gravity = Gravity.END
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = dp(context, 6f)
                }
            }
            val controlsRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
            }
            fun makeCtrlBtn(label: String, btnTag: String, primary: Boolean = false): TextView {
                return TextView(context).apply {
                    tag = btnTag
                    text = label
                    textSize = 12f
                    setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
                    setTextColor(if (primary) Color.BLACK else Color.WHITE)
                    gravity = Gravity.CENTER
                    setPadding(dp(context, 12f), dp(context, 7f), dp(context, 12f), dp(context, 7f))
                    background = GradientDrawable().apply {
                        cornerRadius = dp(context, 8f).toFloat()
                        setColor(if (primary) accentColor else Color.parseColor("#0F172A"))
                    }
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        marginStart = dp(context, 5f)
                        marginEnd = dp(context, 5f)
                    }
                }
            }
            val btnPrev = makeCtrlBtn("⏮ Prev", "fw_music_prev")
            val btnPlayPause = makeCtrlBtn(if (isBackgroundPlaying()) "⏸ Pause" else "▶ Play", "fw_music_play_pause", primary = true)
            val btnNext = makeCtrlBtn("Next ⏭", "fw_music_next")
            controlsRow.addView(btnPrev)
            controlsRow.addView(btnPlayPause)
            controlsRow.addView(btnNext)

            playerCard.addView(tvNowPlaying)
            playerCard.addView(seekBar)
            playerCard.addView(tvTime)
            playerCard.addView(controlsRow)

            // Grant Media Permission Banner
            val btnGrantAudioPerm = TextView(context).apply {
                tag = "fw_music_grant_perm"
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
                visibility = if (MediaPermissionActivity.hasAudioPermission(context)) View.GONE else View.VISIBLE
            }

            // Virtualized Audio Track List (RecyclerView) + Empty State
            val tvMusicEmpty = TextView(context).apply {
                tag = "fw_music_empty"
                textSize = 12f
                setTextColor(Color.parseColor("#94A3B8"))
                gravity = Gravity.CENTER
                setPadding(dp(context, 12f), dp(context, 20f), dp(context, 12f), dp(context, 20f))
                visibility = View.GONE
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.CENTER
                )
            }
            val musicRecyclerView = RecyclerView(context).apply {
                tag = "fw_music_list"
                layoutManager = LinearLayoutManager(context)
                setHasFixedSize(false)
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            }
            val listHost = FrameLayout(context).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    0,
                    1f
                )
                addView(musicRecyclerView)
                addView(tvMusicEmpty)
            }

            fun syncPlayerUi() {
                val track = bgCurrentTrack
                if (track != null) {
                    playerCard.visibility = View.VISIBLE
                    tvNowPlaying.text = "🎵 ${track.displayName}"
                }
                val playing = isBackgroundPlaying()
                btnPlayPause.text = if (playing) "⏸ Pause" else "▶ Play"
            }

            musicWindowUiSync = {
                mainHandler.post {
                    if (!isReleased) syncPlayerUi()
                }
            }

            fun playTrackAt(idx: Int) {
                val queue = bgQueue
                val track = queue.getOrNull(idx) ?: return
                bgCurrentIndex = idx
                bgCurrentTrack = track
                playerCard.visibility = View.VISIBLE
                tvNowPlaying.text = "🎵 ${track.displayName}"
                tvTime.text = "00:00 / ${FloatingMediaFilesHelper.formatDurationMs(track.durationMs)}"
                seekBar.progress = 0

                runCatching {
                    bgMediaPlayer?.stop()
                    bgMediaPlayer?.release()
                }
                bgMediaPlayer = null

                val appCtx = context.applicationContext ?: context
                val player = MediaPlayer().apply {
                    setAudioAttributes(
                        AudioAttributes.Builder()
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .build()
                    )
                    setOnPreparedListener { mp ->
                        mp.start()
                        notifyPlaybackListeners()
                    }
                    setOnCompletionListener {
                        if (bgCurrentIndex + 1 < bgQueue.size) {
                            playTrackAt(bgCurrentIndex + 1)
                        } else {
                            notifyPlaybackListeners()
                        }
                    }
                    setOnErrorListener { _, _, _ ->
                        Toast.makeText(appCtx, "Cannot play ${track.displayName}", Toast.LENGTH_SHORT).show()
                        notifyPlaybackListeners()
                        true
                    }
                }
                val loaded = runCatching {
                    player.setDataSource(appCtx, track.uri)
                    player.prepareAsync()
                    true
                }.getOrDefault(false)
                if (loaded) {
                    bgMediaPlayer = player
                    notifyPlaybackListeners()
                } else {
                    runCatching { player.release() }
                    Toast.makeText(appCtx, "Unable to open ${track.displayName}", Toast.LENGTH_SHORT).show()
                    notifyPlaybackListeners()
                }
            }

            class MusicTrackViewHolder(
                val row: LinearLayout,
                val tvTitle: TextView,
                val tvMeta: TextView
            ) : RecyclerView.ViewHolder(row)

            val musicAdapter = object : RecyclerView.Adapter<MusicTrackViewHolder>() {
                private var items: List<AudioTrackItem> = emptyList()

                fun submitList(newItems: List<AudioTrackItem>) {
                    items = newItems
                    notifyDataSetChanged()
                }

                override fun getItemCount(): Int = items.size

                override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MusicTrackViewHolder {
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
                            bottomMargin = dp(context, 5f)
                        }
                    }
                    row.addView(TextView(context).apply {
                        text = "🎵"
                        textSize = 16f
                        setPadding(0, 0, dp(context, 8f), 0)
                    })
                    val textCol = LinearLayout(context).apply {
                        orientation = LinearLayout.VERTICAL
                        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    }
                    val tvTitle = TextView(context).apply {
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
                    textCol.addView(tvTitle)
                    textCol.addView(tvMeta)
                    row.addView(textCol)
                    return MusicTrackViewHolder(row, tvTitle, tvMeta)
                }

                override fun onBindViewHolder(holder: MusicTrackViewHolder, position: Int) {
                    val track = items[position]
                    holder.row.tag = "fw_music_row_$position"
                    holder.tvTitle.text = track.displayName
                    val durLabel = if (track.durationMs > 0L) {
                        FloatingMediaFilesHelper.formatDurationMs(track.durationMs)
                    } else {
                        "Audio"
                    }
                    holder.tvMeta.text = "${track.folderName} • $durLabel"
                    holder.row.setOnClickListener {
                        val adapterPos = holder.bindingAdapterPosition
                        val targetIdx = if (adapterPos != RecyclerView.NO_POSITION) adapterPos else position
                        playTrackAt(targetIdx)
                    }
                }
            }
            musicRecyclerView.adapter = musicAdapter

            fun applyAudioFilterAndRender() {
                val base = if (hideCallRecordings) {
                    allQueriedTracks.filterNot { isCallRecording(it) }
                } else {
                    allQueriedTracks
                }
                val q = searchQuery.trim().lowercase(Locale.ROOT)
                val filtered = if (q.isEmpty()) {
                    base
                } else {
                    base.filter {
                        it.displayName.lowercase(Locale.ROOT).contains(q) ||
                            it.folderName.lowercase(Locale.ROOT).contains(q)
                    }
                }
                bgQueue = filtered
                tvCount.text = if (hideCallRecordings && allQueriedTracks.size != filtered.size) {
                    "Audio Library (${filtered.size} / ${allQueriedTracks.size})"
                } else {
                    "Audio Library (${filtered.size})"
                }
                musicAdapter.submitList(filtered)
                if (filtered.isEmpty()) {
                    musicRecyclerView.visibility = View.GONE
                    tvMusicEmpty.visibility = View.VISIBLE
                    tvMusicEmpty.text = when {
                        !MediaPermissionActivity.hasAudioPermission(context) ->
                            "Media permission required to list all device songs.\nTap \"Grant Media Permission\" above or open via ☰ Menu."
                        q.isNotEmpty() ->
                            "No songs match \"$searchQuery\"."
                        allQueriedTracks.isNotEmpty() && hideCallRecordings ->
                            "All ${allQueriedTracks.size} tracks look like Call Recordings.\nTurn off \"Hide Call Recordings\" inside ☰ Menu to view them."
                        else ->
                            "No audio tracks found in MediaStore.\nOpen ☰ Menu and tap ↻ Sync Audio."
                    }
                } else {
                    tvMusicEmpty.visibility = View.GONE
                    musicRecyclerView.visibility = View.VISIBLE
                }
            }

            fun refreshAudioLibrary() {
                val hasPerm = MediaPermissionActivity.hasAudioPermission(context)
                btnGrantAudioPerm.visibility = if (hasPerm) View.GONE else View.VISIBLE
                val seq = ++refreshSeq
                musicScope.launch {
                    val queried = withContext(Dispatchers.IO) {
                        queryAudioLibrary(context)
                    }
                    withContext(Dispatchers.Main) {
                        if (isReleased || seq != refreshSeq) return@withContext
                        btnGrantAudioPerm.visibility = if (MediaPermissionActivity.hasAudioPermission(context)) {
                            View.GONE
                        } else {
                            View.VISIBLE
                        }
                        allQueriedTracks = queried
                        applyAudioFilterAndRender()
                    }
                }
            }

            // Build ☰ Hamburger Menu Header containing Search, "Hide Call Recordings" Switch, and Sync Button
            val menuHeader = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(context, 4f), dp(context, 2f), dp(context, 4f), dp(context, 4f))
            }
            val etMenuSearch = EditText(context).apply {
                tag = "fw_music_search_input"
                hint = "Search songs or folders..."
                setTextColor(Color.WHITE)
                setHintTextColor(Color.parseColor("#64748B"))
                textSize = 12f
                isSingleLine = true
                setPadding(dp(context, 10f), dp(context, 6f), dp(context, 10f), dp(context, 6f))
                background = GradientDrawable().apply {
                    cornerRadius = dp(context, 8f).toFloat()
                    setColor(Color.parseColor("#1E293B"))
                }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = dp(context, 6f)
                }
            }
            etMenuSearch.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    searchQuery = s?.toString().orEmpty()
                    applyAudioFilterAndRender()
                }
                override fun afterTextChanged(s: Editable?) {}
            })

            val callRecRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(context, 8f), dp(context, 6f), dp(context, 8f), dp(context, 6f))
                background = GradientDrawable().apply {
                    cornerRadius = dp(context, 8f).toFloat()
                    setColor(Color.parseColor("#1E293B"))
                }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = dp(context, 6f)
                }
            }
            val tvHideCallLabel = TextView(context).apply {
                text = "Hide Call Recordings"
                textSize = 11.5f
                setTextColor(Color.parseColor("#E2E8F0"))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val switchHideCallRec = Switch(context).apply {
                tag = "fw_music_hide_call_rec_toggle"
                isChecked = hideCallRecordings
                contentDescription = "Hide call recordings"
            }
            callRecRow.addView(tvHideCallLabel)
            callRecRow.addView(switchHideCallRec)

            val btnSync = TextView(context).apply {
                tag = "fw_music_sync"
                text = "↻ Sync Audio"
                textSize = 12f
                setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
                setTextColor(Color.BLACK)
                gravity = Gravity.CENTER
                contentDescription = "Sync audio library"
                setPadding(dp(context, 10f), dp(context, 7f), dp(context, 10f), dp(context, 7f))
                background = GradientDrawable().apply {
                    cornerRadius = dp(context, 8f).toFloat()
                    setColor(accentColor)
                }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = dp(context, 4f)
                }
            }
            menuHeader.addView(etMenuSearch)
            menuHeader.addView(callRecRow)
            menuHeader.addView(btnSync)
            managed.setMenuHeaderView(menuHeader)

            switchHideCallRec.setOnCheckedChangeListener { _, isChecked ->
                hideCallRecordings = isChecked
                prefs.edit().putBoolean(PREF_MUSIC_HIDE_CALL_REC, isChecked).apply()
                applyAudioFilterAndRender()
            }

            btnGrantAudioPerm.setOnClickListener {
                MediaPermissionActivity.launchPermissionPrompt(
                    context = context,
                    permissions = MediaPermissionActivity.requiredPermissionsForAudio()
                ) {
                    mainHandler.post {
                        if (!isReleased) refreshAudioLibrary()
                    }
                }
            }

            btnSync.setOnClickListener {
                refreshAudioLibrary()
                managed.closeMenu()
            }
            btnPlayPause.setOnClickListener {
                if (bgMediaPlayer != null) {
                    toggleBackgroundPlayPause()
                } else if (bgQueue.isNotEmpty()) {
                    playTrackAt(0)
                }
            }
            btnPrev.setOnClickListener {
                if (bgQueue.isNotEmpty()) {
                    val prev = if (bgCurrentIndex <= 0) bgQueue.lastIndex else bgCurrentIndex - 1
                    playTrackAt(prev)
                }
            }
            btnNext.setOnClickListener {
                if (bgQueue.isNotEmpty()) {
                    val next = (bgCurrentIndex + 1) % bgQueue.size
                    playTrackAt(next)
                }
            }
            seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) runCatching { bgMediaPlayer?.seekTo(progress) }
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })

            val ticker = object : Runnable {
                override fun run() {
                    if (isReleased) return
                    val mp = bgMediaPlayer
                    if (mp != null) {
                        runCatching {
                            val dur = mp.duration
                            if (dur > 0) {
                                val pos = mp.currentPosition
                                seekBar.max = dur
                                seekBar.progress = pos
                                tvTime.text = "${FloatingMediaFilesHelper.formatDurationMs(pos.toLong())} / ${FloatingMediaFilesHelper.formatDurationMs(dur.toLong())}"
                            }
                        }
                    }
                    mainHandler.postDelayed(this, 500L)
                }
            }
            mainHandler.postDelayed(ticker, 500L)

            managed.setMenuItems(
                listOf(
                    FloatingWindowManager.WindowMenuItem("open_audio_saf", "📂 Open Audio File (SAF)", "fw_music_menu_open") {
                        ViewerPickerActivity.pendingCallback = { uri, _ ->
                            val name = uri.lastPathSegment?.substringAfterLast('/') ?: "Audio Track"
                            val item = AudioTrackItem(name, uri, 0L, "Picked", 0L, dateAddedSec = System.currentTimeMillis() / 1000L)
                            allQueriedTracks = listOf(item) + allQueriedTracks
                            applyAudioFilterAndRender()
                            playTrackAt(0)
                        }
                        context.startActivity(
                            Intent(context, ViewerPickerActivity::class.java).apply {
                                putExtra(ViewerPickerActivity.EXTRA_PICK_MODE, ViewerPickerActivity.MODE_MEDIA)
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                        )
                    },
                    FloatingWindowManager.WindowMenuItem("stop_bg_music", "⏹ Stop Background Playback", "fw_music_menu_stop") {
                        stopBackgroundPlayback()
                        playerCard.visibility = View.GONE
                    }
                )
            )

            // Instant cached render immediately (0ms delay), then refresh silently in background if cache empty or for freshness
            if (allQueriedTracks.isNotEmpty()) {
                applyAudioFilterAndRender()
            }
            refreshAudioLibrary()

            container.addView(tvCount)
            container.addView(playerCard)
            container.addView(btnGrantAudioPerm)
            container.addView(listHost)

            val cleanup: () -> Unit = {
                isReleased = true
                isMusicWindowOpen = false
                musicWindowUiSync = null
                musicJob.cancel()
                mainHandler.removeCallbacks(ticker)
                // Keep bgMediaPlayer running cleanly in FloatingService for true background playback!
                onBackgroundPlaybackChanged?.invoke(
                    isBackgroundPlaying(),
                    bgCurrentTrack?.displayName,
                    false
                )
            }
            container to cleanup
        }
    }

    /**
     * Instant Pre-Loaded All-Files & Media Viewer:
     * - Displays pre-cached All Files sorted strictly by DATE_ADDED DESC (newest first).
     * - Clean 2-tab layout: "All Files — New to Old" & "Folders — New to Old".
     * - Search, filters, and ↻ Sync live inside the ☰ Hamburger Menu.
     * - Supports Left/Right horizontal swipe inside Photo & Video viewers.
     */
    @SuppressLint("ClickableViewAccessibility")
    fun openFloatingPhotosVideosGallery(
        context: Context,
        manager: FloatingWindowManager,
        accentColor: Int,
        windowId: String = "floating_photos_videos",
        windowTitle: String = context.getString(R.string.floating_window_title_gallery)
    ): FloatingWindowManager.ManagedWindow? {
        return manager.openOrFocusWindow(
            windowId = windowId,
            title = windowTitle,
            accentColor = accentColor,
            iconRes = android.R.drawable.ic_menu_gallery,
            requiresTextInput = false
        ) { managed ->
            val container = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(context, 6f), dp(context, 6f), dp(context, 6f), dp(context, 6f))
            }

            val mainHandler = Handler(Looper.getMainLooper())
            val thumbJob = SupervisorJob()
            val thumbScope = CoroutineScope(Dispatchers.Main.immediate + thumbJob)
            var currentFilter = GalleryFilter.ALL
            var isFoldersTab = false
            var selectedFolderName: String? = null
            var searchQuery = ""
            var allIndexedFiles: List<GalleryMediaItem> = cachedAllFiles ?: cachedGalleryMedia ?: emptyList()
            var displayedItems: List<GalleryMediaItem> = emptyList()
            var currentViewerIndex = -1
            var isReleased = false
            var isVideoMuted = false
            var activeVideoPlayer: MediaPlayer? = null
            var galleryLoadSeq = 0

            // 1. Grid / Folders View
            val gridRoot = LinearLayout(context).apply {
                tag = "fw_gallery_root"
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    0,
                    1f
                )
            }

            // Clean 2-Tab Bar: All Files — New to Old | Folders — New to Old
            val twoTabRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = dp(context, 6f)
                }
            }
            val tabAllFiles = TextView(context).apply {
                tag = "fw_gallery_tab_all_files"
                text = "All Files — New to Old"
                textSize = 11.5f
                gravity = Gravity.CENTER
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setPadding(dp(context, 8f), dp(context, 7f), dp(context, 8f), dp(context, 7f))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginEnd = dp(context, 4f)
                }
            }
            val tabFolders = TextView(context).apply {
                tag = "fw_gallery_tab_folders"
                text = "Folders — New to Old"
                textSize = 11.5f
                gravity = Gravity.CENTER
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setPadding(dp(context, 8f), dp(context, 7f), dp(context, 8f), dp(context, 7f))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            twoTabRow.addView(tabAllFiles)
            twoTabRow.addView(tabFolders)

            val folderBreadcrumbBar = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                visibility = View.GONE
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = dp(context, 6f)
                }
            }
            val btnFolderBack = TextView(context).apply {
                tag = "fw_gallery_folder_back"
                text = "← All Folders"
                textSize = 11.5f
                setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
                setTextColor(accentColor)
                setPadding(dp(context, 8f), dp(context, 4f), dp(context, 8f), dp(context, 4f))
                background = GradientDrawable().apply {
                    cornerRadius = dp(context, 8f).toFloat()
                    setColor(Color.parseColor("#1E293B"))
                }
            }
            val tvFolderName = TextView(context).apply {
                textSize = 12f
                setTextColor(Color.WHITE)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = dp(context, 8f)
                }
            }
            folderBreadcrumbBar.addView(btnFolderBack)
            folderBreadcrumbBar.addView(tvFolderName)

            val btnGrantGalleryPerm = TextView(context).apply {
                tag = "fw_gallery_grant_perm"
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
                visibility = if (MediaPermissionActivity.hasImagesAndVideoPermission(context)) View.GONE else View.VISIBLE
            }

            val tvGalleryEmpty = TextView(context).apply {
                tag = "fw_gallery_empty"
                textSize = 12f
                setTextColor(Color.parseColor("#94A3B8"))
                gravity = Gravity.CENTER
                setPadding(dp(context, 12f), dp(context, 24f), dp(context, 12f), dp(context, 24f))
                visibility = View.GONE
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.CENTER
                )
            }
            val gridRecyclerView = RecyclerView(context).apply {
                tag = "fw_gallery_grid"
                layoutManager = GridLayoutManager(context, 3)
                setHasFixedSize(false)
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            }
            val foldersRecyclerView = RecyclerView(context).apply {
                tag = "fw_gallery_folders_list"
                layoutManager = LinearLayoutManager(context)
                visibility = View.GONE
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            }
            val gridHost = FrameLayout(context).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    0,
                    1f
                )
                addView(gridRecyclerView)
                addView(foldersRecyclerView)
                addView(tvGalleryEmpty)
            }
            gridRoot.addView(twoTabRow)
            gridRoot.addView(folderBreadcrumbBar)
            gridRoot.addView(btnGrantGalleryPerm)
            gridRoot.addView(gridHost)

            // 2. Full-bleed In-Window Pinch-Zoom Image Viewer
            val imageViewerView = LinearLayout(context).apply {
                tag = "fw_gallery_image_viewer"
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
                tag = "fw_gallery_image_back"
                text = "← Gallery"
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
            val zoomImageView = FloatingMediaFilesHelper.PinchZoomImageView(context).apply {
                tag = "fw_gallery_zoom_image"
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    0,
                    1f
                )
            }
            imageViewerView.addView(imageTopRow)
            imageViewerView.addView(zoomImageView)

            // 3. Full-bleed In-Window Video Player
            val videoPlayerView = LinearLayout(context).apply {
                tag = "fw_gallery_video_player"
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
                tag = "fw_gallery_video_back"
                text = "← Gallery"
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
                tag = "fw_gallery_video_view"
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    Gravity.CENTER
                )
            }
            videoHost.addView(videoView)

            val videoSeekBar = SeekBar(context).apply {
                tag = "fw_gallery_video_seekbar"
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
                tag = "fw_gallery_video_play_pause"
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
                tag = "fw_gallery_video_mute"
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

            // 4. Document / APK / Audio Info Viewer
            val docViewerView = LinearLayout(context).apply {
                tag = "fw_gallery_doc_viewer"
                orientation = LinearLayout.VERTICAL
                visibility = View.GONE
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    0,
                    1f
                )
            }
            val btnDocBack = TextView(context).apply {
                tag = "fw_gallery_doc_back"
                text = "← Gallery"
                textSize = 12f
                setTextColor(accentColor)
                setPadding(dp(context, 8f), dp(context, 4f), dp(context, 8f), dp(context, 4f))
                background = GradientDrawable().apply {
                    cornerRadius = dp(context, 8f).toFloat()
                    setColor(Color.parseColor("#1E293B"))
                }
            }
            val tvDocBody = TextView(context).apply {
                tag = "fw_gallery_doc_text"
                textSize = 12f
                setTextColor(Color.parseColor("#E2E8F0"))
                setPadding(dp(context, 10f), dp(context, 10f), dp(context, 10f), dp(context, 10f))
            }
            docViewerView.addView(btnDocBack)
            docViewerView.addView(ScrollView(context).apply {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
                addView(tvDocBody)
            })

            fun showPane(target: View) {
                gridRoot.visibility = if (target === gridRoot) View.VISIBLE else View.GONE
                imageViewerView.visibility = if (target === imageViewerView) View.VISIBLE else View.GONE
                videoPlayerView.visibility = if (target === videoPlayerView) View.VISIBLE else View.GONE
                docViewerView.visibility = if (target === docViewerView) View.VISIBLE else View.GONE
                if (target !== videoPlayerView) {
                    runCatching { if (videoView.isPlaying) videoView.pause() }
                }
            }

            fun openGalleryItem(item: GalleryMediaItem) {
                val found = displayedItems.indexOfFirst { it.uri == item.uri }
                if (found >= 0) {
                    currentViewerIndex = found
                }
                when (item.fileType) {
                    GalleryFileType.VIDEO -> {
                        showPane(videoPlayerView)
                        tvVideoTitle.text = item.displayName
                        isVideoMuted = false
                        btnVideoMute.text = "🔊 Sound"
                        btnVideoPlayPause.text = "⏸ Pause"
                        videoView.setOnPreparedListener { mp ->
                            activeVideoPlayer = mp
                            videoSeekBar.max = mp.duration.coerceAtLeast(1)
                            videoView.start()
                        }
                        videoView.setOnErrorListener { _, _, _ ->
                            Toast.makeText(context, "Cannot play ${item.displayName}", Toast.LENGTH_SHORT).show()
                            true
                        }
                        videoView.setVideoURI(item.uri)
                    }
                    GalleryFileType.IMAGE -> {
                        showPane(imageViewerView)
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
                    GalleryFileType.AUDIO -> {
                        openFloatingMusicPlayer(context, manager, accentColor)
                    }
                    GalleryFileType.DOCUMENT, GalleryFileType.APK -> {
                        showPane(docViewerView)
                        val body = if (item.fileType == GalleryFileType.APK) {
                            "APK Package: ${item.displayName}\nFolder: ${item.folderName}\nSize: ${android.text.format.Formatter.formatShortFileSize(context, item.sizeBytes)}"
                        } else {
                            runCatching {
                                context.contentResolver.openInputStream(item.uri)?.use { s ->
                                    val buf = ByteArray(64 * 1024)
                                    val r = s.read(buf)
                                    if (r > 0) String(buf, 0, r, Charsets.UTF_8) else "(Empty document)"
                                } ?: "${item.displayName}\nFolder: ${item.folderName}"
                            }.getOrElse { "${item.displayName}\nPreview unavailable." }
                        }
                        tvDocBody.text = body
                    }
                }
            }

            fun navigateRelativeVisualItem(delta: Int) {
                val visualIndices = displayedItems.indices.filter { idx ->
                    val ft = displayedItems[idx].fileType
                    ft == GalleryFileType.IMAGE || ft == GalleryFileType.VIDEO
                }
                if (visualIndices.size <= 1) return
                val curPos = visualIndices.indexOf(currentViewerIndex).let { if (it < 0) 0 else it }
                val targetPos = ((curPos + delta) % visualIndices.size + visualIndices.size) % visualIndices.size
                openGalleryItem(displayedItems[visualIndices[targetPos]])
            }

            zoomImageView.onHorizontalSwipeListener = { swipeLeftNext ->
                navigateRelativeVisualItem(if (swipeLeftNext) 1 else -1)
            }
            val videoSwipeDetector = GestureDetector(
                context,
                object : GestureDetector.SimpleOnGestureListener() {
                    override fun onFling(
                        e1: MotionEvent?,
                        e2: MotionEvent,
                        velocityX: Float,
                        velocityY: Float
                    ): Boolean {
                        if (e1 != null) {
                            val dx = e2.x - e1.x
                            val dy = e2.y - e1.y
                            if (kotlin.math.abs(dx) > 60f && kotlin.math.abs(dx) > kotlin.math.abs(dy)) {
                                navigateRelativeVisualItem(if (dx < 0f) 1 else -1)
                                return true
                            }
                        }
                        return false
                    }
                }
            )
            videoHost.setOnTouchListener { _, event ->
                videoSwipeDetector.onTouchEvent(event)
                true
            }

            val tileHeightPx = dp(context, 90f)
            val tileGap = dp(context, 4f)
            val thumbTargetPx = dp(context, 160f)

            class GalleryGridViewHolder(
                val tile: FrameLayout,
                val thumbImg: ImageView,
                val iconText: TextView,
                val badgeView: TextView,
                var loadJob: Job? = null,
                var boundUri: Uri? = null
            ) : RecyclerView.ViewHolder(tile)

            val galleryAdapter = object : RecyclerView.Adapter<GalleryGridViewHolder>() {
                private var items: List<GalleryMediaItem> = emptyList()

                fun submitList(newItems: List<GalleryMediaItem>) {
                    items = newItems
                    notifyDataSetChanged()
                }

                override fun getItemCount(): Int = items.size

                override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): GalleryGridViewHolder {
                    val tile = FrameLayout(context).apply {
                        layoutParams = RecyclerView.LayoutParams(
                            RecyclerView.LayoutParams.MATCH_PARENT,
                            tileHeightPx
                        ).apply {
                            setMargins(tileGap / 2, tileGap / 2, tileGap / 2, tileGap / 2)
                        }
                        background = GradientDrawable().apply {
                            cornerRadius = dp(context, 8f).toFloat()
                            setColor(Color.parseColor("#1E293B"))
                        }
                    }
                    val thumbImg = ImageView(context).apply {
                        scaleType = ImageView.ScaleType.CENTER_CROP
                        layoutParams = FrameLayout.LayoutParams(
                            FrameLayout.LayoutParams.MATCH_PARENT,
                            FrameLayout.LayoutParams.MATCH_PARENT
                        )
                    }
                    val iconText = TextView(context).apply {
                        textSize = 22f
                        gravity = Gravity.CENTER
                        visibility = View.GONE
                        layoutParams = FrameLayout.LayoutParams(
                            FrameLayout.LayoutParams.MATCH_PARENT,
                            FrameLayout.LayoutParams.MATCH_PARENT
                        )
                    }
                    val badgeView = TextView(context).apply {
                        textSize = 9.5f
                        maxLines = 1
                        ellipsize = TextUtils.TruncateAt.END
                        setTextColor(Color.WHITE)
                        setPadding(dp(context, 5f), dp(context, 2f), dp(context, 5f), dp(context, 2f))
                        background = GradientDrawable().apply {
                            cornerRadius = dp(context, 5f).toFloat()
                            setColor(Color.argb(180, 15, 23, 42))
                        }
                        layoutParams = FrameLayout.LayoutParams(
                            FrameLayout.LayoutParams.WRAP_CONTENT,
                            FrameLayout.LayoutParams.WRAP_CONTENT,
                            Gravity.BOTTOM or Gravity.END
                        ).apply {
                            marginEnd = dp(context, 4f)
                            bottomMargin = dp(context, 4f)
                        }
                        visibility = View.GONE
                    }
                    tile.addView(thumbImg)
                    tile.addView(iconText)
                    tile.addView(badgeView)
                    return GalleryGridViewHolder(tile, thumbImg, iconText, badgeView)
                }

                override fun onBindViewHolder(holder: GalleryGridViewHolder, position: Int) {
                    val item = items[position]
                    holder.loadJob?.cancel()
                    holder.boundUri = item.uri
                    holder.tile.tag = "fw_gallery_item_$position"
                    holder.thumbImg.contentDescription = item.displayName
                    holder.tile.setOnClickListener { openGalleryItem(item) }

                    when (item.fileType) {
                        GalleryFileType.VIDEO -> {
                            holder.iconText.visibility = View.GONE
                            holder.thumbImg.visibility = View.VISIBLE
                            holder.badgeView.visibility = View.VISIBLE
                            holder.badgeView.text = if (item.durationMs > 0L) {
                                "▶ ${FloatingMediaFilesHelper.formatDurationMs(item.durationMs)}"
                            } else {
                                "▶ Video"
                            }
                        }
                        GalleryFileType.IMAGE -> {
                            holder.iconText.visibility = View.GONE
                            holder.thumbImg.visibility = View.VISIBLE
                            holder.badgeView.visibility = View.GONE
                        }
                        GalleryFileType.AUDIO -> {
                            holder.thumbImg.visibility = View.GONE
                            holder.iconText.visibility = View.VISIBLE
                            holder.iconText.text = "🎵"
                            holder.badgeView.visibility = View.VISIBLE
                            holder.badgeView.text = item.displayName.take(10)
                        }
                        GalleryFileType.DOCUMENT -> {
                            holder.thumbImg.visibility = View.GONE
                            holder.iconText.visibility = View.VISIBLE
                            holder.iconText.text = "📄"
                            holder.badgeView.visibility = View.VISIBLE
                            holder.badgeView.text = item.displayName.take(10)
                        }
                        GalleryFileType.APK -> {
                            holder.thumbImg.visibility = View.GONE
                            holder.iconText.visibility = View.VISIBLE
                            holder.iconText.text = "📦"
                            holder.badgeView.visibility = View.VISIBLE
                            holder.badgeView.text = item.displayName.take(10)
                        }
                    }

                    if (item.fileType == GalleryFileType.IMAGE || item.fileType == GalleryFileType.VIDEO) {
                        val cached = thumbnailCache.get(thumbnailCacheKey(item, thumbTargetPx))
                        if (cached != null && !cached.isRecycled) {
                            holder.thumbImg.setImageBitmap(cached)
                        } else {
                            holder.thumbImg.setImageBitmap(null)
                            holder.loadJob = thumbScope.launch {
                                val bmp = withContext(Dispatchers.IO) {
                                    loadThumbnailBitmap(context, item, thumbTargetPx)
                                }
                                if (bmp != null && !isReleased && holder.boundUri == item.uri) {
                                    holder.thumbImg.setImageBitmap(bmp)
                                }
                            }
                        }
                    }
                }

                override fun onViewRecycled(holder: GalleryGridViewHolder) {
                    holder.loadJob?.cancel()
                    holder.loadJob = null
                    holder.boundUri = null
                    super.onViewRecycled(holder)
                }
            }
            gridRecyclerView.adapter = galleryAdapter

            data class FolderBucket(
                val folderName: String,
                val count: Int,
                val latestDateSec: Long
            )

            class FolderBucketViewHolder(
                val row: LinearLayout,
                val tvTitle: TextView,
                val tvMeta: TextView
            ) : RecyclerView.ViewHolder(row)

            lateinit var applyFilterAndRenderView: () -> Unit

            val foldersAdapter = object : RecyclerView.Adapter<FolderBucketViewHolder>() {
                private var buckets: List<FolderBucket> = emptyList()

                fun submitBuckets(newBuckets: List<FolderBucket>) {
                    buckets = newBuckets
                    notifyDataSetChanged()
                }

                override fun getItemCount(): Int = buckets.size

                override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FolderBucketViewHolder {
                    val row = LinearLayout(context).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(dp(context, 10f), dp(context, 10f), dp(context, 10f), dp(context, 10f))
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
                    row.addView(TextView(context).apply {
                        text = "📁"
                        textSize = 18f
                        setPadding(0, 0, dp(context, 10f), 0)
                    })
                    val col = LinearLayout(context).apply {
                        orientation = LinearLayout.VERTICAL
                        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    }
                    val tvTitle = TextView(context).apply {
                        textSize = 13f
                        setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
                        setTextColor(Color.WHITE)
                        maxLines = 1
                        ellipsize = TextUtils.TruncateAt.END
                    }
                    val tvMeta = TextView(context).apply {
                        textSize = 11f
                        setTextColor(Color.parseColor("#94A3B8"))
                    }
                    col.addView(tvTitle)
                    col.addView(tvMeta)
                    row.addView(col)
                    return FolderBucketViewHolder(row, tvTitle, tvMeta)
                }

                override fun onBindViewHolder(holder: FolderBucketViewHolder, position: Int) {
                    val bucket = buckets[position]
                    holder.row.tag = "fw_gallery_folder_row_$position"
                    holder.tvTitle.text = bucket.folderName
                    holder.tvMeta.text = "${bucket.count} files"
                    holder.row.setOnClickListener {
                        selectedFolderName = bucket.folderName
                        applyFilterAndRenderView()
                    }
                }
            }
            foldersRecyclerView.adapter = foldersAdapter

            fun renderTwoTabs() {
                tabAllFiles.background = GradientDrawable().apply {
                    cornerRadius = dp(context, 9f).toFloat()
                    setColor(if (!isFoldersTab) accentColor else Color.parseColor("#1E293B"))
                }
                tabAllFiles.setTextColor(if (!isFoldersTab) Color.BLACK else Color.WHITE)
                tabAllFiles.setTypeface(
                    android.graphics.Typeface.DEFAULT,
                    if (!isFoldersTab) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL
                )

                tabFolders.background = GradientDrawable().apply {
                    cornerRadius = dp(context, 9f).toFloat()
                    setColor(if (isFoldersTab) accentColor else Color.parseColor("#1E293B"))
                }
                tabFolders.setTextColor(if (isFoldersTab) Color.BLACK else Color.WHITE)
                tabFolders.setTypeface(
                    android.graphics.Typeface.DEFAULT,
                    if (isFoldersTab) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL
                )
            }

            // Build ☰ Hamburger Menu Header: Search + Filter Chips + Sync Button
            val menuHeader = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(context, 4f), dp(context, 2f), dp(context, 4f), dp(context, 4f))
            }
            val etMenuSearch = EditText(context).apply {
                tag = "fw_gallery_menu_search"
                hint = "Search files or folders..."
                setTextColor(Color.WHITE)
                setHintTextColor(Color.parseColor("#64748B"))
                textSize = 12f
                isSingleLine = true
                setPadding(dp(context, 10f), dp(context, 6f), dp(context, 10f), dp(context, 6f))
                background = GradientDrawable().apply {
                    cornerRadius = dp(context, 8f).toFloat()
                    setColor(Color.parseColor("#1E293B"))
                }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = dp(context, 6f)
                }
            }
            val chipsGridRow1 = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = dp(context, 4f)
                }
            }
            val chipsGridRow2 = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = dp(context, 6f)
                }
            }
            val btnSync = TextView(context).apply {
                tag = "fw_gallery_sync"
                text = "↻ Sync Files & Media"
                textSize = 12f
                setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
                setTextColor(Color.BLACK)
                gravity = Gravity.CENTER
                contentDescription = "Sync photos, videos and files"
                setPadding(dp(context, 10f), dp(context, 7f), dp(context, 10f), dp(context, 7f))
                background = GradientDrawable().apply {
                    cornerRadius = dp(context, 8f).toFloat()
                    setColor(accentColor)
                }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = dp(context, 4f)
                }
            }
            menuHeader.addView(etMenuSearch)
            menuHeader.addView(chipsGridRow1)
            menuHeader.addView(chipsGridRow2)
            menuHeader.addView(btnSync)
            managed.setMenuHeaderView(menuHeader)

            fun renderFilterChips() {
                chipsGridRow1.removeAllViews()
                chipsGridRow2.removeAllViews()
                val options = listOf(
                    GalleryFilter.ALL to "All",
                    GalleryFilter.PHOTOS to "Photos",
                    GalleryFilter.VIDEOS to "Videos",
                    GalleryFilter.AUDIO to "Audio",
                    GalleryFilter.DOCS to "Docs",
                    GalleryFilter.APK to "APK"
                )
                options.forEachIndexed { idx, (filter, label) ->
                    val selected = filter == currentFilter
                    val chip = TextView(context).apply {
                        tag = "fw_gallery_filter_${filter.name.lowercase(Locale.ROOT)}"
                        text = label
                        textSize = 11f
                        gravity = Gravity.CENTER
                        setTypeface(
                            android.graphics.Typeface.DEFAULT,
                            if (selected) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL
                        )
                        setTextColor(if (selected) Color.BLACK else Color.WHITE)
                        setPadding(dp(context, 6f), dp(context, 5f), dp(context, 6f), dp(context, 5f))
                        background = GradientDrawable().apply {
                            cornerRadius = dp(context, 7f).toFloat()
                            setColor(if (selected) accentColor else Color.parseColor("#1E293B"))
                        }
                        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                            marginEnd = if (idx % 3 != 2) dp(context, 4f) else 0
                        }
                        setOnClickListener {
                            currentFilter = filter
                            renderFilterChips()
                            applyFilterAndRenderView()
                        }
                    }
                    if (idx < 3) chipsGridRow1.addView(chip) else chipsGridRow2.addView(chip)
                }
            }

            applyFilterAndRenderView = {
                renderTwoTabs()
                btnGrantGalleryPerm.visibility = if (MediaPermissionActivity.hasImagesAndVideoPermission(context)) {
                    View.GONE
                } else {
                    View.VISIBLE
                }

                val q = searchQuery.trim().lowercase(Locale.ROOT)
                val filteredByTypeAndQuery = allIndexedFiles.filter { item ->
                    val matchesType = when (currentFilter) {
                        GalleryFilter.ALL -> true
                        GalleryFilter.PHOTOS -> item.fileType == GalleryFileType.IMAGE
                        GalleryFilter.VIDEOS -> item.fileType == GalleryFileType.VIDEO
                        GalleryFilter.AUDIO -> item.fileType == GalleryFileType.AUDIO
                        GalleryFilter.DOCS -> item.fileType == GalleryFileType.DOCUMENT
                        GalleryFilter.APK -> item.fileType == GalleryFileType.APK
                    }
                    val matchesSearch = q.isEmpty() ||
                        item.displayName.lowercase(Locale.ROOT).contains(q) ||
                        item.folderName.lowercase(Locale.ROOT).contains(q)
                    matchesType && matchesSearch
                }.sortedByDescending { it.dateAddedSec }

                if (isFoldersTab && selectedFolderName == null) {
                    folderBreadcrumbBar.visibility = View.GONE
                    gridRecyclerView.visibility = View.GONE
                    val grouped = filteredByTypeAndQuery
                        .groupBy { it.folderName.ifBlank { "Storage" } }
                        .map { (name, items) ->
                            FolderBucket(
                                folderName = name,
                                count = items.size,
                                latestDateSec = items.maxOfOrNull { it.dateAddedSec } ?: 0L
                            )
                        }
                        .sortedByDescending { it.latestDateSec }
                    foldersAdapter.submitBuckets(grouped)
                    if (grouped.isEmpty()) {
                        foldersRecyclerView.visibility = View.GONE
                        tvGalleryEmpty.visibility = View.VISIBLE
                        tvGalleryEmpty.text = if (!MediaPermissionActivity.hasImagesAndVideoPermission(context)) {
                            "Media permission required.\nTap \"Grant Media Permission\" above."
                        } else {
                            "No folders indexed yet."
                        }
                    } else {
                        tvGalleryEmpty.visibility = View.GONE
                        foldersRecyclerView.visibility = View.VISIBLE
                    }
                } else {
                    foldersRecyclerView.visibility = View.GONE
                    val folderScoped = if (isFoldersTab && selectedFolderName != null) {
                        folderBreadcrumbBar.visibility = View.VISIBLE
                        tvFolderName.text = selectedFolderName
                        filteredByTypeAndQuery.filter {
                            it.folderName.equals(selectedFolderName, ignoreCase = true)
                        }
                    } else {
                        folderBreadcrumbBar.visibility = View.GONE
                        filteredByTypeAndQuery
                    }
                    displayedItems = folderScoped
                    galleryAdapter.submitList(displayedItems)
                    if (displayedItems.isEmpty()) {
                        gridRecyclerView.visibility = View.GONE
                        tvGalleryEmpty.visibility = View.VISIBLE
                        tvGalleryEmpty.text = if (!MediaPermissionActivity.hasImagesAndVideoPermission(context)) {
                            "Media permission required to sync Photos & Videos.\nTap \"Grant Media Permission\" above or pick via ☰ Menu."
                        } else if (q.isNotEmpty()) {
                            "No files match \"$searchQuery\"."
                        } else {
                            "No media or files indexed yet.\nTap ↻ Sync inside ☰ Menu."
                        }
                    } else {
                        tvGalleryEmpty.visibility = View.GONE
                        gridRecyclerView.visibility = View.VISIBLE
                    }
                }
            }

            fun refreshAllMedia() {
                val seq = ++galleryLoadSeq
                thumbScope.launch {
                    val items = withContext(Dispatchers.IO) {
                        queryAllFiles(context)
                    }
                    withContext(Dispatchers.Main) {
                        if (isReleased || seq != galleryLoadSeq) return@withContext
                        allIndexedFiles = items
                        applyFilterAndRenderView()
                    }
                }
            }

            etMenuSearch.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    searchQuery = s?.toString().orEmpty()
                    applyFilterAndRenderView()
                }
                override fun afterTextChanged(s: Editable?) {}
            })

            tabAllFiles.setOnClickListener {
                isFoldersTab = false
                selectedFolderName = null
                applyFilterAndRenderView()
            }
            tabFolders.setOnClickListener {
                isFoldersTab = true
                selectedFolderName = null
                applyFilterAndRenderView()
            }
            btnFolderBack.setOnClickListener {
                selectedFolderName = null
                applyFilterAndRenderView()
            }

            btnGrantGalleryPerm.setOnClickListener {
                MediaPermissionActivity.launchPermissionPrompt(
                    context = context,
                    permissions = MediaPermissionActivity.requiredPermissionsForGallery()
                ) {
                    mainHandler.post {
                        if (!isReleased) refreshAllMedia()
                    }
                }
            }

            btnSync.setOnClickListener {
                refreshAllMedia()
                managed.closeMenu()
            }
            btnImageBack.setOnClickListener { showPane(gridRoot) }
            btnVideoBack.setOnClickListener { showPane(gridRoot) }
            btnDocBack.setOnClickListener { showPane(gridRoot) }

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

            val ticker = object : Runnable {
                override fun run() {
                    if (isReleased) return
                    runCatching {
                        if (videoPlayerView.visibility == View.VISIBLE && videoView.duration > 0) {
                            val pos = videoView.currentPosition
                            val dur = videoView.duration
                            videoSeekBar.max = dur
                            videoSeekBar.progress = pos
                            tvVideoTime.text = "${FloatingMediaFilesHelper.formatDurationMs(pos.toLong())} / ${FloatingMediaFilesHelper.formatDurationMs(dur.toLong())}"
                        }
                    }
                    mainHandler.postDelayed(this, 500L)
                }
            }
            mainHandler.postDelayed(ticker, 500L)

            managed.setMenuItems(
                listOf(
                    FloatingWindowManager.WindowMenuItem("gallery_grid", "🖼 All Files & Media Grid", "fw_gallery_menu_grid") {
                        showPane(gridRoot)
                    },
                    FloatingWindowManager.WindowMenuItem("gallery_sync", "↻ Sync Media", "fw_gallery_menu_sync") {
                        refreshAllMedia()
                        showPane(gridRoot)
                    },
                    FloatingWindowManager.WindowMenuItem("pick_photo", "📷 Pick Photo (Photo Picker)", "fw_gallery_menu_pick") {
                        ViewerPickerActivity.pendingCallback = { uri, isImg ->
                            val name = uri.lastPathSegment?.substringAfterLast('/') ?: "Selected Media"
                            val mime = runCatching { context.contentResolver.getType(uri) }.getOrNull().orEmpty()
                            if (isImg || mime.startsWith("image/")) {
                                openGalleryItem(GalleryMediaItem(0L, name, uri, false, 0L, 0L, "Picked", GalleryFileType.IMAGE))
                            } else if (mime.startsWith("video/")) {
                                openGalleryItem(GalleryMediaItem(0L, name, uri, true, 0L, 0L, "Picked", GalleryFileType.VIDEO))
                            } else {
                                val body = runCatching {
                                    context.contentResolver.openInputStream(uri)?.use { s ->
                                        val buf = ByteArray(64 * 1024)
                                        val r = s.read(buf)
                                        if (r > 0) String(buf, 0, r, Charsets.UTF_8) else "(Empty)"
                                    } ?: "Unable to open file."
                                }.getOrElse { "Preview unavailable." }
                                tvDocBody.text = body
                                showPane(docViewerView)
                            }
                        }
                        context.startActivity(
                            Intent(context, ViewerPickerActivity::class.java).apply {
                                putExtra(ViewerPickerActivity.EXTRA_PICK_MODE, ViewerPickerActivity.MODE_IMAGE)
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                        )
                    },
                    FloatingWindowManager.WindowMenuItem("open_media_files", "🎵 Open Media & Files", "fw_gallery_menu_media_files") {
                        FloatingMediaFilesHelper.openFloatingMediaFiles(context, manager, accentColor)
                    }
                )
            )

            renderFilterChips()
            if (allIndexedFiles.isNotEmpty()) {
                applyFilterAndRenderView()
            }
            refreshAllMedia()
            showPane(gridRoot)

            container.addView(gridRoot)
            container.addView(imageViewerView)
            container.addView(videoPlayerView)
            container.addView(docViewerView)

            val cleanup: () -> Unit = {
                isReleased = true
                thumbJob.cancel()
                mainHandler.removeCallbacks(ticker)
                ViewerPickerActivity.pendingCallback = null
                runCatching { videoView.stopPlayback() }
                activeVideoPlayer = null
            }
            container to cleanup
        }
    }
}
