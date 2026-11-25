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
import android.util.Size
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import android.widget.VideoView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/**
 * Play-Policy-compliant in-app Floating Media libraries:
 * 1. Dedicated Floating Music & Voice Player (queries MediaStore.Audio.Media + Sync button + MediaPlayer).
 * 2. True In-App Floating Photos & Videos Thumbnail Grid (queries MediaStore.Images & MediaStore.Video
 *    sorted by DATE_ADDED DESC, displaying a 3-column thumbnail grid with built-in PinchZoomImageView
 *    and VideoView player — never launching the external system file manager).
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

    private val AUDIO_EXTS = setOf("mp3", "m4a", "wav", "ogg", "aac", "flac", "opus", "amr")
    private val IMAGE_EXTS = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "heic")
    private val VIDEO_EXTS = setOf("mp4", "mkv", "webm", "3gp", "mov", "avi")

    private fun dp(context: Context, value: Float): Int =
        (value * context.resources.displayMetrics.density.coerceAtLeast(1f)).toInt()

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

                var count = 0
                while (cursor.moveToNext() && count < 150) {
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
                    count++
                }
            }
        }

        @Suppress("DEPRECATION")
        val fallbackDirs = listOfNotNull(
            runCatching { Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC) }.getOrNull(),
            runCatching { Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS) }.getOrNull(),
            runCatching { File(Environment.getExternalStorageDirectory(), "Recordings") }.getOrNull()
        )
        fallbackDirs.distinctBy { it.absolutePath }.forEach { dir ->
            if (dir.exists() && dir.isDirectory && dir.canRead()) {
                dir.listFiles()
                    ?.filter { it.isFile && !it.isHidden && it.extension.lowercase(Locale.ROOT) in AUDIO_EXTS }
                    ?.sortedByDescending { it.lastModified() }
                    ?.take(50)
                    ?.forEach { file ->
                        addUnique(
                            AudioTrackItem(
                                displayName = file.name,
                                uri = Uri.fromFile(file),
                                durationMs = 0L,
                                folderName = dir.name,
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
                    var count = 0
                    while (cursor.moveToNext() && count < 120) {
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
                        count++
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
                    var count = 0
                    while (cursor.moveToNext() && count < 90) {
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
                        count++
                    }
                }
            }
        }

        @Suppress("DEPRECATION")
        val fallbackDirs = listOfNotNull(
            runCatching { Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES) }.getOrNull(),
            runCatching { Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM) }.getOrNull(),
            runCatching { Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES) }.getOrNull()
        )
        fallbackDirs.distinctBy { it.absolutePath }.forEach { dir ->
            if (dir.exists() && dir.isDirectory && dir.canRead()) {
                dir.listFiles()?.filter { it.isFile && !it.isHidden }?.forEach { file ->
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
                                folderName = dir.name
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

    private fun loadThumbnailBitmap(context: Context, item: GalleryMediaItem, sizePx: Int): Bitmap? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val loaded = runCatching {
                context.contentResolver.loadThumbnail(item.uri, Size(sizePx, sizePx), null)
            }.getOrNull()
            if (loaded != null) return loaded
        }
        if (item.isVideo) {
            return null
        }
        return runCatching {
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
            var audioQueue: List<AudioTrackItem> = emptyList()
            var currentAudioIndex = -1
            var mediaPlayer: MediaPlayer? = null
            var isReleased = false

            // Top Library Header + Sync (↻) Button
            val headerRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = dp(context, 6f)
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

            // Scrollable Audio Track List
            val listContainer = LinearLayout(context).apply {
                tag = "fw_music_list"
                orientation = LinearLayout.VERTICAL
            }
            val listScroll = ScrollView(context).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    0,
                    1f
                )
                addView(listContainer)
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

            fun refreshAudioLibrary() {
                listContainer.removeAllViews()
                audioQueue = queryAudioLibrary(context)
                tvCount.text = "Audio Library (${audioQueue.size})"
                if (audioQueue.isEmpty()) {
                    listContainer.addView(TextView(context).apply {
                        tag = "fw_music_empty"
                        text = "No audio or voice recordings found in MediaStore.\nTap ↻ Sync or open an audio file from ☰ Menu."
                        textSize = 12f
                        setTextColor(Color.parseColor("#94A3B8"))
                        gravity = Gravity.CENTER
                        setPadding(dp(context, 12f), dp(context, 20f), dp(context, 12f), dp(context, 20f))
                    })
                    return
                }
                audioQueue.forEachIndexed { idx, track ->
                    val row = LinearLayout(context).apply {
                        tag = "fw_music_row_$idx"
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(dp(context, 10f), dp(context, 8f), dp(context, 10f), dp(context, 8f))
                        background = GradientDrawable().apply {
                            cornerRadius = dp(context, 8f).toFloat()
                            setColor(Color.parseColor("#1E293B"))
                        }
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        ).apply {
                            bottomMargin = dp(context, 5f)
                        }
                        setOnClickListener { playTrackAt(idx) }
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
                    textCol.addView(TextView(context).apply {
                        text = track.displayName
                        textSize = 12.5f
                        setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
                        setTextColor(Color.WHITE)
                        maxLines = 1
                        ellipsize = TextUtils.TruncateAt.END
                    })
                    val durLabel = if (track.durationMs > 0L) {
                        FloatingMediaFilesHelper.formatDurationMs(track.durationMs)
                    } else {
                        "Audio"
                    }
                    textCol.addView(TextView(context).apply {
                        text = "${track.folderName} • $durLabel"
                        textSize = 10.5f
                        setTextColor(Color.parseColor("#94A3B8"))
                    })
                    row.addView(textCol)
                    listContainer.addView(row)
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
                            val item = AudioTrackItem(name, uri, 0L, "Picked", 0L)
                            audioQueue = listOf(item) + audioQueue
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
            container.addView(playerCard)
            container.addView(listScroll)

            val cleanup: () -> Unit = {
                isReleased = true
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
            val thumbJob = Job()
            val thumbScope = CoroutineScope(Dispatchers.Main + thumbJob)
            var currentFilter = GalleryFilter.ALL
            var isReleased = false
            var isVideoMuted = false
            var activeVideoPlayer: MediaPlayer? = null

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

            val gridContainer = LinearLayout(context).apply {
                tag = "fw_gallery_grid"
                orientation = LinearLayout.VERTICAL
            }
            val gridScroll = ScrollView(context).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    0,
                    1f
                )
                addView(gridContainer)
            }
            gridRoot.addView(filterRow)
            gridRoot.addView(gridScroll)

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

            renderGrid = {
                gridContainer.removeAllViews()
                val items = queryPhotosAndVideos(context, currentFilter)
                if (items.isEmpty()) {
                    gridContainer.addView(TextView(context).apply {
                        tag = "fw_gallery_empty"
                        text = "No photos or videos indexed yet.\nTap ↻ Sync or open ☰ Menu to browse."
                        textSize = 12f
                        setTextColor(Color.parseColor("#94A3B8"))
                        gravity = Gravity.CENTER
                        setPadding(dp(context, 12f), dp(context, 24f), dp(context, 12f), dp(context, 24f))
                    })
                } else {
                    val cols = 3
                    val tileHeightPx = dp(context, 90f)
                    val tileGap = dp(context, 4f)
                    val thumbTargetPx = dp(context, 160f)
                    val chunks = items.chunked(cols)
                    var globalIdx = 0
                    chunks.forEach { rowItems ->
                        val row = LinearLayout(context).apply {
                            orientation = LinearLayout.HORIZONTAL
                            layoutParams = LinearLayout.LayoutParams(
                                LinearLayout.LayoutParams.MATCH_PARENT,
                                LinearLayout.LayoutParams.WRAP_CONTENT
                            ).apply {
                                bottomMargin = tileGap
                            }
                        }
                        rowItems.forEach { item ->
                            val itemIdx = globalIdx++
                            val tile = FrameLayout(context).apply {
                                tag = "fw_gallery_item_$itemIdx"
                                layoutParams = LinearLayout.LayoutParams(0, tileHeightPx, 1f).apply {
                                    marginEnd = tileGap / 2
                                    marginStart = tileGap / 2
                                }
                                background = GradientDrawable().apply {
                                    cornerRadius = dp(context, 8f).toFloat()
                                    setColor(Color.parseColor("#1E293B"))
                                }
                                setOnClickListener { openGalleryItem(item) }
                            }
                            val thumbImg = ImageView(context).apply {
                                scaleType = ImageView.ScaleType.CENTER_CROP
                                contentDescription = item.displayName
                                layoutParams = FrameLayout.LayoutParams(
                                    FrameLayout.LayoutParams.MATCH_PARENT,
                                    FrameLayout.LayoutParams.MATCH_PARENT
                                )
                            }
                            tile.addView(thumbImg)
                            if (item.isVideo) {
                                val badgeText = if (item.durationMs > 0L) {
                                    "▶ ${FloatingMediaFilesHelper.formatDurationMs(item.durationMs)}"
                                } else {
                                    "▶ Video"
                                }
                                tile.addView(TextView(context).apply {
                                    text = badgeText
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
                                })
                            }
                            thumbScope.launch {
                                val bmp = withContext(Dispatchers.IO) {
                                    loadThumbnailBitmap(context, item, thumbTargetPx)
                                }
                                if (bmp != null && !isReleased) {
                                    thumbImg.setImageBitmap(bmp)
                                }
                            }
                            row.addView(tile)
                        }
                        if (rowItems.size < cols) {
                            repeat(cols - rowItems.size) {
                                row.addView(View(context).apply {
                                    layoutParams = LinearLayout.LayoutParams(0, tileHeightPx, 1f).apply {
                                        marginEnd = tileGap / 2
                                        marginStart = tileGap / 2
                                    }
                                })
                            }
                        }
                        gridContainer.addView(row)
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
