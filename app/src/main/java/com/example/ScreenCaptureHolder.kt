package com.example

import android.graphics.Bitmap
import android.util.DisplayMetrics
import android.view.Surface

/**
 * Holds the most recent in-memory captured frame and its display geometry metadata
 * so bitmaps never need to be written to storage or passed through Binder intents,
 * and OCR bounding boxes map accurately to live overlay coordinates.
 */
object ScreenCaptureHolder {

    data class CapturedFrame(
        val bitmap: Bitmap,
        val isAllBlackOrProtected: Boolean,
        val captureWidth: Int = bitmap.width,
        val captureHeight: Int = bitmap.height,
        val displayRotation: Int = Surface.ROTATION_0,
        val densityDpi: Int = DisplayMetrics.DENSITY_DEFAULT
    )

    private val lock = Any()
    private var pendingFrame: CapturedFrame? = null

    fun setFrame(
        bitmap: Bitmap,
        isAllBlackOrProtected: Boolean,
        captureWidth: Int = bitmap.width,
        captureHeight: Int = bitmap.height,
        displayRotation: Int = Surface.ROTATION_0,
        densityDpi: Int = DisplayMetrics.DENSITY_DEFAULT
    ) {
        synchronized(lock) {
            pendingFrame?.bitmap?.let { old ->
                if (old !== bitmap && !old.isRecycled) {
                    old.recycle()
                }
            }
            pendingFrame = CapturedFrame(
                bitmap = bitmap,
                isAllBlackOrProtected = isAllBlackOrProtected,
                captureWidth = captureWidth,
                captureHeight = captureHeight,
                displayRotation = displayRotation,
                densityDpi = densityDpi
            )
        }
    }

    fun consumeFrame(): CapturedFrame? {
        synchronized(lock) {
            val frame = pendingFrame
            pendingFrame = null
            return frame
        }
    }

    fun clear() {
        synchronized(lock) {
            pendingFrame?.bitmap?.let { bmp ->
                if (!bmp.isRecycled) {
                    bmp.recycle()
                }
            }
            pendingFrame = null
        }
    }
}
