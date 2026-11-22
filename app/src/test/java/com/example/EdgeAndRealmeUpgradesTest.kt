package com.example

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ResolveInfo
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.example.EdgeGestureDetector.EdgeGesture
import com.example.EdgeGestureDetector.GesturePart
import com.example.ui.theme.MyApplicationTheme
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
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import org.robolectric.shadows.ShadowSettings
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class EdgeAndRealmeUpgradesTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var appContext: Context

    @Before
    fun setUp() {
        appContext = RuntimeEnvironment.getApplication()
        ShadowSettings.setCanDrawOverlays(true)
        appContext.getSharedPreferences("smart_dot_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        FreeformWindowLauncher.promptShownCountForTest = 0
        SmartDotAccessibilityService.setInstanceForTest(null)
    }

    @After
    fun tearDown() {
        SmartDotAccessibilityService.setInstanceForTest(null)
        ShadowSettings.setCanDrawOverlays(false)
    }

    private fun createDetector(
        screenHeight: Int = 2400,
        density: Float = 2.75f,
        allTapWindowsOpen: Boolean = true,
        detectedOut: MutableList<EdgeGesture>
    ): EdgeGestureDetector {
        return EdgeGestureDetector(
            density = density,
            screenHeightProvider = { screenHeight },
            onGestureDetected = { g -> detectedOut.add(g) },
            onTapWindowCheck = { allTapWindowsOpen }
        )
    }

    private fun feedTouchSequence(
        detector: EdgeGestureDetector,
        points: List<Triple<Float, Float, Long>>,
        releaseUp: Boolean = true
    ) {
        val baseTime = 10_000L
        val first = points.first()
        val down = MotionEvent.obtain(baseTime, baseTime + first.third, MotionEvent.ACTION_DOWN, first.first, first.second, 0)
        detector.onTouchEvent(down)
        down.recycle()

        for (i in 1 until points.size - if (releaseUp) 1 else 0) {
            val pt = points[i]
            val move = MotionEvent.obtain(baseTime, baseTime + pt.third, MotionEvent.ACTION_MOVE, pt.first, pt.second, 0)
            detector.onTouchEvent(move)
            move.recycle()
        }

        if (releaseUp) {
            val last = points.last()
            val up = MotionEvent.obtain(baseTime, baseTime + last.third, MotionEvent.ACTION_UP, last.first, last.second, 0)
            detector.onTouchEvent(up)
            up.recycle()
        }
    }

    private fun feedTap(detector: EdgeGestureDetector, downOffsetMs: Long, x: Float = 1060f, y: Float = 1000f) {
        val t = 10_000L + downOffsetMs
        val down = MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, y, 0)
        val up = MotionEvent.obtain(t, t + 60L, MotionEvent.ACTION_UP, x - 4f, y + 4f, 0)
        detector.onTouchEvent(down)
        detector.onTouchEvent(up)
        down.recycle()
        up.recycle()
    }

    @Test
    fun `EdgeGestureDetector detects all Part 1 Tap DoubleTap TripleTap and LongPress gestures`() {
        // 1. Single Tap (< 200ms)
        val singleOut = mutableListOf<EdgeGesture>()
        val singleDetector = createDetector(detectedOut = singleOut)
        feedTap(singleDetector, downOffsetMs = 0L)
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks()
        assertEquals(listOf(EdgeGesture.TAP), singleOut)

        // 2. Double Tap (<= 250ms gap)
        val doubleOut = mutableListOf<EdgeGesture>()
        val doubleDetector = createDetector(detectedOut = doubleOut)
        feedTap(doubleDetector, downOffsetMs = 0L)
        feedTap(doubleDetector, downOffsetMs = 150L)
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks()
        assertEquals(listOf(EdgeGesture.DOUBLE_TAP), doubleOut)

        // 3. Triple Tap (<= 400ms total window)
        val tripleOut = mutableListOf<EdgeGesture>()
        val tripleDetector = createDetector(detectedOut = tripleOut)
        feedTap(tripleDetector, downOffsetMs = 0L)
        feedTap(tripleDetector, downOffsetMs = 130L)
        feedTap(tripleDetector, downOffsetMs = 260L)
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks()
        assertEquals(listOf(EdgeGesture.TRIPLE_TAP), tripleOut)

        // 4. Long Press (>= 500ms stationary)
        val holdOut = mutableListOf<EdgeGesture>()
        val holdDetector = createDetector(detectedOut = holdOut)
        feedTouchSequence(
            holdDetector,
            listOf(Triple(1060f, 1000f, 0L), Triple(1055f, 1008f, 100L)),
            releaseUp = false
        )
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(520, TimeUnit.MILLISECONDS)
        assertEquals(listOf(EdgeGesture.LONG_PRESS), holdOut)
    }

    @Test
    fun `EdgeGestureDetector detects all 5 Part 2 Straight and Diagonal Swipes and excludes Rightward`() {
        val cases = listOf(
            EdgeGesture.SWIPE_UP to listOf(Triple(1060f, 1000f, 0L), Triple(1058f, 920f, 50L), Triple(1056f, 840f, 110L)),
            EdgeGesture.SWIPE_DOWN to listOf(Triple(1060f, 1000f, 0L), Triple(1062f, 1090f, 50L), Triple(1060f, 1180f, 110L)),
            EdgeGesture.SWIPE_LEFT to listOf(Triple(1060f, 1000f, 0L), Triple(980f, 1002f, 50L), Triple(890f, 1005f, 110L)),
            EdgeGesture.SWIPE_UP_LEFT to listOf(Triple(1060f, 1000f, 0L), Triple(990f, 930f, 50L), Triple(910f, 850f, 110L)),
            EdgeGesture.SWIPE_DOWN_LEFT to listOf(Triple(1060f, 1000f, 0L), Triple(990f, 1070f, 50L), Triple(910f, 1150f, 110L))
        )

        for ((expected, pts) in cases) {
            val out = mutableListOf<EdgeGesture>()
            val detector = createDetector(detectedOut = out)
            feedTouchSequence(detector, pts)
            assertEquals("Expected $expected", listOf(expected), out)
        }

        // Rule 6: Strictly exclude Right, Up-Right, Down-Right
        assertNull(EdgeGestureDetector.classifyDirection(dx = 150f, dy = 0f, inwardIsRight = false))
        assertNull(EdgeGestureDetector.classifyDirection(dx = 140f, dy = -140f, inwardIsRight = false))
        assertNull(EdgeGestureDetector.classifyDirection(dx = 140f, dy = 140f, inwardIsRight = false))
    }

    @Test
    fun `EdgeGestureDetector detects all 5 Part 3 Swipe plus Hold gestures`() {
        val cases = listOf(
            EdgeGesture.SWIPE_UP_HOLD to listOf(Triple(1060f, 1000f, 0L), Triple(1060f, 860f, 80L)),
            EdgeGesture.SWIPE_DOWN_HOLD to listOf(Triple(1060f, 1000f, 0L), Triple(1060f, 1140f, 80L)),
            EdgeGesture.SWIPE_LEFT_HOLD to listOf(Triple(1060f, 1000f, 0L), Triple(900f, 1000f, 80L)),
            EdgeGesture.SWIPE_UP_LEFT_HOLD to listOf(Triple(1060f, 1000f, 0L), Triple(930f, 870f, 80L)),
            EdgeGesture.SWIPE_DOWN_LEFT_HOLD to listOf(Triple(1060f, 1000f, 0L), Triple(930f, 1130f, 80L))
        )

        for ((expected, pts) in cases) {
            val out = mutableListOf<EdgeGesture>()
            val detector = createDetector(detectedOut = out)
            feedTouchSequence(detector, pts, releaseUp = false)
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(380, TimeUnit.MILLISECONDS)
            assertEquals("Expected $expected", listOf(expected), out)
        }
    }

    @Test
    fun `EdgeGestureDetector detects all 8 Part 4 L-Shape and U-Turn Compound Gestures`() {
        val cases = listOf(
            EdgeGesture.UP_THEN_LEFT to listOf(
                Triple(1060f, 1000f, 0L),
                Triple(1060f, 860f, 120L),
                Triple(920f, 860f, 340L)
            ),
            EdgeGesture.DOWN_THEN_LEFT to listOf(
                Triple(1060f, 1000f, 0L),
                Triple(1060f, 1140f, 120L),
                Triple(920f, 1140f, 340L)
            ),
            EdgeGesture.LEFT_THEN_UP to listOf(
                Triple(1060f, 1000f, 0L),
                Triple(920f, 1000f, 120L),
                Triple(920f, 860f, 340L)
            ),
            EdgeGesture.LEFT_THEN_DOWN to listOf(
                Triple(1060f, 1000f, 0L),
                Triple(920f, 1000f, 120L),
                Triple(920f, 1140f, 340L)
            ),
            EdgeGesture.UP_THEN_DOWN to listOf(
                Triple(1060f, 1000f, 0L),
                Triple(1060f, 840f, 140L),
                Triple(1055f, 1000f, 360L)
            ),
            EdgeGesture.DOWN_THEN_UP to listOf(
                Triple(1060f, 1000f, 0L),
                Triple(1060f, 1160f, 140L),
                Triple(1055f, 1000f, 360L)
            ),
            EdgeGesture.UP_LEFT_THEN_DOWN to listOf(
                Triple(1060f, 1000f, 0L),
                Triple(940f, 880f, 140L),
                Triple(940f, 1060f, 360L)
            ),
            EdgeGesture.DOWN_LEFT_THEN_UP to listOf(
                Triple(1060f, 1000f, 0L),
                Triple(940f, 1120f, 140L),
                Triple(940f, 940f, 360L)
            )
        )

        for ((expected, pts) in cases) {
            val out = mutableListOf<EdgeGesture>()
            val detector = createDetector(detectedOut = out)
            feedTouchSequence(detector, pts)
            assertEquals("Expected compound $expected", listOf(expected), out)
        }
    }

    @Test
    fun `EdgeGestureDetector enforces trigger width 25-45px, minimum 80px swipe, 900ms cap, and bottom nav exclusion`() {
        // Rule 1: Trigger Zone 25..45px
        assertEquals(25, EdgeGestureDetector.clampTriggerWidthPx(10))
        assertEquals(30, EdgeGestureDetector.clampTriggerWidthPx(30))
        assertEquals(45, EdgeGestureDetector.clampTriggerWidthPx(75))

        // Rule 2: Movement < 80px does NOT trigger Swipe
        val shortOut = mutableListOf<EdgeGesture>()
        val shortDet = createDetector(detectedOut = shortOut)
        feedTouchSequence(shortDet, listOf(Triple(1060f, 1000f, 0L), Triple(1000f, 1000f, 100L)))
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks()
        assertFalse("60px swipe must never fire SWIPE_LEFT", shortOut.contains(EdgeGesture.SWIPE_LEFT))

        // Rule 4: Complete window > 900ms rejected for swipe
        val slowOut = mutableListOf<EdgeGesture>()
        val slowDet = createDetector(detectedOut = slowOut)
        feedTouchSequence(
            slowDet,
            listOf(Triple(1060f, 1000f, 0L), Triple(950f, 1000f, 150L), Triple(890f, 1000f, 960L))
        )
        assertTrue("Swipe exceeding 900ms must be rejected", slowOut.isEmpty())

        // Rule 6: Bottom-edge system navigation gesture zone rejected
        val bottomOut = mutableListOf<EdgeGesture>()
        val bottomDet = createDetector(screenHeight = 2400, density = 2.75f, detectedOut = bottomOut)
        feedTouchSequence(
            bottomDet,
            listOf(Triple(1060f, 2385f, 0L), Triple(900f, 2385f, 80L))
        )
        assertTrue("Touch starting in bottom nav exclusion zone must be ignored", bottomOut.isEmpty())
    }

    @Test
    fun `FloatingService enforces 25-45px edge trigger width and zero haptic feedback`() {
        val prefs = appContext.getSharedPreferences("smart_dot_prefs", Context.MODE_PRIVATE)
        prefs.edit()
            .putBoolean("sidebar_enabled", true)
            .putInt("sidebar_width", 40) // oversized dp should clamp strictly to <= 45px
            .commit()

        val controller = Robolectric.buildService(FloatingService::class.java)
        val service = controller.create().startCommand(0, 1).get()

        val sidebarViewField = FloatingService::class.java.getDeclaredField("sidebarView").apply { isAccessible = true }
        val sidebarParamsField = FloatingService::class.java.getDeclaredField("sidebarParams").apply { isAccessible = true }
        val sidebarView = sidebarViewField.get(service) as View
        val sparams = sidebarParamsField.get(service) as WindowManager.LayoutParams

        assertTrue("Sidebar handle width (${sparams.width}) must be in 25..45px", sparams.width in 25..45)

        val t = 20_000L
        sidebarView.dispatchTouchEvent(MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, 1060f, 200f, 0))
        sidebarView.dispatchTouchEvent(MotionEvent.obtain(t, t + 50, MotionEvent.ACTION_UP, 900f, 200f, 0))
        ShadowLooper.idleMainLooper()

        assertEquals(
            "Zero haptic feedback allowed on Edge Sidebar",
            -1,
            Shadows.shadowOf(sidebarView).lastHapticFeedbackPerformed()
        )
        controller.destroy()
    }

    @Test
    fun `GesturesScreen renders all 22 gestures grouped in 4 parts and SmartDotScreen shows new sliders without vibration`() {
        assertEquals(22, EdgeGesture.entries.size)
        assertEquals(4, EdgeGesture.entries.count { it.part == GesturePart.TAP_HOLD })
        assertEquals(5, EdgeGesture.entries.count { it.part == GesturePart.SWIPE })
        assertEquals(5, EdgeGesture.entries.count { it.part == GesturePart.SWIPE_HOLD })
        assertEquals(8, EdgeGesture.entries.count { it.part == GesturePart.COMPOUND })

        // Test GesturesScreen
        composeTestRule.setContent {
            MyApplicationTheme {
                GesturesScreen(dotColor = "#00E5FF")
            }
        }

        composeTestRule.onNodeWithTag("gestures_engine_badge").assertIsDisplayed()
        composeTestRule.onNodeWithText(GesturePart.TAP_HOLD.title).performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText(GesturePart.SWIPE.title).performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText(GesturePart.SWIPE_HOLD.title).performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText(GesturePart.COMPOUND.title).performScrollTo().assertIsDisplayed()

        // Assign Up then Left -> Realme Smart Sidebar
        composeTestRule.onNodeWithTag("gesture_row_${EdgeGesture.UP_THEN_LEFT.prefKey}")
            .performScrollTo()
            .performClick()
        composeTestRule.onNodeWithTag("gesture_option_realme_smart_sidebar")
            .performScrollTo()
            .performClick()

        val prefs = appContext.getSharedPreferences("smart_dot_prefs", Context.MODE_PRIVATE)
        assertEquals("realme_smart_sidebar", EdgeGestureDetector.getAssignedAction(prefs, EdgeGesture.UP_THEN_LEFT))

        // Reset all 22 gestures
        composeTestRule.onNodeWithTag("reset_gestures_button").performClick()
        assertEquals("none", EdgeGestureDetector.getAssignedAction(prefs, EdgeGesture.UP_THEN_LEFT))
        assertEquals("open_menu", EdgeGestureDetector.getAssignedAction(prefs, EdgeGesture.TAP))
    }

    @Test
    fun `SmartDotScreen exposes Touch Sensitivity and Menu Animation Speed sliders with zero Vibration Feedback toggle`() {
        composeTestRule.setContent {
            MyApplicationTheme {
                SmartDotScreen(
                    hasOverlayPermission = true,
                    hasNotificationPermission = true,
                    hasAccessibilityPermission = true,
                    isServiceRunning = true,
                    onRequestOverlay = {},
                    onRequestNotification = {},
                    onRequestAccessibility = {},
                    onToggleService = {},
                    onSyncOverlayToggles = { _, _ -> }
                )
            }
        }

        composeTestRule.onNodeWithTag("slider_touch_sensitivity").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("slider_menu_anim_speed").performScrollTo().assertIsDisplayed()
        assertEquals(
            "Vibration Feedback UI toggle must be completely removed",
            0,
            composeTestRule.onAllNodesWithText("Vibration Feedback").fetchSemanticsNodes().size
        )
    }

    @Test
    fun `FreeformWindowLauncher attaches Realme UI windowing extras and launches Realme Smart Sidebar`() {
        val intent = Intent(Intent.ACTION_MAIN)
        val prepared = FreeformWindowLauncher.prepareFreeformIntent(intent)
        assertEquals(5, prepared.getIntExtra(FreeformWindowLauncher.EXTRA_ANDROID_WINDOWING_MODE, -1))
        assertTrue(prepared.getBooleanExtra(FreeformWindowLauncher.EXTRA_OPLUS_FREEFORM, false))
        assertTrue((prepared.flags and Intent.FLAG_ACTIVITY_NEW_TASK) != 0)
        assertTrue((prepared.flags and Intent.FLAG_ACTIVITY_MULTIPLE_TASK) != 0)
        assertEquals(Rect(120, 250, 960, 1450), FreeformWindowLauncher.REALME_FLEXIBLE_BOUNDS)

        // When unavailable, returns false cleanly
        assertFalse(FreeformWindowLauncher.launchRealmeSmartSidebar(appContext))

        // Register com.coloros.smartsidebar launch activity in Robolectric PackageManager
        val shadowPm = Shadows.shadowOf(appContext.packageManager)
        val resolveInfo = ResolveInfo().apply {
            activityInfo = ActivityInfo().apply {
                packageName = "com.coloros.smartsidebar"
                name = "com.coloros.smartsidebar.MainActivity"
                exported = true
            }
        }
        val mainQuery = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
            setPackage("com.coloros.smartsidebar")
        }
        shadowPm.addResolveInfoForIntent(mainQuery, resolveInfo)

        assertTrue(FreeformWindowLauncher.launchRealmeSmartSidebar(appContext))
        val launched = Shadows.shadowOf(RuntimeEnvironment.getApplication()).nextStartedActivity
        assertNotNull(launched)
        assertEquals("com.coloros.smartsidebar", launched.`package`)
    }

    @Test
    fun `SmartDotAccessibilityService performSplitScreen auto-clicks Split screen at 150ms and retries at 350ms`() {
        val service = Robolectric.setupService(SmartDotAccessibilityService::class.java)
        val dispatched = mutableListOf<Int>()
        service.performGlobalActionTestHook = { action ->
            dispatched.add(action)
            action == AccessibilityService.GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN
        }
        service.splitModeCheckTestHook = { false }

        val recentsRoot = AccessibilityNodeInfo.obtain().apply {
            packageName = "com.android.systemui"
        }
        val splitBtn = AccessibilityNodeInfo.obtain().apply {
            packageName = "com.android.systemui"
            text = "Split screen"
            isClickable = true
            isEnabled = true
        }
        Shadows.shadowOf(recentsRoot).addChild(splitBtn)

        // Initially empty at 150ms (Recents animating), populates before 350ms retry
        var rootsReady = false
        service.accessibleRootsTestHook = {
            if (rootsReady) listOf(recentsRoot) else emptyList()
        }

        var callbackResult: Boolean? = null
        val handler = Handler(Looper.getMainLooper())
        assertTrue(service.performSplitScreen(handler) { ok -> callbackResult = ok })
        assertTrue(dispatched.contains(AccessibilityService.GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN))

        // 150ms check fires when Recents isn't ready yet -> waits for 350ms check
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(160, TimeUnit.MILLISECONDS)
        assertNull(callbackResult)

        // Now Recents renders "Split screen" node before 350ms check
        rootsReady = true
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(210, TimeUnit.MILLISECONDS)
        assertEquals(true, callbackResult)
        assertTrue(
            "Split screen button must be clicked via ACTION_CLICK at 350ms retry",
            Shadows.shadowOf(splitBtn).performedActions.contains(AccessibilityNodeInfo.ACTION_CLICK)
        )

        // Also verify 150ms fast click when Recents is ready immediately
        val fastBtn = AccessibilityNodeInfo.obtain().apply {
            packageName = "com.android.systemui"
            text = "Split screen"
            isClickable = true
            isEnabled = true
        }
        val fastRoot = AccessibilityNodeInfo.obtain().apply {
            packageName = "com.android.systemui"
        }
        Shadows.shadowOf(fastRoot).addChild(fastBtn)
        service.accessibleRootsTestHook = { listOf(fastRoot) }

        var fastResult: Boolean? = null
        assertTrue(service.performSplitScreen(handler) { ok -> fastResult = ok })
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(155, TimeUnit.MILLISECONDS)
        assertEquals(true, fastResult)
        assertTrue(
            "Split screen button must be clicked at 150ms when already visible",
            Shadows.shadowOf(fastBtn).performedActions.contains(AccessibilityNodeInfo.ACTION_CLICK)
        )
    }

    @Test
    fun `FloatingService clean screenshot hides UI for 180ms before capture and restores after 600ms`() {
        val a11y = Robolectric.setupService(SmartDotAccessibilityService::class.java)
        val actionsWhileHidden = mutableListOf<Pair<Int, Int>>()
        val prefs = appContext.getSharedPreferences("smart_dot_prefs", Context.MODE_PRIVATE)
        prefs.edit()
            .putBoolean("floating_dot_enabled", true)
            .putBoolean("sidebar_enabled", true)
            .commit()

        val controller = Robolectric.buildService(FloatingService::class.java)
        val service = controller.create().startCommand(0, 1).get()

        val dotViewField = FloatingService::class.java.getDeclaredField("floatingView").apply { isAccessible = true }
        val sidebarField = FloatingService::class.java.getDeclaredField("sidebarView").apply { isAccessible = true }
        val dotView = dotViewField.get(service) as View
        val sidebarView = sidebarField.get(service) as View

        a11y.performGlobalActionTestHook = { action ->
            actionsWhileHidden.add(action to dotView.visibility)
            true
        }
        SmartDotAccessibilityService.setInstanceForTest(a11y)

        assertEquals(View.VISIBLE, dotView.visibility)
        assertEquals(View.VISIBLE, sidebarView.visibility)

        val triggerScreenshot = FloatingService::class.java.getDeclaredMethod("triggerScreenshot").apply { isAccessible = true }
        triggerScreenshot.invoke(service)

        // Immediately hidden before screenshot
        assertEquals(View.GONE, dotView.visibility)
        assertEquals(View.GONE, sidebarView.visibility)

        // Advance 200ms -> screenshot fires while views are GONE
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(200, TimeUnit.MILLISECONDS)
        assertEquals(1, actionsWhileHidden.size)
        assertEquals(AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT, actionsWhileHidden[0].first)
        assertEquals(View.GONE, actionsWhileHidden[0].second)

        // Advance past 600ms -> SmartDot UI is restored smoothly
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(450, TimeUnit.MILLISECONDS)
        assertEquals(View.VISIBLE, dotView.visibility)
        assertEquals(View.VISIBLE, sidebarView.visibility)

        controller.destroy()
    }

    @Test
    fun `FloatingService Voice Assistant prioritizes Google App VOICE_COMMAND`() {
        val controller = Robolectric.buildService(FloatingService::class.java)
        val service = controller.create().startCommand(0, 1).get()

        val triggerVoice = FloatingService::class.java.getDeclaredMethod("triggerVoiceAssistant").apply { isAccessible = true }
        triggerVoice.invoke(service)

        val started = Shadows.shadowOf(RuntimeEnvironment.getApplication()).nextStartedActivity
        assertNotNull(started)
        assertEquals(Intent.ACTION_VOICE_COMMAND, started.action)
        assertEquals("com.google.android.googlequicksearchbox", started.`package`)
        assertTrue((started.flags and Intent.FLAG_ACTIVITY_NEW_TASK) != 0)

        controller.destroy()
    }
}
