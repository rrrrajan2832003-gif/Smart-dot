package com.example

import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.util.AttributeSet
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Transparent overlay view aligned 1:1 over the live screen with ZERO unselected boxes,
 * ZERO borders, and ZERO strokes.
 *
 * - COPY mode: Invisible hit-areas over each text Rect; tapping softly highlights ONLY the
 *   tapped text (borderless tint) and anchors a floating "Copy" pill beside it.
 * - TRANSLATE mode: Automatically renders clean, borderless, background-matched translated
 *   text pills directly over each original text Rect in place; tapping any translated label
 *   copies that translated text immediately.
 */
class InlineTextSelectionView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    enum class DisplayMode {
        COPY,
        TRANSLATE
    }

    data class MappedCopyNode(
        val node: SmartDotAccessibilityService.ScreenTextNode,
        val rect: RectF
    )

    data class RenderedTranslatedLabel(
        val item: ScreenTranslateHelper.TranslatedNode,
        val rect: RectF,
        val layout: StaticLayout,
        val textOffsetY: Float
    )

    private var mode: DisplayMode = DisplayMode.COPY
    private var rawNodes: List<SmartDotAccessibilityService.ScreenTextNode> = emptyList()
    private var translatedItems: List<ScreenTranslateHelper.TranslatedNode> = emptyList()
    private var accentColor: Int = Color.parseColor("#00E5FF")

    private var selectedCopyNodeIds = LinkedHashSet<Int>()
    private var flashedTranslateNodeId: Int? = null
    private val mappedCopyNodes = mutableListOf<MappedCopyNode>()
    private val renderedTranslatedLabels = mutableListOf<RenderedTranslatedLabel>()

    private var onCopySelectionChanged: ((selectedText: String, anchorRect: RectF?) -> Unit)? = null
    private var onTranslatedNodeTapped: ((item: ScreenTranslateHelper.TranslatedNode) -> Unit)? = null
    private var onScrollDismissRequested: (() -> Unit)? = null

    private val density = context.resources.displayMetrics.density
    private val tapTouchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat().coerceIn(8f * density, 14f * density)
    private val scrollDismissThresholdPx = 72f * density
    private val cornerRadius = 6f * density
    private val labelPadX = 2.5f * density
    private val labelPadY = 1.5f * density
    private val exactPad = 4f * density
    private val nearPad = 10f * density
    private val highlighterHitRadius = 8f * density

    // Zero stroke/border paints for text outlines — only soft borderless fills and live highlighter marker trail.
    private val selectedHighlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val highlighterStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = 24f * density
    }
    private val translatedLabelBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val baseTranslateTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.DEFAULT_BOLD
    }

    private val highlighterPath = Path()
    private var isHighlighterDragging = false
    private var downX = 0f
    private var downY = 0f
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var downHitNodeId: Int? = null

    private val clearFlashRunnable = Runnable {
        flashedTranslateNodeId = null
        invalidate()
    }

    init {
        setWillNotDraw(false)
        setBackgroundColor(Color.TRANSPARENT)
        isClickable = true
        isFocusable = true
    }

    fun configureCopyMode(
        nodes: List<SmartDotAccessibilityService.ScreenTextNode>,
        accentColor: Int,
        onCopySelectionChanged: (selectedText: String, anchorRect: RectF?) -> Unit,
        onScrollDismissRequested: () -> Unit
    ) {
        removeCallbacks(clearFlashRunnable)
        this.mode = DisplayMode.COPY
        this.rawNodes = nodes
        this.translatedItems = emptyList()
        this.accentColor = accentColor
        this.selectedCopyNodeIds.clear()
        this.flashedTranslateNodeId = null
        this.isHighlighterDragging = false
        this.highlighterPath.reset()
        this.onCopySelectionChanged = onCopySelectionChanged
        this.onTranslatedNodeTapped = null
        this.onScrollDismissRequested = onScrollDismissRequested

        selectedHighlightPaint.color = Color.argb(
            82,
            Color.red(accentColor),
            Color.green(accentColor),
            Color.blue(accentColor)
        )
        highlighterStrokePaint.color = Color.argb(
            96,
            Color.red(accentColor),
            Color.green(accentColor),
            Color.blue(accentColor)
        )

        recomputeAllRects()
        invalidate()
    }

    fun configureTranslateMode(
        accentColor: Int,
        onTranslatedNodeTapped: (item: ScreenTranslateHelper.TranslatedNode) -> Unit,
        onScrollDismissRequested: () -> Unit
    ) {
        removeCallbacks(clearFlashRunnable)
        this.mode = DisplayMode.TRANSLATE
        this.rawNodes = emptyList()
        this.translatedItems = emptyList()
        this.accentColor = accentColor
        this.selectedCopyNodeIds.clear()
        this.flashedTranslateNodeId = null
        this.isHighlighterDragging = false
        this.highlighterPath.reset()
        this.onCopySelectionChanged = null
        this.onTranslatedNodeTapped = onTranslatedNodeTapped
        this.onScrollDismissRequested = onScrollDismissRequested

        selectedHighlightPaint.color = Color.argb(
            76,
            Color.red(accentColor),
            Color.green(accentColor),
            Color.blue(accentColor)
        )

        recomputeAllRects()
        invalidate()
    }

    fun setBulkTranslations(items: List<ScreenTranslateHelper.TranslatedNode>) {
        this.mode = DisplayMode.TRANSLATE
        this.translatedItems = items
        this.flashedTranslateNodeId = null
        recomputeAllRects()
        invalidate()
    }

    fun getSelectedCopyNodes(): List<SmartDotAccessibilityService.ScreenTextNode> {
        if (selectedCopyNodeIds.isEmpty()) return emptyList()
        return rawNodes.filter { it.id in selectedCopyNodeIds }
    }

    fun getSelectedCopyNode(): SmartDotAccessibilityService.ScreenTextNode? =
        getSelectedCopyNodes().firstOrNull()

    fun getCombinedSelectedCopyText(): String =
        getSelectedCopyNodes().joinToString(" ") { it.text }

    fun isHighlighterTrailActive(): Boolean = isHighlighterDragging

    fun getMappedRectForCopyNode(nodeId: Int): RectF? =
        mappedCopyNodes.firstOrNull { it.node.id == nodeId }?.rect?.let { RectF(it) }

    fun getRenderedTranslatedLabelsSnapshot(): List<RenderedTranslatedLabel> =
        renderedTranslatedLabels.toList()

    fun getMappedCopyNodesSnapshot(): List<MappedCopyNode> = mappedCopyNodes.toList()

    fun clearSelection() {
        if (selectedCopyNodeIds.isEmpty() && !isHighlighterDragging) return
        selectedCopyNodeIds.clear()
        isHighlighterDragging = false
        highlighterPath.reset()
        invalidate()
        onCopySelectionChanged?.invoke("", null)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        recomputeAllRects()
        if (mode == DisplayMode.COPY && selectedCopyNodeIds.isNotEmpty() && !isHighlighterDragging) {
            emitCurrentSelectionToController()
        }
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        recomputeAllRects()
        if (changed && mode == DisplayMode.COPY && selectedCopyNodeIds.isNotEmpty() && !isHighlighterDragging) {
            emitCurrentSelectionToController()
        }
    }

    internal fun recomputeAllRects() {
        mappedCopyNodes.clear()
        renderedTranslatedLabels.clear()

        val loc = IntArray(2)
        getLocationOnScreen(loc)

        if (mode == DisplayMode.COPY) {
            for (node in rawNodes) {
                val local = RectF(
                    node.boundsInScreen.left.toFloat() - loc[0],
                    node.boundsInScreen.top.toFloat() - loc[1],
                    node.boundsInScreen.right.toFloat() - loc[0],
                    node.boundsInScreen.bottom.toFloat() - loc[1]
                )
                mappedCopyNodes.add(MappedCopyNode(node, local))
            }
            return
        }

        if (translatedItems.isEmpty()) return

        val (bgColor, textColor) = resolveAdaptiveLabelColors()
        translatedLabelBgPaint.color = bgColor
        val labelPaint = TextPaint(baseTranslateTextPaint).apply {
            color = textColor
        }

        for (item in translatedItems) {
            val bounds = item.node.boundsInScreen
            val local = RectF(
                bounds.left.toFloat() - loc[0],
                bounds.top.toFloat() - loc[1],
                bounds.right.toFloat() - loc[0],
                bounds.bottom.toFloat() - loc[1]
            )
            local.inset(-labelPadX, -labelPadY)

            val availW = (local.width() - 4f * density).toInt().coerceAtLeast((24 * density).toInt())
            val availH = (local.height() - 2f * density).coerceAtLeast(12f * density)

            val layout = buildFittedStaticLayout(item.result.translatedText, labelPaint, availW, availH)
            val textOffsetY = ((local.height() - layout.height) / 2f).coerceAtLeast(1f * density)

            renderedTranslatedLabels.add(
                RenderedTranslatedLabel(
                    item = item,
                    rect = local,
                    layout = layout,
                    textOffsetY = textOffsetY
                )
            )
        }
    }

    private fun resolveAdaptiveLabelColors(): Pair<Int, Int> {
        val isDarkMode = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        return if (isDarkMode) {
            Color.argb(246, 15, 23, 42) to Color.parseColor("#F8FAFC")
        } else {
            Color.argb(246, 250, 251, 252) to Color.parseColor("#0F172A")
        }
    }

    private fun buildFittedStaticLayout(
        text: String,
        paint: TextPaint,
        maxWidthPx: Int,
        maxHeightPx: Float
    ): StaticLayout {
        val minTextSizePx = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP,
            8.5f,
            resources.displayMetrics
        ).coerceAtLeast(10f)
        val maxCapPx = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP,
            20f,
            resources.displayMetrics
        )
        val maxTextSizePx = (maxHeightPx * 0.78f).coerceIn(minTextSizePx, maxCapPx)

        var currentSize = maxTextSizePx
        var bestLayout: StaticLayout? = null

        while (currentSize >= minTextSizePx) {
            paint.textSize = currentSize
            val candidate = createStaticLayout(text, paint, maxWidthPx, maxLines = Int.MAX_VALUE)
            bestLayout = candidate
            if (candidate.height <= maxHeightPx + 2f) {
                return candidate
            }
            currentSize -= 1.2f * density.coerceAtLeast(1f)
        }

        paint.textSize = minTextSizePx
        val approxMaxLines = (maxHeightPx / (minTextSizePx * 1.15f)).toInt().coerceAtLeast(1)
        return bestLayout?.let {
            createStaticLayout(text, paint, maxWidthPx, maxLines = approxMaxLines)
        } ?: createStaticLayout(text, paint, maxWidthPx, maxLines = approxMaxLines)
    }

    private fun createStaticLayout(
        text: String,
        paint: TextPaint,
        maxWidthPx: Int,
        maxLines: Int
    ): StaticLayout {
        return StaticLayout.Builder.obtain(text, 0, text.length, paint, maxWidthPx)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setIncludePad(false)
            .setMaxLines(maxLines)
            .setEllipsize(TextUtils.TruncateAt.END)
            .build()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (mode == DisplayMode.COPY) {
            // Zero unselected borders/outlines — draw only highlighted selected words and active highlighter trail.
            for (mapped in mappedCopyNodes) {
                if (mapped.node.id in selectedCopyNodeIds) {
                    canvas.drawRoundRect(mapped.rect, cornerRadius, cornerRadius, selectedHighlightPaint)
                }
            }
            if (isHighlighterDragging && !highlighterPath.isEmpty) {
                canvas.drawPath(highlighterPath, highlighterStrokePaint)
            }
            return
        }

        val innerPadX = 2f * density
        for (rendered in renderedTranslatedLabels) {
            // Clean borderless pill matching background + fitted translated text inside Rect.
            canvas.drawRoundRect(rendered.rect, cornerRadius, cornerRadius, translatedLabelBgPaint)
            if (rendered.item.node.id == flashedTranslateNodeId) {
                canvas.drawRoundRect(rendered.rect, cornerRadius, cornerRadius, selectedHighlightPaint)
            }

            val saveCount = canvas.save()
            canvas.clipRect(rendered.rect)
            canvas.translate(rendered.rect.left + innerPadX, rendered.rect.top + rendered.textOffsetY)
            rendered.layout.draw(canvas)
            canvas.restoreToCount(saveCount)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                lastTouchX = event.x
                lastTouchY = event.y
                downHitNodeId = findTappedNodeId(downX, downY)
                isHighlighterDragging = false
                highlighterPath.reset()
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (mode == DisplayMode.COPY) {
                    val x = event.x
                    val y = event.y
                    if (!isHighlighterDragging) {
                        val moved = hypot(x - downX, y - downY)
                        if (moved >= tapTouchSlop) {
                            isHighlighterDragging = true
                            selectedCopyNodeIds.clear()
                            onCopySelectionChanged?.invoke("", null)
                            highlighterPath.moveTo(downX, downY)
                            highlightWordsIntersectingSegment(downX, downY, x, y)
                        }
                    }
                    if (isHighlighterDragging) {
                        val midX = (lastTouchX + x) * 0.5f
                        val midY = (lastTouchY + y) * 0.5f
                        highlighterPath.quadTo(lastTouchX, lastTouchY, midX, midY)
                        highlightWordsIntersectingSegment(lastTouchX, lastTouchY, x, y)
                        lastTouchX = x
                        lastTouchY = y
                        invalidate()
                    }
                    return true
                }
            }

            MotionEvent.ACTION_UP -> {
                val dx = event.x - downX
                val dy = event.y - downY
                val distance = hypot(dx, dy)

                if (mode == DisplayMode.COPY) {
                    if (isHighlighterDragging) {
                        highlightWordsIntersectingSegment(lastTouchX, lastTouchY, event.x, event.y)
                        highlighterPath.reset()
                        isHighlighterDragging = false
                        invalidate()

                        if (selectedCopyNodeIds.isNotEmpty()) {
                            emitCurrentSelectionToController()
                        } else {
                            onCopySelectionChanged?.invoke("", null)
                            if (downHitNodeId == null &&
                                abs(dy) >= scrollDismissThresholdPx &&
                                abs(dy) > abs(dx) * 1.8f
                            ) {
                                onScrollDismissRequested?.invoke()
                            }
                        }
                        return true
                    }

                    // Single tap without drag -> select ONLY that single word.
                    performClick()
                    val hitId = findTappedNodeId(event.x, event.y) ?: downHitNodeId
                    if (hitId != null) {
                        if (selectedCopyNodeIds.size == 1 && selectedCopyNodeIds.contains(hitId)) {
                            selectedCopyNodeIds.clear()
                            invalidate()
                            onCopySelectionChanged?.invoke("", null)
                        } else {
                            selectedCopyNodeIds.clear()
                            selectedCopyNodeIds.add(hitId)
                            invalidate()
                            emitCurrentSelectionToController()
                        }
                    } else if (selectedCopyNodeIds.isNotEmpty()) {
                        clearSelection()
                    }
                    return true
                }

                if (distance <= tapTouchSlop) {
                    performClick()
                    val hitId = findTappedNodeId(event.x, event.y) ?: downHitNodeId
                    if (hitId != null) {
                        val tapped = renderedTranslatedLabels.firstOrNull { it.item.node.id == hitId }?.item
                        if (tapped != null) {
                            flashedTranslateNodeId = hitId
                            invalidate()
                            removeCallbacks(clearFlashRunnable)
                            postDelayed(clearFlashRunnable, 220L)
                            onTranslatedNodeTapped?.invoke(tapped)
                        }
                    }
                    return true
                }

                // Dismiss cleanly if user swipes across empty screen area so scrolling is never trapped.
                if (downHitNodeId == null && abs(dy) >= scrollDismissThresholdPx) {
                    onScrollDismissRequested?.invoke()
                    return true
                }
            }

            MotionEvent.ACTION_CANCEL -> {
                if (isHighlighterDragging) {
                    isHighlighterDragging = false
                    highlighterPath.reset()
                    invalidate()
                }
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        return super.performClick()
    }

    private fun emitCurrentSelectionToController() {
        val selectedMapped = mappedCopyNodes.filter { it.node.id in selectedCopyNodeIds }
        if (selectedMapped.isEmpty()) {
            onCopySelectionChanged?.invoke("", null)
            return
        }
        val combinedText = selectedMapped.joinToString(" ") { it.node.text }
        val unionRect = RectF(selectedMapped[0].rect)
        for (i in 1 until selectedMapped.size) {
            unionRect.union(selectedMapped[i].rect)
        }
        onCopySelectionChanged?.invoke(combinedText, unionRect)
    }

    private fun highlightWordsIntersectingSegment(x1: Float, y1: Float, x2: Float, y2: Float) {
        for (mapped in mappedCopyNodes) {
            if (segmentIntersectsRect(x1, y1, x2, y2, mapped.rect, highlighterHitRadius)) {
                selectedCopyNodeIds.add(mapped.node.id)
            }
        }
    }

    private fun segmentIntersectsRect(
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float,
        rect: RectF,
        expandPx: Float
    ): Boolean {
        val left = rect.left - expandPx
        val top = rect.top - expandPx
        val right = rect.right + expandPx
        val bottom = rect.bottom + expandPx

        val dx = x2 - x1
        val dy = y2 - y1
        var t0 = 0f
        var t1 = 1f

        val p = floatArrayOf(-dx, dx, -dy, dy)
        val q = floatArrayOf(x1 - left, right - x1, y1 - top, bottom - y1)

        for (i in 0 until 4) {
            if (p[i] == 0f) {
                if (q[i] < 0f) return false
            } else {
                val r = q[i] / p[i]
                if (p[i] < 0f) {
                    if (r > t1) return false
                    if (r > t0) t0 = r
                } else {
                    if (r < t0) return false
                    if (r < t1) t1 = r
                }
            }
        }
        return t0 <= t1
    }

    private fun findTappedNodeId(x: Float, y: Float): Int? {
        val candidates: List<Pair<Int, RectF>> = if (mode == DisplayMode.COPY) {
            mappedCopyNodes.map { it.node.id to it.rect }
        } else {
            renderedTranslatedLabels.map { it.item.node.id to it.rect }
        }

        var bestId: Int? = null
        var bestArea = Float.MAX_VALUE
        for ((id, r) in candidates) {
            if (x >= r.left - exactPad &&
                x <= r.right + exactPad &&
                y >= r.top - exactPad &&
                y <= r.bottom + exactPad
            ) {
                val area = r.width().coerceAtLeast(1f) * r.height().coerceAtLeast(1f)
                if (area < bestArea) {
                    bestArea = area
                    bestId = id
                }
            }
        }
        if (bestId != null) return bestId

        var closestId: Int? = null
        var minDistSq = Float.MAX_VALUE
        for ((id, r) in candidates) {
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
                    closestId = id
                }
            }
        }
        return closestId
    }
}
