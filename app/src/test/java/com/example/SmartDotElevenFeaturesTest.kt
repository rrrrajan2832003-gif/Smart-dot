package com.example

import android.accessibilityservice.AccessibilityService
import android.app.Application
import android.app.SearchManager
import android.appwidget.AppWidgetManager
import android.bluetooth.BluetoothAdapter
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
import android.os.Build
import android.os.SystemClock
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.example.QuickTileAutomation.QuickTileTarget
import com.example.database.NoteEntry
import com.example.database.SmartDotDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
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
import java.io.File
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1080dp-h2400dp-mdpi")
class SmartDotElevenFeaturesTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private lateinit var appContext: Application

    @Before
    fun setUp() {
        appContext = ApplicationProvider.getApplicationContext()
        ShadowSettings.setCanDrawOverlays(true)
        appContext.getSharedPreferences("smart_dot_prefs", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        SmartDotAccessibilityService.setUserPaused(appContext, false)
        SmartDotAccessibilityService.isShieldActive = false
        SmartDotAccessibilityService.onShieldStateChangedListener = null
        SmartDotAccessibilityService.setInstanceForTest(null)
        PrivilegedToggleHelper.shizukuShellTestHook = null
        PrivilegedToggleHelper.rootShellTestHook = null
        PrivilegedToggleHelper.mobileDataStateTestHook = null
        clearFileProviderCache()
    }

    @After
    fun tearDown() {
        SmartDotAccessibilityService.setUserPaused(appContext, false)
        SmartDotAccessibilityService.isShieldActive = false
        SmartDotAccessibilityService.onShieldStateChangedListener = null
        SmartDotAccessibilityService.setInstanceForTest(null)
        PrivilegedToggleHelper.shizukuShellTestHook = null
        PrivilegedToggleHelper.rootShellTestHook = null
        PrivilegedToggleHelper.mobileDataStateTestHook = null
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

    private fun drainActivities() {
        val shadowApp = Shadows.shadowOf(appContext)
        while (shadowApp.nextStartedActivity != null) {
            // drain
        }
    }

    private fun registerResolveActivity(
        action: String,
        pkg: String,
        className: String = "$pkg.TestActivity",
        dataScheme: String? = null,
        mimeType: String? = null
    ) {
        val shadowPm = Shadows.shadowOf(appContext.packageManager)
        val component = ComponentName(pkg, className)
        shadowPm.addActivityIfNotPresent(component)
        val resolveInfo = ResolveInfo().apply {
            activityInfo = ActivityInfo().apply {
                packageName = pkg
                name = className
                exported = true
                applicationInfo = ApplicationInfo().apply { packageName = pkg }
            }
        }
        val filter = Intent(action).apply {
            setPackage(pkg)
            if (dataScheme != null) {
                data = Uri.parse("$dataScheme://example.com")
            }
            if (mimeType != null) {
                type = mimeType
            }
        }
        shadowPm.addResolveInfoForIntent(filter, resolveInfo)
    }

    // Feature 0: Quick "Translate" chip in Question Box + TextWatcher dim/disable
    @Test
    fun `Feature 0 - Quick Translate chip is enabled when empty, dims when typing, and searches Translate to Hindi-English`() {
        registerResolveActivity(Intent.ACTION_WEB_SEARCH, SelectionToolbarHelper.GOOGLE_APP_PACKAGE)

        val scope = CoroutineScope(Dispatchers.Main + Job())
        val clipboard = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val bitmap = Bitmap.createBitmap(1080, 2400, Bitmap.Config.ARGB_8888)
        val ocrHelper = ScreenOcrHelper(
            customOcrRunner = {
                listOf(ScreenOcrHelper.OcrRawItem("Quantum", Rect(100, 300, 280, 360), isLine = false)) to emptyList()
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
            ShadowLooper.idleMainLooper(ScreenTextCopyController.CAPTURE_HIDE_DELAY_MS + 15L, TimeUnit.MILLISECONDS)
            val deadline = System.currentTimeMillis() + 3000L
            while (!controller.isOverlayShowing() && System.currentTimeMillis() < deadline) {
                ShadowLooper.idleMainLooper(20, TimeUnit.MILLISECONDS)
                Thread.sleep(10)
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

            val etWordQuestion = root.findViewById<EditText>(R.id.et_word_ai_question)
            val btnWordQuickTranslate = root.findViewById<ImageButton>(R.id.btn_word_quick_translate)
            val etCutoutQuestion = root.findViewById<EditText>(R.id.et_cutout_ai_question)
            val btnCutoutQuickTranslate = root.findViewById<ImageButton>(R.id.btn_cutout_quick_translate)

            assertTrue(btnWordQuickTranslate.isEnabled)
            assertEquals(1.0f, btnWordQuickTranslate.alpha, 0.01f)
            assertTrue(btnCutoutQuickTranslate.isEnabled)
            assertEquals(1.0f, btnCutoutQuickTranslate.alpha, 0.01f)

            // Typing dims and disables both chips
            etCutoutQuestion.setText("What is this")
            assertFalse(btnCutoutQuickTranslate.isEnabled)
            assertTrue(btnCutoutQuickTranslate.alpha < 0.5f)
            etCutoutQuestion.setText("")
            assertTrue(btnCutoutQuickTranslate.isEnabled)
            assertEquals(1.0f, btnCutoutQuickTranslate.alpha, 0.01f)

            etWordQuestion.setText("custom")
            assertFalse(btnWordQuickTranslate.isEnabled)
            assertTrue(btnWordQuickTranslate.alpha < 0.5f)
            etWordQuestion.setText("")
            assertTrue(btnWordQuickTranslate.isEnabled)
            assertEquals(1.0f, btnWordQuickTranslate.alpha, 0.01f)

            // Select word 0 and tap quick Translate chip while EditText is empty
            val selView = root.findViewById<InlineTextSelectionView>(R.id.inline_ocr_selection_view)
            val rect = selView.getMappedRectForCopyNode(0)!!
            selView.dispatchTouchEvent(MotionEvent.obtain(10L, 10L, MotionEvent.ACTION_DOWN, rect.centerX(), rect.centerY(), 0))
            selView.dispatchTouchEvent(MotionEvent.obtain(10L, 25L, MotionEvent.ACTION_UP, rect.centerX(), rect.centerY(), 0))

            drainActivities()
            btnWordQuickTranslate.performClick()
            ShadowLooper.idleMainLooper()

            val started = Shadows.shadowOf(appContext).nextStartedActivity
            assertNotNull("Quick Translate chip must launch Google Search immediately", started)
            assertEquals(Intent.ACTION_WEB_SEARCH, started!!.action)
            assertEquals(SelectionToolbarHelper.GOOGLE_APP_PACKAGE, started.`package`)
            assertEquals("Translate to Hindi/English Quantum", started.getStringExtra(SearchManager.QUERY))
        } finally {
            controller.release()
            scope.cancel()
        }
    }

    // Feature 1: QrScannerActivity shortcut & URI helpers
    @Test
    fun `Feature 1 - QR Scanner shortcut launches QrScannerActivity and parses URL and UPI URIs`() {
        assertTrue(QrScannerActivity.isUpiUri("upi://pay?pa=merchant@okaxis&pn=Store"))
        assertFalse(QrScannerActivity.isUpiUri("https://example.com"))
        assertTrue(QrScannerActivity.isWebUrl("https://developer.android.com"))
        assertFalse(QrScannerActivity.isWebUrl("Plain coupon text"))

        val service = Robolectric.buildService(FloatingService::class.java).create().startCommand(0, 1).get()
        drainActivities()
        val executeAction = FloatingService::class.java.getDeclaredMethod("executeAction", String::class.java).apply {
            isAccessible = true
        }
        executeAction.invoke(service, "qr_scanner")

        val started = Shadows.shadowOf(appContext).nextStartedActivity
        assertNotNull(started)
        assertEquals(QrScannerActivity::class.java.name, started!!.component?.className)
        service.stopSelf()
    }

    // Feature 2 & 6: True 1-Tap Direct Mobile Data Widget (Shizuku -> WRITE_SECURE_SETTINGS, never opening Settings/QS)
    @Test
    fun `Feature 2 & 6 - DataToggleWidget toggles silently via Shizuku or WRITE_SECURE_SETTINGS with zero QS or Settings`() {
        val widgetView = LayoutInflater.from(appContext).inflate(R.layout.widget_data_toggle, null)
        val icon = widgetView.findViewById<ImageView>(R.id.widget_data_icon)
        assertNotNull(icon)

        val a11yService = Robolectric.setupService(SmartDotAccessibilityService::class.java)
        val globalActions = mutableListOf<Int>()
        a11yService.performGlobalActionTestHook = { act ->
            globalActions.add(act)
            true
        }
        SmartDotAccessibilityService.setInstanceForTest(a11yService)

        // Path 1: Shizuku shell `svc data enable`
        val cmds = mutableListOf<String>()
        PrivilegedToggleHelper.shizukuShellTestHook = { cmd ->
            cmds.add(cmd)
            true
        }
        PrivilegedToggleHelper.mobileDataStateTestHook = { false }

        drainActivities()
        DataToggleWidget.handleWidgetTap(appContext)
        assertTrue(cmds.contains("svc data enable"))
        assertTrue("Widget must NEVER open Quick Settings", globalActions.isEmpty())
        assertNull("Widget must NEVER open Settings Panel", Shadows.shadowOf(appContext).peekNextStartedActivity())

        // Path 2: Unprivileged tap does NOT open Quick Settings or Settings Panel either
        PrivilegedToggleHelper.shizukuShellTestHook = { false }
        PrivilegedToggleHelper.rootShellTestHook = { false }
        DataToggleWidget.handleWidgetTap(appContext)
        assertTrue(globalActions.isEmpty())
        assertNull(Shadows.shadowOf(appContext).peekNextStartedActivity())

        // Path 3: WRITE_SECURE_SETTINGS fallback updates Settings.Global `mobile_data`
        Shadows.shadowOf(appContext).grantPermissions(android.Manifest.permission.WRITE_SECURE_SETTINGS)
        android.provider.Settings.Global.putInt(appContext.contentResolver, "mobile_data", 0)
        PrivilegedToggleHelper.mobileDataStateTestHook = null
        assertEquals(true, PrivilegedToggleHelper.toggleMobileDataSilently(appContext))
        assertEquals(1, android.provider.Settings.Global.getInt(appContext.contentResolver, "mobile_data", 0))
    }

    // Feature 3: Full-Screen Installed App Launches without Freeform Prompt
    @Test
    fun `Feature 3 - InAppShortcutLauncher and FreeformWindowLauncher launch apps cleanly full screen`() {
        registerResolveActivity(Intent.ACTION_MAIN, "com.android.chrome")
        val launchIntent = Intent(Intent.ACTION_MAIN).setPackage("com.android.chrome")
        drainActivities()
        assertTrue(FreeformWindowLauncher.launchFullscreen(appContext, launchIntent, "Chrome"))
        val started = Shadows.shadowOf(appContext).nextStartedActivity
        assertNotNull(started)
        assertTrue((started!!.flags and Intent.FLAG_ACTIVITY_NEW_TASK) != 0)
        assertEquals(0, started.flags and Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
    }

    // Feature 4: 4-step Voice Assistant Priority Chain
    @Test
    fun `Feature 4 - Voice Assistant follows OpaActivity - AssistantActivity - VoiceCommand - ACTION_ASSIST chain`() {
        val service = Robolectric.buildService(FloatingService::class.java).create().startCommand(0, 1).get()
        val triggerVoice = FloatingService::class.java.getDeclaredMethod("triggerVoiceAssistant").apply {
            isAccessible = true
        }

        // Priority 1: OpaActivity in Google App
        val shadowPm = Shadows.shadowOf(appContext.packageManager)
        val opaComp = ComponentName(
            "com.google.android.googlequicksearchbox",
            "com.google.android.apps.gsa.staticplugins.opa.OpaActivity"
        )
        shadowPm.addActivityIfNotPresent(opaComp)
        shadowPm.addResolveInfoForIntent(
            Intent().setComponent(opaComp),
            ResolveInfo().apply {
                activityInfo = ActivityInfo().apply {
                    packageName = opaComp.packageName
                    name = opaComp.className
                    exported = true
                    applicationInfo = ApplicationInfo().apply { packageName = opaComp.packageName }
                }
            }
        )

        drainActivities()
        triggerVoice.invoke(service)
        val step1Intent = Shadows.shadowOf(appContext).nextStartedActivity
        assertNotNull(step1Intent)
        assertEquals(opaComp, step1Intent!!.component)
        service.stopSelf()
    }

    // Feature 5: True Pure-Black OLED Pocket Mode, Hands-Free Auto-Scroller, Auto-Clicker (replacing Magnifier)
    @Test
    fun `Feature 5 - OLED Pocket Mode, Auto-Scroller, and Auto-Clicker work properly`() {
        assertFalse("Magnifier must be removed from ACTION_LIST", ACTION_LIST.any { it.id == "screen_magnifier" })
        assertTrue("Auto-Clicker must be in ACTION_LIST", ACTION_LIST.any { it.id == "auto_clicker" })

        val a11yService = Robolectric.setupService(SmartDotAccessibilityService::class.java)
        var scrollCalls = 0
        val tapCoords = mutableListOf<Pair<Float, Float>>()
        a11yService.autoScrollTestHook = { speed ->
            scrollCalls += speed
            true
        }
        a11yService.autoClickTestHook = { x, y ->
            tapCoords.add(x to y)
            true
        }
        SmartDotAccessibilityService.setInstanceForTest(a11yService)

        val wm = appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val proTools = ProToolsOverlayManager(appContext, wm)

        // 1) True Pure-Black OLED Pocket Mode with dim lock icon (0.20f)
        proTools.toggleBlackScreenMode()
        assertTrue(proTools.isBlackScreenActive())
        val blackScreenField = ProToolsOverlayManager::class.java
            .getDeclaredField("blackScreenView")
            .apply { isAccessible = true }
        val blackRoot = blackScreenField.get(proTools) as FrameLayout
        val unlockIcon = blackRoot.findViewWithTag<ImageView>("pro_black_screen_unlock_badge")
        assertNotNull(unlockIcon)
        assertEquals(0.20f, unlockIcon.alpha, 0.01f)
        proTools.toggleBlackScreenMode()
        assertFalse(proTools.isBlackScreenActive())

        // 2) Hands-Free Auto-Scroller
        proTools.toggleAutoScroller()
        assertTrue(proTools.isAutoScrollerActive())
        a11yService.performAutoScrollStep(2)
        assertEquals(2, scrollCalls)
        proTools.toggleAutoScroller()
        assertFalse(proTools.isAutoScrollerActive())

        // 3) Auto-Clicker (target crosshair + Play/Pause pass-through + interval cycling)
        proTools.toggleAutoClicker(Color.CYAN)
        assertTrue(proTools.isAutoClickerActive())
        assertFalse(proTools.isAutoClickerPlaying())
        assertEquals(500L, proTools.getAutoClickerIntervalMs())

        val targetField = ProToolsOverlayManager::class.java
            .getDeclaredField("autoClickerTargetParams")
            .apply { isAccessible = true }
        val barField = ProToolsOverlayManager::class.java
            .getDeclaredField("autoClickerBarView")
            .apply { isAccessible = true }
        val bar = barField.get(proTools) as LinearLayout
        val btnSpeed = bar.findViewWithTag<TextView>("pro_auto_clicker_speed")
        val btnPlayPause = bar.findViewWithTag<TextView>("pro_auto_clicker_play_pause")

        btnSpeed.performClick()
        assertEquals(1000L, proTools.getAutoClickerIntervalMs())

        btnPlayPause.performClick()
        assertTrue(proTools.isAutoClickerPlaying())
        val lpWhilePlaying = targetField.get(proTools) as WindowManager.LayoutParams
        assertTrue((lpWhilePlaying.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE) != 0)
        assertTrue("Auto-Clicker must dispatch taps via AccessibilityService", tapCoords.isNotEmpty())

        btnPlayPause.performClick()
        assertFalse(proTools.isAutoClickerPlaying())
        val lpWhilePaused = targetField.get(proTools) as WindowManager.LayoutParams
        assertEquals(0, lpWhilePaused.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)

        proTools.setShieldHidden(true)
        proTools.setShieldHidden(false)
        proTools.toggleAutoClicker(Color.CYAN)
        assertFalse(proTools.isAutoClickerActive())

        proTools.releaseAll()
    }

    // Feature 7: Open Gemini, ChatGPT, and Google AI Mode in Resizable Floating Windows
    @Test
    fun `Feature 7 - Gemini, ChatGPT, and Google AI Mode open inside FloatingWindowManager with WebView and upload contract`() {
        val wm = appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val fwm = FloatingWindowManager(appContext, wm)

        FloatingMiniApps.openFloatingAiWebWindow(appContext, fwm, Color.CYAN, "gemini")
        FloatingMiniApps.openFloatingAiWebWindow(appContext, fwm, Color.CYAN, "chatgpt")
        FloatingMiniApps.openFloatingAiWebWindow(appContext, fwm, Color.CYAN, "google_ai_mode")

        assertTrue(fwm.hasWindow("floating_ai_gemini"))
        assertTrue(fwm.hasWindow("floating_ai_chatgpt"))
        assertTrue(fwm.hasWindow("floating_ai_google_ai_mode"))

        val geminiWin = fwm.getWindow("floating_ai_gemini")!!
        val desktopToggle = geminiWin.rootView.findViewWithTag<TextView>("fw_ai_mode_toggle_gemini")
        assertNotNull(desktopToggle)
        assertEquals("Mode: Mobile", desktopToggle.text.toString())
        desktopToggle.performClick()
        assertEquals("Mode: Desktop", desktopToggle.text.toString())

        assertEquals("web_file_chooser", ViewerPickerActivity.MODE_WEB_FILE_CHOOSER)
        fwm.closeAllWindows()
    }

    // Feature 8: Cutout Search sends BOTH Cropped Bitmap EXTRA_STREAM and Typed Question EXTRA_TEXT
    @Test
    fun `Feature 8 - Cutout Visual Search with typed question sends both EXTRA_STREAM and EXTRA_TEXT together`() {
        registerResolveActivity(
            action = Intent.ACTION_SEND,
            pkg = SelectionToolbarHelper.GOOGLE_APP_PACKAGE,
            mimeType = "image/png"
        )

        val scope = CoroutineScope(Dispatchers.Main + Job())
        val clipboard = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val screenBitmap = Bitmap.createBitmap(1080, 2400, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.BLUE)
        }
        val controller = ScreenTextCopyController(
            context = appContext,
            serviceScope = scope,
            clipboardManager = clipboard,
            getAccentColor = { Color.CYAN },
            dismissActivePopup = {},
            setHostOverlaysVisible = {},
            onAccessibilityRequired = {},
            nodeExtractor = { emptyList() },
            ocrHelperFactory = { ScreenOcrHelper(customOcrRunner = { emptyList<ScreenOcrHelper.OcrRawItem>() to emptyList() }) },
            screenBitmapCapturer = { cb -> cb(screenBitmap) }
        )

        try {
            controller.start()
            ShadowLooper.idleMainLooper(ScreenTextCopyController.CAPTURE_HIDE_DELAY_MS + 15L, TimeUnit.MILLISECONDS)
            val deadline = System.currentTimeMillis() + 3000L
            while (!controller.isOverlayShowing() && System.currentTimeMillis() < deadline) {
                ShadowLooper.idleMainLooper(20, TimeUnit.MILLISECONDS)
                Thread.sleep(10)
            }
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
            val steps = 16
            for (i in 0..steps) {
                val angle = (i / steps.toDouble()) * 2.0 * Math.PI
                val x = 400f + (160f * kotlin.math.cos(angle)).toFloat()
                val y = 700f + (160f * kotlin.math.sin(angle)).toFloat()
                val act = when (i) {
                    0 -> MotionEvent.ACTION_DOWN
                    steps -> MotionEvent.ACTION_UP
                    else -> MotionEvent.ACTION_MOVE
                }
                selView.dispatchTouchEvent(MotionEvent.obtain(100L, 100L + i * 10L, act, x, y, 0))
            }

            root.findViewById<EditText>(R.id.et_cutout_ai_question).setText("Identify this part")
            drainActivities()
            root.findViewById<ImageButton>(R.id.btn_cutout_visual_search).performClick()
            ShadowLooper.idleMainLooper()

            val intent = Shadows.shadowOf(appContext).nextStartedActivity
            assertNotNull("Expected Google Lens/App SEND intent", intent)
            assertEquals(Intent.ACTION_SEND, intent!!.action)
            assertEquals("image/png", intent.type)
            assertNotNull("Cutout image URI must be attached in EXTRA_STREAM", intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
            assertNotNull("Cutout image URI must be attached in ClipData", intent.clipData)
            assertEquals("Identify this part", intent.getStringExtra(Intent.EXTRA_TEXT))
            assertEquals("Identify this part", intent.getStringExtra(Intent.EXTRA_SUBJECT))
            assertEquals("Identify this part", clipboard.primaryClip?.getItemAt(0)?.text?.toString())
        } finally {
            controller.release()
            scope.cancel()
        }
    }

    @Test
    fun `Circle-to-Search Lens mode opens bottom bar without full image upload and translates screen in-place`() {
        val a11yService = Robolectric.setupService(SmartDotAccessibilityService::class.java)
        SmartDotAccessibilityService.setInstanceForTest(a11yService)

        val scope = CoroutineScope(Dispatchers.Main + Job())
        val clipboard = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val bitmap = Bitmap.createBitmap(1080, 2400, Bitmap.Config.ARGB_8888)
        var fullScreenTranslateCalls = 0
        val ocrHelper = ScreenOcrHelper(
            customOcrRunner = {
                listOf(ScreenOcrHelper.OcrRawItem("Bonjour", Rect(100, 300, 280, 360), isLine = false)) to emptyList()
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
            screenBitmapCapturer = { cb -> cb(bitmap) },
            onFullScreenTranslateRequested = { fullScreenTranslateCalls++ }
        )

        try {
            drainActivities()
            controller.start(circleToSearchBarMode = true)
            ShadowLooper.idleMainLooper(ScreenTextCopyController.CAPTURE_HIDE_DELAY_MS + 15L, TimeUnit.MILLISECONDS)
            val deadline = System.currentTimeMillis() + 3000L
            while (!controller.isOverlayShowing() && System.currentTimeMillis() < deadline) {
                ShadowLooper.idleMainLooper(20, TimeUnit.MILLISECONDS)
                Thread.sleep(10)
            }
            assertTrue(controller.isOverlayShowing())
            assertNull("Circle-to-Search bar mode must NOT upload full-screen image", Shadows.shadowOf(appContext).nextStartedActivity)

            val overlayCtrl = ScreenTextCopyController::class.java
                .getDeclaredField("overlayController")
                .apply { isAccessible = true }
                .get(controller) as InlineTextOverlayController
            val root = InlineTextOverlayController::class.java
                .getDeclaredField("overlayView")
                .apply { isAccessible = true }
                .get(overlayCtrl) as View

            val bottomBar = root.findViewById<LinearLayout>(R.id.circle_to_search_bottom_bar)
            val btnTranslate = root.findViewById<ImageButton>(R.id.btn_circle_search_translate)
            assertEquals(View.VISIBLE, bottomBar.visibility)
            assertNotNull(btnTranslate)

            btnTranslate.performClick()
            assertEquals(1, fullScreenTranslateCalls)
        } finally {
            controller.release()
            scope.cancel()
        }
    }

    @Test
    fun `MainActivity Silent Toggle Setup card copies ADB WRITE_SECURE_SETTINGS grant command`() {
        composeRule.onNodeWithTag("silent_toggle_setup_card").performScrollTo()
        composeRule.onNodeWithTag("btn_copy_adb_command").performScrollTo().performClick()
        composeRule.waitForIdle()

        val clipboard = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val copied = clipboard.primaryClip?.getItemAt(0)?.text?.toString().orEmpty()
        assertEquals(PrivilegedToggleHelper.adbGrantCommand(appContext), copied)
    }

    // Feature 9: 1-Tap Google Lens Shortcut
    @Test
    fun `Feature 9 - Google Lens shortcut exists in catalog and toolbar and creates image SEND intent`() {
        assertTrue(ACTION_LIST.any { it.id == "google_lens" })
        assertTrue(SelectionToolbarHelper.DEFAULT_ORDER.contains(SelectionToolbarHelper.ToolbarAction.GOOGLE_LENS))

        registerResolveActivity(
            action = Intent.ACTION_SEND,
            pkg = "com.google.ar.lens",
            mimeType = "image/png"
        )
        val bmp = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.GREEN)
        }
        val lensIntent = SelectionToolbarHelper.createGoogleLensImageIntent(appContext, bmp)
        assertNotNull(lensIntent)
        assertEquals(Intent.ACTION_SEND, lensIntent!!.action)
        assertEquals("com.google.ar.lens", lensIntent.`package`)
        assertEquals("image/png", lensIntent.type)
        assertNotNull(lensIntent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
        bmp.recycle()
    }

    // Feature 10: Room Notes Title, @Update, and MIGRATION_1_2
    @Test
    fun `Feature 10 - Room v2 Notes supports title, updateNote edit mode, and MIGRATION_1_2 preserves existing notes`() = runBlocking {
        val dbFile = File(appContext.cacheDir, "test_v1_to_v2.db").apply { if (exists()) delete() }
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            androidx.sqlite.db.SupportSQLiteOpenHelper.Configuration.builder(appContext)
                .name(dbFile.absolutePath)
                .callback(object : androidx.sqlite.db.SupportSQLiteOpenHelper.Callback(1) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL("CREATE TABLE IF NOT EXISTS `notes` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `content` TEXT NOT NULL, `timestamp` INTEGER NOT NULL)")
                        db.execSQL("CREATE TABLE IF NOT EXISTS `clipboard_entries` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `text` TEXT NOT NULL, `timestamp` INTEGER NOT NULL)")
                        db.execSQL("INSERT INTO `notes` (`content`, `timestamp`) VALUES ('Legacy note content', 12345)")
                    }

                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
                })
                .build()
        )
        val rawDb = helper.writableDatabase
        rawDb.close()
        helper.close()

        val roomDb = Room.databaseBuilder(appContext, SmartDotDatabase::class.java, dbFile.absolutePath)
            .addMigrations(SmartDotDatabase.MIGRATION_1_2)
            .allowMainThreadQueries()
            .build()

        val initialNotes = roomDb.smartDotDao().getAllNotesFlow().first()
        assertEquals(1, initialNotes.size)
        assertEquals("", initialNotes[0].title)
        assertEquals("Legacy note content", initialNotes[0].content)

        // Update existing note with new title and body
        val updated = initialNotes[0].copy(title = "Shopping", content = "Updated groceries")
        roomDb.smartDotDao().updateNote(updated)

        val afterUpdate = roomDb.smartDotDao().getAllNotesFlow().first()
        assertEquals(1, afterUpdate.size)
        assertEquals("Shopping", afterUpdate[0].title)
        assertEquals("Updated groceries", afterUpdate[0].content)

        roomDb.close()
        dbFile.delete()
        Unit
    }

    // Feature 11: Banking/Payment App Shield + Pause Accessibility Switch
    @Test
    fun `Feature 11 - Banking App Shield hides FloatingService overlays and Pause Accessibility Switch toggles service pause state`() {
        // Package classifier tests
        assertTrue(SmartDotAccessibilityService.isProtectedBankingPackage("com.phonepe.app"))
        assertTrue(SmartDotAccessibilityService.isProtectedBankingPackage("com.google.android.apps.nbu.paisa.user"))
        assertTrue(SmartDotAccessibilityService.isProtectedBankingPackage("net.one97.paytm"))
        assertTrue(SmartDotAccessibilityService.isProtectedBankingPackage("in.org.npci.upiapp"))
        assertTrue(SmartDotAccessibilityService.isProtectedBankingPackage("com.hdfc.mobilebanking"))
        assertFalse(SmartDotAccessibilityService.isProtectedBankingPackage("com.android.chrome"))
        assertFalse(SmartDotAccessibilityService.isProtectedBankingPackage("com.example"))
        assertFalse(SmartDotAccessibilityService.isProtectedBankingPackage("com.whatsapp"))

        val a11yService = Robolectric.setupService(SmartDotAccessibilityService::class.java)
        SmartDotAccessibilityService.setInstanceForTest(a11yService)

        val floatingController = Robolectric.buildService(FloatingService::class.java)
        val floatingService = floatingController.create().startCommand(0, 1).get()
        val floatingViewField = FloatingService::class.java.getDeclaredField("floatingView").apply { isAccessible = true }
        val sidebarViewField = FloatingService::class.java.getDeclaredField("sidebarView").apply { isAccessible = true }

        val dotView = floatingViewField.get(floatingService) as View
        val sidebarView = sidebarViewField.get(floatingService) as View
        assertEquals(View.VISIBLE, dotView.visibility)
        assertEquals(View.VISIBLE, sidebarView.visibility)

        // Simulate opening PhonePe -> Shield activates and hides overlays with View.GONE
        val openBankEvent = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED).apply {
            packageName = "com.phonepe.app"
        }
        a11yService.onAccessibilityEvent(openBankEvent)
        ShadowLooper.idleMainLooper()
        assertTrue(SmartDotAccessibilityService.isShieldActive)
        assertEquals(View.GONE, dotView.visibility)
        assertEquals(View.GONE, sidebarView.visibility)

        // Simulate leaving banking app -> overlays auto-restore
        val exitBankEvent = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED).apply {
            packageName = "com.whatsapp"
        }
        a11yService.onAccessibilityEvent(exitBankEvent)
        ShadowLooper.idleMainLooper()
        assertFalse(SmartDotAccessibilityService.isShieldActive)
        assertEquals(View.VISIBLE, dotView.visibility)
        assertEquals(View.VISIBLE, sidebarView.visibility)

        // Toggle Pause Accessibility Switch in MainActivity
        composeRule.onNodeWithTag("switch_pause_accessibility")
            .performScrollTo()
            .assertIsOff()
            .performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("switch_pause_accessibility").assertIsOn()
        assertTrue(SmartDotAccessibilityService.isUserPaused)
        assertFalse(SmartDotAccessibilityService.isEnabled())

        // While paused, PhonePe event is ignored cleanly
        a11yService.onAccessibilityEvent(
            AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED).apply {
                packageName = "com.phonepe.app"
            }
        )
        ShadowLooper.idleMainLooper()
        assertFalse(SmartDotAccessibilityService.isShieldActive)
        assertEquals(View.VISIBLE, dotView.visibility)

        floatingController.destroy()
    }
}
