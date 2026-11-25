package com.example

import android.Manifest
import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Environment
import android.speech.tts.TextToSpeech
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.example.database.NoteEntry
import com.example.database.SmartDotDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.Locale
import java.util.WeakHashMap

/**
 * Provides the dedicated self-contained floating mini-apps inside FloatingWindowManager:
 * 1. Floating Browser (Clutter-free WebView + compact URL pill + Left ☰ Menu for Back/Forward/Reload/Tabs/Bookmarks/History/Downloads/Copy-Paste/Desktop+Opacity)
 * 2. Floating Social & Web Apps (WhatsApp Web w/ Desktop Chrome UA, Instagram, Facebook, Telegram, Gmail)
 * 3. Floating YouTube (Full-bleed WebView + background playback + Left ☰ Menu)
 * 4. Floating Media & Files (File Browser + Image Viewer + Video Player + Audio Player)
 * 5. Floating Notes, Floating Calculator, Floating Translator, Floating File/Image Viewer, & Floating AI Web
 */
object FloatingMiniApps {

    data class BookmarkEntry(
        val title: String,
        val url: String
    )

    data class HistoryEntry(
        val title: String,
        val url: String,
        val timestamp: Long
    )

    data class DownloadEntry(
        val fileName: String,
        val url: String,
        val timestamp: Long
    )

    const val PREF_BROWSER_BOOKMARKS = "floating_browser_bookmarks"
    const val PREF_BROWSER_HISTORY = "floating_browser_history"
    const val PREF_BROWSER_DOWNLOADS = "floating_browser_downloads"

    private const val MOBILE_USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
    private const val DESKTOP_USER_AGENT =
        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    @Volatile
    internal var pendingBrowserUploadImageUri: Uri? = null

    private val browserNavigators = WeakHashMap<View, (String, Uri?) -> Unit>()

    // --- Bookmarks Persistence ---
    fun getBrowserBookmarks(context: Context): List<BookmarkEntry> {
        val prefs = context.getSharedPreferences(SelectionToolbarHelper.PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(PREF_BROWSER_BOOKMARKS, null).orEmpty()
        if (raw.isBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            val result = ArrayList<BookmarkEntry>(arr.length())
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val url = obj.optString("url").trim()
                if (url.isEmpty()) continue
                val title = obj.optString("title").trim().ifEmpty { url }
                result.add(BookmarkEntry(title = title, url = url))
            }
            result
        }.getOrElse { emptyList() }
    }

    private fun saveBrowserBookmarks(context: Context, entries: List<BookmarkEntry>) {
        val arr = JSONArray()
        entries.forEach { entry ->
            arr.put(JSONObject().apply {
                put("title", entry.title)
                put("url", entry.url)
            })
        }
        val prefs = context.getSharedPreferences(SelectionToolbarHelper.PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(PREF_BROWSER_BOOKMARKS, arr.toString()).apply()
    }

    fun isBookmarked(context: Context, url: String): Boolean {
        val cleanUrl = url.trim()
        if (cleanUrl.isEmpty()) return false
        return getBrowserBookmarks(context).any { it.url.equals(cleanUrl, ignoreCase = true) }
    }

    fun toggleBrowserBookmark(context: Context, title: String, url: String): Boolean {
        val cleanUrl = url.trim()
        if (cleanUrl.isEmpty()) return false
        val current = getBrowserBookmarks(context).toMutableList()
        val existingIdx = current.indexOfFirst { it.url.equals(cleanUrl, ignoreCase = true) }
        return if (existingIdx >= 0) {
            current.removeAt(existingIdx)
            saveBrowserBookmarks(context, current)
            false
        } else {
            val cleanTitle = title.trim().ifEmpty { cleanUrl }
            current.add(0, BookmarkEntry(title = cleanTitle, url = cleanUrl))
            saveBrowserBookmarks(context, current)
            true
        }
    }

    fun removeBrowserBookmark(context: Context, url: String) {
        val cleanUrl = url.trim()
        if (cleanUrl.isEmpty()) return
        val updated = getBrowserBookmarks(context).filterNot { it.url.equals(cleanUrl, ignoreCase = true) }
        saveBrowserBookmarks(context, updated)
    }

    // --- History Persistence ---
    fun getBrowserHistory(context: Context): List<HistoryEntry> {
        val prefs = context.getSharedPreferences(SelectionToolbarHelper.PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(PREF_BROWSER_HISTORY, null).orEmpty()
        if (raw.isBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            val result = ArrayList<HistoryEntry>(arr.length())
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val url = obj.optString("url").trim()
                if (url.isEmpty()) continue
                val title = obj.optString("title").trim().ifEmpty { url }
                val ts = obj.optLong("ts", 0L)
                result.add(HistoryEntry(title = title, url = url, timestamp = ts))
            }
            result
        }.getOrElse { emptyList() }
    }

    fun addBrowserHistory(context: Context, title: String, url: String) {
        val cleanUrl = url.trim()
        if (cleanUrl.isEmpty() || cleanUrl == "about:blank") return
        val current = getBrowserHistory(context).filterNot { it.url.equals(cleanUrl, ignoreCase = true) }.toMutableList()
        current.add(0, HistoryEntry(title = title.trim().ifEmpty { cleanUrl }, url = cleanUrl, timestamp = System.currentTimeMillis()))
        val trimmed = current.take(40)
        val arr = JSONArray()
        trimmed.forEach { entry ->
            arr.put(JSONObject().apply {
                put("title", entry.title)
                put("url", entry.url)
                put("ts", entry.timestamp)
            })
        }
        val prefs = context.getSharedPreferences(SelectionToolbarHelper.PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(PREF_BROWSER_HISTORY, arr.toString()).apply()
    }

    fun clearBrowserHistory(context: Context) {
        context.getSharedPreferences(SelectionToolbarHelper.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().remove(PREF_BROWSER_HISTORY).apply()
    }

    // --- Downloads Persistence ---
    fun getBrowserDownloads(context: Context): List<DownloadEntry> {
        val prefs = context.getSharedPreferences(SelectionToolbarHelper.PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(PREF_BROWSER_DOWNLOADS, null).orEmpty()
        if (raw.isBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            val result = ArrayList<DownloadEntry>(arr.length())
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val url = obj.optString("url").trim()
                if (url.isEmpty()) continue
                val fileName = obj.optString("name").trim().ifEmpty { "download" }
                val ts = obj.optLong("ts", 0L)
                result.add(DownloadEntry(fileName = fileName, url = url, timestamp = ts))
            }
            result
        }.getOrElse { emptyList() }
    }

    fun addBrowserDownload(context: Context, fileName: String, url: String) {
        val cleanUrl = url.trim()
        if (cleanUrl.isEmpty()) return
        val current = getBrowserDownloads(context).toMutableList()
        current.add(0, DownloadEntry(fileName = fileName.trim().ifEmpty { "file" }, url = cleanUrl, timestamp = System.currentTimeMillis()))
        val trimmed = current.take(30)
        val arr = JSONArray()
        trimmed.forEach { entry ->
            arr.put(JSONObject().apply {
                put("name", entry.fileName)
                put("url", entry.url)
                put("ts", entry.timestamp)
            })
        }
        val prefs = context.getSharedPreferences(SelectionToolbarHelper.PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(PREF_BROWSER_DOWNLOADS, arr.toString()).apply()
    }

    fun clearBrowserDownloads(context: Context) {
        context.getSharedPreferences(SelectionToolbarHelper.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().remove(PREF_BROWSER_DOWNLOADS).apply()
    }

    private class KeepAliveWebView(context: Context) : WebView(context) {
        override fun onWindowVisibilityChanged(visibility: Int) {
            // Keep WebView active even when minimized to bubble for background audio/playback
            super.onWindowVisibilityChanged(View.VISIBLE)
        }
    }

    private fun dp(context: Context, value: Float): Int =
        (value * context.resources.displayMetrics.density.coerceAtLeast(1f)).toInt()

    private fun normalizeBrowserInput(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return "https://www.google.com"
        if (trimmed.startsWith("http://", ignoreCase = true) ||
            trimmed.startsWith("https://", ignoreCase = true)
        ) {
            return trimmed
        }
        if (!trimmed.contains(' ') && trimmed.contains('.')) {
            return "https://$trimmed"
        }
        val encoded = runCatching { URLEncoder.encode(trimmed, "UTF-8") }.getOrDefault(trimmed)
        return "https://www.google.com/search?q=$encoded"
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebViewCommon(
        context: Context,
        webView: WebView,
        desktopMode: Boolean
    ) {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            @Suppress("DEPRECATION")
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            allowFileAccess = false
            allowContentAccess = true
            useWideViewPort = true
            loadWithOverviewMode = true
            builtInZoomControls = true
            displayZoomControls = false
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            userAgentString = if (desktopMode) DESKTOP_USER_AGENT else MOBILE_USER_AGENT
        }
        runCatching {
            val cm = CookieManager.getInstance()
            cm.setAcceptCookie(true)
            cm.setAcceptThirdPartyCookies(webView, true)
            cm.flush()
        }
        webView.setDownloadListener { url, _, contentDisposition, mimeType, _ ->
            val fileName = URLUtil.guessFileName(url, contentDisposition, mimeType)
            addBrowserDownload(context, fileName, url)
            runCatching {
                val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager
                val req = DownloadManager.Request(Uri.parse(url)).apply {
                    setTitle(fileName)
                    setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
                }
                dm?.enqueue(req)
            }
            Toast.makeText(context, "Downloading $fileName", Toast.LENGTH_SHORT).show()
        }
    }

    private fun grantWebPermissionsIfPermitted(context: Context, request: PermissionRequest) {
        val granted = ArrayList<String>()
        val hasCamera = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        val hasMic = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        request.resources.forEach { res ->
            when (res) {
                PermissionRequest.RESOURCE_VIDEO_CAPTURE -> if (hasCamera) granted.add(res)
                PermissionRequest.RESOURCE_AUDIO_CAPTURE -> if (hasMic) granted.add(res)
                PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID -> granted.add(res)
            }
        }
        if (granted.isNotEmpty()) {
            request.grant(granted.toTypedArray())
        } else {
            request.deny()
        }
    }

    private fun handleWebFileChooser(
        context: Context,
        filePathCallback: ValueCallback<Array<Uri>>?,
        fileChooserParams: WebChromeClient.FileChooserParams?
    ): Boolean {
        if (filePathCallback == null) return false
        val stagedUri = pendingBrowserUploadImageUri
        if (stagedUri != null) {
            pendingBrowserUploadImageUri = null
            filePathCallback.onReceiveValue(arrayOf(stagedUri))
            return true
        }
        ViewerPickerActivity.pendingWebFileCallback?.onReceiveValue(null)
        ViewerPickerActivity.pendingWebFileCallback = filePathCallback
        val acceptTypes = fileChooserParams?.acceptTypes
            ?.filter { it.isNotBlank() }
            ?.toTypedArray()
        val pickerIntent = Intent(context, ViewerPickerActivity::class.java).apply {
            putExtra(ViewerPickerActivity.EXTRA_PICK_MODE, ViewerPickerActivity.MODE_WEB_FILE_CHOOSER)
            if (acceptTypes != null && acceptTypes.isNotEmpty()) {
                putExtra(ViewerPickerActivity.EXTRA_ACCEPT_MIME_TYPES, acceptTypes)
            }
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return runCatching {
            context.startActivity(pickerIntent)
            true
        }.getOrElse {
            ViewerPickerActivity.pendingWebFileCallback?.onReceiveValue(null)
            ViewerPickerActivity.pendingWebFileCallback = null
            false
        }
    }

    /**
     * Opens the clean, clutter-free Floating Browser with Hamburger (☰) Menu drawer:
     * Back, Forward, Reload, + New Tab, ★ Bookmark, 📑 Bookmarks, 🕒 History, ⬇ Downloads,
     * 📋 Copy / Paste, Desktop/Mobile View toggle, and Window Opacity slider.
     */
    @SuppressLint("SetJavaScriptEnabled")
    fun openFloatingBrowser(
        context: Context,
        manager: FloatingWindowManager,
        accentColor: Int,
        initialUrl: String = "https://www.google.com",
        navigateIfOpen: Boolean = false,
        preloadImageUri: Uri? = null
    ): FloatingWindowManager.ManagedWindow? {
        if (preloadImageUri != null) {
            pendingBrowserUploadImageUri = preloadImageUri
        }
        val alreadyOpen = manager.hasWindow("floating_browser")
        val managedWin = manager.openOrFocusWindow(
            windowId = "floating_browser",
            title = context.getString(R.string.floating_window_title_browser),
            accentColor = accentColor,
            iconRes = R.drawable.ic_radial_chrome,
            requiresTextInput = true
        ) { managed ->
            val container = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(context, 4f), dp(context, 4f), dp(context, 4f), dp(context, 4f))
            }

            var isDesktopMode = false

            val webHost = FrameLayout(context).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    0,
                    1f
                )
            }
            val tabsStrip = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val tabsScroll = HorizontalScrollView(context).apply {
                isHorizontalScrollBarEnabled = false
                visibility = View.GONE // Hidden when only 1 tab so main view remains 100% spacious
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = dp(context, 4f)
                }
                addView(tabsStrip)
            }

            val etUrl = EditText(context).apply {
                tag = "fw_browser_url_input"
                hint = "Search Google or enter URL"
                setTextColor(Color.WHITE)
                setHintTextColor(Color.parseColor("#64748B"))
                textSize = 12f
                isSingleLine = true
                imeOptions = EditorInfo.IME_ACTION_GO
                setPadding(dp(context, 10f), dp(context, 6f), dp(context, 10f), dp(context, 6f))
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp(context, 10f).toFloat()
                    setColor(Color.parseColor("#1E293B"))
                }
                layoutParams = LinearLayout.LayoutParams(0, dp(context, 34f), 1f).apply {
                    marginEnd = dp(context, 4f)
                }
            }

            val btnGo = TextView(context).apply {
                tag = "fw_browser_go"
                text = "Go"
                textSize = 12f
                setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
                gravity = Gravity.CENTER
                setTextColor(Color.BLACK)
                minWidth = dp(context, 36f)
                minHeight = dp(context, 34f)
                setPadding(dp(context, 10f), 0, dp(context, 10f), 0)
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp(context, 10f).toFloat()
                    setColor(accentColor)
                }
            }

            val urlBar = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = dp(context, 4f)
                }
                addView(etUrl)
                addView(btnGo)
            }

            data class BrowserTab(var title: String, val webView: WebView, var lastUrl: String = "")
            val tabs = ArrayList<BrowserTab>()
            var activeTabIdx = 0

            // Reusable collapsible panel for Bookmarks, History, or Downloads
            fun buildOverlaySheet(sheetTag: String, titleText: String): Pair<LinearLayout, LinearLayout> {
                val listCol = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                }
                val scroll = ScrollView(context).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        dp(context, 170f)
                    )
                    addView(listCol)
                }
                val sheetHeader = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, 0, 0, dp(context, 4f))
                }
                sheetHeader.addView(TextView(context).apply {
                    text = titleText
                    textSize = 12f
                    setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
                    setTextColor(accentColor)
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                })
                val sheet = LinearLayout(context).apply {
                    tag = sheetTag
                    orientation = LinearLayout.VERTICAL
                    visibility = View.GONE
                    setPadding(dp(context, 8f), dp(context, 6f), dp(context, 8f), dp(context, 6f))
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        cornerRadius = dp(context, 10f).toFloat()
                        setColor(Color.parseColor("#0F172A"))
                        setStroke(1, accentColor)
                    }
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        bottomMargin = dp(context, 4f)
                    }
                }
                sheetHeader.addView(TextView(context).apply {
                    text = "✕"
                    textSize = 13f
                    setTextColor(Color.parseColor("#94A3B8"))
                    setPadding(dp(context, 8f), dp(context, 2f), dp(context, 4f), dp(context, 2f))
                    setOnClickListener { sheet.visibility = View.GONE }
                })
                sheet.addView(sheetHeader)
                sheet.addView(scroll)
                return sheet to listCol
            }

            val (bookmarksSheet, bookmarksListContainer) = buildOverlaySheet("fw_browser_bookmarks_sheet", "★ Bookmarks")
            val (historySheet, historyListContainer) = buildOverlaySheet("fw_browser_history_sheet", "🕒 History")
            val (downloadsSheet, downloadsListContainer) = buildOverlaySheet("fw_browser_downloads_sheet", "⬇ Downloads")

            fun hideAllSheets() {
                bookmarksSheet.visibility = View.GONE
                historySheet.visibility = View.GONE
                downloadsSheet.visibility = View.GONE
            }

            fun activeCurrentUrl(): String {
                val activeTab = tabs.getOrNull(activeTabIdx)
                val wvUrl = activeTab?.webView?.url?.takeIf { it.isNotBlank() }
                val rememberedUrl = activeTab?.lastUrl?.takeIf { it.isNotBlank() }
                val fieldUrl = etUrl.text?.toString()?.trim()?.takeIf { it.isNotBlank() }
                return wvUrl ?: rememberedUrl ?: fieldUrl ?: ""
            }

            lateinit var refreshMenuItems: () -> Unit

            fun loadUrlInActiveTab(rawUrl: String) {
                val dest = normalizeBrowserInput(rawUrl)
                val activeTab = tabs.getOrNull(activeTabIdx) ?: return
                activeTab.lastUrl = dest
                etUrl.setText(dest)
                activeTab.webView.loadUrl(dest)
                addBrowserHistory(context, activeTab.title, dest)
                refreshMenuItems()
            }

            fun renderBookmarksSheet() {
                bookmarksListContainer.removeAllViews()
                val items = getBrowserBookmarks(context)
                if (items.isEmpty()) {
                    bookmarksListContainer.addView(TextView(context).apply {
                        tag = "fw_browser_bookmarks_empty"
                        text = context.getString(R.string.bookmarks_empty)
                        textSize = 11.5f
                        setTextColor(Color.parseColor("#94A3B8"))
                        setPadding(dp(context, 6f), dp(context, 8f), dp(context, 6f), dp(context, 8f))
                    })
                    return
                }
                items.forEachIndexed { idx, entry ->
                    val row = LinearLayout(context).apply {
                        tag = "fw_browser_bookmark_row_$idx"
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(dp(context, 6f), dp(context, 6f), dp(context, 4f), dp(context, 6f))
                        background = GradientDrawable().apply {
                            cornerRadius = dp(context, 8f).toFloat()
                            setColor(Color.parseColor("#1E293B"))
                        }
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        ).apply {
                            bottomMargin = dp(context, 4f)
                        }
                        setOnClickListener {
                            loadUrlInActiveTab(entry.url)
                            bookmarksSheet.visibility = View.GONE
                        }
                    }
                    val textCol = LinearLayout(context).apply {
                        orientation = LinearLayout.VERTICAL
                        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    }
                    textCol.addView(TextView(context).apply {
                        text = entry.title
                        textSize = 11.5f
                        setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
                        setTextColor(Color.WHITE)
                        maxLines = 1
                        ellipsize = TextUtils.TruncateAt.END
                    })
                    textCol.addView(TextView(context).apply {
                        text = entry.url
                        textSize = 10f
                        setTextColor(Color.parseColor("#94A3B8"))
                        maxLines = 1
                        ellipsize = TextUtils.TruncateAt.END
                    })
                    val btnDel = TextView(context).apply {
                        tag = "fw_browser_bookmark_delete_$idx"
                        text = "✕"
                        textSize = 12f
                        gravity = Gravity.CENTER
                        setTextColor(Color.parseColor("#EF4444"))
                        minWidth = dp(context, 32f)
                        minHeight = dp(context, 32f)
                        contentDescription = "Delete bookmark ${entry.title}"
                        setOnClickListener {
                            removeBrowserBookmark(context, entry.url)
                            refreshMenuItems()
                            renderBookmarksSheet()
                        }
                    }
                    row.addView(textCol)
                    row.addView(btnDel)
                    bookmarksListContainer.addView(row)
                }
            }

            fun renderHistorySheet() {
                historyListContainer.removeAllViews()
                val items = getBrowserHistory(context)
                if (items.isEmpty()) {
                    historyListContainer.addView(TextView(context).apply {
                        tag = "fw_browser_history_empty"
                        text = "No browsing history yet."
                        textSize = 11.5f
                        setTextColor(Color.parseColor("#94A3B8"))
                        setPadding(dp(context, 6f), dp(context, 8f), dp(context, 6f), dp(context, 8f))
                    })
                    return
                }
                items.forEachIndexed { idx, entry ->
                    val row = LinearLayout(context).apply {
                        tag = "fw_browser_history_row_$idx"
                        orientation = LinearLayout.VERTICAL
                        setPadding(dp(context, 8f), dp(context, 6f), dp(context, 8f), dp(context, 6f))
                        background = GradientDrawable().apply {
                            cornerRadius = dp(context, 8f).toFloat()
                            setColor(Color.parseColor("#1E293B"))
                        }
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        ).apply {
                            bottomMargin = dp(context, 4f)
                        }
                        setOnClickListener {
                            loadUrlInActiveTab(entry.url)
                            historySheet.visibility = View.GONE
                        }
                    }
                    row.addView(TextView(context).apply {
                        text = entry.title
                        textSize = 11.5f
                        setTextColor(Color.WHITE)
                        maxLines = 1
                        ellipsize = TextUtils.TruncateAt.END
                    })
                    row.addView(TextView(context).apply {
                        text = entry.url
                        textSize = 10f
                        setTextColor(Color.parseColor("#94A3B8"))
                        maxLines = 1
                        ellipsize = TextUtils.TruncateAt.END
                    })
                    historyListContainer.addView(row)
                }
            }

            fun renderDownloadsSheet() {
                downloadsListContainer.removeAllViews()
                val items = getBrowserDownloads(context)
                if (items.isEmpty()) {
                    downloadsListContainer.addView(TextView(context).apply {
                        tag = "fw_browser_downloads_empty"
                        text = "No downloads recorded yet."
                        textSize = 11.5f
                        setTextColor(Color.parseColor("#94A3B8"))
                        setPadding(dp(context, 6f), dp(context, 8f), dp(context, 6f), dp(context, 8f))
                    })
                    return
                }
                items.forEachIndexed { idx, entry ->
                    val row = LinearLayout(context).apply {
                        tag = "fw_browser_download_row_$idx"
                        orientation = LinearLayout.VERTICAL
                        setPadding(dp(context, 8f), dp(context, 6f), dp(context, 8f), dp(context, 6f))
                        background = GradientDrawable().apply {
                            cornerRadius = dp(context, 8f).toFloat()
                            setColor(Color.parseColor("#1E293B"))
                        }
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        ).apply {
                            bottomMargin = dp(context, 4f)
                        }
                    }
                    row.addView(TextView(context).apply {
                        text = "⬇ ${entry.fileName}"
                        textSize = 11.5f
                        setTextColor(Color.WHITE)
                        maxLines = 1
                        ellipsize = TextUtils.TruncateAt.END
                    })
                    row.addView(TextView(context).apply {
                        text = entry.url
                        textSize = 10f
                        setTextColor(Color.parseColor("#94A3B8"))
                        maxLines = 1
                        ellipsize = TextUtils.TruncateAt.END
                    })
                    downloadsListContainer.addView(row)
                }
            }

            lateinit var renderTabsStrip: () -> Unit

            fun openNewBrowserTab(url: String = "https://www.google.com") {
                val newIdx = tabs.size
                val wv = createBrowserWebView(
                    context = context,
                    etUrl = etUrl,
                    desktopMode = isDesktopMode,
                    onTitleChanged = { title ->
                        if (newIdx < tabs.size) {
                            tabs[newIdx].title = title.ifBlank { "Tab ${newIdx + 1}" }
                            renderTabsStrip()
                        }
                    },
                    onUrlFinished = { finishedUrl ->
                        if (newIdx < tabs.size) {
                            tabs[newIdx].lastUrl = finishedUrl
                            addBrowserHistory(context, tabs[newIdx].title, finishedUrl)
                        }
                        if (newIdx == activeTabIdx) refreshMenuItems()
                    }
                )
                tabs.add(BrowserTab("Tab ${newIdx + 1}", wv, url))
                webHost.addView(
                    wv,
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                    )
                )
                activeTabIdx = newIdx
                tabs.forEachIndexed { j, t ->
                    t.webView.visibility = if (j == activeTabIdx) View.VISIBLE else View.GONE
                }
                wv.loadUrl(url)
                etUrl.setText(url)
                tabsScroll.visibility = if (tabs.size > 1) View.VISIBLE else View.GONE
                renderTabsStrip()
                refreshMenuItems()
            }

            renderTabsStrip = {
                tabsScroll.visibility = if (tabs.size > 1) View.VISIBLE else View.GONE
                tabsStrip.removeAllViews()
                tabs.forEachIndexed { idx, tab ->
                    val pill = TextView(context).apply {
                        tag = "fw_browser_tab_$idx"
                        text = tab.title.take(12)
                        textSize = 10.5f
                        maxLines = 1
                        setPadding(dp(context, 8f), dp(context, 4f), dp(context, 8f), dp(context, 4f))
                        setTextColor(if (idx == activeTabIdx) Color.BLACK else Color.WHITE)
                        background = GradientDrawable().apply {
                            shape = GradientDrawable.RECTANGLE
                            cornerRadius = dp(context, 10f).toFloat()
                            setColor(if (idx == activeTabIdx) accentColor else Color.parseColor("#1E293B"))
                        }
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        ).apply {
                            marginEnd = dp(context, 4f)
                        }
                        setOnClickListener {
                            activeTabIdx = idx
                            tabs.forEachIndexed { j, t ->
                                t.webView.visibility = if (j == activeTabIdx) View.VISIBLE else View.GONE
                            }
                            etUrl.setText(tabs[activeTabIdx].webView.url ?: tabs[activeTabIdx].lastUrl)
                            refreshMenuItems()
                            renderTabsStrip()
                        }
                    }
                    tabsStrip.addView(pill)
                }
                val btnNewTabPill = TextView(context).apply {
                    tag = "fw_browser_new_tab_pill"
                    text = "+ Tab"
                    textSize = 10.5f
                    setTextColor(accentColor)
                    setPadding(dp(context, 8f), dp(context, 4f), dp(context, 8f), dp(context, 4f))
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        cornerRadius = dp(context, 10f).toFloat()
                        setColor(Color.parseColor("#1E293B"))
                        setStroke(1, accentColor)
                    }
                    setOnClickListener { openNewBrowserTab() }
                }
                tabsStrip.addView(btnNewTabPill)
            }

            var browserMenuInitialized = false
            refreshMenuItems = {
                val isBookmarkedNow = isBookmarked(context, activeCurrentUrl())
                val starLabel = if (isBookmarkedNow) "★ Bookmarked" else "☆ Bookmark Page"
                val starColor = if (isBookmarkedNow) Color.parseColor("#FBBF24") else Color.parseColor("#F8FAFC")
                val modeLabel = if (isDesktopMode) "📱 Mobile View" else "🖥 Desktop View"
                if (browserMenuInitialized) {
                    managed.updateMenuItemLabel("fw_browser_bookmark_star", starLabel, starColor)
                    managed.updateMenuItemLabel("fw_browser_mode_toggle", modeLabel, accentColor)
                } else {
                    browserMenuInitialized = true
                    managed.setMenuItems(
                        listOf(
                            FloatingWindowManager.WindowMenuItem("back", "← Go back", "fw_browser_back") {
                                val wv = tabs.getOrNull(activeTabIdx)?.webView
                                if (wv?.canGoBack() == true) wv.goBack()
                            },
                            FloatingWindowManager.WindowMenuItem("forward", "→ Go forward", "fw_browser_forward") {
                                val wv = tabs.getOrNull(activeTabIdx)?.webView
                                if (wv?.canGoForward() == true) wv.goForward()
                            },
                            FloatingWindowManager.WindowMenuItem("reload", "↻ Reload", "fw_browser_reload") {
                                tabs.getOrNull(activeTabIdx)?.webView?.reload()
                            },
                            FloatingWindowManager.WindowMenuItem("new_tab", "+ New tab", "fw_browser_new_tab") {
                                openNewBrowserTab()
                            },
                            FloatingWindowManager.WindowMenuItem(
                                id = "bookmark_star",
                                label = starLabel,
                                tag = "fw_browser_bookmark_star",
                                closeOnTap = false,
                                textColor = starColor
                            ) {
                                val activeTab = tabs.getOrNull(activeTabIdx)
                                val url = activeCurrentUrl()
                                if (url.isNotBlank()) {
                                    val pageTitle = activeTab?.webView?.title?.takeIf { it.isNotBlank() }
                                        ?: activeTab?.title?.takeIf { it.isNotBlank() }
                                        ?: url
                                    val added = toggleBrowserBookmark(context, pageTitle, url)
                                    refreshMenuItems()
                                    if (bookmarksSheet.visibility == View.VISIBLE) renderBookmarksSheet()
                                    val msgRes = if (added) R.string.bookmark_added else R.string.bookmark_removed
                                    Toast.makeText(context, context.getString(msgRes), Toast.LENGTH_SHORT).show()
                                }
                            },
                            FloatingWindowManager.WindowMenuItem("bookmarks", "★ Bookmarks", "fw_browser_bookmarks_list") {
                                if (bookmarksSheet.visibility == View.VISIBLE) {
                                    bookmarksSheet.visibility = View.GONE
                                } else {
                                    hideAllSheets()
                                    renderBookmarksSheet()
                                    bookmarksSheet.visibility = View.VISIBLE
                                }
                            },
                            FloatingWindowManager.WindowMenuItem("history", "🕒 History", "fw_browser_history_btn") {
                                if (historySheet.visibility == View.VISIBLE) {
                                    historySheet.visibility = View.GONE
                                } else {
                                    hideAllSheets()
                                    renderHistorySheet()
                                    historySheet.visibility = View.VISIBLE
                                }
                            },
                            FloatingWindowManager.WindowMenuItem("downloads", "⬇ Downloads", "fw_browser_downloads_btn") {
                                if (downloadsSheet.visibility == View.VISIBLE) {
                                    downloadsSheet.visibility = View.GONE
                                } else {
                                    hideAllSheets()
                                    renderDownloadsSheet()
                                    downloadsSheet.visibility = View.VISIBLE
                                }
                            },
                            FloatingWindowManager.WindowMenuItem("copy_url", "📋 Copy URL", "fw_browser_copy_url") {
                                val url = activeCurrentUrl()
                                if (url.isNotBlank()) {
                                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    cm.setPrimaryClip(ClipData.newPlainText("URL", url))
                                    Toast.makeText(context, "URL copied", Toast.LENGTH_SHORT).show()
                                }
                            },
                            FloatingWindowManager.WindowMenuItem("paste_go", "📋 Paste & Go", "fw_browser_paste_go") {
                                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                val clipText = cm.primaryClip?.getItemAt(0)?.text?.toString()?.trim().orEmpty()
                                if (clipText.isNotEmpty()) {
                                    loadUrlInActiveTab(clipText)
                                } else {
                                    Toast.makeText(context, "Clipboard is empty", Toast.LENGTH_SHORT).show()
                                }
                            },
                            FloatingWindowManager.WindowMenuItem(
                                id = "desktop_mode",
                                label = modeLabel,
                                tag = "fw_browser_mode_toggle",
                                textColor = accentColor
                            ) {
                                isDesktopMode = !isDesktopMode
                                tabs.forEach { tab ->
                                    tab.webView.settings.userAgentString = if (isDesktopMode) DESKTOP_USER_AGENT else MOBILE_USER_AGENT
                                }
                                tabs.getOrNull(activeTabIdx)?.webView?.reload()
                                refreshMenuItems()
                            }
                        )
                    )
                }
            }

            btnGo.setOnClickListener { loadUrlInActiveTab(etUrl.text?.toString().orEmpty()) }
            etUrl.setOnEditorActionListener { _, actionId, event ->
                if (actionId == EditorInfo.IME_ACTION_GO ||
                    actionId == EditorInfo.IME_ACTION_DONE ||
                    (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
                ) {
                    loadUrlInActiveTab(etUrl.text?.toString().orEmpty())
                    true
                } else {
                    false
                }
            }

            // Initial Tab
            val startUrl = normalizeBrowserInput(initialUrl)
            val firstWebView = createBrowserWebView(
                context = context,
                etUrl = etUrl,
                desktopMode = isDesktopMode,
                onTitleChanged = { title ->
                    if (tabs.isNotEmpty()) {
                        tabs[0].title = title.ifBlank { "Google" }
                        renderTabsStrip()
                    }
                },
                onUrlFinished = { finishedUrl ->
                    if (tabs.isNotEmpty()) {
                        tabs[0].lastUrl = finishedUrl
                        addBrowserHistory(context, tabs[0].title, finishedUrl)
                    }
                    if (activeTabIdx == 0) refreshMenuItems()
                }
            )
            tabs.add(BrowserTab("Tab 1", firstWebView, startUrl))
            webHost.addView(
                firstWebView,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            )
            firstWebView.loadUrl(startUrl)
            etUrl.setText(startUrl)
            addBrowserHistory(context, "Tab 1", startUrl)
            renderTabsStrip()
            refreshMenuItems()

            browserNavigators[managed.rootView] = { nextUrl, imgUri ->
                if (imgUri != null) {
                    pendingBrowserUploadImageUri = imgUri
                }
                hideAllSheets()
                loadUrlInActiveTab(nextUrl)
            }

            container.addView(urlBar)
            container.addView(tabsScroll)
            container.addView(bookmarksSheet)
            container.addView(historySheet)
            container.addView(downloadsSheet)
            container.addView(webHost)

            val cleanup: () -> Unit = {
                browserNavigators.remove(managed.rootView)
                pendingBrowserUploadImageUri = null
                tabs.forEach { tab ->
                    runCatching {
                        tab.webView.stopLoading()
                        tab.webView.destroy()
                    }
                }
                tabs.clear()
            }
            container to cleanup
        }

        if (alreadyOpen && navigateIfOpen && managedWin != null) {
            browserNavigators[managedWin.rootView]?.invoke(normalizeBrowserInput(initialUrl), preloadImageUri)
        }
        return managedWin
    }

    /**
     * Opens popular social & communication apps (WhatsApp, Instagram, Facebook, Telegram, Gmail)
     * directly inside SmartDot's own resizable Floating WebView window with persistent cookies/session,
     * file-upload & camera/mic support, clutter-free full-bleed center WebView, and Left ☰ Menu!
     */
    @SuppressLint("SetJavaScriptEnabled")
    fun openFloatingSocialApp(
        context: Context,
        manager: FloatingWindowManager,
        accentColor: Int,
        appId: String
    ): FloatingWindowManager.ManagedWindow? {
        data class SocialSpec(
            val windowId: String,
            val title: String,
            val url: String,
            val iconRes: Int,
            val defaultDesktop: Boolean
        )
        val spec = when (appId) {
            "whatsapp" -> SocialSpec(
                windowId = "floating_whatsapp",
                title = context.getString(R.string.floating_window_title_whatsapp),
                url = "https://web.whatsapp.com",
                iconRes = R.drawable.ic_radial_whatsapp,
                defaultDesktop = true // Desktop Chrome UA required for WhatsApp Web login & session
            )
            "instagram" -> SocialSpec(
                windowId = "floating_instagram",
                title = context.getString(R.string.floating_window_title_instagram),
                url = "https://www.instagram.com",
                iconRes = R.drawable.ic_floating_window,
                defaultDesktop = false
            )
            "facebook" -> SocialSpec(
                windowId = "floating_facebook",
                title = context.getString(R.string.floating_window_title_facebook),
                url = "https://m.facebook.com",
                iconRes = R.drawable.ic_floating_window,
                defaultDesktop = false
            )
            "telegram" -> SocialSpec(
                windowId = "floating_telegram",
                title = context.getString(R.string.floating_window_title_telegram),
                url = "https://web.telegram.org",
                iconRes = R.drawable.ic_floating_window,
                defaultDesktop = false
            )
            else -> SocialSpec(
                windowId = "floating_gmail",
                title = context.getString(R.string.floating_window_title_gmail),
                url = "https://mail.google.com",
                iconRes = android.R.drawable.ic_dialog_email,
                defaultDesktop = false
            )
        }

        return manager.openOrFocusWindow(
            windowId = spec.windowId,
            title = spec.title,
            accentColor = accentColor,
            iconRes = spec.iconRes,
            requiresTextInput = true
        ) { managed ->
            var isDesktopMode = spec.defaultDesktop
            var lastKnownUrl = spec.url

            val webView = KeepAliveWebView(context).apply {
                tag = "fw_social_webview_$appId"
                configureWebViewCommon(context, this, desktopMode = isDesktopMode)
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        if (!url.isNullOrBlank()) {
                            lastKnownUrl = url
                            addBrowserHistory(context, view?.title ?: spec.title, url)
                        }
                        runCatching { CookieManager.getInstance().flush() }
                    }
                }
                webChromeClient = object : WebChromeClient() {
                    override fun onShowFileChooser(
                        webView: WebView?,
                        filePathCallback: ValueCallback<Array<Uri>>?,
                        fileChooserParams: FileChooserParams?
                    ): Boolean = handleWebFileChooser(context, filePathCallback, fileChooserParams)

                    override fun onPermissionRequest(request: PermissionRequest?) {
                        if (request != null) grantWebPermissionsIfPermitted(context, request)
                    }
                }
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            }

            lateinit var refreshSocialMenu: () -> Unit
            var socialMenuInitialized = false
            refreshSocialMenu = {
                val bookmarked = isBookmarked(context, lastKnownUrl)
                val starLabel = if (bookmarked) "★ Bookmarked" else "☆ Bookmark Page"
                val modeLabel = if (isDesktopMode) "📱 Mobile View" else "🖥 Desktop View"
                if (socialMenuInitialized) {
                    managed.updateMenuItemLabel("fw_social_bookmark_$appId", starLabel)
                    managed.updateMenuItemLabel("fw_social_mode_toggle_$appId", modeLabel, accentColor)
                } else {
                    socialMenuInitialized = true
                    managed.setMenuItems(
                        listOf(
                            FloatingWindowManager.WindowMenuItem("back", "← Go back", "fw_social_back_$appId") {
                                if (webView.canGoBack()) webView.goBack()
                            },
                            FloatingWindowManager.WindowMenuItem("forward", "→ Go forward", "fw_social_forward_$appId") {
                                if (webView.canGoForward()) webView.goForward()
                            },
                            FloatingWindowManager.WindowMenuItem("reload", "↻ Reload", "fw_social_reload_$appId") {
                                webView.reload()
                            },
                            FloatingWindowManager.WindowMenuItem(
                                id = "bookmark",
                                label = starLabel,
                                tag = "fw_social_bookmark_$appId",
                                closeOnTap = false
                            ) {
                                val title = webView.title?.takeIf { it.isNotBlank() } ?: spec.title
                                toggleBrowserBookmark(context, title, lastKnownUrl)
                                refreshSocialMenu()
                            },
                            FloatingWindowManager.WindowMenuItem("copy_url", "📋 Copy URL", "fw_social_copy_url_$appId") {
                                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                cm.setPrimaryClip(ClipData.newPlainText("URL", webView.url ?: lastKnownUrl))
                                Toast.makeText(context, "URL copied", Toast.LENGTH_SHORT).show()
                            },
                            FloatingWindowManager.WindowMenuItem(
                                id = "desktop_mode",
                                label = modeLabel,
                                tag = "fw_social_mode_toggle_$appId",
                                textColor = accentColor
                            ) {
                                isDesktopMode = !isDesktopMode
                                webView.settings.userAgentString = if (isDesktopMode) DESKTOP_USER_AGENT else MOBILE_USER_AGENT
                                webView.reload()
                                refreshSocialMenu()
                            }
                        )
                    )
                }
            }
            refreshSocialMenu()

            webView.loadUrl(spec.url)

            val cleanup: () -> Unit = {
                ViewerPickerActivity.pendingWebFileCallback?.onReceiveValue(null)
                ViewerPickerActivity.pendingWebFileCallback = null
                runCatching { CookieManager.getInstance().flush() }
                runCatching {
                    webView.stopLoading()
                    webView.destroy()
                }
            }
            webView to cleanup
        }
    }

    fun openFloatingMediaFiles(
        context: Context,
        manager: FloatingWindowManager,
        accentColor: Int
    ): FloatingWindowManager.ManagedWindow? {
        return FloatingMediaFilesHelper.openFloatingMediaFiles(context, manager, accentColor)
    }

    fun openGoogleAiModeInBrowser(
        context: Context,
        manager: FloatingWindowManager,
        accentColor: Int,
        query: String,
        question: String = "",
        cutoutBitmap: Bitmap? = null
    ): FloatingWindowManager.ManagedWindow? {
        val cleanQuestion = question.trim()
        val imageUri = cutoutBitmap?.let { bmp ->
            SelectionToolbarHelper.writeCutoutToCacheUri(context, bmp, fileName = "cutout.png")
        }
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        if (imageUri != null) {
            runCatching {
                cm?.setPrimaryClip(ClipData.newUri(context.contentResolver, "Cutout Image", imageUri))
            }
        } else {
            val combinedClipboard = SelectionToolbarHelper.combineQuestionWithText(cleanQuestion, query)
            if (combinedClipboard.isNotEmpty()) {
                runCatching {
                    cm?.setPrimaryClip(ClipData.newPlainText("SmartDot AI Query", combinedClipboard))
                }
            }
        }
        val effectiveQuery = if (imageUri != null) "" else query
        val url = SelectionToolbarHelper.buildGoogleAiModeUrl(effectiveQuery, cleanQuestion)
        return openFloatingBrowser(
            context = context,
            manager = manager,
            accentColor = accentColor,
            initialUrl = url,
            navigateIfOpen = true,
            preloadImageUri = imageUri
        )
    }

    fun openChatGptInBrowser(
        context: Context,
        manager: FloatingWindowManager,
        accentColor: Int,
        text: String,
        question: String = "",
        cutoutBitmap: Bitmap? = null
    ): FloatingWindowManager.ManagedWindow? {
        val cleanQuestion = question.trim()
        val imageUri = cutoutBitmap?.let { bmp ->
            SelectionToolbarHelper.writeCutoutToCacheUri(context, bmp, fileName = "cutout.png")
        }
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        if (imageUri != null) {
            runCatching {
                cm?.setPrimaryClip(ClipData.newUri(context.contentResolver, "Cutout Image", imageUri))
            }
        } else {
            val combinedClipboard = SelectionToolbarHelper.combineQuestionWithText(cleanQuestion, text)
            if (combinedClipboard.isNotEmpty()) {
                runCatching {
                    cm?.setPrimaryClip(ClipData.newPlainText("SmartDot AI Query", combinedClipboard))
                }
            }
        }
        val effectiveText = if (imageUri != null) "" else text
        val url = SelectionToolbarHelper.buildChatGptUrl(effectiveText, cleanQuestion)
        return openFloatingBrowser(
            context = context,
            manager = manager,
            accentColor = accentColor,
            initialUrl = url,
            navigateIfOpen = true,
            preloadImageUri = imageUri
        )
    }

    internal fun getAutoUploadTriggerScript(): String = """
        (function() {
            if (window.__smartDotUploadTriggered) return 'already_triggered';
            var inputs = Array.from(document.querySelectorAll('input[type="file"]'));
            var imgInput = inputs.find(function(i) {
                var acc = (i.getAttribute('accept') || '').toLowerCase();
                return acc.indexOf('image') !== -1 || acc === '' || acc.indexOf('*') !== -1;
            }) || inputs[0];
            if (imgInput) {
                window.__smartDotUploadTriggered = true;
                imgInput.click();
                return 'clicked_input';
            }
            var triggers = document.querySelectorAll(
                'button[aria-label*="image" i], button[aria-label*="upload" i], button[aria-label*="photo" i], button[aria-label*="attach" i], [data-testid*="attach" i], [data-testid*="upload" i]'
            );
            if (triggers.length > 0) {
                window.__smartDotUploadTriggered = true;
                triggers[0].click();
                setTimeout(function() {
                    var laterInputs = document.querySelectorAll('input[type="file"]');
                    if (laterInputs.length > 0) {
                        laterInputs[0].click();
                    }
                }, 350);
                return 'clicked_trigger';
            }
            return 'none';
        })();
    """.trimIndent()

    @SuppressLint("SetJavaScriptEnabled")
    private fun createBrowserWebView(
        context: Context,
        etUrl: EditText,
        desktopMode: Boolean = false,
        onTitleChanged: (String) -> Unit,
        onUrlFinished: (String) -> Unit = {}
    ): WebView {
        return WebView(context).apply {
            configureWebViewCommon(context, this, desktopMode = desktopMode)
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    if (!url.isNullOrBlank()) {
                        etUrl.setText(url)
                        onUrlFinished(url)
                    }
                    onTitleChanged(view?.title.orEmpty())
                    runCatching { CookieManager.getInstance().flush() }
                    if (pendingBrowserUploadImageUri != null && view != null) {
                        val js = getAutoUploadTriggerScript()
                        view.evaluateJavascript(js, null)
                        view.postDelayed({
                            if (pendingBrowserUploadImageUri != null) {
                                view.evaluateJavascript(js, null)
                            }
                        }, 600L)
                        view.postDelayed({
                            if (pendingBrowserUploadImageUri != null) {
                                view.evaluateJavascript(js, null)
                            }
                        }, 1500L)
                    }
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onReceivedTitle(view: WebView?, title: String?) {
                    if (!title.isNullOrBlank()) onTitleChanged(title)
                }

                override fun onPermissionRequest(request: PermissionRequest?) {
                    if (request != null) grantWebPermissionsIfPermitted(context, request)
                }

                override fun onShowFileChooser(
                    webView: WebView?,
                    filePathCallback: ValueCallback<Array<Uri>>?,
                    fileChooserParams: FileChooserParams?
                ): Boolean = handleWebFileChooser(context, filePathCallback, fileChooserParams)
            }
        }
    }

    /**
     * Clean clutter-free Floating YouTube with full-bleed WebView, background playback when minimized,
     * and Left Hamburger (☰) Menu for Back, Forward, Reload, Desktop/Mobile View, and Opacity Slider.
     */
    @SuppressLint("SetJavaScriptEnabled")
    fun openFloatingYouTube(
        context: Context,
        manager: FloatingWindowManager,
        accentColor: Int
    ): FloatingWindowManager.ManagedWindow? {
        return manager.openOrFocusWindow(
            windowId = "floating_youtube",
            title = context.getString(R.string.floating_window_title_youtube),
            accentColor = accentColor,
            iconRes = android.R.drawable.ic_menu_slideshow,
            requiresTextInput = true
        ) { managed ->
            var isDesktopMode = false
            val webView = KeepAliveWebView(context).apply {
                tag = "fw_youtube_webview"
                configureWebViewCommon(context, this, desktopMode = false)
                webViewClient = WebViewClient()
                webChromeClient = WebChromeClient()
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            }

            lateinit var refreshYtMenu: () -> Unit
            var ytMenuInitialized = false
            refreshYtMenu = {
                val modeLabel = if (isDesktopMode) "Mode: Desktop" else "Mode: Mobile"
                if (ytMenuInitialized) {
                    managed.updateMenuItemLabel("fw_youtube_mode_toggle", modeLabel, accentColor)
                } else {
                    ytMenuInitialized = true
                    managed.setMenuItems(
                        listOf(
                            FloatingWindowManager.WindowMenuItem("back", "← Go back", "fw_youtube_back") {
                                if (webView.canGoBack()) webView.goBack()
                            },
                            FloatingWindowManager.WindowMenuItem("forward", "→ Go forward", "fw_youtube_forward") {
                                if (webView.canGoForward()) webView.goForward()
                            },
                            FloatingWindowManager.WindowMenuItem("reload", "↻ Reload", "fw_youtube_reload") {
                                webView.reload()
                            },
                            FloatingWindowManager.WindowMenuItem("copy_url", "📋 Copy URL", "fw_youtube_copy_url") {
                                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                cm.setPrimaryClip(ClipData.newPlainText("YouTube URL", webView.url ?: "https://m.youtube.com"))
                                Toast.makeText(context, "URL copied", Toast.LENGTH_SHORT).show()
                            },
                            FloatingWindowManager.WindowMenuItem(
                                id = "mode_toggle",
                                label = modeLabel,
                                tag = "fw_youtube_mode_toggle",
                                textColor = accentColor
                            ) {
                                isDesktopMode = !isDesktopMode
                                if (isDesktopMode) {
                                    webView.settings.userAgentString = DESKTOP_USER_AGENT
                                    webView.settings.useWideViewPort = true
                                    webView.loadUrl("https://www.youtube.com")
                                } else {
                                    webView.settings.userAgentString = MOBILE_USER_AGENT
                                    webView.loadUrl("https://m.youtube.com")
                                }
                                refreshYtMenu()
                            }
                        )
                    )
                }
            }
            refreshYtMenu()

            webView.loadUrl("https://m.youtube.com")

            val cleanup: () -> Unit = {
                runCatching {
                    webView.stopLoading()
                    webView.destroy()
                }
            }
            webView to cleanup
        }
    }

    fun openFloatingNotes(
        context: Context,
        manager: FloatingWindowManager,
        serviceScope: CoroutineScope,
        accentColor: Int
    ): FloatingWindowManager.ManagedWindow? {
        return manager.openOrFocusWindow(
            windowId = "floating_notes",
            title = context.getString(R.string.floating_window_title_notes),
            accentColor = accentColor,
            iconRes = R.drawable.ic_tab_notes,
            requiresTextInput = true
        ) { managed ->
            val container = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(context, 8f), dp(context, 8f), dp(context, 8f), dp(context, 8f))
            }

            var editingNote: NoteEntry? = null

            val editBanner = LinearLayout(context).apply {
                tag = "fw_notes_edit_banner"
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                visibility = View.GONE
                setPadding(dp(context, 8f), dp(context, 4f), dp(context, 8f), dp(context, 4f))
                background = GradientDrawable().apply {
                    cornerRadius = dp(context, 8f).toFloat()
                    setColor(Color.parseColor("#1E293B"))
                }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = dp(context, 4f)
                }
            }
            val tvEditStatus = TextView(context).apply {
                text = context.getString(R.string.note_editing_label)
                textSize = 11f
                setTextColor(accentColor)
                setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val btnCancelEdit = TextView(context).apply {
                tag = "fw_notes_cancel_edit"
                text = context.getString(R.string.note_cancel_edit)
                textSize = 11f
                setTextColor(Color.parseColor("#EF4444"))
                setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
                setPadding(dp(context, 6f), dp(context, 2f), dp(context, 6f), dp(context, 2f))
            }
            editBanner.addView(tvEditStatus)
            editBanner.addView(btnCancelEdit)

            val etTitle = EditText(context).apply {
                tag = "fw_notes_title_input"
                hint = context.getString(R.string.note_title_hint)
                setTextColor(Color.WHITE)
                setHintTextColor(Color.parseColor("#64748B"))
                textSize = 12.5f
                isSingleLine = true
                setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
                setPadding(dp(context, 10f), dp(context, 6f), dp(context, 10f), dp(context, 6f))
                background = GradientDrawable().apply {
                    cornerRadius = dp(context, 10f).toFloat()
                    setColor(Color.parseColor("#1E293B"))
                }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = dp(context, 4f)
                }
            }

            val inputRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = dp(context, 8f)
                }
            }

            val etNote = EditText(context).apply {
                tag = "fw_notes_input"
                hint = "Write a sticky note..."
                setTextColor(Color.WHITE)
                setHintTextColor(Color.parseColor("#64748B"))
                textSize = 12.5f
                setPadding(dp(context, 10f), dp(context, 8f), dp(context, 10f), dp(context, 8f))
                background = GradientDrawable().apply {
                    cornerRadius = dp(context, 10f).toFloat()
                    setColor(Color.parseColor("#1E293B"))
                }
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginEnd = dp(context, 6f)
                }
            }

            val btnSave = Button(context).apply {
                tag = "fw_notes_save"
                text = "Save"
                textSize = 11.5f
                setTextColor(Color.BLACK)
                backgroundTintList = null
                background = GradientDrawable().apply {
                    cornerRadius = dp(context, 8f).toFloat()
                    setColor(accentColor)
                }
            }
            inputRow.addView(etNote)
            inputRow.addView(btnSave)

            fun clearEditMode() {
                editingNote = null
                editBanner.visibility = View.GONE
                btnSave.text = "Save"
                etTitle.setText("")
                etNote.setText("")
            }

            btnCancelEdit.setOnClickListener { clearEditMode() }

            val listContainer = LinearLayout(context).apply {
                tag = "fw_notes_list"
                orientation = LinearLayout.VERTICAL
            }
            val scroller = ScrollView(context).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    0,
                    1f
                )
                addView(listContainer)
            }

            val notesJob = Job(serviceScope.coroutineContext[Job])
            val windowScope = CoroutineScope(Dispatchers.Main + notesJob)

            managed.setMenuItems(
                listOf(
                    FloatingWindowManager.WindowMenuItem("new_note", "+ New Note", "fw_notes_menu_new") {
                        clearEditMode()
                        etNote.requestFocus()
                    },
                    FloatingWindowManager.WindowMenuItem("paste_note", "📋 Paste to Note", "fw_notes_menu_paste") {
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        val clip = cm.primaryClip?.getItemAt(0)?.text?.toString().orEmpty()
                        if (clip.isNotBlank()) {
                            val current = etNote.text?.toString().orEmpty()
                            etNote.setText(if (current.isBlank()) clip else "$current\n$clip")
                        }
                    }
                )
            )

            btnSave.setOnClickListener {
                val titleTxt = etTitle.text?.toString()?.trim().orEmpty()
                val txt = etNote.text?.toString()?.trim().orEmpty()
                if (txt.isEmpty()) {
                    Toast.makeText(context, "Write a note first.", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                val activeEdit = editingNote
                clearEditMode()
                windowScope.launch {
                    withContext(Dispatchers.IO) {
                        val dao = SmartDotDatabase.getDatabase(context).smartDotDao()
                        if (activeEdit != null) {
                            dao.updateNote(
                                activeEdit.copy(
                                    title = titleTxt,
                                    content = txt,
                                    timestamp = System.currentTimeMillis()
                                )
                            )
                        } else {
                            dao.insertNote(NoteEntry(title = titleTxt, content = txt))
                        }
                    }
                }
            }

            windowScope.launch {
                SmartDotDatabase.getDatabase(context).smartDotDao().getAllNotesFlow().collectLatest { notes ->
                    listContainer.removeAllViews()
                    if (notes.isEmpty()) {
                        listContainer.addView(TextView(context).apply {
                            text = "No notes saved yet."
                            setTextColor(Color.parseColor("#64748B"))
                            textSize = 12f
                            gravity = Gravity.CENTER
                            setPadding(dp(context, 12f), dp(context, 16f), dp(context, 12f), dp(context, 16f))
                        })
                    } else {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        notes.forEach { note ->
                            val row = LinearLayout(context).apply {
                                tag = "fw_note_row_${note.id}"
                                orientation = LinearLayout.HORIZONTAL
                                gravity = Gravity.CENTER_VERTICAL
                                setPadding(dp(context, 8f), dp(context, 8f), dp(context, 8f), dp(context, 8f))
                                background = GradientDrawable().apply {
                                    cornerRadius = dp(context, 8f).toFloat()
                                    setColor(Color.parseColor("#1E293B"))
                                }
                                layoutParams = LinearLayout.LayoutParams(
                                    LinearLayout.LayoutParams.MATCH_PARENT,
                                    LinearLayout.LayoutParams.WRAP_CONTENT
                                ).apply {
                                    bottomMargin = dp(context, 6f)
                                }
                                setOnClickListener {
                                    editingNote = note
                                    etTitle.setText(note.title)
                                    etNote.setText(note.content)
                                    etNote.setSelection(note.content.length)
                                    etNote.requestFocus()
                                    editBanner.visibility = View.VISIBLE
                                    btnSave.text = "Update"
                                }
                            }
                            val textCol = LinearLayout(context).apply {
                                orientation = LinearLayout.VERTICAL
                                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                            }
                            if (note.title.isNotBlank()) {
                                textCol.addView(TextView(context).apply {
                                    text = note.title
                                    setTextColor(accentColor)
                                    textSize = 12f
                                    setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
                                    maxLines = 1
                                    ellipsize = TextUtils.TruncateAt.END
                                })
                            }
                            val tvContent = TextView(context).apply {
                                text = note.content
                                setTextColor(Color.WHITE)
                                textSize = 12.5f
                            }
                            textCol.addView(tvContent)

                            val btnCopy = TextView(context).apply {
                                text = "Copy"
                                textSize = 10.5f
                                setTextColor(accentColor)
                                setPadding(dp(context, 8f), dp(context, 4f), dp(context, 8f), dp(context, 4f))
                                setOnClickListener {
                                    val combined = if (note.title.isNotBlank()) "${note.title}\n${note.content}" else note.content
                                    clipboard.setPrimaryClip(ClipData.newPlainText("Note", combined))
                                    Toast.makeText(context, "Copied note", Toast.LENGTH_SHORT).show()
                                }
                            }
                            val btnDel = TextView(context).apply {
                                text = "Del"
                                textSize = 10.5f
                                setTextColor(Color.parseColor("#EF4444"))
                                setPadding(dp(context, 6f), dp(context, 4f), dp(context, 6f), dp(context, 4f))
                                setOnClickListener {
                                    if (editingNote?.id == note.id) clearEditMode()
                                    windowScope.launch {
                                        withContext(Dispatchers.IO) {
                                            SmartDotDatabase.getDatabase(context).smartDotDao().deleteNoteById(note.id)
                                        }
                                    }
                                }
                            }
                            row.addView(textCol)
                            row.addView(btnCopy)
                            row.addView(btnDel)
                            listContainer.addView(row)
                        }
                    }
                }
            }

            container.addView(editBanner)
            container.addView(etTitle)
            container.addView(inputRow)
            container.addView(scroller)

            val cleanup: () -> Unit = { notesJob.cancel() }
            container to cleanup
        }
    }

    fun openFloatingCalculator(
        context: Context,
        manager: FloatingWindowManager,
        accentColor: Int
    ): FloatingWindowManager.ManagedWindow? {
        return manager.openOrFocusWindow(
            windowId = "floating_calculator",
            title = context.getString(R.string.floating_window_title_calculator),
            accentColor = accentColor,
            iconRes = R.drawable.ic_tab_calc,
            requiresTextInput = false
        ) { managed ->
            val container = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(context, 8f), dp(context, 8f), dp(context, 8f), dp(context, 8f))
            }

            var expr = ""
            val tvExpr = TextView(context).apply {
                tag = "fw_calc_expr"
                text = "0"
                textSize = 22f
                setTextColor(Color.WHITE)
                gravity = Gravity.END
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.START
            }
            val tvPreview = TextView(context).apply {
                tag = "fw_calc_preview"
                text = ""
                textSize = 14f
                setTextColor(accentColor)
                gravity = Gravity.END
                maxLines = 1
            }

            val displayCard = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(context, 10f), dp(context, 8f), dp(context, 10f), dp(context, 8f))
                background = GradientDrawable().apply {
                    cornerRadius = dp(context, 10f).toFloat()
                    setColor(Color.parseColor("#1E293B"))
                }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = dp(context, 8f)
                }
                addView(tvExpr)
                addView(tvPreview)
            }

            fun refreshDisplay() {
                tvExpr.text = if (expr.isEmpty()) "0" else expr
                val normalized = expr.replace('×', '*').replace('÷', '/')
                val eval = CalculatorEvaluator.evaluateLivePreview(normalized)
                tvPreview.text = eval?.let { "= $it" }.orEmpty()
            }

            managed.setMenuItems(
                listOf(
                    FloatingWindowManager.WindowMenuItem("copy_calc", "📋 Copy Result") {
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("Calculator", tvExpr.text.toString()))
                        Toast.makeText(context, "Copied result", Toast.LENGTH_SHORT).show()
                    },
                    FloatingWindowManager.WindowMenuItem("clear_calc", "⌫ Clear All") {
                        expr = ""
                        refreshDisplay()
                    }
                )
            )

            val rows = listOf(
                listOf("C", "⌫", "%", "÷"),
                listOf("7", "8", "9", "×"),
                listOf("4", "5", "6", "-"),
                listOf("1", "2", "3", "+"),
                listOf("00", "0", ".", "=")
            )

            container.addView(displayCard)

            rows.forEach { rowTokens ->
                val rowLayout = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    weightSum = 4f
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        0,
                        1f
                    ).apply {
                        bottomMargin = dp(context, 4f)
                    }
                }
                rowTokens.forEach { token ->
                    val isEquals = token == "="
                    val isOp = token in listOf("C", "⌫", "%", "÷", "×", "-", "+")
                    val btn = TextView(context).apply {
                        tag = "fw_calc_btn_$token"
                        text = token
                        textSize = 16f
                        setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
                        gravity = Gravity.CENTER
                        setTextColor(
                            when {
                                isEquals -> Color.BLACK
                                isOp -> accentColor
                                else -> Color.WHITE
                            }
                        )
                        background = GradientDrawable().apply {
                            cornerRadius = dp(context, 10f).toFloat()
                            setColor(if (isEquals) accentColor else Color.parseColor("#1E293B"))
                        }
                        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply {
                            marginEnd = dp(context, 4f)
                        }
                        setOnClickListener {
                            when (token) {
                                "C" -> expr = ""
                                "⌫" -> if (expr.isNotEmpty()) expr = expr.dropLast(1)
                                "=" -> {
                                    if (expr.isNotEmpty()) {
                                        val normalized = expr.replace('×', '*').replace('÷', '/')
                                        expr = CalculatorEvaluator.evaluateFinal(normalized)
                                        tvPreview.text = ""
                                    }
                                }
                                else -> expr += token
                            }
                            refreshDisplay()
                        }
                    }
                    rowLayout.addView(btn)
                }
                container.addView(rowLayout)
            }

            container to null
        }
    }

    fun openFloatingTranslator(
        context: Context,
        manager: FloatingWindowManager,
        serviceScope: CoroutineScope,
        accentColor: Int
    ): FloatingWindowManager.ManagedWindow? {
        return manager.openOrFocusWindow(
            windowId = "floating_translator",
            title = context.getString(R.string.floating_window_title_translator),
            accentColor = accentColor,
            iconRes = R.drawable.ic_tab_translate,
            requiresTextInput = true
        ) { managed ->
            val container = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(context, 8f), dp(context, 8f), dp(context, 8f), dp(context, 8f))
            }

            val prefs = context.getSharedPreferences(SelectionToolbarHelper.PREFS_NAME, Context.MODE_PRIVATE)
            val savedLang = prefs.getString("translate_target_language", "Hindi") ?: "Hindi"
            val langs = ScreenTranslateHelper.SUPPORTED_LANGUAGES

            val spinner = Spinner(context).apply {
                tag = "fw_translate_lang_spinner"
                adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, langs)
                val idx = langs.indexOf(savedLang).coerceAtLeast(0)
                setSelection(idx)
                background = GradientDrawable().apply {
                    cornerRadius = dp(context, 8f).toFloat()
                    setColor(Color.parseColor("#E2E8F0"))
                }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(context, 38f)
                ).apply {
                    bottomMargin = dp(context, 6f)
                }
            }

            val etSource = EditText(context).apply {
                tag = "fw_translate_input"
                hint = "Enter text to auto-detect & translate..."
                setTextColor(Color.WHITE)
                setHintTextColor(Color.parseColor("#64748B"))
                textSize = 13f
                minLines = 3
                gravity = Gravity.TOP or Gravity.START
                setPadding(dp(context, 10f), dp(context, 8f), dp(context, 10f), dp(context, 8f))
                background = GradientDrawable().apply {
                    cornerRadius = dp(context, 10f).toFloat()
                    setColor(Color.parseColor("#1E293B"))
                }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(context, 90f)
                ).apply {
                    bottomMargin = dp(context, 6f)
                }
            }

            val btnTranslate = Button(context).apply {
                tag = "fw_translate_btn"
                text = "Translate"
                textSize = 12.5f
                setTextColor(Color.BLACK)
                backgroundTintList = null
                background = GradientDrawable().apply {
                    cornerRadius = dp(context, 8f).toFloat()
                    setColor(accentColor)
                }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(context, 40f)
                ).apply {
                    bottomMargin = dp(context, 8f)
                }
            }

            val tvResult = TextView(context).apply {
                tag = "fw_translate_result"
                text = "Translation output appears here..."
                setTextColor(Color.parseColor("#E2E8F0"))
                textSize = 13.5f
                setPadding(dp(context, 10f), dp(context, 10f), dp(context, 10f), dp(context, 10f))
                background = GradientDrawable().apply {
                    cornerRadius = dp(context, 10f).toFloat()
                    setColor(Color.parseColor("#1E293B"))
                }
            }

            val resultScroll = ScrollView(context).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    0,
                    1f
                )
                addView(tvResult)
            }

            val helper = ScreenTranslateHelper()
            val trJob = Job(serviceScope.coroutineContext[Job])
            val windowScope = CoroutineScope(Dispatchers.Main + trJob)
            var tts: TextToSpeech? = null

            val actionBar = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = dp(context, 6f)
                }
            }
            val btnCopy = TextView(context).apply {
                tag = "fw_translate_copy"
                text = "Copy"
                textSize = 12f
                gravity = Gravity.CENTER
                setTextColor(accentColor)
                setPadding(dp(context, 10f), dp(context, 6f), dp(context, 10f), dp(context, 6f))
                background = GradientDrawable().apply {
                    cornerRadius = dp(context, 8f).toFloat()
                    setColor(Color.parseColor("#1E293B"))
                }
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginEnd = dp(context, 6f)
                }
                setOnClickListener {
                    val out = tvResult.text?.toString().orEmpty()
                    if (out.isNotBlank() && !out.startsWith("Translation output")) {
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("Translation", out))
                        Toast.makeText(context, "Copied translation", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            val btnSpeak = TextView(context).apply {
                tag = "fw_translate_speak"
                text = "Speak"
                textSize = 12f
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                setPadding(dp(context, 10f), dp(context, 6f), dp(context, 10f), dp(context, 6f))
                background = GradientDrawable().apply {
                    cornerRadius = dp(context, 8f).toFloat()
                    setColor(Color.parseColor("#1E293B"))
                }
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                setOnClickListener {
                    val out = tvResult.text?.toString().orEmpty()
                    if (out.isNotBlank() && !out.startsWith("Translation output")) {
                        if (tts == null) {
                            tts = TextToSpeech(context.applicationContext) { status ->
                                if (status == TextToSpeech.SUCCESS) {
                                    tts?.language = Locale.getDefault()
                                    tts?.speak(out, TextToSpeech.QUEUE_FLUSH, null, "fw_tr_tts")
                                }
                            }
                        } else {
                            tts?.speak(out, TextToSpeech.QUEUE_FLUSH, null, "fw_tr_tts")
                        }
                    }
                }
            }
            actionBar.addView(btnCopy)
            actionBar.addView(btnSpeak)

            managed.setMenuItems(
                listOf(
                    FloatingWindowManager.WindowMenuItem("paste_src", "📋 Paste Source Text") {
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        val clip = cm.primaryClip?.getItemAt(0)?.text?.toString().orEmpty()
                        if (clip.isNotBlank()) etSource.setText(clip)
                    },
                    FloatingWindowManager.WindowMenuItem("copy_out", "📋 Copy Translation") {
                        btnCopy.performClick()
                    }
                )
            )

            btnTranslate.setOnClickListener {
                val input = etSource.text?.toString()?.trim().orEmpty()
                if (input.isEmpty()) {
                    Toast.makeText(context, "Type or paste text to translate.", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                val dst = spinner.selectedItem?.toString() ?: "Hindi"
                prefs.edit().putString("translate_target_language", dst).apply()
                tvResult.text = context.getString(R.string.screen_translate_translating)
                windowScope.launch {
                    val res = helper.translateTextDetailed(
                        text = input,
                        targetLanguageName = dst,
                        autoFlipIfSameAsTarget = true
                    )
                    tvResult.text = res.translatedText
                }
            }

            container.addView(spinner)
            container.addView(etSource)
            container.addView(btnTranslate)
            container.addView(resultScroll)
            container.addView(actionBar)

            val cleanup: () -> Unit = {
                trJob.cancel()
                runCatching { helper.close() }
                runCatching { tts?.stop(); tts?.shutdown() }
            }
            container to cleanup
        }
    }

    fun openFloatingViewer(
        context: Context,
        manager: FloatingWindowManager,
        accentColor: Int
    ): FloatingWindowManager.ManagedWindow? {
        return manager.openOrFocusWindow(
            windowId = "floating_viewer",
            title = context.getString(R.string.floating_window_title_viewer),
            accentColor = accentColor,
            iconRes = android.R.drawable.ic_menu_gallery,
            requiresTextInput = false
        ) { managed ->
            val container = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(context, 8f), dp(context, 8f), dp(context, 8f), dp(context, 8f))
            }

            val topBtns = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = dp(context, 6f)
                }
            }

            val tvFileInfo = TextView(context).apply {
                tag = "fw_viewer_status"
                text = "Pick an image or text document via Android Photo Picker / Files."
                textSize = 11.5f
                setTextColor(Color.parseColor("#94A3B8"))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = dp(context, 6f)
                }
            }

            val imageView = FloatingMediaFilesHelper.PinchZoomImageView(context).apply {
                tag = "fw_viewer_image"
                visibility = View.GONE
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            }

            val tvFilePreview = TextView(context).apply {
                tag = "fw_viewer_text"
                text = ""
                textSize = 12f
                setTextColor(Color.WHITE)
                setPadding(dp(context, 8f), dp(context, 8f), dp(context, 8f), dp(context, 8f))
            }
            val textScroll = ScrollView(context).apply {
                visibility = View.GONE
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
                addView(tvFilePreview)
            }

            val previewHost = FrameLayout(context).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    0,
                    1f
                )
                background = GradientDrawable().apply {
                    cornerRadius = dp(context, 10f).toFloat()
                    setColor(Color.parseColor("#1E293B"))
                }
                addView(imageView)
                addView(textScroll)
            }

            fun loadUriIntoViewer(uri: Uri, isImage: Boolean) {
                val mime = runCatching { context.contentResolver.getType(uri) }.getOrNull().orEmpty()
                if (isImage || mime.startsWith("image/")) {
                    imageView.resetZoom()
                    val bmp = runCatching {
                        context.contentResolver.openInputStream(uri)?.use { stream ->
                            BitmapFactory.decodeStream(stream)
                        }
                    }.getOrNull()
                    if (bmp != null) {
                        imageView.setImageBitmap(bmp)
                    } else {
                        imageView.setImageURI(uri)
                    }
                    imageView.visibility = View.VISIBLE
                    textScroll.visibility = View.GONE
                    tvFileInfo.text = uri.lastPathSegment ?: "Selected Image"
                } else {
                    val textBody = runCatching {
                        context.contentResolver.openInputStream(uri)?.use { stream ->
                            val buf = ByteArray(64 * 1024)
                            val read = stream.read(buf)
                            if (read > 0) String(buf, 0, read, Charsets.UTF_8) else "(Empty file)"
                        } ?: "Unable to open selected file."
                    }.getOrElse { "Preview unavailable for binary file: ${uri.lastPathSegment ?: uri}" }
                    tvFilePreview.text = textBody
                    textScroll.visibility = View.VISIBLE
                    imageView.visibility = View.GONE
                    tvFileInfo.text = uri.lastPathSegment ?: "Selected Document"
                }
            }

            val btnPickImage = Button(context).apply {
                tag = "fw_viewer_pick_image"
                text = "Pick Image"
                textSize = 11.5f
                setTextColor(Color.BLACK)
                backgroundTintList = null
                background = GradientDrawable().apply {
                    cornerRadius = dp(context, 8f).toFloat()
                    setColor(accentColor)
                }
                layoutParams = LinearLayout.LayoutParams(0, dp(context, 38f), 1f).apply {
                    marginEnd = dp(context, 6f)
                }
                setOnClickListener {
                    ViewerPickerActivity.pendingCallback = { uri, isImg ->
                        loadUriIntoViewer(uri, isImg)
                    }
                    val intent = Intent(context, ViewerPickerActivity::class.java).apply {
                        putExtra(ViewerPickerActivity.EXTRA_PICK_MODE, ViewerPickerActivity.MODE_IMAGE)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                }
            }

            val btnPickFile = Button(context).apply {
                tag = "fw_viewer_pick_file"
                text = "Open File"
                textSize = 11.5f
                setTextColor(Color.WHITE)
                backgroundTintList = null
                background = GradientDrawable().apply {
                    cornerRadius = dp(context, 8f).toFloat()
                    setColor(Color.parseColor("#334155"))
                }
                layoutParams = LinearLayout.LayoutParams(0, dp(context, 38f), 1f)
                setOnClickListener {
                    ViewerPickerActivity.pendingCallback = { uri, isImg ->
                        loadUriIntoViewer(uri, isImg)
                    }
                    val intent = Intent(context, ViewerPickerActivity::class.java).apply {
                        putExtra(ViewerPickerActivity.EXTRA_PICK_MODE, ViewerPickerActivity.MODE_FILE)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                }
            }

            managed.setMenuItems(
                listOf(
                    FloatingWindowManager.WindowMenuItem("pick_img", "🖼 Pick Image (Photo Picker)") {
                        btnPickImage.performClick()
                    },
                    FloatingWindowManager.WindowMenuItem("open_file", "📂 Open Document (SAF)") {
                        btnPickFile.performClick()
                    },
                    FloatingWindowManager.WindowMenuItem("open_media", "🎵 Open Full Media & Files") {
                        openFloatingMediaFiles(context, manager, accentColor)
                    }
                )
            )

            topBtns.addView(btnPickImage)
            topBtns.addView(btnPickFile)

            container.addView(topBtns)
            container.addView(tvFileInfo)
            container.addView(previewHost)

            val cleanup: () -> Unit = {
                ViewerPickerActivity.pendingCallback = null
            }
            container to cleanup
        }
    }

    /**
     * Opens official Gemini, ChatGPT, or Google AI Mode web interfaces inside a movable &
     * resizable FloatingWindowManager overlay with full-bleed center WebView and Left ☰ Menu.
     */
    @SuppressLint("SetJavaScriptEnabled")
    fun openFloatingAiWebWindow(
        context: Context,
        manager: FloatingWindowManager,
        accentColor: Int,
        aiModeId: String
    ): FloatingWindowManager.ManagedWindow? {
        val (title, targetUrl, iconRes) = when (aiModeId) {
            "chatgpt" -> Triple(
                context.getString(R.string.floating_window_title_chatgpt),
                "https://chatgpt.com/",
                R.drawable.ic_toolbar_chatgpt
            )
            "google_ai_mode" -> Triple(
                context.getString(R.string.floating_window_title_ai_mode),
                "https://www.google.com/search?udm=50",
                R.drawable.ic_toolbar_ai_mode
            )
            else -> Triple(
                context.getString(R.string.floating_window_title_gemini),
                "https://gemini.google.com/app",
                R.drawable.ic_toolbar_gemini
            )
        }

        return manager.openOrFocusWindow(
            windowId = "floating_ai_$aiModeId",
            title = title,
            accentColor = accentColor,
            iconRes = iconRes,
            requiresTextInput = true
        ) { managed ->
            var isDesktopMode = false
            val webView = WebView(context).apply {
                tag = "fw_ai_webview_$aiModeId"
                configureWebViewCommon(context, this, desktopMode = false)
                webViewClient = WebViewClient()
                webChromeClient = object : WebChromeClient() {
                    override fun onShowFileChooser(
                        webView: WebView?,
                        filePathCallback: ValueCallback<Array<Uri>>?,
                        fileChooserParams: FileChooserParams?
                    ): Boolean = handleWebFileChooser(context, filePathCallback, fileChooserParams)

                    override fun onPermissionRequest(request: PermissionRequest?) {
                        if (request != null) grantWebPermissionsIfPermitted(context, request)
                    }
                }
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            }

            lateinit var refreshAiMenu: () -> Unit
            var aiMenuInitialized = false
            refreshAiMenu = {
                val modeLabel = if (isDesktopMode) "Mode: Desktop" else "Mode: Mobile"
                if (aiMenuInitialized) {
                    managed.updateMenuItemLabel("fw_ai_mode_toggle_$aiModeId", modeLabel, accentColor)
                } else {
                    aiMenuInitialized = true
                    managed.setMenuItems(
                        listOf(
                            FloatingWindowManager.WindowMenuItem("back", "← Go back", "fw_ai_back_$aiModeId") {
                                if (webView.canGoBack()) webView.goBack()
                            },
                            FloatingWindowManager.WindowMenuItem("forward", "→ Go forward", "fw_ai_forward_$aiModeId") {
                                if (webView.canGoForward()) webView.goForward()
                            },
                            FloatingWindowManager.WindowMenuItem("reload", "↻ Reload", "fw_ai_reload_$aiModeId") {
                                webView.reload()
                            },
                            FloatingWindowManager.WindowMenuItem("copy_url", "📋 Copy URL", "fw_ai_copy_$aiModeId") {
                                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                cm.setPrimaryClip(ClipData.newPlainText("URL", webView.url ?: targetUrl))
                                Toast.makeText(context, "URL copied", Toast.LENGTH_SHORT).show()
                            },
                            FloatingWindowManager.WindowMenuItem(
                                id = "mode_toggle",
                                label = modeLabel,
                                tag = "fw_ai_mode_toggle_$aiModeId",
                                textColor = accentColor
                            ) {
                                isDesktopMode = !isDesktopMode
                                webView.settings.userAgentString = if (isDesktopMode) DESKTOP_USER_AGENT else MOBILE_USER_AGENT
                                webView.reload()
                                refreshAiMenu()
                            }
                        )
                    )
                }
            }
            refreshAiMenu()

            webView.loadUrl(targetUrl)

            val cleanup: () -> Unit = {
                ViewerPickerActivity.pendingWebFileCallback?.onReceiveValue(null)
                ViewerPickerActivity.pendingWebFileCallback = null
                runCatching { CookieManager.getInstance().flush() }
                runCatching {
                    webView.stopLoading()
                    webView.destroy()
                }
            }
            webView to cleanup
        }
    }
}
