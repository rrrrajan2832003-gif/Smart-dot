package com.example

import android.app.Application
import android.content.ClipboardManager
import android.content.Context
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
import org.junit.Assert.assertNull
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

        // Single-line words sit on the same row strictly left-to-right inside [60, 420]
        assertTrue(words[0].boundsInScreen.left >= 60)
        assertTrue(words[0].boundsInScreen.right <= words[1].boundsInScreen.left)
        assertTrue(words[1].boundsInScreen.right <= words[2].boundsInScreen.left)
        assertTrue(words[2].boundsInScreen.right <= 420)

        // Multi-line paragraph places line 2 ("Epsilon") cleanly below line 1 ("Alpha")
        val alphaRect = words[3].boundsInScreen
        val epsilonRect = words[7].boundsInScreen
        assertTrue(epsilonRect.top >= alphaRect.bottom - 2)
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

        // Tap ONLY the single word "₹850" (id = 3)
        val targetRect = selView.getMappedRectForCopyNode(3)!!
        tapAt(selView, targetRect.centerX(), targetRect.centerY())

        assertEquals(listOf("₹850"), selView.getSelectedCopyNodes().map { it.text })
        assertEquals("₹850", selView.getCombinedSelectedCopyText())
        assertEquals(View.VISIBLE, copyBtn.visibility)

        // Tap "Copy" pill -> copies ONLY that tapped word and dismisses overlay
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

        // Drag highlighter finger smoothly over "important" and "words" (skipping "Save" and "here")
        val down = MotionEvent.obtain(100L, 100L, MotionEvent.ACTION_DOWN, importantRect.centerX(), importantRect.centerY(), 0)
        selView.dispatchTouchEvent(down)
        down.recycle()

        val moveMid = MotionEvent.obtain(100L, 120L, MotionEvent.ACTION_MOVE, (importantRect.right + wordsRect.left) * 0.5f, importantRect.centerY(), 0)
        selView.dispatchTouchEvent(moveMid)
        moveMid.recycle()

        val moveEnd = MotionEvent.obtain(100L, 140L, MotionEvent.ACTION_MOVE, wordsRect.centerX(), wordsRect.centerY(), 0)
        selView.dispatchTouchEvent(moveEnd)
        moveEnd.recycle()

        // While dragging: highlighter trail is active, Copy pill stays hidden, intersected words highlight dynamically
        assertTrue(selView.isHighlighterTrailActive())
        assertEquals(View.GONE, copyBtn.visibility)
        assertEquals(listOf("important", "words"), selView.getSelectedCopyNodes().map { it.text })

        val up = MotionEvent.obtain(100L, 160L, MotionEvent.ACTION_UP, wordsRect.centerX(), wordsRect.centerY(), 0)
        selView.dispatchTouchEvent(up)
        up.recycle()

        // On ACTION_UP: trail resets cleanly, words stay highlighted, Copy pill appears
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
    fun `ScreenTextCopyController splits paragraphs to words, highlights across rows on drag, copies via ClipboardManager, and closes`() {
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
            nodeExtractor = { extractedNodes }
        )

        controller.start()
        ShadowLooper.idleMainLooper(180, TimeUnit.MILLISECONDS)

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
        // Words: 0="Tap", 1="Me", 2="Too", 3="नमस्ते", 4="भारत"
        assertEquals(5, selView.getMappedCopyNodesSnapshot().size)

        val tooRect = selView.getMappedRectForCopyNode(2)!!
        val namasteRect = selView.getMappedRectForCopyNode(3)!!

        // Drag diagonally from "Too" on row 1 to "नमस्ते" on row 2
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
            nodeExtractor = { null }
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
