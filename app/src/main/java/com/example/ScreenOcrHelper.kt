package com.example

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.graphics.RectF
import android.util.Log
import kotlin.math.abs
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

/**
 * Performs on-device OCR using Google ML Kit for both English (Latin)
 * and Hindi (Devanagari) scripts, returning deduplicated blocks in reading order.
 */
class ScreenOcrHelper(
    private val latinClientFactory: () -> TextRecognizer = {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    },
    private val devanagariClientFactory: () -> TextRecognizer = {
        TextRecognition.getClient(DevanagariTextRecognizerOptions.Builder().build())
    },
    private val recognizerRunner: suspend (TextRecognizer, Bitmap) -> Text = { recognizer, bmp ->
        val inputImage = InputImage.fromBitmap(bmp, 0)
        recognizer.process(inputImage).await()
    }
) {

    data class OcrTextBlock(
        val id: Int,
        val text: String,
        val boundingBox: Rect,
        val scriptLabel: String
    )

    private val latinRecognizer: TextRecognizer by lazy(latinClientFactory)
    private val devanagariRecognizer: TextRecognizer by lazy(devanagariClientFactory)

    suspend fun recognizeEnglishAndHindi(bitmap: Bitmap): List<OcrTextBlock> =
        withContext(Dispatchers.Default) {
            require(!bitmap.isRecycled) { "Captured bitmap was recycled before OCR." }

            val prepared = prepareBitmapForMlKit(bitmap)
            try {
                val (latinOutcome, devanagariOutcome) = coroutineScope {
                    val latinTask = async {
                        runCatching {
                            recognizerRunner(latinRecognizer, prepared.bitmap)
                        }.onFailure { err ->
                            Log.e(TAG, "Latin ML Kit OCR failed", err)
                        }
                    }
                    val devTask = async {
                        runCatching {
                            recognizerRunner(devanagariRecognizer, prepared.bitmap)
                        }.onFailure { err ->
                            Log.e(TAG, "Devanagari ML Kit OCR failed", err)
                        }
                    }
                    latinTask.await() to devTask.await()
                }

                val latinFailure = latinOutcome.exceptionOrNull()
                val devFailure = devanagariOutcome.exceptionOrNull()
                if (latinFailure != null && devFailure != null) {
                    throw IllegalStateException(
                        "Both English and Hindi OCR recognizers failed: ${latinFailure.localizedMessage ?: devFailure.localizedMessage}",
                        latinFailure
                    )
                }

                mergeBlocks(
                    latinResult = latinOutcome.getOrNull(),
                    devanagariResult = devanagariOutcome.getOrNull(),
                    scaleBackX = prepared.scaleBackX,
                    scaleBackY = prepared.scaleBackY,
                    maxWidth = bitmap.width,
                    maxHeight = bitmap.height
                )
            } finally {
                if (prepared.ownsTempBitmap && !prepared.bitmap.isRecycled) {
                    prepared.bitmap.recycle()
                }
            }
        }

    internal data class RawOcrBlock(
        val text: String,
        val boundingBox: Rect
    )

    internal fun mergeBlocks(
        latinResult: Text?,
        devanagariResult: Text?,
        scaleBackX: Float = 1f,
        scaleBackY: Float = 1f,
        maxWidth: Int = Int.MAX_VALUE,
        maxHeight: Int = Int.MAX_VALUE
    ): List<OcrTextBlock> {
        val latinBlocks = extractGranularRawBlocks(latinResult)
        val devBlocks = extractGranularRawBlocks(devanagariResult)
        return mergeRawBlocks(
            latinBlocks = latinBlocks,
            devanagariBlocks = devBlocks,
            scaleBackX = scaleBackX,
            scaleBackY = scaleBackY,
            maxWidth = maxWidth,
            maxHeight = maxHeight
        )
    }

    private fun extractGranularRawBlocks(result: Text?): List<RawOcrBlock> {
        if (result == null) return emptyList()
        val rawUnits = mutableListOf<RawOcrBlock>()
        for (block in result.textBlocks) {
            val lines = block.lines
            if (lines.isNotEmpty()) {
                for (line in lines) {
                    val lineRect = line.boundingBox ?: continue
                    val lineText = line.text.trim()
                    if (lineText.isNotEmpty()) {
                        rawUnits.add(RawOcrBlock(lineText, lineRect))
                    }
                }
            } else {
                val blockRect = block.boundingBox ?: continue
                val blockText = block.text.trim()
                if (blockText.isNotEmpty()) {
                    rawUnits.add(RawOcrBlock(blockText, blockRect))
                }
            }
        }
        return rawUnits
    }

    internal fun mergeRawBlocks(
        latinBlocks: List<RawOcrBlock>,
        devanagariBlocks: List<RawOcrBlock>,
        scaleBackX: Float = 1f,
        scaleBackY: Float = 1f,
        maxWidth: Int = Int.MAX_VALUE,
        maxHeight: Int = Int.MAX_VALUE
    ): List<OcrTextBlock> {
        val selected = mutableListOf<PendingBlock>()

        // 1. Prefer blocks from the Devanagari recognizer whenever they contain Devanagari script.
        for (block in devanagariBlocks) {
            val text = block.text.trim()
            if (text.isEmpty()) continue
            val rect = scaleRectBack(block.boundingBox, scaleBackX, scaleBackY, maxWidth, maxHeight)
            if (containsDevanagari(text)) {
                val label = if (containsLatin(text)) "HI+EN" else "HI"
                selected.add(PendingBlock(text = text, rect = rect, scriptLabel = label))
            }
        }

        // 2. Add Latin blocks that do not substantially overlap already-selected Devanagari blocks.
        for (block in latinBlocks) {
            val text = block.text.trim()
            if (text.isEmpty()) continue
            val rect = scaleRectBack(block.boundingBox, scaleBackX, scaleBackY, maxWidth, maxHeight)
            if (!overlapsAny(rect, selected)) {
                selected.add(PendingBlock(text = text, rect = rect, scriptLabel = "EN"))
            }
        }

        // 3. Fallback: add any remaining non-Devanagari blocks detected only by the Devanagari recognizer.
        for (block in devanagariBlocks) {
            val text = block.text.trim()
            if (text.isEmpty() || containsDevanagari(text)) continue
            val rect = scaleRectBack(block.boundingBox, scaleBackX, scaleBackY, maxWidth, maxHeight)
            if (!overlapsAny(rect, selected)) {
                selected.add(PendingBlock(text = text, rect = rect, scriptLabel = "EN"))
            }
        }

        // Sort top-to-bottom (with 24px row tolerance), then left-to-right for natural reading order.
        val sorted = selected.sortedWith { a, b ->
            val rowDiff = a.rect.top - b.rect.top
            if (kotlin.math.abs(rowDiff) > 24) {
                rowDiff
            } else {
                a.rect.left - b.rect.left
            }
        }

        return sorted.mapIndexed { index, item ->
            OcrTextBlock(
                id = index,
                text = item.text,
                boundingBox = item.rect,
                scriptLabel = item.scriptLabel
            )
        }
    }

    private fun prepareBitmapForMlKit(source: Bitmap): PreparedBitmap {
        val w = source.width
        val h = source.height

        // ML Kit requires at least 32x32 pixels and ARGB_8888 / software-readable bitmap.
        if (w < MIN_MLKIT_DIM_PX || h < MIN_MLKIT_DIM_PX) {
            val targetW = w.coerceAtLeast(MIN_MLKIT_DIM_PX)
            val targetH = h.coerceAtLeast(MIN_MLKIT_DIM_PX)
            val padded = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(padded)
            canvas.drawColor(Color.BLACK)
            canvas.drawBitmap(source, 0f, 0f, null)
            return PreparedBitmap(padded, ownsTempBitmap = true, scaleBackX = 1f, scaleBackY = 1f)
        }

        if (source.config != Bitmap.Config.ARGB_8888) {
            val argb = source.copy(Bitmap.Config.ARGB_8888, false)
            if (argb != null) {
                return PreparedBitmap(argb, ownsTempBitmap = true, scaleBackX = 1f, scaleBackY = 1f)
            }
        }

        return PreparedBitmap(source, ownsTempBitmap = false, scaleBackX = 1f, scaleBackY = 1f)
    }

    private fun scaleRectBack(
        rect: Rect,
        scaleX: Float,
        scaleY: Float,
        maxWidth: Int,
        maxHeight: Int
    ): Rect {
        return Rect(
            (rect.left * scaleX).toInt().coerceIn(0, maxWidth),
            (rect.top * scaleY).toInt().coerceIn(0, maxHeight),
            (rect.right * scaleX).toInt().coerceIn(0, maxWidth),
            (rect.bottom * scaleY).toInt().coerceIn(0, maxHeight)
        )
    }

    private fun overlapsAny(candidate: Rect, existing: List<PendingBlock>): Boolean {
        val candidateArea = (candidate.width().coerceAtLeast(1) * candidate.height().coerceAtLeast(1)).toFloat()
        for (other in existing) {
            val inter = Rect()
            if (inter.setIntersect(candidate, other.rect)) {
                val interArea = (inter.width() * inter.height()).toFloat()
                val otherArea = (other.rect.width().coerceAtLeast(1) * other.rect.height().coerceAtLeast(1)).toFloat()
                val minArea = minOf(candidateArea, otherArea)
                if (interArea / minArea >= 0.40f) {
                    return true
                }
            }
        }
        return false
    }

    private fun containsDevanagari(text: String): Boolean {
        return text.any { ch -> ch in '\u0900'..'\u097F' }
    }

    private fun containsLatin(text: String): Boolean {
        return text.any { ch -> ch in 'A'..'Z' || ch in 'a'..'z' }
    }

    fun close() {
        runCatching { latinRecognizer.close() }
        runCatching { devanagariRecognizer.close() }
    }

    private data class PreparedBitmap(
        val bitmap: Bitmap,
        val ownsTempBitmap: Boolean,
        val scaleBackX: Float,
        val scaleBackY: Float
    )

    private data class PendingBlock(
        val text: String,
        val rect: Rect,
        val scriptLabel: String
    )

    companion object {
        private const val TAG = "ScreenOcrHelper"
        private const val MIN_MLKIT_DIM_PX = 32

        /**
         * Joins selected OCR blocks/lines in visual reading order. Items sharing the same
         * horizontal baseline are joined with a space; items on distinct lines with a newline.
         */
        fun formatSelectedText(
            blocks: List<OcrTextBlock>,
            selectedIds: Set<Int>
        ): String {
            if (selectedIds.isEmpty()) return ""
            val ordered = blocks.filter { selectedIds.contains(it.id) }
            if (ordered.isEmpty()) return ""

            val sb = StringBuilder()
            var previous: OcrTextBlock? = null
            for (current in ordered) {
                val clean = current.text.trim()
                if (clean.isEmpty()) continue
                if (previous != null) {
                    val prevBox = previous.boundingBox
                    val currBox = current.boundingBox
                    val sameRowTolerance = minOf(
                        prevBox.height().coerceAtLeast(1),
                        currBox.height().coerceAtLeast(1)
                    ) * 0.6f
                    val sameRow = abs(currBox.centerY() - prevBox.centerY()) <= sameRowTolerance &&
                        currBox.left >= prevBox.right - 12
                    sb.append(if (sameRow) " " else "\n")
                }
                sb.append(clean)
                previous = current
            }
            return sb.toString().trim()
        }

        /**
         * Maps a capture-bitmap Rect into the local coordinate space of a WindowManager overlay view,
         * accounting for any display scale differences and any system inset / screen origin offset
         * (`overlayScreenOffsetX`, `overlayScreenOffsetY`) reported by `View.getLocationOnScreen()`.
         */
        fun mapCaptureRectToOverlayLocal(
            captureRect: Rect,
            captureWidth: Int,
            captureHeight: Int,
            screenWidth: Int,
            screenHeight: Int,
            overlayScreenOffsetX: Int = 0,
            overlayScreenOffsetY: Int = 0
        ): RectF {
            val safeCapW = captureWidth.coerceAtLeast(1).toFloat()
            val safeCapH = captureHeight.coerceAtLeast(1).toFloat()
            val safeScreenW = screenWidth.coerceAtLeast(1).toFloat()
            val safeScreenH = screenHeight.coerceAtLeast(1).toFloat()

            val scaleX = safeScreenW / safeCapW
            val scaleY = safeScreenH / safeCapH

            return RectF(
                captureRect.left * scaleX - overlayScreenOffsetX,
                captureRect.top * scaleY - overlayScreenOffsetY,
                captureRect.right * scaleX - overlayScreenOffsetX,
                captureRect.bottom * scaleY - overlayScreenOffsetY
            )
        }
    }
}
