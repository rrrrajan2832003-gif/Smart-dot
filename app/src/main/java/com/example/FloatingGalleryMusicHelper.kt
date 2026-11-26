package com.example

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
import android.text.TextUtils
import android.util.LruCache
import android.util.Size
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/**
 * In-app Floating Media libraries:
 * 1. Dedicated Floating Music & Voice Player (queries MediaStore.Audio.Media on Dispatchers.IO + Sync button + MediaPlayer).
 * 2. True In-App Floating Photos & Videos Thumbnail Grid (queries MediaStore.Images & MediaStore.Video
 *    on Dispatchers.IO sorted by DATE_ADDED DESC, displaying a 3-column thumbnail grid with built-in
 *    PinchZoomImageView and VideoView player).
 */
object FloatingGalleryMusicHelper {

    enum class GalleryFilter {
        ALL,
        PHOTOS,
        VIDEOS
    }

    data class AudioTrackItem(
        val displayName: String,
        val uri: Uri,
        val durationMs: Long,
        val folderName: String,
        val sizeBytes: Long
    )

    data class GalleryMediaItem(
        val id: Long,
        val displayName: String,
        val uri: Uri,
        val isVideo: Boolean,
        val durationMs: Long,
        val dateAddedSec: Long,
        val folderName: String
    )

    private const val PREF_MUSIC_HIDE_SHORT = "music_hide_short_audio"
    private const val SHORT_AUDIO_THRESHOLD_MS = 60_000L

    private val AUDIO_EXTS = setOf("mp3", "m4a", "wav", "ogg", "aac", "flac", "opus", "amr")
    private val IMAGE_EXTS = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "heic")
    private val VIDEO_EXTS = setOf("mp4", "mkv", "webm", "3gp", "mov", "avi")

    private val thumbnailCache = object : LruCache<String, Bitmap>(16 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount.coerceAtLeast(1)
    }

    private fun dp(context: Context, value: Float): Int =
        (value * context.resources.displayMetrics.density.coerceAtLeast(1f)).toInt()

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
                    val rawPath = if (pathIdx >= 0) cursor.getString(pathIdx).orEmpty() else ""
                    val folder = extractFolderLabel(rawPath, "Music")
                    addUnique(
                        AudioTrackItem(
                            displayName = name,
                            uri = ContentUris.withAppendedId(collection, id),
                            durationMs = duration,
                            folderName = folder,
                            sizeBytes = size
                        )
                    )
                }
            }
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
                                sizeBytes = file.length()
                            )
                        )
                    }
            }
        }
        return result
    }

    fun queryPhotosAndVideos(
        context: Context,
        filter: GalleryFilter = GalleryFilter.ALL
    ): List<GalleryMediaItem> {
        val result = ArrayList<GalleryMediaItem>()
        val seenKeys = HashSet<String>()

        fun addUnique(item: GalleryMediaItem) {
            val key = "${item.isVideo}|${item.displayName.lowercase(Locale.ROOT)}|${item.uri}"
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
                        val rawPath = if (pathIdx >= 0) cursor.getString(pathIdx).orEmpty() else ""
                        addUnique(
                            GalleryMediaItem(
                                id = id,
                                displayName = name,
                                uri = ContentUris.withAppendedId(imgUri, id),
                                isVideo = false,
                                durationMs = 0L,
                                dateAddedSec = dateSec,
                                folderName = extractFolderLabel(rawPath, "Pictures")
                            )
                        )
                    }
                }
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
                        val rawPath = if (pathIdx >= 0) cursor.getString(pathIdx).orEmpty() else ""
                        addUnique(
                            GalleryMediaItem(
                                id = id,
                                displayName = name,
                                uri = ContentUris.withAppendedId(vidUri, id),
                                isVideo = true,
                                durationMs = dur,
                                dateAddedSec = dateSec,
                                folderName = extractFolderLabel(rawPath, "Movies")
                            )
                        )
                    }
                }
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
                                folderName = file.parentFile?.name ?: dir.name
                            )
                        )
                    }
                }
            }
        }

        return result.sortedByDescending { it.dateAddedSec }
    }

    private fun extractFolderLabel(rawPath: String, defaultLabel: String): String {
        val clean = rawPath.trim().trimEnd('/')
        if (clean.isEmpty()) return defaultLabel
        return clean.substringAfterLast('/').ifBlank { defaultLabel }
    }

    private fun thumbnailCacheKey(item: GalleryMediaItem, sizePx: Int): String = "${item.uri}|$sizePx"

    private fun loadThumbnailBitmap(context: Context, item: GalleryMediaItem, sizePx: Int): Bitmap? {
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
     * Dedicated Floating Music & Voice Player window with pre-loaded MediaStore audio library
     * and 1-tap Sync (↻) refresh.
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
                setPadding(dp(context, 8f), dp(context, 8f), dp(context, 8f), dp(context, 8f))
            }

            val mainHandler = Handler(Looper.getMainLooper())
            val musicJob = SupervisorJob()
            val musicScope = CoroutineScope(Dispatchers.Main.immediate + musicJob)
            val prefs = context.getSharedPreferences(SelectionToolbarHelper.PREFS_NAME, Context.MODE_PRIVATE)
            var hideShortAudio = prefs.getBoolean(PREF_MUSIC_HIDE_SHORT, true)
            var allQueriedTracks: List<AudioTrackItem> = emptyList()
            var audioQueue: List<AudioTrackItem> = emptyList()
            var currentAudioIndex = -1
            var mediaPlayer: MediaPlayer? = null
            var isReleased = false
            var refreshSeq = 0

            // Top Library Header + Sync (↻) Button
            val headerRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = dp(context, 4f)
                }
            }
            val tvCount = TextView(context).apply {
                tag = "fw_music_count"
                text = "Audio Library (0)"
                textSize = 12f
                setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
                setTextColor(Color.parseColor("#CBD5E1"))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val btnSync = TextView(context).apply {
                tag = "fw_music_sync"
                text = "↻ Sync"
                textSize = 11.5f
                setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
                setTextColor(Color.BLACK)
                contentDescription = "Sync audio library"
                setPadding(dp(context, 10f), dp(context, 6f), dp(context, 10f), dp(context, 6f))
                background = GradientDrawable().apply {
                    cornerRadius = dp(context, 8f).toFloat()
                    setColor(accentColor)
                }
            }
            headerRow.addView(tvCount)
            headerRow.addView(btnSync)

            // Filter Row: "Hide < 1 Min Audio" Switch
            val filterBar = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(context, 8f), dp(context, 4f), dp(context, 8f), dp(context, 4f))
                background = GradientDrawable().apply {
                    cornerRadius = dp(context, 8f).toFloat()
                    setColor(Color.parseColor("#162032"))
                }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = dp(context, 6f)
                }
            }
            val tvHideShortLabel = TextView(context).apply {
                text = "Hide < 1 Min Audio"
                textSize = 11.5f
                setTextColor(Color.parseColor("#E2E8F0"))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val switchHideShort = Switch(context).apply {
                tag = "fw_music_hide_short_toggle"
                isChecked = hideShortAudio
                contentDescription = "Hide audio tracks under 1 minute"
            }
            filterBar.addView(tvHideShortLabel)
            filterBar.addView(switchHideShort)

            // Player Card
            val playerCard = LinearLayout(context).apply {
                tag = "fw_music_player_card"
                orientation = LinearLayout.VERTICAL
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
                text = "Select a track to play"
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
            val btnPlayPause = makeCtrlBtn("▶ Play", "fw_music_play_pause", primary = true)
            val btnNext = makeCtrlBtn("Next ⏭", "fw_music_next")
            controlsRow.addView(btnPrev)
            controlsRow.addView(btnPlayPause)
            controlsRow.addView(btnNext)

            playerCard.addView(tvNowPlaying)
            playerCard.addView(seekBar)
            playerCard.addView(tvTime)
            playerCard.addView(controlsRow)

            // Grant Media Permission Banner (visible when READ_MEDIA_AUDIO / READ_EXTERNAL_STORAGE missing)
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

            fun updatePlayPauseLabel() {
                val playing = runCatching { mediaPlayer?.isPlaying == true }.getOrDefault(false)
                btnPlayPause.text = if (playing) "⏸ Pause" else "▶ Play"
            }

            fun playTrackAt(idx: Int) {
                val track = audioQueue.getOrNull(idx) ?: return
                currentAudioIndex = idx
                tvNowPlaying.text = "🎵 ${track.displayName}"
                tvTime.text = "00:00 / ${FloatingMediaFilesHelper.formatDurationMs(track.durationMs)}"
                seekBar.progress = 0

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
                        seekBar.max = mp.duration.coerceAtLeast(1)
                        mp.start()
                        updatePlayPauseLabel()
                    }
                    setOnCompletionListener {
                        if (currentAudioIndex + 1 < audioQueue.size) {
                            playTrackAt(currentAudioIndex + 1)
                        } else {
                            updatePlayPauseLabel()
                        }
                    }
                    setOnErrorListener { _, _, _ ->
                        Toast.makeText(context, "Cannot play ${track.displayName}", Toast.LENGTH_SHORT).show()
                        true
                    }
                }
                val loaded = runCatching {
                    player.setDataSource(context, track.uri)
                    player.prepareAsync()
                    true
                }.getOrDefault(false)
                if (loaded) {
                    mediaPlayer = player
                    updatePlayPauseLabel()
                } else {
                    runCatching { player.release() }
                    Toast.makeText(context, "Unable to open ${track.displayName}", Toast.LENGTH_SHORT).show()
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
                audioQueue = if (hideShortAudio) {
                    allQueriedTracks.filter { it.durationMs >= SHORT_AUDIO_THRESHOLD_MS }
                } else {
                    allQueriedTracks
                }
                tvCount.text = if (hideShortAudio && allQueriedTracks.size != audioQueue.size) {
                    "Audio Library (${audioQueue.size} / ${allQueriedTracks.size})"
                } else {
                    "Audio Library (${audioQueue.size})"
                }
                musicAdapter.submitList(audioQueue)
                if (audioQueue.isEmpty()) {
                    musicRecyclerView.visibility = View.GONE
                    tvMusicEmpty.visibility = View.VISIBLE
                    tvMusicEmpty.text = when {
                        !MediaPermissionActivity.hasAudioPermission(context) ->
                            "Media permission required to list all device songs.\nTap \"Grant Media Permission\" above or open via ☰ Menu."
                        allQueriedTracks.isNotEmpty() && hideShortAudio ->
                            "All ${allQueriedTracks.size} tracks are under 1 min.\nTurn off \"Hide < 1 Min Audio\" above to view them."
                        else ->
                            "No audio or voice recordings found in MediaStore.\nTap ↻ Sync or open an audio file from ☰ Menu."
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

            switchHideShort.setOnCheckedChangeListener { _, isChecked ->
                hideShortAudio = isChecked
                prefs.edit().putBoolean(PREF_MUSIC_HIDE_SHORT, isChecked).apply()
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

            btnSync.setOnClickListener { refreshAudioLibrary() }
            btnPlayPause.setOnClickListener {
                val mp = mediaPlayer
                if (mp != null) {
                    runCatching { if (mp.isPlaying) mp.pause() else mp.start() }
                    updatePlayPauseLabel()
                } else if (audioQueue.isNotEmpty()) {
                    playTrackAt(0)
                }
            }
            btnPrev.setOnClickListener {
                if (audioQueue.isNotEmpty()) {
                    val prev = if (currentAudioIndex <= 0) audioQueue.lastIndex else currentAudioIndex - 1
                    playTrackAt(prev)
                }
            }
            btnNext.setOnClickListener {
                if (audioQueue.isNotEmpty()) {
                    val next = (currentAudioIndex + 1) % audioQueue.size
                    playTrackAt(next)
                }
            }
            seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) runCatching { mediaPlayer?.seekTo(progress) }
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })

            val ticker = object : Runnable {
                override fun run() {
                    if (isReleased) return
                    val mp = mediaPlayer
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
                    FloatingWindowManager.WindowMenuItem("sync_audio", "↻ Sync Audio Library", "fw_music_menu_sync") {
                        refreshAudioLibrary()
                    },
                    FloatingWindowManager.WindowMenuItem("open_audio_saf", "📂 Open Audio File (SAF)", "fw_music_menu_open") {
                        ViewerPickerActivity.pendingCallback = { uri, _ ->
                            val name = uri.lastPathSegment?.substringAfterLast('/') ?: "Audio Track"
                            val item = AudioTrackItem(name, uri, SHORT_AUDIO_THRESHOLD_MS, "Picked", 0L)
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
                    }
                )
            )

            refreshAudioLibrary()
            container.addView(headerRow)
            container.addView(filterBar)
            container.addView(playerCard)
            container.addView(btnGrantAudioPerm)
            container.addView(listHost)

            val cleanup: () -> Unit = {
                isReleased = true
                musicJob.cancel()
                mainHandler.removeCallbacks(ticker)
                runCatching {
                    mediaPlayer?.stop()
                    mediaPlayer?.release()
                }
                mediaPlayer = null
            }
            container to cleanup
        }
    }

    /**
     * True In-App Floating Photos & Videos Thumbnail Grid using MediaStore.Images & MediaStore.Video
     * sorted by DATE_ADDED DESC. Never launches the external system file manager on open.
     */
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
            var isReleased = false
            var isVideoMuted = false
            var activeVideoPlayer: MediaPlayer? = null
            var galleryLoadSeq = 0

            // 1. Grid View
            val gridRoot = LinearLayout(context).apply {
                tag = "fw_gallery_root"
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    0,
                    1f
                )
            }

            val filterRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = dp(context, 6f)
                }
            }
            val chipsContainer = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val btnSync = TextView(context).apply {
                tag = "fw_gallery_sync"
                text = "↻"
                textSize = 14f
                setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
                setTextColor(Color.BLACK)
                gravity = Gravity.CENTER
                contentDescription = "Sync photos and videos"
                setPadding(dp(context, 10f), dp(context, 4f), dp(context, 10f), dp(context, 4f))
                background = GradientDrawable().apply {
                    cornerRadius = dp(context, 8f).toFloat()
                    setColor(accentColor)
                }
            }
            filterRow.addView(chipsContainer)
            filterRow.addView(btnSync)

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
            val gridHost = FrameLayout(context).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    0,
                    1f
                )
                addView(gridRecyclerView)
                addView(tvGalleryEmpty)
            }
            gridRoot.addView(filterRow)
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

            // 4. Document Text Viewer (for SAF fallback if explicitly selected from menu)
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
                if (item.isVideo) {
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
                } else {
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
            }

            lateinit var renderGrid: () -> Unit

            fun renderFilterChips() {
                chipsContainer.removeAllViews()
                val options = listOf(
                    GalleryFilter.ALL to "All",
                    GalleryFilter.PHOTOS to "Photos",
                    GalleryFilter.VIDEOS to "Videos"
                )
                options.forEach { (filter, label) ->
                    val selected = filter == currentFilter
                    val chip = TextView(context).apply {
                        tag = "fw_gallery_filter_${filter.name.lowercase(Locale.ROOT)}"
                        text = label
                        textSize = 11.5f
                        setTypeface(android.graphics.Typeface.DEFAULT, if (selected) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
                        setTextColor(if (selected) Color.BLACK else Color.WHITE)
                        setPadding(dp(context, 10f), dp(context, 5f), dp(context, 10f), dp(context, 5f))
                        background = GradientDrawable().apply {
                            cornerRadius = dp(context, 9f).toFloat()
                            setColor(if (selected) accentColor else Color.parseColor("#1E293B"))
                        }
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        ).apply {
                            marginEnd = dp(context, 6f)
                        }
                        setOnClickListener {
                            currentFilter = filter
                            renderFilterChips()
                            renderGrid()
                        }
                    }
                    chipsContainer.addView(chip)
                }
            }

            val tileHeightPx = dp(context, 90f)
            val tileGap = dp(context, 4f)
            val thumbTargetPx = dp(context, 160f)

            class GalleryGridViewHolder(
                val tile: FrameLayout,
                val thumbImg: ImageView,
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
                    val badgeView = TextView(context).apply {
                        textSize = 10f
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
                    tile.addView(badgeView)
                    return GalleryGridViewHolder(tile, thumbImg, badgeView)
                }

                override fun onBindViewHolder(holder: GalleryGridViewHolder, position: Int) {
                    val item = items[position]
                    holder.loadJob?.cancel()
                    holder.boundUri = item.uri
                    holder.tile.tag = "fw_gallery_item_$position"
                    holder.thumbImg.contentDescription = item.displayName
                    holder.tile.setOnClickListener { openGalleryItem(item) }

                    if (item.isVideo) {
                        holder.badgeView.visibility = View.VISIBLE
                        holder.badgeView.text = if (item.durationMs > 0L) {
                            "▶ ${FloatingMediaFilesHelper.formatDurationMs(item.durationMs)}"
                        } else {
                            "▶ Video"
                        }
                    } else {
                        holder.badgeView.visibility = View.GONE
                    }

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

                override fun onViewRecycled(holder: GalleryGridViewHolder) {
                    holder.loadJob?.cancel()
                    holder.loadJob = null
                    holder.boundUri = null
                    super.onViewRecycled(holder)
                }
            }
            gridRecyclerView.adapter = galleryAdapter

            renderGrid = {
                btnGrantGalleryPerm.visibility = if (MediaPermissionActivity.hasImagesAndVideoPermission(context)) {
                    View.GONE
                } else {
                    View.VISIBLE
                }
                val seq = ++galleryLoadSeq
                val activeFilter = currentFilter
                thumbScope.launch {
                    val items = withContext(Dispatchers.IO) {
                        queryPhotosAndVideos(context, activeFilter)
                    }
                    withContext(Dispatchers.Main) {
                        if (isReleased || seq != galleryLoadSeq) return@withContext
                        btnGrantGalleryPerm.visibility = if (MediaPermissionActivity.hasImagesAndVideoPermission(context)) {
                            View.GONE
                        } else {
                            View.VISIBLE
                        }
                        galleryAdapter.submitList(items)
                        if (items.isEmpty()) {
                            gridRecyclerView.visibility = View.GONE
                            tvGalleryEmpty.visibility = View.VISIBLE
                            tvGalleryEmpty.text = if (!MediaPermissionActivity.hasImagesAndVideoPermission(context)) {
                                "Media permission required to sync Photos & Videos.\nTap \"Grant Media Permission\" above or pick via ☰ Menu."
                            } else {
                                "No photos or videos indexed yet.\nTap ↻ Sync or open ☰ Menu to browse."
                            }
                        } else {
                            tvGalleryEmpty.visibility = View.GONE
                            gridRecyclerView.visibility = View.VISIBLE
                        }
                    }
                }
            }

            btnGrantGalleryPerm.setOnClickListener {
                MediaPermissionActivity.launchPermissionPrompt(
                    context = context,
                    permissions = MediaPermissionActivity.requiredPermissionsForGallery()
                ) {
                    mainHandler.post {
                        if (!isReleased) renderGrid()
                    }
                }
            }

            btnSync.setOnClickListener { renderGrid() }
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
                    FloatingWindowManager.WindowMenuItem("gallery_grid", "🖼 Photos & Videos Grid", "fw_gallery_menu_grid") {
                        showPane(gridRoot)
                    },
                    FloatingWindowManager.WindowMenuItem("gallery_sync", "↻ Sync Media", "fw_gallery_menu_sync") {
                        renderGrid()
                        showPane(gridRoot)
                    },
                    FloatingWindowManager.WindowMenuItem("pick_photo", "📷 Pick Photo (Photo Picker)", "fw_gallery_menu_pick") {
                        ViewerPickerActivity.pendingCallback = { uri, isImg ->
                            val name = uri.lastPathSegment?.substringAfterLast('/') ?: "Selected Media"
                            val mime = runCatching { context.contentResolver.getType(uri) }.getOrNull().orEmpty()
                            if (isImg || mime.startsWith("image/")) {
                                openGalleryItem(GalleryMediaItem(0L, name, uri, false, 0L, 0L, "Picked"))
                            } else if (mime.startsWith("video/")) {
                                openGalleryItem(GalleryMediaItem(0L, name, uri, true, 0L, 0L, "Picked"))
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
            renderGrid()
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
