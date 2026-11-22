package com.example

import android.app.Application
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.example.SelectionToolbarHelper.ToolbarAction
import com.google.mlkit.nl.translate.TranslateLanguage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
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
@Config(sdk = [36], qualifiers = "w1080dp-h2400dp-mdpi")
class SmartDotFiveFixesTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private lateinit var appContext: Application

    @Before
    fun setUp() {
        appContext = ApplicationProvider.getApplicationContext()
        appContext.getSharedPreferences("smart_dot_prefs", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        ShadowSettings.setCanDrawOverlays(true)
    }

    @Test
    fun `Fix 1 - Eye visibility toggles hide toolbar buttons in MainActivity and filter Word and Cutout bars cleanly`() {
        composeRule.onNodeWithTag("selection_toolbar_order_card")
            .performScrollTo()
            .assertIsDisplayed()

        // Toggle visibility OFF for 5 actions so only 5 remain visible -> Row 2 should hide completely
        val toHide = listOf(
            ToolbarAction.GEMINI,
            ToolbarAction.CHATGPT,
            ToolbarAction.SHARE,
            ToolbarAction.FULL_SCREEN_TRANSLATE,
            ToolbarAction.GOOGLE_AI_MODE
        )
        toHide.forEach { action ->
            composeRule.onNodeWithTag("btn_toolbar_visibility_${action.id}")
                .performScrollTo()
                .performClick()
        }

        val prefs = composeRule.activity.getSharedPreferences("smart_dot_prefs", Context.MODE_PRIVATE)
        val hidden = SelectionToolbarHelper.getHiddenToolbarActions(prefs)
        assertEquals(toHide.toSet(), hidden)

        val visibleOrder = SelectionToolbarHelper.getVisibleToolbarOrder(prefs)
        assertEquals(5, visibleOrder.size)
        toHide.forEach { assertFalse(visibleOrder.contains(it)) }

        // Verify ScreenTextCopyController renders ONLY the 5 visible icons on BOTH Word & Cutout toolbars
        val scope = CoroutineScope(Dispatchers.Main + Job())
        val clipboard = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val bitmap = Bitmap.createBitmap(1080, 2400, Bitmap.Config.ARGB_8888)
        val ocrHelper = ScreenOcrHelper(
            customOcrRunner = {
                listOf(ScreenOcrHelper.OcrRawItem("Alpha", Rect(100, 300, 250, 360), isLine = false)) to emptyList()
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

        controller.start()
        ShadowLooper.idleMainLooper(ScreenTextCopyController.CAPTURE_HIDE_DELAY_MS + 10L, TimeUnit.MILLISECONDS)
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

        val wordRow1 = root.findViewById<LinearLayout>(R.id.inline_word_row1)
        val wordRow2 = root.findViewById<LinearLayout>(R.id.inline_word_row2)
        val cutoutRow1 = root.findViewById<LinearLayout>(R.id.inline_cutout_row1)
        val cutoutRow2 = root.findViewById<LinearLayout>(R.id.inline_cutout_row2)

        assertEquals(5, wordRow1.childCount)
        assertEquals(0, wordRow2.childCount)
        assertEquals(View.GONE, wordRow2.visibility)
        assertEquals(5, cutoutRow1.childCount)
        assertEquals(0, cutoutRow2.childCount)
        assertEquals(View.GONE, cutoutRow2.visibility)

        val expectedTags = visibleOrder.map { "toolbar_action_${it.id}" }
        assertEquals(expectedTags, (0 until wordRow1.childCount).map { wordRow1.getChildAt(it).tag })
        assertEquals(expectedTags, (0 until cutoutRow1.childCount).map { cutoutRow1.getChildAt(it).tag })

        controller.release()
        scope.cancel()

        // Reset restores all 10 visible icons
        composeRule.onNodeWithTag("btn_reset_toolbar_order")
            .performScrollTo()
            .performClick()
        assertTrue(SelectionToolbarHelper.getHiddenToolbarActions(prefs).isEmpty())
        assertEquals(10, SelectionToolbarHelper.getVisibleToolbarOrder(prefs).size)
    }

    @Test
    fun `Fix 2 - Top 6 popup tabs have 6 distinct vector icons and grid labels fit cleanly on compact menu`() {
        val prefs = appContext.getSharedPreferences("smart_dot_prefs", Context.MODE_PRIVATE)
        prefs.edit()
            .putString("menu_size_preset", "Small")
            .putInt("menu_width_dp", 250)
            .putInt("menu_height_dp", 250)
            .putInt("menu_grid_rows", 3)
            .putInt("menu_grid_cols", 3)
            .putBoolean(FloatingService.PREF_HIDE_SHORTCUT_LABELS, false)
            .commit()

        val serviceController = Robolectric.buildService(FloatingService::class.java)
        val service = serviceController.create().startCommand(0, 1).get()

        FloatingService::class.java.getDeclaredMethod("showAssistantSidebar").apply {
            isAccessible = true
            invoke(service)
        }
        ShadowLooper.idleMainLooper()

        val activePopup = FloatingService::class.java
            .getDeclaredField("activePopupWindow")
            .apply { isAccessible = true }
            .get(service) as PopupWindow
        val popupContent = activePopup.contentView

        val tabBar = popupContent.findViewById<LinearLayout>(R.id.tab_bar)
        assertEquals(6, tabBar.childCount)
        val iconTags = (0 until tabBar.childCount).map { (tabBar.getChildAt(it) as ImageView).tag as Int }
        assertEquals("All 6 top tabs must use distinct icons", 6, iconTags.toSet().size)
        assertEquals(R.drawable.ic_tab_calc, iconTags[0])
        assertEquals(R.drawable.ic_tab_translate, iconTags[1])
        assertEquals(R.drawable.ic_tab_clipboard, iconTags[2])
        assertEquals(R.drawable.ic_tab_notes, iconTags[3])
        assertEquals(R.drawable.ic_tab_apps, iconTags[4])
        assertEquals(R.drawable.ic_radial_settings, iconTags[5])

        // Verify grid rows use flexible weight=1f so labels fit inside viewport without clipping at the bottom
        val pager = popupContent.findViewById<androidx.viewpager.widget.ViewPager>(R.id.vp_shortcuts_pager)
        val page0 = pager.getChildAt(0) as LinearLayout
        assertEquals(3, page0.childCount)
        for (r in 0 until page0.childCount) {
            val rowLp = page0.getChildAt(r).layoutParams as LinearLayout.LayoutParams
            assertEquals("Row $r must use height=0 with weight=1f to prevent bottom label clipping", 0, rowLp.height)
            assertEquals(1f, rowLp.weight, 0.01f)
        }

        serviceController.destroy()
    }

    @Test
    fun `Fix 3 - Edge Sidebar width slider updates container and inner visual pill live without teardown`() {
        composeRule.onNodeWithTag("slider_sidebar_width")
            .performScrollTo()
            .assertIsDisplayed()

        val prefs = appContext.getSharedPreferences("smart_dot_prefs", Context.MODE_PRIVATE)
        prefs.edit()
            .putBoolean("sidebar_enabled", true)
            .putInt("sidebar_width", 3)
            .commit()

        val serviceController = Robolectric.buildService(FloatingService::class.java)
        val service = serviceController.create().startCommand(0, 1).get()

        val sidebarViewField = FloatingService::class.java.getDeclaredField("sidebarView").apply { isAccessible = true }
        val sidebarPillField = FloatingService::class.java.getDeclaredField("sidebarPillView").apply { isAccessible = true }
        val sidebarParamsField = FloatingService::class.java.getDeclaredField("sidebarParams").apply { isAccessible = true }

        val initialContainer = sidebarViewField.get(service) as FrameLayout
        val initialPill = sidebarPillField.get(service) as View
        val initialParams = sidebarParamsField.get(service) as WindowManager.LayoutParams

        assertEquals(25, initialParams.width)
        val thinPillW = initialPill.layoutParams.width

        // Drag slider to thickest (16dp) and trigger live settings update
        prefs.edit().putInt("sidebar_width", 16).commit()
        FloatingService::class.java.getDeclaredMethod("applyCustomizables").apply {
            isAccessible = true
            invoke(service)
        }
        ShadowLooper.idleMainLooper()

        val updatedContainer = sidebarViewField.get(service) as FrameLayout
        val updatedPill = sidebarPillField.get(service) as View
        val updatedParams = sidebarParamsField.get(service) as WindowManager.LayoutParams

        assertSame("Sidebar view must update live in place without re-creating", initialContainer, updatedContainer)
        assertEquals(45, updatedParams.width)
        assertTrue(
            "Thick 16dp visual pill (${updatedPill.layoutParams.width}px) must be strictly wider than thin 3dp pill (${thinPillW}px)",
            updatedPill.layoutParams.width > thinPillW
        )

        serviceController.destroy()
    }

    @Test
    fun `Fix 4 - Top header banner Core Utility Widget is completely removed from MainActivity`() {
        assertTrue(composeRule.onAllNodesWithText("Core Utility Widget").fetchSemanticsNodes().isEmpty())
        assertTrue(composeRule.onAllNodesWithText("Floating Shortcuts").fetchSemanticsNodes().isEmpty())
        composeRule.onNodeWithText("System Requirements").assertIsDisplayed()
    }

    @Test
    fun `Fix 5 - ML Kit auto-detect runs first for inline and screen translation with expanded languages`() {
        var identifyInvoked = false
        val helper = ScreenTranslateHelper(
            identifyLanguageFn = { text ->
                identifyInvoked = true
                when {
                    text.contains("Bonjour") -> "fr"
                    text.contains("سلام") -> "ur"
                    else -> "es"
                }
            },
            translateBlockFn = { src, dst, _, _ -> "$src->$dst" },
            onlineTranslateFn = { _, _ -> error("Offline auto-detect ML Kit should run when preferOnline=false") }
        )

        val urduResult = runBlocking {
            helper.translateTextDetailed("سلام دوست", "English", preferOnline = false)
        }
        assertTrue("LanguageIdentification must run first", identifyInvoked)
        assertEquals(TranslateLanguage.URDU, urduResult.sourceLanguageTag)
        assertEquals("ur->en", urduResult.translatedText)

        val frenchResult = runBlocking {
            helper.translateTextDetailed("Bonjour tout le monde", "Hindi", preferOnline = false)
        }
        assertEquals(TranslateLanguage.FRENCH, frenchResult.sourceLanguageTag)
        assertEquals("fr->hi", frenchResult.translatedText)

        assertTrue(ScreenTranslateHelper.SUPPORTED_LANGUAGES.contains("Urdu"))
        assertTrue(ScreenTranslateHelper.SUPPORTED_LANGUAGES.contains("Bengali"))
        assertTrue(ScreenTranslateHelper.SUPPORTED_LANGUAGES.contains("Arabic"))
        helper.close()
    }
}
