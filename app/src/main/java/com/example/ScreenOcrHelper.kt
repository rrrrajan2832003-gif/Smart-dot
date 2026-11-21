package com.example

import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 100% free on-device ML Kit OCR (Latin + Devanagari) for in-memory screen Bitmaps.
 * Extracts individual word elements (`Text.Element`) and lines (`Text.Line`) with exact
 * screen-pixel Rect coordinates so text inside photos, images, videos, and apps is aligned 1:1.
 */
class ScreenOcrHelper(
    private val latinRecognizerFactory: () -> TextRecognizer = {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    },
    private val devanagariRecognizerFactory: () -> TextRecognizer = {
        TextRecognition.getClient(DevanagariTextRecognizerOptions.Builder().build())
    },
    private val customOcrRunner: (suspend (Bitmap) -> Pair<List<OcrRawItem>, List<OcrRawItem>>)? = null
) {

    data class OcrRawItem(
        val text: String,
        val rect: Rect,
        val isLine: Boolean
    )

    data class OcrScreenResult(
        val wordNodes: List<SmartDotAccessibilityService.ScreenTextNode>,
        val lineNodes: List<SmartDotAccessibilityService.ScreenTextNode>
    )

    private val latinRecognizer: TextRecognizer by lazy(latinRecognizerFactory)
    private val devanagariRecognizer: TextRecognizer by lazy(devanagariRecognizerFactory)

    suspend fun recognizeInMemoryBitmap(
        bitmap: Bitmap,
        targetScreenWidth: Int,
        targetScreenHeight: Int
    ): OcrScreenResult = withContext(Dispatchers.Default) {
        val bmpW = bitmap.width.coerceAtLeast(1)
        val bmpH = bitmap.height.coerceAtLeast(1)
        val scaleX = if (targetScreenWidth > 0 && targetScreenWidth != bmpW) {
            targetScreenWidth.toFloat() / bmpW.toFloat()
        } else {
            1f
        }
        val scaleY = if (targetScreenHeight > 0 && targetScreenHeight != bmpH) {
            targetScreenHeight.toFloat() / bmpH.toFloat()
        } else {
            1f
        }

        val (latinItems, devanagariItems) = if (customOcrRunner != null) {
            customOcrRunner.invoke(bitmap)
        } else {
            val image = InputImage.fromBitmap(bitmap, 0)
            val latinDeferred = async {
                runCatching { latinRecognizer.process(image).await() }
                    .onFailure { Log.w(TAG, "Latin OCR pass failed", it) }
                    .getOrNull()
                    ?.let(::extractRawItems)
                    ?: emptyList()
            }
            val devanagariDeferred = async {
                runCatching { devanagariRecognizer.process(image).await() }
                    .onFailure { Log.w(TAG, "Devanagari OCR pass failed", it) }
                    .getOrNull()
                    ?.let(::extractRawItems)
                    ?: emptyList()
            }
            latinDeferred.await() to devanagariDeferred.await()
        }

        mergeScaleAndBuildNodes(
            latinItems = latinItems,
            devanagariItems = devanagariItems,
            scaleX = scaleX,
            scaleY = scaleY,
            maxWidth = if (targetScreenWidth > 0) targetScreenWidth else bmpW,
            maxHeight = if (targetScreenHeight > 0) targetScreenHeight else bmpH
        )
    }

    fun close() {
        runCatching { latinRecognizer.close() }
        runCatching { devanagariRecognizer.close() }
    }

    companion object {
        private const val TAG = "ScreenOcrHelper"
        private const val ROW_SORT_TOLERANCE_PX = 20

        fun extractRawItems(visionText: Text): List<OcrRawItem> {
            val items = ArrayList<OcrRawItem>()
            for (block in visionText.textBlocks) {
                for (line in block.lines) {
                    val lineText = line.text.trim()
                    val lineBox = line.boundingBox
                    if (lineText.isNotEmpty() && lineBox != null && lineBox.width() > 0 && lineBox.height() > 0) {
                        items.add(OcrRawItem(lineText, Rect(lineBox), isLine = true))
                    }
                    if (line.elements.isNotEmpty()) {
                        for (element in line.elements) {
                            val elemText = element.text.trim()
                            val elemBox = element.boundingBox ?: lineBox
                            if (elemText.isNotEmpty() && elemBox != null && elemBox.width() > 0 && elemBox.height() > 0) {
                                items.add(OcrRawItem(elemText, Rect(elemBox), isLine = false))
                            }
                        }
                    } else if (lineText.isNotEmpty() && lineBox != null && lineBox.width() > 0 && lineBox.height() > 0) {
                        items.add(OcrRawItem(lineText, Rect(lineBox), isLine = false))
                    }
                }
            }
            return items
        }

        fun mergeScaleAndBuildNodes(
            latinItems: List<OcrRawItem>,
            devanagariItems: List<OcrRawItem>,
            scaleX: Float = 1f,
            scaleY: Float = 1f,
            maxWidth: Int = Int.MAX_VALUE,
            maxHeight: Int = Int.MAX_VALUE
        ): OcrScreenResult {
            val mergedWords = mergeItemsByScript(
                latinList = latinItems.filter { !it.isLine },
                devanagariList = devanagariItems.filter { !it.isLine }
            )
            val mergedLines = mergeItemsByScript(
                latinList = latinItems.filter { it.isLine },
                devanagariList = devanagariItems.filter { it.isLine }
            )

            val scaledWords = scaleAndSortNodes(mergedWords, scaleX, scaleY, maxWidth, maxHeight)
            val scaledLines = scaleAndSortNodes(mergedLines, scaleX, scaleY, maxWidth, maxHeight)
            return OcrScreenResult(wordNodes = scaledWords, lineNodes = scaledLines)
        }

        private fun mergeItemsByScript(
            latinList: List<OcrRawItem>,
            devanagariList: List<OcrRawItem>
        ): List<OcrRawItem> {
            val devanagariSpecific = devanagariList.filter { item ->
                item.text.any { it in '\u0900'..'\u097F' }
            }
            val merged = ArrayList<OcrRawItem>(latinList.size + devanagariSpecific.size)

            // Keep Latin items unless a Devanagari-script detection occupies the same bounding box
            for (latin in latinList) {
                val overshadowedByHindi = devanagariSpecific.any { dev ->
                    overlapRatioOfSmaller(latin.rect, dev.rect) >= 0.35f
                }
                if (!overshadowedByHindi) {
                    merged.add(latin)
                }
            }

            // Add all Devanagari-script items, plus non-overlapping items only present in Devanagari pass
            merged.addAll(devanagariSpecific)
            for (devOther in devanagariList) {
                if (devOther.text.any { it in '\u0900'..'\u097F' }) continue
                val alreadyCovered = merged.any { existing ->
                    overlapRatioOfSmaller(existing.rect, devOther.rect) >= 0.35f
                }
                if (!alreadyCovered) {
                    merged.add(devOther)
                }
            }
            return merged
        }

        private fun overlapRatioOfSmaller(a: Rect, b: Rect): Float {
            val interLeft = maxOf(a.left, b.left)
            val interTop = maxOf(a.top, b.top)
            val interRight = minOf(a.right, b.right)
            val interBottom = minOf(a.bottom, b.bottom)
            if (interRight <= interLeft || interBottom <= interTop) return 0f

            val interArea = (interRight - interLeft).toLong() * (interBottom - interTop).toLong()
            val areaA = (a.width().coerceAtLeast(1)).toLong() * (a.height().coerceAtLeast(1)).toLong()
            val areaB = (b.width().coerceAtLeast(1)).toLong() * (b.height().coerceAtLeast(1)).toLong()
            val smallerArea = minOf(areaA, areaB).coerceAtLeast(1L)
            return interArea.toFloat() / smallerArea.toFloat()
        }

        private fun scaleAndSortNodes(
            items: List<OcrRawItem>,
            scaleX: Float,
            scaleY: Float,
            maxWidth: Int,
            maxHeight: Int
        ): List<SmartDotAccessibilityService.ScreenTextNode> {
            val scaled = items.mapNotNull { item ->
                val left = (item.rect.left * scaleX).roundToInt().coerceIn(0, (maxWidth - 1).coerceAtLeast(0))
                val top = (item.rect.top * scaleY).roundToInt().coerceIn(0, (maxHeight - 1).coerceAtLeast(0))
                val right = (item.rect.right * scaleX).roundToInt().coerceIn(left + 1, maxWidth.coerceAtLeast(left + 1))
                val bottom = (item.rect.bottom * scaleY).roundToInt().coerceIn(top + 1, maxHeight.coerceAtLeast(top + 1))
                if (right <= left || bottom <= top) return@mapNotNull null
                item.text to Rect(left, top, right, bottom)
            }.sortedWith { a, b ->
                val rowDiff = a.second.top - b.second.top
                if (abs(rowDiff) > ROW_SORT_TOLERANCE_PX) {
                    rowDiff
                } else {
                    a.second.left - b.second.left
                }
            }

            return scaled.mapIndexed { idx, (text, rect) ->
                SmartDotAccessibilityService.ScreenTextNode(
                    id = idx,
                    text = text,
                    boundsInScreen = rect,
                    hasVisibleText = true
                )
            }
        }
    }
}
