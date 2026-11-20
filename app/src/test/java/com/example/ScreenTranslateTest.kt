package com.example

import android.app.Application
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.google.mlkit.nl.translate.TranslateLanguage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import org.robolectric.shadows.ShadowToast
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ScreenTranslateTest {

    private lateinit var appContext: Application

    @Before
    fun setUp() {
        appContext = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun `ScreenTranslateHelper uses offline translation logic and auto-flips bilingual Hindi-English cleanly`() {
        val helper = ScreenTranslateHelper(
            identifyLanguageFn = { text ->
                if (text.any { it in '\u0900'..'\u097F' }) "hi" else "en"
            },
            translateBlockFn = { src, dst, text, _ ->
                when {
                    src == TranslateLanguage.ENGLISH && dst == TranslateLanguage.HINDI -> "आपका स्वागत है"
                    src == TranslateLanguage.HINDI && dst == TranslateLanguage.ENGLISH -> "Hello India"
                    else -> text
                }
            }
        )

        val enToHi = runBlocking {
            helper.translateTextDetailed("Welcome", "Hindi", autoFlipIfSameAsTarget = true)
        }
        assertEquals(TranslateLanguage.ENGLISH, enToHi.sourceLanguageTag)
        assertEquals(TranslateLanguage.HINDI, enToHi.targetLanguageTag)
        assertEquals("आपका स्वागत है", enToHi.translatedText)

        // When target is Hindi and tapped text is already Hindi, autoFlipIfSameAsTarget translates to English.
        val hiToEn = runBlocking {
            helper.translateTextDetailed("नमस्ते भारत", "Hindi", autoFlipIfSameAsTarget = true)
        }
        assertEquals(TranslateLanguage.HINDI, hiToEn.sourceLanguageTag)
        assertEquals(TranslateLanguage.ENGLISH, hiToEn.targetLanguageTag)
        assertEquals("Hello India", hiToEn.translatedText)

        helper.close()
    }

    @Test
    fun `ScreenTranslateHelper translateNodesBulk translates visible nodes with deduplication`() {
        var translateCalls = 0
        val helper = ScreenTranslateHelper(
            identifyLanguageFn = { "en" },
            translateBlockFn = { _, dst, text, _ ->
                translateCalls++
                when {
                    dst == TranslateLanguage.HINDI && text == "Home" -> "होम"
                    dst == TranslateLanguage.HINDI && text == "Settings" -> "सेटिंग्स"
                    else -> text
                }
            }
        )

        val nodes = listOf(
            SmartDotAccessibilityService.ScreenTextNode(0, "Home", Rect(40, 100, 200, 160)),
            SmartDotAccessibilityService.ScreenTextNode(1, "Settings", Rect(40, 200, 260, 260)),
            SmartDotAccessibilityService.ScreenTextNode(2, "Home", Rect(40, 300, 200, 360))
        )

        val translated = runBlocking { helper.translateNodesBulk(nodes, "Hindi") }
        assertEquals(3, translated.size)
        assertEquals("होम", translated[0].result.translatedText)
        assertEquals("सेटिंग्स", translated[1].result.translatedText)
        assertEquals("होम", translated[2].result.translatedText)
        assertEquals(2, translateCalls)

        helper.close()
    }

    @Test
    fun `ScreenTranslateController opens transparent overlay, bulk-translates immediately in place, switches language live, and copies on label tap`() {
        val scope = CoroutineScope(Dispatchers.Main + Job())
        val clipboard = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.clearPrimaryClip()

        val translateHelper = ScreenTranslateHelper(
            identifyLanguageFn = { "en" },
            translateBlockFn = { _, dst, text, _ ->
                when (dst) {
                    TranslateLanguage.HINDI -> when (text) {
                        "Settings" -> "सेटिंग्स"
                        "Notifications" -> "सूचनाएं"
                        else -> text
                    }
                    TranslateLanguage.SPANISH -> when (text) {
                        "Settings" -> "Ajustes"
                        "Notifications" -> "Notificaciones"
                        else -> text
                    }
                    else -> text
                }
            }
        )

        var overlaysVisible = true
        var savedLang = "Hindi"
        val nodes = listOf(
            SmartDotAccessibilityService.ScreenTextNode(0, "Settings", Rect(100, 220, 360, 280), hasVisibleText = true),
            SmartDotAccessibilityService.ScreenTextNode(1, "Notifications", Rect(100, 360, 420, 420), hasVisibleText = true),
            SmartDotAccessibilityService.ScreenTextNode(2, "Back button", Rect(20, 40, 80, 100), hasVisibleText = false)
        )

        val controller = ScreenTranslateController(
            context = appContext,
            serviceScope = scope,
            clipboardManager = clipboard,
            getAccentColor = { Color.parseColor("#00E5FF") },
            getSavedTargetLanguage = { savedLang },
            saveTargetLanguage = { savedLang = it },
            dismissActivePopup = {},
            setHostOverlaysVisible = { overlaysVisible = it },
            onAccessibilityRequired = {},
            nodeExtractor = { nodes },
            translateHelperFactory = { translateHelper }
        )

        controller.start("Hindi")
        ShadowLooper.idleMainLooper(180, TimeUnit.MILLISECONDS)

        assertTrue(controller.isOverlayShowing())
        assertFalse(overlaysVisible)

        val overlayField = ScreenTranslateController::class.java
            .getDeclaredField("overlayController")
            .apply { isAccessible = true }
        val overlayCtrl = overlayField.get(controller) as InlineTextOverlayController

        val rootField = InlineTextOverlayController::class.java
            .getDeclaredField("overlayView")
            .apply { isAccessible = true }
        val root = rootField.get(overlayCtrl) as View
        assertEquals(Color.TRANSPARENT, (root.background as? ColorDrawable)?.color)

        val selView = root.findViewById<InlineTextSelectionView>(R.id.inline_ocr_selection_view)
        assertEquals(Color.TRANSPARENT, (selView.background as? ColorDrawable)?.color)

        root.measure(
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(2400, View.MeasureSpec.EXACTLY)
        )
        root.layout(0, 0, 1080, 2400)

        // Bulk translation runs automatically without tapping each item; icon-only node (id=2) is skipped.
        val deadline = System.currentTimeMillis() + 3000L
        while (selView.getRenderedTranslatedLabelsSnapshot().size < 2 && System.currentTimeMillis() < deadline) {
            ShadowLooper.idleMainLooper(25, TimeUnit.MILLISECONDS)
            Thread.sleep(15)
        }

        val hindiLabels = selView.getRenderedTranslatedLabelsSnapshot()
        assertEquals(2, hindiLabels.size)
        assertEquals("सेटिंग्स", hindiLabels[0].item.result.translatedText)
        assertEquals("सूचनाएं", hindiLabels[1].item.result.translatedText)
        assertEquals(View.GONE, root.findViewById<TextView>(R.id.tv_inline_status_pill).visibility)
        assertEquals(View.GONE, root.findViewById<LinearLayout>(R.id.btn_inline_ocr_copy).visibility)

        // Switch target language to Spanish in the top bar -> bulk re-translates in place immediately.
        val langChip = root.findViewById<TextView>(R.id.btn_inline_target_lang)
        val dropdown = root.findViewById<LinearLayout>(R.id.inline_lang_dropdown)
        langChip.performClick()
        assertEquals(View.VISIBLE, dropdown.visibility)

        dropdown.findViewWithTag<TextView>("lang_option_Spanish").performClick()
        assertEquals("Spanish", savedLang)
        assertEquals(View.GONE, dropdown.visibility)

        val deadlineEs = System.currentTimeMillis() + 3000L
        while (
            selView.getRenderedTranslatedLabelsSnapshot().firstOrNull()?.item?.result?.translatedText != "Ajustes" &&
            System.currentTimeMillis() < deadlineEs
        ) {
            ShadowLooper.idleMainLooper(25, TimeUnit.MILLISECONDS)
            Thread.sleep(15)
        }

        val spanishLabels = selView.getRenderedTranslatedLabelsSnapshot()
        assertEquals("Ajustes", spanishLabels[0].item.result.translatedText)
        assertEquals("Notificaciones", spanishLabels[1].item.result.translatedText)

        // Tap any translated label directly -> copies translated text to clipboard and shows Copied toast.
        val targetRect = spanishLabels[0].rect
        val down = MotionEvent.obtain(100L, 100L, MotionEvent.ACTION_DOWN, targetRect.centerX(), targetRect.centerY(), 0)
        val up = MotionEvent.obtain(100L, 130L, MotionEvent.ACTION_UP, targetRect.centerX(), targetRect.centerY(), 0)
        selView.dispatchTouchEvent(down)
        selView.dispatchTouchEvent(up)
        down.recycle()
        up.recycle()
        ShadowLooper.idleMainLooper()

        assertEquals("Ajustes", clipboard.primaryClip?.getItemAt(0)?.text?.toString())
        assertEquals(appContext.getString(R.string.screen_copy_copied), ShadowToast.getTextOfLatestToast())

        // Tap Close -> dismisses cleanly and restores host overlays
        root.findViewById<LinearLayout>(R.id.btn_inline_ocr_close).performClick()
        assertFalse(controller.isOverlayShowing())
        assertTrue(overlaysVisible)

        controller.release()
        scope.cancel()
    }
}
