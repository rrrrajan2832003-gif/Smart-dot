package com.example

import android.app.Application
import android.app.SearchManager
import android.content.ComponentName
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ProviderInfo
import android.content.pm.ResolveInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.graphics.RectF
import android.hardware.Sensor
import android.hardware.SensorManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.Switch
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
import org.robolectric.shadows.ShadowContentResolver
import org.robolectric.shadows.ShadowLooper
import org.robolectric.shadows.ShadowSensor
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1080dp-h2400dp-mdpi")
class SmartDotThirteenUpgradesTest {

    private lateinit var appContext: Application

    @Before
    fun setup() {
        appContext = ApplicationProvider.getApplicationContext()
        clearFileProviderCache()
        org.robolectric.shadows.ShadowSettings.setCanDrawOverlays(true)
        SmartDotAccessibilityService.isShieldActive = false
        FloatingGalleryMusicHelper.cachedAudioTracks = null
        FloatingGalleryMusicHelper.cachedGalleryMedia = null
        FloatingGalleryMusicHelper.cachedAllFiles = null
        appContext.getSharedPreferences(SelectionToolbarHelper.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @After
    fun tearDown() {
        SmartDotAccessibilityService.isShieldActive = false
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

    private fun registerResolveActivity(action: String, pkg: String, mimeType: String? = null) {
        val spm = Shadows.shadowOf(appContext.packageManager)
        val intent = Intent(action).apply {
            setPackage(pkg)
            if (mimeType != null) type = mimeType
        }
        val resolveInfo = ResolveInfo().apply {
            activityInfo = ActivityInfo().apply {
                packageName = pkg
                name = "$pkg.StubActivity"
            }
        }
        spm.addResolveInfoForIntent(intent, resolveInfo)
        val launchIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
            setPackage(pkg)
            component = ComponentName(pkg, "$pkg.MainActivity")
        }
        spm.addResolveInfoForIntent(launchIntent, resolveInfo)
    }

    @Test
    fun `Fix 1 - Back key and bottom swipe-up close window, while left-right edge drag docks half-hidden bubble`() {
        val wm = appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val fwm = FloatingWindowManager(appContext, wm)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        try {
            // 1. Back key closes window (never minimizes to bubble)
            val win = FloatingMiniApps.openFloatingNotes(appContext, fwm, scope, Color.CYAN)!!
            assertTrue(fwm.hasWindow("floating_notes"))
            win.rootView.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK))
            win.rootView.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK))
            assertFalse("Back button must close window", fwm.hasWindow("floating_notes"))

            // 2. Bottom pill swipe-up closes window
            val win2 = FloatingMiniApps.openFloatingNotes(appContext, fwm, scope, Color.CYAN)!!
            val pill = win2.rootView.findViewWithTag<View>("fw_bottom_pill_floating_notes")
            assertNotNull(pill)
            val d = MotionEvent.obtain(10L, 10L, MotionEvent.ACTION_DOWN, 120f, 400f, 0)
            val m = MotionEvent.obtain(10L, 25L, MotionEvent.ACTION_MOVE, 120f, 290f, 0) // dy = -110
            val u = MotionEvent.obtain(10L, 40L, MotionEvent.ACTION_UP, 120f, 290f, 0)
            pill.dispatchTouchEvent(d)
            pill.dispatchTouchEvent(m)
            pill.dispatchTouchEvent(u)
            d.recycle(); m.recycle(); u.recycle()
            assertFalse("Bottom swipe-up must close window", fwm.hasWindow("floating_notes"))

            // 3. Drag top title bar to LEFT edge -> half-hidden bubble (-width/2, FLAG_LAYOUT_NO_LIMITS)
            val winLeft = FloatingMiniApps.openFloatingNotes(appContext, fwm, scope, Color.CYAN)!!
            val titleBarLeft = winLeft.rootView.findViewWithTag<View>("fw_title_bar_floating_notes")
            assertNotNull(titleBarLeft)
            winLeft.layoutParams.x = 60
            val ld = MotionEvent.obtain(100L, 100L, MotionEvent.ACTION_DOWN, 200f, 40f, 0)
            val lm = MotionEvent.obtain(100L, 120L, MotionEvent.ACTION_MOVE, 142f, 40f, 0) // x = 2 <= 10
            val lu = MotionEvent.obtain(100L, 140L, MotionEvent.ACTION_UP, 142f, 40f, 0)
            titleBarLeft.dispatchTouchEvent(ld)
            titleBarLeft.dispatchTouchEvent(lm)
            titleBarLeft.dispatchTouchEvent(lu)
            ld.recycle(); lm.recycle(); lu.recycle()

            assertTrue("Left edge drag must minimize to edge bubble", winLeft.isMinimized)
            assertEquals(FloatingWindowManager.EdgeDockSide.LEFT, winLeft.edgeDockSide)
            val leftBubbleParams = winLeft.bubbleParams!!
            assertEquals(-leftBubbleParams.width / 2, leftBubbleParams.x)
            assertTrue((leftBubbleParams.flags and WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS) != 0)

            // Restore from left bubble clamps window inside screen bounds
            fwm.restoreFromBubble(winLeft)
            assertFalse(winLeft.isMinimized)
            assertEquals(FloatingWindowManager.EdgeDockSide.NONE, winLeft.edgeDockSide)
            assertTrue(winLeft.layoutParams.x >= 12)

            // 4. Drag top title bar to RIGHT edge -> half-hidden bubble (screenW - width/2)
            val screenW = appContext.resources.displayMetrics.widthPixels
            winLeft.layoutParams.x = screenW - winLeft.layoutParams.width - 40
            val rd = MotionEvent.obtain(300L, 300L, MotionEvent.ACTION_DOWN, 100f, 40f, 0)
            val rm = MotionEvent.obtain(300L, 320L, MotionEvent.ACTION_MOVE, 150f, 40f, 0) // x + w >= screenW
            val ru = MotionEvent.obtain(300L, 340L, MotionEvent.ACTION_UP, 150f, 40f, 0)
            titleBarLeft.dispatchTouchEvent(rd)
            titleBarLeft.dispatchTouchEvent(rm)
            titleBarLeft.dispatchTouchEvent(ru)
            rd.recycle(); rm.recycle(); ru.recycle()

            assertTrue("Right edge drag must minimize to right-edge bubble", winLeft.isMinimized)
            assertEquals(FloatingWindowManager.EdgeDockSide.RIGHT, winLeft.edgeDockSide)
            val rightBubbleParams = winLeft.bubbleParams!!
            assertEquals(screenW - rightBubbleParams.width / 2, rightBubbleParams.x)
        } finally {
            fwm.closeAllWindows()
            scope.cancel()
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
    fun `Fix 2 - Zero-limit device media queries, RecyclerView containers, and Hide under 1 Min Audio toggle`() {
        val audioRows = buildList<Array<Any?>> {
            for (i in 1..170) {
                add(arrayOf(i.toLong(), "Song_$i.mp3", 185_000L, 4_200_000L, (5000 + i).toLong(), "Music/"))
            }
            for (i in 171..220) {
                add(arrayOf(i.toLong(), "Call_Recording_$i.m4a", 24_000L, 280_000L, (4000 + i).toLong(), "Recordings/Call/"))
            }
        }
        val imageRows = List<Array<Any?>>(180) { idx ->
            arrayOf((idx + 1).toLong(), "IMG_${idx + 1}.jpg", (1000 + idx).toLong(), 250_000L, "Pictures/")
        }
        val downloadsRows = List<Array<Any?>>(110) { idx ->
            arrayOf((idx + 1).toLong(), "Doc_${idx + 1}.pdf", "application/pdf", 50_000L, (3000 + idx).toLong(), "Download/")
        }
        val filesRows = List<Array<Any?>>(110) { idx ->
            arrayOf((1000 + idx + 1).toLong(), "Doc_${idx + 1}.pdf", "application/pdf", 50_000L, (3000 + idx).toLong(), "Download/")
        }

        val tables = mutableMapOf<Uri, Pair<Array<String>, List<Array<Any?>>>>(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI to (
                arrayOf(
                    MediaStore.Audio.Media._ID,
                    MediaStore.Audio.Media.DISPLAY_NAME,
                    MediaStore.Audio.Media.DURATION,
                    MediaStore.Audio.Media.SIZE,
                    MediaStore.Audio.Media.DATE_ADDED,
                    MediaStore.Audio.Media.RELATIVE_PATH
                ) to audioRows
            ),
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI to (
                arrayOf(
                    MediaStore.Images.Media._ID,
                    MediaStore.Images.Media.DISPLAY_NAME,
                    MediaStore.Images.Media.DATE_ADDED,
                    MediaStore.Images.Media.SIZE,
                    MediaStore.Images.Media.RELATIVE_PATH
                ) to imageRows
            ),
            MediaStore.Files.getContentUri("external") to (
                arrayOf(
                    MediaStore.Files.FileColumns._ID,
                    MediaStore.Files.FileColumns.DISPLAY_NAME,
                    MediaStore.Files.FileColumns.MIME_TYPE,
                    MediaStore.Files.FileColumns.SIZE,
                    MediaStore.Files.FileColumns.DATE_ADDED,
                    MediaStore.Files.FileColumns.RELATIVE_PATH
                ) to filesRows
            )
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tables[MediaStore.Downloads.EXTERNAL_CONTENT_URI] = (
                arrayOf(
                    MediaStore.MediaColumns._ID,
                    MediaStore.MediaColumns.DISPLAY_NAME,
                    MediaStore.MediaColumns.MIME_TYPE,
                    MediaStore.MediaColumns.SIZE,
                    MediaStore.MediaColumns.DATE_MODIFIED,
                    MediaStore.MediaColumns.RELATIVE_PATH
                ) to downloadsRows
            )
        }
        registerMediaStoreProvider(tables)

        val queriedAudio = FloatingGalleryMusicHelper.queryAudioLibrary(appContext)
        assertEquals("Must load all 220 audio files without 150 cap", 220, queriedAudio.size)

        val queriedPhotos = FloatingGalleryMusicHelper.queryPhotosAndVideos(
            appContext,
            FloatingGalleryMusicHelper.GalleryFilter.PHOTOS
        )
        assertEquals("Must load all 180 photos without 120 cap", 180, queriedPhotos.size)

        val queriedDownloads = FloatingMediaFilesHelper.listFilesForFolder(
            appContext,
            FloatingMediaFilesHelper.MediaFolderCategory.DOWNLOADS
        )
        assertEquals("Must load all 110 downloads without 80 cap", 110, queriedDownloads.size)

        // Open UI windows and verify RecyclerView + Hide Call Recordings live filtering inside ☰ Menu
        val wm = appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val fwm = FloatingWindowManager(appContext, wm)
        try {
            val musicWin = FloatingGalleryMusicHelper.openFloatingMusicPlayer(appContext, fwm, Color.CYAN)!!
            ShadowLooper.idleMainLooper(200L, TimeUnit.MILLISECONDS)

            val rvMusic = musicWin.rootView.findViewWithTag<View>("fw_music_list")
            assertTrue("Music list must use RecyclerView", rvMusic is RecyclerView)
            val hideToggle = musicWin.rootView.findViewWithTag<Switch>("fw_music_hide_call_rec_toggle")
            assertNotNull(hideToggle)
            assertTrue("Hide Call Recordings defaults ON", hideToggle.isChecked)

            val adapter = (rvMusic as RecyclerView).adapter!!
            assertEquals("With Hide Call Recordings ON, 170 music tracks shown", 170, adapter.itemCount)

            hideToggle.isChecked = false
            assertEquals("With Hide Call Recordings OFF, all 220 tracks shown", 220, adapter.itemCount)

            val galleryWin = FloatingGalleryMusicHelper.openFloatingPhotosVideosGallery(appContext, fwm, Color.CYAN)!!
            val rvGallery = galleryWin.rootView.findViewWithTag<View>("fw_gallery_grid") as RecyclerView
            repeat(20) {
                ShadowLooper.idleMainLooper()
                if (rvGallery.adapter!!.itemCount >= 510) return@repeat
                Thread.sleep(25)
            }
            assertEquals(510, rvGallery.adapter!!.itemCount)
            galleryWin.rootView.findViewWithTag<View>("fw_gallery_filter_photos").performClick()
            assertEquals(180, rvGallery.adapter!!.itemCount)

            val mediaWin = FloatingMediaFilesHelper.openFloatingMediaFiles(appContext, fwm, Color.CYAN)!!
            val rvMedia = mediaWin.rootView.findViewWithTag<View>("fw_media_file_list") as RecyclerView
            repeat(20) {
                ShadowLooper.idleMainLooper()
                if (rvMedia.adapter!!.itemCount >= 110) return@repeat
                Thread.sleep(25)
            }
            assertEquals(110, rvMedia.adapter!!.itemCount)
        } finally {
            fwm.closeAllWindows()
        }
    }

    @Test
    fun `Fix 3 & 4 - Double-tap in Copy mode selects full-screen image cutout and keyboard auto-lifts bars`() {
        val wm = appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val controller = InlineTextOverlayController(appContext, wm)
        val ocrWords = listOf(
            SmartDotAccessibilityService.ScreenTextNode(0, "Header", Rect(40, 120, 220, 170)),
            SmartDotAccessibilityService.ScreenTextNode(1, "Body", Rect(40, 300, 220, 350))
        )
        var capturedBox: RectF? = null

        try {
            controller.showCopyOverlay(
                nodes = ocrWords,
                accentColor = Color.CYAN,
                onCopyClicked = {},
                onRescanRequested = {},
                onCutoutSearchWithFallback = { rect, _, _, _, _ -> capturedBox = rect },
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
            assertTrue(selView.selectFullScreenCutout())

            val cutoutRect = selView.getActiveCutoutRect()
            assertNotNull("Full-screen double-tap must create cutout rect", cutoutRect)
            assertEquals(0f, cutoutRect!!.left, 0.5f)
            assertEquals(0f, cutoutRect.top, 0.5f)
            assertEquals(1080f, cutoutRect.width(), 0.5f)
            assertEquals(2400f, cutoutRect.height(), 0.5f)
            assertTrue(
                "Word selection must remain empty after full-screen image double-tap",
                selView.getSelectedCopyNodes().isEmpty()
            )

            val cutoutBar = root.findViewById<LinearLayout>(R.id.inline_cutout_action_bar)
            assertEquals(View.VISIBLE, cutoutBar.visibility)

            // Fix 4: Keyboard auto-lift and restore on Cutout action bar
            val lp = cutoutBar.layoutParams as android.widget.FrameLayout.LayoutParams
            lp.topMargin = 1750
            cutoutBar.layoutParams = lp
            controller.adjustActiveBarsForKeyboard(keyboardHeightPx = 800, rootHeightPx = 2400)
            val liftedMargin = (cutoutBar.layoutParams as android.widget.FrameLayout.LayoutParams).topMargin
            assertTrue(
                "Cutout bar topMargin ($liftedMargin) must lift above keyboard top (1600)",
                liftedMargin < 1600
            )

            controller.adjustActiveBarsForKeyboard(keyboardHeightPx = 0, rootHeightPx = 2400)
            val restoredMargin = (cutoutBar.layoutParams as android.widget.FrameLayout.LayoutParams).topMargin
            assertEquals(1750, restoredMargin)

            // Trigger search confirms capturedBox is wired
            val searchBtn = cutoutBar.findViewWithTag<View>("toolbar_action_search")
            searchBtn.performClick()
            assertNotNull(capturedBox)
        } finally {
            controller.dismiss()
        }
    }

    @Test
    fun `Fix 5 - Saved Direct WhatsApp Meta AI shortcut appears in all lists and gesture pickers`() {
        val prefs = appContext.getSharedPreferences(SelectionToolbarHelper.PREFS_NAME, Context.MODE_PRIVATE)
        val saved = saveCustomWhatsAppShortcut(prefs, "Meta AI", "13135550002")
        assertNotNull(saved)

        val allActions = getAllAvailableActions(prefs)
        assertTrue(
            "Saved Meta AI shortcut must appear in getAllAvailableActions",
            allActions.any { it.id == saved!!.id && it.name.contains("Meta AI") }
        )

        val gestureOptions = SwipeGestureHelper.getAllGestureOptions(prefs)
        assertTrue(
            "Saved Meta AI shortcut must appear in 22-gesture picker options",
            gestureOptions.any { it.id == saved!!.id && it.name.contains("Meta AI") }
        )
    }

    @Test
    fun `Fix 6 - ShakeDetector requires 3 firm shakes and suppresses accidental unlock jolt`() {
        var triggers = 0
        val detector = ShakeDetector(thresholdAccel = 16.0f, requiredShakes = 3) {
            triggers++
        }

        // Single or double jolt (like picking up phone / fingerprint unlock) must NOT trigger torch
        assertFalse(detector.onAccelerationSample(15f, 10f, 2f, 1000L))
        assertFalse(detector.onAccelerationSample(-15f, 10f, 2f, 1180L))
        assertEquals("Double jolt must not trigger when 3 shakes required", 0, triggers)

        // Third shake inside 800ms window triggers once
        assertTrue(detector.onAccelerationSample(16f, 9f, 2f, 1340L))
        assertEquals(1, triggers)

        // 2000ms unlock suppression rejects even 3 firm shakes
        detector.suppressFor(2000L, nowMs = 3000L)
        assertFalse(detector.onAccelerationSample(18f, 0f, 0f, 3200L))
        assertFalse(detector.onAccelerationSample(-18f, 0f, 0f, 3380L))
        assertFalse(detector.onAccelerationSample(18f, 0f, 0f, 3550L))
        assertEquals(1, triggers)

        // BackTapDetector was completely removed
        assertFalse(runCatching { Class.forName("com.example.BackTapDetector") }.isSuccess)
    }

    @Test
    fun `Fix 7 - Cutout Box 1 Preset buttons attach saved prompt text with cropped image`() {
        registerResolveActivity(
            action = Intent.ACTION_SEND,
            pkg = SelectionToolbarHelper.GOOGLE_APP_PACKAGE,
            mimeType = "image/png"
        )
        val prefs = appContext.getSharedPreferences(SelectionToolbarHelper.PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putString(SelectionToolbarHelper.PREF_QUICK_PROMPT_1, "Explain this diagram")
            .putString(SelectionToolbarHelper.PREF_QUICK_PROMPT_2, "Solve step by step")
            .commit()

        val wm = appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val controller = InlineTextOverlayController(appContext, wm)
        var receivedRect: RectF? = null
        var receivedPrompt = ""

        try {
            controller.showCopyOverlay(
                nodes = emptyList(),
                accentColor = Color.CYAN,
                onCopyClicked = {},
                onRescanRequested = {},
                onCutoutGoogleAiModeClicked = { rect, _, _, _, question ->
                    receivedRect = rect
                    receivedPrompt = question
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
            assertTrue(selView.selectFullScreenCutout())

            val btnPreset1 = root.findViewById<ImageButton>(R.id.btn_cutout_preset_1)
            val btnPreset2 = root.findViewById<ImageButton>(R.id.btn_cutout_preset_2)
            assertNotNull(btnPreset1)
            assertNotNull(btnPreset2)

            btnPreset1.performClick()
            assertNotNull(receivedRect)
            assertEquals("Explain this diagram", receivedPrompt)

            // Also verify SelectionToolbarHelper attaches both image stream & text query extras
            val bmp = Bitmap.createBitmap(120, 120, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
            val intent = SelectionToolbarHelper.createGoogleSearchImageAndTextIntent(
                context = appContext,
                croppedBitmap = bmp,
                question = "Explain this diagram"
            )!!
            bmp.recycle()
            assertEquals("Explain this diagram", intent.getStringExtra(Intent.EXTRA_TEXT))
            assertEquals("Explain this diagram", intent.getStringExtra(SearchManager.QUERY))
            assertEquals("Explain this diagram", intent.getStringExtra("query"))
            assertNotNull(intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
        } finally {
            controller.dismiss()
        }
    }

    @Test
    fun `Fix 8 - Google Pay & UPI Shield actually detaches overlay windows from WindowManager and restores on exit`() {
        val wm = appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val fwm = FloatingWindowManager(appContext, wm)
        val proTools = ProToolsOverlayManager(appContext, wm)
        val serviceCtrl = Robolectric.buildService(FloatingService::class.java).create().startCommand(0, 1)
        val service = serviceCtrl.get()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

        val a11yService = Robolectric.buildService(SmartDotAccessibilityService::class.java).create().get()
        SmartDotAccessibilityService.setInstanceForTest(a11yService)

        try {
            // Confirm banking package detection
            assertTrue(SmartDotAccessibilityService.isProtectedBankingPackage("com.google.android.apps.nbu.paisa.user"))
            assertTrue(SmartDotAccessibilityService.isProtectedBankingPackage("com.phonepe.app"))
            assertTrue(SmartDotAccessibilityService.isProtectedBankingPackage("net.one97.paytm"))
            assertTrue(SmartDotAccessibilityService.isProtectedBankingPackage("in.org.npci.upiapp"))
            assertFalse(SmartDotAccessibilityService.isProtectedBankingPackage("com.whatsapp"))

            val floatingField = FloatingService::class.java.getDeclaredField("floatingView")
                .apply { isAccessible = true }
            val dotView = floatingField.get(service) as View
            assertTrue("Floating dot should be attached to WindowManager initially", dotView.isAttachedToWindow)

            val noteWin = FloatingMiniApps.openFloatingNotes(appContext, fwm, scope, Color.CYAN)!!
            ShadowLooper.idleMainLooper()
            assertTrue(noteWin.rootView.isAttachedToWindow)

            proTools.toggleAutoScroller()
            assertTrue(proTools.isAutoScrollerActive())

            // Activate UPI Payment Shield
            val shieldMethod = FloatingService::class.java.getDeclaredMethod("setBankingShieldHidden", Boolean::class.javaPrimitiveType)
                .apply { isAccessible = true }
            shieldMethod.invoke(service, true)
            fwm.setShieldHidden(true)
            proTools.setShieldHidden(true)
            ShadowLooper.idleMainLooper()

            assertFalse("Dot view must be detached from WindowManager during UPI shield", dotView.isAttachedToWindow)
            assertFalse("Floating window must be detached from WindowManager during UPI shield", noteWin.rootView.isAttachedToWindow)
            assertNull("Opening new windows must be blocked while UPI shield active", FloatingMiniApps.openFloatingCalculator(appContext, fwm, Color.CYAN))

            // Deactivate UPI Payment Shield when leaving bank app
            shieldMethod.invoke(service, false)
            fwm.setShieldHidden(false)
            proTools.setShieldHidden(false)
            ShadowLooper.idleMainLooper()

            assertTrue("Dot view must re-attach after exiting UPI app", dotView.isAttachedToWindow)
            assertTrue("Floating window must re-attach after exiting UPI app", noteWin.rootView.isAttachedToWindow)
        } finally {
            fwm.closeAllWindows()
            proTools.releaseAll()
            serviceCtrl.destroy()
            SmartDotAccessibilityService.setInstanceForTest(null)
            scope.cancel()
        }
    }

    @Test
    fun `Fix 9 - Pop Current App into Floating Window opens matching SmartDot window for foreground app`() {
        val serviceCtrl = Robolectric.buildService(FloatingService::class.java).create()
        val service = serviceCtrl.get()
        val a11yService = Robolectric.buildService(SmartDotAccessibilityService::class.java).create().get()
        SmartDotAccessibilityService.setInstanceForTest(a11yService)

        try {
            val popMethod = FloatingService::class.java.getDeclaredMethod("triggerPopOutActiveAppWindow")
                .apply { isAccessible = true }
            val fwmField = FloatingService::class.java.getDeclaredField("floatingWindowManager")
                .apply { isAccessible = true }

            // 1. WhatsApp foreground root -> Floating WhatsApp
            a11yService.activeRootTestHook = AccessibilityNodeInfo.obtain().apply {
                packageName = "com.whatsapp"
                isVisibleToUser = true
            }
            popMethod.invoke(service)
            val fwm = fwmField.get(service) as? FloatingWindowManager
            assertNotNull(fwm)
            assertTrue(
                "Pop Current App on WhatsApp must open Floating WhatsApp window",
                fwm!!.hasWindow("floating_whatsapp")
            )

            // 2. YouTube foreground root -> Floating YouTube
            a11yService.activeRootTestHook = AccessibilityNodeInfo.obtain().apply {
                packageName = "com.google.android.youtube"
                isVisibleToUser = true
            }
            popMethod.invoke(service)
            assertTrue(fwm.hasWindow("floating_youtube"))

            // 3. Chrome foreground root with URL -> Floating Browser
            val urlNode = AccessibilityNodeInfo.obtain().apply {
                packageName = "com.android.chrome"
                viewIdResourceName = "com.android.chrome:id/url_bar"
                text = "https://kotlinlang.org"
                isVisibleToUser = true
            }
            val chromeRoot = AccessibilityNodeInfo.obtain().apply {
                packageName = "com.android.chrome"
                isVisibleToUser = true
            }
            Shadows.shadowOf(chromeRoot).addChild(urlNode)
            a11yService.activeRootTestHook = chromeRoot
            popMethod.invoke(service)
            assertTrue(fwm.hasWindow("floating_browser"))
        } finally {
            a11yService.activeRootTestHook = null
            SmartDotAccessibilityService.setInstanceForTest(null)
            serviceCtrl.destroy()
        }
    }
}
