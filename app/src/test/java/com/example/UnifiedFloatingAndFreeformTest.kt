package com.example

import android.app.ActivityOptions
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.pm.ResolveInfo
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.PopupWindow
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.example.ui.theme.MyApplicationTheme
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
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
@Config(sdk = [36])
class UnifiedFloatingAndFreeformTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var appContext: Context

    @Before
    fun setUp() {
        appContext = RuntimeEnvironment.getApplication()
        ShadowSettings.setCanDrawOverlays(true)
        appContext.getSharedPreferences("smart_dot_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        FreeformWindowLauncher.promptShownCountForTest = 0
        FreeformWindowLauncher.activeGuideDialogRef?.get()?.dismiss()
        FreeformWindowLauncher.activeGuideDialogRef = null
    }

    @After
    fun tearDown() {
        FreeformWindowLauncher.activeGuideDialogRef?.get()?.dismiss()
        FreeformWindowLauncher.activeGuideDialogRef = null
        ShadowSettings.setCanDrawOverlays(false)
    }

    @Test
    fun `service stays alive with only Floating Dot ON or only Edge Sidebar ON and stops when both OFF`() {
        val prefs = appContext.getSharedPreferences("smart_dot_prefs", Context.MODE_PRIVATE)
        prefs.edit()
            .putBoolean("floating_dot_enabled", true)
            .putBoolean("sidebar_enabled", false)
            .commit()

        val controller = Robolectric.buildService(FloatingService::class.java)
        val service = controller.create().startCommand(0, 1).get()

        val sidebarViewField = FloatingService::class.java.getDeclaredField("sidebarView").apply { isAccessible = true }
        val isFloatingAddedField = FloatingService::class.java.getDeclaredField("isFloatingViewAdded").apply { isAccessible = true }

        assertTrue("Service must stay active with only Dot enabled", FloatingService.isRunning)
        assertTrue("Floating dot must be attached", isFloatingAddedField.getBoolean(service))
        assertNull("Sidebar view must be null when disabled", sidebarViewField.get(service))

        // Switch to Sidebar ONLY via broadcast update
        prefs.edit()
            .putBoolean("floating_dot_enabled", false)
            .putBoolean("sidebar_enabled", true)
            .commit()
        service.sendBroadcast(Intent(FloatingService.ACTION_UPDATE_SETTINGS).setPackage(appContext.packageName))
        ShadowLooper.idleMainLooper()

        assertTrue("Service must stay active with only Sidebar enabled", FloatingService.isRunning)
        assertFalse("Floating dot must detach when disabled", isFloatingAddedField.getBoolean(service))
        assertNotNull("Sidebar view must attach when enabled", sidebarViewField.get(service))

        // Switch BOTH OFF via broadcast update
        prefs.edit()
            .putBoolean("floating_dot_enabled", false)
            .putBoolean("sidebar_enabled", false)
            .commit()
        service.sendBroadcast(Intent(FloatingService.ACTION_UPDATE_SETTINGS).setPackage(appContext.packageName))
        ShadowLooper.idleMainLooper()

        assertFalse("Service must stop when both Dot and Sidebar are OFF", FloatingService.isRunning)
        controller.destroy()
    }

    @Test
    fun `dot menu and edge sidebar menu share identical tabs, apps launcher, and actions`() {
        registerFakeExternalApp("com.sample.calculatorapp", "Sample Calc")

        val controller = Robolectric.buildService(FloatingService::class.java)
        val service = controller.create().startCommand(0, 1).get()

        val floatingViewField = FloatingService::class.java.getDeclaredField("floatingView").apply { isAccessible = true }
        val sidebarViewField = FloatingService::class.java.getDeclaredField("sidebarView").apply { isAccessible = true }
        val activePopupField = FloatingService::class.java.getDeclaredField("activePopupWindow").apply { isAccessible = true }
        val lastDismissField = FloatingService::class.java.getDeclaredField("lastDismissTime").apply { isAccessible = true }

        val floatingView = floatingViewField.get(service) as View
        val sidebarView = sidebarViewField.get(service) as View
        val imgDot = floatingView.findViewById<View>(R.id.img_floating_dot)

        // Open via Floating Dot
        val t1 = SystemClock.uptimeMillis()
        imgDot.dispatchTouchEvent(MotionEvent.obtain(t1, t1, MotionEvent.ACTION_DOWN, 10f, 10f, 0))
        imgDot.dispatchTouchEvent(MotionEvent.obtain(t1, t1 + 15, MotionEvent.ACTION_UP, 10f, 10f, 0))
        val dotPopup = activePopupField.get(service) as PopupWindow
        assertTrue(dotPopup.isShowing)

        // Wait a beat for IO coroutine populating dynamic apps
        Thread.sleep(150)
        ShadowLooper.idleMainLooper()

        val dotTabs = dotPopup.contentView.findViewById<LinearLayout>(R.id.tab_bar)
        val dotDynamicApps = dotPopup.contentView.findViewById<LinearLayout>(R.id.dynamic_app_shortcuts)
        assertEquals(6, dotTabs.childCount)
        assertTrue("Dot popup must populate dynamic apps list", dotDynamicApps.childCount >= 1)

        // Switch via Drawer tab (index 4)
        dotTabs.getChildAt(4).performClick()
        assertEquals(View.VISIBLE, dotPopup.contentView.findViewById<View>(R.id.layout_tab_apps).visibility)

        dotPopup.dismiss()
        lastDismissField.setLong(service, System.currentTimeMillis() - 500L)

        // Open via Edge Sidebar
        val t2 = SystemClock.uptimeMillis()
        sidebarView.dispatchTouchEvent(MotionEvent.obtain(t2, t2, MotionEvent.ACTION_DOWN, 300f, 200f, 0))
        sidebarView.dispatchTouchEvent(MotionEvent.obtain(t2, t2 + 45, MotionEvent.ACTION_UP, 120f, 200f, 0))
        val sidebarPopup = activePopupField.get(service) as PopupWindow
        assertTrue(sidebarPopup.isShowing)

        Thread.sleep(150)
        ShadowLooper.idleMainLooper()

        val sidebarTabs = sidebarPopup.contentView.findViewById<LinearLayout>(R.id.tab_bar)
        val sidebarDynamicApps = sidebarPopup.contentView.findViewById<LinearLayout>(R.id.dynamic_app_shortcuts)
        assertEquals(dotTabs.childCount, sidebarTabs.childCount)
        assertEquals(dotDynamicApps.childCount, sidebarDynamicApps.childCount)

        // Switch Drawer via executeAction("open_app_drawer") from sidebar popup
        val executeAction = FloatingService::class.java.getDeclaredMethod("executeAction", String::class.java).apply {
            isAccessible = true
        }
        executeAction.invoke(service, "open_app_drawer")
        assertEquals(View.VISIBLE, sidebarPopup.contentView.findViewById<View>(R.id.layout_tab_apps).visibility)

        controller.destroy()
    }

    @Test
    fun `FreeformWindowLauncher configures WINDOWING_MODE_FREEFORM, bounded rect, flags, and dev options prompt`() {
        val bounds = FreeformWindowLauncher.computeCenteredFreeformBounds(appContext)
        val screenW = appContext.resources.displayMetrics.widthPixels
        val screenH = appContext.resources.displayMetrics.heightPixels
        assertTrue("Bounds width must fit strictly within screen width", bounds.width() in 100 until screenW)
        assertTrue("Bounds height must fit strictly within screen height", bounds.height() in 100 until screenH)
        assertEquals(5, FreeformWindowLauncher.WINDOWING_MODE_FREEFORM)

        val options = FreeformWindowLauncher.buildFreeformActivityOptions(appContext)
        val bundle = options.toBundle()
        assertNotNull(bundle)
        assertEquals(bounds, options.launchBounds)

        // Verify prompt triggers when freeform support is disabled (default 0)
        Settings.Global.putInt(appContext.contentResolver, "enable_freeform_support", 0)
        assertFalse(FreeformWindowLauncher.isFreeformMultiWindowEnabled(appContext))

        registerFakeExternalApp("com.example.freeformtarget", "Target App")
        val launchIntent = appContext.packageManager.getLaunchIntentForPackage("com.example.freeformtarget")!!
        val launched = FreeformWindowLauncher.launchInFreeformWindow(appContext, launchIntent, "Target App")
        assertTrue(launched)
        assertEquals(1, FreeformWindowLauncher.promptShownCountForTest)

        // Verify Developer Options dialog action fires Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS
        val dialog = FreeformWindowLauncher.activeGuideDialogRef?.get()
        assertNotNull("Expected Developer Options guide AlertDialog", dialog)
        dialog?.getButton(android.app.AlertDialog.BUTTON_POSITIVE)?.performClick()
        ShadowLooper.idleMainLooper()

        val startedIntent = Shadows.shadowOf(RuntimeEnvironment.getApplication()).nextStartedActivity
        assertNotNull(startedIntent)
        // First started activity is Target App, drain until dev settings intent
        val startedIntents = mutableListOf(startedIntent)
        while (true) {
            val next = Shadows.shadowOf(RuntimeEnvironment.getApplication()).nextStartedActivity ?: break
            startedIntents.add(next)
        }
        val appLaunch = startedIntents.firstOrNull {
            it.`package` == "com.example.freeformtarget" ||
                it.component?.packageName == "com.example.freeformtarget"
        }
        assertNotNull("Expected target app launch intent in $startedIntents", appLaunch)
        assertTrue((appLaunch!!.flags and Intent.FLAG_ACTIVITY_NEW_TASK) != 0)
        assertTrue((appLaunch.flags and Intent.FLAG_ACTIVITY_MULTIPLE_TASK) != 0)
        assertTrue(
            "Clicking Open Developer Options must launch ACTION_APPLICATION_DEVELOPMENT_SETTINGS",
            startedIntents.any { it.action == Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS }
        )

        // When enable_freeform_support == 1, no prompt is shown
        FreeformWindowLauncher.promptShownCountForTest = 0
        Settings.Global.putInt(appContext.contentResolver, "enable_freeform_support", 1)
        assertTrue(FreeformWindowLauncher.isFreeformMultiWindowEnabled(appContext))
        FreeformWindowLauncher.launchInFreeformWindow(appContext, launchIntent, "Target App")
        assertEquals(0, FreeformWindowLauncher.promptShownCountForTest)
    }

    @Test
    fun `clicking app from either Floating Dot or Edge Sidebar launches it in Freeform flags mode`() {
        Settings.Global.putInt(appContext.contentResolver, "enable_freeform_support", 1)
        registerFakeExternalApp("com.google.android.youtube", "YouTube")

        val controller = Robolectric.buildService(FloatingService::class.java)
        val service = controller.create().startCommand(0, 1).get()

        val executeAction = FloatingService::class.java.getDeclaredMethod("executeAction", String::class.java).apply {
            isAccessible = true
        }
        executeAction.invoke(service, "youtube")

        val started = Shadows.shadowOf(RuntimeEnvironment.getApplication()).nextStartedActivity
        assertNotNull("YouTube action must launch activity", started)
        assertTrue((started.flags and Intent.FLAG_ACTIVITY_NEW_TASK) != 0)
        assertTrue((started.flags and Intent.FLAG_ACTIVITY_MULTIPLE_TASK) != 0)

        controller.destroy()
    }

    @Test
    fun `Home screen status card displays independent Floating Dot and Edge Sidebar switches persisted to prefs`() {
        val prefs = appContext.getSharedPreferences("smart_dot_prefs", Context.MODE_PRIVATE)
        val syncEvents = mutableListOf<Pair<Boolean, Boolean>>()

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
                    onSyncOverlayToggles = { dotOn, sidebarOn ->
                        syncEvents.add(dotOn to sidebarOn)
                    }
                )
            }
        }

        composeTestRule.onNodeWithText("Floating Dot").assertExists()
        composeTestRule.onNodeWithText("Edge Sidebar").assertExists()
        composeTestRule.onNodeWithTag("switch_floating_dot").assertIsOn()
        composeTestRule.onNodeWithTag("switch_edge_sidebar").assertIsOn()

        // Turn OFF ONLY Floating Dot
        composeTestRule.onNodeWithTag("switch_floating_dot").performScrollTo().performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("switch_floating_dot").assertIsOff()
        composeTestRule.onNodeWithTag("switch_edge_sidebar").assertIsOn()
        assertFalse(prefs.getBoolean("floating_dot_enabled", true))
        assertTrue(prefs.getBoolean("sidebar_enabled", true))
        assertEquals(false to true, syncEvents.last())

        // Turn OFF Edge Sidebar as well
        composeTestRule.onNodeWithTag("switch_edge_sidebar").performScrollTo().performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("switch_edge_sidebar").assertIsOff()
        assertFalse(prefs.getBoolean("sidebar_enabled", true))
        assertEquals(false to false, syncEvents.last())
    }

    private fun registerFakeExternalApp(pkg: String, label: String) {
        val shadowPm = Shadows.shadowOf(appContext.packageManager)
        val component = ComponentName(pkg, "$pkg.MainActivity")
        val packageLaunchIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
            setPackage(pkg)
        }
        val resolveInfo = ResolveInfo().apply {
            nonLocalizedLabel = label
            activityInfo = ActivityInfo().apply {
                packageName = pkg
                name = component.className
                exported = true
                nonLocalizedLabel = label
            }
        }
        shadowPm.addActivityIfNotPresent(component)
        shadowPm.addResolveInfoForIntent(packageLaunchIntent, resolveInfo)
        shadowPm.addResolveInfoForIntent(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),
            resolveInfo
        )
    }
}
