package com.example

import android.accessibilityservice.AccessibilityService
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
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.RectF
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.ImageButton
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
import kotlin.math.cos
import kotlin.math.sin

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SmartDotUpgradeFeaturesTest {

    private lateinit var appContext: Application

    @Before
    fun setUp() {
        appContext = ApplicationProvider.getApplicationContext()
        ShadowSettings.setCanDrawOverlays(true)
        appContext.getSharedPreferences("smart_dot_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        SmartDotAccessibilityService.setInstanceForTest(null)
    }

    @After
    fun tearDown() {
        SmartDotAccessibilityService.setInstanceForTest(null)
        ShadowSettings.setCanDrawOverlays(false)
    }

    // -------------------------------------------------------------
    // Feature 1: 1-Tap Quick-Tile Automation (Wi-Fi, Mobile Data, Bluetooth, Auto-Rotate)
    // -------------------------------------------------------------

    @Test
    fun `findMatchingTileInRoots resolves clickable parent and skips hotspot when matching Wi-Fi`() {
        val qsRoot = AccessibilityNodeInfo.obtain().apply { isVisibleToUser = true }

        val hotspotContainer = AccessibilityNodeInfo.obtain().apply {
            isClickable = true
            isEnabled = true
            isVisibleToUser = true
        }
        val hotspotLabel = AccessibilityNodeInfo.obtain().apply {
            text = "Wi-Fi hotspot"
            isVisibleToUser = true
        }
        Shadows.shadowOf(hotspotContainer).addChild(hotspotLabel)

        val wifiContainer = AccessibilityNodeInfo.obtain().apply {
            isClickable = true
            isEnabled = true
            isVisibleToUser = true
        }
        val wifiLabel = AccessibilityNodeInfo.obtain().apply {
            text = "Wi-Fi"
            isVisibleToUser = true
        }
        Shadows.shadowOf(wifiContainer).addChild(wifiLabel)

        val bluetoothTile = AccessibilityNodeInfo.obtain().apply {
            text = "Bluetooth"
            isClickable = true
            isEnabled = true
            isVisibleToUser = true
        }
        val mobileTile = AccessibilityNodeInfo.obtain().apply {
            text = "Mobile data"
            isClickable = true
            isEnabled = true
            isVisibleToUser = true
        }
        val rotateTile = AccessibilityNodeInfo.obtain().apply {
            contentDescription = "Auto-rotate"
            isClickable = true
            isEnabled = true
            isVisibleToUser = true
        }

        Shadows.shadowOf(qsRoot).addChild(hotspotContainer)
        Shadows.shadowOf(qsRoot).addChild(wifiContainer)
        Shadows.shadowOf(qsRoot).addChild(bluetoothTile)
        Shadows.shadowOf(qsRoot).addChild(mobileTile)
        Shadows.shadowOf(qsRoot).addChild(rotateTile)

        val wifiMatch = QuickTileAutomation.findMatchingTileInRoots(
            listOf(qsRoot),
            QuickTileTarget.WIFI.primaryKeywords
        )
        assertNotNull(wifiMatch)
        assertEquals("Wi-Fi", wifiMatch!!.matchedLabel)
        assertTrue(wifiMatch.clickableNode.isClickable)

        val btMatch = QuickTileAutomation.findMatchingTileInRoots(
            listOf(qsRoot),
            QuickTileTarget.BLUETOOTH.primaryKeywords
        )
        assertEquals("Bluetooth", btMatch?.matchedLabel)

        val mobileMatch = QuickTileAutomation.findMatchingTileInRoots(
            listOf(qsRoot),
            QuickTileTarget.MOBILE_DATA.primaryKeywords
        )
        assertEquals("Mobile data", mobileMatch?.matchedLabel)

        val rotateMatch = QuickTileAutomation.findMatchingTileInRoots(
            listOf(qsRoot),
            QuickTileTarget.AUTO_ROTATE.primaryKeywords
        )
        assertEquals("Auto-rotate", rotateMatch?.matchedLabel)
    }

    @Test
    fun `automateQuickSettingsToggle clicks Bluetooth tile directly and closes shade with Back`() {
        val dispatchedGlobals = mutableListOf<Int>()
        val btTile = AccessibilityNodeInfo.obtain().apply {
            text = "Bluetooth"
            isClickable = true
            isEnabled = true
            isVisibleToUser = true
        }
        var btClicked = false
        Shadows.shadowOf(btTile).setOnPerformActionListener { action, _ ->
            if (action == AccessibilityNodeInfo.ACTION_CLICK) {
                btClicked = true
                true
            } else {
                false
            }
        }
        val qsRoot = AccessibilityNodeInfo.obtain().apply { isVisibleToUser = true }
        Shadows.shadowOf(qsRoot).addChild(btTile)

        var callbackResult: Boolean? = null
        QuickTileAutomation.automateQuickSettingsToggle(
            target = QuickTileTarget.BLUETOOTH,
            handler = Handler(Looper.getMainLooper()),
            performGlobalActionFn = { action ->
                dispatchedGlobals.add(action)
                true
            },
            rootsProvider = { listOf(qsRoot) },
            onResult = { callbackResult = it }
        )

        ShadowLooper.idleMainLooper(500, TimeUnit.MILLISECONDS)

        assertTrue("Bluetooth tile must receive ACTION_CLICK", btClicked)
        assertEquals(true, callbackResult)
        assertEquals(
            listOf(
                AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS,
                AccessibilityService.GLOBAL_ACTION_BACK
            ),
            dispatchedGlobals
        )
    }

    @Test
    fun `automateQuickSettingsToggle drills into Android 12+ Internet panel for Mobile Data and closes shade`() {
        val dispatchedGlobals = mutableListOf<Int>()
        var internetOpened = false
        var mobileSwitchClicked = false

        val internetTile = AccessibilityNodeInfo.obtain().apply {
            text = "Internet"
            isClickable = true
            isEnabled = true
            isVisibleToUser = true
        }
        Shadows.shadowOf(internetTile).setOnPerformActionListener { action, _ ->
            if (action == AccessibilityNodeInfo.ACTION_CLICK) {
                internetOpened = true
                true
            } else {
                false
            }
        }
        val qsRoot = AccessibilityNodeInfo.obtain().apply { isVisibleToUser = true }
        Shadows.shadowOf(qsRoot).addChild(internetTile)

        val mobileDataSwitch = AccessibilityNodeInfo.obtain().apply {
            text = "Mobile data"
            isClickable = true
            isEnabled = true
            isVisibleToUser = true
        }
        Shadows.shadowOf(mobileDataSwitch).setOnPerformActionListener { action, _ ->
            if (action == AccessibilityNodeInfo.ACTION_CLICK) {
                mobileSwitchClicked = true
                true
            } else {
                false
            }
        }
        val internetDialogRoot = AccessibilityNodeInfo.obtain().apply { isVisibleToUser = true }
        Shadows.shadowOf(internetDialogRoot).addChild(mobileDataSwitch)

        var completed: Boolean? = null
        QuickTileAutomation.automateQuickSettingsToggle(
            target = QuickTileTarget.MOBILE_DATA,
            handler = Handler(Looper.getMainLooper()),
            performGlobalActionFn = { action ->
                dispatchedGlobals.add(action)
                true
            },
            rootsProvider = {
                if (!internetOpened) listOf(qsRoot) else listOf(internetDialogRoot)
            },
            onResult = { completed = it }
        )

        ShadowLooper.idleMainLooper(1000, TimeUnit.MILLISECONDS)

        assertTrue("Internet super-tile must be clicked first", internetOpened)
        assertTrue("Mobile data switch inside Internet panel must be clicked", mobileSwitchClicked)
        assertEquals(true, completed)
        assertEquals(
            listOf(
                AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS,
                AccessibilityService.GLOBAL_ACTION_BACK,
                AccessibilityService.GLOBAL_ACTION_BACK
            ),
            dispatchedGlobals
        )
    }

    @Test
    fun `FloatingService BT WiFi MobileData shortcuts toggle silently in background without opening Settings Panels`() {
        val service = Robolectric.buildService(FloatingService::class.java)
            .create()
            .startCommand(0, 1)
            .get()
        val executeAction = FloatingService::class.java
            .getDeclaredMethod("executeAction", String::class.java)
            .apply { isAccessible = true }

        val shadowApp = Shadows.shadowOf(appContext)
        val shellCommands = mutableListOf<String>()
        PrivilegedToggleHelper.shizukuShellTestHook = { cmd ->
            shellCommands.add(cmd)
            true
        }
        PrivilegedToggleHelper.mobileDataStateTestHook = { false }
        try {
            executeAction.invoke(service, "bluetooth")
            assertTrue(shellCommands.any { it.startsWith("svc bluetooth") })
            assertNull("Bluetooth must toggle silently without Settings Panel", shadowApp.nextStartedActivity)

            executeAction.invoke(service, "mobile_data")
            assertTrue(shellCommands.contains("svc data enable"))
            assertNull("Mobile Data must toggle silently without Settings Panel", shadowApp.nextStartedActivity)

            executeAction.invoke(service, "wifi")
            assertTrue(shellCommands.any { it.startsWith("svc wifi") })
            assertNull("Wi-Fi must toggle silently without Settings Panel", shadowApp.nextStartedActivity)
        } finally {
            PrivilegedToggleHelper.shizukuShellTestHook = null
            PrivilegedToggleHelper.mobileDataStateTestHook = null
        }

        // Unprivileged fallback still must NEVER open BT/Wi-Fi/Mobile Data Settings Panels
        executeAction.invoke(service, "bluetooth")
        assertNull(shadowApp.nextStartedActivity)
        executeAction.invoke(service, "wifi")
        assertNull(shadowApp.nextStartedActivity)

        Settings.System.putInt(appContext.contentResolver, Settings.System.ACCELEROMETER_ROTATION, 0)
        executeAction.invoke(service, "auto_rotate")
        assertEquals(
            1,
            Settings.System.getInt(appContext.contentResolver, Settings.System.ACCELEROMETER_ROTATION, 0)
        )
        assertEquals(
            Settings.ACTION_DISPLAY_SETTINGS,
            QuickTileAutomation.buildFallbackSettingsIntent(QuickTileTarget.AUTO_ROTATE).action
        )
    }

    // -------------------------------------------------------------
    // Feature 2: Fix Split Screen Action
    // -------------------------------------------------------------

    @Test
    fun `performSplitScreen returns true on success and triggers direct Recents fallback with zero guidance toast`() {
        val a11yService = Robolectric.buildService(SmartDotAccessibilityService::class.java).create().get()
        SmartDotAccessibilityService.setInstanceForTest(a11yService)

        val actions = mutableListOf<Int>()
        a11yService.performGlobalActionTestHook = { action ->
            actions.add(action)
            action == AccessibilityService.GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN
        }

        assertTrue(a11yService.performSplitScreen())
        assertEquals(listOf(AccessibilityService.GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN), actions)

        // Simulate OEM blocking GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN (returns false)
        actions.clear()
        a11yService.performGlobalActionTestHook = { action ->
            actions.add(action)
            action != AccessibilityService.GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN
        }

        val floatingService = Robolectric.buildService(FloatingService::class.java)
            .create()
            .startCommand(0, 1)
            .get()
        val executeAction = FloatingService::class.java
            .getDeclaredMethod("executeAction", String::class.java)
            .apply { isAccessible = true }

        ShadowToast.reset()
        executeAction.invoke(floatingService, "split_screen")
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(400, TimeUnit.MILLISECONDS)

        assertEquals(
            listOf(
                AccessibilityService.GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN,
                AccessibilityService.GLOBAL_ACTION_RECENTS
            ),
            actions
        )
        assertNull(
            "Direct 1-tap Split Screen must not show any manual guidance toast",
            ShadowToast.getTextOfLatestToast()
        )
    }

    // -------------------------------------------------------------
    // Feature 3: 5-Action Word Pill & Circle-to-Search Cutout Box
    // -------------------------------------------------------------

    @Test
    fun `CircleCutoutAndMediaHelper detects closed loops, ignores straight highlights, and crops exact screen rect`() {
        val density = 2f
        val circlePoints = (0..24).map { step ->
            val angle = (step / 24.0) * 2.0 * Math.PI
            PointF(
                300f + (120f * cos(angle)).toFloat(),
                400f + (100f * sin(angle)).toFloat()
            )
        }
        val loopRect = CircleCutoutAndMediaHelper.detectCircleLoopBounds(circlePoints, density)
        assertNotNull("Closed loop gesture must convert into a cutout RectF", loopRect)
        assertTrue(loopRect!!.width() > 200f)
        assertTrue(loopRect.height() > 160f)

        val straightLinePoints = (0..12).map { step ->
            PointF(100f + step * 35f, 300f + (step % 2) * 2f)
        }
        assertNull(
            "Straight horizontal drag must stay in word highlighter mode",
            CircleCutoutAndMediaHelper.detectCircleLoopBounds(straightLinePoints, density)
        )

        val fullBitmap = Bitmap.createBitmap(1080, 2400, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.DKGRAY)
        }
        val cropped = CircleCutoutAndMediaHelper.cropBitmapToScreenRect(
            bitmap = fullBitmap,
            screenRect = RectF(100f, 200f, 400f, 600f),
            viewWidth = 1080,
            viewHeight = 2400
        )
        assertNotNull(cropped)
        assertEquals(300, cropped!!.width)
        assertEquals(400, cropped.height)
        cropped.recycle()
        fullBitmap.recycle()
    }

    @Test
    fun `5-Action Word Pill supports Copy, Speak, Inline Translate, Google Search, and Full Screen Save`() {
        val scope = CoroutineScope(Dispatchers.Main + Job())
        val clipboard = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.clearPrimaryClip()

        val screenBitmap = Bitmap.createBitmap(1080, 2400, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.BLUE)
        }
        val ocrHelper = ScreenOcrHelper(
            customOcrRunner = {
                val words = listOf(
                    ScreenOcrHelper.OcrRawItem("Quantum Physics", Rect(100, 300, 520, 370), isLine = true),
                    ScreenOcrHelper.OcrRawItem("Quantum", Rect(100, 300, 300, 370), isLine = false),
                    ScreenOcrHelper.OcrRawItem("Physics", Rect(320, 300, 520, 370), isLine = false)
                )
                words to emptyList()
            }
        )
        val translateHelper = ScreenTranslateHelper(
            identifyLanguageFn = { "en" },
            translateBlockFn = { _, dst, text, _ ->
                if (dst == com.google.mlkit.nl.translate.TranslateLanguage.HINDI) "क्वांटम" else "$text [$dst]"
            },
            onlineTranslateFn = { _, _ -> null }
        )

        var spokenText: String? = null
        var savedPrefix: String? = null
        var savedWidth = 0

        val controller = ScreenTextCopyController(
            context = appContext,
            serviceScope = scope,
            clipboardManager = clipboard,
            getAccentColor = { Color.parseColor("#00E5FF") },
            dismissActivePopup = {},
            setHostOverlaysVisible = {},
            onAccessibilityRequired = {},
            nodeExtractor = { emptyList() },
            ocrHelperFactory = { ocrHelper },
            screenBitmapCapturer = { cb -> cb(screenBitmap) },
            translateHelperFactory = { translateHelper },
            customSpeakHandler = { spokenText = it },
            customGallerySaver = { bmp, prefix ->
                savedPrefix = prefix
                savedWidth = bmp.width
                Uri.parse("content://media/external/images/media/99")
            }
        )

        controller.start()
        waitUntilOverlayVisible(controller)

        val overlayField = ScreenTextCopyController::class.java
            .getDeclaredField("overlayController")
            .apply { isAccessible = true }
        val overlayCtrl = overlayField.get(controller) as InlineTextOverlayController
        val root = getOverlayRoot(overlayCtrl)
        measureAndLayout(root)

        val selView = root.findViewById<InlineTextSelectionView>(R.id.inline_ocr_selection_view)
        val quantumRect = selView.getMappedRectForCopyNode(0)!!
        tapAt(selView, quantumRect.centerX(), quantumRect.centerY())

        val actionBar = root.findViewById<LinearLayout>(R.id.inline_word_action_bar)
        val btnCopy = root.findViewById<ImageButton>(R.id.btn_inline_ocr_copy)
        val btnSpeak = root.findViewById<ImageButton>(R.id.btn_inline_word_speak)
        val btnTranslate = root.findViewById<ImageButton>(R.id.btn_inline_word_translate)
        val btnSearch = root.findViewById<ImageButton>(R.id.btn_inline_word_search)
        val btnSaveFull = root.findViewById<ImageButton>(R.id.btn_inline_word_save_full)

        assertEquals(View.VISIBLE, actionBar.visibility)
        assertEquals(View.VISIBLE, btnCopy.visibility)
        assertEquals(View.VISIBLE, btnSpeak.visibility)
        assertEquals(View.VISIBLE, btnTranslate.visibility)
        assertEquals(View.VISIBLE, btnSearch.visibility)
        assertEquals(View.VISIBLE, btnSaveFull.visibility)

        // 1) Read Aloud
        btnSpeak.performClick()
        assertEquals("Quantum", spokenText)

        // 2) Inline Translate bubble
        btnTranslate.performClick()
        val translateBubble = root.findViewById<LinearLayout>(R.id.inline_word_translate_bubble)
        val tvTranslated = root.findViewById<TextView>(R.id.tv_inline_word_translated_text)
        val deadline = System.currentTimeMillis() + 3000L
        while (tvTranslated.text.toString() != "क्वांटम" && System.currentTimeMillis() < deadline) {
            ShadowLooper.idleMainLooper(20, TimeUnit.MILLISECONDS)
            Thread.sleep(10)
        }
        assertEquals(View.VISIBLE, translateBubble.visibility)
        assertEquals("क्वांटम", tvTranslated.text.toString())

        // 3) Full Screen Image save
        btnSaveFull.performClick()
        ShadowLooper.idleMainLooper(100, TimeUnit.MILLISECONDS)
        val saveDeadline = System.currentTimeMillis() + 3000L
        while (savedPrefix == null && System.currentTimeMillis() < saveDeadline) {
            ShadowLooper.idleMainLooper(20, TimeUnit.MILLISECONDS)
            Thread.sleep(10)
        }
        assertEquals("SmartDot_Screen", savedPrefix)
        assertEquals(1080, savedWidth)

        // 4) Search opens Google App directly via ACTION_WEB_SEARCH (or Google Search URL fallback when absent)
        val shadowPm = Shadows.shadowOf(appContext.packageManager)
        val googleResolveInfo = ResolveInfo().apply {
            activityInfo = ActivityInfo().apply {
                packageName = SelectionToolbarHelper.GOOGLE_APP_PACKAGE
                name = "com.google.android.googlequicksearchbox.SearchActivity"
                applicationInfo = ApplicationInfo().apply {
                    packageName = SelectionToolbarHelper.GOOGLE_APP_PACKAGE
                }
            }
        }
        shadowPm.addResolveInfoForIntent(
            Intent(Intent.ACTION_WEB_SEARCH).setPackage(SelectionToolbarHelper.GOOGLE_APP_PACKAGE),
            googleResolveInfo
        )
        btnSearch.performClick()
        ShadowLooper.idleMainLooper()
        val searchIntent = Shadows.shadowOf(appContext).nextStartedActivity
        assertNotNull("Search button should launch Google App search intent", searchIntent)
        assertEquals(Intent.ACTION_WEB_SEARCH, searchIntent.action)
        assertEquals(SelectionToolbarHelper.GOOGLE_APP_PACKAGE, searchIntent.`package`)
        assertEquals("Quantum", searchIntent.getStringExtra(SearchManager.QUERY))
        assertFalse(controller.isOverlayShowing())

        controller.release()
        scope.cancel()
    }

    @Test
    fun `Circle-to-Search Cutout mode supports loop creation, corner resize, move, Save, Lens, Copy Text, and Close`() {
        val scope = CoroutineScope(Dispatchers.Main + Job())
        val clipboard = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.clearPrimaryClip()

        val screenBitmap = Bitmap.createBitmap(1080, 2400, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.GREEN)
        }
        val ocrHelper = ScreenOcrHelper(
            customOcrRunner = {
                val items = listOf(
                    ScreenOcrHelper.OcrRawItem("Circle Text Inside", Rect(0, 0, 200, 50), isLine = true),
                    ScreenOcrHelper.OcrRawItem("Outside", Rect(800, 1800, 980, 1860), isLine = false)
                )
                items to emptyList()
            }
        )

        var cutoutSavedPrefix: String? = null
        var cutoutSavedBitmapSize: Pair<Int, Int>? = null
        var visualSearchBitmapSize: Pair<Int, Int>? = null

        val controller = ScreenTextCopyController(
            context = appContext,
            serviceScope = scope,
            clipboardManager = clipboard,
            getAccentColor = { Color.parseColor("#00E5FF") },
            dismissActivePopup = {},
            setHostOverlaysVisible = {},
            onAccessibilityRequired = {},
            nodeExtractor = { emptyList() },
            ocrHelperFactory = { ocrHelper },
            screenBitmapCapturer = { cb -> cb(screenBitmap) },
            customGallerySaver = { bmp, prefix ->
                cutoutSavedPrefix = prefix
                cutoutSavedBitmapSize = bmp.width to bmp.height
                Uri.parse("content://media/external/images/media/101")
            },
            customVisualSearchLauncher = { bmp ->
                visualSearchBitmapSize = bmp.width to bmp.height
                true
            }
        )

        controller.start()
        waitUntilOverlayVisible(controller)

        val overlayField = ScreenTextCopyController::class.java
            .getDeclaredField("overlayController")
            .apply { isAccessible = true }
        val overlayCtrl = overlayField.get(controller) as InlineTextOverlayController
        val root = getOverlayRoot(overlayCtrl)
        measureAndLayout(root)

        val selView = root.findViewById<InlineTextSelectionView>(R.id.inline_ocr_selection_view)
        val cutoutBar = root.findViewById<LinearLayout>(R.id.inline_cutout_action_bar)
        assertEquals(View.GONE, cutoutBar.visibility)

        // Draw a closed circular loop around (200..600, 500..900)
        dispatchCircleGesture(selView, cx = 400f, cy = 700f, rx = 200f, ry = 200f)

        val initialBox = selView.getActiveCutoutRect()
        assertNotNull("Closed circle gesture must produce resizable cutout box", initialBox)
        assertEquals(View.VISIBLE, cutoutBar.visibility)

        // Drag bottom-right corner handle (+60, +40) to resize box
        val brX = initialBox!!.right
        val brY = initialBox.bottom
        dispatchDrag(selView, brX, brY, brX + 60f, brY + 40f)
        val resizedBox = selView.getActiveCutoutRect()!!
        assertEquals(initialBox.right + 60f, resizedBox.right, 2f)
        assertEquals(initialBox.bottom + 40f, resizedBox.bottom, 2f)

        // Drag inside cutout box (+50, +30) to move entire box
        dispatchDrag(selView, resizedBox.centerX(), resizedBox.centerY(), resizedBox.centerX() + 50f, resizedBox.centerY() + 30f)
        val movedBox = selView.getActiveCutoutRect()!!
        assertEquals(resizedBox.left + 50f, movedBox.left, 2f)
        assertEquals(resizedBox.top + 30f, movedBox.top, 2f)
        assertEquals(resizedBox.width(), movedBox.width(), 2f)

        // 1) Save Cutout crops strictly the box area
        root.findViewById<ImageButton>(R.id.btn_cutout_save).performClick()
        val saveDeadline = System.currentTimeMillis() + 3000L
        while (cutoutSavedPrefix == null && System.currentTimeMillis() < saveDeadline) {
            ShadowLooper.idleMainLooper(20, TimeUnit.MILLISECONDS)
            Thread.sleep(10)
        }
        assertEquals("SmartDot_Cutout", cutoutSavedPrefix)
        assertEquals(movedBox.width().toInt(), cutoutSavedBitmapSize!!.first)
        assertEquals(movedBox.height().toInt(), cutoutSavedBitmapSize!!.second)

        // 2) Copy Text in Box runs OCR on cropped box and copies to clipboard
        root.findViewById<ImageButton>(R.id.btn_cutout_copy_text).performClick()
        val copyDeadline = System.currentTimeMillis() + 3000L
        while (clipboard.primaryClip == null && System.currentTimeMillis() < copyDeadline) {
            ShadowLooper.idleMainLooper(20, TimeUnit.MILLISECONDS)
            Thread.sleep(10)
        }
        assertEquals("Circle Text Inside", clipboard.primaryClip?.getItemAt(0)?.text?.toString())
        assertFalse(controller.isOverlayShowing())

        // Re-open overlay and test Close Cutout + Visual Search
        controller.start()
        waitUntilOverlayVisible(controller)
        val root2 = getOverlayRoot(overlayField.get(controller) as InlineTextOverlayController)
        measureAndLayout(root2)
        val selView2 = root2.findViewById<InlineTextSelectionView>(R.id.inline_ocr_selection_view)
        val cutoutBar2 = root2.findViewById<LinearLayout>(R.id.inline_cutout_action_bar)

        dispatchCircleGesture(selView2, cx = 400f, cy = 700f, rx = 180f, ry = 180f)
        assertNotNull(selView2.getActiveCutoutRect())
        root2.findViewById<ImageButton>(R.id.btn_cutout_close).performClick()
        assertNull("Closing cutout bar must clear active cutout box", selView2.getActiveCutoutRect())
        assertEquals(View.GONE, cutoutBar2.visibility)

        // Draw loop again and test Visual Search (Lens)
        dispatchCircleGesture(selView2, cx = 400f, cy = 700f, rx = 180f, ry = 180f)
        val lensBox = selView2.getActiveCutoutRect()!!
        root2.findViewById<ImageButton>(R.id.btn_cutout_visual_search).performClick()
        assertNotNull(visualSearchBitmapSize)
        assertEquals(lensBox.width().toInt(), visualSearchBitmapSize!!.first)
        assertFalse(controller.isOverlayShowing())

        controller.release()
        scope.cancel()
    }

    // -------------------------------------------------------------
    // Feature 4: "Floating Window" Shortcut Pop-Out Active App
    // -------------------------------------------------------------

    @Test
    fun `Floating Window shortcut exists in ACTION_LIST and relaunches active foreground app in freeform mode`() {
        val actionEntry = ACTION_LIST.find { it.id == "floating_window" }
        assertNotNull(actionEntry)
        assertEquals("Floating Window", actionEntry!!.name)
        assertTrue(SwipeGestureHelper.GESTURE_ACTIONS.any { it.id == "floating_window" })

        val a11yService = Robolectric.buildService(SmartDotAccessibilityService::class.java).create().get()
        SmartDotAccessibilityService.setInstanceForTest(a11yService)

        val targetPkg = "com.sample.reader"
        val targetActivity = "com.sample.reader.ReaderActivity"

        val shadowPm = Shadows.shadowOf(appContext.packageManager)
        val resolveInfo = ResolveInfo().apply {
            activityInfo = ActivityInfo().apply {
                packageName = targetPkg
                name = targetActivity
                applicationInfo = ApplicationInfo().apply {
                    packageName = targetPkg
                    nonLocalizedLabel = "Sample Reader"
                }
            }
        }
        val mainLauncherIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
            setPackage(targetPkg)
        }
        shadowPm.addResolveInfoForIntent(mainLauncherIntent, resolveInfo)

        val activeRoot = AccessibilityNodeInfo.obtain().apply {
            packageName = targetPkg
            isVisibleToUser = true
        }
        a11yService.activeRootTestHook = activeRoot

        val floatingService = Robolectric.buildService(FloatingService::class.java)
            .create()
            .startCommand(0, 1)
            .get()
        val getActionIcon = FloatingService::class.java
            .getDeclaredMethod("getActionIcon", String::class.java)
            .apply { isAccessible = true }
        assertEquals(R.drawable.ic_floating_window, getActionIcon.invoke(floatingService, "floating_window"))

        val executeAction = FloatingService::class.java
            .getDeclaredMethod("executeAction", String::class.java)
            .apply { isAccessible = true }
        executeAction.invoke(floatingService, "floating_window")
        ShadowLooper.idleMainLooper()

        val relaunched = Shadows.shadowOf(appContext).nextStartedActivity
        assertNotNull("Floating Window shortcut must launch active foreground app", relaunched)
        assertEquals(targetPkg, relaunched.component?.packageName ?: relaunched.`package`)
        assertTrue((relaunched.flags and Intent.FLAG_ACTIVITY_NEW_TASK) != 0)
        assertEquals(0, relaunched.flags and Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
    }

    private fun waitUntilOverlayVisible(controller: ScreenTextCopyController) {
        ShadowLooper.idleMainLooper(ScreenTextCopyController.CAPTURE_HIDE_DELAY_MS + 10L, TimeUnit.MILLISECONDS)
        val deadline = System.currentTimeMillis() + 3000L
        while (!controller.isOverlayShowing() && System.currentTimeMillis() < deadline) {
            ShadowLooper.idleMainLooper(25, TimeUnit.MILLISECONDS)
            Thread.sleep(15)
        }
        assertTrue("Overlay must become visible", controller.isOverlayShowing())
    }

    private fun measureAndLayout(root: View) {
        root.measure(
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(2400, View.MeasureSpec.EXACTLY)
        )
        root.layout(0, 0, 1080, 2400)
    }

    private fun tapAt(view: View, x: Float, y: Float) {
        val down = MotionEvent.obtain(100L, 100L, MotionEvent.ACTION_DOWN, x, y, 0)
        val up = MotionEvent.obtain(100L, 130L, MotionEvent.ACTION_UP, x, y, 0)
        view.dispatchTouchEvent(down)
        view.dispatchTouchEvent(up)
        down.recycle()
        up.recycle()
    }

    private fun dispatchDrag(view: View, x1: Float, y1: Float, x2: Float, y2: Float) {
        val down = MotionEvent.obtain(100L, 100L, MotionEvent.ACTION_DOWN, x1, y1, 0)
        val move = MotionEvent.obtain(100L, 120L, MotionEvent.ACTION_MOVE, x2, y2, 0)
        val up = MotionEvent.obtain(100L, 140L, MotionEvent.ACTION_UP, x2, y2, 0)
        view.dispatchTouchEvent(down)
        view.dispatchTouchEvent(move)
        view.dispatchTouchEvent(up)
        down.recycle()
        move.recycle()
        up.recycle()
    }

    private fun dispatchCircleGesture(view: View, cx: Float, cy: Float, rx: Float, ry: Float) {
        val steps = 20
        val events = mutableListOf<MotionEvent>()
        val t0 = 200L
        for (i in 0..steps) {
            val angle = (i / steps.toDouble()) * 2.0 * Math.PI
            val x = cx + (rx * cos(angle)).toFloat()
            val y = cy + (ry * sin(angle)).toFloat()
            val action = when (i) {
                0 -> MotionEvent.ACTION_DOWN
                steps -> MotionEvent.ACTION_UP
                else -> MotionEvent.ACTION_MOVE
            }
            val ev = MotionEvent.obtain(t0, t0 + i * 15L, action, x, y, 0)
            events.add(ev)
            view.dispatchTouchEvent(ev)
        }
        events.forEach { it.recycle() }
    }

    private fun getOverlayRoot(controller: InlineTextOverlayController): View {
        val field = InlineTextOverlayController::class.java
            .getDeclaredField("overlayView")
            .apply { isAccessible = true }
        return field.get(controller) as View
    }
}
