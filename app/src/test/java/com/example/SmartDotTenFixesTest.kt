package com.example

import android.accessibilityservice.AccessibilityService
import android.app.Application
import android.app.SearchManager
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.graphics.Rect
import android.media.projection.MediaProjection
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.AlarmClock
import android.provider.Settings
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.example.QuickTileAutomation.QuickTileTarget
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
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import org.robolectric.shadows.ShadowSettings
import org.robolectric.shadows.ShadowToast
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SmartDotTenFixesTest {

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
        PrivilegedToggleHelper.shizukuShellTestHook = null
        PrivilegedToggleHelper.rootShellTestHook = null
        PrivilegedToggleHelper.mobileDataStateTestHook = null
        ShadowToast.reset()
    }

    @After
    fun tearDown() {
        PrivilegedToggleHelper.shizukuShellTestHook = null
        PrivilegedToggleHelper.rootShellTestHook = null
        PrivilegedToggleHelper.mobileDataStateTestHook = null
        SmartDotAccessibilityService.setInstanceForTest(null)
    }

    private fun registerResolveActivity(
        action: String,
        packageName: String,
        activityName: String = "$packageName.MainActivity",
        dataScheme: String? = null,
        mimeType: String? = null
    ) {
        val shadowPm = Shadows.shadowOf(appContext.packageManager)
        val probe = Intent(action).apply {
            setPackage(packageName)
            if (dataScheme != null) {
                val uri = Uri.parse("$dataScheme://example.com")
                if (mimeType != null) setDataAndType(uri, mimeType) else data = uri
            } else if (mimeType != null) {
                type = mimeType
            }
        }
        val info = ResolveInfo().apply {
            activityInfo = ActivityInfo().apply {
                this.packageName = packageName
                this.name = activityName
                applicationInfo = ApplicationInfo().apply {
                    this.packageName = packageName
                    this.name = packageName
                }
            }
        }
        shadowPm.addResolveInfoForIntent(probe, info)

        // Also allow getLaunchIntentForPackage(packageName) to succeed
        val mainIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
            setPackage(packageName)
        }
        shadowPm.addResolveInfoForIntent(mainIntent, info)
    }

    private fun drainStartedActivities() {
        val shadowApp = Shadows.shadowOf(appContext)
        while (shadowApp.nextStartedActivity != null) {
            // drain
        }
    }

    private fun getOverlayRoot(controller: ScreenTextCopyController): View {
        val ocField = ScreenTextCopyController::class.java
            .getDeclaredField("overlayController")
            .apply { isAccessible = true }
        val oc = ocField.get(controller) as InlineTextOverlayController
        val viewField = InlineTextOverlayController::class.java
            .getDeclaredField("overlayView")
            .apply { isAccessible = true }
        return viewField.get(oc) as View
    }

    private fun sendMotion(target: View, action: Int, x: Float, y: Float, rawX: Float = x, rawY: Float = y) {
        val now = SystemClock.uptimeMillis()
        val ev = MotionEvent.obtain(now, now, action, x, y, 0)
        // Ensure raw coordinates match test drag deltas cleanly
        ev.offsetLocation(rawX - ev.rawX, rawY - ev.rawY)
        target.dispatchTouchEvent(ev)
        ev.recycle()
    }

    // -------------------------------------------------------------
    // Fix 1: Include Typed Question Box Text in Google Search Button
    // -------------------------------------------------------------

    @Test
    fun `word selection and cutout Google Search both combine question box text with target text`() {
        registerResolveActivity(
            action = Intent.ACTION_WEB_SEARCH,
            packageName = SelectionToolbarHelper.GOOGLE_APP_PACKAGE,
            activityName = "com.google.android.googlequicksearchbox.SearchActivity"
        )

        val scope = CoroutineScope(Dispatchers.Main + Job())
        val clipboard = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val nodes = listOf(
            SmartDotAccessibilityService.ScreenTextNode(
                id = 0,
                text = "Quantum",
                boundsInScreen = Rect(100, 300, 260, 360)
            ),
            SmartDotAccessibilityService.ScreenTextNode(
                id = 1,
                text = "Physics",
                boundsInScreen = Rect(270, 300, 420, 360)
            )
        )

        val controller = ScreenTextCopyController(
            context = appContext,
            serviceScope = scope,
            clipboardManager = clipboard,
            getAccentColor = { 0xFF00E5FF.toInt() },
            dismissActivePopup = {},
            setHostOverlaysVisible = {},
            onAccessibilityRequired = {},
            nodeExtractor = { nodes }
        )

        try {
            controller.start()
            ShadowLooper.idleMainLooper(100, TimeUnit.MILLISECONDS)
            assertTrue(controller.isOverlayShowing())

            val overlayRoot = getOverlayRoot(controller)
            val selView = overlayRoot.findViewById<InlineTextSelectionView>(R.id.inline_ocr_selection_view)
            selView.layout(0, 0, 1080, 1920)

            // Drag highlighter across "Quantum" and "Physics"
            sendMotion(selView, MotionEvent.ACTION_DOWN, 140f, 330f)
            sendMotion(selView, MotionEvent.ACTION_MOVE, 340f, 330f)
            sendMotion(selView, MotionEvent.ACTION_UP, 340f, 330f)

            val etWordQuestion = overlayRoot.findViewById<EditText>(R.id.et_word_ai_question)
            etWordQuestion.setText("who invented")

            val shadowApp = Shadows.shadowOf(appContext)
            drainStartedActivities()
            overlayRoot.findViewById<ImageButton>(R.id.btn_inline_word_search).performClick()

            val launchedWordSearch = shadowApp.nextStartedActivity
            assertNotNull("Google Search intent must launch on word search tap", launchedWordSearch)
            assertEquals(Intent.ACTION_WEB_SEARCH, launchedWordSearch!!.action)
            assertEquals(SelectionToolbarHelper.GOOGLE_APP_PACKAGE, launchedWordSearch.`package`)
            assertEquals(
                "who invented Quantum Physics",
                launchedWordSearch.getStringExtra(SearchManager.QUERY)
            )

            // Re-open overlay and test Cutout Question + Search flow
            controller.start()
            ShadowLooper.idleMainLooper(100, TimeUnit.MILLISECONDS)
            val overlayRoot2 = getOverlayRoot(controller)
            val selView2 = overlayRoot2.findViewById<InlineTextSelectionView>(R.id.inline_ocr_selection_view)
            selView2.layout(0, 0, 1080, 1920)

            // Draw a closed circle loop enclosing both "Quantum" & "Physics" (x: 60..460, y: 240..420)
            val steps = 20
            for (i in 0..steps) {
                val angle = (i.toDouble() / steps) * 2.0 * Math.PI
                val px = 260f + (210f * kotlin.math.cos(angle)).toFloat()
                val py = 330f + (95f * kotlin.math.sin(angle)).toFloat()
                val action = when (i) {
                    0 -> MotionEvent.ACTION_DOWN
                    steps -> MotionEvent.ACTION_UP
                    else -> MotionEvent.ACTION_MOVE
                }
                sendMotion(selView2, action, px, py)
            }

            val etCutoutQuestion = overlayRoot2.findViewById<EditText>(R.id.et_cutout_ai_question)
            etCutoutQuestion.setText("explain simply")

            drainStartedActivities()
            overlayRoot2.findViewById<ImageButton>(R.id.btn_cutout_visual_search).performClick()

            val launchedCutoutSearch = shadowApp.nextStartedActivity
            assertNotNull("Google Search intent must launch on cutout search tap", launchedCutoutSearch)
            assertEquals(Intent.ACTION_WEB_SEARCH, launchedCutoutSearch!!.action)
            assertEquals(SelectionToolbarHelper.GOOGLE_APP_PACKAGE, launchedCutoutSearch.`package`)
            assertEquals(
                "explain simply Quantum Physics",
                launchedCutoutSearch.getStringExtra(SearchManager.QUERY)
            )
        } finally {
            controller.release()
            scope.cancel()
        }
    }

    // -------------------------------------------------------------
    // Fix 2: Open "Google AI Mode" Directly Inside Google App
    // -------------------------------------------------------------

    @Test
    fun `Google AI Mode explicitly targets Google App or SearchLite instead of Chrome`() {
        registerResolveActivity(
            action = Intent.ACTION_VIEW,
            packageName = SelectionToolbarHelper.GOOGLE_APP_PACKAGE,
            dataScheme = "https"
        )
        registerResolveActivity(
            action = Intent.ACTION_VIEW,
            packageName = "com.android.chrome",
            dataScheme = "https"
        )

        val intent = SelectionToolbarHelper.createGoogleAiModeTextIntent(
            context = appContext,
            query = "black holes",
            question = "explain briefly"
        )
        assertNotNull(intent)
        assertEquals(Intent.ACTION_VIEW, intent!!.action)
        assertEquals(SelectionToolbarHelper.GOOGLE_APP_PACKAGE, intent.`package`)
        assertTrue(intent.dataString!!.contains("https://www.google.com/search?udm=50"))
        assertTrue(intent.dataString!!.contains("explain+briefly+black+holes"))

        // Also test FloatingService executeAction("google_ai_mode") targeting Google App directly
        val service = Robolectric.buildService(FloatingService::class.java)
            .create()
            .startCommand(0, 1)
            .get()
        val executeAction = FloatingService::class.java
            .getDeclaredMethod("executeAction", String::class.java)
            .apply { isAccessible = true }

        val shadowApp = Shadows.shadowOf(appContext)
        drainStartedActivities()
        executeAction.invoke(service, "google_ai_mode")

        val started = shadowApp.nextStartedActivity
        assertNotNull(started)
        assertEquals(SelectionToolbarHelper.GOOGLE_APP_PACKAGE, started!!.`package`)
        assertEquals("https://www.google.com/search?udm=50", started.dataString)
        service.stopSelf()
    }

    // -------------------------------------------------------------
    // Fix 3: Reuse Screen Recording MediaProjection Session
    // -------------------------------------------------------------

    @Test
    fun `ScreenRecorderManager retains active MediaProjection across stop and FloatingService reuses it without re-prompting`() {
        val manager = ScreenRecorderManager(appContext)
        assertFalse(manager.hasActiveProjection())
        assertFalse(manager.startRecordingWithExistingProjection(false))

        // Inject a MediaProjection stub via Unsafe/field or reflection to verify lifecycle retention
        val unsafeCls = Class.forName("sun.misc.Unsafe")
        val theUnsafeField = unsafeCls.getDeclaredField("theUnsafe").apply { isAccessible = true }
        val unsafe = theUnsafeField.get(null)
        val allocateInstance = unsafeCls.getMethod("allocateInstance", Class::class.java)
        val fakeProjection = allocateInstance.invoke(unsafe, MediaProjection::class.java) as MediaProjection

        val projField = ScreenRecorderManager::class.java.getDeclaredField("mediaProjection").apply {
            isAccessible = true
        }
        projField.set(manager, fakeProjection)
        assertTrue("Cached MediaProjection should be active", manager.hasActiveProjection())

        // stopRecording() keeps projection alive for subsequent fast starts
        manager.stopRecording()
        assertTrue("stopRecording must keep active MediaProjection session alive", manager.hasActiveProjection())

        // Verify FloatingService reuses cached manager without starting ScreenRecordPermissionActivity again
        val service = Robolectric.buildService(FloatingService::class.java)
            .create()
            .startCommand(0, 1)
            .get()
        val mgrField = FloatingService::class.java.getDeclaredField("screenRecorderManager").apply {
            isAccessible = true
        }
        mgrField.set(service, manager)

        val shadowApp = Shadows.shadowOf(appContext)
        drainStartedActivities()
        val triggerRecord = FloatingService::class.java.getDeclaredMethod("triggerScreenRecording").apply {
            isAccessible = true
        }
        triggerRecord.invoke(service)
        // Even after 120ms, it attempts reuse rather than unconditionally launching ScreenRecordPermissionActivity beforehand
        assertNull(
            "Should not immediately open permission consent activity before attempting existing projection reuse",
            shadowApp.peekNextStartedActivity()
        )

        manager.releaseProjection()
        assertFalse("releaseProjection must clear cached MediaProjection", manager.hasActiveProjection())
        service.stopSelf()
    }

    // -------------------------------------------------------------
    // Fix 4: Fix Voice Assistant & Clock / Alarm Shortcuts
    // -------------------------------------------------------------

    @Test
    fun `Voice Assistant and Clock shortcuts resolve cleanly and manifest includes SET_ALARM`() {
        val pkgInfo = appContext.packageManager.getPackageInfo(
            appContext.packageName,
            PackageManager.GET_PERMISSIONS
        )
        assertTrue(
            "SET_ALARM permission must be declared in AndroidManifest.xml",
            pkgInfo.requestedPermissions?.contains("com.android.alarm.permission.SET_ALARM") == true
        )

        registerResolveActivity(
            action = Intent.ACTION_MAIN,
            packageName = "com.google.android.apps.googleassistant"
        )
        registerResolveActivity(
            action = AlarmClock.ACTION_SHOW_ALARMS,
            packageName = "com.google.android.deskclock"
        )

        val service = Robolectric.buildService(FloatingService::class.java)
            .create()
            .startCommand(0, 1)
            .get()
        val executeAction = FloatingService::class.java
            .getDeclaredMethod("executeAction", String::class.java)
            .apply { isAccessible = true }

        val shadowApp = Shadows.shadowOf(appContext)
        drainStartedActivities()
        executeAction.invoke(service, "voice_assistant")
        val voiceIntent = shadowApp.nextStartedActivity
        assertNotNull("Voice assistant must start an activity", voiceIntent)
        assertEquals("com.google.android.apps.googleassistant", voiceIntent!!.`package`)

        drainStartedActivities()
        executeAction.invoke(service, "clock")
        val clockIntent = shadowApp.nextStartedActivity
        assertNotNull("Clock shortcut must start AlarmClock activity", clockIntent)
        assertEquals(AlarmClock.ACTION_SHOW_ALARMS, clockIntent!!.action)
        service.stopSelf()
    }

    // -------------------------------------------------------------
    // Fix 5: Direct 1-Tap Split Screen (3-dot nested menu at 350ms)
    // -------------------------------------------------------------

    @Test
    fun `performSplitScreen expands Recents options menu when Split button is nested`() {
        val service = Robolectric.setupService(SmartDotAccessibilityService::class.java)
        service.performGlobalActionTestHook = { true }
        service.splitModeCheckTestHook = { false }

        val recentsRoot = AccessibilityNodeInfo.obtain().apply {
            packageName = "com.android.systemui"
        }
        val moreMenuBtn = AccessibilityNodeInfo.obtain().apply {
            packageName = "com.android.systemui"
            contentDescription = "More options"
            isClickable = true
            isEnabled = true
        }
        val nestedSplitBtn = AccessibilityNodeInfo.obtain().apply {
            packageName = "com.android.systemui"
            text = "Split screen"
            isClickable = true
            isEnabled = true
        }

        var menuExpanded = false
        Shadows.shadowOf(moreMenuBtn).setOnPerformActionListener { action, _ ->
            if (action == AccessibilityNodeInfo.ACTION_CLICK) {
                menuExpanded = true
                true
            } else {
                false
            }
        }
        Shadows.shadowOf(recentsRoot).addChild(moreMenuBtn)

        val popupRoot = AccessibilityNodeInfo.obtain().apply {
            packageName = "com.android.systemui"
        }
        Shadows.shadowOf(popupRoot).addChild(nestedSplitBtn)

        service.accessibleRootsTestHook = {
            if (!menuExpanded) listOf(recentsRoot) else listOf(recentsRoot, popupRoot)
        }

        var finishedResult: Boolean? = null
        service.performSplitScreen(Handler(Looper.getMainLooper())) { ok -> finishedResult = ok }

        ShadowLooper.idleMainLooper(350, TimeUnit.MILLISECONDS)
        assertTrue("More options button must be auto-clicked", menuExpanded)
        assertEquals(true, finishedResult)
        assertTrue(
            "Nested Split screen menu item must be clicked",
            Shadows.shadowOf(nestedSplitBtn).performedActions.contains(AccessibilityNodeInfo.ACTION_CLICK)
        )
    }

    // -------------------------------------------------------------
    // Fix 6 & 7: Multi-Window Floating Mini-Apps + Realme Fallback
    // -------------------------------------------------------------

    @Test
    fun `FloatingWindowManager opens all 6 mini-apps simultaneously with drag, resize, minimize bubble, maximize, and close`() {
        val wm = appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val manager = FloatingWindowManager(appContext, wm)
        val scope = CoroutineScope(Dispatchers.Main + Job())

        try {
            val wBrowser = FloatingMiniApps.openFloatingBrowser(appContext, manager, 0xFF00E5FF.toInt())
            val wYouTube = FloatingMiniApps.openFloatingYouTube(appContext, manager, 0xFFFF1744.toInt())
            val wNotes = FloatingMiniApps.openFloatingNotes(appContext, manager, scope, 0xFFFFC107.toInt())
            val wCalc = FloatingMiniApps.openFloatingCalculator(appContext, manager, 0xFF38BDF8.toInt())
            val wTranslator = FloatingMiniApps.openFloatingTranslator(appContext, manager, scope, 0xFF10B981.toInt())
            val wViewer = FloatingMiniApps.openFloatingViewer(appContext, manager, 0xFFA855F7.toInt())

            assertNotNull(wBrowser)
            assertNotNull(wYouTube)
            assertNotNull(wNotes)
            assertNotNull(wCalc)
            assertNotNull(wTranslator)
            assertNotNull(wViewer)
            assertEquals(6, manager.getOpenWindowIds().size)

            // Test Floating Calculator live evaluation
            wCalc!!.rootView.findViewWithTag<View>("fw_calc_btn_1").performClick()
            wCalc.rootView.findViewWithTag<View>("fw_calc_btn_2").performClick()
            wCalc.rootView.findViewWithTag<View>("fw_calc_btn_×").performClick()
            wCalc.rootView.findViewWithTag<View>("fw_calc_btn_3").performClick()
            assertEquals("= 36", wCalc.rootView.findViewWithTag<TextView>("fw_calc_preview").text.toString())
            wCalc.rootView.findViewWithTag<View>("fw_calc_btn_=").performClick()
            assertEquals("36", wCalc.rootView.findViewWithTag<TextView>("fw_calc_expr").text.toString())

            // Test Drag Move on Calculator Title Bar
            val titleBar = wCalc.rootView.findViewWithTag<View>("fw_title_bar_floating_calculator")
            val startX = wCalc.layoutParams.x
            val startY = wCalc.layoutParams.y
            sendMotion(titleBar, MotionEvent.ACTION_DOWN, 20f, 20f, rawX = 100f, rawY = 200f)
            sendMotion(titleBar, MotionEvent.ACTION_MOVE, 70f, 90f, rawX = 150f, rawY = 270f)
            sendMotion(titleBar, MotionEvent.ACTION_UP, 70f, 90f, rawX = 150f, rawY = 270f)
            assertTrue("Window X must change on title bar drag", wCalc.layoutParams.x != startX || wCalc.layoutParams.y != startY)

            // Test Bottom-Right Resize Grip
            val grip = wCalc.rootView.findViewWithTag<View>("fw_resize_grip_floating_calculator")
            val initW = wCalc.layoutParams.width
            val initH = wCalc.layoutParams.height
            sendMotion(grip, MotionEvent.ACTION_DOWN, 10f, 10f, rawX = 300f, rawY = 400f)
            sendMotion(grip, MotionEvent.ACTION_MOVE, -50f, -60f, rawX = 240f, rawY = 340f)
            sendMotion(grip, MotionEvent.ACTION_UP, -50f, -60f, rawX = 240f, rawY = 340f)
            assertTrue("Window width/height must update on resize drag", wCalc.layoutParams.width != initW || wCalc.layoutParams.height != initH)

            // Test Maximize & Restore
            val btnMax = wCalc.rootView.findViewWithTag<View>("fw_btn_maximize_floating_calculator")
            btnMax.performClick()
            assertTrue(wCalc.isMaximized)
            btnMax.performClick()
            assertFalse(wCalc.isMaximized)

            // Test Minimize to Bubble -> preserves View.GONE for YouTube background playback -> Tap Bubble Restores
            val btnMinYt = wYouTube!!.rootView.findViewWithTag<View>("fw_btn_minimize_floating_youtube")
            btnMinYt.performClick()
            assertTrue(wYouTube.isMinimized)
            assertEquals(View.GONE, wYouTube.rootView.visibility)
            assertNotNull(wYouTube.bubbleView)

            val modeToggle = wYouTube.rootView.findViewWithTag<TextView>("fw_youtube_mode_toggle")
            assertEquals("Mode: Mobile", modeToggle.text.toString())
            modeToggle.performClick()
            assertEquals("Mode: Desktop", modeToggle.text.toString())

            // Tap minimized bubble to restore
            sendMotion(wYouTube.bubbleView!!, MotionEvent.ACTION_DOWN, 10f, 10f, rawX = 10f, rawY = 10f)
            sendMotion(wYouTube.bubbleView!!, MotionEvent.ACTION_UP, 10f, 10f, rawX = 10f, rawY = 10f)
            assertFalse(wYouTube.isMinimized)
            assertEquals(View.VISIBLE, wYouTube.rootView.visibility)
            assertNull(wYouTube.bubbleView)

            // Test Close Window (X)
            wNotes!!.rootView.findViewWithTag<View>("fw_btn_close_floating_notes").performClick()
            assertFalse(manager.hasWindow("floating_notes"))

            manager.closeAllWindows()
            assertTrue(manager.getOpenWindowIds().isEmpty())
        } finally {
            manager.closeAllWindows()
            scope.cancel()
        }

        // Fix 7: Realme freeform web fallbacks
        assertEquals("https://m.youtube.com", FreeformWindowLauncher.webFallbackUrlForPackage("com.google.android.youtube"))
        assertEquals("https://chatgpt.com/", FreeformWindowLauncher.webFallbackUrlForPackage("com.openai.chatgpt"))
        assertEquals("https://gemini.google.com/app", FreeformWindowLauncher.webFallbackUrlForPackage("com.google.android.apps.bard"))
        assertEquals("https://web.whatsapp.com/", FreeformWindowLauncher.webFallbackUrlForPackage("com.whatsapp"))
    }

    // -------------------------------------------------------------
    // Fix 8 & 9: Silent Instant Hardware Toggles + Compact 1x1 Data Widget
    // -------------------------------------------------------------

    @Test
    fun `PrivilegedToggleHelper toggles hardware silently without Quick Settings shade and DataToggleWidget is compact icon-only`() {
        val dispatchedGlobalActions = mutableListOf<Int>()
        val a11yService = Robolectric.buildService(SmartDotAccessibilityService::class.java).create().get()
        a11yService.performGlobalActionTestHook = { action ->
            dispatchedGlobalActions.add(action)
            true
        }
        SmartDotAccessibilityService.setInstanceForTest(a11yService)

        val shellCommands = mutableListOf<String>()
        PrivilegedToggleHelper.shizukuShellTestHook = { cmd ->
            shellCommands.add(cmd)
            true
        }
        PrivilegedToggleHelper.mobileDataStateTestHook = { false }

        // Test 1x1 compact widget layout (single icon inside squircle FrameLayout, zero TextView crowding)
        val widgetView = LayoutInflater.from(appContext).inflate(R.layout.widget_data_toggle, null)
        assertTrue(widgetView is FrameLayout)
        val icon = widgetView.findViewById<ImageView>(R.id.widget_data_icon)
        assertNotNull(icon)
        assertFalse(icon.contentDescription.isNullOrBlank())
        assertFalse("Compact 1x1 widget must contain zero crowded TextViews", containsTextView(widgetView as ViewGroup))

        // Tapping widget calls silent Mobile Data toggle -> "svc data enable" and ZERO Quick Settings pulls
        DataToggleWidget.handleWidgetTap(appContext)
        assertTrue(shellCommands.contains("svc data enable"))
        assertFalse(
            "Silent toggle must NEVER pull down GLOBAL_ACTION_QUICK_SETTINGS",
            dispatchedGlobalActions.contains(AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS)
        )

        // Wi-Fi & Bluetooth silent shell execution
        assertEquals(true, PrivilegedToggleHelper.toggleSilently(appContext, QuickTileTarget.WIFI))
        assertTrue(shellCommands.contains("svc wifi enable"))
        assertEquals(true, PrivilegedToggleHelper.toggleSilently(appContext, QuickTileTarget.BLUETOOTH))
        assertTrue(shellCommands.contains("svc bluetooth enable"))

        // Auto-Rotate & Brightness silent writes
        Shadows.shadowOf(appContext).grantPermissions(android.Manifest.permission.WRITE_SETTINGS)
        val appOps = appContext.getSystemService(Context.APP_OPS_SERVICE) as android.app.AppOpsManager
        Shadows.shadowOf(appOps).setMode(
            android.app.AppOpsManager.OPSTR_WRITE_SETTINGS,
            appContext.applicationInfo.uid,
            appContext.packageName,
            android.app.AppOpsManager.MODE_ALLOWED
        )
        Settings.System.putInt(appContext.contentResolver, Settings.System.ACCELEROMETER_ROTATION, 0)
        assertEquals(true, PrivilegedToggleHelper.toggleAutoRotateSilently(appContext))
        assertEquals(1, Settings.System.getInt(appContext.contentResolver, Settings.System.ACCELEROMETER_ROTATION, 0))

        Settings.System.putInt(appContext.contentResolver, Settings.System.SCREEN_BRIGHTNESS, 100)
        val raised = PrivilegedToggleHelper.adjustBrightnessByStep(appContext, +35)
        assertEquals(135, raised)
        val lowered = PrivilegedToggleHelper.adjustBrightnessByStep(appContext, -35)
        assertEquals(100, lowered)

        assertTrue(
            "Zero GLOBAL_ACTION_QUICK_SETTINGS calls allowed during silent hardware toggles",
            dispatchedGlobalActions.isEmpty()
        )
    }

    private fun containsTextView(group: ViewGroup): Boolean {
        for (i in 0 until group.childCount) {
            val child = group.getChildAt(i)
            if (child is TextView) return true
            if (child is ViewGroup && containsTextView(child)) return true
        }
        return false
    }

    // -------------------------------------------------------------
    // Fix 10: Expanded 20+ Shortcut Library & Power Menu Action
    // -------------------------------------------------------------

    @Test
    fun `ShortcutCatalog includes 20+ new shortcuts and preserves screen_copy to screen_translate ordering`() {
        assertTrue("Catalog must contain at least 40 shortcuts", ACTION_LIST.size >= 40)

        val requiredNewIds = listOf(
            "floating_browser", "floating_youtube", "floating_notes",
            "floating_calculator", "floating_translator", "floating_viewer",
            "google_ai_mode", "gemini", "chatgpt",
            "power_menu", "volume_panel", "media_play_pause", "media_next",
            "brightness_up", "brightness_down", "qr_scanner"
        )
        val catalogIds = ACTION_LIST.map { it.id }.toSet()
        requiredNewIds.forEach { id ->
            assertTrue("Catalog missing required shortcut: $id", catalogIds.contains(id))
            val label = resolveShortcutShortLabel(id)
            assertTrue("Missing short label for $id", label.isNotBlank() && label != "Shortcut")
        }

        val copyIdx = ACTION_LIST.indexOfFirst { it.id == "screen_copy" }
        val translateIdx = ACTION_LIST.indexOfFirst { it.id == "screen_translate" }
        assertTrue(copyIdx >= 0 && translateIdx == copyIdx + 1)

        // Verify power_menu dispatches GLOBAL_ACTION_POWER_DIALOG via AccessibilityService
        val a11yService = Robolectric.buildService(SmartDotAccessibilityService::class.java).create().get()
        val actions = mutableListOf<Int>()
        a11yService.performGlobalActionTestHook = { action ->
            actions.add(action)
            true
        }
        SmartDotAccessibilityService.setInstanceForTest(a11yService)

        val floatingService = Robolectric.buildService(FloatingService::class.java)
            .create()
            .startCommand(0, 1)
            .get()
        val executeAction = FloatingService::class.java
            .getDeclaredMethod("executeAction", String::class.java)
            .apply { isAccessible = true }

        executeAction.invoke(floatingService, "power_menu")
        assertTrue(actions.contains(AccessibilityService.GLOBAL_ACTION_POWER_DIALOG))
        floatingService.stopSelf()
    }
}
