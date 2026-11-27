package com.example

import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.Color
import android.graphics.Rect
import android.graphics.RectF
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.PermissionRequest
import android.webkit.WebView
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ApplicationProvider
import com.example.database.NoteEntry
import com.example.database.SmartDotDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import org.robolectric.shadows.ShadowLooper
import java.io.File
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1080dp-h2400dp-mdpi")
class SmartDotFourteenUpgradesTest {

    private lateinit var appContext: Application

    @Before
    fun setUp() {
        appContext = ApplicationProvider.getApplicationContext()
        org.robolectric.shadows.ShadowSettings.setCanDrawOverlays(true)
        SmartDotAccessibilityService.isShieldActive = false
        clearFileProviderCache()
        appContext.getSharedPreferences(SelectionToolbarHelper.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().clear().commit()
        FloatingGalleryMusicHelper.cachedAudioTracks = null
        FloatingGalleryMusicHelper.cachedGalleryMedia = null
        FloatingGalleryMusicHelper.cachedAllFiles = null
        FloatingGalleryMusicHelper.stopBackgroundPlayback()
        FloatingMiniApps.clearCalcHistory(appContext)
    }

    @After
    fun tearDown() {
        FloatingGalleryMusicHelper.stopBackgroundPlayback()
        FloatingGalleryMusicHelper.onBackgroundPlaybackChanged = null
        ViewerPickerActivity.pendingVoiceCallback = null
        ViewerPickerActivity.pendingWebFileCallback = null
        ViewerPickerActivity.pendingCallback = null
        ViewerPickerActivity.pendingFolderCallback = null
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

    private fun registerMediaStoreProvider(
        tables: Map<Uri, Pair<Array<String>, List<Array<Any?>>>>
    ) {
        val provider = object : ContentProvider() {
            override fun onCreate(): Boolean = true
            override fun query(
                uri: Uri,
                projection: Array<out String>?,
                selection: String?,
                selectionArgs: Array<out String>?,
                sortOrder: String?
            ): Cursor {
                val matched = tables[uri] ?: return MatrixCursor(projection ?: arrayOf("_id"))
                val (cols, rows) = matched
                return MatrixCursor(cols, rows.size).apply {
                    rows.forEach { addRow(it) }
                }
            }
            override fun getType(uri: Uri): String? = null
            override fun insert(uri: Uri, values: ContentValues?): Uri? = null
            override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
            override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
        }
        provider.attachInfo(appContext, ProviderInfo().apply { authority = MediaStore.AUTHORITY })
        ShadowContentResolver.registerProviderInternal(MediaStore.AUTHORITY, provider)
    }

    @Test
    fun `Fix 0 - Translation card renders strictly above Selection Toolbar without overlapping shortcuts`() {
        val wm = appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val controller = InlineTextOverlayController(appContext, wm)
        val ocrWords = listOf(
            SmartDotAccessibilityService.ScreenTextNode(0, "Bonjour", Rect(100, 900, 320, 960))
        )
        try {
            controller.showCopyOverlay(
                nodes = ocrWords,
                accentColor = Color.CYAN,
                onCopyClicked = {},
                onRescanRequested = {},
                onTranslateWordRequested = { _, callback ->
                    callback("Hello")
                },
                onDismiss = {}
            )

            val rootField = InlineTextOverlayController::class.java.getDeclaredField("overlayView")
                .apply { isAccessible = true }
            val root = rootField.get(controller) as View
            root.measure(
                View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(2400, View.MeasureSpec.EXACTLY)
            )
            root.layout(0, 0, 1080, 2400)

            val selView = root.findViewById<InlineTextSelectionView>(R.id.inline_ocr_selection_view)
            selView.layout(0, 0, 1080, 2400)
            selView.dispatchTouchEvent(MotionEvent.obtain(10L, 10L, MotionEvent.ACTION_DOWN, 210f, 930f, 0))
            selView.dispatchTouchEvent(MotionEvent.obtain(10L, 25L, MotionEvent.ACTION_UP, 210f, 930f, 0))

            val wordBar = root.findViewById<LinearLayout>(R.id.inline_word_action_bar)
            assertEquals(View.VISIBLE, wordBar.visibility)

            // Trigger Translate shortcut in word bar
            val btnTranslate = wordBar.findViewWithTag<View>("toolbar_action_translate")
            btnTranslate.performClick()
            ShadowLooper.idleMainLooper(100L, TimeUnit.MILLISECONDS)

            val translateBubble = root.findViewById<LinearLayout>(R.id.inline_word_translate_bubble)
            assertEquals(View.VISIBLE, translateBubble.visibility)

            val bubbleLp = translateBubble.layoutParams as FrameLayout.LayoutParams
            val barLp = wordBar.layoutParams as FrameLayout.LayoutParams
            val bubbleBottom = bubbleLp.topMargin + translateBubble.measuredHeight.coerceAtLeast(1)
            assertTrue(
                "Translation card bottom ($bubbleBottom) must sit strictly ABOVE word toolbar top (${barLp.topMargin})",
                bubbleBottom <= barLp.topMargin
            )
        } finally {
            controller.dismiss()
        }
    }

    @Test
    fun `Fix 1 - Music pre-caches, hides Call Recordings in menu, hides bottom player initially, and keeps mini-pill alive`() {
        registerMediaStoreProvider(
            mapOf(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI to (
                    arrayOf(
                        MediaStore.Audio.Media._ID,
                        MediaStore.Audio.Media.DISPLAY_NAME,
                        MediaStore.Audio.Media.DURATION,
                        MediaStore.Audio.Media.SIZE,
                        MediaStore.Audio.Media.DATE_ADDED,
                        MediaStore.Audio.Media.RELATIVE_PATH
                    ) to listOf(
                        arrayOf(1L, "Favorite_Song.mp3", 210_000L, 4_500_000L, 2000L, "Music/"),
                        arrayOf(2L, "Call_Mom_2026.m4a", 95_000L, 900_000L, 1900L, "Recordings/Call/"),
                        arrayOf(3L, "+15551234567_1200.amr", 80_000L, 700_000L, 1800L, "MIUI/sound_recorder/call_rec/")
                    )
                )
            )
        )

        val tracks = FloatingGalleryMusicHelper.queryAudioLibrary(appContext)
        assertEquals(3, tracks.size)
        assertFalse(FloatingGalleryMusicHelper.isCallRecording(tracks[0]))
        assertTrue(FloatingGalleryMusicHelper.isCallRecording(tracks[1]))
        assertTrue(FloatingGalleryMusicHelper.isCallRecording(tracks[2]))

        val wm = appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val fwm = FloatingWindowManager(appContext, wm)
        try {
            val musicWin = FloatingGalleryMusicHelper.openFloatingMusicPlayer(appContext, fwm, Color.CYAN)!!
            ShadowLooper.idleMainLooper(200L, TimeUnit.MILLISECONDS)

            val playerCard = musicWin.rootView.findViewWithTag<View>("fw_music_player_card")
            assertEquals("Bottom player card must be GONE initially", View.GONE, playerCard.visibility)

            val rvMusic = musicWin.rootView.findViewWithTag<RecyclerView>("fw_music_list")
            assertEquals("Only non-call-recording track shown by default", 1, rvMusic.adapter!!.itemCount)

            val menuDrawer = musicWin.rootView.findViewWithTag<LinearLayout>("fw_menu_drawer_floating_music")
            val hideCallSwitch = menuDrawer.findViewWithTag<Switch>("fw_music_hide_call_rec_toggle")
            assertNotNull("Hide Call Recordings toggle must live inside menu drawer", hideCallSwitch)
            val searchBox = menuDrawer.findViewWithTag<EditText>("fw_music_search_input")
            assertNotNull("Music search input must live inside menu drawer", searchBox)

            hideCallSwitch.isChecked = false
            assertEquals(3, rvMusic.adapter!!.itemCount)

            searchBox.setText("Favorite")
            assertEquals(1, rvMusic.adapter!!.itemCount)

            // Verify FloatingService mini-pill shows when music is playing in background and window is closed
            val serviceController = Robolectric.buildService(FloatingService::class.java).create()
            val service = serviceController.get()
            try {
                val updateMethod = FloatingService::class.java.getDeclaredMethod(
                    "updateMusicMiniPillAndNotification",
                    Boolean::class.javaPrimitiveType,
                    String::class.java,
                    Boolean::class.javaPrimitiveType
                ).apply { isAccessible = true }
                updateMethod.invoke(service, true, "Favorite_Song.mp3", false)
                ShadowLooper.idleMainLooper(50L, TimeUnit.MILLISECONDS)

                val pillField = FloatingService::class.java.getDeclaredField("musicMiniPillView")
                    .apply { isAccessible = true }
                assertNotNull("Mini-pill must show when music plays after window closed", pillField.get(service))

                updateMethod.invoke(service, false, null, false)
                ShadowLooper.idleMainLooper(50L, TimeUnit.MILLISECONDS)
                assertEquals(null, pillField.get(service))
            } finally {
                serviceController.destroy()
                ShadowLooper.idleMainLooper()
            }
        } finally {
            fwm.closeAllWindows()
            ShadowLooper.idleMainLooper()
        }
    }

    @Test
    fun `Fix 2 - Floating Notes shows 1-line title rows, expands Reader on tap, and allows native EditText focus`() {
        val dao = SmartDotDatabase.getDatabase(appContext).smartDotDao()
        runBlocking {
            dao.insertNote(NoteEntry(title = "Grocery List", content = "Milk\nEggs\nBread\nApples"))
        }

        val wm = appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val fwm = FloatingWindowManager(appContext, wm)
        val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
        try {
            val win = FloatingMiniApps.openFloatingNotes(appContext, fwm, scope, Color.CYAN)!!
            val listContainer = win.rootView.findViewWithTag<LinearLayout>("fw_notes_list")
            repeat(15) {
                ShadowLooper.idleMainLooper()
                if (listContainer.childCount > 0 && listContainer.getChildAt(0) is LinearLayout) return@repeat
                Thread.sleep(60)
            }
            ShadowLooper.idleMainLooper()

            assertEquals(ViewGroup.FOCUS_AFTER_DESCENDANTS, win.rootView.descendantFocusability)
            val etNote = win.rootView.findViewWithTag<EditText>("fw_notes_input")
            assertTrue(etNote.isFocusable)
            assertTrue(etNote.isLongClickable)

            assertTrue("Expected at least 1 note row in fw_notes_list", listContainer.childCount >= 1)
            val firstRow = listContainer.getChildAt(0) as LinearLayout
            val titleTv = firstRow.getChildAt(0) as TextView
            assertEquals("Row title must be clamped to 1 line", 1, titleTv.maxLines)
            assertEquals("Grocery List", titleTv.text.toString())

            val readerView = win.rootView.findViewWithTag<View>("fw_notes_reader_view")
            assertEquals(View.GONE, readerView.visibility)

            firstRow.performClick()
            assertEquals(View.VISIBLE, readerView.visibility)
            val readerContent = win.rootView.findViewWithTag<EditText>("fw_notes_reader_content")
            assertTrue(readerContent.text.toString().contains("Eggs"))

            win.rootView.findViewWithTag<View>("fw_notes_reader_back").performClick()
            assertEquals(View.GONE, readerView.visibility)
            assertEquals(View.VISIBLE, win.rootView.findViewWithTag<View>("fw_notes_main_view").visibility)
        } finally {
            fwm.closeAllWindows()
            scope.cancel()
        }
    }

    @Test
    fun `Fix 3a - Floating Calculator saves calculation history and reuses or clears entries`() {
        val wm = appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val fwm = FloatingWindowManager(appContext, wm)
        try {
            val calcWin = FloatingMiniApps.openFloatingCalculator(appContext, fwm, Color.CYAN)!!
            calcWin.rootView.findViewWithTag<View>("fw_calc_btn_5").performClick()
            calcWin.rootView.findViewWithTag<View>("fw_calc_btn_6").performClick()
            calcWin.rootView.findViewWithTag<View>("fw_calc_btn_×").performClick()
            calcWin.rootView.findViewWithTag<View>("fw_calc_btn_6").performClick()
            calcWin.rootView.findViewWithTag<View>("fw_calc_btn_=").performClick()

            val history = FloatingMiniApps.getCalcHistory(appContext)
            assertEquals(1, history.size)
            assertTrue(history[0].contains("336"))

            val historyPanel = calcWin.rootView.findViewWithTag<View>("fw_calc_history_panel")
            assertEquals(View.GONE, historyPanel.visibility)

            calcWin.rootView.findViewWithTag<View>("fw_calc_history_btn").performClick()
            assertEquals(View.VISIBLE, historyPanel.visibility)

            calcWin.rootView.findViewWithTag<View>("fw_calc_btn_C").performClick()
            assertEquals("0", calcWin.rootView.findViewWithTag<TextView>("fw_calc_expr").text.toString())

            calcWin.rootView.findViewWithTag<View>("fw_calc_history_row_0").performClick()
            assertEquals("336", calcWin.rootView.findViewWithTag<TextView>("fw_calc_expr").text.toString())

            calcWin.rootView.findViewWithTag<View>("fw_calc_history_btn").performClick()
            calcWin.rootView.findViewWithTag<View>("fw_calc_history_clear").performClick()
            assertTrue(FloatingMiniApps.getCalcHistory(appContext).isEmpty())
        } finally {
            fwm.closeAllWindows()
        }
    }

    @Test
    fun `Fix 4 & 6 - Ad-Blocker, Proxy toggle, Bottom-Pill Fullscreen drag, and YouTube Voice Search`() {
        assertTrue(FloatingMiniApps.isAdDomainUrl("https://googleads.g.doubleclick.net/pagead/viewthroughconversion"))
        assertTrue(FloatingMiniApps.isAdDomainUrl("https://www.youtube.com/api/stats/ads?ad=1"))
        assertFalse(FloatingMiniApps.isAdDomainUrl("https://m.youtube.com/watch?v=dQw4w9WgXcQ"))
        assertTrue(
            FloatingMiniApps.wrapUrlWithWebProxy("https://example.com/news")
                .startsWith("https://translate.google.com/translate")
        )

        val wm = appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val fwm = FloatingWindowManager(appContext, wm)
        try {
            val browserWin = FloatingMiniApps.openFloatingBrowser(appContext, fwm, Color.CYAN)!!
            val menu = browserWin.rootView.findViewWithTag<LinearLayout>("fw_menu_drawer_floating_browser")
            val adToggle = menu.findViewWithTag<TextView>("fw_browser_adblock_toggle")
            val proxyToggle = menu.findViewWithTag<TextView>("fw_browser_proxy_toggle")
            assertNotNull(adToggle)
            assertNotNull(proxyToggle)
            assertTrue(adToggle.text.toString().contains("ON"))
            assertTrue(proxyToggle.text.toString().contains("OFF"))

            // Bottom pill downward drag (> 80px) toggles 100% fullscreen and restores on second downward drag
            val pill = browserWin.rootView.findViewWithTag<View>("fw_bottom_pill_floating_browser")
            val dm = appContext.resources.displayMetrics
            pill.dispatchTouchEvent(MotionEvent.obtain(10L, 10L, MotionEvent.ACTION_DOWN, 100f, 100f, 0))
            pill.dispatchTouchEvent(MotionEvent.obtain(10L, 40L, MotionEvent.ACTION_MOVE, 100f, 220f, 0))
            pill.dispatchTouchEvent(MotionEvent.obtain(10L, 60L, MotionEvent.ACTION_UP, 100f, 220f, 0))
            assertTrue(browserWin.isMaximized)
            assertEquals(0, browserWin.layoutParams.x)
            assertEquals(0, browserWin.layoutParams.y)
            assertEquals(dm.widthPixels, browserWin.layoutParams.width)
            assertEquals(dm.heightPixels, browserWin.layoutParams.height)

            pill.dispatchTouchEvent(MotionEvent.obtain(100L, 100L, MotionEvent.ACTION_DOWN, 100f, 100f, 0))
            pill.dispatchTouchEvent(MotionEvent.obtain(100L, 140L, MotionEvent.ACTION_MOVE, 100f, 220f, 0))
            pill.dispatchTouchEvent(MotionEvent.obtain(100L, 160L, MotionEvent.ACTION_UP, 100f, 220f, 0))
            assertFalse(browserWin.isMaximized)

            // Floating YouTube ad-block & voice search menu items + audio permission grant
            val ytWin = FloatingMiniApps.openFloatingYouTube(appContext, fwm, Color.RED)!!
            val ytMenu = ytWin.rootView.findViewWithTag<LinearLayout>("fw_menu_drawer_floating_youtube")
            assertNotNull(ytMenu.findViewWithTag<View>("fw_youtube_adblock_toggle"))
            val voiceBtn = ytMenu.findViewWithTag<View>("fw_youtube_voice_song_search")
            assertNotNull(voiceBtn)

            val ytWebView = ytWin.contentHost.findViewWithTag<WebView>("fw_youtube_webview")
            var grantedRes: Array<String>? = null
            ytWebView.webChromeClient?.onPermissionRequest(object : PermissionRequest() {
                override fun getOrigin(): Uri = Uri.parse("https://m.youtube.com")
                override fun getResources(): Array<String> = arrayOf(RESOURCE_AUDIO_CAPTURE)
                override fun grant(resources: Array<String>) { grantedRes = resources }
                override fun deny() {}
            })
            assertNotNull(grantedRes)
            assertEquals(PermissionRequest.RESOURCE_AUDIO_CAPTURE, grantedRes!!.first())

            voiceBtn.performClick()
            val launched = Shadows.shadowOf(appContext).nextStartedActivity
            assertNotNull(launched)
            assertEquals(ViewerPickerActivity.MODE_VOICE_SEARCH, launched!!.getStringExtra(ViewerPickerActivity.EXTRA_PICK_MODE))
            ViewerPickerActivity.pendingVoiceCallback?.invoke("lofi beats")
            ShadowLooper.idleMainLooper(50L, TimeUnit.MILLISECONDS)
            assertTrue(
                Shadows.shadowOf(ytWebView).lastLoadedUrl.orEmpty()
                    .contains("https://m.youtube.com/results?search_query=lofi")
            )
        } finally {
            fwm.closeAllWindows()
        }
    }

    @Test
    fun `Fix 5 - All-Files 2-tab layout newest-first, menu search, and PinchZoomImageView Left-Right swipe`() {
        registerMediaStoreProvider(
            mapOf(
                MediaStore.Files.getContentUri("external") to (
                    arrayOf(
                        MediaStore.Files.FileColumns._ID,
                        MediaStore.Files.FileColumns.DISPLAY_NAME,
                        MediaStore.Files.FileColumns.MIME_TYPE,
                        MediaStore.Files.FileColumns.SIZE,
                        MediaStore.Files.FileColumns.DATE_ADDED,
                        MediaStore.Files.FileColumns.RELATIVE_PATH
                    ) to listOf(
                        arrayOf(10L, "Resume.pdf", "application/pdf", 120_000L, 5000L, "Documents/"),
                        arrayOf(11L, "AppRelease.apk", "application/vnd.android.package-archive", 9_000_000L, 4500L, "Download/")
                    )
                )
            )
        )

        val allFiles = FloatingGalleryMusicHelper.queryAllFiles(appContext)
        assertTrue(allFiles.any { it.displayName == "Resume.pdf" && it.fileType == FloatingGalleryMusicHelper.GalleryFileType.DOCUMENT })
        assertTrue(allFiles.any { it.displayName == "AppRelease.apk" && it.fileType == FloatingGalleryMusicHelper.GalleryFileType.APK })
        assertEquals("Resume.pdf", allFiles.first().displayName)

        val wm = appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val fwm = FloatingWindowManager(appContext, wm)
        try {
            val galleryWin = FloatingGalleryMusicHelper.openFloatingPhotosVideosGallery(appContext, fwm, Color.CYAN)!!
            ShadowLooper.idleMainLooper(200L, TimeUnit.MILLISECONDS)

            assertNotNull(galleryWin.rootView.findViewWithTag<View>("fw_gallery_tab_all_files"))
            assertNotNull(galleryWin.rootView.findViewWithTag<View>("fw_gallery_tab_folders"))
            val menu = galleryWin.rootView.findViewWithTag<LinearLayout>("fw_menu_drawer_floating_photos_videos")
            assertNotNull(menu.findViewWithTag<EditText>("fw_gallery_menu_search"))

            galleryWin.rootView.findViewWithTag<View>("fw_gallery_tab_folders").performClick()
            val foldersList = galleryWin.rootView.findViewWithTag<RecyclerView>("fw_gallery_folders_list")
            repeat(15) {
                ShadowLooper.idleMainLooper()
                if (foldersList.adapter!!.itemCount >= 2) return@repeat
                Thread.sleep(25)
            }
            assertEquals(View.VISIBLE, foldersList.visibility)
            assertEquals(2, foldersList.adapter!!.itemCount)

            // Verify PinchZoomImageView Left/Right horizontal swipe callback
            val zoomView = galleryWin.rootView.findViewWithTag<FloatingMediaFilesHelper.PinchZoomImageView>("fw_gallery_zoom_image")
            var swipeDir: Boolean? = null
            zoomView.onHorizontalSwipeListener = { isNext -> swipeDir = isNext }
            zoomView.dispatchTouchEvent(MotionEvent.obtain(10L, 10L, MotionEvent.ACTION_DOWN, 500f, 300f, 0))
            zoomView.dispatchTouchEvent(MotionEvent.obtain(10L, 30L, MotionEvent.ACTION_MOVE, 250f, 305f, 0))
            zoomView.dispatchTouchEvent(MotionEvent.obtain(10L, 45L, MotionEvent.ACTION_UP, 100f, 305f, 0))
            assertEquals(true, swipeDir)
        } finally {
            fwm.closeAllWindows()
        }
    }

    @Test
    fun `Fix 7 - Multi-word highlighter drag interpolation never skips intermediate words`() {
        val selView = InlineTextSelectionView(appContext)
        selView.measure(
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(2400, View.MeasureSpec.EXACTLY)
        )
        selView.layout(0, 0, 1080, 2400)

        val nodes = listOf(
            SmartDotAccessibilityService.ScreenTextNode(0, "One", Rect(100, 500, 200, 550)),
            SmartDotAccessibilityService.ScreenTextNode(1, "Two", Rect(220, 500, 320, 550)),
            SmartDotAccessibilityService.ScreenTextNode(2, "Three", Rect(340, 500, 460, 550)),
            SmartDotAccessibilityService.ScreenTextNode(3, "Four", Rect(480, 500, 580, 550))
        )
        selView.configureCopyMode(
            nodes = nodes,
            accentColor = Color.CYAN,
            onCopySelectionChanged = { _, _ -> },
            onScrollDismissRequested = {}
        )

        // Fast sweep jumping directly from "One" to "Four" in a single ACTION_MOVE
        selView.dispatchTouchEvent(MotionEvent.obtain(10L, 10L, MotionEvent.ACTION_DOWN, 150f, 525f, 0))
        selView.dispatchTouchEvent(MotionEvent.obtain(10L, 20L, MotionEvent.ACTION_MOVE, 530f, 525f, 0))
        selView.dispatchTouchEvent(MotionEvent.obtain(10L, 30L, MotionEvent.ACTION_UP, 530f, 525f, 0))

        val selected = selView.getSelectedWordNodes().map { it.text }
        assertEquals(listOf("One", "Two", "Three", "Four"), selected)
    }

    @Test
    fun `Fix 9 & 10 - WhatsApp viewport zoom settings and ViewerPickerActivity FileProvider upload cache`() {
        val wm = appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val fwm = FloatingWindowManager(appContext, wm)
        try {
            val waWin = FloatingMiniApps.openFloatingSocialApp(appContext, fwm, Color.GREEN, "whatsapp")!!
            val waWeb = waWin.contentHost.findViewWithTag<WebView>("fw_social_webview_whatsapp")
            assertTrue(waWeb.settings.useWideViewPort)
            assertTrue(waWeb.settings.loadWithOverviewMode)
            assertTrue(waWeb.settings.supportZoom())
            assertTrue(waWeb.settings.builtInZoomControls)
            assertFalse(waWeb.settings.displayZoomControls)
        } finally {
            fwm.closeAllWindows()
        }

        val srcFile = File(appContext.filesDir, "picked_sample.png").apply {
            writeBytes(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 1, 2, 3, 4))
        }
        val activity = Robolectric.buildActivity(
            ViewerPickerActivity::class.java,
            Intent(appContext, ViewerPickerActivity::class.java).apply {
                putExtra(ViewerPickerActivity.EXTRA_PICK_MODE, ViewerPickerActivity.MODE_WEB_FILE_CHOOSER)
            }
        ).create().get()

        val resolved = activity.resolveWebUploadUris(listOf(Uri.fromFile(srcFile)))
        assertEquals(1, resolved.size)
        val outUri = resolved.first()
        assertEquals("content", outUri.scheme)
        assertEquals("${appContext.packageName}.fileprovider", outUri.authority)
        srcFile.delete()
    }
}
