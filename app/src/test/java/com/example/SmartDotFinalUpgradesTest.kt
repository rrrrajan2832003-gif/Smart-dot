package com.example

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.provider.Settings
import android.view.View
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
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
import org.robolectric.shadows.ShadowLooper
import org.robolectric.shadows.ShadowSettings

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w1080dp-h2400dp-mdpi")
class SmartDotFinalUpgradesTest {

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
    }

    @After
    fun tearDown() {
        ShadowSettings.setCanDrawOverlays(false)
    }

    @Test
    fun `MainActivity exposes Hide Shortcut Labels toggle and Shake Sensitivity slider persisted in prefs`() {
        val prefs = appContext.getSharedPreferences("smart_dot_prefs", Context.MODE_PRIVATE)

        // Hide Shortcut Labels toggle in Menu Appearance card
        composeRule.onNodeWithTag("switch_hide_shortcut_labels")
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()
        assertTrue(prefs.getBoolean(FloatingService.PREF_HIDE_SHORTCUT_LABELS, false))

        // Shake Sensitivity slider renders right under Shake Torch toggle
        composeRule.onNodeWithTag("switch_shake_torch")
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithTag("slider_shake_sensitivity")
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun `Popup hides AI strip, fits 6 top tabs and Calculator cleanly on Small preset, toggles active tab, and hides shortcut labels`() {
        val prefs = appContext.getSharedPreferences("smart_dot_prefs", Context.MODE_PRIVATE)
        prefs.edit()
            .putInt("menu_width_dp", 250)
            .putInt("menu_height_dp", 250)
            .putBoolean(FloatingService.PREF_HIDE_SHORTCUT_LABELS, true)
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
        popupContent.measure(
            View.MeasureSpec.makeMeasureSpec(250, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(250, View.MeasureSpec.EXACTLY)
        )
        popupContent.layout(0, 0, 250, 250)
        ShadowLooper.idleMainLooper()

        // 1) AI Strip hidden
        assertEquals(View.GONE, popupContent.findViewById<View>(R.id.btn_open_ai_panel).visibility)

        // 2) All 6 top bar tabs fit inside 250px popup width without clipping
        val tabBar = popupContent.findViewById<LinearLayout>(R.id.tab_bar)
        assertEquals(6, tabBar.childCount)
        for (i in 0 until tabBar.childCount) {
            val child = tabBar.getChildAt(i) as ImageView
            assertTrue("Tab $i width must be > 0", child.width > 0)
            assertTrue("Tab $i height must be > 0", child.height > 0)
            assertTrue("Tab $i right (${child.right}) must fit in tabBar (${tabBar.width})", child.right <= tabBar.width)
        }

        // 3) Tapping Calculator tab opens calculator & tapping again toggles back to Actions
        val layoutActions = popupContent.findViewById<View>(R.id.layout_tab_actions)
        val layoutCalc = popupContent.findViewById<View>(R.id.layout_tab_calc)
        tabBar.getChildAt(0).performClick()
        assertEquals(View.VISIBLE, layoutCalc.visibility)
        assertEquals(View.GONE, layoutActions.visibility)

        popupContent.measure(
            View.MeasureSpec.makeMeasureSpec(250, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(250, View.MeasureSpec.EXACTLY)
        )
        popupContent.layout(0, 0, 250, 250)
        val calcGrid = popupContent.findViewById<GridLayout>(R.id.calc_keypad_grid)
        val eqButton = popupContent.findViewById<View>(R.id.btn_calc_eq)
        assertTrue("Calculator last button (${eqButton.bottom}) must fit inside keypad (${calcGrid.height})", eqButton.bottom <= calcGrid.height)

        // Second tap returns cleanly to shortcut grid
        tabBar.getChildAt(0).performClick()
        assertEquals(View.VISIBLE, layoutActions.visibility)
        assertEquals(View.GONE, layoutCalc.visibility)

        // 4) Shortcut labels are hidden (View.GONE) and tight spacing is applied
        val pager = popupContent.findViewById<ViewPager>(R.id.vp_shortcuts_pager)
        pager.measure(
            View.MeasureSpec.makeMeasureSpec(230, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(180, View.MeasureSpec.EXACTLY)
        )
        pager.layout(0, 0, 230, 180)
        val firstPage = pager.getChildAt(0) as LinearLayout
        assertTrue("Page padding must be tight (<= 4px at 1x density)", firstPage.paddingLeft <= 4)
        val firstRow = firstPage.getChildAt(0) as LinearLayout
        val firstItem = firstRow.getChildAt(0) as LinearLayout
        val labelView = firstItem.getChildAt(1) as TextView
        assertEquals(View.GONE, labelView.visibility)

        controller.destroy()
    }

    @Test
    fun `Floating Dot ON OFF toggle attaches visibly and cycles all 5 dot shape styles`() {
        val prefs = appContext.getSharedPreferences("smart_dot_prefs", Context.MODE_PRIVATE)
        prefs.edit()
            .putBoolean("floating_dot_enabled", false)
            .putBoolean("sidebar_enabled", true)
            .putInt("dot_size", 56)
            .commit()

        val controller = Robolectric.buildService(FloatingService::class.java)
        val service = controller.create().startCommand(0, 1).get()

        val isAddedField = FloatingService::class.java.getDeclaredField("isFloatingViewAdded").apply {
            isAccessible = true
        }
        assertFalse("Dot should not be attached when floating_dot_enabled=false", isAddedField.getBoolean(service))

        // Turn Floating Dot ON and re-invoke onStartCommand / applyCustomizables
        prefs.edit().putBoolean("floating_dot_enabled", true).commit()
        controller.startCommand(0, 2)
        ShadowLooper.idleMainLooper()

        assertTrue("Dot must attach when toggled ON", isAddedField.getBoolean(service))
        val floatingViewField = FloatingService::class.java.getDeclaredField("floatingView").apply {
            isAccessible = true
        }
        val imgDotField = FloatingService::class.java.getDeclaredField("imgFloatingDot").apply {
            isAccessible = true
        }
        val floatingView = floatingViewField.get(service) as View
        val imgDot = imgDotField.get(service) as ImageView
        assertEquals(View.VISIBLE, floatingView.visibility)
        assertEquals(View.VISIBLE, imgDot.visibility)

        val applyDotStyles = FloatingService::class.java.getDeclaredMethod("applyDotCustomizables").apply {
            isAccessible = true
        }

        // Cycle all 5 dot shape styles and verify geometry + drawable type
        val styles = listOf("Normal Circle", "Vertical Line", "Three Dots", "Small Pill", "Rounded Bar")
        val measuredSizes = HashMap<String, Pair<Int, Int>>()
        for (style in styles) {
            prefs.edit().putString("dot_style", style).commit()
            applyDotStyles.invoke(service)
            val lp = imgDot.layoutParams
            measuredSizes[style] = lp.width to lp.height
            assertEquals("dot_style_$style", imgDot.tag)
            when (style) {
                "Three Dots" -> assertTrue(imgDot.drawable is LayerDrawable)
                "Vertical Line", "Small Pill", "Rounded Bar" -> assertTrue(imgDot.drawable is GradientDrawable)
            }
        }

        val normal = measuredSizes["Normal Circle"]!!
        assertEquals(normal.first, normal.second)
        assertTrue(measuredSizes["Vertical Line"]!!.second > measuredSizes["Vertical Line"]!!.first)
        assertTrue(measuredSizes["Rounded Bar"]!!.second > measuredSizes["Small Pill"]!!.second)

        controller.destroy()
    }

    @Test
    fun `Shake Sensitivity slider dynamically adjusts ShakeDetector threshold with zero vibration`() {
        val prefs = appContext.getSharedPreferences("smart_dot_prefs", Context.MODE_PRIVATE)
        prefs.edit()
            .putBoolean(FloatingService.PREF_SHAKE_TORCH_ENABLED, true)
            .putFloat(FloatingService.PREF_SHAKE_SENSITIVITY, 12.5f)
            .putBoolean("floating_dot_enabled", true)
            .commit()

        val controller = Robolectric.buildService(FloatingService::class.java)
        val service = controller.create().startCommand(0, 1).get()

        val detectorField = FloatingService::class.java.getDeclaredField("shakeDetector").apply {
            isAccessible = true
        }
        val detector = detectorField.get(service) as? ShakeDetector
        assertNotNull(detector)
        assertEquals(12.5f, detector!!.getThreshold(), 0.01f)

        // Update threshold to Hard (24f) and verify syncShakeTorchListener applies it
        prefs.edit().putFloat(FloatingService.PREF_SHAKE_SENSITIVITY, 24f).commit()
        val syncShake = FloatingService::class.java.getDeclaredMethod("syncShakeTorchListener").apply {
            isAccessible = true
        }
        syncShake.invoke(service)
        assertEquals(24f, detector.getThreshold(), 0.01f)

        // Verify gentle shake ~15 m/s^2 does NOT trigger at 24f, DOES trigger at 12f
        assertFalse(detector.onAccelerationSample(15f, 0f, 0f, 1000L))
        assertFalse(detector.onAccelerationSample(-15f, 0f, 0f, 1180L))

        detector.setThreshold(12f)
        detector.reset()
        assertFalse(detector.onAccelerationSample(15f, 0f, 0f, 2000L))
        assertFalse(detector.onAccelerationSample(-15f, 0f, 0f, 2180L))
        assertTrue(detector.onAccelerationSample(15f, 0f, 0f, 2340L))

        controller.destroy()
    }

    @Test
    fun `1x1 DataToggleWidget provider XML points to R_xml_data_widget_info and responds to tap`() {
        val receiverInfo = appContext.packageManager.getReceiverInfo(
            ComponentName(appContext, DataToggleWidget::class.java),
            PackageManager.GET_META_DATA
        )
        assertNotNull(receiverInfo.metaData)
        assertEquals(
            R.xml.data_widget_info,
            receiverInfo.metaData.getInt("android.appwidget.provider")
        )

        val a11yService = Robolectric.setupService(SmartDotAccessibilityService::class.java)
        val globalActions = mutableListOf<Int>()
        a11yService.performGlobalActionTestHook = { act ->
            globalActions.add(act)
            true
        }
        SmartDotAccessibilityService.setInstanceForTest(a11yService)
        try {
            val shadowApp = Shadows.shadowOf(RuntimeEnvironment.getApplication())
            while (shadowApp.nextStartedActivity != null) { /* drain launch permission intent */ }
            val widget = DataToggleWidget()
            widget.onReceive(appContext, Intent(DataToggleWidget.ACTION_WIDGET_TOGGLE_DATA))
            assertTrue(globalActions.contains(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS))
            val launchedIntent = shadowApp.nextStartedActivity
            assertNull("Accessibility quick-tile tap must not open Settings Activity immediately", launchedIntent)
        } finally {
            SmartDotAccessibilityService.setInstanceForTest(null)
            PrivilegedToggleHelper.mobileDataStateTestHook = null
        }
    }
}
