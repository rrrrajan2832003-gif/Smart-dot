package com.example

import android.app.Application
import android.app.SearchManager
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.ResolveInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.net.Uri
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.example.SelectionToolbarHelper.ToolbarAction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import java.util.concurrent.TimeUnit
import kotlin.math.cos
import kotlin.math.sin

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w1080dp-h2400dp-mdpi")
class SelectionToolbarUpgradeTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private lateinit var appContext: Application

    @Before
    fun setUp() {
        appContext = ApplicationProvider.getApplicationContext()
        appContext.getSharedPreferences(SelectionToolbarHelper.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .apply()
    }

    @Test
    fun `Direct Google App Search uses ACTION_WEB_SEARCH targeted at googlequicksearchbox`() {
        val shadowPm = Shadows.shadowOf(appContext.packageManager)
        val googleResolveInfo = ResolveInfo().apply {
            activityInfo = ActivityInfo().apply {
                packageName = SelectionToolbarHelper.GOOGLE_APP_PACKAGE
                name = "com.google.android.googlequicksearchbox.SearchActivity"
                applicationInfo = ApplicationInfo().apply {
                    packageName = SelectionToolbarHelper.GOOGLE_APP_PACKAGE
                }
            }
        }
        shadowPm.addResolveInfoForIntent(
            Intent(Intent.ACTION_WEB_SEARCH).setPackage(SelectionToolbarHelper.GOOGLE_APP_PACKAGE),
            googleResolveInfo
        )

        val primaryIntent = SelectionToolbarHelper.createGoogleSearchIntent(appContext, "Kotlin Coroutines")
        assertNotNull(primaryIntent)
        assertEquals(Intent.ACTION_WEB_SEARCH, primaryIntent!!.action)
        assertEquals(SelectionToolbarHelper.GOOGLE_APP_PACKAGE, primaryIntent.`package`)
        assertEquals("Kotlin Coroutines", primaryIntent.getStringExtra(SearchManager.QUERY))
        assertTrue((primaryIntent.flags and Intent.FLAG_ACTIVITY_NEW_TASK) != 0)
    }

    @Test
    fun `Direct Google App Search falls back to ACTION_VIEW targeted at googlequicksearchbox when ACTION_WEB_SEARCH is unavailable`() {
        val shadowPm = Shadows.shadowOf(appContext.packageManager)
        val googleResolveInfo = ResolveInfo().apply {
            activityInfo = ActivityInfo().apply {
                packageName = SelectionToolbarHelper.GOOGLE_APP_PACKAGE
                name = "com.google.android.googlequicksearchbox.GoogleSearchWebActivity"
                applicationInfo = ApplicationInfo().apply {
                    packageName = SelectionToolbarHelper.GOOGLE_APP_PACKAGE
                }
            }
        }
        shadowPm.addResolveInfoForIntent(
            Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=Jetpack+Compose"))
                .setPackage(SelectionToolbarHelper.GOOGLE_APP_PACKAGE),
            googleResolveInfo
        )

        val fallbackIntent = SelectionToolbarHelper.createGoogleSearchIntent(appContext, "Jetpack Compose")
        assertNotNull(fallbackIntent)
        assertEquals(Intent.ACTION_VIEW, fallbackIntent!!.action)
        assertEquals(SelectionToolbarHelper.GOOGLE_APP_PACKAGE, fallbackIntent.`package`)
        assertEquals("https://www.google.com/search?q=Jetpack+Compose", fallbackIntent.dataString)
    }

    @Test
    fun `Icon-only Text Selection toolbar renders all 10 ImageButtons with zero TextViews and launches AI Mode, Gemini, ChatGPT, Share, and Full Translate`() {
        val scope = CoroutineScope(Dispatchers.Main + Job())
        val clipboard = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val shadowPm = Shadows.shadowOf(appContext.packageManager)

        registerPackageResolveInfo(shadowPm, SelectionToolbarHelper.GOOGLE_APP_PACKAGE, "com.google.Search", Intent.ACTION_VIEW, "https://www.google.com/search?udm=50&q=Orbit")
        registerPackageResolveInfo(shadowPm, SelectionToolbarHelper.GEMINI_PACKAGE, "com.bard.ShareActivity", Intent.ACTION_SEND, mimeType = "text/plain")
        registerPackageResolveInfo(shadowPm, SelectionToolbarHelper.CHATGPT_PACKAGE, "com.openai.ShareActivity", Intent.ACTION_SEND, mimeType = "text/plain")

        var fullTranslateTriggered = false
        val screenBitmap = Bitmap.createBitmap(1080, 2400, Bitmap.Config.ARGB_8888)
        val ocrHelper = ScreenOcrHelper(
            customOcrRunner = {
                val items = listOf(
                    ScreenOcrHelper.OcrRawItem("Orbit", Rect(120, 300, 300, 370), isLine = true),
                    ScreenOcrHelper.OcrRawItem("Orbit", Rect(120, 300, 300, 370), isLine = false)
                )
                items to emptyList()
            }
        )

        val controller = ScreenTextCopyController(
            context = appContext,
            serviceScope = scope,
            clipboardManager = clipboard,
            getAccentColor = { Color.parseColor("#00E5FF") },
            dismissActivePopup = {},
            setHostOverlaysVisible = {},
            onAccessibilityRequired = {},
            nodeExtractor = { emptyList() },
            ocrHelperFactory = { ocrHelper },
            screenBitmapCapturer = { cb -> cb(screenBitmap) },
            onFullScreenTranslateRequested = { fullTranslateTriggered = true }
        )

        // Start overlay & tap "Orbit"
        controller.start()
        waitUntilOverlayVisible(controller)
        val overlayCtrl = getOverlayController(controller)
        var root = getOverlayRoot(overlayCtrl)
        measureAndLayout(root)

        val wordBar = root.findViewById<LinearLayout>(R.id.inline_word_action_bar)
        val wordRow1 = root.findViewById<LinearLayout>(R.id.inline_word_row1)
        val wordRow2 = root.findViewById<LinearLayout>(R.id.inline_word_row2)
        val wordButtons = (0 until wordRow1.childCount).map { wordRow1.getChildAt(it) } +
            (0 until wordRow2.childCount).map { wordRow2.getChildAt(it) }
        assertEquals("Row 1 must have 5 icons", 5, wordRow1.childCount)
        assertEquals("Row 2 must have 6 icons", 6, wordRow2.childCount)
        assertEquals("Word toolbar must have all 11 icon buttons", 11, wordButtons.size)
        assertZeroTextViews(wordRow1)
        assertZeroTextViews(wordRow2)

        for (child in wordButtons) {
            assertTrue("All toolbar items must be ImageButton icons", child is ImageButton)
            assertFalse("Icon must have accessibility contentDescription", child.contentDescription.isNullOrBlank())
        }
        val etWordQuestion = root.findViewById<EditText>(R.id.et_word_ai_question)
        val btnWordSend = root.findViewById<ImageButton>(R.id.btn_word_ai_send)
        assertNotNull(etWordQuestion)
        assertNotNull(btnWordSend)
        assertEquals("Ask about this text/image...", etWordQuestion.hint?.toString())

        // Google AI Mode -> udm=50 targeted at Google App (zero chooser)
        tapWordNode0(root)
        root.findViewById<ImageButton>(R.id.btn_inline_word_ai_mode).performClick()
        ShadowLooper.idleMainLooper()
        val aiIntent = Shadows.shadowOf(appContext).nextStartedActivity
        assertEquals(Intent.ACTION_VIEW, aiIntent.action)
        assertEquals("https://www.google.com/search?udm=50&q=Orbit", aiIntent.dataString)
        assertEquals(SelectionToolbarHelper.GOOGLE_APP_PACKAGE, aiIntent.`package`)

        // Direct AI Question Box + Send -> combines question + selected text directly into Google AI Mode
        controller.start()
        waitUntilOverlayVisible(controller)
        root = getOverlayRoot(overlayCtrl)
        measureAndLayout(root)
        tapWordNode0(root)
        root.findViewById<EditText>(R.id.et_word_ai_question).setText("Explain simply")
        root.findViewById<ImageButton>(R.id.btn_word_ai_send).performClick()
        ShadowLooper.idleMainLooper()
        val aiQuestionIntent = Shadows.shadowOf(appContext).nextStartedActivity
        assertEquals(Intent.ACTION_VIEW, aiQuestionIntent.action)
        assertTrue(aiQuestionIntent.dataString?.contains("udm=50") == true)
        assertTrue(aiQuestionIntent.dataString?.contains("Explain+simply+Orbit") == true)

        // Gemini with Question Box -> com.google.android.apps.bard with text/plain directly (no chooser)
        controller.start()
        waitUntilOverlayVisible(controller)
        root = getOverlayRoot(overlayCtrl)
        measureAndLayout(root)
        tapWordNode0(root)
        root.findViewById<EditText>(R.id.et_word_ai_question).setText("Define this")
        root.findViewById<ImageButton>(R.id.btn_inline_word_gemini).performClick()
        ShadowLooper.idleMainLooper()
        val geminiIntent = Shadows.shadowOf(appContext).nextStartedActivity
        assertEquals(Intent.ACTION_SEND, geminiIntent.action)
        assertEquals(SelectionToolbarHelper.GEMINI_PACKAGE, geminiIntent.`package`)
        assertEquals("Define this\n\nOrbit", geminiIntent.getStringExtra(Intent.EXTRA_TEXT))

        // ChatGPT -> com.openai.chatgpt with text/plain directly (no chooser)
        controller.start()
        waitUntilOverlayVisible(controller)
        root = getOverlayRoot(overlayCtrl)
        measureAndLayout(root)
        tapWordNode0(root)
        root.findViewById<EditText>(R.id.et_word_ai_question).setText("")
        root.findViewById<ImageButton>(R.id.btn_inline_word_chatgpt).performClick()
        ShadowLooper.idleMainLooper()
        val gptIntent = Shadows.shadowOf(appContext).nextStartedActivity
        assertEquals(Intent.ACTION_SEND, gptIntent.action)
        assertEquals(SelectionToolbarHelper.CHATGPT_PACKAGE, gptIntent.`package`)
        assertEquals("Orbit", gptIntent.getStringExtra(Intent.EXTRA_TEXT))

        // Share -> Android Intent.ACTION_CHOOSER
        controller.start()
        waitUntilOverlayVisible(controller)
        root = getOverlayRoot(overlayCtrl)
        measureAndLayout(root)
        tapWordNode0(root)
        root.findViewById<ImageButton>(R.id.btn_inline_word_share).performClick()
        ShadowLooper.idleMainLooper()
        val shareIntent = Shadows.shadowOf(appContext).nextStartedActivity
        assertEquals(Intent.ACTION_CHOOSER, shareIntent.action)
        @Suppress("DEPRECATION")
        val wrappedShare = shareIntent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
        assertNotNull(wrappedShare)
        assertEquals("Orbit", wrappedShare!!.getStringExtra(Intent.EXTRA_TEXT))

        // Full Screen Translate -> switches cleanly to full screen translate callback
        controller.start()
        waitUntilOverlayVisible(controller)
        root = getOverlayRoot(overlayCtrl)
        measureAndLayout(root)
        tapWordNode0(root)
        root.findViewById<ImageButton>(R.id.btn_inline_word_full_translate).performClick()
        ShadowLooper.idleMainLooper()
        assertTrue(fullTranslateTriggered)
        assertFalse(controller.isOverlayShowing())

        controller.release()
        scope.cancel()
    }

    @Test
    fun `Cutout Box displays the EXACT same 10-icon bar in custom user order and supports Cutout Speak, Translate, Gemini, ChatGPT, and Share`() {
        val prefs = appContext.getSharedPreferences(SelectionToolbarHelper.PREFS_NAME, Context.MODE_PRIVATE)
        val customOrder = listOf(
            ToolbarAction.GEMINI,
            ToolbarAction.CHATGPT,
            ToolbarAction.SPEAK,
            ToolbarAction.TRANSLATE,
            ToolbarAction.GOOGLE_AI_MODE,
            ToolbarAction.SEARCH,
            ToolbarAction.GOOGLE_LENS,
            ToolbarAction.SAVE_IMAGE,
            ToolbarAction.COPY,
            ToolbarAction.SHARE,
            ToolbarAction.FULL_SCREEN_TRANSLATE
        )
        SelectionToolbarHelper.saveToolbarOrder(prefs, customOrder)

        val scope = CoroutineScope(Dispatchers.Main + Job())
        val clipboard = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val shadowPm = Shadows.shadowOf(appContext.packageManager)
        registerPackageResolveInfo(shadowPm, SelectionToolbarHelper.GEMINI_PACKAGE, "com.bard.ImgActivity", Intent.ACTION_SEND, mimeType = "image/png")
        registerPackageResolveInfo(shadowPm, SelectionToolbarHelper.CHATGPT_PACKAGE, "com.openai.ImgActivity", Intent.ACTION_SEND, mimeType = "image/png")

        var spokenFromCutout: String? = null
        var sharedCutoutBitmapSize: Pair<Int, Int>? = null
        val screenBitmap = Bitmap.createBitmap(1080, 2400, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.DKGRAY)
        }
        val ocrHelper = ScreenOcrHelper(
            customOcrRunner = {
                listOf(ScreenOcrHelper.OcrRawItem("Cutout Heading", Rect(10, 10, 260, 60), isLine = true)) to emptyList()
            }
        )
        val translateHelper = ScreenTranslateHelper(
            identifyLanguageFn = { "en" },
            translateBlockFn = { _, dst, _, _ ->
                if (dst == com.google.mlkit.nl.translate.TranslateLanguage.HINDI) "कटआउट शीर्षक" else "Translated"
            },
            onlineTranslateFn = { _, _ -> null }
        )

        val controller = ScreenTextCopyController(
            context = appContext,
            serviceScope = scope,
            clipboardManager = clipboard,
            getAccentColor = { Color.parseColor("#00E5FF") },
            dismissActivePopup = {},
            setHostOverlaysVisible = {},
            onAccessibilityRequired = {},
            nodeExtractor = { emptyList() },
            ocrHelperFactory = { ocrHelper },
            screenBitmapCapturer = { cb -> cb(screenBitmap) },
            translateHelperFactory = { translateHelper },
            customSpeakHandler = { spokenFromCutout = it },
            customShareImageHandler = { bmp -> sharedCutoutBitmapSize = bmp.width to bmp.height }
        )

        controller.start()
        waitUntilOverlayVisible(controller)
        val overlayCtrl = getOverlayController(controller)
        var root = getOverlayRoot(overlayCtrl)
        measureAndLayout(root)

        val wordRow1 = root.findViewById<LinearLayout>(R.id.inline_word_row1)
        val wordRow2 = root.findViewById<LinearLayout>(R.id.inline_word_row2)
        val cutoutRow1 = root.findViewById<LinearLayout>(R.id.inline_cutout_row1)
        val cutoutRow2 = root.findViewById<LinearLayout>(R.id.inline_cutout_row2)
        val cutoutBar = root.findViewById<LinearLayout>(R.id.inline_cutout_action_bar)
        assertEquals(5, cutoutRow1.childCount)
        assertEquals(6, cutoutRow2.childCount)
        assertZeroTextViews(cutoutRow1)
        assertZeroTextViews(cutoutRow2)

        val expectedTags = customOrder.map { "toolbar_action_${it.id}" }
        val actualWordTags = (0 until wordRow1.childCount).map { wordRow1.getChildAt(it).tag } +
            (0 until wordRow2.childCount).map { wordRow2.getChildAt(it).tag }
        val actualCutoutTags = (0 until cutoutRow1.childCount).map { cutoutRow1.getChildAt(it).tag } +
            (0 until cutoutRow2.childCount).map { cutoutRow2.getChildAt(it).tag }
        assertEquals("Word toolbar order must match custom prefs", expectedTags, actualWordTags)
        assertEquals("Cutout toolbar order must match exact same custom prefs", expectedTags, actualCutoutTags)

        // Draw circle to activate Cutout Box
        val selView = root.findViewById<InlineTextSelectionView>(R.id.inline_ocr_selection_view)
        dispatchCircleGesture(selView, cx = 400f, cy = 700f, rx = 180f, ry = 180f)
        val cutoutBox = selView.getActiveCutoutRect()
        assertNotNull(cutoutBox)
        assertEquals(View.VISIBLE, cutoutBar.visibility)

        // 1) Cutout Speak reads extracted OCR text
        root.findViewById<ImageButton>(R.id.btn_cutout_speak).performClick()
        val speakDeadline = System.currentTimeMillis() + 3000L
        while (spokenFromCutout == null && System.currentTimeMillis() < speakDeadline) {
            ShadowLooper.idleMainLooper(20, TimeUnit.MILLISECONDS)
            Thread.sleep(10)
        }
        assertEquals("Cutout Heading", spokenFromCutout)

        // 2) Cutout Translate populates translate bubble with OCR translation
        root.findViewById<ImageButton>(R.id.btn_cutout_translate).performClick()
        val tvTranslated = root.findViewById<TextView>(R.id.tv_inline_word_translated_text)
        val trDeadline = System.currentTimeMillis() + 3000L
        while (tvTranslated.text.toString() != "कटआउट शीर्षक" && System.currentTimeMillis() < trDeadline) {
            ShadowLooper.idleMainLooper(20, TimeUnit.MILLISECONDS)
            Thread.sleep(10)
        }
        assertEquals("कटआउट शीर्षक", tvTranslated.text.toString())

        // 3) Cutout Share receives cropped bitmap
        root.findViewById<ImageButton>(R.id.btn_cutout_share).performClick()
        assertNotNull(sharedCutoutBitmapSize)
        assertTrue(
            "Expected width near ${cutoutBox!!.width()} (sel=${selView.width}x${selView.height}), got $sharedCutoutBitmapSize",
            kotlin.math.abs(cutoutBox.width() - sharedCutoutBitmapSize!!.first) <= 2f
        )

        // 4) Cutout Gemini sends image/png + optional typed question directly to com.google.android.apps.bard
        controller.start()
        waitUntilOverlayVisible(controller)
        root = getOverlayRoot(overlayCtrl)
        measureAndLayout(root)
        dispatchCircleGesture(root.findViewById(R.id.inline_ocr_selection_view), cx = 400f, cy = 700f, rx = 180f, ry = 180f)
        root.findViewById<EditText>(R.id.et_cutout_ai_question).setText("What is in this cutout?")
        root.findViewById<ImageButton>(R.id.btn_cutout_gemini).performClick()
        ShadowLooper.idleMainLooper()
        val geminiImgIntent = Shadows.shadowOf(appContext).nextStartedActivity
        assertEquals(Intent.ACTION_SEND, geminiImgIntent.action)
        assertEquals("image/png", geminiImgIntent.type)
        assertEquals(SelectionToolbarHelper.GEMINI_PACKAGE, geminiImgIntent.`package`)
        assertEquals("What is in this cutout?", geminiImgIntent.getStringExtra(Intent.EXTRA_TEXT))

        // 5) Cutout Question Box Send -> sends directly to Google AI Mode without ACTION_CHOOSER
        registerPackageResolveInfo(shadowPm, SelectionToolbarHelper.GOOGLE_APP_PACKAGE, "com.google.LensActivity", Intent.ACTION_SEND, mimeType = "image/png")
        controller.start()
        waitUntilOverlayVisible(controller)
        root = getOverlayRoot(overlayCtrl)
        measureAndLayout(root)
        dispatchCircleGesture(root.findViewById(R.id.inline_ocr_selection_view), cx = 400f, cy = 700f, rx = 180f, ry = 180f)
        root.findViewById<EditText>(R.id.et_cutout_ai_question).setText("Identify object")
        root.findViewById<ImageButton>(R.id.btn_cutout_ai_send).performClick()
        ShadowLooper.idleMainLooper()
        val cutoutAiSendIntent = Shadows.shadowOf(appContext).nextStartedActivity
        assertEquals(Intent.ACTION_SEND, cutoutAiSendIntent.action)
        assertEquals(SelectionToolbarHelper.GOOGLE_APP_PACKAGE, cutoutAiSendIntent.`package`)
        assertEquals("Identify object", cutoutAiSendIntent.getStringExtra(Intent.EXTRA_TEXT))

        controller.release()
        scope.cancel()
    }

    @Test
    fun `MainActivity Selection Toolbar Order UI moves items Up and Down and persists sequence to SharedPreferences`() {
        composeRule.onNodeWithTag("selection_toolbar_order_card")
            .performScrollTo()
            .assertIsDisplayed()

        // Move 2nd item ("speak") Up so order begins with ["speak", "copy", ...]
        composeRule.onNodeWithTag("btn_toolbar_up_speak")
            .performScrollTo()
            .performClick()

        val prefs = composeRule.activity.getSharedPreferences(SelectionToolbarHelper.PREFS_NAME, Context.MODE_PRIVATE)
        val afterUp = SelectionToolbarHelper.getToolbarOrder(prefs)
        assertEquals(ToolbarAction.SPEAK, afterUp[0])
        assertEquals(ToolbarAction.COPY, afterUp[1])

        // Move "speak" back Down, then Reset
        composeRule.onNodeWithTag("btn_toolbar_down_speak")
            .performScrollTo()
            .performClick()
        val afterDown = SelectionToolbarHelper.getToolbarOrder(prefs)
        assertEquals(ToolbarAction.COPY, afterDown[0])
        assertEquals(ToolbarAction.SPEAK, afterDown[1])

        // Move "chatgpt" Up once and verify saved order persists
        composeRule.onNodeWithTag("btn_toolbar_up_chatgpt")
            .performScrollTo()
            .performClick()
        val afterGptUp = SelectionToolbarHelper.getToolbarOrder(prefs)
        assertTrue(afterGptUp.indexOf(ToolbarAction.CHATGPT) < afterGptUp.indexOf(ToolbarAction.GEMINI))

        // Reset restores exact DEFAULT_ORDER
        composeRule.onNodeWithTag("btn_reset_toolbar_order")
            .performScrollTo()
            .performClick()
        assertEquals(SelectionToolbarHelper.DEFAULT_ORDER, SelectionToolbarHelper.getToolbarOrder(prefs))
    }

    private fun registerPackageResolveInfo(
        shadowPm: org.robolectric.shadows.ShadowPackageManager,
        pkg: String,
        activityName: String,
        action: String,
        url: String? = null,
        mimeType: String? = null
    ) {
        val info = ResolveInfo().apply {
            activityInfo = ActivityInfo().apply {
                packageName = pkg
                name = activityName
                applicationInfo = ApplicationInfo().apply {
                    packageName = pkg
                }
            }
        }
        val intent = if (url != null) {
            Intent(action, Uri.parse(url)).setPackage(pkg)
        } else {
            Intent(action).apply {
                if (mimeType != null) type = mimeType
                setPackage(pkg)
            }
        }
        shadowPm.addResolveInfoForIntent(intent, info)
    }

    private fun assertZeroTextViews(group: ViewGroup) {
        for (i in 0 until group.childCount) {
            val child = group.getChildAt(i)
            assertFalse("Floating icon toolbar must contain zero TextViews", child is TextView)
            if (child is ViewGroup) {
                assertZeroTextViews(child)
            }
        }
    }

    private fun tapWordNode0(root: View) {
        val selView = root.findViewById<InlineTextSelectionView>(R.id.inline_ocr_selection_view)
        val rect = selView.getMappedRectForCopyNode(0)!!
        val down = MotionEvent.obtain(100L, 100L, MotionEvent.ACTION_DOWN, rect.centerX(), rect.centerY(), 0)
        val up = MotionEvent.obtain(100L, 125L, MotionEvent.ACTION_UP, rect.centerX(), rect.centerY(), 0)
        selView.dispatchTouchEvent(down)
        selView.dispatchTouchEvent(up)
        down.recycle()
        up.recycle()
    }

    private fun dispatchCircleGesture(view: View, cx: Float, cy: Float, rx: Float, ry: Float) {
        val steps = 20
        val events = mutableListOf<MotionEvent>()
        val t0 = 200L
        for (i in 0..steps) {
            val angle = (i / steps.toDouble()) * 2.0 * Math.PI
            val x = cx + (rx * cos(angle)).toFloat()
            val y = cy + (ry * sin(angle)).toFloat()
            val action = when (i) {
                0 -> MotionEvent.ACTION_DOWN
                steps -> MotionEvent.ACTION_UP
                else -> MotionEvent.ACTION_MOVE
            }
            val ev = MotionEvent.obtain(t0, t0 + i * 15L, action, x, y, 0)
            events.add(ev)
            view.dispatchTouchEvent(ev)
        }
        events.forEach { it.recycle() }
    }

    private fun waitUntilOverlayVisible(controller: ScreenTextCopyController) {
        ShadowLooper.idleMainLooper(ScreenTextCopyController.CAPTURE_HIDE_DELAY_MS + 10L, TimeUnit.MILLISECONDS)
        val deadline = System.currentTimeMillis() + 3000L
        while (!controller.isOverlayShowing() && System.currentTimeMillis() < deadline) {
            ShadowLooper.idleMainLooper(25, TimeUnit.MILLISECONDS)
            Thread.sleep(15)
        }
        assertTrue("Overlay must become visible", controller.isOverlayShowing())
    }

    private fun measureAndLayout(root: View) {
        root.measure(
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(2400, View.MeasureSpec.EXACTLY)
        )
        root.layout(0, 0, 1080, 2400)
    }

    private fun getOverlayController(controller: ScreenTextCopyController): InlineTextOverlayController {
        val field = ScreenTextCopyController::class.java
            .getDeclaredField("overlayController")
            .apply { isAccessible = true }
        return field.get(controller) as InlineTextOverlayController
    }

    private fun getOverlayRoot(controller: InlineTextOverlayController): View {
        val field = InlineTextOverlayController::class.java
            .getDeclaredField("overlayView")
            .apply { isAccessible = true }
        return field.get(controller) as View
    }
}
