package com.example

import android.accessibilityservice.AccessibilityService
import android.app.Application
import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.database.MatrixCursor
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
import android.widget.EditText
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
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.fakes.RoboCursor
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
        appContext.getSharedPreferences(SelectionToolbarHelper.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @After
    fun tearDown() {
        SmartDotAccessibilityService.instance = null
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

    @Test
    fun `Fix 2 - Zero-limit device media queries, RecyclerView containers, and Hide under 1 Min Audio toggle`() {
        val shadowCr = Shadows.shadowOf(appContext.contentResolver)

        // Populate 220 audio tracks (170 full songs >= 60s, 50 voice notes < 60s)
        val audioCursor = RoboCursor().apply {
            setColumnNames(
                listOf(
                    MediaStore.Audio.Media._ID,
                    MediaStore.Audio.Media.DISPLAY_NAME,
                    MediaStore.Audio.Media.DURATION,
                    MediaStore.Audio.Media.SIZE,
                    MediaStore.Audio.Media.RELATIVE_PATH
                )
            )
            val rows = ArrayList<Array<Any>>()
            for (i in 1..170) {
                rows.add(arrayOf(i.toLong(), "Song_$i.mp3", 185_000L, 4_200_000L, "Music/"))
            }
            for (i in 171..220) {
                rows.add(arrayOf(i.toLong(), "VoiceNote_$i.m4a", 24_000L, 280_000L, "Recordings/"))
            }
            setResults(rows.toTypedArray())
        }
        shadowCr.setCursor(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, audioCursor)

        // Populate 180 photos + 110 downloads files (all above old 120 / 80 caps)
        val imageCursor = RoboCursor().apply {
            setColumnNames(
                listOf(
                    MediaStore.Images.Media._ID,
                    MediaStore.Images.Media.DISPLAY_NAME,
                    MediaStore.Images.Media.DATE_ADDED,
                    MediaStore.Images.Media.RELATIVE_PATH
                )
            )
            val rows = Array(180) { idx ->
                arrayOf<Any>((idx + 1).toLong(), "IMG_${idx + 1}.jpg", (1000 + idx).toLong(), "Pictures/")
            }
            setResults(rows)
        }
        shadowCr.setCursor(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, imageCursor)

        val downloadsCursor = RoboCursor().apply {
            setColumnNames(
                listOf(
                    MediaStore.MediaColumns._ID,
                    MediaStore.MediaColumns.DISPLAY_NAME,
                    MediaStore.MediaColumns.MIME_TYPE,
                    MediaStore.MediaColumns.SIZE,
                    MediaStore.MediaColumns.RELATIVE_PATH
                )
            )
            val rows = Array(110) { idx ->
                arrayOf<Any>((idx + 1).toLong(), "Doc_${idx + 1}.pdf", "application/pdf", 50_000L, "Download/")
            }
            setResults(rows)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            shadowCr.setCursor(MediaStore.Downloads.EXTERNAL_CONTENT_URI, downloadsCursor)
        }
        shadowCr.setCursor(MediaStore.Files.getContentUri("external"), downloadsCursor)

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

        // Open UI windows and verify RecyclerView + Hide < 1 Min Audio live filtering
        val wm = appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val fwm = FloatingWindowManager(appContext, wm)
        try {
            val musicWin = FloatingGalleryMusicHelper.openFloatingMusicPlayer(appContext, fwm, Color.CYAN)!!
            ShadowLooper.idleMainLooper(200L, TimeUnit.MILLISECONDS)

            val rvMusic = musicWin.rootView.findViewWithTag<View>("fw_music_list")
            assertTrue("Music list must use RecyclerView", rvMusic is RecyclerView)
            val hideToggle = musicWin.rootView.findViewWithTag<Switch>("fw_music_hide_short_toggle")
            assertNotNull(hideToggle)
            assertTrue("Hide < 1 Min Audio defaults ON", hideToggle.isChecked)

            val adapter = (rvMusic as RecyclerView).adapter!!
            assertEquals("With Hide < 1 Min Audio ON, 170 full songs shown", 170, adapter.itemCount)

            hideToggle.isChecked = false
            assertEquals("With Hide < 1 Min Audio OFF, all 220 tracks shown", 220, adapter.itemCount)

            val galleryWin = FloatingGalleryMusicHelper.openFloatingPhotosVideosGallery(appContext, fwm, Color.CYAN)!!
            ShadowLooper.idleMainLooper(200L, TimeUnit.MILLISECONDS)
            val rvGallery = galleryWin.rootView.findViewWithTag<View>("fw_gallery_grid")
            assertTrue("Gallery grid must use RecyclerView", rvGallery is RecyclerView)
            assertEquals(180, (rvGallery as RecyclerView).adapter!!.itemCount)

            val mediaWin = FloatingMediaFilesHelper.openFloatingMediaFiles(appContext, fwm, Color.CYAN)!!
            ShadowLooper.idleMainLooper(200L, TimeUnit.MILLISECONDS)
            val rvMedia = mediaWin.rootView.findViewWithTag<View>("fw_media_file_list")
            assertTrue("Media file list must use RecyclerView", rvMedia is RecyclerView)
            assertEquals(110, (rvMedia as RecyclerView).adapter!!.itemCount)
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
                onCutoutSearchClicked = { rect, _ -> capturedBox = rect },
                onDismiss = {}
            )

            val rootField = InlineTextOverlayController::class.java.getDeclaredField("rootView")
                .apply { isAccessible = true }
            val root = rootField.get(controller) as View
            root.measure(
                View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(2400, View.MeasureSpec.EXACTLY)
            )
            root.layout(0, 0, 1080, 2400)

            val selView = root.findViewById<InlineTextSelectionView>(R.id.inline_ocr_selection_view)
            assertTrue(selView.selectFullScreenCutout())

            val cutoutRect = selView.getCutoutRectViewCoords()
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

            // Trigger search with preset verifies capturedBox works
            val sendBtn = root.findViewById<ImageButton>(R.id.btn_cutout_ai_send)
            sendBtn.performClick()
            assertNotNull(capturedBox)
        } finally {
            controller.release()
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
            allActions.any { it.id == saved!!.actionId && it.label.contains("Meta AI") }
        )

        val gestureOptions = SwipeGestureHelper.getAllGestureOptions(prefs)
        assertTrue(
            "Saved Meta AI shortcut must appear in 22-gesture picker options",
            gestureOptions.any { it.id == saved!!.actionId && it.label.contains("Meta AI") }
        )
    }

    @Test
    fun `Fix 6 - BackTapDetector enforces strict Z-axis isolation and prefers TYPE_LINEAR_ACCELERATION`() {
        var triggers = 0
        val detector = BackTapDetector(zSpikeThreshold = 7.5f) {
            triggers++
        }

        // 1. Rejects lateral shake (X or Y > 4.5) even when Z spike is in range
        assertFalse(detector.onLinearAccelerationSample(5.2f, 0.3f, 10.0f, 1000L))
        assertFalse(detector.onLinearAccelerationSample(0.2f, 0.1f, 10.0f, 1250L))
        assertEquals("Lateral tilt/shake must never trigger back-tap", 0, triggers)

        // 2. Rejects extreme slam > 18.0 m/s^2
        assertFalse(detector.onLinearAccelerationSample(0.2f, 0.2f, 22.0f, 2000L))
        assertFalse(detector.onLinearAccelerationSample(0.2f, 0.2f, 10.0f, 2220L))
        assertEquals("Excessive slam > 18.0 must not trigger", 0, triggers)

        // 3. Rejects sustained shake pulse >= 60ms
        assertFalse(detector.onLinearAccelerationSample(0.2f, 0.2f, 9.0f, 3000L))
        assertFalse(detector.onLinearAccelerationSample(0.2f, 0.2f, 9.5f, 3035L))
        assertFalse(detector.onLinearAccelerationSample(0.2f, 0.2f, 9.2f, 3075L)) // duration 75ms >= 60ms -> invalidated
        assertFalse(detector.onLinearAccelerationSample(0.2f, 0.2f, 9.8f, 3260L))
        assertEquals("Sustained pulse >= 60ms must be rejected", 0, triggers)

        // 4. Rejects gap < 120ms or > 380ms
        assertFalse(detector.onLinearAccelerationSample(0.1f, 0.1f, 9.5f, 4000L))
        assertFalse(detector.onLinearAccelerationSample(0.1f, 0.1f, 9.5f, 4080L)) // 80ms < 120ms -> ignored
        assertFalse(detector.onLinearAccelerationSample(0.1f, 0.1f, 9.5f, 4450L)) // 450ms > 380ms -> resets first tap
        assertEquals(0, triggers)

        // 5. Two crisp isolated Z-axis taps 7.5..18.0 spaced 200ms trigger cleanly
        assertFalse(detector.onLinearAccelerationSample(0.2f, 0.3f, 9.5f, 5000L))
        assertTrue(detector.onLinearAccelerationSample(0.2f, 0.3f, 10.5f, 5200L))
        assertEquals(1, triggers)

        // 6. FloatingService prefers TYPE_LINEAR_ACCELERATION over TYPE_ACCELEROMETER
        val sensorManager = appContext.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        Shadows.shadowOf(sensorManager).addSensor(ShadowSensor.newInstance(Sensor.TYPE_ACCELEROMETER))
        Shadows.shadowOf(sensorManager).addSensor(ShadowSensor.newInstance(Sensor.TYPE_LINEAR_ACCELERATION))

        appContext.getSharedPreferences(SelectionToolbarHelper.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(FloatingService.PREF_BACK_TAP_DATA_ENABLED, true)
            .commit()

        val controller = Robolectric.buildService(FloatingService::class.java).create()
        val service = controller.get()
        try {
            val isLinearField = FloatingService::class.java.getDeclaredField("isBackTapLinearSensor")
                .apply { isAccessible = true }
            assertTrue(
                "FloatingService must choose TYPE_LINEAR_ACCELERATION when available",
                isLinearField.getBoolean(service)
            )
        } finally {
            controller.destroy()
        }
    }

    @Test
    fun `Fix 7 - Cutout Box 1 Preset buttons attach saved prompt text with cropped image`() {
        val prefs = appContext.getSharedPreferences(SelectionToolbarHelper.PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putString(SelectionToolbarHelper.PREF_TOOLBAR_PRESET_1, "Explain this diagram")
            .putString(SelectionToolbarHelper.PREF_TOOLBAR_PRESET_2, "Solve step by step")
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
                onCutoutGoogleAiModeClicked = { rect, prompt ->
                    receivedRect = rect
                    receivedPrompt = prompt
                },
                onDismiss = {}
            )

            val rootField = InlineTextOverlayController::class.java.getDeclaredField("rootView")
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
            val intent = SelectionToolbarHelper.createGoogleSearchImageAndTextIntent(
                context = appContext,
                imageUri = Uri.parse("content://com.example.fileprovider/cutout.png"),
                question = "Explain this diagram"
            )
            assertEquals("Explain this diagram", intent.getStringExtra(Intent.EXTRA_TEXT))
            assertEquals("Explain this diagram", intent.getStringExtra(SearchManager.QUERY))
            assertEquals("Explain this diagram", intent.getStringExtra("query"))
            assertNotNull(intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
        } finally {
            controller.release()
        }
    }

    @Test
    fun `Fix 8 - Google Pay & UPI Shield actually detaches overlay windows from WindowManager and restores on exit`() {
        val wm = appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val fwm = FloatingWindowManager(appContext, wm)
        val proTools = ProToolsOverlayManager(appContext, wm)
        val serviceCtrl = Robolectric.buildService(FloatingService::class.java).create()
        val service = serviceCtrl.get()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

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
            assertTrue(noteWin.rootView.isAttachedToWindow)

            proTools.toggleAutoScroller(Color.CYAN) {}
            assertTrue(proTools.isAutoScrollerActive())

            // Activate UPI Payment Shield
            val shieldMethod = FloatingService::class.java.getDeclaredMethod("setBankingShieldHidden", Boolean::class.javaPrimitiveType)
                .apply { isAccessible = true }
            shieldMethod.invoke(service, true)
            fwm.setShieldHidden(true)
            proTools.setShieldHidden(true)

            assertFalse("Dot view must be detached from WindowManager during UPI shield", dotView.isAttachedToWindow)
            assertFalse("Floating window must be detached from WindowManager during UPI shield", noteWin.rootView.isAttachedToWindow)
            assertNull("Opening new windows must be blocked while UPI shield active", FloatingMiniApps.openFloatingCalculator(appContext, fwm, Color.CYAN))

            // Deactivate UPI Payment Shield when leaving bank app
            shieldMethod.invoke(service, false)
            fwm.setShieldHidden(false)
            proTools.setShieldHidden(false)

            assertTrue("Dot view must re-attach after exiting UPI app", dotView.isAttachedToWindow)
            assertTrue("Floating window must re-attach after exiting UPI app", noteWin.rootView.isAttachedToWindow)
        } finally {
            fwm.closeAllWindows()
            proTools.releaseAll()
            serviceCtrl.destroy()
            scope.cancel()
        }
    }

    @Test
    fun `Fix 9 - Pop Current App into Floating Window opens matching SmartDot window for foreground app`() {
        val serviceCtrl = Robolectric.buildService(FloatingService::class.java).create()
        val service = serviceCtrl.get()
        val fakeA11y = TestPopOutAccessibilityService("com.whatsapp", null)
        SmartDotAccessibilityService.instance = fakeA11y

        try {
            val popMethod = FloatingService::class.java.getDeclaredMethod("triggerPopOutActiveAppWindow")
                .apply { isAccessible = true }
            popMethod.invoke(service)

            val fwmField = FloatingService::class.java.getDeclaredField("floatingWindowManager")
                .apply { isAccessible = true }
            val fwm = fwmField.get(service) as? FloatingWindowManager
            assertNotNull(fwm)
            assertTrue(
                "Pop Current App on WhatsApp must open Floating WhatsApp window",
                fwm!!.hasWindow("floating_social_whatsapp")
            )

            // Also verify YouTube -> Floating YouTube
            fakeA11y.mockPkg = "com.google.android.youtube"
            popMethod.invoke(service)
            assertTrue(fwm.hasWindow("floating_youtube"))

            // Also verify Chrome + URL -> Floating Browser at that URL
            fakeA11y.mockPkg = "com.android.chrome"
            fakeA11y.mockUrl = "https://kotlinlang.org"
            popMethod.invoke(service)
            assertTrue(fwm.hasWindow("floating_browser"))
        } finally {
            serviceCtrl.destroy()
            SmartDotAccessibilityService.instance = null
        }
    }

    private class TestPopOutAccessibilityService(
        var mockPkg: String,
        var mockUrl: String?
    ) : SmartDotAccessibilityService() {
        override fun getActiveForegroundPackageName(excludedPackage: String?): String? = mockPkg
        override fun extractActiveBrowserUrl(): String? = mockUrl
    }
}
