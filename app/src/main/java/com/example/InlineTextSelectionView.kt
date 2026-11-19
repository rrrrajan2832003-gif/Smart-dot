package com.example

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Build
import android.util.AttributeSet
import android.util.DisplayMetrics
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Transparent, touch-aware overlay view aligned 1:1 over the live screen.
 * Renders subtle bounds over detected English/Hindi OCR text lines and bright highlights
 * over user-selected text without drawing or replacing the underlying screen with a bitmap.
 */
class InlineTextSelectionView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    data class MappedBlock(
        val block: ScreenOcrHelper.OcrTextBlock,
        val rect: RectF
    )

    private var blocks: List<ScreenOcrHelper.OcrTextBlock> = emptyList()
    private var captureWidth: Int = 1
    private var captureHeight: Int = 1
    private var accentColor: Int = Color.parseColor("#00E5FF")

    private val selectedIds = linkedSetOf<Int>()
    private var lastSelectedBlockId: Int? = null
    private val mappedBlocks = mutableListOf<MappedBlock>()

    private var onSelectionChanged: ((selectedIds: Set<Int>, anchorRect: RectF?) -> Unit)? = null
    private var onScrollDismissRequested: (() -> Unit)? = null

    private val density = context.resources.displayMetrics.density
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat().coerceAtLeast(16f * density)
    private val scrollDismissThresholdPx = 72f * density
    private val cornerRadius = 5f * density
    private val boxPad = 2.5f * density
    private val exactPad = 4f * density
    private val nearPad = 10f * density

    private val unselectedFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(20, 255, 255, 255)
    }
    private val unselectedStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.2f * density
        color = Color.argb(120, 226, 232, 240)
    }
    private val selectedFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val selectedStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.2f * density
    }

    private var downX = 0f
    private var downY = 0f
    private var downHitBlockId: Int? = null

    init {
        setWillNotDraw(false)
        isClickable = true
        isFocusable = true
    }

    fun setOcrData(
        blocks: List<ScreenOcrHelper.OcrTextBlock>,
        captureWidth: Int,
        captureHeight: Int,
        accentColor: Int,
        onSelectionChanged: (selectedIds: Set<Int>, anchorRect: RectF?) -> Unit,
        onScrollDismissRequested: () -> Unit
    ) {
        this.blocks = blocks
        this.captureWidth = captureWidth.coerceAtLeast(1)
        this.captureHeight = captureHeight.coerceAtLeast(1)
        this.accentColor = accentColor
        this.onSelectionChanged = onSelectionChanged
        this.onScrollDismissRequested = onScrollDismissRequested
        this.selectedIds.clear()
        this.lastSelectedBlockId = null

        selectedFillPaint.color = Color.argb(
            82,
            Color.red(accentColor),
            Color.green(accentColor),
            Color.blue(accentColor)
        )
        selectedStrokePaint.color = accentColor

        recomputeMappedRects()
        invalidate()
    }

    fun getSelectedIds(): Set<Int> = selectedIds.toSet()

    fun getMappedRectsSnapshot(): List<MappedBlock> = mappedBlocks.toList()

    fun getMappedRectForBlock(blockId: Int): RectF? =
        mappedBlocks.firstOrNull { it.block.id == blockId }?.rect?.let { RectF(it) }

    fun clearSelection() {
        if (selectedIds.isEmpty()) return
        selectedIds.clear()
        lastSelectedBlockId = null
        invalidate()
        onSelectionChanged?.invoke(emptySet(), null)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        recomputeMappedRects()
        if (selectedIds.isNotEmpty()) {
            onSelectionChanged?.invoke(selectedIds.toSet(), computeSelectionAnchorRect())
        }
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        recomputeMappedRects()
        if (changed && selectedIds.isNotEmpty()) {
            onSelectionChanged?.invoke(selectedIds.toSet(), computeSelectionAnchorRect())
        }
    }

    internal fun recomputeMappedRects() {
        mappedBlocks.clear()
        if (blocks.isEmpty()) return

        val (screenW, screenH) = resolveCurrentScreenSize()
        val loc = IntArray(2)
        getLocationOnScreen(loc)

        for (block in blocks) {
            val mapped = ScreenOcrHelper.mapCaptureRectToOverlayLocal(
                captureRect = block.boundingBox,
                captureWidth = captureWidth,
                captureHeight = captureHeight,
                screenWidth = screenW,
                screenHeight = screenH,
                overlayScreenOffsetX = loc[0],
                overlayScreenOffsetY = loc[1]
            )
            // Keep tiny visual padding for clean rounded selection boxes around glyphs.
            mapped.inset(-boxPad, -boxPad)
            mappedBlocks.add(MappedBlock(block, mapped))
        }
    }

    private fun resolveCurrentScreenSize(): Pair<Int, Int> {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        if (wm != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val bounds = wm.maximumWindowMetrics.bounds
                if (bounds.width() > 0 && bounds.height() > 0) {
                    return bounds.width() to bounds.height()
                }
            } else {
                val metrics = DisplayMetrics()
                @Suppress("DEPRECATION")
                wm.defaultDisplay?.getRealMetrics(metrics)
                if (metrics.widthPixels > 0 && metrics.heightPixels > 0) {
                    return metrics.widthPixels to metrics.heightPixels
                }
            }
        }
        val fallbackW = width.takeIf { it > 0 } ?: captureWidth
        val fallbackH = height.takeIf { it > 0 } ?: captureHeight
        return fallbackW to fallbackH
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        for (item in mappedBlocks) {
            if (selectedIds.contains(item.block.id)) {
                canvas.drawRoundRect(item.rect, cornerRadius, cornerRadius, selectedFillPaint)
                canvas.drawRoundRect(item.rect, cornerRadius, cornerRadius, selectedStrokePaint)
            } else {
                canvas.drawRoundRect(item.rect, cornerRadius, cornerRadius, unselectedFillPaint)
                canvas.drawRoundRect(item.rect, cornerRadius, cornerRadius, unselectedStrokePaint)
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                downHitBlockId = findTappedBlockId(downX, downY)
                return true
            }

            MotionEvent.ACTION_UP -> {
                val dx = event.x - downX
                val dy = event.y - downY
                val distance = hypot(dx, dy)

                if (distance <= touchSlop) {
                    performClick()
                    val hitId = findTappedBlockId(event.x, event.y) ?: downHitBlockId
                    if (hitId != null) {
                        toggleBlockSelection(hitId)
                    } else if (selectedIds.isNotEmpty()) {
                        clearSelection()
                    }
                    return true
                }

                // If the user tries to scroll/swipe across empty screen area, dismiss overlay cleanly
                // so normal touch scrolling is never trapped.
                if (downHitBlockId == null && abs(dy) >= scrollDismissThresholdPx) {
                    onScrollDismissRequested?.invoke()
                    return true
                }
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        return super.performClick()
    }

    private fun toggleBlockSelection(blockId: Int) {
        if (selectedIds.contains(blockId)) {
            selectedIds.remove(blockId)
            if (lastSelectedBlockId == blockId) {
                lastSelectedBlockId = selectedIds.lastOrNull()
            }
        } else {
            selectedIds.add(blockId)
            lastSelectedBlockId = blockId
        }
        invalidate()
        onSelectionChanged?.invoke(selectedIds.toSet(), computeSelectionAnchorRect())
    }

    private fun computeSelectionAnchorRect(): RectF? {
        if (selectedIds.isEmpty()) return null
        val union = RectF()
        var initialized = false
        for (item in mappedBlocks) {
            if (selectedIds.contains(item.block.id)) {
                if (!initialized) {
                    union.set(item.rect)
                    initialized = true
                } else {
                    union.union(item.rect)
                }
            }
        }
        return if (initialized) union else null
    }

    private fun findTappedBlockId(x: Float, y: Float): Int? {
        var bestId: Int? = null
        var bestArea = Float.MAX_VALUE
        for (item in mappedBlocks) {
            val r = item.rect
            if (x >= r.left - exactPad &&
                x <= r.right + exactPad &&
                y >= r.top - exactPad &&
                y <= r.bottom + exactPad
            ) {
                val area = r.width().coerceAtLeast(1f) * r.height().coerceAtLeast(1f)
                if (area < bestArea) {
                    bestArea = area
                    bestId = item.block.id
                }
            }
        }
        if (bestId != null) return bestId

        var closestId: Int? = null
        var minDistSq = Float.MAX_VALUE
        for (item in mappedBlocks) {
            val r = item.rect
            if (x >= r.left - nearPad &&
                x <= r.right + nearPad &&
                y >= r.top - nearPad &&
                y <= r.bottom + nearPad
            ) {
                val cx = x.coerceIn(r.left, r.right)
                val cy = y.coerceIn(r.top, r.bottom)
                val dSq = (x - cx) * (x - cx) + (y - cy) * (y - cy)
                if (dSq < minDistSq) {
                    minDistSq = dSq
                    closestId = item.block.id
                }
            }
        }
        return closestId
    }
}
