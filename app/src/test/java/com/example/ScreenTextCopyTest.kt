package com.example

import android.app.Application
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.LinearLayout
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import org.robolectric.shadows.ShadowToast
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ScreenTextCopyTest {

    private lateinit var appContext: Application

    @Before
    fun setUp() {
        appContext = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun `shortcut ACTION_LIST places Screen Translate right below Screen Text Copy with ic_tab_translate icon`() {
        val copyIndex = ACTION_LIST.indexOfFirst { it.id == "screen_copy" }
        val translateIndex = ACTION_LIST.indexOfFirst { it.id == "screen_translate" }
        assertTrue("screen_copy must exist in ACTION_LIST", copyIndex >= 0)
        assertEquals("screen_translate must appear right below screen_copy", copyIndex + 1, translateIndex)
        assertEquals("Screen Text Copy", ACTION_LIST[copyIndex].name)
        assertEquals("Screen Translate", ACTION_LIST[translateIndex].name)

        val service = org.robolectric.Robolectric.buildService(FloatingService::class.java).create().get()
        val getActionIcon = FloatingService::class.java.getDeclaredMethod("getActionIcon", String::class.java)
            .apply { isAccessible = true }
        assertEquals(R.drawable.ic_tab_translate, getActionIcon.invoke(service, "screen_translate"))
    }

    @Test
    fun `extractTextNodesFromRoot scans visible text and contentDescription in reading order with screen bounds`() {
        val root = AccessibilityNodeInfo.obtain().apply {
            isVisibleToUser = true
            setBoundsInScreen(Rect(0, 0, 1080, 2400))
        }
        val childTop = AccessibilityNodeInfo.obtain().apply {
            isVisibleToUser = true
            text = "Breaking News"
            setBoundsInScreen(Rect(40, 120, 420, 180))
        }
        val childIcon = AccessibilityNodeInfo.obtain().apply {
            isVisibleToUser = true
            contentDescription = "Share Article"
            setBoundsInScreen(Rect(880, 120, 980, 180))
        }
        val childDevanagari = AccessibilityNodeInfo.obtain().apply {
            isVisibleToUser = true
            text = "ताज़ा खबरें"
            setBoundsInScreen(Rect(40, 240, 360, 310))
        }
        val invisibleChild = AccessibilityNodeInfo.obtain().apply {
            isVisibleToUser = false
            text = "Hidden Node"
            setBoundsInScreen(Rect(40, 340, 360, 400))
        }

        Shadows.shadowOf(root).addChild(childDevanagari)
        Shadows.shadowOf(root).addChild(childIcon)
        Shadows.shadowOf(root).addChild(childTop)
        Shadows.shadowOf(root).addChild(invisibleChild)

        val nodes = SmartDotAccessibilityService.extractTextNodesFromRoot(root, requireVisibleToUser = true)
        assertEquals(3, nodes.size)
        assertEquals("Breaking News", nodes[0].text)
        assertTrue(nodes[0].hasVisibleText)
        assertEquals(Rect(40, 120, 420, 180), nodes[0].boundsInScreen)
        assertEquals("Share Article", nodes[1].text)
        assertFalse(nodes[1].hasVisibleText)
        assertEquals("ताज़ा खबरें", nodes[2].text)
        assertTrue(nodes[2].hasVisibleText)

        @Suppress("DEPRECATION")
        runCatching { root.recycle() }
    }

    @Test
    fun `splitNodesIntoWordNodes breaks multi-word and multi-line paragraphs into word Rects in reading order`() {
        val rawNodes = listOf(
            SmartDotAccessibilityService.ScreenTextNode(
                id = 0,
                text = "Tap Me Too",
                boundsInScreen = Rect(60, 120, 420, 180)
            ),
            SmartDotAccessibilityService.ScreenTextNode(
                id = 1,
                text = "Alpha Beta Gamma Delta\nEpsilon Zeta",
                boundsInScreen = Rect(40, 220, 440, 360)
            ),
            SmartDotAccessibilityService.ScreenTextNode(
                id = 2,
                text = "नमस्ते भारत",
                boundsInScreen = Rect(60, 400, 400, 460)
            )
        )

        val words = SmartDotAccessibilityService.splitNodesIntoWordNodes(rawNodes, density = 1f)
        assertEquals(
            listOf("Tap", "Me", "Too", "Alpha", "Beta", "Gamma", "Delta", "Epsilon", "Zeta", "नमस्ते", "भारत"),
            words.map { it.text }
        )

        assertTrue(words[0].boundsInScreen.left >= 60)
        assertTrue(words[0].boundsInScreen.right <= words[1].boundsInScreen.left)
        assertTrue(words[1].boundsInScreen.right <= words[2].boundsInScreen.left)
        assertTrue(words[2].boundsInScreen.right <= 420)

        val alphaRect = words[3].boundsInScreen
        val epsilonRect = words[7].boundsInScreen
        assertTrue(epsilonRect.top >= alphaRect.bottom - 2)
    }

    @Test
    fun `ScreenOcrHelper merges Latin and Devanagari scripts, scales coordinates, and extracts word and line nodes`() {
        val latinItems = listOf(
            ScreenOcrHelper.OcrRawItem("Photo Banner", Rect(50, 100, 350, 140), isLine = true),
            ScreenOcrHelper.OcrRawItem("Photo", Rect(50, 100, 160, 140), isLine = false),
            ScreenOcrHelper.OcrRawItem("Banner", Rect(180, 100, 350, 140), isLine = false),
            // Garbled Latin echo overlapping Devanagari box should be suppressed in favor of Devanagari script
            ScreenOcrHelper.OcrRawItem("garbled", Rect(55, 200, 245, 250), isLine = true),
            ScreenOcrHelper.OcrRawItem("grbl", Rect(55, 200, 245, 250), isLine = false)
        )
        val devanagariItems = listOf(
            ScreenOcrHelper.OcrRawItem("नमस्ते भारत", Rect(50, 200, 250, 250), isLine = true),
            ScreenOcrHelper.OcrRawItem("नमस्ते", Rect(50, 200, 145, 250), isLine = false),
            ScreenOcrHelper.OcrRawItem("भारत", Rect(155, 200, 250, 250), isLine = false)
        )

        val result = ScreenOcrHelper.mergeScaleAndBuildNodes(
            latinItems = latinItems,
            devanagariItems = devanagariItems,
            scaleX = 2f,
            scaleY = 2f,
            maxWidth = 1080,
            maxHeight = 2400
        )

        assertEquals(listOf("Photo", "Banner", "नमस्ते", "भारत"), result.wordNodes.map { it.text })
        assertEquals(Rect(100, 200, 320, 280), result.wordNodes[0].boundsInScreen)
        assertEquals(listOf("Photo Banner", "नमस्ते भारत"), result.lineNodes.map { it.text })
    }

    @Test
    fun `InlineTextSelectionView uses thin 6dp highlighter stroke, snug 2dp highlight rect, and does not spill to adjacent rows`() {
        val wm = appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val controller = InlineTextOverlayController(appContext, wm)
        val density = appContext.resources.displayMetrics.density

        // Two vertically close rows (row 1: bottom=240, row 2: top=256 — 16px apart)
        val ocrWords = listOf(
            SmartDotAccessibilityService.ScreenTextNode(0, "First", Rect(80, 190, 220, 240)),
            SmartDotAccessibilityService.ScreenTextNode(1, "Row", Rect(240, 190, 360, 240)),
            SmartDotAccessibilityService.ScreenTextNode(2, "Adjacent", Rect(80, 256, 230, 306)),
            SmartDotAccessibilityService.ScreenTextNode(3, "Below", Rect(250, 256, 370, 306))
        )

        controller.showCopyOverlay(
            nodes = ocrWords,
            accentColor = Color.parseColor("#00E5FF"),
            onCopyClicked = {},
            onRescanRequested = {},
            onDismiss = {}
        )

        val root = getOverlayRoot(controller)
        root.measure(
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(2400, View.MeasureSpec.EXACTLY)
        )
        root.layout(0, 0, 1080, 2400)

        val selView = root.findViewById<InlineTextSelectionView>(R.id.inline_ocr_selection_view)
        assertEquals(6f * density, selView.getHighlighterStrokeWidthPx(), 0.01f)

        // Verify snug highlight box stays strictly inside original Element bounding box with 2dp inset
        val firstWordRect = selView.getMappedRectForCopyNode(0)!!
        val snugRect = selView.getHighlightDrawRect(0)!!
        assertEquals(firstWordRect.left + 2f * density, snugRect.left, 0.01f)
        assertEquals(firstWordRect.top + 2f * density, snugRect.top, 0.01f)
        assertEquals(firstWordRect.right - 2f * density, snugRect.right, 0.01f)
        assertEquals(firstWordRect.bottom - 2f * density, snugRect.bottom, 0.01f)

        // Drag straight along row 1 only; tight 1.5dp collision must NOT grab row 2 below
        val rowRect = selView.getMappedRectForCopyNode(1)!!
        val down = MotionEvent.obtain(100L, 100L, MotionEvent.ACTION_DOWN, firstWordRect.centerX(), firstWordRect.centerY(), 0)
        val move = MotionEvent.obtain(100L, 120L, MotionEvent.ACTION_MOVE, rowRect.centerX(), rowRect.centerY(), 0)
        val up = MotionEvent.obtain(100L, 140L, MotionEvent.ACTION_UP, rowRect.centerX(), rowRect.centerY(), 0)
        selView.dispatchTouchEvent(down)
        selView.dispatchTouchEvent(move)
        selView.dispatchTouchEvent(up)
        down.recycle()
        move.recycle()
        up.recycle()

        assertEquals(listOf("First", "Row"), selView.getSelectedCopyNodes().map { it.text })
        assertEquals("First Row", selView.getCombinedSelectedCopyText())

        controller.dismiss()
    }

    @Test
    fun `InlineTextOverlayController in COPY mode supports single-word tap without borders and dismisses on Copy`() {
        val wm = appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val controller = InlineTextOverlayController(appContext, wm)

        val wordNodes = SmartDotAccessibilityService.splitNodesIntoWordNodes(
            listOf(
                SmartDotAccessibilityService.ScreenTextNode(0, "Order #4921", Rect(80, 200, 420, 270)),
                SmartDotAccessibilityService.ScreenTextNode(1, "Total ₹850", Rect(80, 360, 380, 430))
            ),
            density = appContext.resources.displayMetrics.density
        )

        var copiedPayload: String? = null
        var dismissed = false

        controller.showCopyOverlay(
            nodes = wordNodes,
            accentColor = Color.parseColor("#00E5FF"),
            onCopyClicked = { copiedPayload = it },
            onRescanRequested = {},
            onDismiss = { dismissed = true }
        )

        assertTrue(controller.isShowing())
        val root = getOverlayRoot(controller)
        assertEquals(Color.TRANSPARENT, (root.background as? ColorDrawable)?.color)

        val selView = root.findViewById<InlineTextSelectionView>(R.id.inline_ocr_selection_view)
        assertEquals(Color.TRANSPARENT, (selView.background as? ColorDrawable)?.color)
        assertEquals(View.GONE, root.findViewById<TextView>(R.id.btn_inline_target_lang).visibility)
        assertEquals(View.GONE, root.findViewById<TextView>(R.id.tv_inline_status_pill).visibility)

        val copyBtn = root.findViewById<LinearLayout>(R.id.btn_inline_ocr_copy)
        assertEquals(View.GONE, copyBtn.visibility)
        assertTrue(selView.getSelectedCopyNodes().isEmpty())

        root.measure(
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(2400, View.MeasureSpec.EXACTLY)
        )
        root.layout(0, 0, 1080, 2400)

        val targetRect = selView.getMappedRectForCopyNode(3)!!
        tapAt(selView, targetRect.centerX(), targetRect.centerY())

        assertEquals(listOf("₹850"), selView.getSelectedCopyNodes().map { it.text })
        assertEquals("₹850", selView.getCombinedSelectedCopyText())
        assertEquals(View.VISIBLE, copyBtn.visibility)

        copyBtn.performClick()
        assertEquals("₹850", copiedPayload)
        assertFalse(controller.isShowing())
        assertTrue(dismissed)

        ShadowLooper.idleMainLooper()
    }

    @Test
    fun `InlineTextOverlayController supports highlighter pen finger-drag across specific words in reading order`() {
        val wm = appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val controller = InlineTextOverlayController(appContext, wm)

        val wordNodes = SmartDotAccessibilityService.splitNodesIntoWordNodes(
            listOf(
                SmartDotAccessibilityService.ScreenTextNode(0, "Save important words here", Rect(80, 220, 780, 280))
            ),
            density = appContext.resources.displayMetrics.density
        )
        assertEquals(listOf("Save", "important", "words", "here"), wordNodes.map { it.text })

        var copiedPayload: String? = null
        controller.showCopyOverlay(
            nodes = wordNodes,
            accentColor = Color.parseColor("#00E5FF"),
            onCopyClicked = { copiedPayload = it },
            onRescanRequested = {},
            onDismiss = {}
        )

        val root = getOverlayRoot(controller)
        root.measure(
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(2400, View.MeasureSpec.EXACTLY)
        )
        root.layout(0, 0, 1080, 2400)

        val selView = root.findViewById<InlineTextSelectionView>(R.id.inline_ocr_selection_view)
        val copyBtn = root.findViewById<LinearLayout>(R.id.btn_inline_ocr_copy)

        val importantRect = selView.getMappedRectForCopyNode(1)!!
        val wordsRect = selView.getMappedRectForCopyNode(2)!!

        val down = MotionEvent.obtain(100L, 100L, MotionEvent.ACTION_DOWN, importantRect.centerX(), importantRect.centerY(), 0)
        selView.dispatchTouchEvent(down)
        down.recycle()

        val moveMid = MotionEvent.obtain(100L, 120L, MotionEvent.ACTION_MOVE, (importantRect.right + wordsRect.left) * 0.5f, importantRect.centerY(), 0)
        selView.dispatchTouchEvent(moveMid)
        moveMid.recycle()

        val moveEnd = MotionEvent.obtain(100L, 140L, MotionEvent.ACTION_MOVE, wordsRect.centerX(), wordsRect.centerY(), 0)
        selView.dispatchTouchEvent(moveEnd)
        moveEnd.recycle()

        assertTrue(selView.isHighlighterTrailActive())
        assertEquals(View.GONE, copyBtn.visibility)
        assertEquals(listOf("important", "words"), selView.getSelectedCopyNodes().map { it.text })

        val up = MotionEvent.obtain(100L, 160L, MotionEvent.ACTION_UP, wordsRect.centerX(), wordsRect.centerY(), 0)
        selView.dispatchTouchEvent(up)
        up.recycle()

        assertFalse(selView.isHighlighterTrailActive())
        assertEquals("important words", selView.getCombinedSelectedCopyText())
        assertEquals(View.VISIBLE, copyBtn.visibility)

        copyBtn.performClick()
        assertEquals("important words", copiedPayload)
        assertFalse(controller.isShowing())
    }

    @Test
    fun `InlineTextOverlayController dismisses cleanly on Close button and Back key`() {
        val wm = appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val controller = InlineTextOverlayController(appContext, wm)
        val nodes = listOf(SmartDotAccessibilityService.ScreenTextNode(0, "Hello", Rect(100, 100, 300, 160)))

        var dismissCount = 0
        controller.showCopyOverlay(
            nodes = nodes,
            accentColor = Color.CYAN,
            onCopyClicked = {},
            onRescanRequested = {},
            onDismiss = { dismissCount++ }
        )

        getOverlayRoot(controller).findViewById<LinearLayout>(R.id.btn_inline_ocr_close).performClick()
        assertFalse(controller.isShowing())
        assertEquals(1, dismissCount)

        controller.showCopyOverlay(
            nodes = nodes,
            accentColor = Color.CYAN,
            onCopyClicked = {},
            onRescanRequested = {},
            onDismiss = { dismissCount++ }
        )
        getOverlayRoot(controller).dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK))
        assertFalse(controller.isShowing())
        assertEquals(2, dismissCount)
    }

    @Test
    fun `ScreenTextCopyController hides overlays for 50ms, runs in-memory ML Kit OCR for exact photo words, and recycles Bitmap`() {
        val scope = CoroutineScope(Dispatchers.Main + Job())
        val clipboard = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.clearPrimaryClip()

        var overlaysVisible = true
        var popupDismissed = false
        val inMemoryBitmap = Bitmap.createBitmap(1080, 2400, Bitmap.Config.ARGB_8888)

        val ocrHelper = ScreenOcrHelper(
            customOcrRunner = {
                val latin = listOf(
                    ScreenOcrHelper.OcrRawItem("Sale 50%", Rect(120, 300, 420, 360), isLine = true),
                    ScreenOcrHelper.OcrRawItem("Sale", Rect(120, 300, 240, 360), isLine = false),
                    ScreenOcrHelper.OcrRawItem("50%", Rect(260, 300, 420, 360), isLine = false)
                )
                val dev = listOf(
                    ScreenOcrHelper.OcrRawItem("छूट", Rect(440, 300, 560, 360), isLine = true),
                    ScreenOcrHelper.OcrRawItem("छूट", Rect(440, 300, 560, 360), isLine = false)
                )
                latin to dev
            }
        )

        val controller = ScreenTextCopyController(
            context = appContext,
            serviceScope = scope,
            clipboardManager = clipboard,
            getAccentColor = { Color.parseColor("#00E5FF") },
            dismissActivePopup = { popupDismissed = true },
            setHostOverlaysVisible = { overlaysVisible = it },
            onAccessibilityRequired = {},
            nodeExtractor = { emptyList() },
            ocrHelperFactory = { ocrHelper },
            screenBitmapCapturer = { cb -> cb(inMemoryBitmap) }
        )

        controller.start()
        assertTrue(popupDismissed)
        assertFalse(overlaysVisible)
        assertFalse("Capture must wait for 50ms hide delay before scanning", controller.isOverlayShowing())

        ShadowLooper.idleMainLooper(ScreenTextCopyController.CAPTURE_HIDE_DELAY_MS + 10L, TimeUnit.MILLISECONDS)
        val deadline = System.currentTimeMillis() + 3000L
        while (!controller.isOverlayShowing() && System.currentTimeMillis() < deadline) {
            ShadowLooper.idleMainLooper(25, TimeUnit.MILLISECONDS)
            Thread.sleep(15)
        }

        assertTrue(controller.isOverlayShowing())
        assertTrue("In-memory Bitmap must be recycled immediately after OCR", inMemoryBitmap.isRecycled)

        val overlayField = ScreenTextCopyController::class.java
            .getDeclaredField("overlayController")
            .apply { isAccessible = true }
        val overlayCtrl = overlayField.get(controller) as InlineTextOverlayController
        val root = getOverlayRoot(overlayCtrl)
        root.measure(
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(2400, View.MeasureSpec.EXACTLY)
        )
        root.layout(0, 0, 1080, 2400)

        val selView = root.findViewById<InlineTextSelectionView>(R.id.inline_ocr_selection_view)
        assertEquals(listOf("Sale", "50%", "छूट"), selView.getMappedCopyNodesSnapshot().map { it.node.text })

        val targetRect = selView.getMappedRectForCopyNode(1)!!
        tapAt(selView, targetRect.centerX(), targetRect.centerY())
        root.findViewById<LinearLayout>(R.id.btn_inline_ocr_copy).performClick()
        ShadowLooper.idleMainLooper()

        assertEquals("50%", clipboard.primaryClip?.getItemAt(0)?.text?.toString())
        assertTrue(overlaysVisible)

        controller.release()
        scope.cancel()
    }

    @Test
    fun `ScreenTextCopyController falls back to accessibility word splitting when screen capture is null`() {
        val scope = CoroutineScope(Dispatchers.Main + Job())
        val clipboard = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.clearPrimaryClip()

        var overlaysVisible = true
        var accessibilityPrompted = false
        val extractedNodes = listOf(
            SmartDotAccessibilityService.ScreenTextNode(0, "Tap Me Too", Rect(60, 120, 420, 180)),
            SmartDotAccessibilityService.ScreenTextNode(1, "नमस्ते भारत", Rect(60, 260, 420, 330))
        )

        val controller = ScreenTextCopyController(
            context = appContext,
            serviceScope = scope,
            clipboardManager = clipboard,
            getAccentColor = { Color.parseColor("#00E5FF") },
            dismissActivePopup = {},
            setHostOverlaysVisible = { overlaysVisible = it },
            onAccessibilityRequired = { accessibilityPrompted = true },
            nodeExtractor = { extractedNodes },
            screenBitmapCapturer = { cb -> cb(null) }
        )

        controller.start()
        ShadowLooper.idleMainLooper(80, TimeUnit.MILLISECONDS)

        assertFalse(accessibilityPrompted)
        assertTrue(controller.isOverlayShowing())
        assertFalse(overlaysVisible)

        val overlayField = ScreenTextCopyController::class.java
            .getDeclaredField("overlayController")
            .apply { isAccessible = true }
        val overlayCtrl = overlayField.get(controller) as InlineTextOverlayController
        val root = getOverlayRoot(overlayCtrl)
        root.measure(
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(2400, View.MeasureSpec.EXACTLY)
        )
        root.layout(0, 0, 1080, 2400)

        val selView = root.findViewById<InlineTextSelectionView>(R.id.inline_ocr_selection_view)
        assertEquals(5, selView.getMappedCopyNodesSnapshot().size)

        val tooRect = selView.getMappedRectForCopyNode(2)!!
        val namasteRect = selView.getMappedRectForCopyNode(3)!!

        val down = MotionEvent.obtain(100L, 100L, MotionEvent.ACTION_DOWN, tooRect.centerX(), tooRect.centerY(), 0)
        val move = MotionEvent.obtain(100L, 130L, MotionEvent.ACTION_MOVE, namasteRect.centerX(), namasteRect.centerY(), 0)
        val up = MotionEvent.obtain(100L, 160L, MotionEvent.ACTION_UP, namasteRect.centerX(), namasteRect.centerY(), 0)
        selView.dispatchTouchEvent(down)
        selView.dispatchTouchEvent(move)
        selView.dispatchTouchEvent(up)
        down.recycle()
        move.recycle()
        up.recycle()

        assertEquals("Too नमस्ते", selView.getCombinedSelectedCopyText())

        root.findViewById<LinearLayout>(R.id.btn_inline_ocr_copy).performClick()
        ShadowLooper.idleMainLooper()

        assertEquals("Too नमस्ते", clipboard.primaryClip?.getItemAt(0)?.text?.toString())
        assertEquals(appContext.getString(R.string.screen_copy_copied), ShadowToast.getTextOfLatestToast())
        assertFalse(controller.isOverlayShowing())
        assertTrue(overlaysVisible)

        controller.release()
        scope.cancel()
    }

    @Test
    fun `ScreenTextCopyController triggers accessibility prompt when AccessibilityService returns null`() {
        val scope = CoroutineScope(Dispatchers.Main + Job())
        val clipboard = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        var accessibilityPrompted = false

        val controller = ScreenTextCopyController(
            context = appContext,
            serviceScope = scope,
            clipboardManager = clipboard,
            getAccentColor = { Color.CYAN },
            dismissActivePopup = {},
            setHostOverlaysVisible = {},
            onAccessibilityRequired = { accessibilityPrompted = true },
            nodeExtractor = { null },
            screenBitmapCapturer = { cb -> cb(null) }
        )

        controller.scanAndShowOverlay()
        assertTrue(accessibilityPrompted)
        assertFalse(controller.isOverlayShowing())

        controller.release()
        scope.cancel()
    }

    private fun tapAt(view: View, x: Float, y: Float) {
        val down = MotionEvent.obtain(100L, 100L, MotionEvent.ACTION_DOWN, x, y, 0)
        val up = MotionEvent.obtain(100L, 130L, MotionEvent.ACTION_UP, x, y, 0)
        view.dispatchTouchEvent(down)
        view.dispatchTouchEvent(up)
        down.recycle()
        up.recycle()
    }

    private fun getOverlayRoot(controller: InlineTextOverlayController): View {
        val field = InlineTextOverlayController::class.java
            .getDeclaredField("overlayView")
            .apply { isAccessible = true }
        return field.get(controller) as View
    }
}
