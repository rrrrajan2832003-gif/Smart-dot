package com.example

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import android.view.Surface
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.IntentCompat
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Foreground Service (foregroundServiceType="mediaProjection") that manages an active
 * MediaProjection + VirtualDisplay session so repeated Screen Text Copy captures reuse
 * the same session until the system or user stops it (Android 14/15/16 compliant).
 */
class ScreenCaptureService : Service() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val captureInFlight = AtomicBoolean(false)
    private val isTerminating = AtomicBoolean(false)
    private val blankFramesSkipped = AtomicInteger(0)

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var virtualDisplayWidth = 0
    private var virtualDisplayHeight = 0
    private var virtualDisplayDensity = 0
    private var imageReader: ImageReader? = null

    private var captureWidth = 0
    private var captureHeight = 0
    private var captureDensityDpi = DisplayMetrics.DENSITY_DEFAULT
    private var captureRotation = Surface.ROTATION_0

    private var pendingBlackBitmap: Bitmap? = null

    private val timeoutRunnable = Runnable {
        if (captureInFlight.compareAndSet(true, false)) {
            val blackBmp = pendingBlackBitmap
            pendingBlackBitmap = null
            if (blackBmp != null && !blackBmp.isRecycled) {
                deliverCapturedBitmap(blackBmp, isAllBlack = true)
            } else {
                Log.w(TAG, "Timed out waiting for MediaProjection frame.")
                detachSurfaceAndReader()
                notifyFailure("Screen capture timed out. Please try again.")
            }
        }
    }

    private val blackFallbackRunnable = Runnable {
        if (captureInFlight.compareAndSet(true, false)) {
            val blackBmp = pendingBlackBitmap
            pendingBlackBitmap = null
            if (blackBmp != null && !blackBmp.isRecycled) {
                deliverCapturedBitmap(blackBmp, isAllBlack = true)
            } else {
                detachSurfaceAndReader()
                notifyFailure("No readable frame received from screen.")
            }
        }
    }

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            mainHandler.post {
                val hadPendingCapture = captureInFlight.compareAndSet(true, false)
                terminateSessionAndStop()
                if (hadPendingCapture) {
                    notifySessionExpired()
                }
            }
        }

        override fun onCapturedContentResize(width: Int, height: Int) {
            if (width <= 0 || height <= 0 || isTerminating.get()) return
            if (width == captureWidth && height == captureHeight) return

            Log.d(TAG, "Captured content resized to ${width}x${height}")
            captureWidth = width
            captureHeight = height

            val vDisplay = virtualDisplay ?: return
            if (!captureInFlight.get()) {
                try {
                    vDisplay.resize(width, height, captureDensityDpi)
                    virtualDisplayWidth = width
                    virtualDisplayHeight = height
                } catch (e: Exception) {
                    Log.w(TAG, "Failed resizing idle VirtualDisplay", e)
                }
                return
            }

            try {
                val oldReader = imageReader
                val newReader = createConfiguredImageReader(width, height)
                imageReader = newReader
                vDisplay.resize(width, height, captureDensityDpi)
                vDisplay.surface = newReader.surface
                virtualDisplayWidth = width
                virtualDisplayHeight = height
                oldReader?.setOnImageAvailableListener(null, null)
                oldReader?.close()
            } catch (e: Exception) {
                Log.w(TAG, "Failed resizing VirtualDisplay for captured content", e)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createNotificationChannel()
        val notification = buildNotification()
        val fgsType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        } else {
            0
        }
        try {
            ServiceCompat.startForeground(this, CAPTURE_NOTIFICATION_ID, notification, fgsType)
        } catch (e: Exception) {
            Log.e(TAG, "Failed starting ScreenCaptureService in foreground", e)
            captureInFlight.set(false)
            terminateSessionAndStop()
            notifyFailure("Unable to start foreground screen capture service.")
            return START_NOT_STICKY
        }

        when (intent?.action) {
            ACTION_STOP_SESSION -> {
                captureInFlight.set(false)
                terminateSessionAndStop()
                return START_NOT_STICKY
            }
            ACTION_CAPTURE_NEXT -> {
                handleCaptureNextRequest()
                return START_STICKY
            }
        }

        if (intent == null) {
            notifyFailure("Missing screen capture request data.")
            terminateSessionAndStop()
            return START_NOT_STICKY
        }

        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
        val resultData = IntentCompat.getParcelableExtra(intent, EXTRA_RESULT_DATA, Intent::class.java)
        if (resultCode != Activity.RESULT_OK || resultData == null) {
            notifyFailure(getString(R.string.screen_copy_permission_denied))
            terminateSessionAndStop()
            return START_NOT_STICKY
        }

        // Clean up any older projection before creating a new one from fresh user consent.
        releaseProjectionResourcesOnly()
        isTerminating.set(false)

        val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager
        if (projectionManager == null) {
            notifyFailure("MediaProjection is unavailable on this device.")
            terminateSessionAndStop()
            return START_NOT_STICKY
        }

        val projection = try {
            projectionManager.getMediaProjection(resultCode, resultData)
        } catch (se: SecurityException) {
            Log.e(TAG, "SecurityException getting MediaProjection", se)
            notifyFailure("Screen capture permission could not be initialized.")
            terminateSessionAndStop()
            return START_NOT_STICKY
        } catch (e: Exception) {
            Log.e(TAG, "Failed getting MediaProjection", e)
            notifyFailure("Failed to start screen capture: ${e.localizedMessage}")
            terminateSessionAndStop()
            return START_NOT_STICKY
        }

        if (projection == null) {
            notifyFailure("Could not acquire screen capture session.")
            terminateSessionAndStop()
            return START_NOT_STICKY
        }

        mediaProjection = projection
        projection.registerCallback(projectionCallback, mainHandler)

        initDisplayMetrics()
        beginCapturePass()

        // Short delay allows the translucent consent activity & system dialog to dismiss completely
        // before capturing the underlying app frame.
        mainHandler.postDelayed({
            if (!isTerminating.get() && captureInFlight.get()) {
                createInitialVirtualDisplayAndCapture()
            }
        }, DIALOG_DISMISS_DELAY_MS)

        return START_STICKY
    }

    private fun handleCaptureNextRequest() {
        val projection = mediaProjection
        val vDisplay = virtualDisplay
        if (projection == null || vDisplay == null || !isSessionActive || isTerminating.get()) {
            Log.i(TAG, "Active MediaProjection session unavailable; requesting new consent.")
            captureInFlight.set(false)
            terminateSessionAndStop()
            notifySessionExpired()
            return
        }

        initDisplayMetrics()
        beginCapturePass()

        try {
            val reader = createConfiguredImageReader(captureWidth, captureHeight)
            imageReader = reader
            if (captureWidth != virtualDisplayWidth ||
                captureHeight != virtualDisplayHeight ||
                captureDensityDpi != virtualDisplayDensity
            ) {
                vDisplay.resize(captureWidth, captureHeight, captureDensityDpi)
                virtualDisplayWidth = captureWidth
                virtualDisplayHeight = captureHeight
                virtualDisplayDensity = captureDensityDpi
            }
            vDisplay.surface = reader.surface
        } catch (se: SecurityException) {
            Log.w(TAG, "MediaProjection session invalidated during reuse", se)
            captureInFlight.set(false)
            terminateSessionAndStop()
            notifySessionExpired()
        } catch (ise: IllegalStateException) {
            Log.w(TAG, "VirtualDisplay inactive during reuse", ise)
            captureInFlight.set(false)
            terminateSessionAndStop()
            notifySessionExpired()
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected failure reusing MediaProjection session", e)
            captureInFlight.set(false)
            terminateSessionAndStop()
            notifySessionExpired()
        }
    }

    private fun beginCapturePass() {
        mainHandler.removeCallbacks(timeoutRunnable)
        mainHandler.removeCallbacks(blackFallbackRunnable)
        recyclePendingBlackBitmap()
        blankFramesSkipped.set(0)
        captureInFlight.set(true)
        mainHandler.postDelayed(timeoutRunnable, CAPTURE_TIMEOUT_MS)
    }

    private fun initDisplayMetrics() {
        val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        captureDensityDpi = resources.configuration.densityDpi.takeIf { it > 0 }
            ?: resources.displayMetrics.densityDpi

        val displayManager = getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
        val defaultDisplay = displayManager?.getDisplay(Display.DEFAULT_DISPLAY)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.maximumWindowMetrics.bounds
            captureWidth = bounds.width().coerceAtLeast(1)
            captureHeight = bounds.height().coerceAtLeast(1)
            captureRotation = defaultDisplay?.rotation ?: Surface.ROTATION_0
        } else {
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            val legacyDisplay = windowManager.defaultDisplay
            @Suppress("DEPRECATION")
            legacyDisplay.getRealMetrics(metrics)
            captureWidth = metrics.widthPixels.coerceAtLeast(1)
            captureHeight = metrics.heightPixels.coerceAtLeast(1)
            captureDensityDpi = metrics.densityDpi
            @Suppress("DEPRECATION")
            captureRotation = legacyDisplay.rotation
        }
    }

    private fun createInitialVirtualDisplayAndCapture() {
        val projection = mediaProjection ?: return
        try {
            val reader = createConfiguredImageReader(captureWidth, captureHeight)
            imageReader = reader
            virtualDisplay = projection.createVirtualDisplay(
                "SmartDotScreenCopy",
                captureWidth,
                captureHeight,
                captureDensityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface,
                null,
                mainHandler
            )
            virtualDisplayWidth = captureWidth
            virtualDisplayHeight = captureHeight
            virtualDisplayDensity = captureDensityDpi
            isSessionActive = true
        } catch (e: Exception) {
            Log.e(TAG, "Failed creating VirtualDisplay", e)
            captureInFlight.set(false)
            notifyFailure("Unable to capture screen: ${e.localizedMessage}")
            terminateSessionAndStop()
        }
    }

    private fun createConfiguredImageReader(width: Int, height: Int): ImageReader {
        val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        reader.setOnImageAvailableListener({ r ->
            if (!captureInFlight.get() || isTerminating.get()) return@setOnImageAvailableListener

            val image = try {
                r.acquireLatestImage()
            } catch (e: Exception) {
                Log.w(TAG, "Error acquiring image", e)
                null
            } ?: return@setOnImageAvailableListener

            val finalBitmap: Bitmap? = try {
                val plane = image.planes[0]
                copyRgbaPlaneToBitmap(
                    buffer = plane.buffer,
                    width = image.width,
                    height = image.height,
                    pixelStride = plane.pixelStride,
                    rowStride = plane.rowStride
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed processing captured frame", e)
                null
            } finally {
                image.close()
            }

            if (finalBitmap == null) {
                if (captureInFlight.compareAndSet(true, false)) {
                    detachSurfaceAndReader()
                    notifyFailure("Failed to process screen frame.")
                }
                return@setOnImageAvailableListener
            }

            val isAllBlack = isFramePureBlack(finalBitmap)
            if (isAllBlack && blankFramesSkipped.getAndIncrement() < MAX_WARMUP_BLACK_FRAMES) {
                // Keep one blank frame as quick fallback in case the target window is static FLAG_SECURE,
                // while allowing a subsequent rendered frame to replace it within BLACK_FALLBACK_DELAY_MS.
                recyclePendingBlackBitmap()
                pendingBlackBitmap = finalBitmap
                mainHandler.removeCallbacks(blackFallbackRunnable)
                mainHandler.postDelayed(blackFallbackRunnable, BLACK_FALLBACK_DELAY_MS)
                return@setOnImageAvailableListener
            }

            if (captureInFlight.compareAndSet(true, false)) {
                recyclePendingBlackBitmap()
                deliverCapturedBitmap(finalBitmap, isAllBlack)
            } else {
                if (!finalBitmap.isRecycled) {
                    finalBitmap.recycle()
                }
            }
        }, mainHandler)
        return reader
    }

    private fun deliverCapturedBitmap(bitmap: Bitmap, isAllBlack: Boolean) {
        mainHandler.removeCallbacks(timeoutRunnable)
        mainHandler.removeCallbacks(blackFallbackRunnable)
        detachSurfaceAndReader()

        ScreenCaptureHolder.setFrame(
            bitmap = bitmap,
            isAllBlackOrProtected = isAllBlack,
            captureWidth = bitmap.width.coerceAtLeast(captureWidth),
            captureHeight = bitmap.height.coerceAtLeast(captureHeight),
            displayRotation = captureRotation,
            densityDpi = captureDensityDpi
        )
        val readyIntent = Intent(ACTION_CAPTURE_READY).apply {
            setPackage(packageName)
        }
        sendBroadcast(readyIntent)
    }

    private fun detachSurfaceAndReader() {
        try {
            virtualDisplay?.surface = null
        } catch (e: Exception) {
            Log.w(TAG, "Error detaching VirtualDisplay surface", e)
        }
        try {
            imageReader?.setOnImageAvailableListener(null, null)
            imageReader?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing ImageReader", e)
        }
        imageReader = null
    }

    private fun recyclePendingBlackBitmap() {
        pendingBlackBitmap?.let { bmp ->
            if (!bmp.isRecycled) {
                bmp.recycle()
            }
        }
        pendingBlackBitmap = null
    }

    /**
     * Checks a uniform sample grid to detect all-black frames returned when FLAG_SECURE
     * content or a blank protected surface is captured. Never attempts to bypass FLAG_SECURE.
     */
    private fun isFramePureBlack(bitmap: Bitmap): Boolean {
        val w = bitmap.width
        val h = bitmap.height
        if (w <= 0 || h <= 0) return true

        val steps = 14
        val stepX = (w / steps).coerceAtLeast(1)
        val stepY = (h / steps).coerceAtLeast(1)

        var y = stepY / 2
        while (y < h) {
            var x = stepX / 2
            while (x < w) {
                val pixel = bitmap.getPixel(x, y)
                val r = (pixel shr 16) and 0xFF
                val g = (pixel shr 8) and 0xFF
                val b = pixel and 0xFF
                if (r > 2 || g > 2 || b > 2) {
                    return false
                }
                x += stepX
            }
            y += stepY
        }
        return true
    }

    private fun notifyFailure(message: String) {
        val failIntent = Intent(ACTION_CAPTURE_FAILED).apply {
            setPackage(packageName)
            putExtra(EXTRA_ERROR_MESSAGE, message)
        }
        sendBroadcast(failIntent)
    }

    private fun notifySessionExpired() {
        val expiredIntent = Intent(ACTION_SESSION_EXPIRED).apply {
            setPackage(packageName)
        }
        sendBroadcast(expiredIntent)
    }

    private fun releaseProjectionResourcesOnly() {
        isSessionActive = false
        mainHandler.removeCallbacksAndMessages(null)
        recyclePendingBlackBitmap()

        try {
            imageReader?.setOnImageAvailableListener(null, null)
            imageReader?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing ImageReader", e)
        }
        imageReader = null

        try {
            virtualDisplay?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing VirtualDisplay", e)
        }
        virtualDisplay = null
        virtualDisplayWidth = 0
        virtualDisplayHeight = 0
        virtualDisplayDensity = 0

        try {
            mediaProjection?.unregisterCallback(projectionCallback)
            mediaProjection?.stop()
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping MediaProjection", e)
        }
        mediaProjection = null
    }

    private fun terminateSessionAndStop() {
        if (!isTerminating.compareAndSet(false, true)) return
        captureInFlight.set(false)
        releaseProjectionResourcesOnly()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        captureInFlight.set(false)
        releaseProjectionResourcesOnly()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Screen Text Capture",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Active while screen sharing session is available for Screen Text Copy."
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.screen_capture_notification_title))
            .setContentText(getString(R.string.screen_capture_notification_text))
            .setSmallIcon(R.drawable.ic_screen_text_copy)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val TAG = "ScreenCaptureService"
        private const val CHANNEL_ID = "screen_capture_channel"
        private const val CAPTURE_NOTIFICATION_ID = 1002
        private const val DIALOG_DISMISS_DELAY_MS = 320L
        private const val CAPTURE_TIMEOUT_MS = 5000L
        private const val BLACK_FALLBACK_DELAY_MS = 450L
        private const val MAX_WARMUP_BLACK_FRAMES = 2

        @Volatile
        var isSessionActive: Boolean = false
            internal set

        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"
        const val EXTRA_ERROR_MESSAGE = "extra_error_message"

        const val ACTION_CAPTURE_READY = "com.example.ACTION_SCREEN_CAPTURE_READY"
        const val ACTION_CAPTURE_FAILED = "com.example.ACTION_SCREEN_CAPTURE_FAILED"
        const val ACTION_SESSION_EXPIRED = "com.example.ACTION_SCREEN_CAPTURE_SESSION_EXPIRED"
        const val ACTION_CAPTURE_NEXT = "com.example.ACTION_SCREEN_CAPTURE_NEXT"
        const val ACTION_STOP_SESSION = "com.example.ACTION_SCREEN_CAPTURE_STOP_SESSION"

        /**
         * Copies an Image.Plane RGBA_8888 ByteBuffer into a tightly-packed ARGB_8888 Bitmap.
         * Handles rowStride > width * pixelStride safely without reading past the final row.
         */
        internal fun copyRgbaPlaneToBitmap(
            buffer: ByteBuffer,
            width: Int,
            height: Int,
            pixelStride: Int,
            rowStride: Int
        ): Bitmap {
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            buffer.rewind()

            if (pixelStride <= 0 || width <= 0 || height <= 0) {
                return bitmap
            }

            val rowBytesCount = width * pixelStride
            val rowPadding = rowStride - rowBytesCount

            if (pixelStride == 4 && rowPadding == 0 && buffer.remaining() >= width * height * 4) {
                bitmap.copyPixelsFromBuffer(buffer)
                return bitmap
            }

            if (pixelStride == 4) {
                val tightBuffer = ByteBuffer.allocateDirect(width * height * 4)
                val rowScratch = ByteArray(width * 4)
                val limit = buffer.limit()
                for (y in 0 until height) {
                    val rowStart = y * rowStride
                    if (rowStart >= limit) {
                        tightBuffer.put(ByteArray(rowScratch.size))
                        continue
                    }
                    buffer.position(rowStart)
                    val available = (limit - rowStart).coerceAtMost(rowScratch.size)
                    buffer.get(rowScratch, 0, available)
                    tightBuffer.put(rowScratch, 0, available)
                    if (available < rowScratch.size) {
                        for (pad in available until rowScratch.size) {
                            tightBuffer.put(0)
                        }
                    }
                }
                tightBuffer.rewind()
                bitmap.copyPixelsFromBuffer(tightBuffer)
                return bitmap
            }

            val pixels = IntArray(width * height)
            val limit = buffer.limit()
            for (y in 0 until height) {
                val rowStart = y * rowStride
                for (x in 0 until width) {
                    val idx = rowStart + x * pixelStride
                    if (idx + 3 < limit) {
                        val r = buffer.get(idx).toInt() and 0xFF
                        val g = buffer.get(idx + 1).toInt() and 0xFF
                        val b = buffer.get(idx + 2).toInt() and 0xFF
                        val a = buffer.get(idx + 3).toInt() and 0xFF
                        pixels[y * width + x] = (a shl 24) or (r shl 16) or (g shl 8) or b
                    }
                }
            }
            bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
            return bitmap
        }
    }
}
