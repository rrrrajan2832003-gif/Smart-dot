package com.example

import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.net.URLEncoder

/**
 * Shared 10-action configuration, SharedPreferences order persistence, and direct Intent
 * launchers for the unified icon-only floating toolbar (used in both Screen Text Copy and
 * Circle-to-Search Cutout modes).
 */
object SelectionToolbarHelper {

    private const val TAG = "SelectionToolbarHelper"
    const val PREFS_NAME = "smart_dot_prefs"
    const val PREF_SELECTION_TOOLBAR_ORDER = "selection_toolbar_order"
    const val PREF_SELECTION_TOOLBAR_HIDDEN = "selection_toolbar_hidden"

    const val GOOGLE_APP_PACKAGE = "com.google.android.googlequicksearchbox"
    const val SEARCH_LITE_PACKAGE = "com.google.android.apps.searchlite"
    const val GEMINI_PACKAGE = "com.google.android.apps.bard"
    const val CHATGPT_PACKAGE = "com.openai.chatgpt"

    enum class ToolbarAction(
        val id: String,
        val label: String,
        val iconRes: Int,
        val contentDesc: String
    ) {
        COPY(
            id = "copy",
            label = "Copy",
            iconRes = R.drawable.ic_ocr_copy,
            contentDesc = "Copy selected text"
        ),
        SPEAK(
            id = "speak",
            label = "Speak / Read Aloud",
            iconRes = R.drawable.ic_toolbar_speak,
            contentDesc = "Speak or read aloud"
        ),
        TRANSLATE(
            id = "translate",
            label = "Translate Selection",
            iconRes = R.drawable.ic_toolbar_translate,
            contentDesc = "Translate selection"
        ),
        SEARCH(
            id = "search",
            label = "Google Search",
            iconRes = R.drawable.ic_toolbar_search,
            contentDesc = "Search in Google App"
        ),
        SAVE_IMAGE(
            id = "save_image",
            label = "Save Image",
            iconRes = R.drawable.ic_toolbar_save_image,
            contentDesc = "Save image to gallery"
        ),
        GOOGLE_AI_MODE(
            id = "google_ai_mode",
            label = "Google AI Mode",
            iconRes = R.drawable.ic_toolbar_ai_mode,
            contentDesc = "Google AI Mode"
        ),
        GEMINI(
            id = "gemini",
            label = "Gemini",
            iconRes = R.drawable.ic_toolbar_gemini,
            contentDesc = "Send to Gemini"
        ),
        CHATGPT(
            id = "chatgpt",
            label = "ChatGPT",
            iconRes = R.drawable.ic_toolbar_chatgpt,
            contentDesc = "Send to ChatGPT"
        ),
        SHARE(
            id = "share",
            label = "Share",
            iconRes = R.drawable.ic_toolbar_share,
            contentDesc = "Share"
        ),
        FULL_SCREEN_TRANSLATE(
            id = "full_screen_translate",
            label = "Full Screen Translate",
            iconRes = R.drawable.ic_toolbar_full_translate,
            contentDesc = "Full Screen Translate"
        ),
        GOOGLE_LENS(
            id = "google_lens",
            label = "Google Lens",
            iconRes = R.drawable.ic_toolbar_lens,
            contentDesc = "Open in Google Lens"
        );

        companion object {
            fun fromId(id: String): ToolbarAction? = entries.find { it.id == id }
        }
    }

    val DEFAULT_ORDER: List<ToolbarAction> = ToolbarAction.entries.toList()

    fun getToolbarOrder(context: Context): List<ToolbarAction> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return getToolbarOrder(prefs)
    }

    fun getToolbarOrder(prefs: SharedPreferences): List<ToolbarAction> {
        val saved = prefs.getString(PREF_SELECTION_TOOLBAR_ORDER, null)?.trim().orEmpty()
        if (saved.isEmpty()) return DEFAULT_ORDER
        val resolved = LinkedHashSet<ToolbarAction>()
        saved.split(",").forEach { token ->
            ToolbarAction.fromId(token.trim())?.let { resolved.add(it) }
        }
        DEFAULT_ORDER.forEach { resolved.add(it) }
        return resolved.toList()
    }

    fun saveToolbarOrder(prefs: SharedPreferences, order: List<ToolbarAction>) {
        val normalized = LinkedHashSet<ToolbarAction>(order)
        DEFAULT_ORDER.forEach { normalized.add(it) }
        prefs.edit()
            .putString(PREF_SELECTION_TOOLBAR_ORDER, normalized.joinToString(",") { it.id })
            .apply()
    }

    fun getHiddenToolbarActions(context: Context): Set<ToolbarAction> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return getHiddenToolbarActions(prefs)
    }

    fun getHiddenToolbarActions(prefs: SharedPreferences): Set<ToolbarAction> {
        val saved = prefs.getString(PREF_SELECTION_TOOLBAR_HIDDEN, null)?.trim().orEmpty()
        if (saved.isEmpty()) return emptySet()
        return saved.split(",")
            .mapNotNull { ToolbarAction.fromId(it.trim()) }
            .toSet()
    }

    fun saveHiddenToolbarActions(prefs: SharedPreferences, hidden: Set<ToolbarAction>) {
        prefs.edit()
            .putString(PREF_SELECTION_TOOLBAR_HIDDEN, hidden.joinToString(",") { it.id })
            .apply()
    }

    fun getVisibleToolbarOrder(context: Context): List<ToolbarAction> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return getVisibleToolbarOrder(prefs)
    }

    fun getVisibleToolbarOrder(prefs: SharedPreferences): List<ToolbarAction> {
        val hidden = getHiddenToolbarActions(prefs)
        return getToolbarOrder(prefs).filterNot { it in hidden }
    }

    /**
     * Direct Google App search intent (never targets Chrome):
     * 1) Intent(ACTION_WEB_SEARCH) targeted directly at com.google.android.googlequicksearchbox
     * 2) Fallback: Intent(ACTION_VIEW, https://www.google.com/search?q=...) targeted at Google App
     * 3) Last-resort device fallback if Google App is not installed
     */
    fun createGoogleSearchIntent(context: Context, query: String): Intent? {
        val clean = query.trim()
        if (clean.isEmpty()) return null
        val pm = context.packageManager

        val webSearchIntent = Intent(Intent.ACTION_WEB_SEARCH).apply {
            setPackage(GOOGLE_APP_PACKAGE)
            putExtra(SearchManager.QUERY, clean)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (webSearchIntent.resolveActivity(pm) != null) {
            return webSearchIntent
        }

        val encoded = runCatching { URLEncoder.encode(clean, "UTF-8") }.getOrDefault(clean)
        val googleViewUrl = "https://www.google.com/search?q=$encoded"
        val googleAppViewIntent = Intent(Intent.ACTION_VIEW, Uri.parse(googleViewUrl)).apply {
            setPackage(GOOGLE_APP_PACKAGE)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (googleAppViewIntent.resolveActivity(pm) != null) {
            return googleAppViewIntent
        }

        val genericWebSearch = Intent(Intent.ACTION_WEB_SEARCH).apply {
            putExtra(SearchManager.QUERY, clean)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (genericWebSearch.resolveActivity(pm) != null) {
            return genericWebSearch
        }

        return Intent(Intent.ACTION_VIEW, Uri.parse(googleViewUrl)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    fun launchGoogleSearch(context: Context, query: String): Boolean {
        val intent = createGoogleSearchIntent(context, query) ?: return false
        return try {
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            Log.w(TAG, "Direct Google App Search failed, trying web view fallback", e)
            val clean = query.trim()
            val encoded = runCatching { URLEncoder.encode(clean, "UTF-8") }.getOrDefault(clean)
            val fallback = Intent(
                Intent.ACTION_VIEW,
                Uri.parse("https://www.google.com/search?q=$encoded")
            ).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            runCatching {
                context.startActivity(fallback)
                true
            }.getOrElse {
                Toast.makeText(context, "Unable to open Google Search.", Toast.LENGTH_SHORT).show()
                false
            }
        }
    }

    /**
     * Sends BOTH a cropped image URI (EXTRA_STREAM) and typed question text (EXTRA_TEXT)
     * directly to Google App / Lens for multimodal visual search.
     */
    fun createGoogleSearchImageAndTextIntent(
        context: Context,
        croppedBitmap: Bitmap,
        question: String = "",
        fallbackText: String = ""
    ): Intent? {
        val uri = writeCutoutToCacheUri(context, croppedBitmap) ?: return null
        val pm = context.packageManager
        val cleanQuestion = question.trim()

        val candidates = listOf(
            GOOGLE_APP_PACKAGE,
            "com.google.googlelens",
            "com.google.ar.lens"
        )
        for (pkg in candidates) {
            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, uri)
                if (cleanQuestion.isNotEmpty()) {
                    putExtra(Intent.EXTRA_TEXT, cleanQuestion)
                }
                setPackage(pkg)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            if (sendIntent.resolveActivity(pm) != null) {
                return sendIntent
            }
        }

        val viewIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "image/png")
            if (cleanQuestion.isNotEmpty()) {
                putExtra(Intent.EXTRA_TEXT, cleanQuestion)
            }
            setPackage(GOOGLE_APP_PACKAGE)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        if (viewIntent.resolveActivity(pm) != null) {
            return viewIntent
        }

        val combined = combineQuestionForAiSearch(cleanQuestion, fallbackText)
        if (combined.isNotEmpty()) {
            return createGoogleSearchIntent(context, combined)
        }

        val fallbackSend = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            if (cleanQuestion.isNotEmpty()) {
                putExtra(Intent.EXTRA_TEXT, cleanQuestion)
            }
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(fallbackSend, "Visual Search").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    fun launchGoogleSearchImageAndText(
        context: Context,
        croppedBitmap: Bitmap,
        question: String = "",
        fallbackText: String = ""
    ): Boolean {
        val intent = createGoogleSearchImageAndTextIntent(context, croppedBitmap, question, fallbackText)
            ?: return false
        return runCatching {
            context.startActivity(intent)
            true
        }.getOrElse { e ->
            Log.e(TAG, "Failed launching multimodal image+question search", e)
            Toast.makeText(context, "Unable to search image with question.", Toast.LENGTH_SHORT).show()
            false
        }
    }

    /**
     * 1-tap Google Lens launcher targeting com.google.googlelens -> com.google.ar.lens ->
     * com.google.android.googlequicksearchbox with PNG EXTRA_STREAM.
     */
    fun createGoogleLensImageIntent(context: Context, bitmap: Bitmap): Intent? {
        val uri = writeCutoutToCacheUri(context, bitmap) ?: return null
        val pm = context.packageManager
        val lensPackages = listOf(
            "com.google.googlelens",
            "com.google.ar.lens",
            GOOGLE_APP_PACKAGE
        )
        for (pkg in lensPackages) {
            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, uri)
                setPackage(pkg)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            if (sendIntent.resolveActivity(pm) != null) {
                return sendIntent
            }
        }
        val genericSend = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        if (genericSend.resolveActivity(pm) != null) {
            return Intent.createChooser(genericSend, "Google Lens").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }
        return Intent(Intent.ACTION_VIEW, Uri.parse("https://lens.google.com/")).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    fun launchGoogleLensImage(context: Context, bitmap: Bitmap): Boolean {
        val intent = createGoogleLensImageIntent(context, bitmap) ?: return false
        return runCatching {
            context.startActivity(intent)
            true
        }.getOrElse { e ->
            Log.e(TAG, "Failed launching Google Lens", e)
            Toast.makeText(context, "Google Lens unavailable.", Toast.LENGTH_SHORT).show()
            false
        }
    }

    fun combineQuestionWithText(question: String, selectedText: String): String {
        val cleanQuestion = question.trim()
        val cleanText = selectedText.trim()
        return when {
            cleanQuestion.isEmpty() -> cleanText
            cleanText.isEmpty() -> cleanQuestion
            else -> "$cleanQuestion\n\n$cleanText"
        }
    }

    fun combineQuestionForAiSearch(question: String, selectedText: String): String {
        val cleanQuestion = question.trim()
        val cleanText = selectedText.trim()
        return when {
            cleanQuestion.isEmpty() -> cleanText
            cleanText.isEmpty() -> cleanQuestion
            else -> "$cleanQuestion $cleanText"
        }
    }

    fun createGoogleAiModeTextIntent(context: Context, query: String, question: String = ""): Intent? {
        val clean = combineQuestionForAiSearch(question, query)
        if (clean.isEmpty()) return null
        val pm = context.packageManager
        val encoded = runCatching { URLEncoder.encode(clean, "UTF-8") }.getOrDefault(clean)
        val aiModeUrl = "https://www.google.com/search?udm=50&q=$encoded"

        val googleAppAi = Intent(Intent.ACTION_VIEW, Uri.parse(aiModeUrl)).apply {
            setPackage(GOOGLE_APP_PACKAGE)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val googleInstalled = runCatching { pm.getLaunchIntentForPackage(GOOGLE_APP_PACKAGE) }.getOrNull() != null
        if (googleAppAi.resolveActivity(pm) != null || googleInstalled) {
            return googleAppAi
        }

        val searchLiteAi = Intent(Intent.ACTION_VIEW, Uri.parse(aiModeUrl)).apply {
            setPackage(SEARCH_LITE_PACKAGE)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val liteInstalled = runCatching { pm.getLaunchIntentForPackage(SEARCH_LITE_PACKAGE) }.getOrNull() != null
        if (searchLiteAi.resolveActivity(pm) != null || liteInstalled) {
            return searchLiteAi
        }

        return Intent(Intent.ACTION_VIEW, Uri.parse(aiModeUrl)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    fun launchGoogleAiModeText(context: Context, query: String, question: String = ""): Boolean {
        val clean = combineQuestionForAiSearch(question, query)
        if (clean.isEmpty()) return false
        val encoded = runCatching { URLEncoder.encode(clean, "UTF-8") }.getOrDefault(clean)
        val aiModeUrl = "https://www.google.com/search?udm=50&q=$encoded"
        val primary = createGoogleAiModeTextIntent(context, query, question)
        val candidates = listOfNotNull(
            primary,
            Intent(Intent.ACTION_VIEW, Uri.parse(aiModeUrl)).apply {
                setPackage(SEARCH_LITE_PACKAGE)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
            Intent(Intent.ACTION_WEB_SEARCH).apply {
                setPackage(GOOGLE_APP_PACKAGE)
                putExtra(SearchManager.QUERY, clean)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
            Intent(Intent.ACTION_VIEW, Uri.parse(aiModeUrl)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        ).distinctBy { "${it.action}|${it.`package`}|${it.dataString}" }

        for (candidate in candidates) {
            val ok = runCatching {
                context.startActivity(candidate)
                true
            }.getOrDefault(false)
            if (ok) return true
        }
        Log.e(TAG, "Failed opening Google AI Mode for '$clean'")
        Toast.makeText(context, "Unable to open Google AI Mode.", Toast.LENGTH_SHORT).show()
        return false
    }

    /**
     * Direct image (+ optional question) launch to Google AI Mode / Lens without any share chooser dialog.
     */
    fun createGoogleAiModeImageIntent(
        context: Context,
        croppedBitmap: Bitmap,
        question: String = "",
        fallbackText: String = ""
    ): Intent? {
        val uri = writeCutoutToCacheUri(context, croppedBitmap) ?: return null
        val pm = context.packageManager
        val cleanQuestion = question.trim()

        val lensSend = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            if (cleanQuestion.isNotEmpty()) {
                putExtra(Intent.EXTRA_TEXT, cleanQuestion)
            }
            setPackage(GOOGLE_APP_PACKAGE)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        if (lensSend.resolveActivity(pm) != null) {
            return lensSend
        }

        val lensView = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "image/png")
            if (cleanQuestion.isNotEmpty()) {
                putExtra(Intent.EXTRA_TEXT, cleanQuestion)
            }
            setPackage(GOOGLE_APP_PACKAGE)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        if (lensView.resolveActivity(pm) != null) {
            return lensView
        }

        val combinedText = combineQuestionForAiSearch(cleanQuestion, fallbackText)
        if (combinedText.isNotEmpty()) {
            return createGoogleAiModeTextIntent(context, combinedText)
        }

        return Intent(Intent.ACTION_VIEW, Uri.parse("https://lens.google.com/")).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    fun launchGoogleAiModeImage(
        context: Context,
        croppedBitmap: Bitmap,
        question: String = "",
        fallbackText: String = ""
    ): Boolean {
        val intent = createGoogleAiModeImageIntent(context, croppedBitmap, question, fallbackText)
            ?: return false
        return runCatching {
            context.startActivity(intent)
            true
        }.getOrElse { e ->
            Log.e(TAG, "Failed opening Google AI Mode for image", e)
            Toast.makeText(context, "Unable to send image to Google App.", Toast.LENGTH_SHORT).show()
            false
        }
    }

    fun createTargetAppTextIntent(
        context: Context,
        targetPackage: String,
        webFallbackBaseUrl: String,
        text: String
    ): Intent? {
        val clean = text.trim()
        if (clean.isEmpty()) return null
        val pm = context.packageManager

        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, clean)
            setPackage(targetPackage)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (sendIntent.resolveActivity(pm) != null) {
            return sendIntent
        }

        val launchIntent = runCatching { pm.getLaunchIntentForPackage(targetPackage) }.getOrNull()
        if (launchIntent != null) {
            launchIntent.putExtra(Intent.EXTRA_TEXT, clean)
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            return launchIntent
        }

        val encoded = runCatching { URLEncoder.encode(clean, "UTF-8") }.getOrDefault(clean)
        return Intent(Intent.ACTION_VIEW, Uri.parse("$webFallbackBaseUrl?q=$encoded")).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    fun launchGeminiText(context: Context, text: String, question: String = ""): Boolean {
        val payload = combineQuestionWithText(question, text)
        val intent = createTargetAppTextIntent(
            context = context,
            targetPackage = GEMINI_PACKAGE,
            webFallbackBaseUrl = "https://gemini.google.com/app",
            text = payload
        ) ?: return false
        return runCatching {
            context.startActivity(intent)
            true
        }.getOrElse { e ->
            Log.e(TAG, "Failed launching Gemini with text", e)
            Toast.makeText(context, "Unable to open Gemini.", Toast.LENGTH_SHORT).show()
            false
        }
    }

    fun launchChatGptText(context: Context, text: String, question: String = ""): Boolean {
        val payload = combineQuestionWithText(question, text)
        val intent = createTargetAppTextIntent(
            context = context,
            targetPackage = CHATGPT_PACKAGE,
            webFallbackBaseUrl = "https://chatgpt.com/",
            text = payload
        ) ?: return false
        return runCatching {
            context.startActivity(intent)
            true
        }.getOrElse { e ->
            Log.e(TAG, "Failed launching ChatGPT with text", e)
            Toast.makeText(context, "Unable to open ChatGPT.", Toast.LENGTH_SHORT).show()
            false
        }
    }

    fun createTargetAppImageIntent(
        context: Context,
        targetPackage: String,
        webFallbackUrl: String,
        croppedBitmap: Bitmap,
        question: String = ""
    ): Intent? {
        val uri = writeCutoutToCacheUri(context, croppedBitmap) ?: return null
        val pm = context.packageManager
        val cleanQuestion = question.trim()

        val sendImageIntent = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            if (cleanQuestion.isNotEmpty()) {
                putExtra(Intent.EXTRA_TEXT, cleanQuestion)
            }
            setPackage(targetPackage)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        if (sendImageIntent.resolveActivity(pm) != null) {
            return sendImageIntent
        }

        val launchIntent = runCatching { pm.getLaunchIntentForPackage(targetPackage) }.getOrNull()
        if (launchIntent != null) {
            if (cleanQuestion.isNotEmpty()) {
                launchIntent.putExtra(Intent.EXTRA_TEXT, cleanQuestion)
            }
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            return launchIntent
        }

        val finalUrl = if (cleanQuestion.isNotEmpty()) {
            val encoded = runCatching { URLEncoder.encode(cleanQuestion, "UTF-8") }.getOrDefault(cleanQuestion)
            val separator = if (webFallbackUrl.contains("?")) "&" else "?"
            "$webFallbackUrl${separator}q=$encoded"
        } else {
            webFallbackUrl
        }
        return Intent(Intent.ACTION_VIEW, Uri.parse(finalUrl)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    fun launchGeminiImage(context: Context, croppedBitmap: Bitmap, question: String = ""): Boolean {
        val intent = createTargetAppImageIntent(
            context = context,
            targetPackage = GEMINI_PACKAGE,
            webFallbackUrl = "https://gemini.google.com/app",
            croppedBitmap = croppedBitmap,
            question = question
        ) ?: return false
        return runCatching {
            context.startActivity(intent)
            true
        }.getOrElse { e ->
            Log.e(TAG, "Failed sending cutout image to Gemini", e)
            Toast.makeText(context, "Unable to send image to Gemini.", Toast.LENGTH_SHORT).show()
            false
        }
    }

    fun launchChatGptImage(context: Context, croppedBitmap: Bitmap, question: String = ""): Boolean {
        val intent = createTargetAppImageIntent(
            context = context,
            targetPackage = CHATGPT_PACKAGE,
            webFallbackUrl = "https://chatgpt.com/",
            croppedBitmap = croppedBitmap,
            question = question
        ) ?: return false
        return runCatching {
            context.startActivity(intent)
            true
        }.getOrElse { e ->
            Log.e(TAG, "Failed sending cutout image to ChatGPT", e)
            Toast.makeText(context, "Unable to send image to ChatGPT.", Toast.LENGTH_SHORT).show()
            false
        }
    }

    fun createShareTextIntent(text: String): Intent? {
        val clean = text.trim()
        if (clean.isEmpty()) return null
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, clean)
        }
        return Intent.createChooser(sendIntent, "Share Text").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    fun launchShareText(context: Context, text: String): Boolean {
        val intent = createShareTextIntent(text) ?: return false
        return runCatching {
            context.startActivity(intent)
            true
        }.getOrElse { e ->
            Log.e(TAG, "Failed opening share sheet for text", e)
            Toast.makeText(context, "Unable to share text.", Toast.LENGTH_SHORT).show()
            false
        }
    }

    fun createShareImageIntent(context: Context, croppedBitmap: Bitmap): Intent? {
        val uri = writeCutoutToCacheUri(context, croppedBitmap) ?: return null
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(sendIntent, "Share Cutout Image").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    fun launchShareImage(context: Context, croppedBitmap: Bitmap): Boolean {
        val intent = createShareImageIntent(context, croppedBitmap) ?: return false
        return runCatching {
            context.startActivity(intent)
            true
        }.getOrElse { e ->
            Log.e(TAG, "Failed opening share sheet for image", e)
            Toast.makeText(context, "Unable to share image.", Toast.LENGTH_SHORT).show()
            false
        }
    }

    fun writeCutoutToCacheUri(context: Context, bitmap: Bitmap): Uri? {
        if (bitmap.isRecycled) return null
        return try {
            val cutoutDir = File(context.cacheDir, "cutouts").apply { mkdirs() }
            val file = File(cutoutDir, "cutout_${System.currentTimeMillis()}.png")
            FileOutputStream(file).use { out ->
                val ok = bitmap.compress(Bitmap.CompressFormat.PNG, 95, out)
                if (!ok) return null
            }
            FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed writing cutout to cache URI", e)
            null
        }
    }
}
