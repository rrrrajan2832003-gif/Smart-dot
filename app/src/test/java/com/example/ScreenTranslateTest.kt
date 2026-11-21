package com.example

import android.app.Application
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
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
            },
            onlineTranslateFn = { _, _ -> null }
        )

        val enToHi = runBlocking {
            helper.translateTextDetailed("Welcome", "Hindi", autoFlipIfSameAsTarget = true)
        }
        assertEquals(TranslateLanguage.ENGLISH, enToHi.sourceLanguageTag)
        assertEquals(TranslateLanguage.HINDI, enToHi.targetLanguageTag)
        assertEquals("आपका स्वागत है", enToHi.translatedText)

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
            },
            onlineTranslateFn = { _, _ -> null }
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
    fun `ScreenTranslateController runs in-memory ML Kit OCR for image lines, recycles Bitmap, translates live, and supports 6dp highlighter word copy`() {
        val scope = CoroutineScope(Dispatchers.Main + Job())
        val clipboard = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.clearPrimaryClip()
        val inMemoryBitmap = Bitmap.createBitmap(1080, 2400, Bitmap.Config.ARGB_8888)

        val ocrHelper = ScreenOcrHelper(
            customOcrRunner = {
                val latin = listOf(
                    ScreenOcrHelper.OcrRawItem("System Settings", Rect(100, 220, 560, 280), isLine = true),
                    ScreenOcrHelper.OcrRawItem("Notifications", Rect(100, 360, 480, 420), isLine = true)
                )
                latin to emptyList()
            }
        )

        val translateHelper = ScreenTranslateHelper(
            identifyLanguageFn = { "en" },
            translateBlockFn = { _, dst, text, _ ->
                when (dst) {
                    TranslateLanguage.HINDI -> when (text) {
                        "System Settings" -> "सिस्टम सेटिंग्स"
                        "Notifications" -> "सूचनाएं"
                        else -> text
                    }
                    TranslateLanguage.SPANISH -> when (text) {
                        "System Settings" -> "Ajustes del sistema"
                        "Notifications" -> "Notificaciones"
                        else -> text
                    }
                    else -> text
                }
            },
            onlineTranslateFn = { _, _ -> null }
        )

        var overlaysVisible = true
        var savedLang = "Hindi"
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
            nodeExtractor = { emptyList() },
            translateHelperFactory = { translateHelper },
            ocrHelperFactory = { ocrHelper },
            screenBitmapCapturer = { cb -> cb(inMemoryBitmap) }
        )

        controller.start("Hindi")
        assertFalse(overlaysVisible)
        ShadowLooper.idleMainLooper(ScreenTranslateController.CAPTURE_HIDE_DELAY_MS + 10L, TimeUnit.MILLISECONDS)

        val deadlineShow = System.currentTimeMillis() + 3000L
        while (!controller.isOverlayShowing() && System.currentTimeMillis() < deadlineShow) {
            ShadowLooper.idleMainLooper(25, TimeUnit.MILLISECONDS)
            Thread.sleep(15)
        }

        assertTrue(controller.isOverlayShowing())
        assertTrue("Screen bitmap must be recycled after OCR", inMemoryBitmap.isRecycled)

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

        val deadline = System.currentTimeMillis() + 3000L
        while (selView.getRenderedTranslatedLabelsSnapshot().size < 2 && System.currentTimeMillis() < deadline) {
            ShadowLooper.idleMainLooper(25, TimeUnit.MILLISECONDS)
            Thread.sleep(15)
        }

        val hindiLabels = selView.getRenderedTranslatedLabelsSnapshot()
        assertEquals(2, hindiLabels.size)
        assertEquals("सिस्टम सेटिंग्स", hindiLabels[0].item.result.translatedText)
        assertEquals("सूचनाएं", hindiLabels[1].item.result.translatedText)
        assertEquals(View.GONE, root.findViewById<TextView>(R.id.tv_inline_status_pill).visibility)
        assertEquals(View.GONE, root.findViewById<LinearLayout>(R.id.btn_inline_ocr_copy).visibility)

        // Switch target language to Spanish -> clears any prior selection and bulk re-translates in place
        val langChip = root.findViewById<TextView>(R.id.btn_inline_target_lang)
        val dropdown = root.findViewById<LinearLayout>(R.id.inline_lang_dropdown)
        langChip.performClick()
        assertEquals(View.VISIBLE, dropdown.visibility)

        dropdown.findViewWithTag<TextView>("lang_option_Spanish").performClick()
        assertEquals("Spanish", savedLang)
        assertEquals(View.GONE, dropdown.visibility)

        val deadlineEs = System.currentTimeMillis() + 3000L
        while (
            selView.getRenderedTranslatedLabelsSnapshot().firstOrNull()?.item?.result?.translatedText != "Ajustes del sistema" &&
            System.currentTimeMillis() < deadlineEs
        ) {
            ShadowLooper.idleMainLooper(25, TimeUnit.MILLISECONDS)
            Thread.sleep(15)
        }

        val translatedWords = selView.getMappedTranslatedWordNodesSnapshot()
        assertEquals(listOf("Ajustes", "del", "sistema", "Notificaciones"), translatedWords.map { it.node.text })

        // Highlight specific translated words ("Ajustes" + "del") with the thin 6dp highlighter drag
        val w0Rect = selView.getMappedRectForCopyNode(0)!!
        val w1Rect = selView.getMappedRectForCopyNode(1)!!
        val copyBtn = root.findViewById<LinearLayout>(R.id.btn_inline_ocr_copy)

        val down = MotionEvent.obtain(100L, 100L, MotionEvent.ACTION_DOWN, w0Rect.centerX(), w0Rect.centerY(), 0)
        val move = MotionEvent.obtain(100L, 130L, MotionEvent.ACTION_MOVE, w1Rect.centerX(), w1Rect.centerY(), 0)
        val up = MotionEvent.obtain(100L, 160L, MotionEvent.ACTION_UP, w1Rect.centerX(), w1Rect.centerY(), 0)
        selView.dispatchTouchEvent(down)
        selView.dispatchTouchEvent(move)
        assertTrue(selView.isHighlighterTrailActive())
        assertEquals(View.GONE, copyBtn.visibility)
        selView.dispatchTouchEvent(up)
        down.recycle()
        move.recycle()
        up.recycle()
        ShadowLooper.idleMainLooper()

        assertFalse(selView.isHighlighterTrailActive())
        assertEquals("Ajustes del", selView.getCombinedSelectedCopyText())
        assertEquals(View.VISIBLE, copyBtn.visibility)

        // Tap Copy pill -> copies ONLY the highlighted translated words ("Ajustes del") and dismisses overlay
        copyBtn.performClick()
        ShadowLooper.idleMainLooper()

        assertEquals("Ajustes del", clipboard.primaryClip?.getItemAt(0)?.text?.toString())
        assertEquals(appContext.getString(R.string.screen_copy_copied), ShadowToast.getTextOfLatestToast())
        assertFalse(controller.isOverlayShowing())
        assertTrue(overlaysVisible)

        controller.release()
        scope.cancel()
    }

    @Test
    fun `ScreenTranslateController supports single-word tap on translated text in accessibility fallback mode`() {
        val scope = CoroutineScope(Dispatchers.Main + Job())
        val clipboard = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.clearPrimaryClip()

        val translateHelper = ScreenTranslateHelper(
            identifyLanguageFn = { "en" },
            translateBlockFn = { _, dst, text, _ ->
                if (dst == TranslateLanguage.HINDI && text == "Settings") "सेटिंग्स" else text
            },
            onlineTranslateFn = { _, _ -> null }
        )

        var overlaysVisible = true
        val nodes = listOf(
            SmartDotAccessibilityService.ScreenTextNode(0, "Settings", Rect(100, 220, 360, 280), hasVisibleText = true),
            SmartDotAccessibilityService.ScreenTextNode(1, "Back button", Rect(20, 40, 80, 100), hasVisibleText = false)
        )

        val controller = ScreenTranslateController(
            context = appContext,
            serviceScope = scope,
            clipboardManager = clipboard,
            getAccentColor = { Color.parseColor("#00E5FF") },
            getSavedTargetLanguage = { "Hindi" },
            saveTargetLanguage = {},
            dismissActivePopup = {},
            setHostOverlaysVisible = { overlaysVisible = it },
            onAccessibilityRequired = {},
            nodeExtractor = { nodes },
            translateHelperFactory = { translateHelper },
            screenBitmapCapturer = { cb -> cb(null) }
        )

        controller.start("Hindi")
        ShadowLooper.idleMainLooper(80, TimeUnit.MILLISECONDS)
        assertTrue(controller.isOverlayShowing())

        val overlayField = ScreenTranslateController::class.java
            .getDeclaredField("overlayController")
            .apply { isAccessible = true }
        val overlayCtrl = overlayField.get(controller) as InlineTextOverlayController
        val rootField = InlineTextOverlayController::class.java
            .getDeclaredField("overlayView")
            .apply { isAccessible = true }
        val root = rootField.get(overlayCtrl) as View
        val selView = root.findViewById<InlineTextSelectionView>(R.id.inline_ocr_selection_view)

        root.measure(
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(2400, View.MeasureSpec.EXACTLY)
        )
        root.layout(0, 0, 1080, 2400)

        val deadline = System.currentTimeMillis() + 3000L
        while (selView.getMappedTranslatedWordNodesSnapshot().isEmpty() && System.currentTimeMillis() < deadline) {
            ShadowLooper.idleMainLooper(25, TimeUnit.MILLISECONDS)
            Thread.sleep(15)
        }

        val targetRect = selView.getMappedRectForCopyNode(0)!!
        val down = MotionEvent.obtain(100L, 100L, MotionEvent.ACTION_DOWN, targetRect.centerX(), targetRect.centerY(), 0)
        val up = MotionEvent.obtain(100L, 130L, MotionEvent.ACTION_UP, targetRect.centerX(), targetRect.centerY(), 0)
        selView.dispatchTouchEvent(down)
        selView.dispatchTouchEvent(up)
        down.recycle()
        up.recycle()
        ShadowLooper.idleMainLooper()

        val copyBtn = root.findViewById<LinearLayout>(R.id.btn_inline_ocr_copy)
        assertEquals(View.VISIBLE, copyBtn.visibility)
        assertEquals("सेटिंग्स", selView.getCombinedSelectedCopyText())

        root.findViewById<LinearLayout>(R.id.btn_inline_ocr_close).performClick()
        assertFalse(controller.isOverlayShowing())
        assertTrue(overlaysVisible)

        controller.release()
        scope.cancel()
    }

    @Test
    fun `ScreenTranslateHelper uses online fast mode first without model download and falls back cleanly`() {
        val rawJson = """[[["नमस्ते दुनिया","Hello World",null,null,1]],null,"en"]"""
        val parsed = ScreenTranslateHelper.parseGoogleFreeTranslateJson(rawJson)
        assertEquals("नमस्ते दुनिया" to "en", parsed)

        var downloadTriggered = false
        var offlineFallbackCalls = 0
        val helper = ScreenTranslateHelper(
            identifyLanguageFn = { "en" },
            translateBlockFn = { _, _, text, onDownloadStarted ->
                offlineFallbackCalls++
                onDownloadStarted()
                "Offline($text)"
            },
            onlineTranslateFn = { _, text ->
                if (text == "ForceOffline") null else ("Instant($text)" to "en")
            }
        )

        val onlineResult = runBlocking {
            helper.translateTextDetailed(
                text = "Settings",
                targetLanguageName = "Hindi",
                preferOnline = true,
                onModelDownloading = { downloadTriggered = true }
            )
        }
        assertEquals("Instant(Settings)", onlineResult.translatedText)
        assertFalse("Online mode must not trigger ML Kit download", downloadTriggered)
        assertEquals(0, offlineFallbackCalls)

        val fallbackResult = runBlocking {
            helper.translateTextDetailed(
                text = "ForceOffline",
                targetLanguageName = "Hindi",
                preferOnline = true,
                onModelDownloading = { downloadTriggered = true }
            )
        }
        assertEquals("Offline(ForceOffline)", fallbackResult.translatedText)
        assertTrue("Offline fallback must trigger ML Kit path when online fails", downloadTriggered)
        assertEquals(1, offlineFallbackCalls)

        helper.close()
    }

    @Test
    fun `ScreenTranslateController Online-Fast Mode toggle pill switches mode and re-translates live`() {
        val scope = CoroutineScope(Dispatchers.Main + Job())
        val clipboard = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.clearPrimaryClip()

        var savedOnlineMode = true
        val translateHelper = ScreenTranslateHelper(
            identifyLanguageFn = { "en" },
            translateBlockFn = { _, _, text, _ -> "Offline-$text" },
            onlineTranslateFn = { _, text -> "Online-$text" to "en" }
        )

        val nodes = listOf(
            SmartDotAccessibilityService.ScreenTextNode(0, "Messages", Rect(100, 220, 360, 280), hasVisibleText = true)
        )

        val controller = ScreenTranslateController(
            context = appContext,
            serviceScope = scope,
            clipboardManager = clipboard,
            getAccentColor = { Color.parseColor("#00E5FF") },
            getSavedTargetLanguage = { "Hindi" },
            saveTargetLanguage = {},
            getSavedOnlineMode = { savedOnlineMode },
            saveOnlineMode = { savedOnlineMode = it },
            dismissActivePopup = {},
            setHostOverlaysVisible = {},
            onAccessibilityRequired = {},
            nodeExtractor = { nodes },
            translateHelperFactory = { translateHelper },
            screenBitmapCapturer = { cb -> cb(null) }
        )

        controller.start("Hindi")
        ShadowLooper.idleMainLooper(80, TimeUnit.MILLISECONDS)
        assertTrue(controller.isOverlayShowing())

        val overlayField = ScreenTranslateController::class.java
            .getDeclaredField("overlayController")
            .apply { isAccessible = true }
        val overlayCtrl = overlayField.get(controller) as InlineTextOverlayController
        val rootField = InlineTextOverlayController::class.java
            .getDeclaredField("overlayView")
            .apply { isAccessible = true }
        val root = rootField.get(overlayCtrl) as View
        val selView = root.findViewById<InlineTextSelectionView>(R.id.inline_ocr_selection_view)
        val modeChip = root.findViewById<TextView>(R.id.btn_inline_translate_mode)

        root.measure(
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(2400, View.MeasureSpec.EXACTLY)
        )
        root.layout(0, 0, 1080, 2400)

        val deadlineOnline = System.currentTimeMillis() + 3000L
        while (selView.getRenderedTranslatedLabelsSnapshot().isEmpty() && System.currentTimeMillis() < deadlineOnline) {
            ShadowLooper.idleMainLooper(25, TimeUnit.MILLISECONDS)
            Thread.sleep(15)
        }

        assertEquals(View.VISIBLE, modeChip.visibility)
        assertEquals(appContext.getString(R.string.screen_translate_mode_online), modeChip.text.toString())
        assertEquals("Online-Messages", selView.getRenderedTranslatedLabelsSnapshot().first().item.result.translatedText)

        // Toggle Online -> Offline mode
        modeChip.performClick()
        ShadowLooper.idleMainLooper()
        assertFalse(savedOnlineMode)
        assertEquals(appContext.getString(R.string.screen_translate_mode_offline), modeChip.text.toString())

        val deadlineOffline = System.currentTimeMillis() + 3000L
        while (
            selView.getRenderedTranslatedLabelsSnapshot().firstOrNull()?.item?.result?.translatedText != "Offline-Messages" &&
            System.currentTimeMillis() < deadlineOffline
        ) {
            ShadowLooper.idleMainLooper(25, TimeUnit.MILLISECONDS)
            Thread.sleep(15)
        }
        assertEquals("Offline-Messages", selView.getRenderedTranslatedLabelsSnapshot().first().item.result.translatedText)

        controller.release()
        scope.cancel()
    }
}
