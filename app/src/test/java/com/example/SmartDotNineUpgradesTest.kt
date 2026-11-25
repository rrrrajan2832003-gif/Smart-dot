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
import android.provider.Settings
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.webkit.WebView
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
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
import org.robolectric.shadows.ShadowSettings
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1080dp-h2400dp-mdpi")
class SmartDotNineUpgradesTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private lateinit var appContext: Application

    @Before
    fun setUp() {
        appContext = ApplicationProvider.getApplicationContext()
        ShadowSettings.setCanDrawOverlays(true)
        appContext.getSharedPreferences(SelectionToolbarHelper.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        SmartDotAccessibilityService.setInstanceForTest(null)
    }

    @After
    fun tearDown() {
        SmartDotAccessibilityService.setInstanceForTest(null)
    }

    @Test
    fun `Floating Browser Bookmark Star and Bookmarks List add, open, and delete bookmarks persistently`() {
        val wm = appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val fwm = FloatingWindowManager(appContext, wm)

        val browserWin = FloatingMiniApps.openFloatingBrowser(
            context = appContext,
            manager = fwm,
            accentColor = Color.CYAN,
            initialUrl = "https://kotlinlang.org"
        )!!

        val starBtn = browserWin.rootView.findViewWithTag<TextView>("fw_browser_bookmark_star")
        val listBtn = browserWin.rootView.findViewWithTag<TextView>("fw_browser_bookmarks_list")
        val sheet = browserWin.rootView.findViewWithTag<LinearLayout>("fw_browser_bookmarks_sheet")
        assertNotNull(starBtn)
        assertNotNull(listBtn)
        assertNotNull(sheet)

        assertEquals("☆", starBtn.text.toString())
        assertEquals(View.GONE, sheet.visibility)

        // Tap star -> saves bookmark and updates star icon
        starBtn.performClick()
        assertEquals("★", starBtn.text.toString())
        assertTrue(FloatingMiniApps.isBookmarked(appContext, "https://kotlinlang.org"))
        assertEquals(1, FloatingMiniApps.getBrowserBookmarks(appContext).size)

        // Open bookmarks sheet -> row 0 visible
        listBtn.performClick()
        assertEquals(View.VISIBLE, sheet.visibility)
        val row0 = sheet.findViewWithTag<View>("fw_browser_bookmark_row_0")
        val del0 = sheet.findViewWithTag<View>("fw_browser_bookmark_delete_0")
        assertNotNull(row0)
        assertNotNull(del0)

        // Tap row 0 -> loads URL into active tab and closes sheet
        row0.performClick()
        assertEquals(View.GONE, sheet.visibility)

        // Re-open sheet and delete bookmark
        listBtn.performClick()
        sheet.findViewWithTag<View>("fw_browser_bookmark_delete_0").performClick()
        assertEquals("☆", starBtn.text.toString())
        assertTrue(FloatingMiniApps.getBrowserBookmarks(appContext).isEmpty())

        fwm.closeAllWindows()
    }

    @Test
    fun `MainActivity edits Quick Prompt 1 & 2 and inline Preset buttons plus 2nd Dedicated Floating AI Question Box work`() {
        val shadowPm = Shadows.shadowOf(appContext.packageManager)
        val resolveInfo = ResolveInfo().apply {
            activityInfo = ActivityInfo().apply {
                packageName = SelectionToolbarHelper.GOOGLE_APP_PACKAGE
                name = "${SelectionToolbarHelper.GOOGLE_APP_PACKAGE}.SearchActivity"
                exported = true
                applicationInfo = ApplicationInfo().apply { packageName = SelectionToolbarHelper.GOOGLE_APP_PACKAGE }
            }
        }
        shadowPm.addResolveInfoForIntent(
            Intent(Intent.ACTION_WEB_SEARCH).setPackage(SelectionToolbarHelper.GOOGLE_APP_PACKAGE),
            resolveInfo
        )

        // Edit custom prompts in MainActivity Settings UI
        composeRule.onNodeWithTag("input_quick_prompt_1")
            .performScrollTo()
            .performTextReplacement("Hindi meaning of ")
        composeRule.onNodeWithTag("input_quick_prompt_2")
            .performScrollTo()
            .performTextReplacement("Synonym of ")
        composeRule.waitForIdle()

        val prefs = composeRule.activity.getSharedPreferences(SelectionToolbarHelper.PREFS_NAME, Context.MODE_PRIVATE)
        assertEquals("Hindi meaning of ", SelectionToolbarHelper.getQuickPrompt1(prefs))
        assertEquals("Synonym of ", SelectionToolbarHelper.getQuickPrompt2(prefs))

        // Start inline overlay and test customized Preset 1 + Dedicated 2nd Browser AI Question Box
        val scope = CoroutineScope(Dispatchers.Main + Job())
        val clipboard = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val bitmap = Bitmap.createBitmap(1080, 2400, Bitmap.Config.ARGB_8888)
        val ocrHelper = ScreenOcrHelper(
            customOcrRunner = {
                listOf(ScreenOcrHelper.OcrRawItem("Nebula", Rect(100, 300, 280, 360), isLine = false)) to emptyList()
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
            ShadowLooper.idleMainLooper(ScreenTextCopyController.CAPTURE_HIDE_DELAY_MS + 15L, TimeUnit.MILLISECONDS)
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

            // Drain any pending activity
            while (Shadows.shadowOf(appContext).nextStartedActivity != null) { /* drain */ }

            // Preset 1 uses customized "Hindi meaning of "
            root.findViewById<ImageButton>(R.id.btn_word_preset_1).performClick()
            ShadowLooper.idleMainLooper()
            val searchIntent = Shadows.shadowOf(appContext).nextStartedActivity
            assertNotNull(searchIntent)
            assertEquals("Hindi meaning of Nebula", searchIntent!!.getStringExtra(SearchManager.QUERY))

            // Re-open and test 2nd dedicated question box -> Floating Browser Google AI Mode
            controller.start()
            ShadowLooper.idleMainLooper(ScreenTextCopyController.CAPTURE_HIDE_DELAY_MS + 15L, TimeUnit.MILLISECONDS)
            val root2 = InlineTextOverlayController::class.java
                .getDeclaredField("overlayView")
                .apply { isAccessible = true }
                .get(overlayCtrl) as View
            root2.measure(
                View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(2400, View.MeasureSpec.EXACTLY)
            )
            root2.layout(0, 0, 1080, 2400)
            val selView2 = root2.findViewById<InlineTextSelectionView>(R.id.inline_ocr_selection_view)
            val rect2 = selView2.getMappedRectForCopyNode(0)!!
            selView2.dispatchTouchEvent(MotionEvent.obtain(10L, 10L, MotionEvent.ACTION_DOWN, rect2.centerX(), rect2.centerY(), 0))
            selView2.dispatchTouchEvent(MotionEvent.obtain(10L, 25L, MotionEvent.ACTION_UP, rect2.centerX(), rect2.centerY(), 0))

            val etBrowserAi = root2.findViewById<EditText>(R.id.et_word_browser_ai_question)
            val btnBrowserAiSend = root2.findViewById<ImageButton>(R.id.btn_word_browser_ai_send)
            etBrowserAi.setText("Origin story")
            btnBrowserAiSend.performClick()
            ShadowLooper.idleMainLooper()

            assertTrue("2nd dedicated box must open Floating Browser", fwm.hasWindow("floating_browser"))
            val browserRoot = fwm.getWindow("floating_browser")!!.rootView
            val webView = findWebView(browserRoot)
            assertNotNull(webView)
            assertTrue(webView!!.url.orEmpty().contains("udm=50"))
            assertTrue(webView.url.orEmpty().contains("Origin+story+Nebula"))
        } finally {
            fwm.closeAllWindows()
            controller.release()
            scope.cancel()
        }
    }

    @Test
    fun `Accessibility Settings 1-tap shortcut & ChatGPT Floating Browser shortcut execute properly`() {
        assertTrue(ACTION_LIST.any { it.id == "accessibility_settings" })

        val service = Robolectric.buildService(FloatingService::class.java)
            .create()
            .startCommand(0, 1)
            .get()
        val executeAction = FloatingService::class.java
            .getDeclaredMethod("executeAction", String::class.java)
            .apply { isAccessible = true }

        while (Shadows.shadowOf(appContext).nextStartedActivity != null) { /* drain */ }

        executeAction.invoke(service, "accessibility_settings")
        val a11yIntent = Shadows.shadowOf(appContext).nextStartedActivity
        assertNotNull("accessibility_settings shortcut must launch Accessibility Settings", a11yIntent)
        assertEquals(Settings.ACTION_ACCESSIBILITY_SETTINGS, a11yIntent!!.action)

        executeAction.invoke(service, "chatgpt")
        val fwm = FloatingService::class.java
            .getDeclaredField("floatingWindowManager")
            .apply { isAccessible = true }
            .get(service) as FloatingWindowManager
        assertTrue(fwm.hasWindow("floating_browser"))
        assertNull("chatgpt shortcut must open inside Floating Browser, not external app", Shadows.shadowOf(appContext).nextStartedActivity)

        fwm.closeAllWindows()
        service.stopSelf()
    }

    @Test
    fun `Multi-point Sequential Auto-Clicker dispatches taps in 1-2-3 order across draggable numbered targets`() {
        val a11yService = Robolectric.setupService(SmartDotAccessibilityService::class.java)
        val taps = mutableListOf<Pair<Float, Float>>()
        a11yService.autoClickTestHook = { x, y ->
            taps.add(x to y)
            true
        }
        SmartDotAccessibilityService.setInstanceForTest(a11yService)

        val wm = appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val proTools = ProToolsOverlayManager(appContext, wm)

        proTools.toggleAutoClicker(Color.CYAN)
        val bar = ProToolsOverlayManager::class.java
            .getDeclaredField("autoClickerBarView")
            .apply { isAccessible = true }
            .get(proTools) as LinearLayout
        val btnAdd = bar.findViewWithTag<TextView>("pro_auto_clicker_add")
        val btnPlayPause = bar.findViewWithTag<TextView>("pro_auto_clicker_play_pause")

        btnAdd.performClick()
        btnAdd.performClick()
        val targets = proTools.getAutoClickerTargets()
        assertEquals(3, targets.size)
        assertEquals("1", targets[0].labelView.text.toString())
        assertEquals("2", targets[1].labelView.text.toString())
        assertEquals("3", targets[2].labelView.text.toString())

        // Distinct coordinates per target so we can verify sequential 1 -> 2 -> 3 order
        targets[0].params.x = 100; targets[0].params.y = 200
        targets[1].params.x = 300; targets[1].params.y = 400
        targets[2].params.x = 500; targets[2].params.y = 600

        btnPlayPause.performClick()
        ShadowLooper.idleMainLooper(1200L, TimeUnit.MILLISECONDS)

        assertTrue("Expected at least 3 sequential taps", taps.size >= 3)
        assertTrue("Tap 1 must hit target 1", taps[0].first < taps[1].first && taps[1].first < taps[2].first)
        assertTrue("Tap 1..3 Y must increase strictly", taps[0].second < taps[1].second && taps[1].second < taps[2].second)

        proTools.releaseAll()
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
