package com.example

import android.app.Application
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.google.android.gms.common.Feature
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.interfaces.Detector
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognizer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.tasks.await
import org.junit.After
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
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ScreenTextCopyTest {

    private lateinit var appContext: Application

    @Before
    fun setUp() {
        appContext = ApplicationProvider.getApplicationContext()
        ScreenCaptureService.isSessionActive = false
        ScreenCaptureHolder.clear()
    }

    @After
    fun tearDown() {
        ScreenCaptureService.isSessionActive = false
        ScreenCaptureHolder.clear()
    }

    @Test
    fun `copyRgbaPlaneToBitmap handles GPU rowPadding without Buffer not large enough crash`() {
        val width = 4
        val height = 3
        val pixelStride = 4
        val rowPaddingBytes = 16
        val rowStride = width * pixelStride + rowPaddingBytes

        val bufferSize = rowStride * (height - 1) + width * pixelStride
        val buffer = ByteBuffer.allocateDirect(bufferSize)

        for (y in 0 until height) {
            buffer.position(y * rowStride)
            for (x in 0 until width) {
                val r = if (y == 0) 0xFF else 0x10
                val g = if (y == 1) 0xFF else 0x20
                val b = if (y == 2) 0xFF else 0x30
                buffer.put(r.toByte())
                buffer.put(g.toByte())
                buffer.put(b.toByte())
                buffer.put(0xFF.toByte())
            }
        }
        buffer.rewind()

        val refBuffer = ByteBuffer.allocateDirect(width * height * pixelStride)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val r = if (y == 0) 0xFF else 0x10
                val g = if (y == 1) 0xFF else 0x20
                val b = if (y == 2) 0xFF else 0x30
                refBuffer.put(r.toByte())
                refBuffer.put(g.toByte())
                refBuffer.put(b.toByte())
                refBuffer.put(0xFF.toByte())
            }
        }
        refBuffer.rewind()
        val expectedBitmap = ScreenCaptureService.copyRgbaPlaneToBitmap(
            buffer = refBuffer,
            width = width,
            height = height,
            pixelStride = pixelStride,
            rowStride = width * pixelStride
        )

        val bitmap = ScreenCaptureService.copyRgbaPlaneToBitmap(
            buffer = buffer,
            width = width,
            height = height,
            pixelStride = pixelStride,
            rowStride = rowStride
        )

        assertEquals(width, bitmap.width)
        assertEquals(height, bitmap.height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                assertEquals("Pixel mismatch at ($x,$y)", expectedBitmap.getPixel(x, y), bitmap.getPixel(x, y))
            }
        }
        expectedBitmap.recycle()
        bitmap.recycle()
    }

    @Test
    fun `mergeRawBlocks extracts English and Hindi Devanagari in reading order and deduplicates overlaps`() {
        val helper = ScreenOcrHelper()
        val devanagariBlocks = listOf(
            ScreenOcrHelper.RawOcrBlock("नमस्ते दुनिया", Rect(40, 120, 420, 190)),
            ScreenOcrHelper.RawOcrBlock("Hello world हिंदी", Rect(40, 240, 500, 310))
        )
        val latinBlocks = listOf(
            ScreenOcrHelper.RawOcrBlock("English Header", Rect(40, 20, 380, 80)),
            ScreenOcrHelper.RawOcrBlock("Garbled Latin", Rect(45, 125, 410, 185))
        )

        val merged = helper.mergeRawBlocks(latinBlocks, devanagariBlocks)
        assertEquals(3, merged.size)
        assertEquals("English Header", merged[0].text)
        assertEquals("EN", merged[0].scriptLabel)
        assertEquals("नमस्ते दुनिया", merged[1].text)
        assertEquals("HI", merged[1].scriptLabel)
        assertEquals("Hello world हिंदी", merged[2].text)
        assertEquals("HI+EN", merged[2].scriptLabel)
    }

    @Test
    fun `mapCaptureRectToOverlayLocal accurately maps OCR coordinates across screen density and status bar offset`() {
        // Suppose capture buffer is 1080x2400, display bounds are 1440x3200, and overlay starts below 96px status bar.
        val mapped = ScreenOcrHelper.mapCaptureRectToOverlayLocal(
            captureRect = Rect(90, 300, 450, 360),
            captureWidth = 1080,
            captureHeight = 2400,
            screenWidth = 1440,
            screenHeight = 3200,
            overlayScreenOffsetX = 0,
            overlayScreenOffsetY = 96
        )

        val scale = 1440f / 1080f
        assertEquals(90f * scale, mapped.left, 0.5f)
        assertEquals(300f * scale - 96f, mapped.top, 0.5f)
        assertEquals(450f * scale, mapped.right, 0.5f)
        assertEquals(360f * scale - 96f, mapped.bottom, 0.5f)
    }

    @Test
    fun `InlineTextOverlayController keeps transparent screen, selects tapped block, shows floating Copy, and copies only selected text`() {
        val wm = appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val controller = InlineTextOverlayController(appContext, wm)
        val blocks = listOf(
            ScreenOcrHelper.OcrTextBlock(0, "Invoice #1024", Rect(100, 180, 420, 230), "EN"),
            ScreenOcrHelper.OcrTextBlock(1, "कुल राशि ₹500", Rect(100, 320, 460, 380), "HI"),
            ScreenOcrHelper.OcrTextBlock(2, "Thank you", Rect(100, 480, 340, 530), "EN")
        )

        var copiedText: String? = null
        var copiedCount = 0
        var dismissed = false

        controller.show(
            blocks = blocks,
            captureWidth = 1080,
            captureHeight = 2400,
            displayRotation = Surface.ROTATION_0,
            accentColor = Color.CYAN,
            onCopyText = { text, count ->
                copiedText = text
                copiedCount = count
            },
            onRescanRequested = {},
            onDismiss = { dismissed = true }
        )

        assertTrue(controller.isShowing())
        val overlayRoot = getOverlayRoot(controller)

        // Overlay root must stay completely transparent with NO screenshot image preview.
        val bg = overlayRoot.background as? ColorDrawable
        assertEquals(Color.TRANSPARENT, bg?.color)
        val selectionView = overlayRoot.findViewById<InlineTextSelectionView>(R.id.inline_ocr_selection_view)
        val copyPill = overlayRoot.findViewById<LinearLayout>(R.id.btn_inline_ocr_copy)
        val copyLabel = overlayRoot.findViewById<TextView>(R.id.tv_inline_ocr_copy_label)
        assertEquals(View.GONE, copyPill.visibility)

        // Lay out selection view at full capture bounds
        overlayRoot.measure(
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(2400, View.MeasureSpec.EXACTLY)
        )
        overlayRoot.layout(0, 0, 1080, 2400)

        // Tap directly inside the second (Hindi) block
        val targetRect = selectionView.getMappedRectForBlock(1)!!
        dispatchTap(selectionView, targetRect.centerX(), targetRect.centerY())

        assertEquals(setOf(1), selectionView.getSelectedIds())
        assertEquals(View.VISIBLE, copyPill.visibility)
        assertEquals("Copy", copyLabel.text.toString())

        // Tapping Copy must copy ONLY the tapped text block and dismiss the overlay.
        copyPill.performClick()
        assertEquals("कुल राशि ₹500", copiedText)
        assertEquals(1, copiedCount)
        assertTrue(dismissed)
        assertFalse(controller.isShowing())
    }

    @Test
    fun `InlineTextOverlayController supports multi-block selection, toggle deselect, and reading-order copy`() {
        val wm = appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val controller = InlineTextOverlayController(appContext, wm)
        val blocks = listOf(
            ScreenOcrHelper.OcrTextBlock(0, "Invoice #1024", Rect(100, 180, 420, 230), "EN"),
            ScreenOcrHelper.OcrTextBlock(1, "कुल राशि ₹500", Rect(100, 320, 460, 380), "HI"),
            ScreenOcrHelper.OcrTextBlock(2, "Paid via UPI", Rect(100, 480, 380, 530), "EN")
        )

        var copiedText: String? = null
        var copiedCount = 0

        controller.show(
            blocks = blocks,
            captureWidth = 1080,
            captureHeight = 2400,
            displayRotation = Surface.ROTATION_0,
            accentColor = Color.CYAN,
            onCopyText = { text, count ->
                copiedText = text
                copiedCount = count
            },
            onRescanRequested = {},
            onDismiss = {}
        )

        val overlayRoot = getOverlayRoot(controller)
        val selectionView = overlayRoot.findViewById<InlineTextSelectionView>(R.id.inline_ocr_selection_view)
        val copyPill = overlayRoot.findViewById<LinearLayout>(R.id.btn_inline_ocr_copy)
        val copyLabel = overlayRoot.findViewById<TextView>(R.id.tv_inline_ocr_copy_label)

        overlayRoot.measure(
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(2400, View.MeasureSpec.EXACTLY)
        )
        overlayRoot.layout(0, 0, 1080, 2400)

        // Tap block 2 then block 0 -> multi-select!
        val r2 = selectionView.getMappedRectForBlock(2)!!
        val r0 = selectionView.getMappedRectForBlock(0)!!
        dispatchTap(selectionView, r2.centerX(), r2.centerY())
        dispatchTap(selectionView, r0.centerX(), r0.centerY())

        assertEquals(setOf(2, 0), selectionView.getSelectedIds())
        assertEquals("Copy (2)", copyLabel.text.toString())

        // Tap block 1 as well, then tap block 2 again to deselect it -> leaves {0, 1}
        val r1 = selectionView.getMappedRectForBlock(1)!!
        dispatchTap(selectionView, r1.centerX(), r1.centerY())
        assertEquals("Copy (3)", copyLabel.text.toString())
        dispatchTap(selectionView, r2.centerX(), r2.centerY())
        assertEquals(setOf(0, 1), selectionView.getSelectedIds())
        assertEquals("Copy (2)", copyLabel.text.toString())

        copyPill.performClick()
        assertEquals("Invoice #1024\nकुल राशि ₹500", copiedText)
        assertEquals(2, copiedCount)
        assertFalse(controller.isShowing())
    }

    @Test
    fun `InlineTextOverlayController Close button, Back key, and orientation resize dismiss overlay cleanly`() {
        val wm = appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val controller = InlineTextOverlayController(appContext, wm)
        val blocks = listOf(
            ScreenOcrHelper.OcrTextBlock(0, "Sample", Rect(80, 120, 260, 170), "EN")
        )

        var dismissCalls = 0
        controller.show(
            blocks = blocks,
            captureWidth = 1080,
            captureHeight = 2400,
            displayRotation = Surface.ROTATION_0,
            accentColor = Color.CYAN,
            onCopyText = { _, _ -> },
            onRescanRequested = {},
            onDismiss = { dismissCalls++ }
        )

        // 1. Close button dismisses overlay
        val root1 = getOverlayRoot(controller)
        root1.findViewById<LinearLayout>(R.id.btn_inline_ocr_close).performClick()
        assertFalse(controller.isShowing())
        assertEquals(1, dismissCalls)

        // 2. Back key dismisses overlay
        controller.show(
            blocks = blocks,
            captureWidth = 1080,
            captureHeight = 2400,
            displayRotation = Surface.ROTATION_0,
            accentColor = Color.CYAN,
            onCopyText = { _, _ -> },
            onRescanRequested = {},
            onDismiss = { dismissCalls++ }
        )
        val root2 = getOverlayRoot(controller)
        root2.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK))
        assertFalse(controller.isShowing())
        assertEquals(2, dismissCalls)

        // 3. Screen rotation / dimension swap auto-dismisses stale overlay
        controller.show(
            blocks = blocks,
            captureWidth = 1080,
            captureHeight = 2400,
            displayRotation = Surface.ROTATION_0,
            accentColor = Color.CYAN,
            onCopyText = { _, _ -> },
            onRescanRequested = {},
            onDismiss = { dismissCalls++ }
        )
        val root3 = getOverlayRoot(controller)
        root3.measure(
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(2400, View.MeasureSpec.EXACTLY)
        )
        root3.layout(0, 0, 1080, 2400)
        root3.measure(
            View.MeasureSpec.makeMeasureSpec(2400, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY)
        )
        root3.layout(0, 0, 2400, 1080)
        assertFalse(controller.isShowing())
        assertEquals(3, dismissCalls)
        ShadowLooper.idleMainLooper()
    }

    @Test
    fun `ScreenTextCopyController end-to-end recycles bitmap, copies selected block to ClipboardManager, and rescans without consent`() {
        val scope = CoroutineScope(Dispatchers.Main + Job())
        val clipboard = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

        val devanagariText = Text(
            "कुल राशि ₹500",
            listOf(
                Text.TextBlock(
                    "कुल राशि ₹500",
                    Rect(100, 320, 480, 380),
                    emptyList<android.graphics.Point>(),
                    "hi",
                    Matrix(),
                    emptyList<Text.Line>()
                )
            )
        )
        val latinText = Text(
            "Receipt #42",
            listOf(
                Text.TextBlock(
                    "Receipt #42",
                    Rect(100, 160, 380, 210),
                    emptyList<android.graphics.Point>(),
                    "en",
                    Matrix(),
                    emptyList<Text.Line>()
                )
            )
        )

        val helper = ScreenOcrHelper(
            latinClientFactory = { fakeRecognizer(latinText) },
            devanagariClientFactory = { fakeRecognizer(devanagariText) },
            recognizerRunner = { recognizer, bmp -> recognizer.process(bmp, 0).await() }
        )

        var overlaysVisible = true
        val controller = ScreenTextCopyController(
            context = appContext,
            serviceScope = scope,
            clipboardManager = clipboard,
            getAccentColor = { Color.CYAN },
            dismissActivePopup = {},
            setHostOverlaysVisible = { overlaysVisible = it },
            ocrHelperFactory = { helper }
        )

        val shadowApp = Shadows.shadowOf(appContext)
        while (shadowApp.nextStartedActivity != null) { /* drain */ }
        shadowApp.clearStartedServices()

        ScreenCaptureService.isSessionActive = true
        controller.start()
        ShadowLooper.idleMainLooper(200, TimeUnit.MILLISECONDS)
        assertNull("Reusing active session must not show consent activity", shadowApp.nextStartedActivity)
        assertFalse(overlaysVisible)

        // Deliver a real ARGB_8888 frame through ScreenCaptureHolder + ACTION_CAPTURE_READY
        val capturedBitmap = Bitmap.createBitmap(1080, 2400, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.WHITE)
        }
        ScreenCaptureHolder.setFrame(
            bitmap = capturedBitmap,
            isAllBlackOrProtected = false,
            captureWidth = 1080,
            captureHeight = 2400,
            displayRotation = Surface.ROTATION_0,
            densityDpi = 420
        )
        appContext.sendBroadcast(Intent(ScreenCaptureService.ACTION_CAPTURE_READY).apply {
            setPackage(appContext.packageName)
        })

        // Wait briefly for Dispatchers.Default OCR coroutine + Main dispatcher overlay presentation
        val deadline = System.currentTimeMillis() + 3000L
        while (!controller.isOverlayShowing() && System.currentTimeMillis() < deadline) {
            ShadowLooper.idleMainLooper(25, TimeUnit.MILLISECONDS)
            Thread.sleep(15)
        }

        assertTrue("Inline overlay should be visible after OCR completes", controller.isOverlayShowing())
        assertTrue("Captured bitmap must be recycled immediately after OCR (no screenshot retained)", capturedBitmap.isRecycled)

        // Inspect inline overlay, test Rescan button reuses existing session without consent
        val overlayCtrlField = ScreenTextCopyController::class.java
            .getDeclaredField("overlayController")
            .apply { isAccessible = true }
        val overlayController = overlayCtrlField.get(controller) as InlineTextOverlayController
        val overlayRoot = getOverlayRoot(overlayController)
        val selectionView = overlayRoot.findViewById<InlineTextSelectionView>(R.id.inline_ocr_selection_view)
        val copyPill = overlayRoot.findViewById<LinearLayout>(R.id.btn_inline_ocr_copy)

        overlayRoot.measure(
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(2400, View.MeasureSpec.EXACTLY)
        )
        overlayRoot.layout(0, 0, 1080, 2400)

        // Tap Hindi block (id 1 because sorted by top: Receipt #42 at 160, कुल राशि ₹500 at 320)
        val hindiRect = selectionView.getMappedRectForBlock(1)!!
        dispatchTap(selectionView, hindiRect.centerX(), hindiRect.centerY())
        copyPill.performClick()

        val clipText = clipboard.primaryClip?.getItemAt(0)?.text?.toString()
        assertEquals("कुल राशि ₹500", clipText)
        assertFalse(controller.isOverlayShowing())
        assertTrue(overlaysVisible)

        // Now verify Rescan from inside overlay triggers ACTION_CAPTURE_NEXT cleanly without consent
        controller.start()
        ShadowLooper.idleMainLooper(200, TimeUnit.MILLISECONDS)
        val bmp2 = Bitmap.createBitmap(1080, 2400, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        ScreenCaptureHolder.setFrame(bmp2, false, 1080, 2400, Surface.ROTATION_0, 420)
        appContext.sendBroadcast(Intent(ScreenCaptureService.ACTION_CAPTURE_READY).apply {
            setPackage(appContext.packageName)
        })
        val deadline2 = System.currentTimeMillis() + 3000L
        while (!controller.isOverlayShowing() && System.currentTimeMillis() < deadline2) {
            ShadowLooper.idleMainLooper(25, TimeUnit.MILLISECONDS)
            Thread.sleep(15)
        }
        assertTrue(controller.isOverlayShowing())

        while (shadowApp.nextStartedActivity != null) { /* drain */ }
        shadowApp.clearStartedServices()
        val rescanBtn = getOverlayRoot(overlayController).findViewById<ImageButton>(R.id.btn_inline_ocr_rescan)
        rescanBtn.performClick()
        ShadowLooper.idleMainLooper(200, TimeUnit.MILLISECONDS)

        assertNull("Rescan with active session must NOT request consent again", shadowApp.nextStartedActivity)
        val rescanServiceIntent = shadowApp.nextStartedService
        assertNotNull(rescanServiceIntent)
        assertEquals(ScreenCaptureService.ACTION_CAPTURE_NEXT, rescanServiceIntent.action)

        controller.release()
        scope.cancel()
    }

    @Test
    fun `controller requests consent once then reuses active MediaProjection session without consent dialog`() {
        val scope = CoroutineScope(Dispatchers.Main + Job())
        val clipboard = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val controller = ScreenTextCopyController(
            context = appContext,
            serviceScope = scope,
            clipboardManager = clipboard,
            getAccentColor = { Color.CYAN },
            dismissActivePopup = {},
            setHostOverlaysVisible = {}
        )

        val shadowApp = Shadows.shadowOf(appContext)
        while (shadowApp.nextStartedActivity != null) { /* drain */ }
        shadowApp.clearStartedServices()

        // 1. No active session -> launches ScreenCaptureConsentActivity
        ScreenCaptureService.isSessionActive = false
        controller.start()
        ShadowLooper.idleMainLooper(200, TimeUnit.MILLISECONDS)

        val startedConsent = shadowApp.nextStartedActivity
        assertNotNull("Initial capture must request consent", startedConsent)
        assertEquals(ScreenCaptureConsentActivity::class.java.name, startedConsent.component?.className)

        // Simulate failure cleanup so controller is idle again
        appContext.sendBroadcast(Intent(ScreenCaptureService.ACTION_CAPTURE_FAILED).apply {
            setPackage(appContext.packageName)
            putExtra(ScreenCaptureService.EXTRA_ERROR_MESSAGE, "canceled")
        })
        ShadowLooper.idleMainLooper()

        // 2. Active session exists -> reuses ScreenCaptureService with ACTION_CAPTURE_NEXT (NO consent dialog!)
        while (shadowApp.nextStartedActivity != null) { /* drain */ }
        shadowApp.clearStartedServices()
        ScreenCaptureService.isSessionActive = true

        controller.start()
        ShadowLooper.idleMainLooper(200, TimeUnit.MILLISECONDS)

        assertNull("Must NOT launch consent dialog when session is already active", shadowApp.nextStartedActivity)
        val serviceIntent = shadowApp.nextStartedService
        assertNotNull("Must trigger capture on existing ScreenCaptureService", serviceIntent)
        assertEquals(ScreenCaptureService::class.java.name, serviceIntent.component?.className)
        assertEquals(ScreenCaptureService.ACTION_CAPTURE_NEXT, serviceIntent.action)

        // 3. If system stops/expires session mid-capture -> automatically falls back to consent activity once
        ScreenCaptureService.isSessionActive = false
        appContext.sendBroadcast(Intent(ScreenCaptureService.ACTION_SESSION_EXPIRED).apply {
            setPackage(appContext.packageName)
        })
        ShadowLooper.idleMainLooper()

        val fallbackConsent = shadowApp.nextStartedActivity
        assertNotNull("Expired session must prompt user for fresh consent", fallbackConsent)
        assertEquals(ScreenCaptureConsentActivity::class.java.name, fallbackConsent.component?.className)

        controller.release()
        scope.cancel()
    }

    @Test
    fun `recognizeEnglishAndHindi surfaces error when both recognizers fail and handles small bitmaps safely`() {
        val failingRecognizer = object : TextRecognizer {
            private fun fail(): Task<Text> =
                Tasks.forException(IllegalStateException("Mock ML Kit init failure"))

            override fun process(image: InputImage) = fail()
            override fun process(image: com.google.android.odml.image.MlImage) = fail()
            override fun process(bitmap: Bitmap, rotationDegrees: Int) = fail()
            override fun process(image: android.media.Image, rotationDegrees: Int) = fail()
            override fun process(image: android.media.Image, rotationDegrees: Int, matrix: Matrix) = fail()
            override fun process(buffer: ByteBuffer, width: Int, height: Int, rotationDegrees: Int, format: Int) = fail()
            override fun getDetectorType(): Int = Detector.TYPE_TEXT_RECOGNITION
            override fun getOptionalFeatures(): Array<Feature> = emptyArray()
            override fun close() {}
        }

        val helper = ScreenOcrHelper(
            latinClientFactory = { failingRecognizer },
            devanagariClientFactory = { failingRecognizer }
        )

        val tinyBmp = Bitmap.createBitmap(12, 12, Bitmap.Config.RGB_565)
        var thrown: Throwable? = null
        kotlinx.coroutines.runBlocking {
            try {
                helper.recognizeEnglishAndHindi(tinyBmp)
            } catch (t: Throwable) {
                thrown = t
            }
        }
        assertNotNull("Failure from both recognizers must surface cleanly instead of returning silent empty list", thrown)
        assertTrue(thrown is IllegalStateException)
        tinyBmp.recycle()
        helper.close()
    }

    private fun getOverlayRoot(controller: InlineTextOverlayController): View {
        val field = InlineTextOverlayController::class.java
            .getDeclaredField("overlayView")
            .apply { isAccessible = true }
        return field.get(controller) as View
    }

    private fun dispatchTap(view: View, x: Float, y: Float) {
        val down = MotionEvent.obtain(100L, 100L, MotionEvent.ACTION_DOWN, x, y, 0)
        val up = MotionEvent.obtain(100L, 140L, MotionEvent.ACTION_UP, x, y, 0)
        view.dispatchTouchEvent(down)
        view.dispatchTouchEvent(up)
        down.recycle()
        up.recycle()
    }

    private fun fakeRecognizer(resultText: Text): TextRecognizer {
        return object : TextRecognizer {
            override fun process(image: InputImage): Task<Text> = Tasks.forResult(resultText)
            override fun process(image: com.google.android.odml.image.MlImage): Task<Text> = Tasks.forResult(resultText)
            override fun process(bitmap: Bitmap, rotationDegrees: Int): Task<Text> = Tasks.forResult(resultText)
            override fun process(image: android.media.Image, rotationDegrees: Int): Task<Text> = Tasks.forResult(resultText)
            override fun process(image: android.media.Image, rotationDegrees: Int, matrix: Matrix): Task<Text> = Tasks.forResult(resultText)
            override fun process(buffer: ByteBuffer, width: Int, height: Int, rotationDegrees: Int, format: Int): Task<Text> = Tasks.forResult(resultText)
            override fun getDetectorType(): Int = Detector.TYPE_TEXT_RECOGNITION
            override fun getOptionalFeatures(): Array<Feature> = emptyArray()
            override fun close() {}
        }
    }
}
