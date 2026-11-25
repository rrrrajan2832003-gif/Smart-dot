package com.example

import android.app.Application
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.ResolveInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.net.Uri
import android.provider.Settings
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.webkit.ValueCallback
import android.webkit.WebView
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import org.robolectric.shadows.ShadowToast
import java.util.concurrent.TimeUnit
import kotlin.math.cos
import kotlin.math.sin

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1080dp-h2400dp-mdpi")
class SmartDotFourFixesTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private lateinit var appContext: Application

    @Before
    fun setUp() {
        appContext = ApplicationProvider.getApplicationContext()
        appContext.getSharedPreferences(SelectionToolbarHelper.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        FloatingMiniApps.pendingBrowserUploadImageUri = null
        SmartDotAccessibilityService.setInstanceForTest(null)
    }

    @After
    fun tearDown() {
        FloatingMiniApps.pendingBrowserUploadImageUri = null
        SmartDotAccessibilityService.setInstanceForTest(null)
    }

    @Test
    fun `2nd question box has editable preset buttons with dimming on type, Save button, and Show-Hide Question Boxes toggle`() {
        // Edit both prompts and explicitly tap Save button in MainActivity
        composeRule.onNodeWithTag("input_quick_prompt_1")
            .performScrollTo()
            .performTextReplacement("Explain simply: ")
        composeRule.onNodeWithTag("input_quick_prompt_2")
            .performScrollTo()
            .performTextReplacement("Key facts: ")
        composeRule.onNodeWithTag("btn_save_quick_prompts")
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()
        composeRule.waitForIdle()

        val prefs = composeRule.activity.getSharedPreferences(SelectionToolbarHelper.PREFS_NAME, Context.MODE_PRIVATE)
        assertEquals("Explain simply: ", SelectionToolbarHelper.getQuickPrompt1(prefs))
        assertEquals("Key facts: ", SelectionToolbarHelper.getQuickPrompt2(prefs))
        assertEquals(appContext.getString(R.string.quick_prompts_saved), ShadowToast.getTextOfLatestToast())

        val scope = CoroutineScope(Dispatchers.Main + Job())
        val clipboard = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val bitmap = Bitmap.createBitmap(1080, 2400, Bitmap.Config.ARGB_8888)
        val ocrHelper = ScreenOcrHelper(
            customOcrRunner = {
                listOf(ScreenOcrHelper.OcrRawItem("Quantum", Rect(100, 300, 300, 360), isLine = false)) to emptyList()
            }
        )
        val wm = appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val fwm = FloatingWindowManager(appContext, wm)

        val controller = ScreenTextCopyController(
            context = appContext,
            serviceScope = scope,
            clipboardManager = clipboard,
            getAccentColor = { Color.CYAN },
            dismissActivePopup = {},
            setHostOverlaysVisible = {},
            onAccessibilityRequired = {},
            nodeExtractor = { emptyList() },
            ocrHelperFactory = { ocrHelper },
            screenBitmapCapturer = { cb -> cb(bitmap) },
            customFloatingAiBrowserLauncher = { providerId, textOrQuery, question, cutoutBmp ->
                if (providerId == "chatgpt") {
                    FloatingMiniApps.openChatGptInBrowser(appContext, fwm, Color.CYAN, textOrQuery, question, cutoutBmp)
                } else {
                    FloatingMiniApps.openGoogleAiModeInBrowser(appContext, fwm, Color.CYAN, textOrQuery, question, cutoutBmp)
                }
                true
            }
        )

        try {
            // 1) Question boxes visible by default: verify preset buttons in 2nd box + dimming on type
            assertTrue(SelectionToolbarHelper.isShowQuestionBoxesEnabled(prefs))
            controller.start()
            waitUntilOverlayVisible(controller)

            val overlayCtrl = ScreenTextCopyController::class.java
                .getDeclaredField("overlayController")
                .apply { isAccessible = true }
                .get(controller) as InlineTextOverlayController
            val root = InlineTextOverlayController::class.java
                .getDeclaredField("overlayView")
                .apply { isAccessible = true }
                .get(overlayCtrl) as View

            root.measure(
                View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(2400, View.MeasureSpec.EXACTLY)
            )
            root.layout(0, 0, 1080, 2400)

            val selView = root.findViewById<InlineTextSelectionView>(R.id.inline_ocr_selection_view)
            val rect = selView.getMappedRectForCopyNode(0)!!
            selView.dispatchTouchEvent(MotionEvent.obtain(10L, 10L, MotionEvent.ACTION_DOWN, rect.centerX(), rect.centerY(), 0))
            selView.dispatchTouchEvent(MotionEvent.obtain(10L, 25L, MotionEvent.ACTION_UP, rect.centerX(), rect.centerY(), 0))

            val wordQRow = root.findViewById<LinearLayout>(R.id.inline_word_question_row)
            val wordBrowserRow = root.findViewById<LinearLayout>(R.id.inline_word_browser_ai_row)
            val etBrowserAi = root.findViewById<EditText>(R.id.et_word_browser_ai_question)
            val btnBrowserPreset1 = root.findViewById<ImageButton>(R.id.btn_word_browser_preset_1)
            val btnBrowserPreset2 = root.findViewById<ImageButton>(R.id.btn_word_browser_preset_2)
            assertEquals(View.VISIBLE, wordQRow.visibility)
            assertEquals(View.VISIBLE, wordBrowserRow.visibility)
            assertTrue(btnBrowserPreset1.isEnabled)
            assertTrue(btnBrowserPreset2.isEnabled)
            assertEquals(1f, btnBrowserPreset1.alpha, 0.01f)

            // Typing dims and disables both 2nd-box preset buttons
            etBrowserAi.setText("What is this?")
            assertFalse(btnBrowserPreset1.isEnabled)
            assertFalse(btnBrowserPreset2.isEnabled)
            assertTrue(btnBrowserPreset1.alpha < 0.5f)
            assertTrue(btnBrowserPreset2.alpha < 0.5f)

            // Clearing text re-enables and tapping 2nd-box Preset 1 launches Floating Browser with saved prompt
            etBrowserAi.setText("")
            assertTrue(btnBrowserPreset1.isEnabled)
            assertEquals(1f, btnBrowserPreset1.alpha, 0.01f)
            btnBrowserPreset1.performClick()
            ShadowLooper.idleMainLooper()

            assertTrue(fwm.hasWindow("floating_browser"))
            val webView = findWebView(fwm.getWindow("floating_browser")!!.rootView)!!
            assertTrue(webView.url.orEmpty().contains("udm=50"))
            assertTrue(webView.url.orEmpty().contains("Explain+simply"))
            assertTrue(webView.url.orEmpty().contains("Quantum"))

            // 2) Toggle "Show Question Boxes" OFF in MainActivity -> both boxes hidden on word & cutout toolbars
            composeRule.onNodeWithTag("switch_show_question_boxes")
                .performScrollTo()
                .performClick()
            composeRule.waitForIdle()
            assertFalse(SelectionToolbarHelper.isShowQuestionBoxesEnabled(prefs))

            if (controller.isOverlayShowing()) overlayCtrl.dismiss()
            controller.start()
            waitUntilOverlayVisible(controller)
            val rootHidden = InlineTextOverlayController::class.java
                .getDeclaredField("overlayView")
                .apply { isAccessible = true }
                .get(overlayCtrl) as View
            rootHidden.measure(
                View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(2400, View.MeasureSpec.EXACTLY)
            )
            rootHidden.layout(0, 0, 1080, 2400)

            val selHidden = rootHidden.findViewById<InlineTextSelectionView>(R.id.inline_ocr_selection_view)
            val rectHidden = selHidden.getMappedRectForCopyNode(0)!!
            selHidden.dispatchTouchEvent(MotionEvent.obtain(10L, 10L, MotionEvent.ACTION_DOWN, rectHidden.centerX(), rectHidden.centerY(), 0))
            selHidden.dispatchTouchEvent(MotionEvent.obtain(10L, 25L, MotionEvent.ACTION_UP, rectHidden.centerX(), rectHidden.centerY(), 0))

            assertEquals(View.GONE, rootHidden.findViewById<View>(R.id.inline_word_question_row).visibility)
            assertEquals(View.GONE, rootHidden.findViewById<View>(R.id.inline_word_browser_ai_row).visibility)

            // Draw a closed circle cutout on empty/screen space
            dispatchCircleGesture(selHidden, cx = 360f, cy = 580f, rx = 220f, ry = 200f)

            assertEquals(View.GONE, rootHidden.findViewById<View>(R.id.inline_cutout_question_row).visibility)
            assertEquals(View.GONE, rootHidden.findViewById<View>(R.id.inline_cutout_browser_ai_row).visibility)
            assertEquals(View.VISIBLE, rootHidden.findViewById<View>(R.id.btn_cutout_close).visibility)
        } finally {
            fwm.closeAllWindows()
            controller.release()
            scope.cancel()
        }
    }

    @Test
    fun `Cutout Google AI Mode and ChatGPT send image URI without OCR text and stage auto-upload`() {
        val scope = CoroutineScope(Dispatchers.Main + Job())
        val clipboard = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val bitmap = Bitmap.createBitmap(1080, 2400, Bitmap.Config.ARGB_8888)
        val ocrHelper = ScreenOcrHelper(
            customOcrRunner = {
                listOf(
                    ScreenOcrHelper.OcrRawItem("SECRET_OCR_SHOULD_NOT_APPEAR", Rect(150, 400, 500, 480), isLine = false)
                ) to emptyList()
            }
        )
        val wm = appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val fwm = FloatingWindowManager(appContext, wm)

        val controller = ScreenTextCopyController(
            context = appContext,
            serviceScope = scope,
            clipboardManager = clipboard,
            getAccentColor = { Color.CYAN },
            dismissActivePopup = {},
            setHostOverlaysVisible = {},
            onAccessibilityRequired = {},
            nodeExtractor = { emptyList() },
            ocrHelperFactory = { ocrHelper },
            screenBitmapCapturer = { cb -> cb(bitmap) },
            customFloatingAiBrowserLauncher = { providerId, textOrQuery, question, cutoutBmp ->
                if (providerId == "chatgpt") {
                    FloatingMiniApps.openChatGptInBrowser(appContext, fwm, Color.CYAN, textOrQuery, question, cutoutBmp)
                } else {
                    FloatingMiniApps.openGoogleAiModeInBrowser(appContext, fwm, Color.CYAN, textOrQuery, question, cutoutBmp)
                }
                true
            }
        )

        try {
            controller.start()
            waitUntilOverlayVisible(controller)

            val overlayCtrl = ScreenTextCopyController::class.java
                .getDeclaredField("overlayController")
                .apply { isAccessible = true }
                .get(controller) as InlineTextOverlayController
            val root = InlineTextOverlayController::class.java
                .getDeclaredField("overlayView")
                .apply { isAccessible = true }
                .get(overlayCtrl) as View
            root.measure(
                View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(2400, View.MeasureSpec.EXACTLY)
            )
            root.layout(0, 0, 1080, 2400)

            val selView = root.findViewById<InlineTextSelectionView>(R.id.inline_ocr_selection_view)
            dispatchCircleGesture(selView, cx = 360f, cy = 580f, rx = 220f, ry = 200f)

            // Invoke Google AI Mode cutout action directly on controller
            val aiCutoutMethod = ScreenTextCopyController::class.java
                .getDeclaredMethod(
                    "launchGoogleAiModeCutout",
                    android.graphics.RectF::class.java,
                    List::class.java,
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                    String::class.java
                )
                .apply { isAccessible = true }
            aiCutoutMethod.invoke(
                controller,
                android.graphics.RectF(120f, 380f, 600f, 800f),
                emptyList<SmartDotAccessibilityService.ScreenTextNode>(),
                1080,
                2400,
                ""
            )
            ShadowLooper.idleMainLooper()

            val webView = findWebView(fwm.getWindow("floating_browser")!!.rootView)!!
            val loadedUrl = webView.url.orEmpty()
            assertTrue(loadedUrl.startsWith("https://www.google.com/search?udm=50"))
            assertFalse("Cutout AI Mode must NOT inject screen OCR text", loadedUrl.contains("SECRET_OCR_SHOULD_NOT_APPEAR"))

            val clip = clipboard.primaryClip
            assertNotNull("Cutout image URI must be copied to clipboard", clip)
            assertNotNull(clip!!.getItemAt(0).uri)
            assertTrue(clip.getItemAt(0).uri.toString().endsWith("cutout.png"))

            val stagedUri = FloatingMiniApps.pendingBrowserUploadImageUri
            assertNotNull("Staged upload URI must be set for WebView file chooser", stagedUri)
            assertTrue(stagedUri.toString().endsWith("cutout.png"))

            // Verify WebChromeClient.onShowFileChooser delivers cutout.png URI immediately
            var deliveredUris: Array<Uri>? = null
            val handled = webView.webChromeClient!!.onShowFileChooser(
                webView,
                ValueCallback { uris -> deliveredUris = uris },
                null
            )
            assertTrue(handled)
            assertNotNull(deliveredUris)
            assertEquals(1, deliveredUris!!.size)
            assertTrue(deliveredUris!![0].toString().endsWith("cutout.png"))
            assertNull(FloatingMiniApps.pendingBrowserUploadImageUri)

            // Verify ChatGPT cutout launches https://chatgpt.com/ with NO OCR text and auto-upload script present
            val chatGptCutoutMethod = ScreenTextCopyController::class.java
                .getDeclaredMethod(
                    "launchChatGptCutout",
                    android.graphics.RectF::class.java,
                    List::class.java,
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                    String::class.java
                )
                .apply { isAccessible = true }
            chatGptCutoutMethod.invoke(
                controller,
                android.graphics.RectF(120f, 380f, 600f, 800f),
                emptyList<SmartDotAccessibilityService.ScreenTextNode>(),
                1080,
                2400,
                ""
            )
            ShadowLooper.idleMainLooper()

            assertEquals("https://chatgpt.com/", webView.url)
            assertNotNull(FloatingMiniApps.pendingBrowserUploadImageUri)
            val script = FloatingMiniApps.getAutoUploadTriggerScript()
            assertTrue(script.contains("input[type=\"file\"]"))
            assertTrue(script.contains(".click()"))
        } finally {
            fwm.closeAllWindows()
            controller.release()
            scope.cancel()
        }
    }

    @Test
    fun `Direct SmartDot Accessibility Details intent and Display Over Apps shortcut work`() {
        assertTrue(ACTION_LIST.any { it.id == "display_over_apps" })
        assertEquals("Overlay Perm", resolveShortcutShortLabel("display_over_apps"))

        val expectedFlat = ComponentName(appContext, SmartDotAccessibilityService::class.java).flattenToString()

        // Without system handler for ACCESSIBILITY_DETAILS_SETTINGS, falls back cleanly with :settings:fragment_args_key
        val fallbackIntent = SelectionToolbarHelper.createSmartDotAccessibilityDetailsIntent(appContext)
        assertEquals(Settings.ACTION_ACCESSIBILITY_SETTINGS, fallbackIntent.action)
        assertEquals(expectedFlat, fallbackIntent.getStringExtra(":settings:fragment_args_key"))

        // When OS resolves android.settings.ACCESSIBILITY_DETAILS_SETTINGS, targets SmartDot directly
        val shadowPm = Shadows.shadowOf(appContext.packageManager)
        val detailsResolve = ResolveInfo().apply {
            activityInfo = ActivityInfo().apply {
                packageName = "com.android.settings"
                name = "com.android.settings.accessibility.AccessibilityDetailsSettingsActivity"
                exported = true
                applicationInfo = ApplicationInfo().apply { packageName = "com.android.settings" }
            }
        }
        shadowPm.addResolveInfoForIntent(Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS"), detailsResolve)

        val directIntent = SelectionToolbarHelper.createSmartDotAccessibilityDetailsIntent(appContext)
        assertEquals("android.settings.ACCESSIBILITY_DETAILS_SETTINGS", directIntent.action)
        assertEquals(expectedFlat, directIntent.getStringExtra(Intent.EXTRA_COMPONENT_NAME))

        // Execute display_over_apps via FloatingService
        val service = Robolectric.buildService(FloatingService::class.java)
            .create()
            .startCommand(0, 1)
            .get()
        val executeAction = FloatingService::class.java
            .getDeclaredMethod("executeAction", String::class.java)
            .apply { isAccessible = true }

        while (Shadows.shadowOf(appContext).nextStartedActivity != null) { /* drain */ }

        executeAction.invoke(service, "display_over_apps")
        val overlayIntent = Shadows.shadowOf(appContext).nextStartedActivity
        assertNotNull("display_over_apps must start overlay permission intent", overlayIntent)
        assertEquals(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, overlayIntent!!.action)
        assertEquals("package:${appContext.packageName}", overlayIntent.dataString)

        service.stopSelf()
    }

    @Test
    fun `BackTapDetector triggers on sharp Z double-tap, rejects walking motion, and MainActivity persists settings`() {
        var triggerCount = 0
        val detector = BackTapDetector(zSpikeThreshold = 7.5f) {
            triggerCount++
        }

        // Lateral walking/shaking spikes (high X/Y relative to Z) must NOT trigger
        assertFalse(detector.onLinearAccelerationSample(lx = 9.5f, ly = 7.0f, lz = 8.5f, timestampMs = 1000L))
        assertFalse(detector.onLinearAccelerationSample(lx = 8.5f, ly = 6.5f, lz = 8.2f, timestampMs = 1220L))
        assertEquals(0, triggerCount)

        // Two taps too close (<120ms bounce) must NOT trigger
        assertFalse(detector.onLinearAccelerationSample(lx = 0.5f, ly = 0.4f, lz = 9.2f, timestampMs = 2000L))
        assertFalse(detector.onLinearAccelerationSample(lx = 0.3f, ly = 0.2f, lz = 9.4f, timestampMs = 2060L))
        assertEquals(0, triggerCount)

        // Two taps too far apart (>400ms) must NOT trigger
        assertFalse(detector.onLinearAccelerationSample(lx = 0.4f, ly = 0.3f, lz = 9.2f, timestampMs = 3000L))
        assertFalse(detector.onLinearAccelerationSample(lx = 0.4f, ly = 0.3f, lz = 9.2f, timestampMs = 3600L))
        assertEquals(0, triggerCount)

        // Valid sharp Z double-tap within 120..400ms MUST trigger once
        assertFalse(detector.onLinearAccelerationSample(lx = 0.3f, ly = 0.2f, lz = 9.8f, timestampMs = 5000L))
        assertTrue(detector.onLinearAccelerationSample(lx = 0.4f, ly = 0.3f, lz = -9.6f, timestampMs = 5240L))
        assertEquals(1, triggerCount)

        // Immediately following sample inside 1200ms cooldown must NOT re-trigger
        assertFalse(detector.onLinearAccelerationSample(lx = 0.2f, ly = 0.1f, lz = 10.5f, timestampMs = 5600L))
        assertFalse(detector.onLinearAccelerationSample(lx = 0.2f, ly = 0.1f, lz = 10.5f, timestampMs = 5820L))
        assertEquals(1, triggerCount)

        // Verify MainActivity Back-Tap switch & slider are rendered and persist preferences
        val prefs = composeRule.activity.getSharedPreferences(SelectionToolbarHelper.PREFS_NAME, Context.MODE_PRIVATE)
        assertFalse(prefs.getBoolean(FloatingService.PREF_BACK_TAP_DATA_ENABLED, false))

        composeRule.onNodeWithTag("switch_back_tap_data")
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()
        composeRule.onNodeWithTag("slider_back_tap_sensitivity")
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.waitForIdle()

        assertTrue(prefs.getBoolean(FloatingService.PREF_BACK_TAP_DATA_ENABLED, false))
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
        ShadowLooper.idleMainLooper(ScreenTextCopyController.CAPTURE_HIDE_DELAY_MS + 15L, TimeUnit.MILLISECONDS)
        val deadline = System.currentTimeMillis() + 3000L
        while (!controller.isOverlayShowing() && System.currentTimeMillis() < deadline) {
            ShadowLooper.idleMainLooper(25L, TimeUnit.MILLISECONDS)
            Thread.sleep(15L)
        }
        assertTrue("Overlay must become visible", controller.isOverlayShowing())
    }

    private fun findWebView(view: View): WebView? {
        if (view is WebView) return view
        if (view is android.view.ViewGroup) {
            for (i in 0 until view.childCount) {
                findWebView(view.getChildAt(i))?.let { return it }
            }
        }
        return null
    }
}
