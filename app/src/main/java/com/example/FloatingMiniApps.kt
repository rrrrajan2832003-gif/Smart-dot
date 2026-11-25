package com.example

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.speech.tts.TextToSpeech
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.webkit.CookieManager
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
 * 1. Floating Browser (WebView + URL/Search bar + Bookmark Manager + Back/Forward + Multi-Tabs)
 * 2. Floating YouTube (Mobile m.youtube.com WebView + Desktop mode toggle + background playback)
 * 3. Floating Notes (Room DB sticky notepad)
 * 4. Floating Calculator (CalculatorEvaluator live keypad)
 * 5. Floating Translator (ML Kit auto-detect offline translator)
 * 6. Floating File/Image Viewer (zero-permission Photo Picker & SAF viewer)
 */
object FloatingMiniApps {

    data class BookmarkEntry(
        val title: String,
        val url: String
    )

    const val PREF_BROWSER_BOOKMARKS = "floating_browser_bookmarks"

    @Volatile
    internal var pendingBrowserUploadImageUri: Uri? = null

    private val browserNavigators = WeakHashMap<View, (String, Uri?) -> Unit>()

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
            val obj = JSONObject()
            obj.put("title", entry.title)
            obj.put("url", entry.url)
            arr.put(obj)
        }
        val prefs = context.getSharedPreferences(SelectionToolbarHelper.PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(PREF_BROWSER_BOOKMARKS, arr.toString()).apply()
    }

    fun isBookmarked(context: Context, url: String): Boolean {
        val cleanUrl = url.trim()
        if (cleanUrl.isEmpty()) return false
        return getBrowserBookmarks(context).any { it.url.equals(cleanUrl, ignoreCase = true) }
    }

    /**
     * Toggles a URL in the Floating Browser bookmark list.
     * Returns true if added, false if removed.
     */
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

    private class KeepAliveWebView(context: Context) : WebView(context) {
        override fun onWindowVisibilityChanged(visibility: Int) {
            // Keep WebView active even when minimized to bubble for background YouTube playback
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
                setPadding(dp(context, 6f), dp(context, 6f), dp(context, 6f), dp(context, 6f))
            }

            // Tab state: each tab holds its WebView + current URL/title
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
                setPadding(dp(context, 8f), dp(context, 6f), dp(context, 8f), dp(context, 6f))
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp(context, 8f).toFloat()
                    setColor(Color.parseColor("#1E293B"))
                }
                layoutParams = LinearLayout.LayoutParams(0, dp(context, 34f), 1f).apply {
                    marginStart = dp(context, 4f)
                    marginEnd = dp(context, 4f)
                }
            }

            data class BrowserTab(var title: String, val webView: WebView, var lastUrl: String = "")
            val tabs = ArrayList<BrowserTab>()
            var activeTabIdx = 0

            val bookmarksListContainer = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
            }
            val bookmarksScroll = ScrollView(context).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(context, 160f)
                )
                addView(bookmarksListContainer)
            }
            val bookmarksSheet = LinearLayout(context).apply {
                tag = "fw_browser_bookmarks_sheet"
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
                addView(TextView(context).apply {
                    text = "Bookmarks"
                    textSize = 12f
                    setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
                    setTextColor(accentColor)
                    setPadding(0, 0, 0, dp(context, 4f))
                })
                addView(bookmarksScroll)
            }

            lateinit var btnBookmarkStar: TextView

            fun activeCurrentUrl(): String {
                val activeTab = tabs.getOrNull(activeTabIdx)
                val wvUrl = activeTab?.webView?.url?.takeIf { it.isNotBlank() }
                val rememberedUrl = activeTab?.lastUrl?.takeIf { it.isNotBlank() }
                val fieldUrl = etUrl.text?.toString()?.trim()?.takeIf { it.isNotBlank() }
                return wvUrl ?: rememberedUrl ?: fieldUrl ?: ""
            }

            fun refreshBookmarkStar() {
                val currentUrl = activeCurrentUrl()
                val bookmarked = isBookmarked(context, currentUrl)
                btnBookmarkStar.text = if (bookmarked) "★" else "☆"
                btnBookmarkStar.setTextColor(if (bookmarked) Color.parseColor("#FBBF24") else Color.WHITE)
            }

            fun loadUrlInActiveTab(rawUrl: String) {
                val dest = normalizeBrowserInput(rawUrl)
                val activeTab = tabs.getOrNull(activeTabIdx) ?: return
                activeTab.lastUrl = dest
                etUrl.setText(dest)
                activeTab.webView.loadUrl(dest)
                refreshBookmarkStar()
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
                    }
                    val textCol = LinearLayout(context).apply {
                        orientation = LinearLayout.VERTICAL
                        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                        setOnClickListener {
                            loadUrlInActiveTab(entry.url)
                            bookmarksSheet.visibility = View.GONE
                        }
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
                            refreshBookmarkStar()
                            renderBookmarksSheet()
                        }
                    }
                    row.addView(textCol)
                    row.addView(btnDel)
                    bookmarksListContainer.addView(row)
                }
            }

            fun renderTabsStrip() {
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
                            refreshBookmarkStar()
                            renderTabsStrip()
                        }
                    }
                    tabsStrip.addView(pill)
                }
                val btnNewTab = TextView(context).apply {
                    tag = "fw_browser_new_tab"
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
                }
                tabsStrip.addView(btnNewTab)
                btnNewTab.setOnClickListener {
                    val newIdx = tabs.size
                    val wv = createBrowserWebView(
                        context = context,
                        etUrl = etUrl,
                        onTitleChanged = { title ->
                            if (newIdx < tabs.size) {
                                tabs[newIdx].title = title.ifBlank { "Tab ${newIdx + 1}" }
                                renderTabsStrip()
                            }
                        },
                        onUrlFinished = { finishedUrl ->
                            if (newIdx < tabs.size) {
                                tabs[newIdx].lastUrl = finishedUrl
                            }
                            if (newIdx == activeTabIdx) refreshBookmarkStar()
                        }
                    )
                    tabs.add(BrowserTab("Tab ${newIdx + 1}", wv, "https://www.google.com"))
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
                    wv.loadUrl("https://www.google.com")
                    etUrl.setText("https://www.google.com")
                    refreshBookmarkStar()
                    renderTabsStrip()
                }
            }

            fun makeNavBtn(label: String, btnTag: String, desc: String, onClick: () -> Unit): TextView {
                return TextView(context).apply {
                    tag = btnTag
                    text = label
                    textSize = 13f
                    gravity = Gravity.CENTER
                    setTextColor(Color.WHITE)
                    contentDescription = desc
                    minWidth = dp(context, 28f)
                    minHeight = dp(context, 34f)
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        cornerRadius = dp(context, 6f).toFloat()
                        setColor(Color.parseColor("#1E293B"))
                    }
                    setOnClickListener { onClick() }
                    layoutParams = LinearLayout.LayoutParams(dp(context, 28f), dp(context, 34f)).apply {
                        marginEnd = dp(context, 2f)
                    }
                }
            }

            val navBar = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = dp(context, 4f)
                }
            }

            val btnBack = makeNavBtn("◀", "fw_browser_back", "Back") {
                val wv = tabs.getOrNull(activeTabIdx)?.webView
                if (wv?.canGoBack() == true) wv.goBack()
            }
            val btnForward = makeNavBtn("▶", "fw_browser_forward", "Forward") {
                val wv = tabs.getOrNull(activeTabIdx)?.webView
                if (wv?.canGoForward() == true) wv.goForward()
            }
            btnBookmarkStar = makeNavBtn("☆", "fw_browser_bookmark_star", "Toggle bookmark") {
                val activeTab = tabs.getOrNull(activeTabIdx)
                val url = activeCurrentUrl()
                if (url.isNotBlank()) {
                    val pageTitle = activeTab?.webView?.title?.takeIf { it.isNotBlank() }
                        ?: activeTab?.title?.takeIf { it.isNotBlank() }
                        ?: url
                    val added = toggleBrowserBookmark(context, pageTitle, url)
                    refreshBookmarkStar()
                    if (bookmarksSheet.visibility == View.VISIBLE) {
                        renderBookmarksSheet()
                    }
                    val msgRes = if (added) R.string.bookmark_added else R.string.bookmark_removed
                    Toast.makeText(context, context.getString(msgRes), Toast.LENGTH_SHORT).show()
                }
            }
            val btnBookmarksList = makeNavBtn("📑", "fw_browser_bookmarks_list", "Bookmarks list") {
                if (bookmarksSheet.visibility == View.VISIBLE) {
                    bookmarksSheet.visibility = View.GONE
                } else {
                    renderBookmarksSheet()
                    bookmarksSheet.visibility = View.VISIBLE
                }
            }
            val btnGo = makeNavBtn("Go", "fw_browser_go", "Navigate to URL") {
                loadUrlInActiveTab(etUrl.text?.toString().orEmpty())
            }

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

            navBar.addView(btnBack)
            navBar.addView(btnForward)
            navBar.addView(etUrl)
            navBar.addView(btnBookmarkStar)
            navBar.addView(btnBookmarksList)
            navBar.addView(btnGo)

            // Initial Tab
            val startUrl = normalizeBrowserInput(initialUrl)
            val firstWebView = createBrowserWebView(
                context = context,
                etUrl = etUrl,
                onTitleChanged = { title ->
                    if (tabs.isNotEmpty()) {
                        tabs[0].title = title.ifBlank { "Google" }
                        renderTabsStrip()
                    }
                },
                onUrlFinished = { finishedUrl ->
                    if (tabs.isNotEmpty()) {
                        tabs[0].lastUrl = finishedUrl
                    }
                    if (activeTabIdx == 0) refreshBookmarkStar()
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
            refreshBookmarkStar()
            renderTabsStrip()

            browserNavigators[managed.rootView] = { nextUrl, imgUri ->
                if (imgUri != null) {
                    pendingBrowserUploadImageUri = imgUri
                }
                bookmarksSheet.visibility = View.GONE
                loadUrlInActiveTab(nextUrl)
            }

            container.addView(navBar)
            container.addView(tabsScroll)
            container.addView(bookmarksSheet)
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

    fun openGoogleAiModeInBrowser(
        context: Context,
        manager: FloatingWindowManager,
        accentColor: Int,
        query: String,
        question: String = "",
        cutoutBitmap: Bitmap? = null
    ): FloatingWindowManager.ManagedWindow? {
        val cleanQuestion = question.trim()
        val combinedClipboard = SelectionToolbarHelper.combineQuestionWithText(cleanQuestion, query)
        if (combinedClipboard.isNotEmpty()) {
            runCatching {
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                cm?.setPrimaryClip(ClipData.newPlainText("SmartDot AI Query", combinedClipboard))
            }
        }
        val imageUri = cutoutBitmap?.let { bmp ->
            SelectionToolbarHelper.writeCutoutToCacheUri(context, bmp)
        }
        val url = SelectionToolbarHelper.buildGoogleAiModeUrl(query, cleanQuestion)
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
        val combinedClipboard = SelectionToolbarHelper.combineQuestionWithText(cleanQuestion, text)
        if (combinedClipboard.isNotEmpty()) {
            runCatching {
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                cm?.setPrimaryClip(ClipData.newPlainText("SmartDot AI Query", combinedClipboard))
            }
        }
        val imageUri = cutoutBitmap?.let { bmp ->
            SelectionToolbarHelper.writeCutoutToCacheUri(context, bmp)
        }
        val url = SelectionToolbarHelper.buildChatGptUrl(text, cleanQuestion)
        return openFloatingBrowser(
            context = context,
            manager = manager,
            accentColor = accentColor,
            initialUrl = url,
            navigateIfOpen = true,
            preloadImageUri = imageUri
        )
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createBrowserWebView(
        context: Context,
        etUrl: EditText,
        onTitleChanged: (String) -> Unit,
        onUrlFinished: (String) -> Unit = {}
    ): WebView {
        return WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = true
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            settings.builtInZoomControls = true
            settings.displayZoomControls = false
            runCatching {
                val cm = CookieManager.getInstance()
                cm.setAcceptCookie(true)
                cm.setAcceptThirdPartyCookies(this, true)
            }
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    if (!url.isNullOrBlank()) {
                        etUrl.setText(url)
                        onUrlFinished(url)
                    }
                    onTitleChanged(view?.title.orEmpty())
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onReceivedTitle(view: WebView?, title: String?) {
                    if (!title.isNullOrBlank()) onTitleChanged(title)
                }

                override fun onShowFileChooser(
                    webView: WebView?,
                    filePathCallback: ValueCallback<Array<Uri>>?,
                    fileChooserParams: FileChooserParams?
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
            }
        }
    }

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
        ) { _ ->
            val container = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(context, 4f), dp(context, 4f), dp(context, 4f), dp(context, 4f))
            }

            var isDesktopMode = false
            val mobileUa = "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
            val desktopUa = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

            val webView = KeepAliveWebView(context).apply {
                tag = "fw_youtube_webview"
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.mediaPlaybackRequiresUserGesture = false
                settings.allowFileAccess = false
                settings.userAgentString = mobileUa
                webViewClient = WebViewClient()
                webChromeClient = WebChromeClient()
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    0,
                    1f
                )
            }

            val topControls = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = dp(context, 4f)
                }
            }

            val btnBack = TextView(context).apply {
                tag = "fw_youtube_back"
                text = "◀ Back"
                textSize = 11f
                setTextColor(Color.WHITE)
                setPadding(dp(context, 8f), dp(context, 4f), dp(context, 8f), dp(context, 4f))
                background = GradientDrawable().apply {
                    cornerRadius = dp(context, 8f).toFloat()
                    setColor(Color.parseColor("#1E293B"))
                }
                setOnClickListener { if (webView.canGoBack()) webView.goBack() }
            }

            val btnMode = TextView(context).apply {
                tag = "fw_youtube_mode_toggle"
                text = "Mode: Mobile"
                textSize = 11f
                setTextColor(accentColor)
                setPadding(dp(context, 10f), dp(context, 4f), dp(context, 10f), dp(context, 4f))
                background = GradientDrawable().apply {
                    cornerRadius = dp(context, 8f).toFloat()
                    setColor(Color.parseColor("#1E293B"))
                    setStroke(1, accentColor)
                }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    marginStart = dp(context, 6f)
                }
            }
            btnMode.setOnClickListener {
                isDesktopMode = !isDesktopMode
                if (isDesktopMode) {
                    btnMode.text = "Mode: Desktop"
                    webView.settings.userAgentString = desktopUa
                    webView.settings.useWideViewPort = true
                    webView.loadUrl("https://www.youtube.com")
                } else {
                    btnMode.text = "Mode: Mobile"
                    webView.settings.userAgentString = mobileUa
                    webView.loadUrl("https://m.youtube.com")
                }
            }

            val tvBgStatus = TextView(context).apply {
                text = "BG Audio Ready"
                textSize = 10f
                setTextColor(Color.parseColor("#94A3B8"))
                gravity = Gravity.END
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            topControls.addView(btnBack)
            topControls.addView(btnMode)
            topControls.addView(tvBgStatus)

            webView.loadUrl("https://m.youtube.com")

            container.addView(topControls)
            container.addView(webView)

            val cleanup: () -> Unit = {
                runCatching {
                    webView.stopLoading()
                    webView.destroy()
                }
            }
            container to cleanup
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
        ) { _ ->
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
        ) { _ ->
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
        ) { _ ->
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
        ) { _ ->
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

            val imageView = ImageView(context).apply {
                tag = "fw_viewer_image"
                scaleType = ImageView.ScaleType.FIT_CENTER
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
     * resizable FloatingWindowManager overlay with Desktop/Mobile User-Agent toggle and
     * zero-permission WebChromeClient file/image upload support.
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
        ) { _ ->
            val container = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(context, 4f), dp(context, 4f), dp(context, 4f), dp(context, 4f))
            }

            var isDesktopMode = false
            val mobileUa =
                "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
            val desktopUa =
                "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

            val webView = WebView(context).apply {
                tag = "fw_ai_webview_$aiModeId"
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.allowFileAccess = false
                settings.allowContentAccess = true
                settings.useWideViewPort = true
                settings.loadWithOverviewMode = true
                settings.builtInZoomControls = true
                settings.displayZoomControls = false
                settings.userAgentString = mobileUa
                runCatching {
                    val cm = CookieManager.getInstance()
                    cm.setAcceptCookie(true)
                    cm.setAcceptThirdPartyCookies(this, true)
                }
                webViewClient = WebViewClient()
                webChromeClient = object : WebChromeClient() {
                    override fun onShowFileChooser(
                        webView: WebView?,
                        filePathCallback: ValueCallback<Array<Uri>>?,
                        fileChooserParams: FileChooserParams?
                    ): Boolean {
                        if (filePathCallback == null) return false
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
                }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    0,
                    1f
                )
            }

            val topControls = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = dp(context, 4f)
                }
            }

            val btnBack = TextView(context).apply {
                tag = "fw_ai_back_$aiModeId"
                text = "◀ Back"
                textSize = 11f
                setTextColor(Color.WHITE)
                setPadding(dp(context, 8f), dp(context, 4f), dp(context, 8f), dp(context, 4f))
                background = GradientDrawable().apply {
                    cornerRadius = dp(context, 8f).toFloat()
                    setColor(Color.parseColor("#1E293B"))
                }
                setOnClickListener { if (webView.canGoBack()) webView.goBack() }
            }

            val btnReload = TextView(context).apply {
                tag = "fw_ai_reload_$aiModeId"
                text = "↻ Reload"
                textSize = 11f
                setTextColor(Color.WHITE)
                setPadding(dp(context, 8f), dp(context, 4f), dp(context, 8f), dp(context, 4f))
                background = GradientDrawable().apply {
                    cornerRadius = dp(context, 8f).toFloat()
                    setColor(Color.parseColor("#1E293B"))
                }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    marginStart = dp(context, 6f)
                }
                setOnClickListener { webView.reload() }
            }

            val btnMode = TextView(context).apply {
                tag = "fw_ai_mode_toggle_$aiModeId"
                text = "Mode: Mobile"
                textSize = 11f
                setTextColor(accentColor)
                setPadding(dp(context, 10f), dp(context, 4f), dp(context, 10f), dp(context, 4f))
                background = GradientDrawable().apply {
                    cornerRadius = dp(context, 8f).toFloat()
                    setColor(Color.parseColor("#1E293B"))
                    setStroke(1, accentColor)
                }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    marginStart = dp(context, 6f)
                }
            }
            btnMode.setOnClickListener {
                isDesktopMode = !isDesktopMode
                if (isDesktopMode) {
                    btnMode.text = "Mode: Desktop"
                    webView.settings.userAgentString = desktopUa
                } else {
                    btnMode.text = "Mode: Mobile"
                    webView.settings.userAgentString = mobileUa
                }
                webView.reload()
            }

            topControls.addView(btnBack)
            topControls.addView(btnReload)
            topControls.addView(btnMode)

            webView.loadUrl(targetUrl)

            container.addView(topControls)
            container.addView(webView)

            val cleanup: () -> Unit = {
                ViewerPickerActivity.pendingWebFileCallback?.onReceiveValue(null)
                ViewerPickerActivity.pendingWebFileCallback = null
                runCatching {
                    webView.stopLoading()
                    webView.destroy()
                }
            }
            container to cleanup
        }
    }
}
