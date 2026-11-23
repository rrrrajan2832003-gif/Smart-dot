package com.example

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.graphics.Typeface
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.TextPaint
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import kotlin.math.abs
import kotlin.math.roundToInt

class SmartDotAccessibilityService : AccessibilityService() {

    data class ScreenTextNode(
        val id: Int,
        val text: String,
        val boundsInScreen: Rect,
        val hasVisibleText: Boolean = true
    )

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d(TAG, "Accessibility Service Successfully Hooked and Enabled")
        instance = this
        syncUserPausedFromPrefs(this)
        updateBankingShieldForPackage(getActiveForegroundPackageName(packageName))
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (isUserPaused) {
            return
        }
        val eventType = event?.eventType ?: return
        if (eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED
        ) {
            val pkg = event.packageName?.toString()?.takeIf { it.isNotBlank() }
                ?: getActiveForegroundPackageName(packageName)
            updateBankingShieldForPackage(pkg)
        }
    }

    internal fun updateBankingShieldForPackage(pkg: String?) {
        if (isUserPaused) {
            if (isShieldActive) {
                isShieldActive = false
                onShieldStateChangedListener?.invoke(false)
            }
            return
        }
        if (pkg.isNullOrBlank()) return
        val normalized = pkg.trim()
        if (normalized == packageName || normalized == "com.android.systemui") {
            return
        }
        val shouldShield = isProtectedBankingPackage(normalized, packageName)
        if (shouldShield != isShieldActive) {
            isShieldActive = shouldShield
            onShieldStateChangedListener?.invoke(shouldShield)
        }
    }

    override fun onInterrupt() {
        // Mandatory callback for AccessibilityService
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Accessibility Service Disconnected")
        if (isShieldActive) {
            isShieldActive = false
            onShieldStateChangedListener?.invoke(false)
        }
        if (instance == this) {
            instance = null
        }
    }

    /**
     * Recursively scans all visible UI nodes in the active screen window using
     * AccessibilityNodeInfo (zero screenshots / MediaProjection) and returns every
     * visible text or contentDescription block with its exact screen bounds.
     */
    fun extractVisibleScreenTextNodes(): List<ScreenTextNode> {
        val rootsToScan = ArrayList<AccessibilityNodeInfo>()

        rootInActiveWindow?.let { activeRoot ->
            rootsToScan.add(activeRoot)
        }

        if (rootsToScan.isEmpty() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            runCatching {
                val windowList = windows
                if (windowList != null) {
                    for (window in windowList) {
                        val root = window.root ?: continue
                        val pkg = root.packageName?.toString()
                        if (pkg != packageName) {
                            rootsToScan.add(root)
                        } else {
                            @Suppress("DEPRECATION")
                            runCatching { root.recycle() }
                        }
                    }
                }
            }
        }

        if (rootsToScan.isEmpty()) {
            return emptyList()
        }

        val collected = ArrayList<RawNode>(32)
        val seen = HashSet<String>()

        for (root in rootsToScan) {
            try {
                scanNodeRecursive(
                    node = root,
                    collected = collected,
                    seen = seen,
                    recycleNode = false
                )
            } catch (e: Exception) {
                Log.w(TAG, "Failed scanning accessibility root window", e)
            } finally {
                @Suppress("DEPRECATION")
                runCatching { root.recycle() }
            }
        }

        return sortAndAssignIds(collected)
    }

    /**
     * Scans visible screen nodes and splits multi-word blocks/paragraphs into individual
     * word ScreenTextNodes with approximate screen Rect bounds for highlighter selection.
     */
    fun extractVisibleScreenWordNodes(): List<ScreenTextNode> {
        val rawBlocks = extractVisibleScreenTextNodes()
        val density = resources.displayMetrics.density
        return splitNodesIntoWordNodes(rawBlocks, density)
    }

    /**
     * Quietly captures the current screen frame in memory ONLY via API 30+ AccessibilityService.takeScreenshot()
     * (zero MediaProjection dialog, zero gallery/storage writes). Calls [onCaptured] with a software ARGB_8888 Bitmap
     * that the caller must recycle after ML Kit OCR finishes, or null if unsupported/failed.
     */
    fun captureScreenBitmapInMemory(onCaptured: (Bitmap?) -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            onCaptured(null)
            return
        }

        try {
            takeScreenshot(
                Display.DEFAULT_DISPLAY,
                mainExecutor,
                object : TakeScreenshotCallback {
                    override fun onSuccess(screenshot: ScreenshotResult) {
                        var softwareBitmap: Bitmap? = null
                        val hwBuffer = screenshot.hardwareBuffer
                        try {
                            val wrapped = Bitmap.wrapHardwareBuffer(hwBuffer, screenshot.colorSpace)
                            if (wrapped != null) {
                                softwareBitmap = wrapped.copy(Bitmap.Config.ARGB_8888, false)
                                wrapped.recycle()
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed copying hardware screenshot buffer to in-memory Bitmap", e)
                        } finally {
                            runCatching { hwBuffer.close() }
                        }
                        onCaptured(softwareBitmap)
                    }

                    override fun onFailure(errorCode: Int) {
                        Log.w(TAG, "AccessibilityService.takeScreenshot failed with errorCode=$errorCode")
                        onCaptured(null)
                    }
                }
            )
        } catch (e: Exception) {
            Log.w(TAG, "AccessibilityService.takeScreenshot threw exception", e)
            onCaptured(null)
        }
    }

    /**
     * Triggers Android 9+ (API 28) system Lock Screen via AccessibilityService global action.
     * Returns true if the global action was dispatched, false on unsupported OS versions or failure.
     */
    fun performLockScreen(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            return false
        }
        return try {
            performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
        } catch (e: Exception) {
            Log.w(TAG, "Lock screen global action failed", e)
            false
        }
    }

    internal var performGlobalActionTestHook: ((Int) -> Boolean)? = null
    internal var activeRootTestHook: AccessibilityNodeInfo? = null
    internal var accessibleRootsTestHook: (() -> List<AccessibilityNodeInfo>)? = null
    internal var splitModeCheckTestHook: (() -> Boolean)? = null
    internal var autoScrollTestHook: ((Int) -> Boolean)? = null
    internal var autoClickTestHook: ((Float, Float) -> Boolean)? = null

    /**
     * Dispatches a single-point tap gesture at screen coordinates (x, y) on API 24+ for Auto-Clicker.
     */
    fun performTapAt(x: Float, y: Float): Boolean {
        autoClickTestHook?.let { return it(x, y) }
        if (isUserPaused) return false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false

        val safeX = x.coerceAtLeast(0f)
        val safeY = y.coerceAtLeast(0f)
        val path = Path().apply {
            moveTo(safeX, safeY)
            lineTo(safeX + 0.5f, safeY + 0.5f)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, 40L))
            .build()

        return runCatching {
            dispatchGesture(gesture, null, null)
        }.getOrDefault(false)
    }

    /**
     * Dispatches a smooth vertical swipe upward (scroll down the feed) on API 24+.
     * Speed multiplier (1x / 2x / 3x) tunes swipe duration & stroke travel.
     */
    fun performAutoScrollStep(speedMultiplier: Int = 1): Boolean {
        autoScrollTestHook?.let { return it(speedMultiplier) }
        if (isUserPaused) return false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false

        val dm = resources.displayMetrics
        val w = dm.widthPixels.coerceAtLeast(360)
        val h = dm.heightPixels.coerceAtLeast(640)
        val centerX = w * 0.5f
        val startY = when {
            speedMultiplier >= 3 -> h * 0.78f
            speedMultiplier == 2 -> h * 0.75f
            else -> h * 0.72f
        }
        val endY = when {
            speedMultiplier >= 3 -> h * 0.22f
            speedMultiplier == 2 -> h * 0.25f
            else -> h * 0.28f
        }
        val durationMs = when {
            speedMultiplier >= 3 -> 220L
            speedMultiplier == 2 -> 320L
            else -> 440L
        }

        val path = Path().apply {
            moveTo(centerX, startY)
            lineTo(centerX, endY)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, durationMs))
            .build()

        return runCatching {
            dispatchGesture(gesture, null, null)
        }.getOrDefault(false)
    }

    private fun dispatchGlobalAction(action: Int): Boolean {
        performGlobalActionTestHook?.let { return it(action) }
        return runCatching { performGlobalAction(action) }.getOrDefault(false)
    }

    internal fun dispatchScreenshotAction(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return false
        return dispatchGlobalAction(GLOBAL_ACTION_TAKE_SCREENSHOT)
    }

    private fun safeRootInActiveWindow(): AccessibilityNodeInfo? {
        return activeRootTestHook ?: runCatching { rootInActiveWindow }.getOrNull()
    }

    /**
     * Checks whether the screen is already in split-screen mode by inspecting active
     * AccessibilityWindowInfo entries (2+ app windows or a split-screen divider window).
     */
    internal fun isScreenAlreadyInSplitMode(): Boolean {
        splitModeCheckTestHook?.let { return it() }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return false
        val windowList = runCatching { windows }.getOrNull().orEmpty()
        var appWindowCount = 0
        for (win in windowList) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N &&
                win.type == AccessibilityWindowInfo.TYPE_SPLIT_SCREEN_DIVIDER
            ) {
                return true
            }
            if (win.type == AccessibilityWindowInfo.TYPE_APPLICATION) {
                val pkg = runCatching { win.root?.packageName?.toString() }.getOrNull()
                if (pkg != packageName) {
                    appWindowCount++
                }
            }
        }
        return appWindowCount >= 2
    }

    internal fun dispatchPowerDialogAction(): Boolean {
        return dispatchGlobalAction(GLOBAL_ACTION_POWER_DIALOG)
    }

    /**
     * Direct 1-Tap Split Screen (with Realme UI / ColorOS Recents auto-clicker):
     * 1) Dispatches GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN immediately (opening Recents if needed).
     * 2) Inspects rootInActiveWindow + active windows at 150ms and 350ms: if not already split,
     *    locates "Split screen" / "विभाजित स्क्रीन" / "Split" (or expands the 3-dot card menu)
     *    and performs ACTION_CLICK automatically with zero manual toast/prompt.
     */
    @JvmOverloads
    fun performSplitScreen(
        handler: Handler = Handler(Looper.getMainLooper()),
        onCompleted: ((Boolean) -> Unit)? = null
    ): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            onCompleted?.invoke(false)
            return false
        }

        val toggled = dispatchGlobalAction(GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN)
        if (!toggled) {
            dispatchGlobalAction(GLOBAL_ACTION_RECENTS)
        }

        var finished = false
        fun finish(result: Boolean) {
            if (!finished) {
                finished = true
                onCompleted?.invoke(result)
            }
        }

        fun tryInspectAndClickSplit(isFinalAttempt: Boolean) {
            if (finished) return
            if (isScreenAlreadyInSplitMode()) {
                finish(true)
                return
            }

            val roots = collectAccessibleRoots()
            val directMatch = findSplitScreenOptionInRoots(roots)
            if (directMatch != null) {
                val clicked = runCatching {
                    directMatch.clickableNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                }.getOrDefault(false)
                if (clicked || isFinalAttempt) {
                    finish(clicked || isScreenAlreadyInSplitMode())
                    return
                }
            }

            // Some Realme UI / ColorOS Recents layouts tuck "Split screen" inside the card's 3-dot menu
            val recentsMenuMatch = QuickTileAutomation.findMatchingTileInRoots(
                roots = filterSystemOrLauncherRoots(roots),
                keywords = listOf("more options", "options", "menu", "more", "अधिक")
            )
            if (recentsMenuMatch != null) {
                val menuOpened = runCatching {
                    recentsMenuMatch.clickableNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                }.getOrDefault(false)
                if (menuOpened) {
                    handler.postDelayed({
                        if (finished) return@postDelayed
                        val subRoots = collectAccessibleRoots()
                        val nestedSplitMatch = findSplitScreenOptionInRoots(subRoots)
                        val clicked = if (nestedSplitMatch != null) {
                            runCatching {
                                nestedSplitMatch.clickableNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                            }.getOrDefault(false)
                        } else {
                            false
                        }
                        if (clicked || isScreenAlreadyInSplitMode() || isFinalAttempt) {
                            finish(clicked || isScreenAlreadyInSplitMode())
                        }
                    }, 120L)
                    return
                }
            }

            if (isFinalAttempt) {
                finish(toggled && isScreenAlreadyInSplitMode())
            }
        }

        handler.postDelayed({ tryInspectAndClickSplit(isFinalAttempt = false) }, 150L)
        handler.postDelayed({ tryInspectAndClickSplit(isFinalAttempt = true) }, 350L)

        return toggled
    }

    private fun filterSystemOrLauncherRoots(roots: List<AccessibilityNodeInfo>): List<AccessibilityNodeInfo> {
        val systemRoots = roots.filter { root ->
            val pkg = root.packageName?.toString()?.lowercase().orEmpty()
            pkg.contains("systemui") || pkg.contains("launcher") || pkg.contains("recents")
        }
        return systemRoots.ifEmpty { roots }
    }

    internal fun findSplitScreenOptionInRoots(
        roots: List<AccessibilityNodeInfo>
    ): QuickTileAutomation.TileMatch? {
        val prioritizedRoots = filterSystemOrLauncherRoots(roots)
        return QuickTileAutomation.findMatchingTileInRoots(
            roots = prioritizedRoots,
            keywords = listOf("split screen", "विभाजित स्क्रीन", "floating window", "split")
        )
    }

    /**
     * Collects AccessibilityNodeInfo roots across the active window and system overlay/panel windows.
     */
    fun collectAccessibleRoots(): List<AccessibilityNodeInfo> {
        accessibleRootsTestHook?.let { return it() }
        val roots = ArrayList<AccessibilityNodeInfo>()
        safeRootInActiveWindow()?.let { roots.add(it) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            runCatching { windows }?.getOrNull()?.forEach { win ->
                val r = runCatching { win.root }.getOrNull()
                if (r != null && roots.none { it == r }) {
                    roots.add(r)
                }
            }
        }
        return roots
    }

    /**
     * Automated 1-Tap Quick-Tile Clicker: opens Quick Settings, finds the tile or switch
     * matching [target], clicks it via ACTION_CLICK, and dismisses the shade automatically.
     */
    fun performQuickTileToggle(
        target: QuickTileAutomation.QuickTileTarget,
        onCompleted: (toggled: Boolean) -> Unit
    ) {
        QuickTileAutomation.automateQuickSettingsToggle(
            target = target,
            handler = Handler(Looper.getMainLooper()),
            performGlobalActionFn = { action -> dispatchGlobalAction(action) },
            rootsProvider = { collectAccessibleRoots() },
            onResult = onCompleted
        )
    }

    /**
     * Detects the package name of the currently active foreground app, skipping SmartDot's own
     * overlay package and Android SystemUI windows.
     */
    fun getActiveForegroundPackageName(excludedPackage: String = packageName): String? {
        val activePkg = runCatching { safeRootInActiveWindow()?.packageName?.toString() }.getOrNull()
        if (!activePkg.isNullOrBlank() &&
            activePkg != excludedPackage &&
            activePkg != "com.android.systemui"
        ) {
            return activePkg
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            val windowList = runCatching { windows }.getOrNull().orEmpty()
            for (win in windowList) {
                if (win.type != AccessibilityWindowInfo.TYPE_APPLICATION) continue
                val pkg = runCatching { win.root?.packageName?.toString() }.getOrNull()
                if (!pkg.isNullOrBlank() &&
                    pkg != excludedPackage &&
                    pkg != "com.android.systemui"
                ) {
                    return pkg
                }
            }
        }
        return null
    }

    companion object {
        private const val TAG = "SmartDotAccessibility"
        private const val ROW_SORT_TOLERANCE_PX = 24
        private const val MAX_SCAN_DEPTH = 48
        const val PREF_USER_PAUSED = "a11y_user_paused"

        private val EXACT_PAYMENT_PACKAGES = setOf(
            "com.phonepe.app",
            "com.google.android.apps.nbu.paisa.user",
            "net.one97.paytm",
            "in.org.npci.upiapp",
            "com.dreamplug.androidapp",
            "in.amazon.mShop.android.shopping"
        )

        private val EXCLUDED_NON_BANKING_PACKAGES = setOf(
            "com.android.systemui",
            "com.android.chrome",
            "com.google.android.googlequicksearchbox",
            "com.android.vending",
            "com.android.settings"
        )

        @Volatile
        var instance: SmartDotAccessibilityService? = null
            private set

        @Volatile
        var isUserPaused: Boolean = false
            private set

        @Volatile
        var isShieldActive: Boolean = false
            internal set

        @Volatile
        var onShieldStateChangedListener: ((Boolean) -> Unit)? = null

        fun syncUserPausedFromPrefs(context: Context): Boolean {
            val prefs = context.getSharedPreferences("smart_dot_prefs", Context.MODE_PRIVATE)
            val paused = prefs.getBoolean(PREF_USER_PAUSED, false)
            isUserPaused = paused
            if (paused && isShieldActive) {
                isShieldActive = false
                onShieldStateChangedListener?.invoke(false)
            }
            return paused
        }

        fun setUserPaused(context: Context, paused: Boolean) {
            context.getSharedPreferences("smart_dot_prefs", Context.MODE_PRIVATE)
                .edit()
                .putBoolean(PREF_USER_PAUSED, paused)
                .apply()
            isUserPaused = paused
            if (paused) {
                if (isShieldActive) {
                    isShieldActive = false
                    onShieldStateChangedListener?.invoke(false)
                }
            } else {
                instance?.let { svc ->
                    svc.updateBankingShieldForPackage(svc.getActiveForegroundPackageName(svc.packageName))
                }
            }
        }

        fun isProtectedBankingPackage(pkg: String?, ownPackage: String = "com.example"): Boolean {
            if (pkg.isNullOrBlank()) return false
            val lower = pkg.lowercase().trim()
            if (lower == ownPackage.lowercase() || lower in EXCLUDED_NON_BANKING_PACKAGES) {
                return false
            }
            if (lower in EXACT_PAYMENT_PACKAGES) {
                return true
            }
            return lower.contains("bank") ||
                lower.contains("upi") ||
                lower.contains(".pay") ||
                lower.contains("pay.") ||
                lower.contains("paisa") ||
                lower.contains("bhim") ||
                lower.contains("wallet")
        }

        internal fun setInstanceForTest(service: SmartDotAccessibilityService?) {
            instance = service
        }

        fun isEnabled(): Boolean {
            return instance != null && !isUserPaused
        }

        /**
         * Pure helper usable both from the live AccessibilityService and unit tests.
         * Traverses children before containers (post-order) so exact leaf bounds win over
         * container bounds that repeat child text.
         */
        fun extractTextNodesFromRoot(
            root: AccessibilityNodeInfo?,
            requireVisibleToUser: Boolean = true
        ): List<ScreenTextNode> {
            if (root == null) return emptyList()
            val collected = ArrayList<RawNode>(32)
            val seen = HashSet<String>()
            scanNodeRecursive(
                node = root,
                collected = collected,
                seen = seen,
                recycleNode = false,
                requireVisibleToUser = requireVisibleToUser
            )
            return sortAndAssignIds(collected)
        }

        private data class RawNode(
            val text: String,
            val rect: Rect,
            val hasVisibleText: Boolean
        )

        private fun scanNodeRecursive(
            node: AccessibilityNodeInfo,
            collected: MutableList<RawNode>,
            seen: MutableSet<String>,
            recycleNode: Boolean,
            requireVisibleToUser: Boolean = true,
            depth: Int = 0
        ) {
            try {
                if (depth > MAX_SCAN_DEPTH) return
                if (requireVisibleToUser && !node.isVisibleToUser) return

                val childCount = node.childCount
                for (i in 0 until childCount) {
                    val child = runCatching { node.getChild(i) }.getOrNull() ?: continue
                    scanNodeRecursive(
                        node = child,
                        collected = collected,
                        seen = seen,
                        recycleNode = true,
                        requireVisibleToUser = requireVisibleToUser,
                        depth = depth + 1
                    )
                }

                val explicitText = node.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }
                val descText = node.contentDescription?.toString()?.trim()?.takeIf { it.isNotEmpty() }
                val extractedText = explicitText ?: descText ?: return
                val hasVisibleText = explicitText != null

                val rect = Rect()
                node.getBoundsInScreen(rect)
                if (rect.width() <= 0 || rect.height() <= 0) return
                if (rect.right <= 0 || rect.bottom <= 0) return

                // Deduplicate identical text inside overlapping bounds (e.g., parent wrapper echoing child text).
                val dedupKey = "${extractedText.lowercase()}|${rect.left / 4}|${rect.top / 4}|${rect.right / 4}|${rect.bottom / 4}"
                if (!seen.add(dedupKey)) return

                val hasTighterChildWithSameText = collected.any { existing ->
                    existing.text == extractedText && rect.contains(existing.rect)
                }
                if (hasTighterChildWithSameText) return

                collected.add(RawNode(extractedText, Rect(rect), hasVisibleText))
            } finally {
                if (recycleNode) {
                    @Suppress("DEPRECATION")
                    runCatching { node.recycle() }
                }
            }
        }

        /**
         * Splits multi-word block/paragraph nodes into individual word nodes and computes each
         * word's approximate screen Rect via TextPaint.measureText() and line-wrap estimation.
         */
        fun splitNodesIntoWordNodes(
            nodes: List<ScreenTextNode>,
            density: Float = 2.75f
        ): List<ScreenTextNode> {
            if (nodes.isEmpty()) return emptyList()

            val safeDensity = density.coerceAtLeast(1f)
            val wordRegex = Regex("\\S+")
            val paint = TextPaint().apply { typeface = Typeface.DEFAULT }
            val result = ArrayList<RawNode>(nodes.size * 3)

            for (node in nodes) {
                val trimmed = node.text.trim()
                if (trimmed.isEmpty()) continue

                val bounds = node.boundsInScreen
                if (bounds.width() <= 0 || bounds.height() <= 0) continue

                val hardLines = trimmed.split(Regex("\\r?\\n"))
                    .map { line -> wordRegex.findAll(line).map { it.value }.toList() }
                    .filter { it.isNotEmpty() }

                val totalWords = hardLines.sumOf { it.size }
                if (totalWords == 0) continue
                if (totalWords == 1) {
                    result.add(RawNode(hardLines[0][0], Rect(bounds), node.hasVisibleText))
                    continue
                }

                val boxW = bounds.width().toFloat().coerceAtLeast(1f)
                val boxH = bounds.height().toFloat().coerceAtLeast(1f)

                val visualLines = wrapHardLinesIntoVisualLines(
                    hardLines = hardLines,
                    boxW = boxW,
                    boxH = boxH,
                    density = safeDensity,
                    paint = paint
                )

                val lineCount = visualLines.size.coerceAtLeast(1)
                val spaceW = measureTextCompat(paint, " ").coerceAtLeast(1f)
                val lineMeasuredWidths = visualLines.map { wordsOnLine ->
                    val wordsSum = wordsOnLine.sumOf { measureTextCompat(paint, it).toDouble() }.toFloat()
                    wordsSum + spaceW * (wordsOnLine.size - 1).coerceAtLeast(0)
                }

                // Determine consistent horizontal scale so full wrapped lines fill boxW while
                // shorter trailing paragraph lines preserve left-aligned proportional word widths.
                val fullLineWidths = if (lineCount > 1) {
                    lineMeasuredWidths.dropLast(1).filter { it > 0f }
                } else {
                    emptyList()
                }
                val paragraphScaleX = if (fullLineWidths.isNotEmpty()) {
                    val avgFullW = fullLineWidths.average().toFloat().coerceAtLeast(1f)
                    (boxW / avgFullW).coerceIn(0.8f, 1.45f)
                } else {
                    1f
                }

                for (lineIdx in visualLines.indices) {
                    val wordsOnLine = visualLines[lineIdx]
                    if (wordsOnLine.isEmpty()) continue

                    val lineTop = (bounds.top + (lineIdx * boxH / lineCount)).roundToInt()
                    val lineBottom = (bounds.top + ((lineIdx + 1) * boxH / lineCount)).roundToInt()
                        .coerceAtLeast(lineTop + 1)

                    val measuredLineW = lineMeasuredWidths[lineIdx].coerceAtLeast(1f)
                    val lineScaleX = when {
                        lineCount == 1 -> {
                            // If text occupies >= 45% of boxW (tight container or heading), map
                            // proportionally across boxW; if short text in wide match_parent box,
                            // keep natural measured positions unless it overflows boxW.
                            if (measuredLineW >= boxW * 0.45f || measuredLineW > boxW) {
                                boxW / measuredLineW
                            } else {
                                1f
                            }
                        }
                        lineIdx < lineCount - 1 -> {
                            if (measuredLineW > boxW || measuredLineW >= boxW * 0.6f) {
                                boxW / measuredLineW
                            } else {
                                paragraphScaleX
                            }
                        }
                        else -> {
                            if (measuredLineW * paragraphScaleX > boxW) {
                                boxW / measuredLineW
                            } else {
                                paragraphScaleX
                            }
                        }
                    }

                    var cursorX = bounds.left.toFloat()
                    for (wordIdx in wordsOnLine.indices) {
                        val word = wordsOnLine[wordIdx]
                        val wordW = (measureTextCompat(paint, word).coerceAtLeast(1f) * lineScaleX)
                        val wLeft = cursorX.roundToInt().coerceIn(bounds.left, bounds.right - 1)
                        val wRight = (cursorX + wordW).roundToInt()
                            .coerceIn(wLeft + 1, bounds.right)

                        result.add(
                            RawNode(
                                text = word,
                                rect = Rect(wLeft, lineTop, wRight, lineBottom),
                                hasVisibleText = node.hasVisibleText
                            )
                        )
                        cursorX += wordW + (spaceW * lineScaleX)
                    }
                }
            }

            return sortAndAssignIds(result)
        }

        private fun measureTextCompat(paint: TextPaint, text: String): Float {
            val measured = paint.measureText(text)
            if (paint.textSize >= 10f && paint.measureText("MMMM") < paint.textSize) {
                return text.length * paint.textSize * 0.54f
            }
            return measured
        }

        private fun wrapHardLinesIntoVisualLines(
            hardLines: List<List<String>>,
            boxW: Float,
            boxH: Float,
            density: Float,
            paint: TextPaint
        ): List<List<String>> {
            if (hardLines.size == 1) {
                val singleParagraphWords = hardLines[0]
                val joined = singleParagraphWords.joinToString(" ")
                val singleProbePx = (boxH * 0.56f).coerceIn(12f, 24f * density)
                paint.textSize = singleProbePx
                val fullMeasuredAtProbe = measureTextCompat(paint, joined)

                // Keep single-line widgets on 1 visual line when box height cannot comfortably
                // hold 2 rows or all text fits within boxW at readable size.
                val minWrapHeightPx = (26f * density).coerceAtLeast(24f)
                if (boxH <= minWrapHeightPx || fullMeasuredAtProbe <= boxW * 1.05f) {
                    return listOf(singleParagraphWords)
                }

                // Multi-line paragraph without explicit '\n': search for a textSize whose
                // greedy wrapped height best matches boxH.
                val minSize = (10f * density).coerceIn(10f, (boxH * 0.42f).coerceAtLeast(10f))
                val maxSize = (22f * density).coerceIn(minSize, (boxH * 0.48f).coerceAtLeast(minSize))

                var bestSize = minSize
                var bestScore = Float.MAX_VALUE
                var candidate = maxSize
                val step = (1.5f * density).coerceAtLeast(1.5f)
                while (candidate >= minSize) {
                    paint.textSize = candidate
                    val wrapped = greedyWrapParagraph(singleParagraphWords, boxW, paint)
                    val estHeight = wrapped.size * candidate * 1.28f
                    val diff = abs(estHeight - boxH)
                    if (estHeight <= boxH * 1.18f && diff < bestScore) {
                        bestScore = diff
                        bestSize = candidate
                    }
                    candidate -= step
                }
                paint.textSize = bestSize
                return greedyWrapParagraph(singleParagraphWords, boxW, paint)
            }

            // Explicit '\n' lines: estimate per-line textSize from boxH / hardLines.size and wrap any long lines.
            val estPerLinePx = ((boxH / hardLines.size) * 0.72f)
                .coerceIn(10f, (22f * density).coerceAtLeast(14f))
            paint.textSize = estPerLinePx
            val allVisual = ArrayList<List<String>>(hardLines.size)
            for (lineWords in hardLines) {
                allVisual.addAll(greedyWrapParagraph(lineWords, boxW, paint))
            }
            return allVisual
        }

        private fun greedyWrapParagraph(
            words: List<String>,
            maxWidth: Float,
            paint: TextPaint
        ): List<List<String>> {
            val lines = ArrayList<List<String>>()
            var currentLine = ArrayList<String>()
            val spaceW = measureTextCompat(paint, " ")
            var currentW = 0f

            for (word in words) {
                val wordW = measureTextCompat(paint, word)
                if (currentLine.isEmpty()) {
                    currentLine.add(word)
                    currentW = wordW
                } else if (currentW + spaceW + wordW <= maxWidth) {
                    currentLine.add(word)
                    currentW += spaceW + wordW
                } else {
                    lines.add(currentLine)
                    currentLine = ArrayList()
                    currentLine.add(word)
                    currentW = wordW
                }
            }
            if (currentLine.isNotEmpty()) {
                lines.add(currentLine)
            }
            return lines
        }

        private fun sortAndAssignIds(collected: List<RawNode>): List<ScreenTextNode> {
            val sorted = collected.sortedWith { a, b ->
                val rowDiff = a.rect.top - b.rect.top
                if (abs(rowDiff) > ROW_SORT_TOLERANCE_PX) {
                    rowDiff
                } else {
                    a.rect.left - b.rect.left
                }
            }
            return sorted.mapIndexed { index, raw ->
                ScreenTextNode(
                    id = index,
                    text = raw.text,
                    boundsInScreen = raw.rect,
                    hasVisibleText = raw.hasVisibleText
                )
            }
        }
    }
}
