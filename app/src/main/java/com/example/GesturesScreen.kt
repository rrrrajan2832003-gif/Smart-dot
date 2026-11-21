package com.example

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun GesturesScreen(
    modifier: Modifier = Modifier,
    dotColor: String
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("smart_dot_prefs", Context.MODE_PRIVATE) }
    val activeAccent = remember(dotColor) { Color(android.graphics.Color.parseColor(dotColor)) }
    val scrollState = rememberScrollState()

    var lockPosition by remember {
        mutableStateOf(prefs.getBoolean("dot_position_locked", false))
    }

    val gestureAssignments = remember {
        mutableStateMapOf<SwipeGestureHelper.SwipeDirection, String>().apply {
            SwipeGestureHelper.SwipeDirection.entries.forEach { dir ->
                put(dir, SwipeGestureHelper.getAssignedAction(prefs, dir))
            }
        }
    }

    var activeDirectionForPicker by remember {
        mutableStateOf<SwipeGestureHelper.SwipeDirection?>(null)
    }

    fun broadcastSettingsUpdate() {
        if (FloatingService.isRunning) {
            val intent = Intent(FloatingService.ACTION_UPDATE_SETTINGS).apply {
                setPackage(context.packageName)
            }
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
        // Header Card
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp)
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
                Text(
                    text = "Swipe Gestures",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFFF8FAFC)
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Trigger shortcuts instantly by swiping the Edge Sidebar or Floating Dot in 5 directions.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF94A3B8)
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Lock Position Status Card (synced directly with Settings tab "Lock Position")
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, if (lockPosition) activeAccent.copy(alpha = 0.7f) else Color(0xFF334155), RoundedCornerShape(16.dp)),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
            shape = RoundedCornerShape(16.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 14.dp)
                    .defaultMinSize(minHeight = 48.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                    Text(
                        text = "Lock Position for Swipes",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFF8FAFC)
                    )
                    Text(
                        text = if (lockPosition) {
                            "Locked ON — swiping Dot or Sidebar strictly fires gestures."
                        } else {
                            "Locked OFF — turn ON to prevent Dot or Sidebar from shifting while swiping."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF94A3B8)
                    )
                }
                Switch(
                    checked = lockPosition,
                    onCheckedChange = { checked ->
                        lockPosition = checked
                        prefs.edit().putBoolean("dot_position_locked", checked).apply()
                        broadcastSettingsUpdate()
                    },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.Black,
                        checkedTrackColor = activeAccent
                    ),
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("gestures_tab_lock_switch")
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // 5 Directional Swipe Cards
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, Color(0xFF334155), RoundedCornerShape(16.dp))
                .testTag("gestures_config_card"),
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
                        text = "Directional Actions",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFF8FAFC)
                    )
                    IconButton(
                        onClick = {
                            SwipeGestureHelper.SwipeDirection.entries.forEach { dir ->
                                SwipeGestureHelper.setAssignedAction(prefs, dir, "none")
                                gestureAssignments[dir] = "none"
                            }
                            broadcastSettingsUpdate()
                            Toast.makeText(context, "Swipe gestures reset to None", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("reset_gestures_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Reset Swipe Gestures",
                            tint = activeAccent
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                SwipeGestureHelper.SwipeDirection.entries.forEachIndexed { idx, direction ->
                    val assignedId = gestureAssignments[direction] ?: "none"
                    val assignedLabel = SwipeGestureHelper.getActionDisplayName(assignedId)
                    val isConfigured = assignedId != "none"

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .defaultMinSize(minHeight = 56.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { activeDirectionForPicker = direction }
                            .padding(vertical = 10.dp, horizontal = 4.dp)
                            .testTag("gesture_row_${direction.prefKey}"),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(
                                    if (isConfigured) activeAccent.copy(alpha = 0.16f) else Color(0xFF0F172A)
                                )
                                .border(
                                    1.dp,
                                    if (isConfigured) activeAccent.copy(alpha = 0.6f) else Color(0xFF334155),
                                    CircleShape
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            SwipeDirectionGlyph(
                                direction = direction,
                                tint = if (isConfigured) activeAccent else Color(0xFF94A3B8)
                            )
                        }

                        Spacer(modifier = Modifier.width(12.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = direction.label,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.SemiBold,
                                color = Color(0xFFF8FAFC)
                            )
                            Text(
                                text = direction.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFF94A3B8)
                            )
                        }

                        Spacer(modifier = Modifier.width(8.dp))

                        Surface(
                            color = if (isConfigured) activeAccent.copy(alpha = 0.18f) else Color(0xFF0F172A),
                            shape = RoundedCornerShape(8.dp),
                            border = BorderStroke(
                                1.dp,
                                if (isConfigured) activeAccent else Color(0xFF475569)
                            )
                        ) {
                            Text(
                                text = assignedLabel,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = if (isConfigured) activeAccent else Color(0xFFCBD5E1),
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                            )
                        }
                    }

                    if (idx < SwipeGestureHelper.SwipeDirection.entries.lastIndex) {
                        HorizontalDivider(
                            color = Color(0xFF334155).copy(alpha = 0.7f),
                            thickness = 1.dp
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
    }

    // Action Picker Dialog
    val pickingDirection = activeDirectionForPicker
    if (pickingDirection != null) {
        val currentActionId = gestureAssignments[pickingDirection] ?: "none"
        AlertDialog(
            onDismissRequest = { activeDirectionForPicker = null },
            title = {
                Column {
                    Text(
                        text = pickingDirection.label,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFF8FAFC)
                    )
                    Text(
                        text = "Choose an action to execute immediately on this swipe.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF94A3B8)
                    )
                }
            },
            text = {
                val listScroll = rememberScrollState()
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(340.dp)
                        .verticalScroll(listScroll)
                ) {
                    SwipeGestureHelper.GESTURE_ACTIONS.forEach { option ->
                        val selected = currentActionId == option.id
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .defaultMinSize(minHeight = 48.dp)
                                .clickable {
                                    SwipeGestureHelper.setAssignedAction(prefs, pickingDirection, option.id)
                                    gestureAssignments[pickingDirection] = option.id
                                    broadcastSettingsUpdate()
                                    activeDirectionForPicker = null
                                }
                                .padding(horizontal = 6.dp, vertical = 10.dp)
                                .testTag("gesture_option_${option.id}"),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = option.name,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                color = if (selected) activeAccent else Color(0xFFF8FAFC)
                            )
                            if (selected) {
                                Icon(
                                    imageVector = Icons.Default.CheckCircle,
                                    contentDescription = "Selected",
                                    tint = activeAccent,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                        HorizontalDivider(color = Color(0xFF334155).copy(alpha = 0.6f), thickness = 0.5.dp)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { activeDirectionForPicker = null }) {
                    Text("CLOSE", color = activeAccent)
                }
            },
            containerColor = Color(0xFF1E293B),
            shape = RoundedCornerShape(20.dp)
        )
    }
}

@Composable
private fun SwipeDirectionGlyph(
    direction: SwipeGestureHelper.SwipeDirection,
    tint: Color
) {
    Canvas(modifier = Modifier.size(20.dp)) {
        val w = size.width
        val h = size.height
        val stroke = Stroke(width = 2.4.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)

        val (start, end) = when (direction) {
            SwipeGestureHelper.SwipeDirection.SWIPE_UP -> Offset(w * 0.5f, h * 0.85f) to Offset(w * 0.5f, h * 0.15f)
            SwipeGestureHelper.SwipeDirection.SWIPE_DOWN -> Offset(w * 0.5f, h * 0.15f) to Offset(w * 0.5f, h * 0.85f)
            SwipeGestureHelper.SwipeDirection.SWIPE_LEFT -> Offset(w * 0.85f, h * 0.5f) to Offset(w * 0.15f, h * 0.5f)
            SwipeGestureHelper.SwipeDirection.SWIPE_UP_LEFT -> Offset(w * 0.82f, h * 0.82f) to Offset(w * 0.18f, h * 0.18f)
            SwipeGestureHelper.SwipeDirection.SWIPE_DOWN_LEFT -> Offset(w * 0.82f, h * 0.18f) to Offset(w * 0.18f, h * 0.82f)
        }

        drawLine(color = tint, start = start, end = end, strokeWidth = stroke.width, cap = StrokeCap.Round)

        val angle = kotlin.math.atan2((end.y - start.y).toDouble(), (end.x - start.x).toDouble())
        val headLen = w * 0.30f
        val wingAngle = Math.toRadians(36.0)
        val p1 = Offset(
            (end.x - headLen * kotlin.math.cos(angle - wingAngle)).toFloat(),
            (end.y - headLen * kotlin.math.sin(angle - wingAngle)).toFloat()
        )
        val p2 = Offset(
            (end.x - headLen * kotlin.math.cos(angle + wingAngle)).toFloat(),
            (end.y - headLen * kotlin.math.sin(angle + wingAngle)).toFloat()
        )

        val headPath = Path().apply {
            moveTo(p1.x, p1.y)
            lineTo(end.x, end.y)
            lineTo(p2.x, p2.y)
        }
        drawPath(path = headPath, color = tint, style = stroke)
    }
}
