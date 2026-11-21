package com.example

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PointF
import android.graphics.RectF
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Pure geometry & media helpers for Circle-to-Search Cutout mode, Scoped Storage gallery export
 * (`Pictures/SmartDot` via MediaStore — zero storage permissions required), and Visual Search intents.
 */
object CircleCutoutAndMediaHelper {

    private const val TAG = "CircleCutoutMedia"

    /**
     * Distinguishes a drawn closed loop / circle gesture from a linear text-highlighter drag.
     *
     * Returns the loop's bounding [RectF] when:
     * - The drawn stroke spans at least `44dp x 44dp`, has path length >= `96dp`, AND
     * - Either start-to-end distance closes cleanly relative to box diagonal, OR the stroke
     *   sweeps >= 230 degrees around the polygon centroid (handles slightly open loops cleanly).
     *
     * Straight / shallow text highlighting strokes return null.
     */
    fun detectCircleLoopBounds(points: List<PointF>, density: Float): RectF? {
        if (points.size < 8) return null
        val safeDensity = density.coerceAtLeast(1f)

        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        var pathLength = 0f

        for (i in points.indices) {
            val p = points[i]
            if (p.x < minX) minX = p.x
            if (p.y < minY) minY = p.y
            if (p.x > maxX) maxX = p.x
            if (p.y > maxY) maxY = p.y
            if (i > 0) {
                val prev = points[i - 1]
                pathLength += hypot(p.x - prev.x, p.y - prev.y)
            }
        }

        val width = maxX - minX
        val height = maxY - minY
        val minBoxSizePx = 44f * safeDensity
        if (width < minBoxSizePx || height < minBoxSizePx) {
            return null
        }

        val minPathLengthPx = 96f * safeDensity
        val diag = hypot(width, height)
        if (pathLength < minPathLengthPx || pathLength < diag * 1.45f) {
            return null
        }

        val centerX = (minX + maxX) * 0.5f
        val centerY = (minY + maxY) * 0.5f

        // Compute total signed angular sweep around centroid
        var totalSweepRad = 0f
        var prevAngle = atan2(points[0].y - centerY, points[0].x - centerX)
        for (i in 1 until points.size) {
            val curAngle = atan2(points[i].y - centerY, points[i].x - centerX)
            var delta = curAngle - prevAngle
            while (delta > Math.PI) delta -= (2.0 * Math.PI).toFloat()
            while (delta < -Math.PI) delta += (2.0 * Math.PI).toFloat()
            totalSweepRad += delta
            prevAngle = curAngle
        }

        val absSweepDeg = Math.toDegrees(abs(totalSweepRad).toDouble()).toFloat()
        val endGap = hypot(points.last().x - points.first().x, points.last().y - points.first().y)
        val closureLimit = maxOf(48f * safeDensity, diag * 0.48f)

        return if (absSweepDeg >= 230f || (endGap <= closureLimit && absSweepDeg >= 160f)) {
            RectF(minX, minY, maxX, maxY)
        } else {
            null
        }
    }

    /**
     * Crops [bitmap] to [screenRect] mapped from `[viewWidth x viewHeight]` coordinates.
     * Caller owns the returned Bitmap (distinct instance) and should recycle it when done.
     */
    fun cropBitmapToScreenRect(
        bitmap: Bitmap,
        screenRect: RectF,
        viewWidth: Int,
        viewHeight: Int
    ): Bitmap? {
        if (bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) return null
        val refW = viewWidth.takeIf { it > 0 }?.toFloat() ?: bitmap.width.toFloat()
        val refH = viewHeight.takeIf { it > 0 }?.toFloat() ?: bitmap.height.toFloat()

        val scaleX = bitmap.width.toFloat() / refW
        val scaleY = bitmap.height.toFloat() / refH

        val left = (screenRect.left * scaleX).roundToInt().coerceIn(0, bitmap.width - 1)
        val top = (screenRect.top * scaleY).roundToInt().coerceIn(0, bitmap.height - 1)
        val right = (screenRect.right * scaleX).roundToInt().coerceIn(left + 1, bitmap.width)
        val bottom = (screenRect.bottom * scaleY).roundToInt().coerceIn(top + 1, bitmap.height)

        val cropW = (right - left).coerceAtLeast(1)
        val cropH = (bottom - top).coerceAtLeast(1)

        return runCatching {
            Bitmap.createBitmap(bitmap, left, top, cropW, cropH)
        }.onFailure { e ->
            Log.e(TAG, "Failed cropping bitmap region", e)
        }.getOrNull()
    }

    /**
     * Saves [bitmap] into `Pictures/SmartDot` via Scoped Storage MediaStore (zero runtime permissions).
     */
    fun saveBitmapToGallery(
        context: Context,
        bitmap: Bitmap,
        filePrefix: String = "SmartDot"
    ): Uri? {
        if (bitmap.isRecycled) return null
        val filename = "${filePrefix}_${System.currentTimeMillis()}.png"
        val resolver = context.contentResolver

        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, filename)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(
                    MediaStore.Images.Media.RELATIVE_PATH,
                    "${Environment.DIRECTORY_PICTURES}/SmartDot"
                )
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }

        val uri = runCatching {
            resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        }.getOrNull() ?: return null

        return try {
            resolver.openOutputStream(uri)?.use { out ->
                val ok = bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                if (!ok) throw IllegalStateException("PNG compression returned false")
            } ?: throw IllegalStateException("MediaStore OutputStream was null")

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val doneValues = ContentValues().apply {
                    put(MediaStore.Images.Media.IS_PENDING, 0)
                }
                resolver.update(uri, doneValues, null, null)
            }
            uri
        } catch (e: Exception) {
            Log.e(TAG, "Failed saving bitmap to MediaStore", e)
            runCatching { resolver.delete(uri, null, null) }
            null
        }
    }

    /**
     * Writes [croppedBitmap] into `cacheDir/cutouts/` and prepares a Google Lens / Image Search Intent
     * backed by FileProvider.
     */
    fun createVisualSearchIntent(context: Context, croppedBitmap: Bitmap): Intent? {
        if (croppedBitmap.isRecycled) return null
        val cutoutDir = File(context.cacheDir, "cutouts").apply { mkdirs() }
        val file = File(cutoutDir, "cutout_${System.currentTimeMillis()}.png")

        return try {
            FileOutputStream(file).use { out ->
                croppedBitmap.compress(Bitmap.CompressFormat.PNG, 95, out)
            }
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )

            val pm = context.packageManager
            val lensSend = Intent(Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, uri)
                setPackage("com.google.android.googlequicksearchbox")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            if (lensSend.resolveActivity(pm) != null) {
                return lensSend
            }

            val lensView = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "image/png")
                setPackage("com.google.android.googlequicksearchbox")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            if (lensView.resolveActivity(pm) != null) {
                return lensView
            }

            // Fallback: standard system image share sheet (allows picking Google Lens, Chrome Image Search, etc.)
            val fallbackShare = Intent(Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            Intent.createChooser(fallbackShare, "Visual Search Cutout").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed preparing Visual Search intent", e)
            null
        }
    }
}
