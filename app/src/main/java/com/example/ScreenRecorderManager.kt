package com.example

import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import java.io.File
import java.io.FileInputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Full working MediaProjection + MediaRecorder MP4 screen recorder.
 * Saves recorded MP4 clips into Movies/SmartDot via MediaStore.Video.Media.
 */
class ScreenRecorderManager(
    private val context: Context,
    private val onRecordingStateChanged: (Boolean) -> Unit = {},
    private val onRecordingSaved: (Uri?) -> Unit = {},
    private val onRecordingError: (String) -> Unit = {}
) {
    private var mediaProjection: MediaProjection? = null
    private var projectionCallback: MediaProjection.Callback? = null
    private var mediaRecorder: MediaRecorder? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var tempOutputFile: File? = null

    @Volatile
    var isRecording: Boolean = false
        private set

    fun hasActiveProjection(): Boolean = mediaProjection != null

    fun startRecording(resultCode: Int, data: Intent, recordMicAudio: Boolean): Boolean {
        if (isRecording) return true
        if (resultCode != Activity.RESULT_OK) {
            onRecordingError("Screen recording permission denied")
            return false
        }

        val projection = try {
            val projectionManager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val obtained = projectionManager.getMediaProjection(resultCode, data)
                ?: throw IllegalStateException("MediaProjection session unavailable")
            releaseProjection()
            mediaProjection = obtained
            val cb = object : MediaProjection.Callback() {
                override fun onStop() {
                    if (isRecording) {
                        stopRecording()
                    }
                    mediaProjection = null
                    projectionCallback = null
                }
            }
            projectionCallback = cb
            obtained.registerCallback(cb, Handler(Looper.getMainLooper()))
            obtained
        } catch (e: Exception) {
            Log.e(TAG, "Failed obtaining MediaProjection", e)
            onRecordingError(e.localizedMessage ?: "Failed starting screen recording")
            return false
        }

        return startWithProjection(projection, recordMicAudio, releaseProjectionOnFailure = true)
    }

    fun startRecordingWithExistingProjection(recordMicAudio: Boolean): Boolean {
        if (isRecording) return true
        val projection = mediaProjection ?: return false
        return startWithProjection(projection, recordMicAudio, releaseProjectionOnFailure = true)
    }

    private fun startWithProjection(
        projection: MediaProjection,
        recordMicAudio: Boolean,
        releaseProjectionOnFailure: Boolean
    ): Boolean {
        return try {
            val metrics = DisplayMetrics()
            val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay?.getRealMetrics(metrics)
            val rawW = if (metrics.widthPixels > 0) metrics.widthPixels else 1080
            val rawH = if (metrics.heightPixels > 0) metrics.heightPixels else 1920
            val densityDpi = if (metrics.densityDpi > 0) metrics.densityDpi else DisplayMetrics.DENSITY_HIGH

            val (recWidth, recHeight) = computeEncoderDimensions(rawW, rawH)

            val cacheFile = File(context.cacheDir, "smartdot_rec_${System.currentTimeMillis()}.mp4")
            tempOutputFile = cacheFile

            val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }

            if (recordMicAudio) {
                recorder.setAudioSource(MediaRecorder.AudioSource.MIC)
            }
            recorder.setVideoSource(MediaRecorder.VideoSource.SURFACE)
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            recorder.setOutputFile(cacheFile.absolutePath)
            recorder.setVideoSize(recWidth, recHeight)
            recorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            if (recordMicAudio) {
                recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                recorder.setAudioEncodingBitRate(128000)
                recorder.setAudioSamplingRate(44100)
            }
            recorder.setVideoEncodingBitRate(6_000_000)
            recorder.setVideoFrameRate(30)
            recorder.prepare()

            virtualDisplay = projection.createVirtualDisplay(
                "SmartDotScreenRecorder",
                recWidth,
                recHeight,
                densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                recorder.surface,
                null,
                null
            )

            recorder.start()
            mediaRecorder = recorder
            isRecording = true
            onRecordingStateChanged(true)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed starting screen recording", e)
            cleanupRecorderObjects(releaseProjection = releaseProjectionOnFailure)
            tempOutputFile?.delete()
            tempOutputFile = null
            isRecording = false
            onRecordingStateChanged(false)
            onRecordingError(e.localizedMessage ?: "Failed starting screen recording")
            false
        }
    }

    fun stopRecording(): Uri? {
        if (!isRecording && mediaRecorder == null) return null
        isRecording = false

        try {
            mediaRecorder?.stop()
        } catch (e: Exception) {
            Log.w(TAG, "MediaRecorder stop warning", e)
        }
        // Keep active MediaProjection alive for fast zero-prompt subsequent recordings
        cleanupRecorderObjects(releaseProjection = false)

        val savedUri = tempOutputFile?.let { file ->
            if (file.exists() && file.length() > 0L) {
                saveVideoToMoviesSmartDot(context, file)
            } else {
                null
            }
        }
        tempOutputFile?.delete()
        tempOutputFile = null

        onRecordingStateChanged(false)
        onRecordingSaved(savedUri)
        return savedUri
    }

    fun releaseProjection() {
        cleanupRecorderObjects(releaseProjection = true)
    }

    private fun cleanupRecorderObjects(releaseProjection: Boolean) {
        runCatching { virtualDisplay?.release() }
        virtualDisplay = null
        runCatching { mediaRecorder?.reset() }
        runCatching { mediaRecorder?.release() }
        mediaRecorder = null
        if (releaseProjection) {
            projectionCallback?.let { cb ->
                runCatching { mediaProjection?.unregisterCallback(cb) }
            }
            projectionCallback = null
            runCatching { mediaProjection?.stop() }
            mediaProjection = null
        }
    }

    companion object {
        private const val TAG = "ScreenRecorderManager"

        fun buildMoviesRelativePath(): String = "${Environment.DIRECTORY_MOVIES}/SmartDot"

        fun computeEncoderDimensions(screenWidth: Int, screenHeight: Int): Pair<Int, Int> {
            val maxDim = 1080f
            val scale = if (maxOf(screenWidth, screenHeight) > maxDim) {
                maxDim / minOf(screenWidth, screenHeight).toFloat()
            } else {
                1f
            }
            val scaledW = if (screenWidth > 1920 || screenHeight > 1920) {
                (screenWidth / 2f).toInt()
            } else {
                (screenWidth * minOf(scale, 1f)).toInt()
            }
            val scaledH = if (screenWidth > 1920 || screenHeight > 1920) {
                (screenHeight / 2f).toInt()
            } else {
                (screenHeight * minOf(scale, 1f)).toInt()
            }
            val evenW = (scaledW.coerceAtLeast(360) / 2) * 2
            val evenH = (scaledH.coerceAtLeast(360) / 2) * 2
            return Pair(evenW, evenH)
        }

        fun buildMediaStoreValues(displayName: String, nowMillis: Long = System.currentTimeMillis()): ContentValues {
            return ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.DATE_ADDED, nowMillis / 1000L)
                put(MediaStore.Video.Media.DATE_TAKEN, nowMillis)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Video.Media.RELATIVE_PATH, buildMoviesRelativePath())
                    put(MediaStore.Video.Media.IS_PENDING, 1)
                }
            }
        }

        fun saveVideoToMoviesSmartDot(context: Context, sourceFile: File): Uri? {
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val fileName = "SmartDot_$timestamp.mp4"
            val resolver = context.contentResolver
            val values = buildMediaStoreValues(fileName)

            return try {
                val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: return null
                resolver.openOutputStream(uri)?.use { out ->
                    FileInputStream(sourceFile).use { input ->
                        input.copyTo(out)
                    }
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val publishValues = ContentValues().apply {
                        put(MediaStore.Video.Media.IS_PENDING, 0)
                    }
                    resolver.update(uri, publishValues, null, null)
                }
                uri
            } catch (e: Exception) {
                Log.e(TAG, "Failed writing recording to MediaStore", e)
                null
            }
        }
    }
}
