package com.example

import android.content.Context
import android.os.SystemClock
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.PopupWindow
import androidx.viewpager.widget.ViewPager
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
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSettings

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class FloatingServiceRegressionTest {

    @Before
    fun setUp() {
        ShadowSettings.setCanDrawOverlays(true)
    }

    @After
    fun tearDown() {
        ShadowSettings.setCanDrawOverlays(false)
    }

    @Test
    fun `floating_popup_layout inflates cleanly under service context`() {
        val controller = Robolectric.buildService(FloatingService::class.java)
        val service = controller.create().get()
        val view = LayoutInflater.from(service).inflate(R.layout.floating_popup_layout, null)
        assertNotNull(view.findViewById<View>(R.id.ll_popup_container))
        assertNotNull(view.findViewById<Button>(R.id.btn_clipboard_scan_screen))
    }

    @Test
    fun `FloatingService refuses to stick running when overlay permission is missing`() {
        ShadowSettings.setCanDrawOverlays(false)
        val controller = Robolectric.buildService(FloatingService::class.java)
        controller.create().startCommand(0, 1)
        assertFalse(FloatingService.isRunning)
        controller.destroy()
    }

    @Test
    fun `dot click, edge swipe, 3 pages, shortcuts, repeated open-close, and service stability all work`() {
        val controller = Robolectric.buildService(FloatingService::class.java)
        val service = controller.create().startCommand(0, 1).get()
        assertTrue(FloatingService.isRunning)

        // Repeated onStartCommand stays stable
        controller.startCommand(0, 2)
        assertTrue(FloatingService.isRunning)

        val floatingViewField = FloatingService::class.java.getDeclaredField("floatingView").apply { isAccessible = true }
        val sidebarViewField = FloatingService::class.java.getDeclaredField("sidebarView").apply { isAccessible = true }
        val activePopupField = FloatingService::class.java.getDeclaredField("activePopupWindow").apply { isAccessible = true }
        val lastDismissField = FloatingService::class.java.getDeclaredField("lastDismissTime").apply { isAccessible = true }

        val floatingView = floatingViewField.get(service) as View
        val sidebarView = sidebarViewField.get(service) as View
        val imgDot = floatingView.findViewById<View>(R.id.img_floating_dot)
        assertNotNull(imgDot)

        // 1. Tapping floating dot opens the sidebar
        val downT = SystemClock.uptimeMillis()
        imgDot.dispatchTouchEvent(MotionEvent.obtain(downT, downT, MotionEvent.ACTION_DOWN, 10f, 10f, 0))
        imgDot.dispatchTouchEvent(MotionEvent.obtain(downT, downT + 20, MotionEvent.ACTION_UP, 10f, 10f, 0))

        val popupFromDot = activePopupField.get(service) as? PopupWindow
        assertNotNull("Floating dot tap must open sidebar PopupWindow", popupFromDot)
        assertTrue(popupFromDot?.isShowing == true)

        // Tapping dot while open closes it cleanly
        imgDot.dispatchTouchEvent(MotionEvent.obtain(downT + 40, downT + 40, MotionEvent.ACTION_DOWN, 10f, 10f, 0))
        imgDot.dispatchTouchEvent(MotionEvent.obtain(downT + 40, downT + 60, MotionEvent.ACTION_UP, 10f, 10f, 0))
        assertFalse(popupFromDot?.isShowing == true)

        // 2. Swiping the edge handle inward opens the sidebar
        val swipeT = SystemClock.uptimeMillis()
        sidebarView.dispatchTouchEvent(MotionEvent.obtain(swipeT, swipeT, MotionEvent.ACTION_DOWN, 300f, 200f, 0))
        sidebarView.dispatchTouchEvent(MotionEvent.obtain(swipeT, swipeT + 60, MotionEvent.ACTION_UP, 120f, 200f, 0))

        val popupFromSwipe = activePopupField.get(service) as? PopupWindow
        assertNotNull("Edge handle swipe must open sidebar PopupWindow", popupFromSwipe)
        assertTrue(popupFromSwipe?.isShowing == true)

        // 3. All three sidebar pages exist and work
        val popupContent = popupFromSwipe!!.contentView
        val pager = popupContent.findViewById<ViewPager>(R.id.vp_shortcuts_pager)
        val indicators = popupContent.findViewById<LinearLayout>(R.id.page_indicators_container)
        assertNotNull(pager)
        assertNotNull(pager.adapter)
        assertTrue("Sidebar must have at least 3 pages", (pager.adapter?.count ?: 0) >= 3)
        assertEquals(pager.adapter?.count, indicators.childCount)

        // Trigger looper idle + measure/layout so ViewPager instantiates page views
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        val widthSpec = View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY)
        val heightSpec = View.MeasureSpec.makeMeasureSpec(1200, View.MeasureSpec.EXACTLY)
        popupContent.measure(widthSpec, heightSpec)
        popupContent.layout(0, 0, 800, 1200)
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        val page0 = pager.getChildAt(0) as? ViewGroup
        assertNotNull("ViewPager should instantiate page 0 after layout (childCount=${pager.childCount})", page0)
        val firstRow = page0?.getChildAt(0) as? ViewGroup
        assertNotNull("Page 0 should contain shortcut rows", firstRow)
        val firstShortcut = firstRow?.getChildAt(0)
        assertNotNull("Row 0 should contain shortcut item", firstShortcut)

        pager.setCurrentItem(1, false)
        assertEquals(1, pager.currentItem)
        pager.setCurrentItem(2, false)
        assertEquals(2, pager.currentItem)
        pager.setCurrentItem(0, false)
        assertEquals(0, pager.currentItem)

        // 4. Existing shortcuts and tabs work
        firstShortcut?.performClick()
        val tabBar = popupContent.findViewById<LinearLayout>(R.id.tab_bar)
        assertNotNull(tabBar)
        assertTrue(tabBar.childCount >= 4)

        val layoutCalc = popupContent.findViewById<View>(R.id.layout_tab_calc)
        val layoutTranslate = popupContent.findViewById<View>(R.id.layout_tab_translate)
        val layoutClipboard = popupContent.findViewById<View>(R.id.layout_tab_clipboard)
        val layoutNotes = popupContent.findViewById<View>(R.id.layout_tab_notes)

        tabBar.getChildAt(0).performClick()
        assertEquals(View.VISIBLE, layoutCalc.visibility)
        tabBar.getChildAt(1).performClick()
        assertEquals(View.VISIBLE, layoutTranslate.visibility)
        tabBar.getChildAt(2).performClick()
        assertEquals(View.VISIBLE, layoutClipboard.visibility)
        tabBar.getChildAt(3).performClick()
        assertEquals(View.VISIBLE, layoutNotes.visibility)

        val scanBtn = popupContent.findViewById<Button>(R.id.btn_clipboard_scan_screen)
        assertNotNull(scanBtn)
        assertEquals(service.getString(R.string.screen_copy_button_label), scanBtn.text.toString())

        // 5. Closing and reopening repeatedly works (alternating dot tap and edge swipe)
        for (cycle in 1..4) {
            (activePopupField.get(service) as? PopupWindow)?.dismiss()
            lastDismissField.setLong(service, System.currentTimeMillis() - 500L)

            if (cycle % 2 == 1) {
                val t = SystemClock.uptimeMillis()
                imgDot.dispatchTouchEvent(MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, 10f, 10f, 0))
                imgDot.dispatchTouchEvent(MotionEvent.obtain(t, t + 15, MotionEvent.ACTION_UP, 10f, 10f, 0))
            } else {
                val t = SystemClock.uptimeMillis()
                sidebarView.dispatchTouchEvent(MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, 300f, 200f, 0))
                sidebarView.dispatchTouchEvent(MotionEvent.obtain(t, t + 45, MotionEvent.ACTION_UP, 140f, 200f, 0))
            }

            val reopened = activePopupField.get(service) as? PopupWindow
            assertNotNull("Cycle $cycle must reopen sidebar PopupWindow", reopened)
            assertTrue("Cycle $cycle PopupWindow must be showing", reopened?.isShowing == true)
        }

        // 6. FloatingService shuts down cleanly
        controller.destroy()
        assertFalse(FloatingService.isRunning)
    }
}
