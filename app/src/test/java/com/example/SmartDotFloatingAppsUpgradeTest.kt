package com.example

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import android.webkit.CookieManager
import android.webkit.WebView
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.VideoView
import androidx.test.core.app.ApplicationProvider
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
import java.io.File
import java.io.FileOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SmartDotFloatingAppsUpgradeTest {

    private lateinit var appContext: Application

    @Before
    fun setUp() {
        appContext = ApplicationProvider.getApplicationContext()
        ShadowSettings.setCanDrawOverlays(true)
        appContext.getSharedPreferences(SelectionToolbarHelper.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().clear().commit()
        SmartDotAccessibilityService.setInstanceForTest(null)
    }

    @After
    fun tearDown() {
        SmartDotAccessibilityService.setInstanceForTest(null)
        ShadowSettings.setCanDrawOverlays(false)
    }

    // -------------------------------------------------------------------------
    // Upgrade 3: Clean Floating Window UI + Left Hamburger (☰) Dropdown + Opacity Slider
    // -------------------------------------------------------------------------

    @Test
    fun `FloatingWindowManager Left Hamburger Menu toggles cleanly and Opacity Slider updates window alpha`() {
        val wm = appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val fwm = FloatingWindowManager(appContext, wm)

        val browserWin = FloatingMiniApps.openFloatingBrowser(
            context = appContext,
            manager = fwm,
            accentColor = Color.CYAN,
            initialUrl = "https://kotlinlang.org"
        )!!

        val menuBtn = browserWin.rootView.findViewWithTag<TextView>("fw_btn_menu_floating_browser")
        val menuDrawer = browserWin.rootView.findViewWithTag<LinearLayout>("fw_menu_drawer_floating_browser")
        val opacitySlider = browserWin.rootView.findViewWithTag<SeekBar>("fw_opacity_slider_floating_browser")
        val opacityLabel = browserWin.rootView.findViewWithTag<TextView>("fw_opacity_label_floating_browser")
        val minBtn = browserWin.rootView.findViewWithTag<View>("fw_btn_minimize_floating_browser")
        val maxBtn = browserWin.rootView.findViewWithTag<View>("fw_btn_maximize_floating_browser")
        val closeBtn = browserWin.rootView.findViewWithTag<View>("fw_btn_close_floating_browser")
        val resizeGrip = browserWin.rootView.findViewWithTag<View>("fw_resize_grip_floating_browser")

        assertNotNull(menuBtn)
        assertNotNull(menuDrawer)
        assertNotNull(opacitySlider)
        assertNotNull(opacityLabel)
        assertNotNull(minBtn)
        assertNotNull(maxBtn)
        assertNotNull(closeBtn)
        assertNotNull(resizeGrip)
        assertEquals("☰", menuBtn.text.toString())
        assertEquals(View.GONE, menuDrawer.visibility)

        // Toggle Left Hamburger Menu open
        menuBtn.performClick()
        assertEquals(View.VISIBLE, menuDrawer.visibility)

        // Verify all browser menu items are inside Left Hamburger Dropdown (no crowded middle toolbar)
        val requiredMenuTags = listOf(
            "fw_browser_back",
            "fw_browser_forward",
            "fw_browser_reload",
            "fw_browser_new_tab",
            "fw_browser_bookmark_star",
            "fw_browser_bookmarks_list",
            "fw_browser_history_btn",
            "fw_browser_downloads_btn",
            "fw_browser_copy_url",
            "fw_browser_paste_go",
            "fw_browser_mode_toggle"
        )
        requiredMenuTags.forEach { tag ->
            assertNotNull("Expected hamburger menu item '$tag'", menuDrawer.findViewWithTag<View>(tag))
        }

        // Adjust Window Opacity Slider -> updates rootView.alpha (25%..100%)
        opacitySlider.progress = 25 // 25 + 25 = 50%
        assertEquals(0.50f, browserWin.rootView.alpha, 0.02f)
        assertEquals("Opacity: 50%", opacityLabel.text.toString())

        // Tapping History in Hamburger Menu shows History sheet & closes menu
        menuDrawer.findViewWithTag<View>("fw_browser_history_btn").performClick()
        assertEquals(View.GONE, menuDrawer.visibility)
        val historySheet = browserWin.rootView.findViewWithTag<LinearLayout>("fw_browser_history_sheet")
        assertEquals(View.VISIBLE, historySheet.visibility)
        assertNotNull(historySheet.findViewWithTag<View>("fw_browser_history_row_0"))

        // Tapping Downloads records & displays downloaded entries
        FloatingMiniApps.addBrowserDownload(appContext, "sample_doc.pdf", "https://example.com/sample_doc.pdf")
        menuBtn.performClick()
        menuDrawer.findViewWithTag<View>("fw_browser_downloads_btn").performClick()
        val downloadsSheet = browserWin.rootView.findViewWithTag<LinearLayout>("fw_browser_downloads_sheet")
        assertEquals(View.VISIBLE, downloadsSheet.visibility)
        assertNotNull(downloadsSheet.findViewWithTag<View>("fw_browser_download_row_0"))

        // Tapping Copy URL copies active URL to clipboard; Paste & Go loads clipboard URL
        menuBtn.performClick()
        menuDrawer.findViewWithTag<View>("fw_browser_copy_url").performClick()
        val cm = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        assertEquals("https://kotlinlang.org", cm.primaryClip?.getItemAt(0)?.text?.toString())

        cm.setPrimaryClip(ClipData.newPlainText("url", "https://developer.android.com"))
        menuBtn.performClick()
        menuDrawer.findViewWithTag<View>("fw_browser_paste_go").performClick()
        val urlInput = browserWin.rootView.findViewWithTag<EditText>("fw_browser_url_input")
        assertEquals("https://developer.android.com", urlInput.text.toString())

        // Toggle Desktop / Mobile View inside menu
        val modeItem = menuDrawer.findViewWithTag<TextView>("fw_browser_mode_toggle")
        assertTrue(modeItem.text.toString().contains("Desktop"))
        modeItem.performClick()
        assertTrue(menuDrawer.findViewWithTag<TextView>("fw_browser_mode_toggle").text.toString().contains("Mobile"))

        fwm.closeAllWindows()
    }

    // -------------------------------------------------------------------------
    // Upgrade 2: Dedicated Social Web Floating Apps + Active-App Floating Window Converter
    // -------------------------------------------------------------------------

    @Test
    fun `Dedicated Social Web Floating Shortcuts and Catalog entries work with Desktop UA for WhatsApp`() {
        val requiredIds = listOf(
            "floating_media_files",
            "floating_whatsapp",
            "floating_instagram",
            "floating_facebook",
            "floating_telegram"
        )
        requiredIds.forEach { id ->
            assertTrue("ACTION_LIST must contain $id", ACTION_LIST.any { it.id == id })
            assertTrue("GESTURE_ACTIONS must contain $id", SwipeGestureHelper.GESTURE_ACTIONS.any { it.id == id })
        }

        val wm = appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val fwm = FloatingWindowManager(appContext, wm)

        // 1. Floating WhatsApp uses Desktop Chrome User-Agent & loads web.whatsapp.com
        val waWin = FloatingMiniApps.openFloatingSocialApp(appContext, fwm, Color.GREEN, "whatsapp")!!
        val waWeb = waWin.rootView.findViewWithTag<WebView>("fw_social_webview_whatsapp")
        assertNotNull(waWeb)
        assertTrue(waWeb.settings.javaScriptEnabled)
        assertTrue(waWeb.settings.domStorageEnabled)
        assertTrue(CookieManager.getInstance().acceptCookie())
        assertTrue(
            "WhatsApp Web must default to Desktop Chrome User-Agent",
            waWeb.settings.userAgentString.contains("X11; Linux x86_64")
        )
        assertEquals("https://web.whatsapp.com", Shadows.shadowOf(waWeb).lastLoadedUrl)
        assertNotNull(waWin.rootView.findViewWithTag<TextView>("fw_btn_menu_floating_whatsapp"))

        // 2. Instagram, Facebook, Telegram open cleanly inside SmartDot FloatingWindowManager
        val igWin = FloatingMiniApps.openFloatingSocialApp(appContext, fwm, Color.MAGENTA, "instagram")!!
        val igWeb = igWin.rootView.findViewWithTag<WebView>("fw_social_webview_instagram")
        assertEquals("https://www.instagram.com", Shadows.shadowOf(igWeb).lastLoadedUrl)

        val fbWin = FloatingMiniApps.openFloatingSocialApp(appContext, fwm, Color.BLUE, "facebook")!!
        val fbWeb = fbWin.rootView.findViewWithTag<WebView>("fw_social_webview_facebook")
        assertEquals("https://m.facebook.com", Shadows.shadowOf(fbWeb).lastLoadedUrl)

        val tgWin = FloatingMiniApps.openFloatingSocialApp(appContext, fwm, Color.CYAN, "telegram")!!
        val tgWeb = tgWin.rootView.findViewWithTag<WebView>("fw_social_webview_telegram")
        assertEquals("https://web.telegram.org", Shadows.shadowOf(tgWeb).lastLoadedUrl)

        fwm.closeAllWindows()
    }

    @Test
    fun `FreeformWindowLauncher resolves foreground packages and detected Chrome URL into SmartDot floating targets`() {
        assertEquals(
            FreeformWindowLauncher.FloatingAppTarget.SocialWeb("whatsapp"),
            FreeformWindowLauncher.resolveFloatingTargetForPackage("com.whatsapp")
        )
        assertEquals(
            FreeformWindowLauncher.FloatingAppTarget.SocialWeb("instagram"),
            FreeformWindowLauncher.resolveFloatingTargetForPackage("com.instagram.android")
        )
        assertEquals(
            FreeformWindowLauncher.FloatingAppTarget.SocialWeb("facebook"),
            FreeformWindowLauncher.resolveFloatingTargetForPackage("com.facebook.katana")
        )
        assertEquals(
            FreeformWindowLauncher.FloatingAppTarget.SocialWeb("telegram"),
            FreeformWindowLauncher.resolveFloatingTargetForPackage("org.telegram.messenger")
        )
        assertEquals(
            FreeformWindowLauncher.FloatingAppTarget.YouTube,
            FreeformWindowLauncher.resolveFloatingTargetForPackage("com.google.android.youtube")
        )
        assertEquals(
            FreeformWindowLauncher.FloatingAppTarget.Browser("https://en.wikipedia.org/wiki/Kotlin"),
            FreeformWindowLauncher.resolveFloatingTargetForPackage(
                "com.android.chrome",
                detectedBrowserUrl = "https://en.wikipedia.org/wiki/Kotlin"
            )
        )
        assertEquals(
            FreeformWindowLauncher.FloatingAppTarget.MediaFiles,
            FreeformWindowLauncher.resolveFloatingTargetForPackage("com.google.android.apps.photos")
        )

        // Verify FloatingService pops Chrome's detected active URL right into Floating Browser
        val a11yService = Robolectric.buildService(SmartDotAccessibilityService::class.java).create().get()
        SmartDotAccessibilityService.setInstanceForTest(a11yService)

        val urlNode = AccessibilityNodeInfo.obtain().apply {
            packageName = "com.android.chrome"
            viewIdResourceName = "com.android.chrome:id/url_bar"
            text = "developer.android.com/jetpack"
            isVisibleToUser = true
        }
        val chromeRoot = AccessibilityNodeInfo.obtain().apply {
            packageName = "com.android.chrome"
            isVisibleToUser = true
        }
        Shadows.shadowOf(chromeRoot).addChild(urlNode)
        a11yService.activeRootTestHook = chromeRoot

        val service = Robolectric.buildService(FloatingService::class.java)
            .create()
            .startCommand(0, 1)
            .get()
        val executeAction = FloatingService::class.java
            .getDeclaredMethod("executeAction", String::class.java)
            .apply { isAccessible = true }
        executeAction.invoke(service, "floating_whatsapp")
        ShadowLooper.idleMainLooper()
        executeAction.invoke(service, "floating_window")
        ShadowLooper.idleMainLooper()

        val managerField = FloatingService::class.java.getDeclaredField("floatingWindowManager").apply { isAccessible = true }
        val fwm = managerField.get(service) as FloatingWindowManager
        assertTrue(fwm.hasWindow("floating_whatsapp"))
        assertTrue(fwm.hasWindow("floating_browser"))
        val browserRoot = fwm.getWindow("floating_browser")!!.rootView
        val urlInput = browserRoot.findViewWithTag<EditText>("fw_browser_url_input")
        assertTrue(urlInput.text.toString().contains("developer.android.com/jetpack"))
        assertNull(Shadows.shadowOf(appContext).nextStartedActivity)

        fwm.closeAllWindows()
    }

    // -------------------------------------------------------------------------
    // Upgrade 1: All-in-One Resizable Floating Media Player & File Manager
    // -------------------------------------------------------------------------

    @Test
    fun `Floating Media & Files classifies media types, switches folders, and opens Image, Video, Audio, and Document views cleanly`() {
        assertEquals(FloatingMediaFilesHelper.MediaFileType.IMAGE, FloatingMediaFilesHelper.classifyFileType("photo.webp"))
        assertEquals(FloatingMediaFilesHelper.MediaFileType.VIDEO, FloatingMediaFilesHelper.classifyFileType("clip.mp4"))
        assertEquals(FloatingMediaFilesHelper.MediaFileType.AUDIO, FloatingMediaFilesHelper.classifyFileType("voice_note.m4a"))
        assertEquals(FloatingMediaFilesHelper.MediaFileType.DOCUMENT, FloatingMediaFilesHelper.classifyFileType("notes.txt"))

        val wm = appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val fwm = FloatingWindowManager(appContext, wm)
        val mediaWin = FloatingMiniApps.openFloatingMediaFiles(appContext, fwm, Color.CYAN)!!

        val root = mediaWin.rootView
        // Verify folder tabs exist
        assertNotNull(root.findViewWithTag<View>("fw_media_folder_downloads"))
        assertNotNull(root.findViewWithTag<View>("fw_media_folder_pictures"))
        assertNotNull(root.findViewWithTag<View>("fw_media_folder_movies"))
        assertNotNull(root.findViewWithTag<View>("fw_media_folder_music"))
        assertNotNull(root.findViewWithTag<View>("fw_media_folder_documents"))

        // Verify Hamburger menu inside Media & Files has Photo Picker, SAF Open Media, SAF Link Folder & Opacity
        val menuBtn = root.findViewWithTag<TextView>("fw_btn_menu_floating_media_files")
        val menuDrawer = root.findViewWithTag<LinearLayout>("fw_menu_drawer_floating_media_files")
        assertNotNull(menuBtn)
        assertNotNull(menuDrawer)
        menuBtn.performClick()
        assertEquals(View.VISIBLE, menuDrawer.visibility)
        assertNotNull(menuDrawer.findViewWithTag<View>("fw_media_menu_files"))
        assertNotNull(menuDrawer.findViewWithTag<View>("fw_media_menu_pick_photo"))
        assertNotNull(menuDrawer.findViewWithTag<View>("fw_media_menu_open_file"))
        assertNotNull(menuDrawer.findViewWithTag<View>("fw_media_menu_link_folder"))
        assertNotNull(menuDrawer.findViewWithTag<SeekBar>("fw_opacity_slider_floating_media_files"))

        // Close menu and verify Image Viewer, Video Player, Audio Player, and Doc Viewer views exist and switch cleanly
        menuBtn.performClick()
        assertNotNull(root.findViewWithTag<View>("fw_media_zoom_image"))
        assertNotNull(root.findViewWithTag<VideoView>("fw_media_video_view"))
        assertNotNull(root.findViewWithTag<View>("fw_video_play_pause"))
        assertNotNull(root.findViewWithTag<View>("fw_video_mute"))
        assertNotNull(root.findViewWithTag<View>("fw_audio_prev"))
        assertNotNull(root.findViewWithTag<View>("fw_audio_play_pause"))
        assertNotNull(root.findViewWithTag<View>("fw_audio_next"))
        assertNotNull(root.findViewWithTag<View>("fw_audio_seekbar"))

        // Write a real temporary image file and verify Photo Picker callback loads it in Image Viewer
        val tmpImg = File(appContext.cacheDir, "test_sample.png")
        FileOutputStream(tmpImg).use { fos ->
            Bitmap.createBitmap(24, 24, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.PNG, 100, fos)
        }
        root.findViewWithTag<View>("fw_media_menu_pick_photo").performClick()
        ViewerPickerActivity.pendingCallback?.invoke(Uri.fromFile(tmpImg), true)
        assertEquals(View.VISIBLE, root.findViewWithTag<View>("fw_media_image_viewer").visibility)
        assertEquals(View.GONE, root.findViewWithTag<View>("fw_media_browser_view").visibility)

        // Tap Back -> returns to File Browser
        root.findViewWithTag<View>("fw_media_image_back").performClick()
        assertEquals(View.VISIBLE, root.findViewWithTag<View>("fw_media_browser_view").visibility)
        assertEquals(View.GONE, root.findViewWithTag<View>("fw_media_image_viewer").visibility)

        // Open a temporary text document via SAF callback -> opens Document Viewer
        val tmpDoc = File(appContext.cacheDir, "hello.txt")
        tmpDoc.writeText("SmartDot Floating File Manager Content")
        root.findViewWithTag<View>("fw_media_menu_open_file").performClick()
        ViewerPickerActivity.pendingCallback?.invoke(Uri.fromFile(tmpDoc), false)
        assertEquals(View.VISIBLE, root.findViewWithTag<View>("fw_media_doc_viewer").visibility)
        assertEquals(
            "SmartDot Floating File Manager Content",
            root.findViewWithTag<TextView>("fw_media_doc_text").text.toString()
        )
        root.findViewWithTag<View>("fw_media_doc_back").performClick()
        assertEquals(View.VISIBLE, root.findViewWithTag<View>("fw_media_browser_view").visibility)

        // Open video -> switches to Video Player & controls toggle Mute state cleanly
        val tmpVideo = File(appContext.cacheDir, "clip.mp4").apply { writeBytes(byteArrayOf(0, 1, 2)) }
        root.findViewWithTag<View>("fw_media_menu_open_file").performClick()
        ViewerPickerActivity.pendingCallback?.invoke(Uri.fromFile(tmpVideo), false)
        assertEquals(View.VISIBLE, root.findViewWithTag<View>("fw_media_video_player").visibility)
        val muteBtn = root.findViewWithTag<TextView>("fw_video_mute")
        assertTrue(muteBtn.text.toString().contains("Sound"))
        muteBtn.performClick()
        assertTrue(muteBtn.text.toString().contains("Muted"))

        // Minimize -> restores cleanly without destroying player window
        val minBtn = root.findViewWithTag<View>("fw_btn_minimize_floating_media_files")
        minBtn.performClick()
        assertTrue(mediaWin.isMinimized)
        assertNotNull(mediaWin.bubbleView)
        val bubble = mediaWin.bubbleView!!
        val downTime = android.os.SystemClock.uptimeMillis()
        bubble.dispatchTouchEvent(android.view.MotionEvent.obtain(downTime, downTime, android.view.MotionEvent.ACTION_DOWN, 10f, 10f, 0))
        bubble.dispatchTouchEvent(android.view.MotionEvent.obtain(downTime, downTime + 20L, android.view.MotionEvent.ACTION_UP, 10f, 10f, 0))
        assertFalse(mediaWin.isMinimized)

        fwm.closeAllWindows()
        tmpImg.delete()
        tmpDoc.delete()
        tmpVideo.delete()
    }
}
