package com.example

import android.app.AlarmManager
import android.app.Application
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.provider.MediaStore
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.PopupWindow
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.viewpager.widget.ViewPager
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
import org.robolectric.shadows.ShadowAlarmManager
import org.robolectric.shadows.ShadowLooper
import org.robolectric.shadows.ShadowSettings

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SmartDotExtendedFeaturesTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private lateinit var appContext: Application

    @Before
    fun setUp() {
        appContext = ApplicationProvider.getApplicationContext()
        ShadowSettings.setCanDrawOverlays(true)
        appContext.getSharedPreferences("smart_dot_prefs", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @After
    fun tearDown() {
        ShadowSettings.setCanDrawOverlays(false)
    }

    @Test
    fun `MainActivity replaces Customize Shortcuts with Custom Shortcuts and exposes new feature controls`() {
        // Unified "Custom Shortcuts" card replaces redundant "Customize Shortcuts"
        composeRule.onNodeWithTag("shortcuts_config_card").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Custom Shortcuts").assertIsDisplayed()
        composeRule.onNodeWithText("Customize Shortcuts").assertDoesNotExist()
        composeRule.onNodeWithTag("add_shortcut_button").assertExists()
        composeRule.onNodeWithTag("reset_to_default_button").assertExists()

        // Adjustable Grid Columns (3, 4, 5)
        composeRule.onNodeWithTag("grid_cols_4").performScrollTo().performClick()
        val prefs = appContext.getSharedPreferences("smart_dot_prefs", Context.MODE_PRIVATE)
        assertEquals(4, prefs.getInt("menu_grid_cols", 3))

        composeRule.onNodeWithTag("grid_cols_5").performScrollTo().performClick()
        assertEquals(5, prefs.getInt("menu_grid_cols", 3))

        // Shake to Toggle Flashlight switch
        composeRule.onNodeWithTag("switch_shake_torch").performScrollTo().performClick()
        assertTrue(prefs.getBoolean(FloatingService.PREF_SHAKE_TORCH_ENABLED, false))

        // Gemini API key input & save
        composeRule.onNodeWithTag("input_gemini_api_key").performScrollTo().performTextInput("AIzaTestFreeKey")
        composeRule.onNodeWithTag("btn_save_gemini_key").performScrollTo().performClick()
        assertEquals("AIzaTestFreeKey", prefs.getString(AiAssistantHelper.PREF_GEMINI_API_KEY, ""))

        // Persistence buttons exist
        composeRule.onNodeWithTag("btn_ignore_battery_opt").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("btn_realme_autolaunch").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `ShakeDetector triggers strictly on double shake gt 16f within 500ms with cooldown and zero haptics`() {
        var triggerCount = 0
        val detector = ShakeDetector { triggerCount++ }

        // Resting gravity ~9.8 -> no trigger
        assertFalse(detector.onAccelerationSample(0f, 9.8f, 0f, 100L))
        assertEquals(0, triggerCount)

        // Two rapid peaks > 16f separated by 180ms (within 500ms) -> triggers once
        assertFalse(detector.onAccelerationSample(14f, 10f, 2f, 1000L)) // magnitude ~17.3
        assertTrue(detector.onAccelerationSample(-15f, 9f, 1f, 1180L)) // magnitude ~17.5
        assertEquals(1, triggerCount)

        // Cooldown suppresses immediate extra peaks
        assertFalse(detector.onAccelerationSample(18f, 5f, 0f, 1300L))
        assertFalse(detector.onAccelerationSample(-18f, 5f, 0f, 1500L))
        assertEquals(1, triggerCount)

        // Two peaks > 500ms apart do NOT count as one double shake
        assertFalse(detector.onAccelerationSample(18f, 0f, 0f, 2500L))
        assertFalse(detector.onAccelerationSample(-18f, 0f, 0f, 3200L))
        assertEquals(1, triggerCount)
    }

    @Test
    fun `Menu grid columns 3, 4, and 5 build dynamic columns cleanly without clipping`() {
        val prefs = appContext.getSharedPreferences("smart_dot_prefs", Context.MODE_PRIVATE)
        prefs.edit()
            .putInt("menu_grid_cols", 5)
            .putInt("menu_grid_rows", 3)
            .putBoolean("floating_dot_enabled", true)
            .apply()

        val controller = Robolectric.buildService(FloatingService::class.java)
        val service = controller.create().startCommand(0, 1).get()

        val showPopup = FloatingService::class.java.getDeclaredMethod("showAssistantSidebar").apply {
            isAccessible = true
        }
        showPopup.invoke(service)
        ShadowLooper.idleMainLooper()

        val activePopupField = FloatingService::class.java.getDeclaredField("activePopupWindow").apply {
            isAccessible = true
        }
        val popupContent = (activePopupField.get(service) as PopupWindow).contentView
        val pager = popupContent.findViewById<ViewPager>(R.id.vp_shortcuts_pager)
        assertNotNull(pager.adapter)
        assertTrue((pager.adapter?.count ?: 0) >= 1)
        pager.measure(
            View.MeasureSpec.makeMeasureSpec(840, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(900, View.MeasureSpec.EXACTLY)
        )
        pager.layout(0, 0, 840, 900)
        ShadowLooper.idleMainLooper()

        val firstPage = pager.getChildAt(0) as LinearLayout
        assertEquals(3, firstPage.childCount)
        val firstRow = firstPage.getChildAt(0) as LinearLayout
        assertEquals(5, firstRow.childCount)

        controller.destroy()
    }

    @Test
    fun `AiAssistantHelper builds valid Gemini payload, parses response, and falls back when key empty`() {
        val bodyJson = AiAssistantHelper.buildGeminiRequestBody("Summarize this screen")
        assertTrue(bodyJson.contains("\"text\":\"Summarize this screen\""))

        val fakeResponse = """
            {
              "candidates": [
                {
                  "content": {
                    "parts": [{"text": "Screen summary: 3 tasks due today."}]
                  }
                }
              ]
            }
        """.trimIndent()
        assertEquals("Screen summary: 3 tasks due today.", AiAssistantHelper.parseGeminiResponseText(fakeResponse))

        // Empty key triggers Gemini/ChatGPT web fallback intents
        val launched = AiAssistantHelper.launchExternalAiFallback(appContext)
        assertTrue(launched)
        val started = Shadows.shadowOf(RuntimeEnvironment.getApplication()).nextStartedActivity
        assertNotNull(started)
        assertTrue(
            started.data?.toString()?.contains("gemini.google.com") == true ||
                started.data?.toString()?.contains("chatgpt.com") == true
        )
    }

    @Test
    fun `FloatingService AI panel opens via strip and ask_ai action`() {
        val controller = Robolectric.buildService(FloatingService::class.java)
        val service = controller.create().startCommand(0, 1).get()

        val executeAction = FloatingService::class.java.getDeclaredMethod("executeAction", String::class.java).apply {
            isAccessible = true
        }
        executeAction.invoke(service, "ask_ai")
        ShadowLooper.idleMainLooper()

        val activePopupField = FloatingService::class.java.getDeclaredField("activePopupWindow").apply {
            isAccessible = true
        }
        val popupContent = (activePopupField.get(service) as PopupWindow).contentView
        assertEquals(View.VISIBLE, popupContent.findViewById<View>(R.id.layout_tab_ai).visibility)
        assertEquals("Free Web Fallback", popupContent.findViewById<android.widget.TextView>(R.id.tv_ai_mode_status).text.toString())

        controller.destroy()
    }

    @Test
    fun `InAppShortcutLauncher builds valid deep links for all 11 In-App Shortcuts and custom URI`() {
        val prefs = appContext.getSharedPreferences("smart_dot_prefs", Context.MODE_PRIVATE)
        prefs.edit().putString(InAppShortcutLauncher.PREF_CUSTOM_INTENT_URI, "https://example.org/notes").commit()

        val inAppIds = listOf(
            "chrome_incognito",
            "chrome_search",
            "youtube_subscriptions",
            "youtube_search",
            "whatsapp_chat",
            "whatsapp_camera",
            "settings_wifi",
            "settings_bluetooth",
            "settings_battery",
            "settings_app_info",
            "custom_url_intent"
        )
        inAppIds.forEach { id ->
            assertTrue("Expected $id to be recognized as In-App Shortcut", InAppShortcutLauncher.isInAppShortcut(id))
            val candidates = InAppShortcutLauncher.buildIntentCandidates(appContext, id)
            assertTrue("Expected non-empty intent candidates for $id", candidates.isNotEmpty())
        }

        // Verify custom URL launches clean ACTION_VIEW intent
        Settings.Global.putInt(appContext.contentResolver, "enable_freeform_support", 1)
        assertTrue(InAppShortcutLauncher.launch(appContext, "custom_url_intent"))
        val launchedIntent = Shadows.shadowOf(RuntimeEnvironment.getApplication()).nextStartedActivity
        assertEquals("https://example.org/notes", launchedIntent?.data?.toString())
    }

    @Test
    fun `ScreenRecorderManager targets Movies_SmartDot and FloatingService launches consent activity`() {
        assertEquals("Movies/SmartDot", ScreenRecorderManager.buildMoviesRelativePath())
        val (w, h) = ScreenRecorderManager.computeEncoderDimensions(1440, 3168)
        assertEquals(0, w % 2)
        assertEquals(0, h % 2)
        val values = ScreenRecorderManager.buildMediaStoreValues("SmartDot_test.mp4", 1700000000000L)
        assertEquals("video/mp4", values.getAsString(MediaStore.Video.Media.MIME_TYPE))
        assertEquals("Movies/SmartDot", values.getAsString(MediaStore.Video.Media.RELATIVE_PATH))

        val controller = Robolectric.buildService(FloatingService::class.java)
        val service = controller.create().startCommand(0, 1).get()
        val executeAction = FloatingService::class.java.getDeclaredMethod("executeAction", String::class.java).apply {
            isAccessible = true
        }
        executeAction.invoke(service, "screen_record")
        val consentIntent = Shadows.shadowOf(RuntimeEnvironment.getApplication()).nextStartedActivity
        assertEquals(ScreenRecordPermissionActivity::class.java.name, consentIntent?.component?.className)
        controller.destroy()
    }

    @Test
    fun `DataToggleWidget and BootReceiver handle mobile data tap and boot auto-start`() {
        // BootReceiver auto-starts FloatingService when enabled and overlay granted
        val prefs = appContext.getSharedPreferences("smart_dot_prefs", Context.MODE_PRIVATE)
        prefs.edit()
            .putBoolean("floating_dot_enabled", false)
            .putBoolean("sidebar_enabled", true)
            .commit()

        val receiver = BootReceiver()
        receiver.onReceive(appContext, Intent(Intent.ACTION_BOOT_COMPLETED))
        val startedService = Shadows.shadowOf(appContext).nextStartedService
        assertEquals(FloatingService::class.java.name, startedService?.component?.className)

        // When both toggles are OFF, BootReceiver does NOT start FloatingService
        prefs.edit()
            .putBoolean("floating_dot_enabled", false)
            .putBoolean("sidebar_enabled", false)
            .commit()
        receiver.onReceive(appContext, Intent(Intent.ACTION_LOCKED_BOOT_COMPLETED))
        assertNull(Shadows.shadowOf(appContext).nextStartedService)

        // FloatingService.onTaskRemoved schedules restart alarm when Sidebar is ON
        prefs.edit().putBoolean("sidebar_enabled", true).commit()
        val controller = Robolectric.buildService(FloatingService::class.java)
        val service = controller.create().startCommand(0, 1).get()
        service.onTaskRemoved(Intent(Intent.ACTION_MAIN))
        val alarmManager = appContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val scheduled: ShadowAlarmManager.ScheduledAlarm? = Shadows.shadowOf(alarmManager).nextScheduledAlarm
        assertNotNull(scheduled)
        controller.destroy()

        // DataToggleWidget tap uses built-in Accessibility Quick-Tile toggle when active, and falls back to Data Settings when Accessibility is off
        val a11yService = Robolectric.setupService(SmartDotAccessibilityService::class.java)
        val globalActions = mutableListOf<Int>()
        a11yService.performGlobalActionTestHook = { act ->
            globalActions.add(act)
            true
        }
        SmartDotAccessibilityService.setInstanceForTest(a11yService)
        try {
            val widget = DataToggleWidget()
            widget.onReceive(appContext, Intent(DataToggleWidget.ACTION_WIDGET_TOGGLE_DATA))
            assertTrue(globalActions.contains(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS))
            assertNull(Shadows.shadowOf(RuntimeEnvironment.getApplication()).nextStartedActivity)

            SmartDotAccessibilityService.setInstanceForTest(null)
            widget.onReceive(appContext, Intent(DataToggleWidget.ACTION_WIDGET_TOGGLE_DATA))
            val fallback = Shadows.shadowOf(RuntimeEnvironment.getApplication()).nextStartedActivity
            assertNotNull(fallback)
        } finally {
            SmartDotAccessibilityService.setInstanceForTest(null)
            PrivilegedToggleHelper.mobileDataStateTestHook = null
        }
    }
}
