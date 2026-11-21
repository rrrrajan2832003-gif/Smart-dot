package com.example

import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
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
 * 100% transparent overlay view aligned 1:1 over the live screen with ZERO unselected boxes,
 * ZERO borders, and ZERO strokes.
 *
 * Both COPY and TRANSLATE modes share the exact same sleek thin (6dp) highlighter pen gesture:
 * - Single tap on any word highlights strictly that word (2dp inward-padded RoundRect inside its box)
 *   and anchors the floating "Copy" pill right beside it.
 * - Dragging a finger draws a thin 6dp highlighter stroke and selects ONLY intersecting words
 *   (strict tight collision radius so adjacent words above/below/beside are never accidentally grabbed),
 *   showing the "Copy" pill on ACTION_UP.
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

    private var selectedWordIds = LinkedHashSet<Int>()
    private val mappedCopyNodes = mutableListOf<MappedCopyNode>()
    private val mappedTranslatedWordNodes = mutableListOf<MappedCopyNode>()
    private val renderedTranslatedLabels = mutableListOf<RenderedTranslatedLabel>()

    private var onCopySelectionChanged: ((selectedText: String, anchorRect: RectF?) -> Unit)? = null
    private var onScrollDismissRequested: (() -> Unit)? = null

    private val density = context.resources.displayMetrics.density
    private val tapTouchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat().coerceIn(8f * density, 14f * density)
    private val scrollDismissThresholdPx = 72f * density
    private val pillCornerRadius = 6f * density
    private val highlightCornerRadius = 4f * density
    private val highlightInsetPad = 2f * density
    private val labelPadX = 2.5f * density
    private val labelPadY = 1.5f * density

    // Tight tap & stroke collision tolerances so adjacent rows/words are never grabbed by mistake
    private val exactPad = 2f * density
    private val nearPad = 4f * density
    private val highlighterHitRadius = 1.5f * density

    // Zero stroke/border paints for text outlines — borderless selection fills + sleek thin 6dp highlighter pen
    private val selectedHighlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val highlighterStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = 6f * density
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
        this.mode = DisplayMode.COPY
        this.rawNodes = nodes
        this.translatedItems = emptyList()
        this.accentColor = accentColor
        this.selectedWordIds.clear()
        this.isHighlighterDragging = false
        this.highlighterPath.reset()
        this.onCopySelectionChanged = onCopySelectionChanged
        this.onScrollDismissRequested = onScrollDismissRequested

        applyAccentPaints(accentColor)
        recomputeAllRects()
        invalidate()
    }

    fun configureTranslateMode(
        accentColor: Int,
        onCopySelectionChanged: (selectedText: String, anchorRect: RectF?) -> Unit,
        onScrollDismissRequested: () -> Unit
    ) {
        this.mode = DisplayMode.TRANSLATE
        this.rawNodes = emptyList()
        this.translatedItems = emptyList()
        this.accentColor = accentColor
        this.selectedWordIds.clear()
        this.isHighlighterDragging = false
        this.highlighterPath.reset()
        this.onCopySelectionChanged = onCopySelectionChanged
        this.onScrollDismissRequested = onScrollDismissRequested

        applyAccentPaints(accentColor)
        recomputeAllRects()
        invalidate()
    }

    private fun applyAccentPaints(color: Int) {
        selectedHighlightPaint.color = Color.argb(
            84,
            Color.red(color),
            Color.green(color),
            Color.blue(color)
        )
        highlighterStrokePaint.color = Color.argb(
            135,
            Color.red(color),
            Color.green(color),
            Color.blue(color)
        )
    }

    fun setBulkTranslations(items: List<ScreenTranslateHelper.TranslatedNode>) {
        this.mode = DisplayMode.TRANSLATE
        this.translatedItems = items
        this.selectedWordIds.clear()
        this.isHighlighterDragging = false
        this.highlighterPath.reset()
        recomputeAllRects()
        invalidate()
        onCopySelectionChanged?.invoke("", null)
    }

    private fun activeWordList(): List<MappedCopyNode> =
        if (mode == DisplayMode.COPY) mappedCopyNodes else mappedTranslatedWordNodes

    fun getSelectedWordNodes(): List<SmartDotAccessibilityService.ScreenTextNode> {
        if (selectedWordIds.isEmpty()) return emptyList()
        return activeWordList()
            .filter { it.node.id in selectedWordIds }
            .map { it.node }
    }

    fun getSelectedCopyNodes(): List<SmartDotAccessibilityService.ScreenTextNode> =
        getSelectedWordNodes()

    fun getSelectedCopyNode(): SmartDotAccessibilityService.ScreenTextNode? =
        getSelectedWordNodes().firstOrNull()

    fun getCombinedSelectedText(): String =
        getSelectedWordNodes().joinToString(" ") { it.text }

    fun getCombinedSelectedCopyText(): String = getCombinedSelectedText()

    fun isHighlighterTrailActive(): Boolean = isHighlighterDragging

    fun getHighlighterStrokeWidthPx(): Float = highlighterStrokePaint.strokeWidth

    fun getMappedRectForCopyNode(nodeId: Int): RectF? =
        activeWordList().firstOrNull { it.node.id == nodeId }?.rect?.let { RectF(it) }

    fun getHighlightDrawRect(nodeId: Int): RectF? =
        getMappedRectForCopyNode(nodeId)?.let { computeSnugHighlightRect(it) }

    fun getRenderedTranslatedLabelsSnapshot(): List<RenderedTranslatedLabel> =
        renderedTranslatedLabels.toList()

    fun getMappedCopyNodesSnapshot(): List<MappedCopyNode> = mappedCopyNodes.toList()

    fun getMappedTranslatedWordNodesSnapshot(): List<MappedCopyNode> = mappedTranslatedWordNodes.toList()

    fun clearSelection() {
        if (selectedWordIds.isEmpty() && !isHighlighterDragging) return
        selectedWordIds.clear()
        isHighlighterDragging = false
        highlighterPath.reset()
        invalidate()
        onCopySelectionChanged?.invoke("", null)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        recomputeAllRects()
        if (selectedWordIds.isNotEmpty() && !isHighlighterDragging) {
            emitCurrentSelectionToController()
        }
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        recomputeAllRects()
        if (changed && selectedWordIds.isNotEmpty() && !isHighlighterDragging) {
            emitCurrentSelectionToController()
        }
    }

    internal fun recomputeAllRects() {
        mappedCopyNodes.clear()
        mappedTranslatedWordNodes.clear()
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

        val innerPadX = 2f * density
        val wordRegex = Regex("\\S+")
        var nextTranslatedWordId = 0

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

            val rendered = RenderedTranslatedLabel(
                item = item,
                rect = local,
                layout = layout,
                textOffsetY = textOffsetY
            )
            renderedTranslatedLabels.add(rendered)

            val translatedText = item.result.translatedText
            val originX = local.left + innerPadX
            val originY = local.top + textOffsetY
            val lineCount = layout.lineCount.coerceAtLeast(1)

            for (match in wordRegex.findAll(translatedText)) {
                val word = match.value
                val startIdx = match.range.first
                val endIdx = (match.range.last + 1).coerceAtMost(translatedText.length)
                val lineIdx = layout.getLineForOffset(startIdx).coerceIn(0, lineCount - 1)
                val lineTop = (originY + layout.getLineTop(lineIdx)).coerceIn(local.top, local.bottom - 2f)
                val lineBottom = (originY + layout.getLineBottom(lineIdx)).coerceIn(lineTop + 2f, local.bottom)

                val lineStart = layout.getLineStart(lineIdx).coerceIn(0, translatedText.length)
                val lineEnd = layout.getLineEnd(lineIdx).coerceIn(lineStart, translatedText.length)

                var leftX = originX + layout.getPrimaryHorizontal(startIdx)
                var rightX = if (endIdx <= lineEnd) {
                    originX + layout.getPrimaryHorizontal(endIdx)
                } else {
                    originX + layout.getPrimaryHorizontal(lineEnd)
                }

                // Fallback when Robolectric/headless StaticLayout returns 0f for getPrimaryHorizontal
                val rawSpan = abs(rightX - leftX)
                if (rawSpan < 1f || (startIdx > lineStart && abs(leftX - originX) < 0.5f)) {
                    val lineSlice = translatedText.substring(lineStart, lineEnd)
                    val relStart = (startIdx - lineStart).coerceIn(0, lineSlice.length)
                    val relEnd = (endIdx - lineStart).coerceIn(relStart, lineSlice.length)
                    val prefixText = lineSlice.substring(0, relStart)
                    val wordText = lineSlice.substring(relStart, relEnd)
                    val totalLineW = measureTextCompat(layout.paint, lineSlice).coerceAtLeast(1f)
                    val prefixW = measureTextCompat(layout.paint, prefixText)
                    val wordW = measureTextCompat(layout.paint, wordText).coerceAtLeast(4f)
                    val maxDrawW = (local.width() - innerPadX * 2f).coerceAtLeast(4f)
                    val scale = if (totalLineW > maxDrawW) maxDrawW / totalLineW else 1f
                    leftX = originX + prefixW * scale
                    rightX = leftX + wordW * scale
                }

                val wordLeft = minOf(leftX, rightX).coerceIn(local.left, (local.right - 2f).coerceAtLeast(local.left))
                val wordRight = maxOf(leftX, rightX).coerceIn(wordLeft + 2f, local.right.coerceAtLeast(wordLeft + 2f))
                val wordRectF = RectF(wordLeft, lineTop, wordRight, lineBottom)

                val id = nextTranslatedWordId++
                mappedTranslatedWordNodes.add(
                    MappedCopyNode(
                        node = SmartDotAccessibilityService.ScreenTextNode(
                            id = id,
                            text = word,
                            boundsInScreen = Rect(
                                wordRectF.left.toInt(),
                                wordRectF.top.toInt(),
                                wordRectF.right.toInt(),
                                wordRectF.bottom.toInt()
                            ),
                            hasVisibleText = true
                        ),
                        rect = wordRectF
                    )
                )
            }
        }
    }

    private fun measureTextCompat(paint: TextPaint, text: String): Float {
        if (text.isEmpty()) return 0f
        val measured = paint.measureText(text)
        if (paint.textSize >= 8f && paint.measureText("MMMM") < paint.textSize) {
            return text.length * paint.textSize * 0.54f
        }
        return measured
    }

    private fun computeSnugHighlightRect(source: RectF): RectF {
        val padX = if (source.width() > highlightInsetPad * 3f) highlightInsetPad else 0f
        val padY = if (source.height() > highlightInsetPad * 3f) highlightInsetPad else 0f
        return RectF(
            source.left + padX,
            source.top + padY,
            source.right - padX,
            source.bottom - padY
        )
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
            // Zero unselected borders/outlines — draw only snug 2dp-padded highlights strictly inside matched word boxes
            for (mapped in mappedCopyNodes) {
                if (mapped.node.id in selectedWordIds) {
                    val drawRect = computeSnugHighlightRect(mapped.rect)
                    canvas.drawRoundRect(drawRect, highlightCornerRadius, highlightCornerRadius, selectedHighlightPaint)
                }
            }
            if (isHighlighterDragging && !highlighterPath.isEmpty) {
                canvas.drawPath(highlighterPath, highlighterStrokePaint)
            }
            return
        }

        // 1. Draw borderless background-matched translated line pills in place
        for (rendered in renderedTranslatedLabels) {
            canvas.drawRoundRect(rendered.rect, pillCornerRadius, pillCornerRadius, translatedLabelBgPaint)
        }

        // 2. Draw snug 2dp-padded highlights under selected translated words for razor-sharp contrast
        for (mapped in mappedTranslatedWordNodes) {
            if (mapped.node.id in selectedWordIds) {
                val drawRect = computeSnugHighlightRect(mapped.rect)
                canvas.drawRoundRect(drawRect, highlightCornerRadius, highlightCornerRadius, selectedHighlightPaint)
            }
        }

        // 3. Draw translated text cleanly on top
        val innerPadX = 2f * density
        for (rendered in renderedTranslatedLabels) {
            val saveCount = canvas.save()
            canvas.clipRect(rendered.rect)
            canvas.translate(rendered.rect.left + innerPadX, rendered.rect.top + rendered.textOffsetY)
            rendered.layout.draw(canvas)
            canvas.restoreToCount(saveCount)
        }

        // 4. Draw thin 6dp highlighter stroke while dragging over translated words
        if (isHighlighterDragging && !highlighterPath.isEmpty) {
            canvas.drawPath(highlighterPath, highlighterStrokePaint)
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
                val x = event.x
                val y = event.y
                if (!isHighlighterDragging) {
                    val moved = hypot(x - downX, y - downY)
                    if (moved >= tapTouchSlop) {
                        isHighlighterDragging = true
                        selectedWordIds.clear()
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

            MotionEvent.ACTION_UP -> {
                val dx = event.x - downX
                val dy = event.y - downY

                if (isHighlighterDragging) {
                    highlightWordsIntersectingSegment(lastTouchX, lastTouchY, event.x, event.y)
                    highlighterPath.reset()
                    isHighlighterDragging = false
                    invalidate()

                    if (selectedWordIds.isNotEmpty()) {
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

                // Single tap without drag -> select ONLY that single word and show Copy pill beside it
                performClick()
                val hitId = findTappedNodeId(event.x, event.y) ?: downHitNodeId
                if (hitId != null) {
                    if (selectedWordIds.size == 1 && selectedWordIds.contains(hitId)) {
                        selectedWordIds.clear()
                        invalidate()
                        onCopySelectionChanged?.invoke("", null)
                    } else {
                        selectedWordIds.clear()
                        selectedWordIds.add(hitId)
                        invalidate()
                        emitCurrentSelectionToController()
                    }
                } else if (selectedWordIds.isNotEmpty()) {
                    clearSelection()
                }
                return true
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
        val selectedMapped = activeWordList().filter { it.node.id in selectedWordIds }
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
        for (mapped in activeWordList()) {
            if (segmentIntersectsRect(x1, y1, x2, y2, mapped.rect, highlighterHitRadius)) {
                selectedWordIds.add(mapped.node.id)
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
        val candidates = activeWordList()

        var bestId: Int? = null
        var bestArea = Float.MAX_VALUE
        for (mapped in candidates) {
            val r = mapped.rect
            if (x >= r.left - exactPad &&
                x <= r.right + exactPad &&
                y >= r.top - exactPad &&
                y <= r.bottom + exactPad
            ) {
                val area = r.width().coerceAtLeast(1f) * r.height().coerceAtLeast(1f)
                if (area < bestArea) {
                    bestArea = area
                    bestId = mapped.node.id
                }
            }
        }
        if (bestId != null) return bestId

        var closestId: Int? = null
        var minDistSq = Float.MAX_VALUE
        for (mapped in candidates) {
            val r = mapped.rect
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
                    closestId = mapped.node.id
                }
            }
        }
        return closestId
    }
}
