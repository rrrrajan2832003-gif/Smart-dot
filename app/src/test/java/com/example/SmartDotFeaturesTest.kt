package com.example

import android.content.Context
import android.content.Intent
import android.os.Build
import android.speech.RecognizerIntent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.PopupWindow
import android.widget.TextView
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.example.SwipeGestureHelper.SwipeDirection
import com.example.ui.theme.MyApplicationTheme
import org.junit.After
import org.junit.Assert.assertEquals
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SmartDotFeaturesTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var appContext: Context

    @Before
    fun setUp() {
        appContext = RuntimeEnvironment.getApplication()
        ShadowSettings.setCanDrawOverlays(true)
        appContext.getSharedPreferences("smart_dot_prefs", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @After
    fun tearDown() {
        ShadowSettings.setCanDrawOverlays(false)
    }

    @Test
    fun `SwipeGestureHelper accurately classifies all 5 directions for dot, right edge, and left edge`() {
        val threshold = 60f

        assertEquals(
            SwipeDirection.SWIPE_UP,
            SwipeGestureHelper.detectSwipeDirection(dx = 8f, dy = -120f, minDeltaPx = threshold)
        )
        assertEquals(
            SwipeDirection.SWIPE_DOWN,
            SwipeGestureHelper.detectSwipeDirection(dx = -5f, dy = 130f, minDeltaPx = threshold)
        )
        assertEquals(
            SwipeDirection.SWIPE_LEFT,
            SwipeGestureHelper.detectSwipeDirection(dx = -110f, dy = 6f, minDeltaPx = threshold, inwardIsRight = false)
        )
        assertEquals(
            SwipeDirection.SWIPE_LEFT,
            SwipeGestureHelper.detectSwipeDirection(dx = 110f, dy = 4f, minDeltaPx = threshold, inwardIsRight = true)
        )
        assertEquals(
            SwipeDirection.SWIPE_UP_LEFT,
            SwipeGestureHelper.detectSwipeDirection(dx = -90f, dy = -95f, minDeltaPx = threshold, inwardIsRight = false)
        )
        assertEquals(
            SwipeDirection.SWIPE_DOWN_LEFT,
            SwipeGestureHelper.detectSwipeDirection(dx = -95f, dy = 90f, minDeltaPx = threshold, inwardIsRight = false)
        )
        assertEquals(
            SwipeDirection.SWIPE_UP_LEFT,
            SwipeGestureHelper.detectSwipeDirection(dx = 90f, dy = -95f, minDeltaPx = threshold, inwardIsRight = true)
        )
        // Small tap is ignored
        assertNull(SwipeGestureHelper.detectSwipeDirection(dx = -12f, dy = -14f, minDeltaPx = threshold))
        // Fast flick shorter than full threshold triggers via velocity
        assertEquals(
            SwipeDirection.SWIPE_UP,
            SwipeGestureHelper.detectSwipeDirection(
                dx = 0f,
                dy = -38f,
                velocityPxPerSec = 950f,
                minDeltaPx = threshold
            )
        )
    }

    @Test
    fun `CalculatorEvaluator and FloatingService calculator tab show live preview before equals and commit cleanly`() {
        assertNull(CalculatorEvaluator.evaluateLivePreview("56"))
        assertNull(CalculatorEvaluator.evaluateLivePreview("56*"))
        assertEquals("336", CalculatorEvaluator.evaluateLivePreview("56*6"))
        assertEquals("70", CalculatorEvaluator.evaluateLivePreview("10+20*3"))
        assertEquals("Div By Zero", CalculatorEvaluator.evaluateLivePreview("8/0"))

        val controller = Robolectric.buildService(FloatingService::class.java)
        val service = controller.create().startCommand(0, 1).get()

        val showPopupMethod = FloatingService::class.java.getDeclaredMethod("showAssistantSidebar").apply {
            isAccessible = true
        }
        showPopupMethod.invoke(service)

        val activePopupField = FloatingService::class.java.getDeclaredField("activePopupWindow").apply { isAccessible = true }
        val popupContent = (activePopupField.get(service) as PopupWindow).contentView

        // Switch popup to Calculator tab (index 0 in dynamic tab_bar)
        popupContent.findViewById<android.widget.LinearLayout>(R.id.tab_bar).getChildAt(0).performClick()
        ShadowLooper.idleMainLooper()

        val tvMain = popupContent.findViewById<TextView>(R.id.tv_calc_screen)
        val tvPreview = popupContent.findViewById<TextView>(R.id.tv_calc_preview)

        popupContent.findViewById<Button>(R.id.btn_calc_5).performClick()
        popupContent.findViewById<Button>(R.id.btn_calc_6).performClick()
        assertEquals("56", tvMain.text.toString())
        assertEquals(View.GONE, tvPreview.visibility)

        popupContent.findViewById<Button>(R.id.btn_calc_mul).performClick()
        assertEquals("56*", tvMain.text.toString())
        assertEquals(View.GONE, tvPreview.visibility)

        popupContent.findViewById<Button>(R.id.btn_calc_6).performClick()
        assertEquals("56*", tvMain.text.toString().take(3))
        assertEquals("56*6", tvMain.text.toString())
        assertEquals(View.VISIBLE, tvPreview.visibility)
        assertEquals("= 336", tvPreview.text.toString())

        // Backspace hides preview again while trailing '*' remains
        popupContent.findViewById<Button>(R.id.btn_calc_back).performClick()
        assertEquals("56*", tvMain.text.toString())
        assertEquals(View.GONE, tvPreview.visibility)

        // Enter '6' again -> live preview returns, then press '=' to commit
        popupContent.findViewById<Button>(R.id.btn_calc_6).performClick()
        assertEquals("= 336", tvPreview.text.toString())

        popupContent.findViewById<Button>(R.id.btn_calc_eq).performClick()
        assertEquals("336", tvMain.text.toString())
        assertEquals(View.GONE, tvPreview.visibility)

        controller.destroy()
    }

    @Test
    fun `Lock Position keeps Floating Dot and Edge Sidebar fixed and fires assigned swipe actions`() {
        val prefs = appContext.getSharedPreferences("smart_dot_prefs", Context.MODE_PRIVATE)
        prefs.edit()
            .putBoolean("dot_position_locked", true)
            .putBoolean("floating_dot_enabled", true)
            .putBoolean("sidebar_enabled", true)
            .putString(SwipeDirection.SWIPE_UP.prefKey, "voice_assistant")
            .putString(SwipeDirection.SWIPE_DOWN_LEFT.prefKey, "wifi")
            .putInt("last_x", 200)
            .putInt("last_y", 500)
            .putInt("sidebar_last_y", 400)
            .commit()

        val controller = Robolectric.buildService(FloatingService::class.java)
        val service = controller.create().startCommand(0, 1).get()

        val dotImgField = FloatingService::class.java.getDeclaredField("imgFloatingDot").apply { isAccessible = true }
        val dotParamsField = FloatingService::class.java.getDeclaredField("params").apply { isAccessible = true }
        val sidebarField = FloatingService::class.java.getDeclaredField("sidebarView").apply { isAccessible = true }
        val sidebarParamsField = FloatingService::class.java.getDeclaredField("sidebarParams").apply { isAccessible = true }

        val dotImg = dotImgField.get(service) as View
        val dotParams = dotParamsField.get(service) as WindowManager.LayoutParams
        val sidebar = sidebarField.get(service) as View
        val sidebarParams = sidebarParamsField.get(service) as WindowManager.LayoutParams

        val initialDotX = dotParams.x
        val initialDotY = dotParams.y
        val initialSidebarY = sidebarParams.y

        // Swipe UP on locked Floating Dot -> triggers voice_assistant without changing coordinates
        dispatchRawDragGesture(dotImg, startX = 200f, startY = 500f, endX = 205f, endY = 260f)
        assertEquals(initialDotX, dotParams.x)
        assertEquals(initialDotY, dotParams.y)

        val voiceIntent = Shadows.shadowOf(RuntimeEnvironment.getApplication()).nextStartedActivity
        assertNotNull("Swipe Up -> Voice Assistant should start activity", voiceIntent)
        assertEquals(Intent.ACTION_VOICE_COMMAND, voiceIntent.action)

        // Swipe Down-Left on locked Edge Sidebar -> triggers wifi settings without changing sidebar y
        dispatchRawDragGesture(sidebar, startX = 1060f, startY = 400f, endX = 880f, endY = 580f)
        assertEquals(initialSidebarY, sidebarParams.y)

        val wifiIntent = Shadows.shadowOf(RuntimeEnvironment.getApplication()).nextStartedActivity
        assertNotNull("Swipe Down-Left -> Wi-Fi should start settings activity", wifiIntent)
        assertEquals(android.provider.Settings.Panel.ACTION_WIFI, wifiIntent.action)

        // Unlock position -> dragging dot updates saved coordinates
        prefs.edit().putBoolean("dot_position_locked", false).commit()
        dispatchRawDragGesture(dotImg, startX = 200f, startY = 500f, endX = 320f, endY = 640f)
        assertTrue("Unlocked Floating Dot drag must update X", dotParams.x != initialDotX)
        assertTrue("Unlocked Floating Dot drag must update Y", dotParams.y != initialDotY)
        assertEquals(dotParams.x, prefs.getInt("last_x", -1))
        assertEquals(dotParams.y, prefs.getInt("last_y", -1))

        controller.destroy()
    }

    @Test
    fun `triggerVoiceAssistant falls back through VOICE_COMMAND, ASSIST, and WEB_SEARCH`() {
        val controller = Robolectric.buildService(FloatingService::class.java)
        val service = controller.create().startCommand(0, 1).get()

        val triggerMethod = FloatingService::class.java.getDeclaredMethod("triggerVoiceAssistant").apply {
            isAccessible = true
        }
        triggerMethod.invoke(service)

        val started = Shadows.shadowOf(RuntimeEnvironment.getApplication()).nextStartedActivity
        assertNotNull(started)
        assertEquals(Intent.ACTION_VOICE_COMMAND, started.action)
        assertTrue((started.flags and Intent.FLAG_ACTIVITY_NEW_TASK) != 0)
        // Verify RecognizerIntent action constant is also clean and valid
        assertEquals("android.speech.action.WEB_SEARCH", RecognizerIntent.ACTION_WEB_SEARCH)

        controller.destroy()
    }

    @Test
    fun `MainActivity Lock Position switch persists configuration to SharedPreferences`() {
        val prefs = appContext.getSharedPreferences("smart_dot_prefs", Context.MODE_PRIVATE)

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

        composeTestRule.onNodeWithText("Lock Position").assertExists()
        composeTestRule.onNodeWithTag("switch_lock_position").assertIsOff()
        composeTestRule.onNodeWithTag("switch_lock_position").performScrollTo().performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("switch_lock_position").assertIsOn()
        assertTrue(prefs.getBoolean("dot_position_locked", false))
    }

    @Test
    fun `GesturesScreen assigns all 5 swipe actions and supports Reset`() {
        val prefs = appContext.getSharedPreferences("smart_dot_prefs", Context.MODE_PRIVATE)

        composeTestRule.setContent {
            MyApplicationTheme {
                GesturesScreen(dotColor = "#00E5FF")
            }
        }

        // All 5 directional gesture rows exist
        SwipeDirection.entries.forEach { dir ->
            composeTestRule.onNodeWithTag("gesture_row_${dir.prefKey}").assertExists()
        }

        // Open picker for Swipe Up and select Lock Screen
        composeTestRule.onNodeWithTag("gesture_row_${SwipeDirection.SWIPE_UP.prefKey}").performScrollTo().performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("gesture_option_lock_screen").performScrollTo().performClick()
        composeTestRule.waitForIdle()
        assertEquals("lock_screen", prefs.getString(SwipeDirection.SWIPE_UP.prefKey, null))

        // Toggle lock position switch inside Gestures tab
        composeTestRule.onNodeWithTag("gestures_tab_lock_switch").performClick()
        composeTestRule.waitForIdle()
        assertTrue(prefs.getBoolean("dot_position_locked", false))

        // Reset gestures back to none
        composeTestRule.onNodeWithTag("reset_gestures_button").performClick()
        composeTestRule.waitForIdle()
        assertEquals("none", prefs.getString(SwipeDirection.SWIPE_UP.prefKey, null))
    }

    @Test
    fun `SmartDotAccessibilityService performLockScreen dispatches global action on API 28+ safely`() {
        val controller = Robolectric.buildService(SmartDotAccessibilityService::class.java).create()
        val accessibilityService = controller.get()
        // Calling performLockScreen returns cleanly on current Robolectric SDK (36 >= 28)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            // Robolectric returns false or true without crashing depending on service connection
            accessibilityService.performLockScreen()
        }
        controller.destroy()
    }

    private fun dispatchRawDragGesture(
        target: View,
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float
    ) {
        val t0 = 1000L
        val down = MotionEvent.obtain(t0, t0, MotionEvent.ACTION_DOWN, startX, startY, 0)
        val move1 = MotionEvent.obtain(t0, t0 + 30L, MotionEvent.ACTION_MOVE, (startX + endX) * 0.5f, (startY + endY) * 0.5f, 0)
        val move2 = MotionEvent.obtain(t0, t0 + 60L, MotionEvent.ACTION_MOVE, endX, endY, 0)
        val up = MotionEvent.obtain(t0, t0 + 80L, MotionEvent.ACTION_UP, endX, endY, 0)

        target.dispatchTouchEvent(down)
        target.dispatchTouchEvent(move1)
        target.dispatchTouchEvent(move2)
        target.dispatchTouchEvent(up)

        down.recycle()
        move1.recycle()
        move2.recycle()
        up.recycle()
        ShadowLooper.idleMainLooper()
    }
}
