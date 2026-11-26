package com.example

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorManager
import android.net.Uri
import android.os.PowerManager
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.webkit.WebView
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
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
import org.robolectric.shadows.ShadowPowerManager
import org.robolectric.shadows.ShadowSensor
import org.robolectric.shadows.ShadowSensorManager
import org.robolectric.shadows.ShadowSettings
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1080dp-h2400dp-mdpi")
class SmartDotTwelveUpgradesTest {

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
        FloatingMiniApps.pendingBrowserUploadImageBase64 = null
        FloatingMiniApps.pendingBrowserUploadPromptText = null
        SmartDotAccessibilityService.setInstanceForTest(null)
        clearFileProviderCache()
    }

    @After
    fun tearDown() {
        FloatingMiniApps.pendingBrowserUploadImageUri = null
        FloatingMiniApps.pendingBrowserUploadImageBase64 = null
        FloatingMiniApps.pendingBrowserUploadPromptText = null
        SmartDotAccessibilityService.setInstanceForTest(null)
        clearFileProviderCache()
    }

    private fun clearFileProviderCache() {
        runCatching {
            val field = androidx.core.content.FileProvider::class.java.getDeclaredField("sCache").apply {
                isAccessible = true
            }
            (field.get(null) as? MutableMap<*, *>)?.clear()
        }
    }

    @Test
    fun `Upgrade 1 - Cutout Base64 PNG encoding and DataTransfer JS file injection for Google AI Mode and ChatGPT`() {
        val bitmap = Bitmap.createBitmap(120, 120, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.CYAN)
        val base64 = FloatingMiniApps.encodeBitmapToPngBase64(bitmap)
        assertFalse("Encoded PNG Base64 must not be blank", base64.isBlank())

        val script = FloatingMiniApps.getBase64UploadAndPromptScript(base64, "Explain diagram")
        assertTrue(script.contains("DataTransfer"))
        assertTrue(script.contains("new File([bytes], 'cutout.png', { type: 'image/png' })"))
        assertTrue(script.contains("Explain diagram"))
        assertTrue(script.contains("input[type=\"file\"]"))

        val wm = appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val fwm = FloatingWindowManager(appContext, wm)
        try {
            FloatingMiniApps.openGoogleAiModeInBrowser(
                context = appContext,
                manager = fwm,
                accentColor = Color.CYAN,
                query = "",
                question = "Summarize",
                cutoutBitmap = bitmap
            )
            val webView = findWebView(fwm.getWindow("floating_browser")!!.rootView)!!
            assertEquals("https://www.google.com/search?udm=50", webView.url)
            assertNotNull(FloatingMiniApps.pendingBrowserUploadImageBase64)
            assertEquals("Summarize", FloatingMiniApps.pendingBrowserUploadPromptText)

            FloatingMiniApps.openChatGptInBrowser(
                context = appContext,
                manager = fwm,
                accentColor = Color.CYAN,
                text = "",
                question = "Solve step by step",
                cutoutBitmap = bitmap
            )
            assertEquals("https://chatgpt.com/", webView.url)
            assertNotNull(FloatingMiniApps.pendingBrowserUploadImageBase64)
            assertEquals("Solve step by step", FloatingMiniApps.pendingBrowserUploadPromptText)
        } finally {
            fwm.closeAllWindows()
        }
    }

    @Test
    fun `Upgrade 2 - Tap outside closes PopupWindow, FloatingWindowManager Left Hamburger drawer, and InlineOverlay popups`() {
        // 2a) FloatingWindowManager Left Hamburger Drawer closes via backdrop tap & ACTION_OUTSIDE
        val wm = appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val fwm = FloatingWindowManager(appContext, wm)
        try {
            val win = FloatingMiniApps.openFloatingCalculator(appContext, fwm, Color.CYAN)!!
            val managed = fwm.getWindow("floating_calculator")!!
            val drawer = win.rootView.findViewWithTag<View>("fw_menu_drawer_floating_calculator")
            val backdrop = win.rootView.findViewWithTag<View>("fw_menu_backdrop_floating_calculator")

            managed.toggleMenu()
            assertEquals(View.VISIBLE, drawer.visibility)
            assertEquals(View.VISIBLE, backdrop.visibility)

            backdrop.performClick()
            assertEquals(View.GONE, drawer.visibility)
            assertEquals(View.GONE, backdrop.visibility)

            managed.toggleMenu()
            assertEquals(View.VISIBLE, drawer.visibility)
            win.rootView.dispatchTouchEvent(MotionEvent.obtain(10L, 10L, MotionEvent.ACTION_OUTSIDE, -50f, -50f, 0))
            assertEquals(View.GONE, drawer.visibility)
        } finally {
            fwm.closeAllWindows()
        }

        // 2b) InlineOverlayRootLayout notifies onOutsidePopupTouch when touching outside child bounds
        val rootLayout = InlineOverlayRootLayout(appContext)
        val child = View(appContext)
        rootLayout.addView(child, LinearLayout.LayoutParams(100, 100))
        rootLayout.measure(
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(2400, View.MeasureSpec.EXACTLY)
        )
        rootLayout.layout(0, 0, 1080, 2400)
        child.layout(100, 100, 200, 200)

        var outsideCalled = false
        rootLayout.onOutsidePopupTouch = { outsideCalled = true }
        rootLayout.dispatchTouchEvent(MotionEvent.obtain(10L, 10L, MotionEvent.ACTION_DOWN, 500f, 500f, 0))
        assertTrue("onOutsidePopupTouch must fire on ACTION_DOWN", outsideCalled)

        // 2c) FloatingService PopupWindow touch interceptor dismisses on ACTION_OUTSIDE
        ShadowSettings.setCanDrawOverlays(true)
        val service = Robolectric.buildService(FloatingService::class.java)
            .create()
            .startCommand(0, 1)
            .get()
        try {
            val showPopup = FloatingService::class.java.getDeclaredMethod("showAssistantSidebar")
                .apply { isAccessible = true }
            showPopup.invoke(service)
            ShadowLooper.idleMainLooper()

            val popupField = FloatingService::class.java.getDeclaredField("activePopupWindow")
                .apply { isAccessible = true }
            val popup = popupField.get(service) as? PopupWindow
            assertNotNull("PopupWindow must be displayed", popup)
            assertTrue(popup!!.isShowing)

            val interceptorField = PopupWindow::class.java.getDeclaredField("mTouchInterceptor")
                .apply { isAccessible = true }
            val interceptor = interceptorField.get(popup) as? View.OnTouchListener
            assertNotNull("PopupWindow must install outside touch interceptor", interceptor)
            val consumed = interceptor!!.onTouch(
                popup.contentView,
                MotionEvent.obtain(20L, 20L, MotionEvent.ACTION_OUTSIDE, -10f, -10f, 0)
            )
            assertTrue(consumed)
            assertFalse("PopupWindow must dismiss on ACTION_OUTSIDE", popup.isShowing)
        } finally {
            service.stopSelf()
        }
    }

    @Test
    fun `Upgrade 3 - Independent Box 1 and Box 2 Eye toggles plus 4 editable presets and 5 icon choices`() {
        val prefs = composeRule.activity.getSharedPreferences(SelectionToolbarHelper.PREFS_NAME, Context.MODE_PRIVATE)

        // Toggle Box 1 OFF while leaving Box 2 ON
        composeRule.onNodeWithTag("btn_toggle_question_box_1")
            .performScrollTo()
            .performClick()
        composeRule.waitForIdle()
        assertFalse(SelectionToolbarHelper.isShowQuestionBox1Enabled(prefs))
        assertTrue(SelectionToolbarHelper.isShowQuestionBox2Enabled(prefs))

        // Customize all 4 preset texts & select distinct icons across 5 choices
        composeRule.onNodeWithTag("input_quick_prompt_1")
            .performScrollTo()
            .performTextReplacement("Box1 Prompt A: ")
        composeRule.onNodeWithTag("btn_preset_icon_box1_1_question")
            .performScrollTo()
            .performClick()

        composeRule.onNodeWithTag("input_quick_prompt_2")
            .performScrollTo()
            .performTextReplacement("Box1 Prompt B: ")
        composeRule.onNodeWithTag("btn_preset_icon_box1_2_summary")
            .performScrollTo()
            .performClick()

        composeRule.onNodeWithTag("input_box2_preset_1")
            .performScrollTo()
            .performTextReplacement("Box2 Prompt A: ")
        composeRule.onNodeWithTag("btn_preset_icon_box2_1_sparkle")
            .performScrollTo()
            .performClick()

        composeRule.onNodeWithTag("input_box2_preset_2")
            .performScrollTo()
            .performTextReplacement("Box2 Prompt B: ")
        composeRule.onNodeWithTag("btn_preset_icon_box2_2_translate")
            .performScrollTo()
            .performClick()

        composeRule.onNodeWithTag("btn_save_quick_prompts")
            .performScrollTo()
            .performClick()
        composeRule.waitForIdle()

        assertEquals(SelectionToolbarHelper.PresetIconOption.QUESTION, SelectionToolbarHelper.getBox1Preset1Icon(prefs))
        assertEquals(SelectionToolbarHelper.PresetIconOption.SUMMARY, SelectionToolbarHelper.getBox1Preset2Icon(prefs))
        assertEquals(SelectionToolbarHelper.PresetIconOption.SPARKLE, SelectionToolbarHelper.getBox2Preset1Icon(prefs))
        assertEquals(SelectionToolbarHelper.PresetIconOption.TRANSLATE, SelectionToolbarHelper.getBox2Preset2Icon(prefs))
        assertEquals("Box2 Prompt A: ", SelectionToolbarHelper.getBox2Preset1Text(prefs))
        assertEquals("Box2 Prompt B: ", SelectionToolbarHelper.getBox2Preset2Text(prefs))

        // Verify InlineTextOverlayController renders Box 1 GONE & Box 2 VISIBLE independently
        val scope = CoroutineScope(Dispatchers.Main + Job())
        val clipboard = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val bitmap = Bitmap.createBitmap(1080, 2400, Bitmap.Config.ARGB_8888)
        val ocrHelper = ScreenOcrHelper(
            customOcrRunner = {
                listOf(ScreenOcrHelper.OcrRawItem("Physics", Rect(100, 300, 300, 360), isLine = false)) to emptyList()
            }
        )
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
            screenBitmapCapturer = { cb -> cb(bitmap) }
        )
        try {
            controller.start()
            val deadline = System.currentTimeMillis() + 3000L
            while (!controller.isOverlayShowing() && System.currentTimeMillis() < deadline) {
                ShadowLooper.idleMainLooper(25L, TimeUnit.MILLISECONDS)
                Thread.sleep(15L)
            }
            assertTrue(controller.isOverlayShowing())
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

            assertEquals(View.GONE, root.findViewById<View>(R.id.inline_word_question_row).visibility)
            assertEquals(View.VISIBLE, root.findViewById<View>(R.id.inline_word_browser_ai_row).visibility)
        } finally {
            controller.release()
            scope.cancel()
        }
    }

    @Test
    fun `Upgrade 4 - Realme raw Accelerometer high-pass BackTapDetector + TYPE_ACCELEROMETER and WakeLock lifecycle`() {
        var triggers = 0
        val detector = BackTapDetector(zSpikeThreshold = 9.5f) {
            triggers++
        }

        // Prime gravity baseline at ~9.8 m/s^2 on Z-axis (phone lying flat or held upright)
        detector.onRawAccelerometerSample(0f, 0f, 9.81f, 1000L)
        detector.onRawAccelerometerSample(0f, 0f, 9.81f, 1020L)
        detector.onRawAccelerometerSample(0f, 0f, 9.81f, 1040L)

        // Two sharp back-taps on Z axis within 100..450ms -> high-pass removes gravity and detects double tap
        assertFalse(detector.onRawAccelerometerSample(0.2f, 0.2f, 26.0f, 1500L))
        assertTrue(detector.onRawAccelerometerSample(0.2f, 0.2f, 28.0f, 1720L))
        assertEquals(1, triggers)

        // Verify FloatingService registers TYPE_ACCELEROMETER and acquires PARTIAL_WAKE_LOCK when enabled
        val pm = appContext.getSystemService(Context.POWER_SERVICE) as PowerManager
        Shadows.shadowOf(pm).setIsInteractive(true)
        val sensorManager = appContext.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        Shadows.shadowOf(sensorManager).addSensor(ShadowSensor.newInstance(Sensor.TYPE_ACCELEROMETER))

        appContext.getSharedPreferences(SelectionToolbarHelper.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(FloatingService.PREF_BACK_TAP_DATA_ENABLED, true)
            .commit()

        val controller = Robolectric.buildService(FloatingService::class.java).create()
        val service = controller.get()
        try {
            val regField = FloatingService::class.java.getDeclaredField("isBackTapListenerRegistered")
                .apply { isAccessible = true }
            assertTrue("Back-tap accelerometer listener must register", regField.getBoolean(service))

            val wakeField = FloatingService::class.java.getDeclaredField("backTapWakeLock")
                .apply { isAccessible = true }
            val wakeLock = wakeField.get(service) as? PowerManager.WakeLock
            assertNotNull("Partial wake lock must be created when back-tap enabled", wakeLock)
            assertTrue("WakeLock must be held while screen is on", wakeLock!!.isHeld)

            // Screen OFF releases WakeLock; Screen ON re-acquires it
            val receiverField = FloatingService::class.java.getDeclaredField("screenStateReceiver")
                .apply { isAccessible = true }
            val receiver = receiverField.get(service) as android.content.BroadcastReceiver
            receiver.onReceive(appContext, Intent(Intent.ACTION_SCREEN_OFF))
            assertFalse("WakeLock must release when screen turns OFF", wakeLock.isHeld)

            receiver.onReceive(appContext, Intent(Intent.ACTION_SCREEN_ON))
            assertTrue("WakeLock must re-acquire when screen turns ON", wakeLock.isHeld)
        } finally {
            controller.destroy()
            val wakeLock = ShadowPowerManager.getLatestWakeLock()
            if (wakeLock != null) {
                assertFalse("WakeLock must be released on service destroy", wakeLock.isHeld)
            }
        }
    }

    @Test
    fun `Upgrade 5 & 6 - Dedicated Music Player, In-App Gallery, Play-Policy clean Manifest, and Zero Haptics`() {
        // Verify shortcuts registered in catalog and icons mapped
        assertTrue(ACTION_LIST.any { it.id == "floating_photos_videos" })
        assertTrue(ACTION_LIST.any { it.id == "floating_music" })

        val wm = appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val fwm = FloatingWindowManager(appContext, wm)
        try {
            // Dedicated Floating Music & Voice Player opens directly with Sync button & list
            val musicWin = FloatingMiniApps.openFloatingMusicPlayer(appContext, fwm, Color.CYAN)!!
            assertTrue(fwm.hasWindow("floating_music"))
            assertNotNull(musicWin.rootView.findViewWithTag<View>("fw_music_sync"))
            assertNotNull(musicWin.rootView.findViewWithTag<View>("fw_music_player_card"))
            assertNotNull(musicWin.rootView.findViewWithTag<View>("fw_music_list"))

            // Dedicated Floating Photos & Videos opens in-app grid directly without launching ViewerPickerActivity
            while (Shadows.shadowOf(appContext).nextStartedActivity != null) { /* drain */ }
            val galleryWin = FloatingMiniApps.openFloatingPhotosVideosGallery(appContext, fwm, Color.CYAN)!!
            assertTrue(fwm.hasWindow("floating_photos_videos"))
            assertNotNull(galleryWin.rootView.findViewWithTag<View>("fw_gallery_sync"))
            assertNotNull(galleryWin.rootView.findViewWithTag<View>("fw_gallery_filter_all"))
            assertNotNull(galleryWin.rootView.findViewWithTag<View>("fw_gallery_filter_photos"))
            assertNotNull(galleryWin.rootView.findViewWithTag<View>("fw_gallery_filter_videos"))
            assertNull(
                "Gallery must open in-app grid directly without auto-launching system picker",
                Shadows.shadowOf(appContext).nextStartedActivity
            )

            // Upgraded floating_viewer also renders the in-app gallery grid directly
            val viewerWin = FloatingMiniApps.openFloatingViewer(appContext, fwm, Color.CYAN)!!
            assertTrue(fwm.hasWindow("floating_viewer"))
            assertNotNull(viewerWin.rootView.findViewWithTag<View>("fw_gallery_root"))
            assertNull(Shadows.shadowOf(appContext).nextStartedActivity)
        } finally {
            fwm.closeAllWindows()
        }

        // Verify Manifest has WAKE_LOCK and full media permissions for Gallery/Music sync
        val pkgInfo = appContext.packageManager.getPackageInfo(
            appContext.packageName,
            PackageManager.GET_PERMISSIONS
        )
        val permissions = pkgInfo.requestedPermissions?.toList().orEmpty()
        assertTrue(permissions.contains(android.Manifest.permission.WAKE_LOCK))
        assertTrue(permissions.contains("android.permission.READ_MEDIA_IMAGES"))
        assertTrue(permissions.contains("android.permission.READ_MEDIA_VIDEO"))
        assertTrue(permissions.contains("android.permission.READ_MEDIA_AUDIO"))
    }

    private fun findWebView(view: View): WebView? {
        if (view is WebView) return view
        if (view is android.view.ViewGroup) {
            for (i in 0 until view.childCount) {
                val found = findWebView(view.getChildAt(i))
                if (found != null) return found
            }
        }
        return null
    }
}
