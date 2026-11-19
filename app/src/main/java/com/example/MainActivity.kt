package com.example

// Clean refresh trigger comment to unlock preview emulator screen

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.content.ComponentName
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.ui.theme.MyApplicationTheme

class MainActivity : ComponentActivity() {

    private var hasOverlayPermissionState = mutableStateOf(false)
    private var hasNotificationPermissionState = mutableStateOf(false)
    private var hasAccessibilityPermissionState = mutableStateOf(false)
    private var isServiceRunningState = mutableStateOf(false)
    private var activeTabState = mutableStateOf("settings")

    private fun isAccessibilityServiceEnabled(): Boolean {
        val expectedComponentName = ComponentName(this, SmartDotAccessibilityService::class.java)
        val enabledServicesSetting = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val colonSplitter = TextUtils.SimpleStringSplitter(':')
        colonSplitter.setString(enabledServicesSetting)
        while (colonSplitter.hasNext()) {
            val componentNameString = colonSplitter.next()
            val enabledService = ComponentName.unflattenFromString(componentNameString)
            if (enabledService != null && enabledService == expectedComponentName) {
                return true
            }
        }
        return false
    }

    // Launcher for notification permission (Required for Android 13+)
    private val requestNotificationLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        hasNotificationPermissionState.value = isGranted
        if (isGranted) {
            Toast.makeText(this, "Notification permission granted", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Notifications are recommended for the floating widget", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Handle incoming tab selection (e.g. settings vs about_app shortcut)
        activeTabState.value = intent?.getStringExtra("open_tab") ?: "settings"

        setContent {
            MyApplicationTheme {
                val hasOverlay by hasOverlayPermissionState
                val hasNotification by hasNotificationPermissionState
                val hasAccessibility by hasAccessibilityPermissionState
                val isRunning by isServiceRunningState

                val context = LocalContext.current
                val prefs = remember { context.getSharedPreferences("smart_dot_prefs", Context.MODE_PRIVATE) }
                var currentTab by activeTabState
                
                var dotColor by remember { mutableStateOf(prefs.getString("dot_color", "#00E5FF") ?: "#00E5FF") }
                
                LaunchedEffect(currentTab) {
                    dotColor = prefs.getString("dot_color", "#00E5FF") ?: "#00E5FF"
                }

                Scaffold(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0xFF0F172A)),
                    contentWindowInsets = WindowInsets.safeDrawing,
                    bottomBar = {
                        val activeColor = remember(dotColor) { Color(android.graphics.Color.parseColor(dotColor)) }
                        NavigationBar(
                            containerColor = Color(0xFF1E293B),
                            tonalElevation = 8.dp,
                            modifier = Modifier.testTag("main_navigation_bar")
                        ) {
                            NavigationBarItem(
                                selected = currentTab == "settings",
                                onClick = { currentTab = "settings" },
                                icon = {
                                    Icon(
                                        imageVector = Icons.Default.Settings,
                                        contentDescription = "Settings"
                                    )
                                },
                                label = { Text("Settings") },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = Color.Black,
                                    selectedTextColor = activeColor,
                                    unselectedIconColor = Color(0xFF94A3B8),
                                    unselectedTextColor = Color(0xFF94A3B8),
                                    indicatorColor = activeColor
                                )
                            )
                            NavigationBarItem(
                                selected = currentTab == "about",
                                onClick = { currentTab = "about" },
                                icon = {
                                    Icon(
                                        imageVector = Icons.Default.Info,
                                        contentDescription = "About App"
                                    )
                                },
                                label = { Text("About App") },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = Color.Black,
                                    selectedTextColor = activeColor,
                                    unselectedIconColor = Color(0xFF94A3B8),
                                    unselectedTextColor = Color(0xFF94A3B8),
                                    indicatorColor = activeColor
                                )
                            )
                        }
                    }
                ) { innerPadding ->
                    if (currentTab == "settings") {
                        SmartDotScreen(
                            modifier = Modifier.padding(innerPadding),
                            hasOverlayPermission = hasOverlay,
                            hasNotificationPermission = hasNotification,
                            hasAccessibilityPermission = hasAccessibility,
                            isServiceRunning = isRunning,
                            onRequestOverlay = { requestOverlayPermission() },
                            onRequestNotification = { requestNotificationPermission() },
                            onRequestAccessibility = { triggerAccessibilityPermission() },
                            onToggleService = { toggleFloatingService() }
                        )
                    } else {
                        AboutAppScreen(
                            modifier = Modifier.padding(innerPadding),
                            dotColor = dotColor
                        )
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Update states dynamically when user returns to the application
        hasOverlayPermissionState.value = Settings.canDrawOverlays(this)
        hasNotificationPermissionState.value = checkNotificationPermission()
        hasAccessibilityPermissionState.value = isAccessibilityServiceEnabled()
        isServiceRunningState.value = FloatingService.isRunning
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        activeTabState.value = intent.getStringExtra("open_tab") ?: "settings"
    }

    private fun triggerAccessibilityPermission() {
        try {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            startActivity(intent)
            Toast.makeText(this, "Find 'Floating Shortcuts' inside the Accessibility services and toggle on.", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Could not launch settings context", Toast.LENGTH_SHORT).show()
        }
    }

    private fun checkNotificationPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestNotificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun requestOverlayPermission() {
        if (!Settings.canDrawOverlays(this)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
            Toast.makeText(this, "Please enable display over other apps for Floating Shortcuts", Toast.LENGTH_LONG).show()
        }
    }

    private fun toggleFloatingService() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Overlay permission is required first!", Toast.LENGTH_LONG).show()
            requestOverlayPermission()
            return
        }

        // Check if Android 13+ notification permission is granted
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !checkNotificationPermission()) {
            requestNotificationPermission()
        }

        val serviceIntent = Intent(this, FloatingService::class.java)
        if (FloatingService.isRunning) {
            stopService(serviceIntent)
            isServiceRunningState.value = false
            Toast.makeText(this, "Floating Shortcuts service stopped.", Toast.LENGTH_SHORT).show()
        } else {
            ContextCompat.startForegroundService(this, serviceIntent)
            isServiceRunningState.value = true
            Toast.makeText(this, "Floating Shortcuts service active.", Toast.LENGTH_SHORT).show()
        }
    }
}

@Composable
fun SmartDotScreen(
    modifier: Modifier = Modifier,
    hasOverlayPermission: Boolean,
    hasNotificationPermission: Boolean,
    hasAccessibilityPermission: Boolean,
    isServiceRunning: Boolean,
    onRequestOverlay: () -> Unit,
    onRequestNotification: () -> Unit,
    onRequestAccessibility: () -> Unit,
    onToggleService: () -> Unit
) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()

    // Retrieve customizable preferences
    val prefs = remember { context.getSharedPreferences("smart_dot_prefs", Context.MODE_PRIVATE) }
    var dotColor by remember { mutableStateOf(prefs.getString("dot_color", "#00E5FF") ?: "#00E5FF") }
    var dotSize by remember { mutableStateOf(prefs.getInt("dot_size", 56)) }
    var dotOpacity by remember { mutableStateOf(prefs.getFloat("dot_transparency", 0.9f)) }
    
    var dotStyle by remember { mutableStateOf(prefs.getString("dot_style", "Normal Circle") ?: "Normal Circle") }
    var dotPositionLocked by remember { mutableStateOf(prefs.getBoolean("dot_position_locked", false)) }
    
    var shortcutIconSize by remember { mutableStateOf(prefs.getInt("shortcut_icon_size", 28)) }
    var shortcutTextSize by remember { mutableStateOf(prefs.getInt("shortcut_text_size", 11)) }
    var shortcutSpacing by remember { mutableStateOf(prefs.getInt("shortcut_spacing", 4)) }

    var menuSizePreset by remember { mutableStateOf(prefs.getString("menu_size_preset", "Medium") ?: "Medium") }
    var menuWidth by remember { mutableStateOf(prefs.getInt("menu_width_dp", 280)) }
    var menuHeight by remember { mutableStateOf(prefs.getInt("menu_height_dp", 460)) }
    var menuGridRows by remember { mutableStateOf(prefs.getInt("menu_grid_rows", 4)) }
    var menuGridCols by remember { mutableStateOf(prefs.getInt("menu_grid_cols", 3)) }

    fun updateServiceStyle() {
        // Broadcaster action update
        if (FloatingService.isRunning) {
            val intent = Intent(FloatingService.ACTION_UPDATE_SETTINGS)
            context.sendBroadcast(intent)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // App Header with cosmic gradient brush
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp)
                .background(
                    Brush.horizontalGradient(
                        colors = listOf(Color(0xFF1E293B), Color(0xFF0F172A))
                    ),
                    shape = RoundedCornerShape(16.dp)
                )
                .border(1.dp, Color(0xFF334155), RoundedCornerShape(16.dp))
                .padding(20.dp)
        ) {
            Column {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column {
                        Text(
                            text = "Floating Shortcuts",
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFF8FAFC)
                        )
                        Text(
                            text = "Core Utility Widget",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color(0xFF94A3B8)
                        )
                    }

                    // Pulse running status indicator
                    Surface(
                        color = if (isServiceRunning) Color(0xFF059669).copy(alpha = 0.2f) else Color(0xFF475569).copy(alpha = 0.2f),
                        shape = CircleShape,
                        border = BorderStroke(
                            1.dp,
                            if (isServiceRunning) Color(0xFF34D399) else Color(0xFF94A3B8)
                        ),
                        modifier = Modifier.padding(horizontal = 4.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .background(
                                        if (isServiceRunning) Color(0xFF10B981) else Color(0xFF64748B),
                                        shape = CircleShape
                                    )
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (isServiceRunning) "RUNNING" else "INACTIVE",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = if (isServiceRunning) Color(0xFF34D399) else Color(0xFFE2E8F0)
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // System Permissions Requirements Card
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, Color(0xFF334155), RoundedCornerShape(16.dp)),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "System Requirements",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFFF8FAFC),
                    modifier = Modifier.padding(bottom = 12.dp)
                )

                // 1. Overlay Permission Status
                PermissionStatusRow(
                    title = "Display over other apps (Overlay)",
                    description = "Required to host and draw the floating dot button on top of your screen.",
                    isGranted = hasOverlayPermission,
                    onGrantClick = onRequestOverlay
                )

                HorizontalDivider(color = Color(0xFF334155), thickness = 1.dp, modifier = Modifier.padding(vertical = 12.dp))

                // 2. Notification Permission Status (Android 13+)
                PermissionStatusRow(
                    title = "Post Notifications",
                    description = "Required on modern Android to display the foreground controller notification.",
                    isGranted = hasNotificationPermission,
                    onGrantClick = onRequestNotification
                )

                HorizontalDivider(color = Color(0xFF334155), thickness = 1.dp, modifier = Modifier.padding(vertical = 12.dp))

                // 3. Accessibility Service Status (Required)
                PermissionStatusRow(
                    title = "Accessibility Service (Required)",
                    description = "Required to perform system shortcuts such as Back, Home, and Recent Apps.",
                    isGranted = hasAccessibilityPermission,
                    onGrantClick = onRequestAccessibility
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Service Controller Interaction Card
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .border(
                    1.dp,
                    if (isServiceRunning) Color(0xFF059669) else Color(0xFF334155),
                    RoundedCornerShape(16.dp)
                ),
            colors = CardDefaults.cardColors(
                containerColor = if (isServiceRunning) Color(0xFF064E3B) else Color(0xFF1E293B)
            ),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = if (isServiceRunning) "The widget is currently active on your screen" else "Start the Floating Shortcuts floating assistant",
                    style = MaterialTheme.typography.bodyLarge,
                    color = Color(0xFFE2E8F0),
                    modifier = Modifier.padding(bottom = 16.dp)
                )

                Button(
                    onClick = onToggleService,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isServiceRunning) Color(0xFFE11D48) else Color(0xFF06B6D4),
                        contentColor = Color.White
                    ),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                ) {
                    Icon(
                        imageVector = if (isServiceRunning) Icons.Default.Warning else Icons.Default.PlayArrow,
                        contentDescription = null
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (isServiceRunning) "MANAGE SMART DOT SERVICE" else "ACTIVATE FLOATING DOT",
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp
                    )
                }

                if (isServiceRunning) {
                    Text(
                        text = "Hint: You can drag the dot freely, tap on it to launch quick toggles or return back to this settings menu.",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFFA7F3D0),
                        modifier = Modifier.padding(top = 10.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))



        Spacer(modifier = Modifier.height(16.dp))

        // 1. Simplified Dot Settings Card
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, Color(0xFF334155), RoundedCornerShape(16.dp)),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Dot Settings",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFFF8FAFC)
                )
                Text(
                    text = "Customize physical accent color, shapes, and transparency.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF64748B),
                    modifier = Modifier.padding(bottom = 12.dp)
                )

                Text(
                    text = "Vibrant Color Palette:",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFF94A3B8),
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                val colorsPalette = listOf(
                    Pair("#00E5FF", "Cyan"),
                    Pair("#A855F7", "Purple"),
                    Pair("#10B981", "Emerald"),
                    Pair("#F59E0B", "Amber"),
                    Pair("#EF4444", "Crimson"),
                    Pair("#EC4899", "Pink"),
                    Pair("#84CC16", "Lime"),
                    Pair("#F97316", "Orange"),
                    Pair("#FFFFFF", "White"),
                    Pair("#3B82F6", "Blue")
                )

                Column(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    colorsPalette.chunked(5).forEach { rowColors ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            rowColors.forEach { (hex, shortName) ->
                                val isSelected = dotColor.equals(hex, ignoreCase = true)
                                val borderColor = if (isSelected) Color.White else Color.Transparent
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    modifier = Modifier.clickable {
                                        dotColor = hex
                                        prefs.edit().putString("dot_color", hex).apply()
                                        updateServiceStyle()
                                    }
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(36.dp)
                                            .clip(CircleShape)
                                            .background(Color(android.graphics.Color.parseColor(hex)))
                                            .border(2.dp, borderColor, CircleShape)
                                    )
                                    Spacer(modifier = Modifier.height(3.dp))
                                    Text(
                                        text = shortName,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (isSelected) Color(android.graphics.Color.parseColor(hex)) else Color(0xFF64748B),
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                    )
                                }
                            }
                        }
                    }
                }

                Divider(color = Color(0xFF334155), modifier = Modifier.padding(vertical = 12.dp))

                // Compact Dot Style Selector
                var styleMenuExpanded by remember { mutableStateOf(false) }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Dot Shape Style",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFF8FAFC)
                        )
                        Text(
                            text = "Compact handle visual format.",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF64748B)
                        )
                    }
                    Box {
                        Text(
                            text = dotStyle,
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(Color(0xFF334155))
                                .clickable { styleMenuExpanded = true }
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.labelMedium
                        )
                        DropdownMenu(
                            expanded = styleMenuExpanded,
                            onDismissRequest = { styleMenuExpanded = false },
                            modifier = Modifier.background(Color(0xFF1E293B)).border(1.dp, Color(0xFF334155))
                        ) {
                            listOf("Normal Circle", "Vertical Line", "Three Dots", "Small Pill", "Rounded Bar").forEach { styleOption ->
                                DropdownMenuItem(
                                    text = { Text(styleOption, color = Color.White) },
                                    onClick = {
                                        dotStyle = styleOption
                                        prefs.edit().putString("dot_style", styleOption).apply()
                                        updateServiceStyle()
                                        styleMenuExpanded = false
                                    }
                                )
                            }
                        }
                    }
                }

                Divider(color = Color(0xFF334155), modifier = Modifier.padding(vertical = 12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Lock Handle Position",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFF8FAFC)
                        )
                        Text(
                            text = "Disable dragging of the Floating Shortcuts overlay.",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF64748B)
                        )
                    }
                    Switch(
                        checked = dotPositionLocked,
                        onCheckedChange = { isChecked ->
                            dotPositionLocked = isChecked
                            prefs.edit().putBoolean("dot_position_locked", isChecked).apply()
                            updateServiceStyle()
                        }
                    )
                }

                Divider(color = Color(0xFF334155), modifier = Modifier.padding(vertical = 12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(text = "Dot Size", style = MaterialTheme.typography.bodyMedium, color = Color(0xFF94A3B8))
                    Text(text = "${dotSize} dp", style = MaterialTheme.typography.bodyMedium, color = Color(android.graphics.Color.parseColor(dotColor)), fontWeight = FontWeight.Bold)
                }
                Slider(
                    value = dotSize.toFloat(),
                    onValueChange = { newValue ->
                        dotSize = newValue.roundToInt()
                    },
                    onValueChangeFinished = {
                        prefs.edit().putInt("dot_size", dotSize).apply()
                        updateServiceStyle()
                    },
                    valueRange = 32f..96f,
                    colors = SliderDefaults.colors(
                        thumbColor = Color(android.graphics.Color.parseColor(dotColor)),
                        activeTrackColor = Color(android.graphics.Color.parseColor(dotColor)),
                        inactiveTrackColor = Color(0xFF475569)
                    )
                )

                Spacer(modifier = Modifier.height(10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(text = "Dot Idle Transparency", style = MaterialTheme.typography.bodyMedium, color = Color(0xFF94A3B8))
                    Text(text = "${(dotOpacity * 100).toInt()}%", style = MaterialTheme.typography.bodyMedium, color = Color(android.graphics.Color.parseColor(dotColor)), fontWeight = FontWeight.Bold)
                }
                Slider(
                    value = dotOpacity,
                    onValueChange = { newValue ->
                        dotOpacity = newValue
                    },
                    onValueChangeFinished = {
                        prefs.edit().putFloat("dot_transparency", dotOpacity).apply()
                        updateServiceStyle()
                    },
                    valueRange = 0.25f..1.0f,
                    colors = SliderDefaults.colors(
                        thumbColor = Color(android.graphics.Color.parseColor(dotColor)),
                        activeTrackColor = Color(android.graphics.Color.parseColor(dotColor)),
                        inactiveTrackColor = Color(0xFF475569)
                    )
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // 2. Sidebar Settings Card
        var sidebarEnabled by remember { mutableStateOf(prefs.getBoolean("sidebar_enabled", true)) }
        var sidebarEdge by remember { mutableStateOf(prefs.getString("sidebar_edge", "Right") ?: "Right") }
        var sidebarHeight by remember { mutableStateOf(prefs.getInt("sidebar_height", 130)) }
        var sidebarWidth by remember { mutableStateOf(prefs.getInt("sidebar_width", 14)) }
        var sidebarOpacity by remember { mutableStateOf(prefs.getInt("sidebar_opacity", 70)) }
        var sidebarSensitivity by remember { mutableStateOf(prefs.getInt("sidebar_sensitivity", 25)) }
        var sidebarStyle by remember { mutableStateOf(prefs.getString("sidebar_style", "Capsule") ?: "Capsule") }
        var sidebarAutoHide by remember { mutableStateOf(prefs.getBoolean("sidebar_autohide", false)) }
        var sidebarVibration by remember { mutableStateOf(prefs.getBoolean("sidebar_vibration", true)) }

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, Color(0xFF334155), RoundedCornerShape(16.dp)),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Sidebar Utility",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFFF8FAFC)
                )
                Text(
                    text = "Access cleanly aligned sidebar shortcut panels by swiping or tapping its edge.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF64748B),
                    modifier = Modifier.padding(bottom = 12.dp)
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Enable Sidebar Handle",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFF8FAFC)
                        )
                        Text(
                            text = "Render edge handle to open utility shortcuts.",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF64748B)
                        )
                    }
                    Switch(
                        checked = sidebarEnabled,
                        onCheckedChange = { isChecked ->
                            sidebarEnabled = isChecked
                            prefs.edit().putBoolean("sidebar_enabled", isChecked).apply()
                            updateServiceStyle()
                        }
                    )
                }

                if (sidebarEnabled) {
                    Divider(color = Color(0xFF334155), modifier = Modifier.padding(vertical = 12.dp))

                    Text(
                        text = "Edge Placement Position",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFF8FAFC),
                        modifier = Modifier.padding(bottom = 8.dp)
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf("Left", "Right").forEach { edge ->
                            val isSelected = sidebarEdge == edge
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .weight(1f)
                                    .height(38.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (isSelected) Color(android.graphics.Color.parseColor(dotColor)) else Color(0xFF334155))
                                    .clickable {
                                        sidebarEdge = edge
                                        prefs.edit().putString("sidebar_edge", edge).apply()
                                        updateServiceStyle()
                                    }
                            ) {
                                Text(
                                    text = "$edge Edge",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isSelected) Color.Black else Color.White
                                )
                            }
                        }
                    }

                    Divider(color = Color(0xFF334155), modifier = Modifier.padding(vertical = 12.dp))

                    Text(
                        text = "Sidebar Style",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFF8FAFC),
                        modifier = Modifier.padding(bottom = 8.dp)
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf("Capsule", "Block", "Notch").forEach { styleOpt ->
                            val isSelected = sidebarStyle == styleOpt
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .weight(1f)
                                    .height(38.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (isSelected) Color(android.graphics.Color.parseColor(dotColor)) else Color(0xFF334155))
                                    .clickable {
                                        sidebarStyle = styleOpt
                                        prefs.edit().putString("sidebar_style", styleOpt).apply()
                                        updateServiceStyle()
                                    }
                            ) {
                                Text(
                                    text = styleOpt,
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isSelected) Color.Black else Color.White
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(text = "Sidebar Height", style = MaterialTheme.typography.bodyMedium, color = Color(0xFF94A3B8))
                        Text(text = "${sidebarHeight} dp", style = MaterialTheme.typography.bodyMedium, color = Color(android.graphics.Color.parseColor(dotColor)), fontWeight = FontWeight.Bold)
                    }
                    Slider(
                        value = sidebarHeight.toFloat(),
                        onValueChange = { sidebarHeight = it.roundToInt() },
                        onValueChangeFinished = {
                            prefs.edit().putInt("sidebar_height", sidebarHeight).apply()
                            updateServiceStyle()
                        },
                        valueRange = 50f..300f,
                        colors = SliderDefaults.colors(
                            thumbColor = Color(android.graphics.Color.parseColor(dotColor)),
                            activeTrackColor = Color(android.graphics.Color.parseColor(dotColor)),
                            inactiveTrackColor = Color(0xFF475569)
                        )
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(text = "Sidebar Width", style = MaterialTheme.typography.bodyMedium, color = Color(0xFF94A3B8))
                        Text(text = "${sidebarWidth} dp", style = MaterialTheme.typography.bodyMedium, color = Color(android.graphics.Color.parseColor(dotColor)), fontWeight = FontWeight.Bold)
                    }
                    Slider(
                        value = sidebarWidth.toFloat(),
                        onValueChange = { sidebarWidth = it.roundToInt() },
                        onValueChangeFinished = {
                            prefs.edit().putInt("sidebar_width", sidebarWidth).apply()
                            updateServiceStyle()
                        },
                        valueRange = 4f..30f,
                        colors = SliderDefaults.colors(
                            thumbColor = Color(android.graphics.Color.parseColor(dotColor)),
                            activeTrackColor = Color(android.graphics.Color.parseColor(dotColor)),
                            inactiveTrackColor = Color(0xFF475569)
                        )
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(text = "Sidebar Transparency", style = MaterialTheme.typography.bodyMedium, color = Color(0xFF94A3B8))
                        Text(text = "${sidebarOpacity}%", style = MaterialTheme.typography.bodyMedium, color = Color(android.graphics.Color.parseColor(dotColor)), fontWeight = FontWeight.Bold)
                    }
                    Slider(
                        value = sidebarOpacity.toFloat(),
                        onValueChange = { sidebarOpacity = it.roundToInt() },
                        onValueChangeFinished = {
                            prefs.edit().putInt("sidebar_opacity", sidebarOpacity).apply()
                            updateServiceStyle()
                        },
                        valueRange = 10f..100f,
                        colors = SliderDefaults.colors(
                            thumbColor = Color(android.graphics.Color.parseColor(dotColor)),
                            activeTrackColor = Color(android.graphics.Color.parseColor(dotColor)),
                            inactiveTrackColor = Color(0xFF475569)
                        )
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(text = "Swipe Inward Sensitivity", style = MaterialTheme.typography.bodyMedium, color = Color(0xFF94A3B8))
                        Text(text = "${sidebarSensitivity} dp", style = MaterialTheme.typography.bodyMedium, color = Color(android.graphics.Color.parseColor(dotColor)), fontWeight = FontWeight.Bold)
                    }
                    Slider(
                        value = sidebarSensitivity.toFloat(),
                        onValueChange = { sidebarSensitivity = it.roundToInt() },
                        onValueChangeFinished = {
                            prefs.edit().putInt("sidebar_sensitivity", sidebarSensitivity).apply()
                            updateServiceStyle()
                        },
                        valueRange = 10f..60f,
                        colors = SliderDefaults.colors(
                            thumbColor = Color(android.graphics.Color.parseColor(dotColor)),
                            activeTrackColor = Color(android.graphics.Color.parseColor(dotColor)),
                            inactiveTrackColor = Color(0xFF475569)
                        )
                    )

                    Divider(color = Color(0xFF334155), modifier = Modifier.padding(vertical = 12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Auto Hide Handle",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFFF8FAFC)
                            )
                            Text(
                                text = "Dim handle to 20% opacity when idle to save screen estate.",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFF64748B)
                            )
                        }
                        Switch(
                            checked = sidebarAutoHide,
                            onCheckedChange = { isChecked ->
                                sidebarAutoHide = isChecked
                                prefs.edit().putBoolean("sidebar_autohide", isChecked).apply()
                                updateServiceStyle()
                            }
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Vibration Feedback",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFFF8FAFC)
                            )
                            Text(
                                text = "Gives a responsive haptic feel on sidebar touches.",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFF64748B)
                            )
                        }
                        Switch(
                            checked = sidebarVibration,
                            onCheckedChange = { isChecked ->
                                sidebarVibration = isChecked
                                prefs.edit().putBoolean("sidebar_vibration", isChecked).apply()
                                updateServiceStyle()
                            }
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // 3. Simplified Popup Sizing Card
        val sizePresets = listOf("Small", "Medium", "Large", "Extra Large")
        var shortcutIconPreset by remember { mutableStateOf(prefs.getString("shortcut_icon_size_preset", "Medium") ?: "Medium") }

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, Color(0xFF334155), RoundedCornerShape(16.dp))
                .testTag("size_controls_card"),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Popup HUD Dimensions",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFFF8FAFC)
                )
                Text(
                    text = "Configure square menu and shortcut icon visual scaling.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF64748B),
                    modifier = Modifier.padding(bottom = 12.dp)
                )

                Text(
                    text = "Popup HUD Size Selection:",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFF94A3B8),
                    modifier = Modifier.padding(bottom = 6.dp)
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    sizePresets.forEach { preset ->
                        val isSelected = menuSizePreset == preset
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .weight(1f)
                                .height(38.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isSelected) Color(android.graphics.Color.parseColor(dotColor)) else Color(0xFF334155))
                                .clickable {
                                    menuSizePreset = preset
                                    val sizeDp = when (preset) {
                                        "Small" -> 250
                                        "Medium" -> 300
                                        "Large" -> 340
                                        "Extra Large" -> 380
                                        else -> 300
                                    }
                                    menuWidth = sizeDp
                                    menuHeight = sizeDp
                                    menuGridRows = 3
                                    menuGridCols = 3
                                    prefs.edit().apply {
                                        putString("menu_size_preset", preset)
                                        putInt("menu_width_dp", sizeDp)
                                        putInt("menu_height_dp", sizeDp)
                                        putInt("menu_grid_rows", 3)
                                        putInt("menu_grid_cols", 3)
                                        apply()
                                    }
                                    val currentList = getShortcutsList(prefs)
                                    saveShortcutsList(prefs, currentList)
                                    updateServiceStyle()
                                }
                        ) {
                            Text(
                                text = preset,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = if (isSelected) Color.Black else Color.White
                            )
                        }
                    }
                }

                Divider(color = Color(0xFF334155), modifier = Modifier.padding(vertical = 12.dp))

                Text(
                    text = "Shortcut Icon Size Option:",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFF94A3B8),
                    modifier = Modifier.padding(bottom = 6.dp)
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf("Small", "Medium", "Large", "Extra Large").forEach { preset ->
                        val isSelected = shortcutIconPreset == preset
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .weight(1f)
                                .height(38.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isSelected) Color(android.graphics.Color.parseColor(dotColor)) else Color(0xFF334155))
                                .clickable {
                                    shortcutIconPreset = preset
                                    prefs.edit().putString("shortcut_icon_size_preset", preset).apply()
                                    updateServiceStyle()
                                }
                        ) {
                            Text(
                                text = preset,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = if (isSelected) Color.Black else Color.White
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // UPGRADED CUSTOMIZABLE SHORTCUTS CARD
        var activeSlotIndex by remember { mutableStateOf(-1) }
        var showSelectorDialog by remember { mutableStateOf(false) }

        val shortcutsList = remember {
            mutableStateListOf<String>().apply {
                addAll(getShortcutsList(prefs))
            }
        }

        // Drag-and-drop variables
        var draggedIndex by remember { mutableStateOf(-1) }
        var draggedOffsetY by remember { mutableStateOf(0f) }
        val density = LocalDensity.current
        val dragThresholdPx = remember { with(density) { 30.dp.toPx() } }

        val swapShortcuts: (Int, Int) -> Unit = { i1, i2 ->
            if (i1 in shortcutsList.indices && i2 in shortcutsList.indices) {
                val temp = shortcutsList[i1]
                shortcutsList[i1] = shortcutsList[i2]
                shortcutsList[i2] = temp
                saveShortcutsList(prefs, shortcutsList)
                updateServiceStyle()
            }
        }

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, Color(0xFF334155), RoundedCornerShape(16.dp))
                .testTag("shortcuts_config_card"),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Customize Shortcuts",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFF8FAFC)
                    )
                    
                    val resetDefaults = {
                        val defaultList = listOf(
                            "home", "back", "recent_apps", "screenshot", 
                            "flashlight", "wifi", "bluetooth", "open_app_drawer", 
                            "settings", "calculator", "chrome"
                        )
                        shortcutsList.clear()
                        shortcutsList.addAll(defaultList)
                        saveShortcutsList(prefs, defaultList)
                        menuSizePreset = "Medium"
                        menuWidth = 300
                        menuHeight = 300
                        menuGridRows = 3
                        menuGridCols = 3
                        prefs.edit().apply {
                            putString("menu_size_preset", "Medium")
                            putInt("menu_width_dp", 300)
                            putInt("menu_height_dp", 300)
                            putInt("menu_grid_rows", 3)
                            putInt("menu_grid_cols", 3)
                            apply()
                        }
                        updateServiceStyle()
                        Toast.makeText(context, "Customizations reset to defaults", Toast.LENGTH_SHORT).show()
                    }

                    // Reset button
                    IconButton(
                        onClick = resetDefaults,
                        modifier = Modifier.size(32.dp).testTag("reset_to_default_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Reset to Default Layout",
                            tint = Color(android.graphics.Color.parseColor(dotColor))
                        )
                    }
                }

                Text(
                    text = "Press and drag on the ☰ handle on any item below to reorder instantly. Click name to swap, or add/remove as needed.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF94A3B8),
                    modifier = Modifier.padding(bottom = 12.dp)
                )

                // Render shortcuts list as linear views
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    shortcutsList.forEachIndexed { index, shortcutId ->
                        val isDragging = draggedIndex == index
                        val actionName = getActionName(shortcutId)
                        val actionItem = ACTION_LIST.find { it.id == shortcutId }
                        val category = actionItem?.category ?: "App Choice"
                        val desc = actionItem?.description ?: "Launch widget action"

                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .graphicsLayer {
                                    if (isDragging) {
                                        translationY = draggedOffsetY
                                        shadowElevation = 8.dp.toPx()
                                        scaleX = 1.02f
                                        scaleY = 1.02f
                                    }
                                }
                                .border(
                                    1.dp,
                                    if (isDragging) Color(android.graphics.Color.parseColor(dotColor)) else Color(0xFF334155),
                                    RoundedCornerShape(12.dp)
                                ),
                            colors = CardDefaults.cardColors(
                                containerColor = if (isDragging) Color(0xFF0F172A) else Color(0xFF0F172A).copy(alpha = 0.5f)
                            ),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // Drag Handle 3 lines
                                Box(
                                    modifier = Modifier
                                        .size(32.dp)
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(Color(0xFF334155).copy(alpha = 0.5f))
                                        .pointerInput(index) {
                                            detectDragGestures(
                                                onDragStart = {
                                                    draggedIndex = index
                                                    draggedOffsetY = 0f
                                                },
                                                onDrag = { change, dragAmount ->
                                                    change.consume()
                                                    draggedOffsetY += dragAmount.y
                                                    if (draggedOffsetY > dragThresholdPx && index < shortcutsList.lastIndex) {
                                                        val target = index + 1
                                                        val temp = shortcutsList[index]
                                                        shortcutsList[index] = shortcutsList[target]
                                                        shortcutsList[target] = temp
                                                        draggedIndex = target
                                                        draggedOffsetY = 0f
                                                    } else if (draggedOffsetY < -dragThresholdPx && index > 0) {
                                                        val target = index - 1
                                                        val temp = shortcutsList[index]
                                                        shortcutsList[index] = shortcutsList[target]
                                                        shortcutsList[target] = temp
                                                        draggedIndex = target
                                                        draggedOffsetY = 0f
                                                    }
                                                },
                                                onDragEnd = {
                                                    draggedIndex = -1
                                                    draggedOffsetY = 0f
                                                    saveShortcutsList(prefs, shortcutsList)
                                                    updateServiceStyle()
                                                },
                                                onDragCancel = {
                                                    draggedIndex = -1
                                                    draggedOffsetY = 0f
                                                }
                                            )
                                        },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Menu,
                                        contentDescription = "Drag to reorder",
                                        tint = Color(0xFF94A3B8),
                                        modifier = Modifier.size(18.dp)
                                    )
                                }

                                Spacer(modifier = Modifier.width(10.dp))

                                // Shortcut Icon indicator
                                Box(
                                    modifier = Modifier
                                        .size(32.dp)
                                        .clip(CircleShape)
                                        .background(Color(0xFF1E293B)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    // Use standard icons for UI aesthetics
                                    Icon(
                                        imageVector = Icons.Default.PlayArrow,
                                        contentDescription = null,
                                        tint = Color(android.graphics.Color.parseColor(dotColor)),
                                        modifier = Modifier.size(16.dp)
                                    )
                                }

                                Spacer(modifier = Modifier.width(10.dp))

                                // Information click triggers dialogue swap
                                Column(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clickable {
                                            activeSlotIndex = index
                                            showSelectorDialog = true
                                        }
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = actionName,
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = Color.White
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = "(${category})",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = Color(android.graphics.Color.parseColor(dotColor))
                                        )
                                    }
                                    Text(
                                        text = desc,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Color(0xFF94A3B8),
                                        maxLines = 1,
                                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                    )
                                }

                                Spacer(modifier = Modifier.width(6.dp))

                                // Custom micro up/down triggers
                                IconButton(
                                    onClick = { swapShortcuts(index, index - 1) },
                                    enabled = index > 0,
                                    modifier = Modifier.size(24.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.KeyboardArrowUp,
                                        contentDescription = "Move Up",
                                        tint = if (index > 0) Color(0xFF94A3B8) else Color(0xFF475569),
                                        modifier = Modifier.size(20.dp)
                                    )
                                }

                                IconButton(
                                    onClick = { swapShortcuts(index, index + 1) },
                                    enabled = index < shortcutsList.lastIndex,
                                    modifier = Modifier.size(24.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.KeyboardArrowDown,
                                        contentDescription = "Move Down",
                                        tint = if (index < shortcutsList.lastIndex) Color(0xFF94A3B8) else Color(0xFF475569),
                                        modifier = Modifier.size(20.dp)
                                    )
                                }

                                Spacer(modifier = Modifier.width(4.dp))

                                // Remove trigger key
                                IconButton(
                                    onClick = {
                                        if (shortcutsList.size > 1) {
                                            shortcutsList.removeAt(index)
                                            saveShortcutsList(prefs, shortcutsList)
                                            updateServiceStyle()
                                        } else {
                                            Toast.makeText(context, "Keep at least 1 shortcut", Toast.LENGTH_SHORT).show()
                                        }
                                    },
                                    modifier = Modifier.size(24.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Delete,
                                        contentDescription = "Delete shortcut",
                                        tint = Color(0xFFEF4444),
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                // Add button for appending new shortcuts
                Button(
                    onClick = {
                        activeSlotIndex = shortcutsList.size
                        showSelectorDialog = true
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7)),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp)
                        .testTag("add_shortcut_button")
                ) {
                    Icon(imageVector = Icons.Default.Add, contentDescription = "Add")
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "ADD NEW SHORTCUT",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }
            }
        }

        if (showSelectorDialog && activeSlotIndex >= 0) {
            AlertDialog(
                onDismissRequest = { showSelectorDialog = false },
                title = {
                    Column {
                        val headerLabelText = if (activeSlotIndex == shortcutsList.size) "Add New Shortcut" else "Remap Slot ${activeSlotIndex + 1}"
                        Text(
                            text = headerLabelText,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFF8FAFC)
                        )
                        Text(
                            text = "Select any tool or action from the 30+ assistant actions suite below.",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF94A3B8)
                        )
                    }
                },
                text = {
                    Box(
                        modifier = Modifier
                            .height(350.dp)
                            .fillMaxWidth()
                    ) {
                        val categories = ACTION_LIST.groupBy { it.category }
                        val scrollStateDiag = rememberScrollState()

                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(scrollStateDiag)
                        ) {
                            categories.forEach { (cat, actions) ->
                                Text(
                                    text = cat.uppercase(),
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(android.graphics.Color.parseColor(dotColor)),
                                    modifier = Modifier.padding(top = 14.dp, bottom = 6.dp)
                                )

                                actions.forEach { action ->
                                    val isCurrentValueSelected = activeSlotIndex < shortcutsList.size && shortcutsList[activeSlotIndex] == action.id
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                if (activeSlotIndex == shortcutsList.size) {
                                                    // Add / append
                                                    shortcutsList.add(action.id)
                                                    Toast.makeText(context, "Appended ${action.name} shortcut", Toast.LENGTH_SHORT).show()
                                                } else {
                                                    // Replace
                                                    shortcutsList[activeSlotIndex] = action.id
                                                    Toast.makeText(context, "Slot ${activeSlotIndex + 1} set to ${action.name}", Toast.LENGTH_SHORT).show()
                                                }
                                                // Save setting
                                                saveShortcutsList(prefs, shortcutsList)
                                                // Close dialog & update widget
                                                showSelectorDialog = false
                                                updateServiceStyle()
                                            }
                                            .padding(vertical = 10.dp, horizontal = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = action.name,
                                                style = MaterialTheme.typography.bodyLarge,
                                                fontWeight = FontWeight.SemiBold,
                                                color = Color.White
                                            )
                                            Text(
                                                text = action.description,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = Color(0xFF94A3B8)
                                            )
                                        }
                                        Icon(
                                            imageVector = Icons.Default.CheckCircle,
                                            contentDescription = null,
                                            tint = if (isCurrentValueSelected) Color(android.graphics.Color.parseColor(dotColor)) else Color.Transparent,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                    HorizontalDivider(color = Color(0xFF334155), thickness = 0.5.dp)
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showSelectorDialog = false }) {
                        Text("CANCEL", color = Color(android.graphics.Color.parseColor(dotColor)))
                    }
                },
                containerColor = Color(0xFF1E293B),
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.padding(horizontal = 8.dp)
            )
        }
    }
}

// -------------------------------------------------------------
// Package level model for mesh sizes mapping
// -------------------------------------------------------------
private data class MeshSize(val w: Int, val h: Int, val r: Int, val c: Int)

// -------------------------------------------------------------
// Package Level Utilities for Customizable Floating Assistant
// -------------------------------------------------------------
private fun getShortcutsList(prefs: android.content.SharedPreferences): List<String> {
    val defaultString = "home,back,recent_apps,screenshot,flashlight,wifi,bluetooth,open_app_drawer,settings,calculator,chrome"
    val saved = prefs.getString("shortcuts_list", defaultString) ?: defaultString
    if (saved.isEmpty()) return emptyList()
    return saved.split(",")
}

private fun saveShortcutsList(prefs: android.content.SharedPreferences, list: List<String>) {
    val editor = prefs.edit()
    val listStr = list.joinToString(",")
    editor.putString("shortcuts_list", listStr)
    // Backward compatibility for legacy indexes
    for (i in list.indices) {
        editor.putString("shortcut_$i", list[i])
    }
    editor.putInt("shortcuts_count", list.size)

    // Auto-partition into pages based on current rows and columns
    val cols = prefs.getInt("menu_grid_cols", 3)
    val rows = prefs.getInt("menu_grid_rows", 4)
    val pageSize = (cols * rows).coerceAtLeast(1)

    if (list.isEmpty()) {
        editor.putString("shortcuts_list_pages", "")
    } else {
        val chunked = list.chunked(pageSize)
        val pagesStr = chunked.map { it.joinToString(",") }.joinToString("|")
        editor.putString("shortcuts_list_pages", pagesStr)
    }
    editor.apply()
}

private fun getShortcutAction(index: Int, prefs: android.content.SharedPreferences): String {
    val defaults = listOf(
        "home", "back", "recent_apps", "screenshot", 
        "flashlight", "wifi", "bluetooth", "open_app_drawer", 
        "settings", "calculator", "chrome"
    )
    return prefs.getString("shortcut_$index", defaults.getOrElse(index) { "settings" }) ?: "settings"
}

private fun getActionName(actionId: String): String {
    return when (actionId) {
        "home" -> "Home"
        "back" -> "Back"
        "recent_apps" -> "Recents"
        "screenshot" -> "Screenshot"
        "screen_record" -> "Screen Rec"
        "notifications" -> "Notif"
        "quick_settings" -> "Quick Set"
        "settings" -> "Dash"
        "whatsapp" -> "WhatsApp"
        "calculator" -> "Calc"
        "chrome" -> "Chrome"
        "flashlight" -> "Torch"
        "camera" -> "Camera"
        "gallery" -> "Gallery"
        "youtube" -> "YouTube"
        "gmail" -> "Gmail"
        "files" -> "Files"
        "wifi" -> "WiFi"
        "mobile_data" -> "Mobile Data"
        "bluetooth" -> "Bluetooth"
        "brightness" -> "Brightness"
        "volume" -> "Volume"
        "mute" -> "Mute"
        "dnd_mode" -> "DND"
        "auto_rotate" -> "Rotation"
        "dark_mode" -> "Theme"
        "airplane_mode" -> "Airplane"
        "hotspot" -> "Hotspot"
        "split_screen" -> "Split"
        "open_app_drawer" -> "Drawer"
        "voice_assistant" -> "Assistant"
        "about_app" -> "About"
        "clock" -> "Clock"
        "contacts" -> "Contacts"
        "messages" -> "Messages"
        "custom_app" -> "Custom"
        else -> "Shortcut"
    }
}

data class AvailableAction(
    val id: String,
    val name: String,
    val category: String,
    val description: String
)

val ACTION_LIST = listOf(
    // System Actions
    AvailableAction("home", "Home", "System Shortcuts", "Go to device Home screen"),
    AvailableAction("back", "Back", "System Shortcuts", "Go back to previous screen"),
    AvailableAction("recent_apps", "Recent Apps", "System Shortcuts", "Open system Recents screen"),
    AvailableAction("screenshot", "Screenshot", "System Shortcuts", "Capture system screen shot"),
    AvailableAction("screen_record", "Screen Record", "System Shortcuts", "Trigger device screen recorder"),
    AvailableAction("notifications", "Notifications", "System Shortcuts", "Expand status bar notification panel"),
    AvailableAction("quick_settings", "Quick Settings", "System Shortcuts", "Expand status bar quick settings panel"),
    AvailableAction("split_screen", "Split Screen", "System Shortcuts", "Initiate system Split Screen Mode"),
    AvailableAction("open_app_drawer", "Open App Drawer", "System Shortcuts", "Open installed launcher app drawer"),
    AvailableAction("voice_assistant", "Voice Assistant", "System Shortcuts", "Activate Google Voice Assistant"),
    AvailableAction("about_app", "About App", "System Shortcuts", "Open local Floating Shortcuts About and Developer suite"),

    // Device Controls
    AvailableAction("flashlight", "Flashlight", "Quick Preferences", "Toggle camera LED torch flashlight"),
    AvailableAction("wifi", "WiFi Settings", "Quick Preferences", "Open WiFi configuration layout"),
    AvailableAction("mobile_data", "Mobile Data settings", "Quick Preferences", "Open cellular data network settings"),
    AvailableAction("bluetooth", "Bluetooth settings", "Quick Preferences", "Open bluetooth tethering configuration"),
    AvailableAction("brightness", "Brightness Booster", "Quick Preferences", "Cycle or boost display brightness"),
    AvailableAction("volume", "Volume Controller", "Quick Preferences", "Display system music and ringer sliders"),
    AvailableAction("mute", "Quiet Ringer (Mute)", "Quick Preferences", "Toggle ringtone silencing"),
    AvailableAction("dnd_mode", "Do Not Disturb (DND)", "Quick Preferences", "Toggle system interruption blocker"),
    AvailableAction("auto_rotate", "Auto Rotation Toggle", "Quick Preferences", "Lock or toggle orientation rotation"),
    AvailableAction("dark_mode", "Dark Theme Settings", "Quick Preferences", "Open Display color styles"),
    AvailableAction("airplane_mode", "Airplane Settings", "Quick Preferences", "Open wireless network preferences"),
    AvailableAction("hotspot", "Tether Settings (Hotspot)", "Quick Preferences", "Toggle internet sharing hotspot"),

    // Applications & Utilities
    AvailableAction("camera", "System Camera", "Utility Apps", "Launch default camera viewfinder"),
    AvailableAction("gallery", "System Gallery", "Utility Apps", "Access saved photos and pictures"),
    AvailableAction("calculator", "System Calculator", "Utility Apps", "Open default calculator application"),
    AvailableAction("chrome", "Chrome Browser", "Utility Apps", "Open google chrome search engine"),
    AvailableAction("whatsapp", "WhatsApp Messenger", "Utility Apps", "Open Whatsapp quick chats"),
    AvailableAction("youtube", "YouTube App", "Utility Apps", "Open Youtube video sharing layout"),
    AvailableAction("gmail", "Gmail Inbox", "Utility Apps", "Launch gmail inbox editor"),
    AvailableAction("files", "Files Explorer", "Utility Apps", "Look up local downloads or documents"),
    AvailableAction("settings", "System Settings", "Utility Apps", "Open Android device preferences"),
    AvailableAction("clock", "Alarms and Clock", "Utility Apps", "Open deskclock alarms scheduler"),
    AvailableAction("contacts", "Contacts Manager", "Utility Apps", "Search for telephone directory people"),
    AvailableAction("messages", "Messages SMS", "Utility Apps", "Trigger default messaging SMS interface"),
    AvailableAction("custom_app", "Custom App Launcher", "Utility Apps", "Launch a custom Android package suffix")
)

@Composable
fun PermissionStatusRow(
    title: String,
    description: String,
    isGranted: Boolean,
    onGrantClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.Top
    ) {
        Icon(
            imageVector = if (isGranted) Icons.Default.CheckCircle else Icons.Default.Info,
            contentDescription = null,
            tint = if (isGranted) Color(0xFF10B981) else Color(0xFFF59E0B),
            modifier = Modifier.size(24.dp)
        )

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                color = Color(0xFFF8FAFC)
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF94A3B8),
                modifier = Modifier.padding(bottom = 6.dp)
            )

            if (!isGranted) {
                Button(
                    onClick = onGrantClick,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2563EB)),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp),
                    shape = RoundedCornerShape(6.dp),
                    modifier = Modifier.height(32.dp)
                ) {
                    Text(
                        text = "Grant Permission",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }
            } else {
                Text(
                    text = "Permission Configured",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF10B981)
                )
            }
        }
    }
}

@Composable
fun AboutAppScreen(
    modifier: Modifier = Modifier,
    dotColor: String
) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()
    
    val activeColor = remember(dotColor) { Color(android.graphics.Color.parseColor(dotColor)) }
    
    val versionName = remember {
        try {
            val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            pInfo.versionName ?: "1.0"
        } catch (e: Exception) {
            "1.0"
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // App Identity Card with Gradient Overlay
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp)
                .background(
                    Brush.radialGradient(
                        colors = listOf(Color(0xFF1E293B), Color(0xFF0F172A)),
                        radius = 1200f
                    ),
                    shape = RoundedCornerShape(24.dp)
                )
                .border(2.dp, activeColor.copy(alpha = 0.5f), RoundedCornerShape(24.dp))
                .padding(24.dp)
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                // Interactive Canvas drawing for Logo
                Box(
                    modifier = Modifier
                        .size(80.dp)
                        .background(activeColor.copy(alpha = 0.15f), CircleShape)
                        .border(2.dp, activeColor, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    // Concentric floating assistance circles
                    Canvas(modifier = Modifier.size(48.dp)) {
                        drawCircle(
                            color = activeColor,
                            radius = 12.dp.toPx()
                        )
                        drawCircle(
                            color = activeColor.copy(alpha = 0.40f),
                            radius = 20.dp.toPx(),
                            style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.dp.toPx())
                        )
                    }
                }
                
                Spacer(modifier = Modifier.height(16.dp))
                
                Text(
                    text = "Floating Shortcuts",
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                
                Spacer(modifier = Modifier.height(4.dp))
                
                Surface(
                    color = Color(0xFF0F172A),
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, Color(0xFF334155))
                ) {
                    Text(
                        text = "VERSION $versionName",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = activeColor,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }
                
                Spacer(modifier = Modifier.height(8.dp))
                
                Text(
                    text = "Developed by Suraj Kumar",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFF94A3B8)
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Professional Description Card
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, Color(0xFF334155), RoundedCornerShape(16.dp)),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text(
                    text = "About Floating Assistant",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                Text(
                    text = "Floating Shortcuts is a customizable floating assistant that provides quick access to apps, shortcuts, accessibility actions, quick settings, screenshots, and productivity tools through a floating dot and sidebar.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFFCBD5E1),
                    lineHeight = 22.sp
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Changelog Section Card
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, Color(0xFF334155), RoundedCornerShape(16.dp)),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text(
                    text = "Changelog",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
                Text(
                    text = "Version 1.0 (Initial Release Support)",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                    color = activeColor,
                    modifier = Modifier.padding(bottom = 12.dp)
                )

                val changelogItems = listOf(
                    "Floating Dot" to "Multi-touch, customizable transparency and custom gestures.",
                    "Sidebar" to "Swipe inward trigger widget that can rest on either edge.",
                    "Custom Shortcuts" to "Configure multi-action grid triggers tailored for you.",
                    "Multi Page Menu" to "Paginate actions comfortably into custom grid patterns.",
                    "Accessibility Actions" to "Back, Home, and Recents integration.",
                    "Popup Customization" to "Alter active grid colors and alignments dynamically.",
                    "Icon Size Controls" to "Modify tool button sizes inside floating sub-menus.",
                    "Menu Size Controls" to "Re-scale width, height, and coordinates parameters."
                )

                changelogItems.forEach { (title, subtitle) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Box(
                            modifier = Modifier
                                .padding(top = 4.dp)
                                .size(6.dp)
                                .background(activeColor, CircleShape)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = title,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                            Text(
                                text = subtitle,
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFF94A3B8)
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Developer Actions Card
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, Color(0xFF334155), RoundedCornerShape(16.dp)),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "Developer Connections",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    modifier = Modifier
                        .align(Alignment.Start)
                        .padding(bottom = 16.dp)
                )

                // 1. Send Feedback Button (Primary Accent Color)
                Button(
                    onClick = {
                        val feedbackEmail = "surajk59011@gmail.com"
                        val intent = Intent(Intent.ACTION_SENDTO).apply {
                            data = Uri.parse("mailto:")
                            putExtra(Intent.EXTRA_EMAIL, arrayOf(feedbackEmail))
                            putExtra(Intent.EXTRA_SUBJECT, "Floating Shortcuts Feedback")
                            putExtra(
                                Intent.EXTRA_TEXT,
                                "Device: ${Build.MANUFACTURER} ${Build.MODEL}\nAndroid Version: ${Build.VERSION.RELEASE}\nFeedback:\n"
                            )
                        }
                        try {
                            context.startActivity(intent)
                        } catch (e: Exception) {
                            try {
                                val chooserIntent = Intent(Intent.ACTION_SEND).apply {
                                    type = "message/rfc822"
                                    putExtra(Intent.EXTRA_EMAIL, arrayOf(feedbackEmail))
                                    putExtra(Intent.EXTRA_SUBJECT, "Floating Shortcuts Feedback")
                                    putExtra(
                                        Intent.EXTRA_TEXT,
                                        "Device: ${Build.MANUFACTURER} ${Build.MODEL}\nAndroid Version: ${Build.VERSION.RELEASE}\nFeedback:\n"
                                    )
                                }
                                context.startActivity(Intent.createChooser(chooserIntent, "Send Feedback via"))
                            } catch (ex: Exception) {
                                Toast.makeText(context, "No email client found", Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = activeColor, contentColor = Color.Black),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag("send_feedback_button")
                ) {
                    Text(text = "SEND FEEDBACK", fontWeight = FontWeight.Bold)
                }

                Spacer(modifier = Modifier.height(12.dp))

                // 2. Outlined Button Row (Privacy policy & Contact Developer)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com/privacy"))
                            try {
                                context.startActivity(intent)
                            } catch (e: Exception) {
                                Toast.makeText(context, "No web browser found", Toast.LENGTH_SHORT).show()
                            }
                        },
                        border = BorderStroke(1.dp, Color(0xFF475569)),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .testTag("privacy_policy_button")
                    ) {
                        Text(text = "Privacy Policy", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }

                    OutlinedButton(
                        onClick = {
                            val devEmail = "surajk59011@gmail.com"
                            val intent = Intent(Intent.ACTION_SENDTO).apply {
                                data = Uri.parse("mailto:")
                                putExtra(Intent.EXTRA_EMAIL, arrayOf(devEmail))
                                putExtra(Intent.EXTRA_SUBJECT, "Contact - Floating Shortcuts Developer")
                            }
                            try {
                                context.startActivity(intent)
                            } catch (e: Exception) {
                                try {
                                    val chooserIntent = Intent(Intent.ACTION_SEND).apply {
                                        type = "message/rfc822"
                                        putExtra(Intent.EXTRA_EMAIL, arrayOf(devEmail))
                                        putExtra(Intent.EXTRA_SUBJECT, "Contact - Floating Shortcuts Developer")
                                    }
                                    context.startActivity(Intent.createChooser(chooserIntent, "Contact Developer"))
                                } catch (ex: Exception) {
                                    Toast.makeText(context, "No email client found", Toast.LENGTH_SHORT).show()
                                }
                            }
                        },
                        border = BorderStroke(1.dp, Color(0xFF475569)),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .testTag("contact_developer_button")
                    ) {
                        Text(text = "Contact Dev", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // 3. Outlined Button Row (Share App & Rate App)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            val shareMessage = "Check out Floating Shortcuts - Floating Assistant"
                            val intent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, shareMessage)
                            }
                            try {
                                context.startActivity(Intent.createChooser(intent, "Share Floating Shortcuts"))
                            } catch (e: Exception) {
                                Toast.makeText(context, "No sharing app found", Toast.LENGTH_SHORT).show()
                            }
                        },
                        border = BorderStroke(1.dp, Color(0xFF475569)),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .testTag("share_app_button")
                    ) {
                        Icon(imageVector = Icons.Default.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(text = "Share App", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }

                    OutlinedButton(
                        onClick = {
                            val pName = context.packageName
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$pName"))
                            var success = false
                            try {
                                context.startActivity(intent)
                                success = true
                            } catch (e: Exception) {
                                try {
                                    val webIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$pName"))
                                    context.startActivity(webIntent)
                                    success = true
                                } catch (ex: Exception) {
                                    // Handle failure cleanly
                                }
                            }
                            if (!success) {
                                Toast.makeText(context, "Coming Soon! Under Review on the Google Play Store.", Toast.LENGTH_LONG).show()
                            }
                        },
                        border = BorderStroke(1.dp, Color(0xFF475569)),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .testTag("rate_app_button")
                    ) {
                        Text(text = "Rate App", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
    }
}
