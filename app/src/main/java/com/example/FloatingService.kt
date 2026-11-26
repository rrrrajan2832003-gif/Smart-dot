package com.example

import android.app.Activity
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.content.BroadcastReceiver
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.hardware.Sensor
import android.hardware.SensorManager
import android.content.SharedPreferences
import android.graphics.drawable.Drawable
import android.view.ViewGroup
import androidx.viewpager.widget.ViewPager
import androidx.viewpager.widget.PagerAdapter
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.OvershootInterpolator
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.example.database.ClipEntry
import com.example.database.NoteEntry
import com.example.database.SmartDotDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import android.content.ActivityNotFoundException
import android.media.AudioManager
import android.provider.AlarmClock
import android.speech.RecognizerIntent
import android.view.KeyEvent
import android.view.VelocityTracker
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

class FloatingService : Service() {

    private lateinit var prefs: SharedPreferences
    private lateinit var windowManager: WindowManager
    private lateinit var floatingView: View
    private lateinit var imgFloatingDot: ImageView
    private lateinit var params: WindowManager.LayoutParams

    private var lastX = 0
    private var lastY = 0
    private var density = 1f
    private var initialX = 0
    private var initialY = 0
    private var startRawX = 0f
    private var startRawY = 0f
    private var isDragging = false
    private val dragDistanceThreshold = 10L
    private val clickTimeThreshold = 300L
    private var startTime = 0L

    // Popup management
    private var activePopupWindow: PopupWindow? = null
    private var lastDismissTime = 0L
    private var isDialogShowing = false
    private var isPopupAlignedToEdge = false

    // Sidebar variables
    private var sidebarView: View? = null
    private var sidebarPillView: View? = null
    private var sidebarParams: WindowManager.LayoutParams? = null
    private var activeSidebarEdge: String? = null
    private var activeSidebarHeightPx: Int = 0
    private var activeSidebarOpacityPercent: Int = 70
    private var activeSidebarAutoHide: Boolean = false
    private var edgeGestureDetector: EdgeGestureDetector? = null

    // Curated standard languages for Translate
    private val translateLanguages = ScreenTranslateHelper.SUPPORTED_LANGUAGES

    // Coroutine Scope for Room IO operations
    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())

    // Clipboard listener to log newly copied items into local history
    private lateinit var clipboardManager: ClipboardManager
    private val clipListener = ClipboardManager.OnPrimaryClipChangedListener {
        saveClipboardData()
    }

    private var isFlashlightOn = false
    private var isUpdateReceiverRegistered = false
    private var isFloatingViewAdded = false
    private var popupSessionJob: Job? = null

    // Isolated controllers for Screen Text Copy and Screen Translate (lazily created when triggered)
    private var screenTextCopyController: ScreenTextCopyController? = null
    private var screenTranslateController: ScreenTranslateController? = null

    // Screen Recorder & Stop Recording Edge Pill
    private var screenRecorderManager: ScreenRecorderManager? = null
    private var stopRecordingPillView: View? = null
    private var lastRecordWithAudio: Boolean = false

    // Multi-window floating mini-apps manager
    private var floatingWindowManager: FloatingWindowManager? = null
    private var proToolsOverlayManager: ProToolsOverlayManager? = null
    private var isShieldHidden = false

    // Accelerometer Shake-to-Torch & Back-Tap Gesture detectors (zero vibration)
    private var sensorManager: SensorManager? = null
    private var shakeDetector: ShakeDetector? = null
    private var isShakeListenerRegistered = false
    private var backTapDetector: BackTapDetector? = null
    private var isBackTapListenerRegistered = false
    private var backTapWakeLock: PowerManager.WakeLock? = null
    private var isScreenStateReceiverRegistered = false

    private val screenStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_ON -> updateBackTapWakeLock(shouldHold = true)
                Intent.ACTION_SCREEN_OFF -> updateBackTapWakeLock(shouldHold = false)
            }
        }
    }

    companion object {
        const val ACTION_UPDATE_SETTINGS = "com.example.UPDATE_SETTINGS"
        const val ACTION_SCREEN_RECORD_RESULT = "com.example.ACTION_SCREEN_RECORD_RESULT"
        const val EXTRA_RECORD_RESULT_CODE = "extra_record_result_code"
        const val EXTRA_RECORD_DATA = "extra_record_data"
        const val EXTRA_RECORD_WITH_AUDIO = "extra_record_with_audio"
        const val PREF_SHAKE_TORCH_ENABLED = "shake_torch_enabled"
        const val PREF_SHAKE_SENSITIVITY = "shake_sensitivity_threshold"
        const val PREF_BACK_TAP_DATA_ENABLED = "back_tap_data_enabled"
        const val PREF_BACK_TAP_SENSITIVITY = "back_tap_sensitivity_threshold"
        const val PREF_HIDE_SHORTCUT_LABELS = "hide_shortcut_labels"
        var isRunning = false
            private set
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private val updateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_UPDATE_SETTINGS) {
                if (!::windowManager.isInitialized) {
                    windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
                }
                applyCustomizables()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences("smart_dot_prefs", Context.MODE_PRIVATE)
        density = resources.displayMetrics.density
        clipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboardManager.addPrimaryClipChangedListener(clipListener)
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        if (!isScreenStateReceiverRegistered) {
            val screenFilter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
            }
            runCatching {
                ContextCompat.registerReceiver(
                    this,
                    screenStateReceiver,
                    screenFilter,
                    ContextCompat.RECEIVER_EXPORTED
                )
                isScreenStateReceiverRegistered = true
            }
        }
        syncShakeTorchListener()
        syncBackTapListener()
        SmartDotAccessibilityService.syncUserPausedFromPrefs(this)
        isShieldHidden = SmartDotAccessibilityService.isShieldActive
        SmartDotAccessibilityService.onShieldStateChangedListener = { shieldActive ->
            Handler(Looper.getMainLooper()).post {
                setBankingShieldHidden(shieldActive)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createNotificationChannel()
        val isScreenRecordResult = intent?.action == ACTION_SCREEN_RECORD_RESULT
        val recordResultCode = intent?.getIntExtra(EXTRA_RECORD_RESULT_CODE, Activity.RESULT_CANCELED) ?: Activity.RESULT_CANCELED
        val recordData: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra(EXTRA_RECORD_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra(EXTRA_RECORD_DATA)
        }
        val canStartProjectionFgs = isScreenRecordResult &&
            recordResultCode == Activity.RESULT_OK &&
            recordData != null

        try {
            val fgsType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                if (canStartProjectionFgs) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                } else {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                }
            } else {
                0
            }
            ServiceCompat.startForeground(this, 1001, getForegroundNotification(), fgsType)
        } catch (e: Exception) {
            Log.e("FloatingService", "Failed starting foreground service", e)
            isRunning = false
            stopSelf()
            return START_NOT_STICKY
        }

        if (isScreenRecordResult) {
            if (canStartProjectionFgs && recordData != null) {
                val withAudio = intent?.getBooleanExtra(EXTRA_RECORD_WITH_AUDIO, false) == true
                startScreenRecordingSession(recordResultCode, recordData, withAudio)
            } else {
                Toast.makeText(this, getString(R.string.screen_record_failed), Toast.LENGTH_SHORT).show()
            }
        }

        if (!Settings.canDrawOverlays(this)) {
            Log.w("FloatingService", "SYSTEM_ALERT_WINDOW permission not granted; stopping overlay service.")
            isRunning = false
            stopSelf()
            return START_NOT_STICKY
        }

        if (!::windowManager.isInitialized) {
            windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        }

        if (!isUpdateReceiverRegistered) {
            val filter = IntentFilter(ACTION_UPDATE_SETTINGS)
            try {
                ContextCompat.registerReceiver(
                    this,
                    updateReceiver,
                    filter,
                    ContextCompat.RECEIVER_EXPORTED
                )
                isUpdateReceiverRegistered = true
            } catch (e: Exception) {
                Log.e("FloatingService", "Failed registering settings updateReceiver", e)
            }
        }

        val dotEnabled = prefs.getBoolean("floating_dot_enabled", true)
        val sidebarEnabled = prefs.getBoolean("sidebar_enabled", true)
        if (!dotEnabled && !sidebarEnabled) {
            Log.i("FloatingService", "Both Floating Dot and Edge Sidebar are OFF; stopping service.")
            isRunning = false
            stopSelf()
            return START_NOT_STICKY
        }

        val dotMissing = dotEnabled && (!::floatingView.isInitialized || (!isFloatingViewAdded && !floatingView.isAttachedToWindow))
        val sidebarMissing = sidebarEnabled && sidebarView == null
        val needsOverlaySetup = !isRunning || dotMissing || sidebarMissing

        if (needsOverlaySetup) {
            val attached = setupFloatingWidget()
            isRunning = attached
            if (!attached) {
                Log.e("FloatingService", "Overlay setup failed; stopping service so state stays accurate.")
                stopSelf()
                return START_NOT_STICKY
            }
        } else {
            applyCustomizables()
        }

        val currentPaused = SmartDotAccessibilityService.syncUserPausedFromPrefs(this)
        setBankingShieldHidden(if (currentPaused) false else SmartDotAccessibilityService.isShieldActive)

        return START_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "smart_dot_channel",
                "Floating Shortcuts Active Widget",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps the Floating Shortcuts overlay widget running smoothly in the background."
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun getForegroundNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        )

        return NotificationCompat.Builder(this, "smart_dot_channel")
            .setContentTitle("Floating Shortcuts Active")
            .setContentText("Tap to configure themes and sidebar utility layers.")
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }

    private fun setupFloatingWidget(): Boolean {
        val dotEnabled = prefs.getBoolean("floating_dot_enabled", true)
        val dotAdded = if (dotEnabled) {
            ensureFloatingDotAttached()
        } else {
            detachFloatingDot()
            false
        }
        setupSidebarWidget()
        return dotAdded || sidebarView != null
    }

    private fun detachFloatingDot() {
        if (::floatingView.isInitialized && (isFloatingViewAdded || floatingView.isAttachedToWindow)) {
            try {
                windowManager.removeView(floatingView)
            } catch (_: Exception) {}
        }
        isFloatingViewAdded = false
    }

    private fun ensureFloatingDotAttached(): Boolean {
        if (::floatingView.isInitialized && (isFloatingViewAdded || floatingView.isAttachedToWindow)) {
            val dotVis = if (isShieldHidden) View.GONE else View.VISIBLE
            floatingView.visibility = dotVis
            if (::imgFloatingDot.isInitialized) {
                imgFloatingDot.visibility = dotVis
            }
            applyDotCustomizables()
            return true
        }

        floatingView = LayoutInflater.from(this).inflate(R.layout.floating_dot_layout, null)
        imgFloatingDot = floatingView.findViewById(R.id.img_floating_dot)

        // Loading shared widget geometries
        lastX = prefs.getInt("last_x", 150)
        lastY = prefs.getInt("last_y", 350)

        params = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            )
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            )
        }

        params.gravity = Gravity.TOP or Gravity.START
        params.x = lastX
        params.y = lastY

        applyDotCustomizables()

        imgFloatingDot.setOnTouchListener(object : View.OnTouchListener {
            private var maxX = 0
            private var maxY = 0
            private var velocityTracker: VelocityTracker? = null

            override fun onTouch(v: View?, event: MotionEvent?): Boolean {
                if (event == null) return false
                val isPositionLocked = prefs.getBoolean("dot_position_locked", false)

                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        startTime = System.currentTimeMillis()
                        initialX = params.x
                        initialY = params.y
                        startRawX = event.rawX
                        startRawY = event.rawY
                        isDragging = false

                        velocityTracker?.recycle()
                        velocityTracker = VelocityTracker.obtain().also { tracker ->
                            val rawEvent = MotionEvent.obtain(event).apply {
                                setLocation(event.rawX, event.rawY)
                            }
                            tracker.addMovement(rawEvent)
                            rawEvent.recycle()
                        }

                        // Precalculate boundaries on touch down to avoid costly work during dragging (ACTION_MOVE)
                        val metrics = resources.displayMetrics
                        val dotWidth = floatingView.width.coerceAtLeast((56 * density).toInt())
                        val dotHeight = floatingView.height.coerceAtLeast((56 * density).toInt())
                        maxX = (metrics.widthPixels - dotWidth).coerceAtLeast(0)
                        maxY = (metrics.heightPixels - dotHeight).coerceAtLeast(0)
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        velocityTracker?.let { tracker ->
                            val rawEvent = MotionEvent.obtain(event).apply {
                                setLocation(event.rawX, event.rawY)
                            }
                            tracker.addMovement(rawEvent)
                            rawEvent.recycle()
                        }

                        val diffX = event.rawX - startRawX
                        val diffY = event.rawY - startRawY

                        if (!isDragging && (abs(diffX) > dragDistanceThreshold || abs(diffY) > dragDistanceThreshold)) {
                            if (activePopupWindow?.isShowing == true) {
                                activePopupWindow?.dismiss()
                            }
                            isDragging = true
                        }

                        // When Lock Position is ON, keep the Floating Dot fixed in place so swipes trigger gestures cleanly
                        if (!isPositionLocked && isDragging) {
                            params.x = (initialX + diffX.toInt()).coerceIn(0, maxX)
                            params.y = (initialY + diffY.toInt()).coerceIn(0, maxY)

                            try {
                                windowManager.updateViewLayout(floatingView, params)
                            } catch (e: Exception) {
                                // Suppress trace log spam in high-frequency update loops
                            }
                        }
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        val duration = System.currentTimeMillis() - startTime
                        val finalDiffX = event.rawX - startRawX
                        val finalDiffY = event.rawY - startRawY

                        var speed = 0f
                        velocityTracker?.let { tracker ->
                            val rawEvent = MotionEvent.obtain(event).apply {
                                setLocation(event.rawX, event.rawY)
                            }
                            tracker.addMovement(rawEvent)
                            rawEvent.recycle()
                            tracker.computeCurrentVelocity(1000)
                            speed = hypot(tracker.xVelocity, tracker.yVelocity)
                            tracker.recycle()
                        }
                        velocityTracker = null

                        if (isPositionLocked) {
                            val dotSwipeThreshold = prefs.getInt("sidebar_sensitivity", 25).coerceAtLeast(22) * density
                            val direction = SwipeGestureHelper.detectSwipeDirection(
                                dx = finalDiffX,
                                dy = finalDiffY,
                                velocityPxPerSec = speed,
                                minDeltaPx = dotSwipeThreshold,
                                inwardIsRight = false
                            )
                            if (direction != null) {
                                executeSwipeGesture(direction, isSidebarOrigin = false)
                            } else if (abs(finalDiffX) < dragDistanceThreshold && abs(finalDiffY) < dragDistanceThreshold && duration < clickTimeThreshold) {
                                handleDotClick()
                            }
                        } else {
                            if (!isDragging || (abs(finalDiffX) < dragDistanceThreshold && abs(finalDiffY) < dragDistanceThreshold && duration < clickTimeThreshold)) {
                                lastX = params.x.coerceIn(0, maxX)
                                lastY = params.y.coerceIn(0, maxY)
                                handleDotClick()
                            } else {
                                lastX = params.x.coerceIn(0, maxX)
                                lastY = params.y.coerceIn(0, maxY)
                                saveLocation(lastX, lastY)
                            }
                        }
                        return true
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        velocityTracker?.recycle()
                        velocityTracker = null
                        return true
                    }
                }
                return false
            }
        })

        return try {
            windowManager.addView(floatingView, params)
            isFloatingViewAdded = true
            true
        } catch (e: Exception) {
            Log.e("FloatingService", "Failed rendering floating dot overlay on display.", e)
            isFloatingViewAdded = false
            false
        }
    }

    private fun handleDotClick() {
        if (activePopupWindow?.isShowing == true) {
            activePopupWindow?.dismiss()
            return
        }

        val clickTime = System.currentTimeMillis()
        if (clickTime - lastDismissTime < 250) {
            return
        }

        showAssistantSidebar()
    }

    private fun getShortcutsPages(prefs: SharedPreferences): List<List<String>> {
        val defaultString = "home,back,recent_apps,screenshot,flashlight,wifi,bluetooth,open_app_drawer,settings,calculator,chrome"
        val legacy = prefs.getString("shortcuts_list", defaultString) ?: defaultString
        
        val cols = prefs.getInt("menu_grid_cols", 3).coerceIn(1, 5)
        val rows = prefs.getInt("menu_grid_rows", 3).coerceAtLeast(1)
        val pageSize = cols * rows

        // Split legacy flat shortcut layout with optional | character indicators
        val rawParts = if (legacy.contains("|")) {
            legacy.replace("|", ",").split(",")
        } else {
            legacy.split(",")
        }
        
        val flatList = ArrayList<String>()
        for (part in rawParts) {
            flatList.add(part.trim())
        }
        
        // Guarantee at least 3 pages
        val minPages = 3
        val requiredPagesCount = maxOf(minPages, (flatList.size + pageSize - 1) / pageSize)
        val totalRequiredSlots = requiredPagesCount * pageSize
        
        // Pad pages to have exactly physical pageSize dimensions
        while (flatList.size < totalRequiredSlots) {
            flatList.add("")
        }
        
        val chunked = flatList.take(totalRequiredSlots).chunked(pageSize)
        Log.i("FloatingService", "Shortcuts partitioning debug: totalCount=${flatList.size}, pageSize=$pageSize (cols=$cols, rows=$rows), pagesCount=${chunked.size}")
        return chunked
    }

    private fun saveShortcutsPages(prefs: SharedPreferences, pages: List<List<String>>) {
        val savedString = pages.map { it.joinToString(",") }.joinToString("|")
        prefs.edit().apply {
            putString("shortcuts_list_pages", savedString)
            putString("shortcuts_list", pages.flatten().joinToString(","))
            apply()
        }
    }

    private fun showShortcutSelectorDialog(onShortcutSelected: (String) -> Unit) {
        val dialog = android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Select Shortcut")
            .setCancelable(true)
        
        val actionIds = ACTION_LIST.map { it.id }.toTypedArray()
        val actionNames = ACTION_LIST.map { it.name }.toTypedArray()
        
        dialog.setItems(actionNames) { d, which ->
            val selectedId = actionIds[which]
            onShortcutSelected(selectedId)
            d.dismiss()
        }
        
        val alert = dialog.create()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            alert.window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
        } else {
            @Suppress("DEPRECATION")
            alert.window?.setType(WindowManager.LayoutParams.TYPE_PHONE)
        }
        alert.show()
    }

    private fun getSystemDrawable(attrId: Int): Drawable? {
        val attrs = intArrayOf(attrId)
        val ta = obtainStyledAttributes(attrs)
        val drawable = ta.getDrawable(0)
        ta.recycle()
        return drawable
    }

    private fun isTabTool(shortcutId: String): Boolean {
        return when (shortcutId) {
            "calculator", "translate", "clipboard", "notes", "open_app_drawer", "settings" -> true
            else -> false
        }
    }

    private fun showAssistantSidebar() {
        showAssistantSidebarCustom(false)
    }

    private fun showSidebarPanel() {
        showAssistantSidebarCustom(true)
    }

    private fun showAssistantSidebarCustom(alignToEdge: Boolean) {
        isPopupAlignedToEdge = alignToEdge
        Log.i("FloatingService", "Popup opened.")

        if (activePopupWindow?.isShowing == true) {
            try {
                activePopupWindow?.dismiss()
            } catch (_: Exception) {}
            activePopupWindow = null
        }
        popupSessionJob?.cancel()
        popupSessionJob = Job(serviceScope.coroutineContext[Job])

        val anchorToken: View? = if (alignToEdge) {
            sidebarView?.takeIf { it.isAttachedToWindow }
                ?: (if (::imgFloatingDot.isInitialized && imgFloatingDot.isAttachedToWindow) imgFloatingDot else null)
        } else {
            (if (::imgFloatingDot.isInitialized && imgFloatingDot.isAttachedToWindow) imgFloatingDot else null)
                ?: sidebarView?.takeIf { it.isAttachedToWindow }
        }
        if (anchorToken == null) {
            Log.w("FloatingService", "Cannot show sidebar popup: overlay anchor view is not attached.")
            return
        }

        try {
            val inflater = getSystemService(Context.LAYOUT_INFLATER_SERVICE) as LayoutInflater
            val popupView = inflater.inflate(R.layout.floating_popup_layout, null)

            val screenWidth = resources.displayMetrics.widthPixels
            val screenHeight = resources.displayMetrics.heightPixels
            val currentDensity = resources.displayMetrics.density

            val popupWidthDp = prefs.getInt("menu_width_dp", 280)
            val popupHeightDp = prefs.getInt("menu_height_dp", 460)

            val marginPx = (16 * currentDensity).toInt()
            val popupWidth = (popupWidthDp * currentDensity).toInt().coerceAtMost((screenWidth - marginPx).coerceAtLeast(100))
            val popupHeight = (popupHeightDp * currentDensity).toInt().coerceAtMost((screenHeight - marginPx).coerceAtLeast(100))

            val popupWindow = PopupWindow(
                popupView,
                popupWidth,
                popupHeight,
                true
            )

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                popupWindow.windowLayoutType = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                popupWindow.windowLayoutType = WindowManager.LayoutParams.TYPE_PHONE
            }

            popupWindow.isOutsideTouchable = true
            popupWindow.isFocusable = true
            popupWindow.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            popupWindow.setTouchInterceptor { _, event ->
                val isOutsideBounds = event.action == MotionEvent.ACTION_OUTSIDE ||
                    (event.action == MotionEvent.ACTION_DOWN &&
                        (event.x < 0f || event.x > popupWidth.toFloat() ||
                            event.y < 0f || event.y > popupHeight.toFloat()))
                if (isOutsideBounds) {
                    popupWindow.dismiss()
                    true
                } else {
                    false
                }
            }

            popupWindow.setOnDismissListener {
                lastDismissTime = System.currentTimeMillis()
                if (activePopupWindow === popupWindow) {
                    activePopupWindow = null
                    popupSessionJob?.cancel()
                    popupSessionJob = null
                }
            }

            // Active Theme/Accent setup
            val accentColorHex = prefs.getString("dot_color", "#00E5FF") ?: "#00E5FF"
            val activeAccentColor = try {
                Color.parseColor(accentColorHex)
            } catch (_: Exception) {
                Color.parseColor("#00E5FF")
            }

            // Custom Visual styling configurations from SharedPrefs
            val iconPackStyle = prefs.getString("icon_pack", "Neo Glow") ?: "Neo Glow"

            // Configure Layout background dynamically for themes
            val container = popupView.findViewById<LinearLayout>(R.id.ll_popup_container)
            container.layoutParams = container.layoutParams?.apply {
                width = popupWidth
                height = popupHeight
            } ?: LinearLayout.LayoutParams(popupWidth, popupHeight)

            val drawableBackground = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(Color.parseColor("#F20F172A")) // Rich trans-glass panel
                cornerRadius = 16 * currentDensity
                // Stroke customization dependent on Icon packs chosen
                val strokeThick = if (iconPackStyle == "Cyber Outline") 3.5f else 1.5f
                val strokeColor = if (iconPackStyle == "Minimal White") Color.WHITE else activeAccentColor
                setStroke((strokeThick * currentDensity).toInt(), strokeColor)
            }
            container.background = drawableBackground

            // Bind unified tabs & tools identically for BOTH Floating Dot and Edge Sidebar
            bindSidebarTabs(popupView, activeAccentColor, currentDensity)
            bindCustomShortcutsGrid(popupView, activeAccentColor, currentDensity)
            bindAppsTab(popupView, activeAccentColor)
            bindClipboardTab(popupView, activeAccentColor)
            bindNotesTab(popupView, activeAccentColor)
            bindCalculatorTab(popupView, activeAccentColor)
            bindTranslateTab(popupView, activeAccentColor)
            bindAiAssistantTab(popupView, activeAccentColor)

            // Positions
            val spacingPx = (8 * currentDensity).toInt()
            val minPosY = marginPx
            val maxPosY = (screenHeight - popupHeight - marginPx - 40).coerceAtLeast(minPosY)
            val maxPosX = (screenWidth - popupWidth).coerceAtLeast(0)

            var posX = 0
            var posY = 0

            if (alignToEdge) {
                val sidebarEdge = prefs.getString("sidebar_edge", "Right") ?: "Right"
                posX = if (sidebarEdge == "Left") {
                    (4 * currentDensity).toInt()
                } else {
                    (screenWidth - popupWidth - (4 * currentDensity).toInt()).coerceAtLeast(0)
                }

                // Align next to the current vertical drag position of the sidebar handle
                val spar = sidebarParams
                if (spar != null) {
                    val currentY = spar.y
                    val handleHeight = (130 * currentDensity).toInt()
                    val centerY = currentY + handleHeight / 2
                    posY = (centerY - popupHeight / 2).coerceIn(minPosY, maxPosY)
                } else {
                    posY = ((screenHeight - popupHeight) / 2).coerceIn(minPosY, maxPosY)
                }
            } else {
                // Position alignment next to the floating dot safely
                val dotW = if (::imgFloatingDot.isInitialized) imgFloatingDot.width else (56 * currentDensity).toInt()
                val dotH = if (::imgFloatingDot.isInitialized) imgFloatingDot.height else (56 * currentDensity).toInt()
                val pX = if (lastX + dotW / 2 < screenWidth / 2) {
                    lastX + dotW + spacingPx
                } else {
                    lastX - popupWidth - spacingPx
                }
                posX = pX.coerceIn(0, maxPosX)
                val pY = lastY + (dotH / 2) - (popupHeight / 2)
                posY = pY.coerceIn(minPosY, maxPosY)
            }

            // Launch expansion animation
            val panel = popupView.findViewById<LinearLayout>(R.id.ll_popup_container)
            panel.alpha = 0f
            panel.scaleX = 0.5f
            panel.scaleY = 0.5f

            activePopupWindow = popupWindow
            popupWindow.showAtLocation(anchorToken, Gravity.TOP or Gravity.START, posX, posY)

            val animDurationMs = prefs.getInt("menu_anim_speed_ms", 160).coerceIn(80, 350).toLong()
            panel.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(animDurationMs)
                .setInterpolator(OvershootInterpolator(1.05f))
                .start()
        } catch (e: Exception) {
            Log.e("FloatingService", "Failed opening assistant sidebar popup", e)
            activePopupWindow = null
        }
    }

    // -------------------------------------------------------------
    // Core Sidebar Page Navigation
    // -------------------------------------------------------------
    private fun bindSidebarTabs(popupView: View, accentColor: Int, density: Float) {
        val tabBar = popupView.findViewById<LinearLayout>(R.id.tab_bar) ?: return
        tabBar.removeAllViews()
        tabBar.clipChildren = false
        tabBar.clipToPadding = false

        val prefs = getSharedPreferences("smart_dot_prefs", Context.MODE_PRIVATE)
        val popupWidthDp = prefs.getInt("menu_width_dp", 280)
        val popupHeightDp = prefs.getInt("menu_height_dp", 460)
        val tabBarHeightDp = if (popupHeightDp <= 270) 36 else 42
        tabBar.layoutParams = tabBar.layoutParams?.apply {
            height = (tabBarHeightDp * density).toInt()
        }

        val layoutActions = popupView.findViewById<View>(R.id.layout_tab_actions)
        val layoutApps = popupView.findViewById<View>(R.id.layout_tab_apps)
        val layoutClipboard = popupView.findViewById<View>(R.id.layout_tab_clipboard)
        val layoutNotes = popupView.findViewById<View>(R.id.layout_tab_notes)
        val layoutCalc = popupView.findViewById<View>(R.id.layout_tab_calc)
        val layoutTranslate = popupView.findViewById<View>(R.id.layout_tab_translate)
        val layoutAi = popupView.findViewById<View>(R.id.layout_tab_ai)
        val btnOpenAiPanel = popupView.findViewById<View>(R.id.btn_open_ai_panel)
        val ivAiStripIcon = popupView.findViewById<ImageView>(R.id.iv_ai_strip_icon)
        val tvAiStripBadge = popupView.findViewById<TextView>(R.id.tv_ai_strip_badge)

        // Hide extra AI strip inside popup so it doesn't waste vertical space
        btnOpenAiPanel?.visibility = View.GONE
        ivAiStripIcon?.setColorFilter(accentColor, PorterDuff.Mode.SRC_IN)
        tvAiStripBadge?.setTextColor(accentColor)

        val layouts = listOf(layoutActions, layoutApps, layoutClipboard, layoutNotes, layoutCalc, layoutTranslate, layoutAi)
        layouts.forEach { it?.visibility = View.GONE }
        layoutActions?.visibility = View.VISIBLE

        val defaultTopShortcuts = "calculator,translate,clipboard,notes,open_app_drawer,settings"
        val topSavedStr = prefs.getString("top_shortcuts_list", defaultTopShortcuts) ?: defaultTopShortcuts
        val topShortcuts = topSavedStr.split(",").filter { it.isNotEmpty() }.toMutableList()

        var activeTabIndex = -1
        val tabButtons = ArrayList<ImageView>()

        fun updateTabStyles() {
            for (i in tabButtons.indices) {
                if (i == activeTabIndex) {
                    tabButtons[i].setColorFilter(accentColor, PorterDuff.Mode.SRC_IN)
                    tabButtons[i].scaleX = 1.12f
                    tabButtons[i].scaleY = 1.12f
                } else {
                    tabButtons[i].setColorFilter(Color.parseColor("#64748B"), PorterDuff.Mode.SRC_IN)
                    tabButtons[i].scaleX = 1.0f
                    tabButtons[i].scaleY = 1.0f
                }
            }
        }

        btnOpenAiPanel?.setOnClickListener {
            activeTabIndex = -1
            layouts.forEach { it?.visibility = View.GONE }
            layoutAi?.visibility = View.VISIBLE
            updateTabStyles()
        }

        val tabPadPx = ((if (popupWidthDp <= 270 || popupHeightDp <= 270) 6 else 7) * density).toInt()
        topShortcuts.forEachIndexed { index, shortcutId ->
            val iconRes = getActionIcon(shortcutId)
            val actionName = getActionName(shortcutId)

            val tabButton = ImageView(this).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply {
                    marginStart = (1 * density).toInt()
                    marginEnd = (1 * density).toInt()
                }
                tag = iconRes
                setImageResource(iconRes)
                scaleType = ImageView.ScaleType.FIT_CENTER
                adjustViewBounds = true
                setColorFilter(Color.parseColor("#64748B"), PorterDuff.Mode.SRC_IN)
                setPadding(tabPadPx, tabPadPx, tabPadPx, tabPadPx)
                background = getSystemDrawable(android.R.attr.selectableItemBackgroundBorderless)
                isClickable = true
                isFocusable = true
                contentDescription = "$actionName Tab Button"

                setOnClickListener {
                    if (isTabTool(shortcutId)) {
                        if (activeTabIndex == index && shortcutId != "settings") {
                            activeTabIndex = -1
                            layouts.forEach { it?.visibility = View.GONE }
                            layoutActions?.visibility = View.VISIBLE
                            updateTabStyles()
                            return@setOnClickListener
                        }
                        activeTabIndex = index
                        layouts.forEach { it?.visibility = View.GONE }
                        when (shortcutId) {
                            "calculator" -> layoutCalc?.visibility = View.VISIBLE
                            "translate" -> layoutTranslate?.visibility = View.VISIBLE
                            "clipboard" -> layoutClipboard?.visibility = View.VISIBLE
                            "notes" -> layoutNotes?.visibility = View.VISIBLE
                            "open_app_drawer" -> layoutApps?.visibility = View.VISIBLE
                            "settings" -> {
                                activeTabIndex = -1
                                layoutActions?.visibility = View.VISIBLE
                                launchSettings()
                            }
                        }
                        updateTabStyles()
                    } else {
                        activeTabIndex = -1
                        layouts.forEach { it?.visibility = View.GONE }
                        layoutActions?.visibility = View.VISIBLE
                        updateTabStyles()
                        executeAction(shortcutId)
                    }
                }

                setOnLongClickListener {
                    showShortcutSelectorDialog { selectedId ->
                        topShortcuts[index] = selectedId
                        prefs.edit().putString("top_shortcuts_list", topShortcuts.joinToString(",")).apply()
                        if (isPopupAlignedToEdge) showSidebarPanel() else showAssistantSidebar()
                    }
                    true
                }
            }
            tabButtons.add(tabButton)
            tabBar.addView(tabButton)
        }
    }

    private fun switchActivePopupToAiTab(): Boolean {
        val popupContent = activePopupWindow?.takeIf { it.isShowing }?.contentView ?: return false
        val layoutAi = popupContent.findViewById<View>(R.id.layout_tab_ai) ?: return false
        listOf(
            R.id.layout_tab_actions,
            R.id.layout_tab_apps,
            R.id.layout_tab_clipboard,
            R.id.layout_tab_notes,
            R.id.layout_tab_calc,
            R.id.layout_tab_translate
        ).forEach { id ->
            popupContent.findViewById<View>(id)?.visibility = View.GONE
        }
        layoutAi.visibility = View.VISIBLE
        return true
    }

    private fun switchActivePopupToAppsTab(): Boolean {
        val popupContent = activePopupWindow?.takeIf { it.isShowing }?.contentView ?: return false
        val layoutActions = popupContent.findViewById<View>(R.id.layout_tab_actions)
        val layoutApps = popupContent.findViewById<View>(R.id.layout_tab_apps) ?: return false
        val layoutClipboard = popupContent.findViewById<View>(R.id.layout_tab_clipboard)
        val layoutNotes = popupContent.findViewById<View>(R.id.layout_tab_notes)
        val layoutCalc = popupContent.findViewById<View>(R.id.layout_tab_calc)
        val layoutTranslate = popupContent.findViewById<View>(R.id.layout_tab_translate)
        val layoutAi = popupContent.findViewById<View>(R.id.layout_tab_ai)

        listOf(layoutActions, layoutClipboard, layoutNotes, layoutCalc, layoutTranslate, layoutAi).forEach {
            it?.visibility = View.GONE
        }
        layoutApps.visibility = View.VISIBLE

        val tabBar = popupContent.findViewById<LinearLayout>(R.id.tab_bar)
        val accentHex = prefs.getString("dot_color", "#00E5FF") ?: "#00E5FF"
        val activeAccent = try {
            Color.parseColor(accentHex)
        } catch (_: Exception) {
            Color.parseColor("#00E5FF")
        }
        if (tabBar != null) {
            for (i in 0 until tabBar.childCount) {
                val child = tabBar.getChildAt(i) as? ImageView ?: continue
                val isAppsTab = child.contentDescription?.toString()?.contains("Drawer", ignoreCase = true) == true
                if (isAppsTab) {
                    child.setColorFilter(activeAccent, PorterDuff.Mode.SRC_IN)
                    child.scaleX = 1.15f
                    child.scaleY = 1.15f
                } else {
                    child.setColorFilter(Color.parseColor("#64748B"), PorterDuff.Mode.SRC_IN)
                    child.scaleX = 1.0f
                    child.scaleY = 1.0f
                }
            }
        }
        return true
    }

    // -------------------------------------------------------------
    // Tab 2: Unified Utility & Installed App Launcher (Freeform Window)
    // -------------------------------------------------------------
    private data class LaunchableAppEntry(
        val packageName: String,
        val activityName: String,
        val label: String,
        val icon: Drawable
    )

    private fun bindAppsTab(popupView: View, accentColor: Int) {
        val rowChrome = popupView.findViewById<LinearLayout>(R.id.row_chrome)
        val rowWhatsapp = popupView.findViewById<LinearLayout>(R.id.row_whatsapp)
        val rowCalculator = popupView.findViewById<LinearLayout>(R.id.row_calculator)
        val dynamicContainer = popupView.findViewById<LinearLayout>(R.id.dynamic_app_shortcuts)

        fun tintRowIcon(row: LinearLayout?) {
            val icon = row?.getChildAt(0) as? ImageView
            icon?.setColorFilter(accentColor, PorterDuff.Mode.SRC_IN)
        }
        tintRowIcon(rowChrome)
        tintRowIcon(rowWhatsapp)
        tintRowIcon(rowCalculator)

        rowChrome?.setOnClickListener {
            activePopupWindow?.dismiss()
            launchChrome()
        }
        rowWhatsapp?.setOnClickListener {
            activePopupWindow?.dismiss()
            launchWhatsApp()
        }
        rowCalculator?.setOnClickListener {
            activePopupWindow?.dismiss()
            launchCalculator()
        }

        if (dynamicContainer == null) return
        dynamicContainer.removeAllViews()

        val currentJob = popupSessionJob ?: serviceScope.coroutineContext[Job]
        serviceScope.launch(currentJob ?: Dispatchers.Main) {
            val apps = withContext(Dispatchers.IO) {
                val pm = packageManager
                val queryIntent = Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_LAUNCHER)
                }
                val resolved = runCatching { pm.queryIntentActivities(queryIntent, 0) }.getOrDefault(emptyList())
                val seenPackages = HashSet<String>()
                resolved.mapNotNull { info ->
                    val pkg = info.activityInfo?.packageName ?: return@mapNotNull null
                    if (pkg == packageName || !seenPackages.add(pkg)) return@mapNotNull null
                    val act = info.activityInfo.name ?: return@mapNotNull null
                    val label = runCatching { info.loadLabel(pm).toString() }.getOrDefault(pkg)
                    val icon = runCatching { info.loadIcon(pm) }.getOrNull()
                        ?: ContextCompat.getDrawable(this@FloatingService, R.drawable.ic_tab_apps)
                        ?: return@mapNotNull null
                    LaunchableAppEntry(pkg, act, label, icon)
                }.sortedBy { it.label.lowercase(Locale.getDefault()) }
            }

            if (activePopupWindow?.contentView !== popupView) return@launch
            dynamicContainer.removeAllViews()

            if (apps.isEmpty()) {
                val emptyView = TextView(this@FloatingService).apply {
                    text = "No launchable apps discovered."
                    setTextColor(Color.parseColor("#64748B"))
                    textSize = 12f
                    setPadding((10 * density).toInt(), (8 * density).toInt(), (10 * density).toInt(), (8 * density).toInt())
                }
                dynamicContainer.addView(emptyView)
                return@launch
            }

            val rowHeightPx = (48 * density).toInt()
            val iconSizePx = (24 * density).toInt()
            val horizontalPadPx = (10 * density).toInt()
            val bottomMarginPx = (4 * density).toInt()

            for (app in apps) {
                val row = LinearLayout(this@FloatingService).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        rowHeightPx
                    ).apply {
                        bottomMargin = bottomMarginPx
                    }
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(horizontalPadPx, 0, horizontalPadPx, 0)
                    background = getSystemDrawable(android.R.attr.selectableItemBackground)
                    isClickable = true
                    isFocusable = true
                    tag = app.packageName
                    contentDescription = "Launch ${app.label}"
                }

                val iconView = ImageView(this@FloatingService).apply {
                    layoutParams = LinearLayout.LayoutParams(iconSizePx, iconSizePx)
                    setImageDrawable(app.icon)
                    contentDescription = null
                }

                val titleView = TextView(this@FloatingService).apply {
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                        marginStart = (12 * density).toInt()
                    }
                    text = app.label
                    setTextColor(Color.WHITE)
                    textSize = 13f
                    setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                }

                row.addView(iconView)
                row.addView(titleView)

                row.setOnClickListener {
                    activePopupWindow?.dismiss()
                    val launchIntent = packageManager.getLaunchIntentForPackage(app.packageName)
                        ?: Intent(Intent.ACTION_MAIN).apply {
                            addCategory(Intent.CATEGORY_LAUNCHER)
                            setClassName(app.packageName, app.activityName)
                        }
                    FreeformWindowLauncher.launchFullscreen(
                        this@FloatingService,
                        launchIntent,
                        app.label
                    )
                }

                dynamicContainer.addView(row)
            }
        }
    }

    // -------------------------------------------------------------
    // Tab 1: Custom Re-mappable 12-Slot Shortcuts Grid (3 Columns)
    // -------------------------------------------------------------
    private fun getActionIcon(actionId: String): Int {
        if (isCustomWhatsAppShortcut(actionId)) return R.drawable.ic_radial_whatsapp
        return when (actionId) {
            "home" -> R.drawable.ic_radial_home
            "back" -> R.drawable.ic_radial_back
            "recent_apps" -> R.drawable.ic_radial_recents
            "screenshot" -> R.drawable.ic_radial_screenshot
            "screen_copy" -> R.drawable.ic_screen_text_copy
            "screen_translate", "translate" -> R.drawable.ic_tab_translate
            "clipboard" -> R.drawable.ic_tab_clipboard
            "notes" -> R.drawable.ic_tab_notes
            "screen_record" -> android.R.drawable.presence_video_online
            "ask_ai" -> android.R.drawable.ic_btn_speak_now
            "notifications" -> R.drawable.ic_radial_notifications
            "quick_settings" -> R.drawable.ic_radial_quick_settings
            "lock_screen" -> android.R.drawable.ic_lock_lock
            "settings" -> R.drawable.ic_radial_settings
            "whatsapp" -> R.drawable.ic_radial_whatsapp
            "calculator" -> R.drawable.ic_tab_calc
            "chrome" -> R.drawable.ic_radial_chrome
            "flashlight" -> R.drawable.ic_app_flashlight
            "camera" -> android.R.drawable.ic_menu_camera
            "gallery" -> android.R.drawable.ic_menu_gallery
            "youtube" -> android.R.drawable.ic_menu_slideshow
            "gmail" -> android.R.drawable.ic_dialog_email
            "files" -> android.R.drawable.ic_menu_save
            "wifi" -> android.R.drawable.ic_menu_info_details
            "mobile_data" -> android.R.drawable.ic_menu_share
            "bluetooth" -> android.R.drawable.ic_menu_compass
            "brightness" -> android.R.drawable.ic_menu_view
            "volume" -> android.R.drawable.ic_lock_silent_mode_off
            "mute" -> android.R.drawable.ic_lock_silent_mode
            "dnd_mode" -> android.R.drawable.presence_busy
            "auto_rotate" -> android.R.drawable.ic_menu_rotate
            "dark_mode" -> android.R.drawable.ic_menu_day
            "airplane_mode" -> android.R.drawable.ic_menu_directions
            "hotspot" -> android.R.drawable.ic_menu_share
            "split_screen" -> android.R.drawable.ic_menu_crop
            "floating_window" -> R.drawable.ic_floating_window
            "open_menu" -> R.drawable.ic_tab_actions
            "realme_smart_sidebar" -> R.drawable.ic_floating_window
            "open_app_drawer" -> R.drawable.ic_tab_apps
            "voice_assistant" -> android.R.drawable.ic_btn_speak_now
            "about_app" -> android.R.drawable.ic_menu_info_details
            "clock" -> android.R.drawable.ic_menu_today
            "contacts" -> android.R.drawable.ic_menu_my_calendar
            "messages" -> android.R.drawable.ic_menu_send
            "custom_app" -> android.R.drawable.ic_menu_add
            "floating_browser" -> R.drawable.ic_radial_chrome
            "floating_youtube" -> android.R.drawable.ic_menu_slideshow
            "floating_notes" -> R.drawable.ic_tab_notes
            "floating_calculator" -> R.drawable.ic_tab_calc
            "floating_translator" -> R.drawable.ic_tab_translate
            "floating_viewer" -> android.R.drawable.ic_menu_gallery
            "floating_photos_videos" -> android.R.drawable.ic_menu_gallery
            "floating_music" -> android.R.drawable.ic_media_play
            "floating_media_files" -> android.R.drawable.ic_menu_gallery
            "floating_whatsapp" -> R.drawable.ic_radial_whatsapp
            "floating_instagram", "floating_facebook", "floating_telegram" -> R.drawable.ic_floating_window
            "google_ai_mode" -> R.drawable.ic_toolbar_ai_mode
            "gemini" -> R.drawable.ic_toolbar_gemini
            "chatgpt" -> R.drawable.ic_toolbar_chatgpt
            "google_lens" -> R.drawable.ic_toolbar_lens
            "black_screen_mode" -> android.R.drawable.ic_lock_idle_lock
            "auto_scroller" -> android.R.drawable.arrow_down_float
            "auto_clicker" -> android.R.drawable.ic_menu_mylocation
            "power_menu" -> android.R.drawable.ic_lock_power_off
            "volume_panel" -> android.R.drawable.ic_lock_silent_mode_off
            "media_play_pause" -> android.R.drawable.ic_media_play
            "media_next" -> android.R.drawable.ic_media_next
            "brightness_up", "brightness_down" -> android.R.drawable.ic_menu_day
            "qr_scanner" -> android.R.drawable.ic_menu_camera
            "chrome_incognito", "chrome_search" -> R.drawable.ic_radial_chrome
            "youtube_subscriptions", "youtube_search" -> android.R.drawable.ic_menu_slideshow
            "whatsapp_chat", "whatsapp_camera" -> R.drawable.ic_radial_whatsapp
            "settings_wifi", "settings_bluetooth", "settings_battery", "settings_app_info", "accessibility_settings" -> R.drawable.ic_radial_settings
            "display_over_apps" -> R.drawable.ic_floating_window
            "custom_url_intent" -> android.R.drawable.ic_menu_directions
            else -> R.drawable.ic_radial_settings
        }
    }

    private fun getActionName(actionId: String): String = resolveShortcutShortLabel(actionId, prefs)

    private fun toggleFlashlight() {
        val cameraManager = getSystemService(Context.CAMERA_SERVICE) as? android.hardware.camera2.CameraManager ?: return
        try {
            val cameraId = cameraManager.cameraIdList.firstOrNull() ?: return
            isFlashlightOn = !isFlashlightOn
            cameraManager.setTorchMode(cameraId, isFlashlightOn)
            Toast.makeText(this, if (isFlashlightOn) "Flashlight ON" else "Flashlight OFF", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "Torch control error: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun executeSwipeGesture(
        direction: SwipeGestureHelper.SwipeDirection,
        isSidebarOrigin: Boolean
    ) {
        val actionId = SwipeGestureHelper.getAssignedAction(prefs, direction)
        if (actionId != "none") {
            executeAction(actionId)
        } else if (isSidebarOrigin && direction == SwipeGestureHelper.SwipeDirection.SWIPE_LEFT) {
            showSidebarPanel()
        }
    }

    private fun executeEdgeGesture(gesture: EdgeGestureDetector.EdgeGesture) {
        val actionId = EdgeGestureDetector.getAssignedAction(prefs, gesture)
        when {
            gesture == EdgeGestureDetector.EdgeGesture.TAP -> {
                if (actionId == "open_menu" || actionId == "none") {
                    showSidebarPanel()
                } else {
                    executeAction(actionId)
                }
            }
            gesture == EdgeGestureDetector.EdgeGesture.SWIPE_LEFT && actionId == "none" -> {
                showSidebarPanel()
            }
            actionId == "open_menu" -> showSidebarPanel()
            actionId != "none" -> executeAction(actionId)
        }
    }

    private fun getOrCreateFloatingWindowManager(): FloatingWindowManager {
        if (!::windowManager.isInitialized) {
            windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        }
        return floatingWindowManager ?: FloatingWindowManager(this, windowManager).also {
            floatingWindowManager = it
        }
    }

    private fun getOrCreateProToolsManager(): ProToolsOverlayManager {
        if (!::windowManager.isInitialized) {
            windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        }
        return proToolsOverlayManager ?: ProToolsOverlayManager(this, windowManager).also {
            proToolsOverlayManager = it
        }
    }

    private fun setBankingShieldHidden(hidden: Boolean) {
        isShieldHidden = hidden
        if (hidden && activePopupWindow?.isShowing == true) {
            activePopupWindow?.dismiss()
        }
        if (::floatingView.isInitialized && isFloatingViewAdded) {
            val dotEnabled = prefs.getBoolean("floating_dot_enabled", true)
            floatingView.visibility = if (!hidden && dotEnabled) View.VISIBLE else View.GONE
        }
        sidebarView?.let { sb ->
            val sidebarEnabled = prefs.getBoolean("sidebar_enabled", true)
            sb.visibility = if (!hidden && sidebarEnabled) View.VISIBLE else View.GONE
        }
        stopRecordingPillView?.visibility = if (hidden) View.GONE else View.VISIBLE
        floatingWindowManager?.setShieldHidden(hidden)
        proToolsOverlayManager?.setShieldHidden(hidden)
    }

    private fun removeAllTextCopyOverlays() {
        if (activePopupWindow?.isShowing == true) {
            activePopupWindow?.dismiss()
        }
        screenTextCopyController?.dismissOverlayIfShowing()
        screenTranslateController?.dismissOverlayIfShowing()
        setOverlayViewsVisible(false)
    }

    private fun triggerGoogleLensOneTap() {
        removeAllTextCopyOverlays()
        val sInstance = SmartDotAccessibilityService.instance
        val mainHandler = Handler(Looper.getMainLooper())
        if (sInstance == null || SmartDotAccessibilityService.isUserPaused) {
            mainHandler.postDelayed({
                setOverlayViewsVisible(true)
                if (!SelectionToolbarHelper.launchNativeGoogleLens(this)) {
                    showAccessibilityDialog()
                }
            }, 180L)
            return
        }
        mainHandler.postDelayed({
            sInstance.captureScreenBitmapInMemory { bitmap ->
                mainHandler.post {
                    setOverlayViewsVisible(true)
                    if (bitmap != null) {
                        val launched = SelectionToolbarHelper.launchGoogleLensImage(this, bitmap)
                        if (!launched) {
                            Toast.makeText(this, "Google Lens not available on this device", Toast.LENGTH_SHORT).show()
                        }
                    } else if (!SelectionToolbarHelper.launchNativeGoogleLens(this)) {
                        Toast.makeText(this, "Google Lens not available on this device", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }, 180L)
    }

    private fun getConfiguredAccentColor(): Int {
        val hex = prefs.getString("dot_color", "#00E5FF") ?: "#00E5FF"
        return try {
            Color.parseColor(hex)
        } catch (_: Exception) {
            Color.parseColor("#00E5FF")
        }
    }

    private fun dispatchMediaKey(keyCode: Int) {
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        val downEvent = KeyEvent(KeyEvent.ACTION_DOWN, keyCode)
        val upEvent = KeyEvent(KeyEvent.ACTION_UP, keyCode)
        audioManager.dispatchMediaKeyEvent(downEvent)
        audioManager.dispatchMediaKeyEvent(upEvent)
    }

    private fun launchClockAlarm() {
        activePopupWindow?.dismiss()
        val pm = packageManager
        val candidates = ArrayList<Intent>()
        candidates.add(Intent(AlarmClock.ACTION_SHOW_ALARMS).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
        candidates.add(Intent(AlarmClock.ACTION_SET_TIMER).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
        val clockPackages = listOf(
            "com.coloros.alarmclock",
            "com.oplus.alarmclock",
            "com.google.android.deskclock",
            "com.android.deskclock",
            "com.sec.android.app.clockpackage"
        )
        clockPackages.forEach { pkg ->
            pm.getLaunchIntentForPackage(pkg)?.let { intent ->
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                candidates.add(intent)
            }
        }
        for (candidate in candidates) {
            val started = runCatching {
                startActivity(candidate)
                true
            }.getOrDefault(false)
            if (started) return
        }
        Toast.makeText(this, "Clock App not found.", Toast.LENGTH_SHORT).show()
    }

    private fun triggerQrScanner() {
        activePopupWindow?.dismiss()
        val scannerIntent = Intent(this, QrScannerActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { startActivity(scannerIntent) }
            .onFailure {
                Toast.makeText(this, "QR Scanner unavailable on this device.", Toast.LENGTH_SHORT).show()
            }
    }

    private fun triggerVoiceAssistant() {
        activePopupWindow?.dismiss()
        val pm = packageManager
        val explicitComponents = listOf(
            // Step 1: Official Google Assistant OpaActivity inside Google App
            ComponentName(
                "com.google.android.googlequicksearchbox",
                "com.google.android.apps.gsa.staticplugins.opa.OpaActivity"
            ),
            // Step 2: Standalone Google Assistant Activity
            ComponentName(
                "com.google.android.apps.googleassistant",
                "com.google.android.apps.googleassistant.AssistantActivity"
            )
        )
        for (comp in explicitComponents) {
            val queryIntent = Intent().apply {
                component = comp
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val isExportedAvailable = runCatching {
                pm.queryIntentActivities(queryIntent, 0)
                    .any { it.activityInfo?.exported == true }
            }.getOrDefault(false)
            if (isExportedAvailable) {
                val launchIntent = Intent(queryIntent).apply {
                    setPackage(comp.packageName)
                }
                val ok = runCatching {
                    startActivity(launchIntent)
                    true
                }.getOrDefault(false)
                if (ok) return
            }
        }

        // Step 2b: Standalone Google Assistant package launcher intent
        pm.getLaunchIntentForPackage("com.google.android.apps.googleassistant")?.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }?.let { launchIntent ->
            val ok = runCatching {
                startActivity(launchIntent)
                true
            }.getOrDefault(false)
            if (ok) return
        }

        // Step 3: Voice command in Google App
        val voiceCommandIntent = Intent(Intent.ACTION_VOICE_COMMAND).apply {
            setPackage("com.google.android.googlequicksearchbox")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (voiceCommandIntent.resolveActivity(pm) != null) {
            val ok = runCatching {
                startActivity(voiceCommandIntent)
                true
            }.getOrDefault(false)
            if (ok) return
        }

        // Step 4: Generic Android ACTION_ASSIST fallback
        val assistIntent = Intent(Intent.ACTION_ASSIST).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val assistStarted = runCatching {
            startActivity(assistIntent)
            true
        }.getOrDefault(false)
        if (!assistStarted) {
            Toast.makeText(this, "Voice Assistant not found on this device", Toast.LENGTH_SHORT).show()
        }
    }

    private fun triggerOneTapQuickTileToggle(target: QuickTileAutomation.QuickTileTarget) {
        activePopupWindow?.dismiss()
        // 1. Native OS toggle first (Wi-Fi < Q, Bluetooth < Tiramisu, System Auto-Rotate)
        val silentResult = PrivilegedToggleHelper.toggleSilently(this, target)
        if (silentResult != null) {
            Toast.makeText(
                this,
                "${target.displayName} ${if (silentResult) "ON" else "OFF"}",
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        // 2. Fast 80ms Accessibility Quick-Tile Clicker + auto-collapse if available & unpaused
        val a11yService = SmartDotAccessibilityService.instance
        if (a11yService != null && !SmartDotAccessibilityService.isUserPaused) {
            a11yService.performQuickTileToggle(target) { toggled ->
                if (!toggled) {
                    runCatching {
                        startActivity(QuickTileAutomation.buildFallbackSettingsIntent(target))
                    }
                }
            }
            return
        }

        // 3. Settings Panel fallback when Accessibility is disabled/paused
        runCatching {
            startActivity(QuickTileAutomation.buildFallbackSettingsIntent(target))
        }
    }

    private fun triggerPopOutActiveAppWindow() {
        activePopupWindow?.dismiss()
        val sInstance = SmartDotAccessibilityService.instance
        if (sInstance == null) {
            showAccessibilityDialog()
            return
        }
        val activePkg = sInstance.getActiveForegroundPackageName(excludedPackage = packageName)
        if (activePkg.isNullOrBlank()) {
            Toast.makeText(this, getString(R.string.floating_window_no_active_app), Toast.LENGTH_SHORT).show()
            return
        }
        val pm = packageManager
        val appLabel = runCatching {
            val appInfo = pm.getApplicationInfo(activePkg, 0)
            pm.getApplicationLabel(appInfo).toString()
        }.getOrNull() ?: activePkg
        val detectedUrl = sInstance.extractActiveBrowserUrl()

        val manager = getOrCreateFloatingWindowManager()
        val accentColor = getConfiguredAccentColor()
        when (val target = FreeformWindowLauncher.resolveFloatingTargetForPackage(activePkg, appLabel, detectedUrl)) {
            is FreeformWindowLauncher.FloatingAppTarget.SocialWeb -> {
                FloatingMiniApps.openFloatingSocialApp(this, manager, accentColor, target.appId)
            }
            is FreeformWindowLauncher.FloatingAppTarget.YouTube -> {
                FloatingMiniApps.openFloatingYouTube(this, manager, accentColor)
            }
            is FreeformWindowLauncher.FloatingAppTarget.AiWeb -> {
                FloatingMiniApps.openFloatingAiWebWindow(this, manager, accentColor, target.aiModeId)
            }
            is FreeformWindowLauncher.FloatingAppTarget.Notes -> {
                FloatingMiniApps.openFloatingNotes(this, manager, serviceScope, accentColor)
            }
            is FreeformWindowLauncher.FloatingAppTarget.Calculator -> {
                FloatingMiniApps.openFloatingCalculator(this, manager, accentColor)
            }
            is FreeformWindowLauncher.FloatingAppTarget.MediaFiles -> {
                FloatingMiniApps.openFloatingMediaFiles(this, manager, accentColor)
            }
            is FreeformWindowLauncher.FloatingAppTarget.Browser -> {
                FloatingMiniApps.openFloatingBrowser(
                    context = this,
                    manager = manager,
                    accentColor = accentColor,
                    initialUrl = target.url,
                    navigateIfOpen = true
                )
            }
        }
    }

    private fun executeAction(actionId: String) {
        val context = this
        val pm = packageManager
        try {
            when (actionId) {
                "none" -> return
                "floating_browser" -> {
                    activePopupWindow?.dismiss()
                    FloatingMiniApps.openFloatingBrowser(this, getOrCreateFloatingWindowManager(), getConfiguredAccentColor())
                }
                "floating_youtube" -> {
                    activePopupWindow?.dismiss()
                    FloatingMiniApps.openFloatingYouTube(this, getOrCreateFloatingWindowManager(), getConfiguredAccentColor())
                }
                "floating_notes" -> {
                    activePopupWindow?.dismiss()
                    FloatingMiniApps.openFloatingNotes(this, getOrCreateFloatingWindowManager(), serviceScope, getConfiguredAccentColor())
                }
                "floating_calculator" -> {
                    activePopupWindow?.dismiss()
                    FloatingMiniApps.openFloatingCalculator(this, getOrCreateFloatingWindowManager(), getConfiguredAccentColor())
                }
                "floating_translator" -> {
                    activePopupWindow?.dismiss()
                    FloatingMiniApps.openFloatingTranslator(this, getOrCreateFloatingWindowManager(), serviceScope, getConfiguredAccentColor())
                }
                "floating_viewer" -> {
                    activePopupWindow?.dismiss()
                    FloatingMiniApps.openFloatingViewer(this, getOrCreateFloatingWindowManager(), getConfiguredAccentColor())
                }
                "floating_photos_videos" -> {
                    activePopupWindow?.dismiss()
                    FloatingMiniApps.openFloatingPhotosVideosGallery(this, getOrCreateFloatingWindowManager(), getConfiguredAccentColor())
                }
                "floating_music" -> {
                    activePopupWindow?.dismiss()
                    FloatingMiniApps.openFloatingMusicPlayer(this, getOrCreateFloatingWindowManager(), getConfiguredAccentColor())
                }
                "floating_media_files" -> {
                    activePopupWindow?.dismiss()
                    FloatingMiniApps.openFloatingMediaFiles(this, getOrCreateFloatingWindowManager(), getConfiguredAccentColor())
                }
                "floating_whatsapp" -> {
                    activePopupWindow?.dismiss()
                    FloatingMiniApps.openFloatingSocialApp(this, getOrCreateFloatingWindowManager(), getConfiguredAccentColor(), "whatsapp")
                }
                "floating_instagram" -> {
                    activePopupWindow?.dismiss()
                    FloatingMiniApps.openFloatingSocialApp(this, getOrCreateFloatingWindowManager(), getConfiguredAccentColor(), "instagram")
                }
                "floating_facebook" -> {
                    activePopupWindow?.dismiss()
                    FloatingMiniApps.openFloatingSocialApp(this, getOrCreateFloatingWindowManager(), getConfiguredAccentColor(), "facebook")
                }
                "floating_telegram" -> {
                    activePopupWindow?.dismiss()
                    FloatingMiniApps.openFloatingSocialApp(this, getOrCreateFloatingWindowManager(), getConfiguredAccentColor(), "telegram")
                }
                "google_ai_mode" -> {
                    activePopupWindow?.dismiss()
                    FloatingMiniApps.openFloatingBrowser(
                        context = this,
                        manager = getOrCreateFloatingWindowManager(),
                        accentColor = getConfiguredAccentColor(),
                        initialUrl = "https://www.google.com/search?udm=50",
                        navigateIfOpen = true
                    )
                }
                "chatgpt" -> {
                    activePopupWindow?.dismiss()
                    FloatingMiniApps.openFloatingBrowser(
                        context = this,
                        manager = getOrCreateFloatingWindowManager(),
                        accentColor = getConfiguredAccentColor(),
                        initialUrl = "https://chatgpt.com/",
                        navigateIfOpen = true
                    )
                }
                "gemini" -> {
                    activePopupWindow?.dismiss()
                    FloatingMiniApps.openFloatingAiWebWindow(
                        this,
                        getOrCreateFloatingWindowManager(),
                        getConfiguredAccentColor(),
                        actionId
                    )
                }
                "google_lens" -> triggerGoogleLensOneTap()
                "accessibility_settings" -> {
                    activePopupWindow?.dismiss()
                    val detailsIntent = SelectionToolbarHelper.createSmartDotAccessibilityDetailsIntent(this)
                    runCatching {
                        startActivity(detailsIntent)
                    }.onFailure {
                        startActivity(SelectionToolbarHelper.createSmartDotAccessibilityFragmentFallbackIntent(this))
                    }
                }
                "display_over_apps" -> {
                    activePopupWindow?.dismiss()
                    startActivity(SelectionToolbarHelper.createDisplayOverAppsIntent(this))
                }
                "black_screen_mode" -> {
                    activePopupWindow?.dismiss()
                    getOrCreateProToolsManager().toggleBlackScreenMode()
                }
                "auto_scroller" -> {
                    activePopupWindow?.dismiss()
                    getOrCreateProToolsManager().toggleAutoScroller()
                }
                "auto_clicker" -> {
                    activePopupWindow?.dismiss()
                    getOrCreateProToolsManager().toggleAutoClicker(getConfiguredAccentColor())
                }
                "home" -> performGlobalActionSafety(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME)
                "back" -> performGlobalActionSafety(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
                "recent_apps" -> performGlobalActionSafety(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_RECENTS)
                "lock_screen" -> {
                    activePopupWindow?.dismiss()
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        val sInstance = SmartDotAccessibilityService.instance
                        if (sInstance != null) {
                            sInstance.performLockScreen()
                        } else {
                            showAccessibilityDialog()
                        }
                    } else {
                        Toast.makeText(this, "Lock Screen requires Android 9 or above.", Toast.LENGTH_SHORT).show()
                    }
                }
                "power_menu" -> {
                    activePopupWindow?.dismiss()
                    val sInstance = SmartDotAccessibilityService.instance
                    if (sInstance != null) {
                        sInstance.dispatchPowerDialogAction()
                    } else {
                        showAccessibilityDialog()
                    }
                }
                "screenshot" -> triggerScreenshot()
                "screen_copy" -> startScreenTextCopy()
                "screen_translate" -> startScreenTranslate()
                "screen_record" -> triggerScreenRecording()
                "ask_ai" -> {
                    if (!switchActivePopupToAiTab()) {
                        if (isPopupAlignedToEdge) showSidebarPanel() else showAssistantSidebar()
                        switchActivePopupToAiTab()
                    }
                }
                "notifications" -> performGlobalActionSafety(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS)
                "quick_settings" -> performGlobalActionSafety(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS)
                "split_screen" -> {
                    activePopupWindow?.dismiss()
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        val sInstance = SmartDotAccessibilityService.instance
                        if (sInstance != null) {
                            sInstance.performSplitScreen()
                        } else {
                            showAccessibilityDialog()
                        }
                    } else {
                        Toast.makeText(this, "Split screen is supported on Nougat and above.", Toast.LENGTH_SHORT).show()
                    }
                }
                "floating_window" -> triggerPopOutActiveAppWindow()
                "open_menu" -> {
                    if (isPopupAlignedToEdge) showSidebarPanel() else showAssistantSidebar()
                }
                "realme_smart_sidebar" -> {
                    activePopupWindow?.dismiss()
                    if (!FreeformWindowLauncher.launchRealmeSmartSidebar(this)) {
                        Toast.makeText(this, getString(R.string.realme_sidebar_unavailable), Toast.LENGTH_SHORT).show()
                    }
                }
                "open_app_drawer" -> {
                    if (!switchActivePopupToAppsTab()) {
                        val homeIntent = Intent(Intent.ACTION_MAIN).apply {
                            addCategory(Intent.CATEGORY_HOME)
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        }
                        startActivity(homeIntent)
                    }
                }
                "voice_assistant" -> triggerVoiceAssistant()
                "flashlight" -> toggleFlashlight()
                "wifi" -> triggerOneTapQuickTileToggle(QuickTileAutomation.QuickTileTarget.WIFI)
                "mobile_data" -> triggerOneTapQuickTileToggle(QuickTileAutomation.QuickTileTarget.MOBILE_DATA)
                "bluetooth" -> triggerOneTapQuickTileToggle(QuickTileAutomation.QuickTileTarget.BLUETOOTH)
                "brightness" -> {
                    val stepped = PrivilegedToggleHelper.adjustBrightnessByStep(this, +35)
                    if (stepped != null) {
                        Toast.makeText(this, "Brightness: ${(stepped * 100 / 255)}%", Toast.LENGTH_SHORT).show()
                    }
                }
                "brightness_up" -> {
                    val stepped = PrivilegedToggleHelper.adjustBrightnessByStep(this, +35)
                    if (stepped != null) {
                        Toast.makeText(this, "Brightness: ${(stepped * 100 / 255)}%", Toast.LENGTH_SHORT).show()
                    }
                }
                "brightness_down" -> {
                    val stepped = PrivilegedToggleHelper.adjustBrightnessByStep(this, -35)
                    if (stepped != null) {
                        Toast.makeText(this, "Brightness: ${(stepped * 100 / 255)}%", Toast.LENGTH_SHORT).show()
                    }
                }
                "volume_panel" -> {
                    activePopupWindow?.dismiss()
                    val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
                    audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_SAME, AudioManager.FLAG_SHOW_UI)
                }
                "volume" -> {
                    activePopupWindow?.dismiss()
                    val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
                    audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_SAME, AudioManager.FLAG_SHOW_UI)
                }
                "media_play_pause" -> dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
                "media_next" -> dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_NEXT)
                "qr_scanner" -> triggerQrScanner()
                "mute" -> {
                    val audioManager = getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
                        if (!notificationManager.isNotificationPolicyAccessGranted) {
                            Toast.makeText(this, "Need Do Not Disturb permission.", Toast.LENGTH_SHORT).show()
                            val soundIntent = Intent(android.provider.Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS).apply {
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                            }
                            startActivity(soundIntent)
                        } else {
                            audioManager.ringerMode = android.media.AudioManager.RINGER_MODE_SILENT
                            Toast.makeText(this, "Silenced (Mute Mode Enabled)", Toast.LENGTH_SHORT).show()
                        }
                    } else {
                        audioManager.ringerMode = android.media.AudioManager.RINGER_MODE_SILENT
                        Toast.makeText(this, "Silenced (Mute Mode Enabled)", Toast.LENGTH_SHORT).show()
                    }
                }
                "dnd_mode" -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
                        if (!notificationManager.isNotificationPolicyAccessGranted) {
                            Toast.makeText(this, "Need Do Not Disturb permission.", Toast.LENGTH_SHORT).show()
                            val dndIntent = Intent(android.provider.Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS).apply {
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                            }
                            startActivity(dndIntent)
                        } else {
                            val currentFilter = notificationManager.currentInterruptionFilter
                            if (currentFilter == android.app.NotificationManager.INTERRUPTION_FILTER_NONE) {
                                notificationManager.setInterruptionFilter(android.app.NotificationManager.INTERRUPTION_FILTER_ALL)
                                Toast.makeText(this, "DND Disabled", Toast.LENGTH_SHORT).show()
                            } else {
                                notificationManager.setInterruptionFilter(android.app.NotificationManager.INTERRUPTION_FILTER_NONE)
                                Toast.makeText(this, "DND Enabled", Toast.LENGTH_SHORT).show()
                            }
                        }
                    } else {
                        Toast.makeText(this, "Not supported on this version of Android.", Toast.LENGTH_SHORT).show()
                    }
                }
                "auto_rotate" -> triggerOneTapQuickTileToggle(QuickTileAutomation.QuickTileTarget.AUTO_ROTATE)
                "dark_mode" -> {
                    val intent = Intent(android.provider.Settings.ACTION_DISPLAY_SETTINGS).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    startActivity(intent)
                }
                "airplane_mode" -> {
                    val intent = Intent(android.provider.Settings.ACTION_AIRPLANE_MODE_SETTINGS).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    startActivity(intent)
                }
                "hotspot" -> {
                    val intent = Intent().apply {
                        setClassName("com.android.settings", "com.android.settings.TetherSettings")
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    if (intent.resolveActivity(pm) != null) {
                        startActivity(intent)
                    } else {
                        val fallback = Intent(android.provider.Settings.ACTION_WIRELESS_SETTINGS).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        }
                        startActivity(fallback)
                    }
                }
                "camera" -> {
                    val intent = Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE)
                    val targetIntent = if (intent.resolveActivity(pm) != null) {
                        intent
                    } else {
                        pm.getLaunchIntentForPackage("com.android.camera")
                            ?: pm.getLaunchIntentForPackage("com.sec.android.app.camera")
                    }
                    if (targetIntent != null) {
                        activePopupWindow?.dismiss()
                        FreeformWindowLauncher.launchFullscreen(this, targetIntent, "Camera")
                    } else {
                        Toast.makeText(context, "Camera App could not be launched.", Toast.LENGTH_SHORT).show()
                    }
                }
                "gallery" -> {
                    val intent = Intent(Intent.ACTION_VIEW).apply {
                        type = "image/*"
                    }
                    val targetIntent = if (intent.resolveActivity(pm) != null) {
                        intent
                    } else {
                        pm.getLaunchIntentForPackage("com.google.android.apps.photos")
                            ?: pm.getLaunchIntentForPackage("com.sec.android.gallery3d")
                    }
                    if (targetIntent != null) {
                        activePopupWindow?.dismiss()
                        FreeformWindowLauncher.launchFullscreen(this, targetIntent, "Gallery")
                    } else {
                        Toast.makeText(context, "Gallery App not found.", Toast.LENGTH_SHORT).show()
                    }
                }
                "calculator" -> {
                    activePopupWindow?.dismiss()
                    launchCalculator()
                }
                "chrome" -> {
                    activePopupWindow?.dismiss()
                    launchChrome()
                }
                "whatsapp" -> {
                    activePopupWindow?.dismiss()
                    launchWhatsApp()
                }
                "youtube" -> {
                    val targetIntent = pm.getLaunchIntentForPackage("com.google.android.youtube")
                    activePopupWindow?.dismiss()
                    if (targetIntent != null) {
                        FreeformWindowLauncher.launchFullscreen(this, targetIntent, "YouTube")
                    } else {
                        FloatingMiniApps.openFloatingYouTube(this, getOrCreateFloatingWindowManager(), getConfiguredAccentColor())
                    }
                }
                "gmail" -> {
                    val intent = pm.getLaunchIntentForPackage("com.google.android.gm")
                    if (intent != null) {
                        activePopupWindow?.dismiss()
                        FreeformWindowLauncher.launchFullscreen(this, intent, "Gmail")
                    } else {
                        Toast.makeText(context, "Gmail not installed.", Toast.LENGTH_SHORT).show()
                    }
                }
                "files" -> {
                    val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                        type = "*/*"
                    }
                    val targetIntent = pm.getLaunchIntentForPackage("com.google.android.apps.nbu.files")
                        ?: pm.getLaunchIntentForPackage("com.android.documentsui")
                        ?: intent.takeIf { it.resolveActivity(pm) != null }
                    if (targetIntent != null) {
                        activePopupWindow?.dismiss()
                        FreeformWindowLauncher.launchFullscreen(this, targetIntent, "Files")
                    } else {
                        FloatingMiniApps.openFloatingViewer(this, getOrCreateFloatingWindowManager(), getConfiguredAccentColor())
                    }
                }
                "settings" -> launchSettings()
                "about_app" -> {
                    val homeIntent = Intent(this, MainActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                        putExtra("open_tab", "about")
                    }
                    startActivity(homeIntent)
                }
                "clock" -> launchClockAlarm()
                "contacts" -> {
                    val intent = Intent(Intent.ACTION_VIEW, android.provider.ContactsContract.Contacts.CONTENT_URI)
                    activePopupWindow?.dismiss()
                    FreeformWindowLauncher.launchFullscreen(this, intent, "Contacts")
                }
                "messages" -> {
                    val intent = Intent(Intent.ACTION_MAIN).apply {
                        addCategory(Intent.CATEGORY_APP_MESSAGING)
                    }
                    val targetIntent = if (intent.resolveActivity(pm) != null) {
                        intent
                    } else {
                        Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:"))
                    }
                    activePopupWindow?.dismiss()
                    FreeformWindowLauncher.launchFullscreen(this, targetIntent, "Messages")
                }
                "custom_app" -> {
                    val customPkg = context.getSharedPreferences("smart_dot_prefs", Context.MODE_PRIVATE)
                        .getString("custom_app_package", "") ?: ""
                    if (customPkg.isNotEmpty()) {
                        val intent = pm.getLaunchIntentForPackage(customPkg)
                        if (intent != null) {
                            activePopupWindow?.dismiss()
                            FreeformWindowLauncher.launchFullscreen(this, intent, customPkg)
                        } else {
                            Toast.makeText(context, "App payload '$customPkg' not found.", Toast.LENGTH_SHORT).show()
                        }
                    } else {
                        Toast.makeText(context, "Configure custom package in app dashboard first.", Toast.LENGTH_SHORT).show()
                        launchSettings()
                    }
                }
                else -> {
                    when {
                        isCustomWhatsAppShortcut(actionId) -> {
                            activePopupWindow?.dismiss()
                            val waShortcut = findCustomWhatsAppShortcut(prefs, actionId)
                            if (waShortcut != null) {
                                FloatingMiniApps.openFloatingSocialApp(
                                    context = this,
                                    manager = getOrCreateFloatingWindowManager(),
                                    accentColor = getConfiguredAccentColor(),
                                    appId = "whatsapp",
                                    overrideUrl = waShortcut.buildDirectChatUrl(),
                                    overrideTitle = waShortcut.title
                                )
                            } else {
                                FloatingMiniApps.openFloatingSocialApp(
                                    context = this,
                                    manager = getOrCreateFloatingWindowManager(),
                                    accentColor = getConfiguredAccentColor(),
                                    appId = "whatsapp"
                                )
                            }
                        }
                        InAppShortcutLauncher.isInAppShortcut(actionId) -> {
                            activePopupWindow?.dismiss()
                            InAppShortcutLauncher.launch(this, actionId)
                        }
                        else -> {
                            Toast.makeText(context, "Action: $actionId executed", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(context, "Error executing action: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun createShortcutItemView(
        actionId: String,
        accentColor: Int,
        density: Float,
        rowHeightDp: Float,
        gridCols: Int,
        hideShortcutLabels: Boolean = prefs.getBoolean(PREF_HIDE_SHORTCUT_LABELS, false)
    ): View {
        val isEmpty = actionId.isEmpty() || actionId == "add_shortcut" || actionId == "empty"
        val actionLabel = if (isEmpty) "Add" else getActionName(actionId)
        val actionIconRes = if (isEmpty) android.R.drawable.ic_menu_add else getActionIcon(actionId)

        val prefs = getSharedPreferences("smart_dot_prefs", Context.MODE_PRIVATE)
        val iconSizePreset = prefs.getString("shortcut_icon_size_preset", "Medium") ?: "Medium"
        val (baseIconSizeDp, baseLabelTextSp) = when (iconSizePreset) {
            "Small" -> Pair(20f, 9.5f)
            "Medium" -> Pair(26f, 10.5f)
            "Large" -> Pair(32f, 11.5f)
            "Extra Large" -> Pair(38f, 13f)
            else -> Pair(26f, 10.5f)
        }
        val colScale = when {
            gridCols >= 5 -> 0.72f
            gridCols == 4 -> 0.85f
            else -> 1.0f
        }
        val maxIconRoom = if (hideShortcutLabels) (rowHeightDp - 8f).coerceAtLeast(16f) else (rowHeightDp - 22f).coerceAtLeast(14f)
        val iconSizeDp = (baseIconSizeDp * colScale).coerceAtMost(maxIconRoom)
        val labelTextSp = when {
            gridCols >= 5 -> (baseLabelTextSp - 1.5f).coerceAtLeast(8f)
            gridCols == 4 -> (baseLabelTextSp - 0.8f).coerceAtLeast(8.5f)
            else -> baseLabelTextSp
        }

        val itemGapPx = (2 * density).toInt()
        val itemLayout = LinearLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.MATCH_PARENT,
                1f
            ).apply {
                setMargins(itemGapPx, 0, itemGapPx, 0)
            }
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            val hPadPx = (2 * density).toInt()
            val vPadPx = (1 * density).toInt()
            setPadding(hPadPx, vPadPx, hPadPx, vPadPx)
            background = getSystemDrawable(android.R.attr.selectableItemBackground)
            isClickable = true
            isFocusable = true
            contentDescription = actionLabel
        }

        val iconImage = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                (iconSizeDp * density).toInt(),
                (iconSizeDp * density).toInt()
            ).apply {
                bottomMargin = when {
                    hideShortcutLabels -> 0
                    rowHeightDp < 60f || gridCols >= 5 -> (1 * density).toInt()
                    else -> (2 * density).toInt()
                }
            }
            setImageResource(actionIconRes)
            if (isEmpty) {
                setColorFilter(Color.parseColor("#475569"), PorterDuff.Mode.SRC_IN)
                alpha = 0.5f
            } else {
                setColorFilter(accentColor, PorterDuff.Mode.SRC_IN)
            }
        }

        val labelText = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            text = actionLabel
            setTextColor(if (isEmpty) Color.parseColor("#64748B") else Color.WHITE)
            textSize = labelTextSp
            includeFontPadding = false
            setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            visibility = if (hideShortcutLabels) View.GONE else View.VISIBLE
        }

        itemLayout.addView(iconImage)
        itemLayout.addView(labelText)
        return itemLayout
    }

    private fun bindCustomShortcutsGrid(popupView: View, accentColor: Int, density: Float) {
        val viewPager = popupView.findViewById<androidx.viewpager.widget.ViewPager>(R.id.vp_shortcuts_pager) ?: return
        val indicatorsContainer = popupView.findViewById<LinearLayout>(R.id.page_indicators_container)

        val prefs = getSharedPreferences("smart_dot_prefs", Context.MODE_PRIVATE)
        val gridRows = prefs.getInt("menu_grid_rows", 3).coerceAtLeast(1)
        val gridCols = prefs.getInt("menu_grid_cols", 3).coerceIn(1, 5)
        val hideShortcutLabels = prefs.getBoolean(PREF_HIDE_SHORTCUT_LABELS, false)

        val pages = getShortcutsPages(prefs)
        val pagesList = ArrayList<View>()

        val popupHeightDp = prefs.getInt("menu_height_dp", 460)
        val overheadDp = if (hideShortcutLabels) 66f else 78f
        val rowHeightDp = ((popupHeightDp - overheadDp - gridRows * 2f) / gridRows.toFloat()).coerceAtLeast(36f)
        val pagePadPx = (2 * density).toInt()

        for (p in pages.indices) {
            val pageShortcuts = pages[p]
            Log.i("FloatingService", "Shortcuts loaded per page: pageIndex=$p, count=${pageShortcuts.size}")
            val pageLayout = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                weightSum = gridRows.toFloat()
                setPadding(pagePadPx, pagePadPx, pagePadPx, pagePadPx)
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            }

            for (r in 0 until gridRows) {
                val rowLayout = LinearLayout(this).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        0,
                        1f
                    ).apply {
                        bottomMargin = if (r < gridRows - 1) (2 * density).toInt() else 0
                    }
                    orientation = LinearLayout.HORIZONTAL
                    weightSum = gridCols.toFloat()
                }

                for (c in 0 until gridCols) {
                    val indexInPage = r * gridCols + c
                    if (indexInPage < pageShortcuts.size) {
                        val actionId = pageShortcuts[indexInPage]
                        val itemLayout = createShortcutItemView(
                            actionId = actionId,
                            accentColor = accentColor,
                            density = density,
                            rowHeightDp = rowHeightDp,
                            gridCols = gridCols,
                            hideShortcutLabels = hideShortcutLabels
                        )
                        
                        val isEmpty = actionId.isEmpty() || actionId == "add_shortcut" || actionId == "empty"
                        if (isEmpty) {
                            itemLayout.setOnClickListener {
                                showShortcutSelectorDialog { selectedId ->
                                    val updatedPages = getShortcutsPages(prefs).map { it.toMutableList() }
                                    if (p < updatedPages.size && indexInPage < updatedPages[p].size) {
                                        updatedPages[p][indexInPage] = selectedId
                                        saveShortcutsPages(prefs, updatedPages)
                                        // Refresh the correct initiator (sidebar or floating dot)
                                        if (isPopupAlignedToEdge) {
                                            showSidebarPanel()
                                        } else {
                                            showAssistantSidebar()
                                        }
                                    }
                                }
                            }
                        } else {
                            itemLayout.setOnClickListener {
                                executeAction(actionId)
                            }
                            itemLayout.setOnLongClickListener {
                                showShortcutSelectorDialog { selectedId ->
                                    val updatedPages = getShortcutsPages(prefs).map { it.toMutableList() }
                                    if (p < updatedPages.size && indexInPage < updatedPages[p].size) {
                                        updatedPages[p][indexInPage] = selectedId
                                        saveShortcutsPages(prefs, updatedPages)
                                        // Refresh the correct initiator (sidebar or floating dot)
                                        if (isPopupAlignedToEdge) {
                                            showSidebarPanel()
                                        } else {
                                            showAssistantSidebar()
                                        }
                                    }
                                }
                                true
                            }
                        }
                        
                        rowLayout.addView(itemLayout)
                    } else {
                        val emptyCell = View(this).apply {
                            layoutParams = LinearLayout.LayoutParams(
                                0,
                                LinearLayout.LayoutParams.MATCH_PARENT,
                                1f
                            )
                        }
                        rowLayout.addView(emptyCell)
                    }
                }
                pageLayout.addView(rowLayout)
            }
            pagesList.add(pageLayout)
        }

        val pagerAdapter = object : androidx.viewpager.widget.PagerAdapter() {
            override fun getCount(): Int = pagesList.size
            override fun isViewFromObject(view: View, `object`: Any): Boolean = view === `object`
            override fun instantiateItem(container: ViewGroup, position: Int): Any {
                val pView = pagesList[position]
                container.addView(pView)
                return pView
            }
            override fun destroyItem(container: ViewGroup, position: Int, `object`: Any) {
                container.removeView(`object` as View)
            }
        }
        viewPager.adapter = pagerAdapter

        indicatorsContainer?.removeAllViews()
        val dots = ArrayList<View>()
        if (pages.size > 1) {
            for (i in pages.indices) {
                val dot = View(this).apply {
                    val dotSize = (6 * density).toInt()
                    val margin = (4 * density).toInt()
                    layoutParams = LinearLayout.LayoutParams(dotSize, dotSize).apply {
                        setMargins(margin, 0, margin, 0)
                    }
                    background = android.graphics.drawable.GradientDrawable().apply {
                        shape = android.graphics.drawable.GradientDrawable.OVAL
                        setColor(if (i == 0) accentColor else Color.parseColor("#475569"))
                    }
                }
                indicatorsContainer?.addView(dot)
                dots.add(dot)
            }
        }

        viewPager.clearOnPageChangeListeners()
        viewPager.addOnPageChangeListener(object : androidx.viewpager.widget.ViewPager.OnPageChangeListener {
            private var lastSelectedPage = 0
            override fun onPageScrolled(p: Int, pOffset: Float, pOffsetPx: Int) {}
            override fun onPageSelected(position: Int) {
                val swipeDirection = if (position > lastSelectedPage) "Left (Next)" else "Right (Previous)"
                Log.i("FloatingService", "Page swipe direction: $swipeDirection")
                Log.i("FloatingService", "Current page number: $position")
                lastSelectedPage = position

                for (i in dots.indices) {
                    val dotBg = dots[i].background as? android.graphics.drawable.GradientDrawable
                    dotBg?.setColor(if (i == position) accentColor else Color.parseColor("#475569"))
                }
            }
            override fun onPageScrollStateChanged(state: Int) {}
        })
    }

    // -------------------------------------------------------------
    // Tab 3: Live Synchronized Clipboard Manager
    // -------------------------------------------------------------
    private fun bindClipboardTab(popupView: View, accentColor: Int) {
        val scrollerContainer = popupView.findViewById<LinearLayout>(R.id.ll_clipboard_scroller)
        val btnClear = popupView.findViewById<TextView>(R.id.btn_clear_clipboard)
        val btnScanScreen = popupView.findViewById<Button>(R.id.btn_clipboard_scan_screen)

        btnScanScreen?.apply {
            backgroundTintList = null
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(accentColor)
                cornerRadius = 8f * resources.displayMetrics.density
            }
            setOnClickListener {
                startScreenTextCopy()
            }
        }

        btnClear.setOnClickListener {
            serviceScope.launch {
                withContext(Dispatchers.IO) {
                    SmartDotDatabase.getDatabase(this@FloatingService).smartDotDao().clearAllClips()
                }
                Toast.makeText(this@FloatingService, "Clipboard history cleared.", Toast.LENGTH_SHORT).show()
            }
        }

        // Reactive DB collect streaming updates right inside active popup session scope
        val flowJob = popupSessionJob ?: serviceScope.coroutineContext[Job]
        serviceScope.launch(flowJob ?: Dispatchers.Main) {
            SmartDotDatabase.getDatabase(this@FloatingService).smartDotDao().getAllClipsFlow()
                .collectLatest { clipList ->
                    scrollerContainer.removeAllViews()
                    if (clipList.isEmpty()) {
                        val emptyText = TextView(this@FloatingService).apply {
                            text = "No clippings logged yet. Any text you copy anywhere is saved here!"
                            setTextColor(Color.parseColor("#475569"))
                            textSize = 12f
                            setPadding(12, 12, 12, 12)
                            gravity = Gravity.CENTER
                        }
                        scrollerContainer.addView(emptyText)
                    } else {
                        for (clip in clipList) {
                            val clipRow = buildClipRowView(clip, accentColor)
                            scrollerContainer.addView(clipRow)
                        }
                    }
                }
        }
    }

    private fun buildClipRowView(clip: ClipEntry, accentColor: Int): View {
        val density = resources.displayMetrics.density
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((8 * density).toInt(), (6 * density).toInt(), (8 * density).toInt(), (6 * density).toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = (6 * density).toInt()
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(Color.parseColor("#1E293B"))
                cornerRadius = 8 * density
            }
        }

        val textContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val clipText = TextView(this).apply {
            text = clip.text
            setTextColor(Color.WHITE)
            textSize = 12f
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
        }

        val timeStr = SimpleDateFormat("HH:mm - dd MMM", Locale.getDefault()).format(Date(clip.timestamp))
        val clipTime = TextView(this).apply {
            text = timeStr
            setTextColor(Color.parseColor("#64748B"))
            textSize = 9f
            setPadding(0, 2, 0, 0)
        }

        textContainer.addView(clipText)
        textContainer.addView(clipTime)

        // Utility: Copy Button
        val btnCopy = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams((28 * density).toInt(), (28 * density).toInt()).apply {
                marginEnd = 4
            }
            setImageResource(android.R.drawable.ic_menu_edit)
            setColorFilter(accentColor, PorterDuff.Mode.SRC_IN)
            setPadding(6, 6, 6, 6)
            isClickable = true
            isFocusable = true
            setOnClickListener {
                val clipData = android.content.ClipData.newPlainText("Copied from Floating Shortcuts", clip.text)
                clipboardManager.setPrimaryClip(clipData)
                Toast.makeText(this@FloatingService, "Clip copied to clipboard!", Toast.LENGTH_SHORT).show()
            }
        }

        // Utility: Delete Button
        val btnDelete = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams((28 * density).toInt(), (28 * density).toInt())
            setImageResource(android.R.drawable.ic_menu_delete)
            setColorFilter(Color.parseColor("#EF4444"), PorterDuff.Mode.SRC_IN)
            setPadding(6, 6, 6, 6)
            isClickable = true
            isFocusable = true
            setOnClickListener {
                serviceScope.launch {
                    withContext(Dispatchers.IO) {
                        SmartDotDatabase.getDatabase(this@FloatingService).smartDotDao().deleteClipById(clip.id)
                    }
                    Toast.makeText(this@FloatingService, "Clip removed.", Toast.LENGTH_SHORT).show()
                }
            }
        }

        row.addView(textContainer)
        row.addView(btnCopy)
        row.addView(btnDelete)
        return row
    }

    private fun saveClipboardData() {
        val clip = clipboardManager.primaryClip
        if (clip != null && clip.itemCount > 0) {
            val text = clip.getItemAt(0).text?.toString() ?: ""
            if (screenTextCopyController?.shouldSkipClipboardEcho(text) == true ||
                screenTranslateController?.shouldSkipClipboardEcho(text) == true
            ) {
                return
            }
            if (text.isNotEmpty() && text.length < 500) { // Keep storage bounds optimized
                serviceScope.launch {
                    withContext(Dispatchers.IO) {
                        val db = SmartDotDatabase.getDatabase(this@FloatingService)
                        // Don't log duplicates back-to-back
                        db.smartDotDao().insertClip(ClipEntry(text = text))
                    }
                }
            }
        }
    }

    private fun setOverlayViewsVisible(visible: Boolean) {
        val visibility = if (visible) View.VISIBLE else View.INVISIBLE
        if (::floatingView.isInitialized && isFloatingViewAdded) {
            floatingView.visibility = visibility
        }
        sidebarView?.visibility = visibility
    }

    private fun getOrCreateScreenTextCopyController(): ScreenTextCopyController {
        return screenTextCopyController ?: ScreenTextCopyController(
            context = this,
            serviceScope = serviceScope,
            clipboardManager = clipboardManager,
            getAccentColor = {
                val hex = prefs.getString("dot_color", "#00E5FF") ?: "#00E5FF"
                try {
                    Color.parseColor(hex)
                } catch (_: Exception) {
                    Color.parseColor("#00E5FF")
                }
            },
            dismissActivePopup = {
                if (activePopupWindow?.isShowing == true) {
                    activePopupWindow?.dismiss()
                }
            },
            setHostOverlaysVisible = { visible ->
                setOverlayViewsVisible(visible)
            },
            onAccessibilityRequired = {
                showAccessibilityDialog()
            },
            onFullScreenTranslateRequested = {
                startScreenTranslate()
            },
            getToolbarOrder = {
                SelectionToolbarHelper.getVisibleToolbarOrder(prefs)
            },
            customFloatingAiBrowserLauncher = { providerId, textOrQuery, question, cutoutBitmap ->
                val fwm = getOrCreateFloatingWindowManager()
                val accent = getConfiguredAccentColor()
                when (providerId) {
                    "chatgpt" -> FloatingMiniApps.openChatGptInBrowser(
                        context = this,
                        manager = fwm,
                        accentColor = accent,
                        text = textOrQuery,
                        question = question,
                        cutoutBitmap = cutoutBitmap
                    )
                    else -> FloatingMiniApps.openGoogleAiModeInBrowser(
                        context = this,
                        manager = fwm,
                        accentColor = accent,
                        query = textOrQuery,
                        question = question,
                        cutoutBitmap = cutoutBitmap
                    )
                }
                true
            }
        ).also { screenTextCopyController = it }
    }

    private fun startScreenTextCopy() {
        getOrCreateScreenTextCopyController().start()
    }

    private fun getOrCreateScreenTranslateController(): ScreenTranslateController {
        return screenTranslateController ?: ScreenTranslateController(
            context = this,
            serviceScope = serviceScope,
            clipboardManager = clipboardManager,
            getAccentColor = {
                val hex = prefs.getString("dot_color", "#00E5FF") ?: "#00E5FF"
                try {
                    Color.parseColor(hex)
                } catch (_: Exception) {
                    Color.parseColor("#00E5FF")
                }
            },
            getSavedTargetLanguage = {
                prefs.getString("translate_target_language", "Hindi") ?: "Hindi"
            },
            saveTargetLanguage = { lang ->
                prefs.edit().putString("translate_target_language", lang).apply()
            },
            dismissActivePopup = {
                if (activePopupWindow?.isShowing == true) {
                    activePopupWindow?.dismiss()
                }
            },
            setHostOverlaysVisible = { visible ->
                setOverlayViewsVisible(visible)
            },
            onAccessibilityRequired = {
                showAccessibilityDialog()
            },
            getSavedOnlineMode = {
                prefs.getBoolean("translate_online_mode", false)
            },
            saveOnlineMode = { useOnline ->
                prefs.edit().putBoolean("translate_online_mode", useOnline).apply()
            }
        ).also { screenTranslateController = it }
    }

    private fun startScreenTranslate(targetLanguage: String? = null) {
        getOrCreateScreenTranslateController().start(targetLanguage)
    }

    // -------------------------------------------------------------
    // Tab 4: Dynamic Quick Jot-down Local Notes (With Title & Edit Mode)
    // -------------------------------------------------------------
    private fun bindNotesTab(popupView: View, accentColor: Int) {
        val notesScroller = popupView.findViewById<LinearLayout>(R.id.ll_notes_scroller)
        val etNoteTitle = popupView.findViewById<EditText>(R.id.et_note_title)
        val etNote = popupView.findViewById<EditText>(R.id.et_note_input)
        val btnSaveNote = popupView.findViewById<ImageView>(R.id.btn_save_note)
        val editBanner = popupView.findViewById<LinearLayout>(R.id.ll_note_edit_banner)
        val editStatus = popupView.findViewById<TextView>(R.id.tv_note_edit_status)
        val btnCancelEdit = popupView.findViewById<TextView>(R.id.btn_cancel_note_edit)

        var editingNote: NoteEntry? = null

        fun exitEditMode() {
            editingNote = null
            editBanner?.visibility = View.GONE
            etNoteTitle?.setText("")
            etNote.setText("")
        }

        fun enterEditMode(note: NoteEntry) {
            editingNote = note
            editBanner?.visibility = View.VISIBLE
            editStatus?.text = "Editing: ${note.title.ifBlank { note.content.take(18) }}"
            etNoteTitle?.setText(note.title)
            etNote.setText(note.content)
            etNote.setSelection(etNote.text.length)
        }

        btnSaveNote.setColorFilter(accentColor, PorterDuff.Mode.SRC_IN)
        btnCancelEdit?.setOnClickListener { exitEditMode() }

        btnSaveNote.setOnClickListener {
            val noteTitle = etNoteTitle?.text?.toString()?.trim().orEmpty()
            val noteContent = etNote.text.toString().trim()
            if (noteContent.isEmpty()) {
                Toast.makeText(this, "Note can't be blank", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val currentEditing = editingNote
            serviceScope.launch {
                withContext(Dispatchers.IO) {
                    val dao = SmartDotDatabase.getDatabase(this@FloatingService).smartDotDao()
                    if (currentEditing != null) {
                        dao.updateNote(
                            currentEditing.copy(
                                title = noteTitle,
                                content = noteContent,
                                timestamp = System.currentTimeMillis()
                            )
                        )
                    } else {
                        dao.insertNote(NoteEntry(title = noteTitle, content = noteContent))
                    }
                }
                exitEditMode()
                Toast.makeText(
                    this@FloatingService,
                    if (currentEditing != null) "Note updated!" else "Note added!",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

        // Handle streaming DB updates
        val flowJob = popupSessionJob ?: serviceScope.coroutineContext[Job]
        serviceScope.launch(flowJob ?: Dispatchers.Main) {
            SmartDotDatabase.getDatabase(this@FloatingService).smartDotDao().getAllNotesFlow()
                .collectLatest { notesList ->
                    notesScroller.removeAllViews()
                    if (notesList.isEmpty()) {
                        val emptyText = TextView(this@FloatingService).apply {
                            text = "You haven't added any notes yet. Write a note above to record it forever!"
                            setTextColor(Color.parseColor("#475569"))
                            textSize = 12f
                            setPadding(12, 12, 12, 12)
                            gravity = Gravity.CENTER
                        }
                        notesScroller.addView(emptyText)
                    } else {
                        for (note in notesList) {
                            val row = buildNoteRowView(note, onEditClicked = { selected ->
                                enterEditMode(selected)
                            }, onDeleted = { deletedId ->
                                if (editingNote?.id == deletedId) {
                                    exitEditMode()
                                }
                            })
                            notesScroller.addView(row)
                        }
                    }
                }
        }
    }

    private fun buildNoteRowView(
        note: NoteEntry,
        onEditClicked: (NoteEntry) -> Unit,
        onDeleted: (Long) -> Unit
    ): View {
        val density = resources.displayMetrics.density
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((8 * density).toInt(), (8 * density).toInt(), (8 * density).toInt(), (8 * density).toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = (8 * density).toInt()
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(Color.parseColor("#1E293B"))
                cornerRadius = 10 * density
            }
            isClickable = true
            isFocusable = true
            contentDescription = "Edit note ${note.title.ifBlank { note.content.take(20) }}"
            setOnClickListener { onEditClicked(note) }
        }

        val textContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        if (note.title.isNotBlank()) {
            val titleView = TextView(this).apply {
                text = note.title
                setTextColor(Color.parseColor("#38BDF8"))
                textSize = 12.5f
                setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
            }
            textContainer.addView(titleView)
        }

        val noteText = TextView(this).apply {
            text = note.content
            setTextColor(Color.WHITE)
            textSize = 12.5f
        }

        val noteTimeStr = SimpleDateFormat("HH:mm - dd MMM", Locale.getDefault()).format(Date(note.timestamp))
        val noteTime = TextView(this).apply {
            text = "$noteTimeStr  • Tap to edit"
            setTextColor(Color.parseColor("#64748B"))
            textSize = 9f
            setPadding(0, 2, 0, 0)
        }

        textContainer.addView(noteText)
        textContainer.addView(noteTime)

        // Delete button
        val btnDeleteNote = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams((28 * density).toInt(), (28 * density).toInt())
            setImageResource(android.R.drawable.ic_menu_delete)
            setColorFilter(Color.parseColor("#EF4444"), PorterDuff.Mode.SRC_IN)
            setPadding(6, 6, 6, 6)
            isClickable = true
            isFocusable = true
            contentDescription = "Delete note"
            setOnClickListener {
                serviceScope.launch {
                    withContext(Dispatchers.IO) {
                        SmartDotDatabase.getDatabase(this@FloatingService).smartDotDao().deleteNoteById(note.id)
                    }
                    onDeleted(note.id)
                    Toast.makeText(this@FloatingService, "Note deleted.", Toast.LENGTH_SHORT).show()
                }
            }
        }

        row.addView(textContainer)
        row.addView(btnDeleteNote)
        return row
    }

    // -------------------------------------------------------------
    // Tab 5: Real Working Mini-Calculator UI Logic (Grid Layout)
    // -------------------------------------------------------------
    private var currentExpr = ""

    private fun bindCalculatorTab(popupView: View, accentColor: Int) {
        val screen = popupView.findViewById<TextView>(R.id.tv_calc_screen)
        val previewScreen = popupView.findViewById<TextView>(R.id.tv_calc_preview)

        fun updateDisplay(hidePreview: Boolean = false) {
            screen.text = if (currentExpr.isEmpty()) "0" else currentExpr
            if (hidePreview) {
                previewScreen?.text = ""
                previewScreen?.visibility = View.GONE
                return
            }
            val livePreview = CalculatorEvaluator.evaluateLivePreview(currentExpr)
            if (livePreview != null) {
                previewScreen?.text = "= $livePreview"
                previewScreen?.setTextColor(accentColor)
                previewScreen?.visibility = View.VISIBLE
            } else {
                previewScreen?.text = ""
                previewScreen?.visibility = View.GONE
            }
        }

        updateDisplay()

        fun appendCalc(char: String) {
            if (currentExpr == "0" || currentExpr == "Error" || currentExpr == "Div By Zero") {
                currentExpr = ""
            }
            currentExpr += char
            updateDisplay()
        }

        val btnIds = listOf(
            R.id.btn_calc_0, R.id.btn_calc_1, R.id.btn_calc_2,
            R.id.btn_calc_3, R.id.btn_calc_4, R.id.btn_calc_5,
            R.id.btn_calc_6, R.id.btn_calc_7, R.id.btn_calc_8, R.id.btn_calc_9
        )

        for (i in 0..9) {
            popupView.findViewById<Button>(btnIds[i]).setOnClickListener {
                appendCalc(i.toString())
            }
        }

        popupView.findViewById<Button>(R.id.btn_calc_dot).setOnClickListener { appendCalc(".") }
        popupView.findViewById<Button>(R.id.btn_calc_add).setOnClickListener { appendCalc("+") }
        popupView.findViewById<Button>(R.id.btn_calc_sub).setOnClickListener { appendCalc("-") }
        popupView.findViewById<Button>(R.id.btn_calc_mul).setOnClickListener { appendCalc("*") }
        popupView.findViewById<Button>(R.id.btn_calc_div).setOnClickListener { appendCalc("/") }
        popupView.findViewById<Button>(R.id.btn_calc_percent).setOnClickListener { appendCalc("%") }

        popupView.findViewById<Button>(R.id.btn_calc_clear).setOnClickListener {
            currentExpr = ""
            updateDisplay(hidePreview = true)
        }

        popupView.findViewById<Button>(R.id.btn_calc_back).setOnClickListener {
            if (currentExpr.isNotEmpty()) {
                currentExpr = currentExpr.substring(0, currentExpr.length - 1)
            }
            updateDisplay()
        }

        // Core interactive evaluation algorithm
        popupView.findViewById<Button>(R.id.btn_calc_eq).apply {
            setBackgroundColor(accentColor)
            setOnClickListener {
                if (currentExpr.isNotEmpty()) {
                    val result = CalculatorEvaluator.evaluateFinal(currentExpr)
                    currentExpr = result
                    updateDisplay(hidePreview = true)
                }
            }
        }
    }

    private fun evaluateMathExpression(expr: String): String {
        return CalculatorEvaluator.evaluateFinal(expr)
    }

    // -------------------------------------------------------------
    // Tab 6: Google Lens-style Screen & Phrase Translator (Fast Online + Offline ML Kit Fallback)
    // -------------------------------------------------------------
    private fun bindTranslateTab(popupView: View, accentColor: Int) {
        val spTarget = popupView.findViewById<Spinner>(R.id.sp_lang_target)
        val btnTranslateScreen = popupView.findViewById<Button>(R.id.btn_translate_screen)
        val etInput = popupView.findViewById<EditText>(R.id.et_translate_input)
        val btnRun = popupView.findViewById<Button>(R.id.btn_trigger_translate)
        val tvOutput = popupView.findViewById<TextView>(R.id.tv_translate_output)
        val btnCopy = popupView.findViewById<Button>(R.id.btn_copy_translation)

        btnTranslateScreen?.apply {
            backgroundTintList = null
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(accentColor)
                cornerRadius = 8f * resources.displayMetrics.density
            }
        }

        btnRun.backgroundTintList = null
        btnRun.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(accentColor)
            cornerRadius = 8f * resources.displayMetrics.density
        }

        val spinnerAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, translateLanguages)
        spTarget.adapter = spinnerAdapter
        val savedTarget = prefs.getString("translate_target_language", "Hindi") ?: "Hindi"
        val defaultIdx = translateLanguages.indexOf(savedTarget).takeIf { it >= 0 } ?: 0
        spTarget.setSelection(defaultIdx)
        spTarget.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                val chosen = translateLanguages.getOrNull(position) ?: return
                prefs.edit().putString("translate_target_language", chosen).apply()
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }

        btnTranslateScreen?.setOnClickListener {
            val selection = spTarget.selectedItem?.toString() ?: "Hindi"
            prefs.edit().putString("translate_target_language", selection).apply()
            startScreenTranslate(selection)
        }

        btnRun.setOnClickListener {
            val text = etInput.text.toString().trim()
            if (text.isEmpty()) {
                Toast.makeText(this, "Type a phrase to translate.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val selection = spTarget.selectedItem?.toString() ?: "Hindi"
            val preferOnline = prefs.getBoolean("translate_online_mode", false)
            tvOutput.text = "Translating..."
            serviceScope.launch {
                try {
                    val helper = getOrCreateScreenTranslateController().getTranslateHelper()
                    val translated = helper.translateSingleText(
                        text = text,
                        targetLanguageName = selection,
                        preferOnline = preferOnline,
                        onModelDownloading = {
                            Handler(Looper.getMainLooper()).post {
                                Toast.makeText(
                                    this@FloatingService,
                                    getString(R.string.screen_translate_downloading_model),
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    )
                    tvOutput.text = translated
                } catch (e: Exception) {
                    Log.e("FloatingService", "On-device phrase translation failed", e)
                    tvOutput.text = "Translation failed: ${e.localizedMessage ?: "Check connection for first model download"}"
                }
            }
        }

        btnCopy.setOnClickListener {
            val translatedResponse = tvOutput.text.toString()
            if (translatedResponse.isNotEmpty() && translatedResponse != "..." && translatedResponse != "Translating...") {
                val clipData = android.content.ClipData.newPlainText("Translated text", translatedResponse)
                clipboardManager.setPrimaryClip(clipData)
                Toast.makeText(this, "Translation copied to clipboard!", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // -------------------------------------------------------------
    // Tab 7: Built-in Free AI Assistant (Ask AI & Screen Summary)
    // -------------------------------------------------------------
    private fun bindAiAssistantTab(popupView: View, accentColor: Int) {
        val tvStatus = popupView.findViewById<TextView>(R.id.tv_ai_mode_status)
        val btnSummarize = popupView.findViewById<Button>(R.id.btn_ai_summarize_screen)
        val etPrompt = popupView.findViewById<EditText>(R.id.et_ai_prompt)
        val btnSend = popupView.findViewById<Button>(R.id.btn_ai_send)
        val tvOutput = popupView.findViewById<TextView>(R.id.tv_ai_output)
        val btnExternal = popupView.findViewById<Button>(R.id.btn_ai_external)
        val btnCopy = popupView.findViewById<Button>(R.id.btn_ai_copy)

        val apiKey = AiAssistantHelper.getSavedApiKey(this)
        tvStatus?.text = if (apiKey.isNotEmpty()) "Gemini Ready" else "Free Web Fallback"
        tvStatus?.setTextColor(accentColor)

        val applyAccentButton: Button.() -> Unit = {
            backgroundTintList = null
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(accentColor)
                cornerRadius = 8f * resources.displayMetrics.density
            }
        }
        btnSummarize?.applyAccentButton()
        btnSend?.applyAccentButton()

        fun runAiPrompt(prompt: String) {
            val key = AiAssistantHelper.getSavedApiKey(this)
            if (key.isEmpty()) {
                tvOutput?.text = "No Gemini API key configured. Opening Gemini / ChatGPT..."
                if (!AiAssistantHelper.launchExternalAiFallback(this)) {
                    Toast.makeText(this, "No browser or AI app found.", Toast.LENGTH_SHORT).show()
                }
                return
            }
            tvOutput?.text = "Thinking with Gemini..."
            val flowJob = popupSessionJob ?: serviceScope.coroutineContext[Job]
            serviceScope.launch(flowJob ?: Dispatchers.Main) {
                val result = AiAssistantHelper.queryGemini(key, prompt)
                result.onSuccess { answer ->
                    tvOutput?.text = answer
                }.onFailure { err ->
                    tvOutput?.text = "AI error: ${err.localizedMessage ?: "Request failed"}\n\nTip: Tap 'Open Gemini / ChatGPT' below for free web fallback."
                }
            }
        }

        btnSummarize?.setOnClickListener {
            val screenText = AiAssistantHelper.collectVisibleScreenText()
            if (screenText.isBlank()) {
                tvOutput?.text = getString(R.string.ai_no_screen_text)
                Toast.makeText(this, getString(R.string.ai_no_screen_text), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val summaryPrompt = AiAssistantHelper.buildScreenSummaryPrompt(screenText)
            runAiPrompt(summaryPrompt)
        }

        btnSend?.setOnClickListener {
            val question = etPrompt?.text?.toString()?.trim().orEmpty()
            if (question.isEmpty()) {
                Toast.makeText(this, "Enter a question for AI.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            runAiPrompt(question)
        }

        btnExternal?.setOnClickListener {
            activePopupWindow?.dismiss()
            if (!AiAssistantHelper.launchExternalAiFallback(this)) {
                Toast.makeText(this, "No browser or AI app found.", Toast.LENGTH_SHORT).show()
            }
        }

        btnCopy?.setOnClickListener {
            val outputText = tvOutput?.text?.toString().orEmpty()
            if (outputText.isNotEmpty() && !outputText.startsWith("Tap 'Summarize")) {
                clipboardManager.setPrimaryClip(android.content.ClipData.newPlainText("AI Response", outputText))
                Toast.makeText(this, "AI answer copied!", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // -------------------------------------------------------------
    // MediaProjection Screen Recording + Floating Stop Pill
    // -------------------------------------------------------------
    private fun triggerScreenRecording() {
        activePopupWindow?.dismiss()
        val existingManager = screenRecorderManager
        if (existingManager?.isRecording == true) {
            stopScreenRecordingSession()
            return
        }
        if (existingManager?.hasActiveProjection() == true) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                runCatching {
                    ServiceCompat.startForeground(
                        this,
                        1001,
                        getForegroundNotification(),
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or
                            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                    )
                }
            }
            setOverlayViewsVisible(false)
            Handler(Looper.getMainLooper()).postDelayed({
                setOverlayViewsVisible(true)
                val reused = existingManager.startRecordingWithExistingProjection(lastRecordWithAudio)
                if (reused) {
                    Toast.makeText(this, getString(R.string.screen_record_started), Toast.LENGTH_SHORT).show()
                } else {
                    val permIntent = Intent(this, ScreenRecordPermissionActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    startActivity(permIntent)
                }
            }, 120L)
            return
        }
        val permIntent = Intent(this, ScreenRecordPermissionActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(permIntent)
    }

    private fun startScreenRecordingSession(resultCode: Int, data: Intent, withAudio: Boolean) {
        lastRecordWithAudio = withAudio
        val manager = screenRecorderManager ?: ScreenRecorderManager(
            context = this,
            onRecordingStateChanged = { recording ->
                Handler(Looper.getMainLooper()).post {
                    if (recording) {
                        showStopRecordingPill()
                    } else {
                        removeStopRecordingPill()
                    }
                }
            },
            onRecordingSaved = { uri ->
                Handler(Looper.getMainLooper()).post {
                    if (uri != null) {
                        Toast.makeText(this, getString(R.string.screen_record_saved), Toast.LENGTH_LONG).show()
                    }
                }
            },
            onRecordingError = { msg ->
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                }
            }
        ).also { screenRecorderManager = it }

        setOverlayViewsVisible(false)
        Handler(Looper.getMainLooper()).postDelayed({
            setOverlayViewsVisible(true)
            val started = manager.startRecording(resultCode, data, withAudio)
            if (started) {
                Toast.makeText(this, getString(R.string.screen_record_started), Toast.LENGTH_SHORT).show()
            }
        }, 120L)
    }

    private fun stopScreenRecordingSession() {
        screenRecorderManager?.stopRecording()
        removeStopRecordingPill()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            runCatching {
                ServiceCompat.startForeground(
                    this,
                    1001,
                    getForegroundNotification(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            }
        }
    }

    private fun showStopRecordingPill() {
        if (stopRecordingPillView != null || !::windowManager.isInitialized) return
        val pill = TextView(this).apply {
            id = View.generateViewId()
            tag = "btn_stop_recording_pill"
            text = "■ ${getString(R.string.screen_record_stop)}"
            setTextColor(Color.WHITE)
            textSize = 12f
            setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
            val padH = (12 * density).toInt()
            val padV = (8 * density).toInt()
            setPadding(padH, padV, padH, padV)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(Color.parseColor("#E11D48"))
                cornerRadius = 20 * density
                setStroke((1.5f * density).toInt(), Color.WHITE)
            }
            setOnClickListener {
                stopScreenRecordingSession()
            }
        }

        val pillParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = (12 * density).toInt()
            y = (80 * density).toInt()
        }

        stopRecordingPillView = pill
        runCatching { windowManager.addView(pill, pillParams) }.onFailure {
            stopRecordingPillView = null
        }
    }

    private fun removeStopRecordingPill() {
        stopRecordingPillView?.let { pill ->
            if (::windowManager.isInitialized) {
                runCatching { windowManager.removeView(pill) }
            }
        }
        stopRecordingPillView = null
    }

    // -------------------------------------------------------------
    // Shake Phone to Toggle Flashlight (Zero Vibration)
    // -------------------------------------------------------------
    private fun syncShakeTorchListener() {
        val manager = sensorManager ?: (getSystemService(Context.SENSOR_SERVICE) as? SensorManager)?.also {
            sensorManager = it
        } ?: return
        val sensitivity = prefs.getFloat(PREF_SHAKE_SENSITIVITY, 16f).coerceIn(12f, 26f)
        val enabled = prefs.getBoolean(PREF_SHAKE_TORCH_ENABLED, false)
        if (enabled) {
            val detector = shakeDetector ?: ShakeDetector(thresholdAccel = sensitivity) {
                Handler(Looper.getMainLooper()).post {
                    toggleFlashlight()
                }
            }.also { shakeDetector = it }
            detector.setThreshold(sensitivity)
            if (!isShakeListenerRegistered) {
                val accelerometer = manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return
                isShakeListenerRegistered = manager.registerListener(detector, accelerometer, SensorManager.SENSOR_DELAY_UI)
            }
        } else if (isShakeListenerRegistered) {
            shakeDetector?.let { manager.unregisterListener(it) }
            shakeDetector?.reset()
            isShakeListenerRegistered = false
        }
    }

    // -------------------------------------------------------------
    // Double-Tap Back of Phone to Toggle Mobile Data (Zero Vibration)
    // -------------------------------------------------------------
    private fun updateBackTapWakeLock(shouldHold: Boolean) {
        val enabled = prefs.getBoolean(PREF_BACK_TAP_DATA_ENABLED, false)
        val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
        val screenInteractive = powerManager?.isInteractive ?: true
        if (enabled && shouldHold && screenInteractive && powerManager != null) {
            val lock = backTapWakeLock ?: powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "SmartDot::BackTapWakeLock"
            ).apply {
                setReferenceCounted(false)
            }.also { backTapWakeLock = it }
            if (!lock.isHeld) {
                runCatching { lock.acquire(60 * 60 * 1000L) }
            }
        } else {
            backTapWakeLock?.let { lock ->
                if (lock.isHeld) {
                    runCatching { lock.release() }
                }
            }
        }
    }

    private fun syncBackTapListener() {
        val manager = sensorManager ?: (getSystemService(Context.SENSOR_SERVICE) as? SensorManager)?.also {
            sensorManager = it
        } ?: return
        val sensitivity = prefs.getFloat(
            PREF_BACK_TAP_SENSITIVITY,
            BackTapDetector.DEFAULT_SENSITIVITY
        ).coerceIn(BackTapDetector.MIN_SENSITIVITY, BackTapDetector.MAX_SENSITIVITY)
        val enabled = prefs.getBoolean(PREF_BACK_TAP_DATA_ENABLED, false)
        if (enabled) {
            val detector = backTapDetector ?: BackTapDetector(zSpikeThreshold = sensitivity) {
                Handler(Looper.getMainLooper()).post {
                    triggerOneTapQuickTileToggle(QuickTileAutomation.QuickTileTarget.MOBILE_DATA)
                }
            }.also { backTapDetector = it }
            detector.setThreshold(sensitivity)
            if (!isBackTapListenerRegistered) {
                val sensor = manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
                    ?: manager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
                    ?: return
                isBackTapListenerRegistered = manager.registerListener(
                    detector,
                    sensor,
                    SensorManager.SENSOR_DELAY_FASTEST
                )
            }
            updateBackTapWakeLock(shouldHold = true)
        } else {
            if (isBackTapListenerRegistered) {
                backTapDetector?.let { manager.unregisterListener(it) }
                backTapDetector?.reset()
                isBackTapListenerRegistered = false
            }
            updateBackTapWakeLock(shouldHold = false)
        }
    }

    // -------------------------------------------------------------
    // Core Utilities and Infrastructure Actions
    // -------------------------------------------------------------
    private fun performGlobalActionSafety(actionId: Int) {
        val sInstance = SmartDotAccessibilityService.instance
        if (sInstance != null) {
            sInstance.performGlobalAction(actionId)
        } else {
            showAccessibilityDialog()
        }
    }

    private fun showAccessibilityDialog() {
        if (isDialogShowing) return
        isDialogShowing = true

        val inflater = getSystemService(Context.LAYOUT_INFLATER_SERVICE) as LayoutInflater
        val dialogView = inflater.inflate(R.layout.dialog_accessibility_prompt, null)

        val dialogParams = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            )
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            )
        }

        dialogView.findViewById<View>(R.id.btn_cancel).setOnClickListener {
            try {
                windowManager.removeView(dialogView)
            } catch (e: Exception) {
                Log.e("FloatingService", "Discard dialog overlay failed", e)
            }
            isDialogShowing = false
        }

        dialogView.findViewById<View>(R.id.btn_enable).setOnClickListener {
            try {
                windowManager.removeView(dialogView)
            } catch (e: Exception) {
                Log.e("FloatingService", "Discard dialog overlay failed", e)
            }
            isDialogShowing = false

            try {
                val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(intent)
                Toast.makeText(this, "Find 'Floating Shortcuts' inside Accessibility -> Shared / Downloaded Services and enable it.", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Toast.makeText(this, "Unable to load settings window.", Toast.LENGTH_SHORT).show()
            }
        }

        dialogView.findViewById<View>(R.id.dialog_root).setOnClickListener {
            try {
                windowManager.removeView(dialogView)
            } catch (e: Exception) {
                Log.e("FloatingService", "Discard dialog overlay failed", e)
            }
            isDialogShowing = false
        }

        try {
            windowManager.addView(dialogView, dialogParams)
        } catch (e: Exception) {
            Log.e("FloatingService", "Hosting accessibility permissions overlay failed", e)
            isDialogShowing = false
        }
    }

    private fun triggerScreenshot() {
        activePopupWindow?.dismiss()
        val dotWasVisible = ::floatingView.isInitialized && floatingView.visibility == View.VISIBLE
        val sidebarWasVisible = sidebarView?.visibility == View.VISIBLE

        if (::floatingView.isInitialized) {
            floatingView.visibility = View.GONE
        }
        sidebarView?.visibility = View.GONE

        val mainHandler = Handler(Looper.getMainLooper())
        mainHandler.postDelayed({
            val sInstance = SmartDotAccessibilityService.instance
            if (sInstance != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                sInstance.dispatchScreenshotAction()
                Toast.makeText(this, "Screenshot captured!", Toast.LENGTH_SHORT).show()
            } else {
                triggerScreenshotEmulation()
            }
        }, 180L)

        mainHandler.postDelayed({
            if (::floatingView.isInitialized && (isFloatingViewAdded || floatingView.isAttachedToWindow) &&
                prefs.getBoolean("floating_dot_enabled", true) && dotWasVisible
            ) {
                floatingView.visibility = View.VISIBLE
            }
            if (prefs.getBoolean("sidebar_enabled", true) && sidebarWasVisible) {
                sidebarView?.visibility = View.VISIBLE
            }
        }, 600L)
    }

    private fun triggerScreenshotEmulation() {
        val flash = View(this).apply {
            setBackgroundColor(Color.WHITE)
        }
        val flashParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )

        try {
            windowManager.addView(flash, flashParams)
            flash.alpha = 0.82f
            flash.animate()
                .alpha(0f)
                .setDuration(350)
                .setListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        try {
                            windowManager.removeView(flash)
                        } catch (e: Exception) {
                            Log.e("FloatingService", "Error clearing visual flash helper.", e)
                        }
                    }
                })
                .start()
        } catch (e: Exception) {
            Log.e("FloatingService", "Screenshot emulation trigger error", e)
        }

        Toast.makeText(this, "Screenshot captured successfully! (emulated)", Toast.LENGTH_SHORT).show()
    }

    private fun launchCalculator() {
        val packages = listOf(
            "com.google.android.calculator",
            "com.android.calculator2",
            "com.sec.android.app.popupcalculator",
            "com.miui.calculator",
            "com.sharp.android.calculator"
        )
        val pm = packageManager
        var launched = false

        for (pkg in packages) {
            try {
                val intent = pm.getLaunchIntentForPackage(pkg)
                if (intent != null) {
                    launched = FreeformWindowLauncher.launchFullscreen(this, intent, "Calculator")
                    if (launched) break
                }
            } catch (_: Exception) {}
        }

        if (!launched) {
            try {
                val intent = Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_APP_CALCULATOR)
                }
                if (!FreeformWindowLauncher.launchFullscreen(this, intent, "Calculator")) {
                    Toast.makeText(this, "Standalone calculator package not discovered.", Toast.LENGTH_SHORT).show()
                }
            } catch (_: Exception) {
                Toast.makeText(this, "Standalone calculator package not discovered.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun launchChrome() {
        val pm = packageManager
        val intent = pm.getLaunchIntentForPackage("com.android.chrome")
            ?: Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com"))
        if (!FreeformWindowLauncher.launchFullscreen(this, intent, "Chrome")) {
            Toast.makeText(this, "Web browser activity not found.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun launchWhatsApp() {
        val pm = packageManager
        val intent = pm.getLaunchIntentForPackage("com.whatsapp")
        if (intent != null) {
            FreeformWindowLauncher.launchFullscreen(this, intent, "WhatsApp")
        } else {
            Toast.makeText(this, "WhatsApp package is not configured on your emulator/device.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun launchSettings() {
        val homeIntent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        startActivity(homeIntent)
    }

    private fun saveLocation(x: Int, y: Int) {
        prefs.edit()
            .putInt("last_x", x)
            .putInt("last_y", y)
            .apply()
    }

    private fun applyDotCustomizables() {
        if (!::imgFloatingDot.isInitialized) return

        val sizeDp = prefs.getInt("dot_size", 56)
        val alpha = prefs.getFloat("dot_transparency", 0.9f)
        val colorHex = prefs.getString("dot_color", "#00E5FF") ?: "#00E5FF"
        val dotStyle = prefs.getString("dot_style", "Normal Circle") ?: "Normal Circle"

        val density = resources.displayMetrics.density
        val sizePx = (sizeDp * density).toInt()
        val parsedColor = try {
            Color.parseColor(colorHex)
        } catch (_: Exception) {
            Color.parseColor("#00E5FF")
        }

        val targetWidthPx: Int
        val targetHeightPx: Int
        when (dotStyle) {
            "Vertical Line" -> {
                targetWidthPx = (sizeDp * 0.22f * density).toInt().coerceAtLeast((8 * density).toInt())
                targetHeightPx = (sizeDp * 1.35f * density).toInt().coerceAtLeast((36 * density).toInt())
                imgFloatingDot.clearColorFilter()
                imgFloatingDot.setImageDrawable(GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = targetWidthPx / 2f
                    setColor(parsedColor)
                    setStroke((1f * density).toInt().coerceAtLeast(1), Color.argb(140, 255, 255, 255))
                })
            }
            "Three Dots" -> {
                targetWidthPx = (sizeDp * 0.48f * density).toInt().coerceAtLeast((20 * density).toInt())
                targetHeightPx = (sizeDp * 1.25f * density).toInt().coerceAtLeast((38 * density).toInt())
                imgFloatingDot.clearColorFilter()
                val dotRadius = (targetWidthPx * 0.34f).toInt().coerceAtLeast((4 * density).toInt())
                val dotDiameter = dotRadius * 2
                val horizInset = ((targetWidthPx - dotDiameter) / 2).coerceAtLeast(0)
                val step = (targetHeightPx - dotDiameter) / 2
                val d1 = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(parsedColor) }
                val d2 = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(parsedColor) }
                val d3 = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(parsedColor) }
                val layer = android.graphics.drawable.LayerDrawable(arrayOf(d1, d2, d3)).apply {
                    setLayerInset(0, horizInset, 0, horizInset, targetHeightPx - dotDiameter)
                    setLayerInset(1, horizInset, step, horizInset, targetHeightPx - step - dotDiameter)
                    setLayerInset(2, horizInset, targetHeightPx - dotDiameter, horizInset, 0)
                }
                imgFloatingDot.setImageDrawable(layer)
            }
            "Small Pill" -> {
                targetWidthPx = (sizeDp * 0.42f * density).toInt().coerceAtLeast((16 * density).toInt())
                targetHeightPx = (sizeDp * 0.90f * density).toInt().coerceAtLeast((30 * density).toInt())
                imgFloatingDot.clearColorFilter()
                imgFloatingDot.setImageDrawable(GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = targetWidthPx / 2f
                    setColor(parsedColor)
                    setStroke((1.2f * density).toInt().coerceAtLeast(1), Color.argb(150, 255, 255, 255))
                })
            }
            "Rounded Bar" -> {
                targetWidthPx = (sizeDp * 0.30f * density).toInt().coerceAtLeast((12 * density).toInt())
                targetHeightPx = (sizeDp * 1.60f * density).toInt().coerceAtLeast((44 * density).toInt())
                imgFloatingDot.clearColorFilter()
                imgFloatingDot.setImageDrawable(GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = 10f * density
                    setColor(parsedColor)
                    setStroke((1.2f * density).toInt().coerceAtLeast(1), Color.argb(140, 255, 255, 255))
                })
            }
            else -> {
                // "Normal Circle"
                targetWidthPx = sizePx
                targetHeightPx = sizePx
                imgFloatingDot.setImageResource(R.drawable.ic_smart_dot)
                imgFloatingDot.setColorFilter(parsedColor, PorterDuff.Mode.SRC_IN)
            }
        }

        val dotParams = imgFloatingDot.layoutParams
        dotParams.width = targetWidthPx
        dotParams.height = targetHeightPx
        imgFloatingDot.layoutParams = dotParams
        imgFloatingDot.alpha = alpha
        imgFloatingDot.tag = "dot_style_$dotStyle"

        if (::floatingView.isInitialized && (isFloatingViewAdded || floatingView.isAttachedToWindow)) {
            runCatching { windowManager.updateViewLayout(floatingView, params) }
        }
    }

    private fun applyCustomizables() {
        val dotEnabled = prefs.getBoolean("floating_dot_enabled", true)
        val sidebarEnabled = prefs.getBoolean("sidebar_enabled", true)
        if (!dotEnabled && !sidebarEnabled) {
            isRunning = false
            detachFloatingDot()
            sidebarView?.let {
                try {
                    windowManager.removeView(it)
                } catch (_: Exception) {}
                sidebarView = null
                sidebarPillView = null
                sidebarParams = null
                activeSidebarEdge = null
            }
            stopSelf()
            return
        }

        if (dotEnabled) {
            ensureFloatingDotAttached()
        } else {
            detachFloatingDot()
        }
        setupSidebarWidget()
        syncShakeTorchListener()
        syncBackTapListener()
    }

    private fun buildSidebarPillBackground(
        activeColor: Int,
        visualPillWidthPx: Int,
        sidebarStyle: String,
        sidebarEdge: String
    ): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(activeColor)

            val r = when (sidebarStyle) {
                "Block" -> 0f
                "Notch" -> visualPillWidthPx * 0.8f
                "Capsule" -> visualPillWidthPx / 2f
                else -> visualPillWidthPx / 2f
            }

            if (sidebarEdge == "Right") {
                cornerRadii = floatArrayOf(r, r, 0f, 0f, 0f, 0f, r, r)
            } else {
                cornerRadii = floatArrayOf(0f, 0f, r, r, r, r, 0f, 0f)
            }
        }
    }

    private fun removeSidebarViewInternal() {
        edgeGestureDetector?.cancelAll()
        edgeGestureDetector = null
        sidebarView?.let {
            try {
                windowManager.removeView(it)
            } catch (_: Exception) {}
        }
        sidebarView = null
        sidebarPillView = null
        sidebarParams = null
        activeSidebarEdge = null
    }

    private fun setupSidebarWidget() {
        val sidebarEnabled = prefs.getBoolean("sidebar_enabled", true)
        val sidebarEdge = prefs.getString("sidebar_edge", "Right") ?: "Right"
        val accentColorHex = prefs.getString("dot_color", "#00E5FF") ?: "#00E5FF"
        val activeColor = try {
            Color.parseColor(accentColorHex)
        } catch (_: Exception) {
            Color.parseColor("#00E5FF")
        }

        val sidebarHeight = prefs.getInt("sidebar_height", 130)
        val sidebarWidthDp = prefs.getInt("sidebar_width", 14).coerceIn(3, 16)
        val sidebarOpacityPercent = prefs.getInt("sidebar_opacity", 70)
        val sidebarTouchSensitivity = if (prefs.contains("sidebar_touch_sensitivity")) {
            prefs.getInt("sidebar_touch_sensitivity", 85)
        } else {
            85
        }
        val sidebarStyle = prefs.getString("sidebar_style", "Capsule") ?: "Capsule"
        val sidebarAutoHide = prefs.getBoolean("sidebar_autohide", false)

        if (!sidebarEnabled) {
            removeSidebarViewInternal()
            return
        }

        // Touch zone stays ergonomic (25..45px) while the visible bar scales cleanly 3dp..16dp
        val touchWidthPx = EdgeGestureDetector.mapSidebarWidthDpToTouchPx(sidebarWidthDp)
        val handleHeight = (sidebarHeight * density).toInt().coerceAtLeast(1)
        val minVisualPx = (3f * density).roundToInt().coerceAtLeast(3)
        val visualPillWidthPx = (sidebarWidthDp * density).roundToInt().coerceIn(minVisualPx, touchWidthPx)
        val pillGravity = if (sidebarEdge == "Right") {
            Gravity.END or Gravity.CENTER_VERTICAL
        } else {
            Gravity.START or Gravity.CENTER_VERTICAL
        }
        val screenHeight = resources.displayMetrics.heightPixels
        val navExclusionPx = (24f * density.coerceAtLeast(1f)).toInt().coerceAtMost(screenHeight / 8)
        val maxSafeSidebarY = (screenHeight - handleHeight - navExclusionPx).coerceAtLeast(0)

        activeSidebarHeightPx = handleHeight
        activeSidebarOpacityPercent = sidebarOpacityPercent
        activeSidebarAutoHide = sidebarAutoHide

        val existingContainer = sidebarView
        val existingPill = sidebarPillView
        val existingParams = sidebarParams
        if (existingContainer != null &&
            existingPill != null &&
            existingParams != null &&
            activeSidebarEdge == sidebarEdge &&
            (existingContainer.isAttachedToWindow || isRunning)
        ) {
            existingParams.width = touchWidthPx
            existingParams.height = handleHeight
            existingParams.y = existingParams.y.coerceIn(0, maxSafeSidebarY)

            val pillLp = (existingPill.layoutParams as? FrameLayout.LayoutParams)
                ?: FrameLayout.LayoutParams(visualPillWidthPx, FrameLayout.LayoutParams.MATCH_PARENT, pillGravity)
            pillLp.width = visualPillWidthPx
            pillLp.height = FrameLayout.LayoutParams.MATCH_PARENT
            pillLp.gravity = pillGravity
            existingPill.layoutParams = pillLp
            existingPill.background = buildSidebarPillBackground(activeColor, visualPillWidthPx, sidebarStyle, sidebarEdge)
            existingContainer.alpha = if (sidebarAutoHide) 0.20f else (sidebarOpacityPercent / 100f)

            edgeGestureDetector?.apply {
                minStep1DistancePx = EdgeGestureDetector.sensitivityToStep1Px(sidebarTouchSensitivity)
                inwardIsRight = (sidebarEdge != "Right")
            }

            runCatching { windowManager.updateViewLayout(existingContainer, existingParams) }
            return
        }

        // Rebuild cleanly if edge flipped or view doesn't exist yet
        removeSidebarViewInternal()

        val pillView = View(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                visualPillWidthPx,
                FrameLayout.LayoutParams.MATCH_PARENT,
                pillGravity
            )
            background = buildSidebarPillBackground(activeColor, visualPillWidthPx, sidebarStyle, sidebarEdge)
        }

        val handle = FrameLayout(this).apply {
            alpha = if (sidebarAutoHide) 0.20f else (sidebarOpacityPercent / 100f)
            visibility = if (isShieldHidden) View.GONE else View.VISIBLE
            addView(pillView)
        }

        val sparams = WindowManager.LayoutParams(
            touchWidthPx,
            handleHeight,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = if (sidebarEdge == "Right") {
                Gravity.TOP or Gravity.END
            } else {
                Gravity.TOP or Gravity.START
            }
            val defaultSidebarY = ((screenHeight - handleHeight) / 2).coerceIn(0, maxSafeSidebarY)
            y = prefs.getInt("sidebar_last_y", defaultSidebarY).coerceIn(0, maxSafeSidebarY)
            x = 0
        }

        val detector = EdgeGestureDetector(
            density = density,
            screenHeightProvider = { resources.displayMetrics.heightPixels },
            onGestureDetected = { gesture ->
                executeEdgeGesture(gesture)
            },
            onTapWindowCheck = { gesture ->
                EdgeGestureDetector.getAssignedAction(prefs, gesture) != "none"
            }
        ).apply {
            minStep1DistancePx = EdgeGestureDetector.sensitivityToStep1Px(sidebarTouchSensitivity)
            inwardIsRight = (sidebarEdge != "Right")
        }
        edgeGestureDetector = detector

        fun hasVerticalEdgeGestureAssigned(): Boolean {
            val verticalGestures = listOf(
                EdgeGestureDetector.EdgeGesture.SWIPE_UP,
                EdgeGestureDetector.EdgeGesture.SWIPE_DOWN,
                EdgeGestureDetector.EdgeGesture.SWIPE_UP_HOLD,
                EdgeGestureDetector.EdgeGesture.SWIPE_DOWN_HOLD,
                EdgeGestureDetector.EdgeGesture.UP_THEN_LEFT,
                EdgeGestureDetector.EdgeGesture.DOWN_THEN_LEFT,
                EdgeGestureDetector.EdgeGesture.UP_THEN_DOWN,
                EdgeGestureDetector.EdgeGesture.DOWN_THEN_UP
            )
            return verticalGestures.any { EdgeGestureDetector.getAssignedAction(prefs, it) != "none" }
        }

        handle.setOnTouchListener(object : View.OnTouchListener {
            private var startTouchX = 0f
            private var startTouchY = 0f
            private var initialY = 0
            private var isRepositionDragging = false
            private var maxY = maxSafeSidebarY

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                val fullAlpha = activeSidebarOpacityPercent / 100f
                val autoHide = activeSidebarAutoHide
                val isPositionLocked = prefs.getBoolean("dot_position_locked", false)

                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        startTouchX = event.rawX
                        startTouchY = event.rawY
                        initialY = sparams.y
                        isRepositionDragging = false

                        val curScreenH = resources.displayMetrics.heightPixels
                        val curNavExclusion = (24f * density.coerceAtLeast(1f)).toInt().coerceAtMost(curScreenH / 8)
                        maxY = (curScreenH - activeSidebarHeightPx - curNavExclusion).coerceAtLeast(0)

                        if (autoHide) {
                            handle.animate().alpha(fullAlpha).setDuration(120).start()
                        }
                        return detector.onTouchEvent(event)
                    }

                    MotionEvent.ACTION_MOVE -> {
                        val diffX = event.rawX - startTouchX
                        val diffY = event.rawY - startTouchY
                        if (!isPositionLocked && !hasVerticalEdgeGestureAssigned()) {
                            if (!isRepositionDragging && abs(diffY) > 12 * density && abs(diffY) > abs(diffX) * 1.5f) {
                                isRepositionDragging = true
                                detector.cancelAll()
                            }
                            if (isRepositionDragging) {
                                sparams.y = (initialY + diffY.toInt()).coerceIn(0, maxY)
                                runCatching { windowManager.updateViewLayout(handle, sparams) }
                                return true
                            }
                        }
                        return detector.onTouchEvent(event)
                    }

                    MotionEvent.ACTION_UP -> {
                        if (autoHide) {
                            handle.animate().alpha(0.20f).setDuration(250).setStartDelay(1200).start()
                        }
                        if (isRepositionDragging) {
                            isRepositionDragging = false
                            prefs.edit().putInt("sidebar_last_y", sparams.y.coerceIn(0, maxY)).apply()
                            return true
                        }
                        return detector.onTouchEvent(event)
                    }

                    MotionEvent.ACTION_CANCEL -> {
                        isRepositionDragging = false
                        if (autoHide) {
                            handle.animate().alpha(0.20f).setDuration(250).start()
                        }
                        return detector.onTouchEvent(event)
                    }
                }
                return false
            }
        })

        sidebarView = handle
        sidebarPillView = pillView
        sidebarParams = sparams
        activeSidebarEdge = sidebarEdge

        try {
            windowManager.addView(handle, sparams)
        } catch (e: Exception) {
            Log.e("FloatingService", "Failed rendering sidebar overlay", e)
            sidebarView = null
            sidebarPillView = null
            sidebarParams = null
            activeSidebarEdge = null
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        val dotEnabled = prefs.getBoolean("floating_dot_enabled", true)
        val sidebarEnabled = prefs.getBoolean("sidebar_enabled", true)
        if (dotEnabled || sidebarEnabled) {
            scheduleRestartAlarm(this)
        }
    }

    private fun scheduleRestartAlarm(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val restartIntent = Intent(context, BootReceiver::class.java).apply {
            action = BootReceiver.ACTION_RESTART_SERVICE
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            1002,
            restartIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val triggerAt = SystemClock.elapsedRealtime() + 1000L
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            alarmManager.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pendingIntent)
        } else {
            alarmManager.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pendingIntent)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        SmartDotAccessibilityService.onShieldStateChangedListener = null
        serviceScope.cancel()

        if (isShakeListenerRegistered) {
            shakeDetector?.let { sensorManager?.unregisterListener(it) }
            isShakeListenerRegistered = false
        }
        if (isBackTapListenerRegistered) {
            backTapDetector?.let { sensorManager?.unregisterListener(it) }
            backTapDetector?.reset()
            isBackTapListenerRegistered = false
        }
        backTapWakeLock?.let { lock ->
            if (lock.isHeld) {
                runCatching { lock.release() }
            }
        }
        backTapWakeLock = null
        removeStopRecordingPill()
        if (screenRecorderManager?.isRecording == true) {
            screenRecorderManager?.stopRecording()
        }
        screenRecorderManager?.releaseProjection()
        screenRecorderManager = null
        floatingWindowManager?.closeAllWindows()
        floatingWindowManager = null
        proToolsOverlayManager?.releaseAll()
        proToolsOverlayManager = null

        try {
            clipboardManager.removePrimaryClipChangedListener(clipListener)
        } catch (e: Exception) {
            Log.e("FloatingService", "Clip listener unregister fail", e)
        }

        if (isUpdateReceiverRegistered) {
            try {
                unregisterReceiver(updateReceiver)
            } catch (e: Exception) {
                Log.e("FloatingService", "Receiver clean failed", e)
            }
            isUpdateReceiverRegistered = false
        }
        if (isScreenStateReceiverRegistered) {
            runCatching { unregisterReceiver(screenStateReceiver) }
            isScreenStateReceiverRegistered = false
        }

        edgeGestureDetector?.cancelAll()
        edgeGestureDetector = null

        screenTextCopyController?.release()
        screenTextCopyController = null
        screenTranslateController?.release()
        screenTranslateController = null

        popupSessionJob?.cancel()
        popupSessionJob = null
        if (activePopupWindow?.isShowing == true) {
            activePopupWindow?.dismiss()
        }

        if (::floatingView.isInitialized && ::windowManager.isInitialized) {
            try {
                windowManager.removeView(floatingView)
            } catch (e: Exception) {
                Log.e("FloatingService", "Removing floating dot fail", e)
            }
        }
        isFloatingViewAdded = false

        sidebarView?.let {
            try {
                windowManager.removeView(it)
            } catch (e: Exception) {
                Log.e("FloatingService", "Removing sidebar handle fail", e)
            }
        }
        sidebarView = null
        sidebarPillView = null
        sidebarParams = null
        activeSidebarEdge = null
    }
}
