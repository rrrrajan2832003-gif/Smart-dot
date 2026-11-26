package com.example

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.webkit.WebView
import android.widget.LinearLayout
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class SmartDotNewUpgradesTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private lateinit var appContext: Application

    @Before
    fun setUp() {
        appContext = ApplicationProvider.getApplicationContext()
        appContext.getSharedPreferences("smart_dot_prefs", Context.MODE_PRIVATE)
            .edit().clear().commit()
        clearFileProviderCache()
        ShadowLooper.idleMainLooper()
    }

    @After
    fun tearDown() {
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

    private fun registerResolveActivity(
        action: String,
        pkg: String,
        className: String = "$pkg.MockActivity",
        mimeType: String? = null
    ) {
        val shadowPm = Shadows.shadowOf(appContext.packageManager)
        val component = ComponentName(pkg, className)
        shadowPm.addActivityIfNotPresent(component)
        val resolveInfo = android.content.pm.ResolveInfo().apply {
            activityInfo = ActivityInfo().apply {
                packageName = pkg
                name = className
                exported = true
                applicationInfo = android.content.pm.ApplicationInfo().apply { packageName = pkg }
            }
        }
        val filter = Intent(action).apply {
            setPackage(pkg)
            if (mimeType != null) type = mimeType
        }
        shadowPm.addResolveInfoForIntent(filter, resolveInfo)
    }

    private fun getOverlayRoot(controller: InlineTextOverlayController): View {
        val field = InlineTextOverlayController::class.java.getDeclaredField("overlayView")
        field.isAccessible = true
        return field.get(controller) as View
    }

    private fun getSelectionControllerOverlay(ctrl: ScreenTextCopyController): InlineTextOverlayController? {
        val field = ScreenTextCopyController::class.java.getDeclaredField("overlayController")
        field.isAccessible = true
        return field.get(ctrl) as? InlineTextOverlayController
    }

    @Test
    fun `Upgrade 1 - Manifest declares media permissions and Floating Gallery Music Media show Grant button launching MediaPermissionActivity`() {
        val pkgInfo = appContext.packageManager.getPackageInfo(
            appContext.packageName,
            PackageManager.GET_PERMISSIONS
        )
        val requested = pkgInfo.requestedPermissions?.toList().orEmpty()
        assertTrue(requested.contains("android.permission.READ_MEDIA_IMAGES"))
        assertTrue(requested.contains("android.permission.READ_MEDIA_VIDEO"))
        assertTrue(requested.contains("android.permission.READ_MEDIA_AUDIO"))

        val wm = appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val fwm = FloatingWindowManager(appContext, wm)
        try {
            while (Shadows.shadowOf(appContext).nextStartedActivity != null) { /* drain */ }

            val galleryWin = FloatingMiniApps.openFloatingPhotosVideosGallery(appContext, fwm, Color.CYAN)!!
            val btnGrantGallery = galleryWin.rootView.findViewWithTag<View>("fw_gallery_grant_perm")
            assertNotNull(btnGrantGallery)
            assertEquals(View.VISIBLE, btnGrantGallery.visibility)

            btnGrantGallery.performClick()
            val launched = Shadows.shadowOf(appContext).nextStartedActivity
            assertNotNull(launched)
            assertEquals(MediaPermissionActivity::class.java.name, launched.component?.className)

            val musicWin = FloatingMiniApps.openFloatingMusicPlayer(appContext, fwm, Color.CYAN)!!
            val btnGrantMusic = musicWin.rootView.findViewWithTag<View>("fw_music_grant_perm")
            assertNotNull(btnGrantMusic)
            assertEquals(View.VISIBLE, btnGrantMusic.visibility)

            val mediaWin = FloatingMiniApps.openFloatingMediaFiles(appContext, fwm, Color.CYAN)!!
            val btnGrantMedia = mediaWin.rootView.findViewWithTag<View>("fw_media_grant_perm")
            assertNotNull(btnGrantMedia)
            assertEquals(View.VISIBLE, btnGrantMedia.visibility)
        } finally {
            fwm.closeAllWindows()
        }
    }

    @Test
    fun `Upgrade 2 - Google Lens action dismisses word overlay and launches clean Lens intent after 180ms`() {
        registerResolveActivity(
            action = Intent.ACTION_SEND,
            pkg = "com.google.ar.lens",
            mimeType = "image/png"
        )
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val clipboard = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val testBmp = Bitmap.createBitmap(400, 800, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        val controller = ScreenTextCopyController(
            context = appContext,
            serviceScope = scope,
            clipboardManager = clipboard,
            getAccentColor = { Color.CYAN },
            dismissActivePopup = {},
            setHostOverlaysVisible = {},
            onAccessibilityRequired = {},
            nodeExtractor = {
                listOf(SmartDotAccessibilityService.ScreenTextNode(0, "Hello", Rect(40, 100, 180, 150)))
            },
            ocrHelperFactory = {
                ScreenOcrHelper(
                    customOcrRunner = {
                        Pair(
                            listOf(ScreenOcrHelper.OcrRawItem("Hello", Rect(40, 100, 180, 150), isLine = false)),
                            emptyList()
                        )
                    }
                )
            },
            screenBitmapCapturer = { cb -> cb(testBmp) }
        )

        try {
            while (Shadows.shadowOf(appContext).nextStartedActivity != null) { /* drain */ }
            controller.scanAndShowOverlay()
            for (i in 0 until 25) {
                ShadowLooper.idleMainLooper(20L, TimeUnit.MILLISECONDS)
                if (controller.isOverlayShowing()) break
                Thread.sleep(10)
            }

            val overlay = getSelectionControllerOverlay(controller)!!
            assertTrue(overlay.isShowing())

            // Simulate tapping Google Lens inside the Selection Toolbar
            val lensBtn = getOverlayRoot(overlay).findViewById<View>(R.id.btn_inline_word_lens)
            assertNotNull(lensBtn)
            lensBtn.performClick()

            // Overlay must dismiss immediately before Lens screenshot/launch
            assertFalse("Word overlay must hide immediately on Lens tap", overlay.isShowing())
            assertFalse(controller.isOverlayShowing())

            ShadowLooper.idleMainLooper(220L, TimeUnit.MILLISECONDS)
            val launched = Shadows.shadowOf(appContext).nextStartedActivity
            assertNotNull("Google Lens intent must fire cleanly after 180ms", launched)
            assertEquals("com.google.ar.lens", launched.`package`)
            assertEquals(Intent.ACTION_SEND, launched.action)
        } finally {
            controller.release()
            scope.cancel()
        }
    }

    @Test
    fun `Upgrade 3 & 4 - 4dp stroke inner-50-core drag, 12dp single-word tap, and double-tap select all reading order`() {
        val wm = appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val controller = InlineTextOverlayController(appContext, wm)
        val density = appContext.resources.displayMetrics.density

        // Intentionally out of reading order to verify reading-order sort on Double-Tap Select All
        val ocrWords = listOf(
            SmartDotAccessibilityService.ScreenTextNode(0, "Row2First", Rect(40, 300, 220, 350)),
            SmartDotAccessibilityService.ScreenTextNode(1, "Row1Second", Rect(260, 120, 440, 170)),
            SmartDotAccessibilityService.ScreenTextNode(2, "Row1First", Rect(40, 120, 220, 170)),
            SmartDotAccessibilityService.ScreenTextNode(3, "Adjacent", Rect(40, 182, 220, 232))
        )

        controller.showCopyOverlay(
            nodes = ocrWords,
            accentColor = Color.CYAN,
            onCopyClicked = {},
            onRescanRequested = {},
            onDismiss = {}
        )

        val root = getOverlayRoot(controller)
        root.measure(
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(2400, View.MeasureSpec.EXACTLY)
        )
        root.layout(0, 0, 1080, 2400)

        val selView = root.findViewById<InlineTextSelectionView>(R.id.inline_ocr_selection_view)
        assertEquals(4f * density, selView.getHighlighterStrokeWidthPx(), 0.01f)

        // Single-word tap selects strictly 1 word
        val r1First = selView.getMappedRectForCopyNode(2)!!
        val tapDown = MotionEvent.obtain(10L, 10L, MotionEvent.ACTION_DOWN, r1First.centerX(), r1First.centerY(), 0)
        val tapUp = MotionEvent.obtain(10L, 25L, MotionEvent.ACTION_UP, r1First.centerX(), r1First.centerY(), 0)
        selView.dispatchTouchEvent(tapDown)
        selView.dispatchTouchEvent(tapUp)
        tapDown.recycle()
        tapUp.recycle()
        assertEquals(listOf("Row1First"), selView.getSelectedCopyNodes().map { it.text })

        // Drag across inner 50% core of Row1First -> Row1Second does NOT touch Adjacent below
        val r1Second = selView.getMappedRectForCopyNode(1)!!
        val dragDown = MotionEvent.obtain(500L, 500L, MotionEvent.ACTION_DOWN, r1First.centerX(), r1First.centerY(), 0)
        val dragMove = MotionEvent.obtain(500L, 520L, MotionEvent.ACTION_MOVE, r1Second.centerX(), r1Second.centerY(), 0)
        val dragUp = MotionEvent.obtain(500L, 540L, MotionEvent.ACTION_UP, r1Second.centerX(), r1Second.centerY(), 0)
        selView.dispatchTouchEvent(dragDown)
        selView.dispatchTouchEvent(dragMove)
        selView.dispatchTouchEvent(dragUp)
        dragDown.recycle()
        dragMove.recycle()
        dragUp.recycle()
        assertEquals(listOf("Row1First", "Row1Second"), selView.getSelectedCopyNodes().map { it.text })

        // Double-Tap selects all words in top-to-bottom, left-to-right reading order and shows action bar
        val selectAllMethod = InlineTextSelectionView::class.java.getDeclaredMethod("selectAllScreenWords").apply {
            isAccessible = true
        }
        selectAllMethod.invoke(selView)
        assertEquals(
            "Row1First Row1Second Adjacent Row2First",
            selView.getCombinedSelectedCopyText()
        )
        val floatingBar = root.findViewById<LinearLayout>(R.id.inline_word_action_bar)
        assertEquals(View.VISIBLE, floatingBar.visibility)

        controller.dismiss()
    }

    @Test
    fun `Upgrade 5 & 6 - Back key and bottom pill swipe-up close window, and IME keyboard auto-lifts and restores window`() {
        val wm = appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val fwm = FloatingWindowManager(appContext, wm)
        try {
            val win = FloatingMiniApps.openFloatingNotes(appContext, fwm, CoroutineScope(SupervisorJob() + Dispatchers.Main), Color.CYAN)!!
            // FLAG_NOT_FOCUSABLE must NOT be set so system Back key reaches the window
            assertEquals(0, win.layoutParams.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)

            // Back key closes the window when no inner backstack applies
            val backDown = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK)
            val backUp = KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK)
            assertTrue(win.rootView.dispatchKeyEvent(backDown))
            assertTrue(win.rootView.dispatchKeyEvent(backUp))
            assertFalse("Back button should close floating window", fwm.hasWindow("floating_notes"))

            // Reopen and verify bottom pill swipe-up (> 80px upward) closes window
            val win2 = FloatingMiniApps.openFloatingNotes(appContext, fwm, CoroutineScope(SupervisorJob() + Dispatchers.Main), Color.CYAN)!!
            val pill = win2.rootView.findViewWithTag<View>("fw_bottom_pill_floating_notes")
            assertNotNull(pill)
            val evDown = MotionEvent.obtain(100L, 100L, MotionEvent.ACTION_DOWN, 150f, 500f, 0)
            val evMove = MotionEvent.obtain(100L, 120L, MotionEvent.ACTION_MOVE, 150f, 380f, 0) // dy = -120f
            val evUp = MotionEvent.obtain(100L, 140L, MotionEvent.ACTION_UP, 150f, 380f, 0)
            pill.dispatchTouchEvent(evDown)
            pill.dispatchTouchEvent(evMove)
            pill.dispatchTouchEvent(evUp)
            evDown.recycle()
            evMove.recycle()
            evUp.recycle()
            assertFalse("Swiping bottom pill upward must close window", fwm.hasWindow("floating_notes"))

            // Reopen and verify keyboard auto-lift & restore
            val win3 = FloatingMiniApps.openFloatingNotes(appContext, fwm, CoroutineScope(SupervisorJob() + Dispatchers.Main), Color.CYAN)!!
            win3.layoutParams.height = 500
            win3.layoutParams.y = 600
            fwm.handleKeyboardHeightChanged(win3, keyboardHeightPx = 450, screenHeightPx = 1200)
            // Expected Y = max(20, 1200 - 450 - 500 - 24) = 226
            assertEquals(226, win3.layoutParams.y)

            // Keyboard dismissed -> restores Y back to 600
            fwm.handleKeyboardHeightChanged(win3, keyboardHeightPx = 0, screenHeightPx = 1200)
            assertEquals(600, win3.layoutParams.y)
        } finally {
            fwm.closeAllWindows()
        }
    }

    @Test
    fun `Upgrade 7 - SwipeGestureHelper GESTURE_ACTIONS exposes every shortcut from ACTION_LIST`() {
        val gestureIds = SwipeGestureHelper.GESTURE_ACTIONS.map { it.id }.toSet()
        assertTrue(gestureIds.contains("none"))
        assertTrue(gestureIds.contains("open_menu"))
        ACTION_LIST.forEach { action ->
            assertTrue(
                "Gesture picker must expose ${action.id}",
                gestureIds.contains(action.id)
            )
        }
        assertTrue(gestureIds.contains("google_ai_mode"))
        assertTrue(gestureIds.contains("chatgpt"))
        assertTrue(gestureIds.contains("gemini"))
        assertTrue(gestureIds.contains("google_lens"))
        assertTrue(gestureIds.contains("auto_clicker"))
        assertTrue(gestureIds.contains("black_screen_mode"))
    }

    @Test
    fun `Upgrade 8 - Direct WhatsApp Chat builder saves Meta AI shortcut, shows in Gestures, and opens WhatsApp Web URL inside floating window`() {
        composeRule.onNodeWithTag("custom_wa_builder_card").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("input_custom_wa_title").performScrollTo().performTextInput("Meta AI")
        composeRule.onNodeWithTag("input_custom_wa_phone").performScrollTo().performTextInput("+1 313-555-0002")
        composeRule.onNodeWithTag("btn_save_custom_wa").performScrollTo().performClick()
        composeRule.waitForIdle()

        val prefs = appContext.getSharedPreferences("smart_dot_prefs", Context.MODE_PRIVATE)
        val savedList = getCustomWhatsAppShortcuts(prefs)
        assertEquals(1, savedList.size)
        val entry = savedList[0]
        assertEquals("Meta AI", entry.title)
        assertEquals("13135550002", entry.phoneDigits)
        assertEquals("https://web.whatsapp.com/send?phone=13135550002", entry.buildDirectChatUrl())

        // Verify it was appended to shortcuts_list and appears in SwipeGestureHelper options
        assertTrue(prefs.getString("shortcuts_list", "").orEmpty().contains(entry.id))
        assertTrue(SwipeGestureHelper.getAllGestureOptions(prefs).any { it.id == entry.id && it.name.contains("Meta AI") })
        assertEquals("Meta AI", resolveShortcutShortLabel(entry.id, prefs))

        // Execute the custom shortcut through FloatingService -> opens Floating WhatsApp at web.whatsapp.com/send?phone=13135550002
        val service = Robolectric.buildService(FloatingService::class.java)
            .create()
            .startCommand(0, 1)
            .get()
        try {
            val execMethod = FloatingService::class.java.getDeclaredMethod("executeAction", String::class.java).apply {
                isAccessible = true
            }
            execMethod.invoke(service, entry.id)

            val fwmField = FloatingService::class.java.getDeclaredField("floatingWindowManager").apply {
                isAccessible = true
            }
            val fwm = fwmField.get(service) as FloatingWindowManager
            val waWindow = fwm.getWindow("floating_whatsapp")
            assertNotNull("Floating WhatsApp window must open", waWindow)
            assertEquals("Meta AI", waWindow!!.title)
            val webView = waWindow.contentHost.findViewWithTag<WebView>("fw_social_webview_whatsapp")
            assertNotNull(webView)
            assertEquals(
                "https://web.whatsapp.com/send?phone=13135550002",
                Shadows.shadowOf(webView!!).lastLoadedUrl
            )
            fwm.closeAllWindows()
        } finally {
            service.stopSelf()
        }
    }

    @Test
    @Config(sdk = [32])
    fun `Upgrade 1b - Android 12 and below exposes READ_EXTERNAL_STORAGE in requestedPermissions`() {
        val pkgInfo = appContext.packageManager.getPackageInfo(
            appContext.packageName,
            PackageManager.GET_PERMISSIONS
        )
        val requested = pkgInfo.requestedPermissions?.toList().orEmpty()
        assertTrue(requested.contains(android.Manifest.permission.READ_EXTERNAL_STORAGE))
    }
}
