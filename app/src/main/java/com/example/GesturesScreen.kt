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
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.EdgeGestureDetector.EdgeGesture
import com.example.EdgeGestureDetector.GesturePart

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
        mutableStateMapOf<EdgeGesture, String>().apply {
            EdgeGesture.entries.forEach { gesture ->
                put(gesture, EdgeGestureDetector.getAssignedAction(prefs, gesture))
            }
        }
    }

    var activeGestureForPicker by remember {
        mutableStateOf<EdgeGesture?>(null)
    }

    val activeGestureCount = gestureAssignments.values.count { it != "none" }

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
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Pinned Header Card + Reset Action
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.horizontalGradient(
                        colors = listOf(Color(0xFF1E293B), Color(0xFF0F172A))
                    ),
                    shape = RoundedCornerShape(16.dp)
                )
                .border(1.dp, Color(0xFF334155), RoundedCornerShape(16.dp))
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "22 Gestures",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFF8FAFC)
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            color = activeAccent.copy(alpha = 0.16f),
                            shape = RoundedCornerShape(20.dp),
                            border = BorderStroke(1.dp, activeAccent.copy(alpha = 0.6f)),
                            modifier = Modifier.testTag("gestures_engine_badge")
                        ) {
                            Text(
                                text = "22-Gesture Edge Engine • $activeGestureCount/22",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = activeAccent,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(4.dp))
                        IconButton(
                            onClick = {
                                EdgeGestureDetector.resetAllGestures(prefs)
                                EdgeGesture.entries.forEach { gesture ->
                                    gestureAssignments[gesture] = gesture.defaultActionId
                                }
                                broadcastSettingsUpdate()
                                Toast.makeText(context, "All 22 gestures reset to defaults", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("reset_gestures_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = "Reset All 22 Gestures",
                                tint = activeAccent
                            )
                        }
                    }
                }
                Text(
                    text = "Tap, Hold, 5 Swipes, 5 Swipe+Hold, 8 L-Shape/U-Turn (No Rightward)",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFF94A3B8)
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Lock Position Status Card (synced directly with Settings tab "Lock Position")
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .border(
                    1.dp,
                    if (lockPosition) activeAccent.copy(alpha = 0.7f) else Color(0xFF334155),
                    RoundedCornerShape(16.dp)
                ),
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
                            "Locked OFF — turn ON to anchor Dot & Sidebar while swiping."
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

        Spacer(modifier = Modifier.height(10.dp))

        // Scrollable 4-Part 22-Gesture Configuration List
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(scrollState)
        ) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, Color(0xFF334155), RoundedCornerShape(16.dp))
                    .testTag("gestures_config_card"),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = "22 Edge Gestures",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFF8FAFC)
                        )
                        Text(
                            text = "25–35px zone • >=80px Step 1 • >70° L-Turn • <=900ms",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFF94A3B8)
                        )
                    }

                Spacer(modifier = Modifier.height(8.dp))

                GesturePart.entries.forEachIndexed { partIdx, part ->
                    val partGestures = EdgeGesture.entries.filter { it.part == part }

                    if (partIdx > 0) {
                        Spacer(modifier = Modifier.height(14.dp))
                    }

                    Surface(
                        color = Color(0xFF0F172A),
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, Color(0xFF334155)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = part.title,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = activeAccent,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    partGestures.forEachIndexed { idx, gesture ->
                        val assignedId = gestureAssignments[gesture] ?: gesture.defaultActionId
                        val assignedLabel = SwipeGestureHelper.getActionDisplayName(assignedId)
                        val isConfigured = assignedId != "none"

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .defaultMinSize(minHeight = 56.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { activeGestureForPicker = gesture }
                                .padding(vertical = 10.dp, horizontal = 4.dp)
                                .testTag("gesture_row_${gesture.prefKey}"),
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
                                EdgeGestureGlyph(
                                    gesture = gesture,
                                    tint = if (isConfigured) activeAccent else Color(0xFF94A3B8)
                                )
                            }

                            Spacer(modifier = Modifier.width(12.dp))

                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = gesture.label,
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Color(0xFFF8FAFC),
                                    fontSize = 15.sp
                                )
                                Text(
                                    text = gesture.description,
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

                        if (idx < partGestures.lastIndex) {
                            HorizontalDivider(
                                color = Color(0xFF334155).copy(alpha = 0.7f),
                                thickness = 1.dp
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
        }
    }

    // Action Picker Dialog
    val pickingGesture = activeGestureForPicker
    if (pickingGesture != null) {
        val currentActionId = gestureAssignments[pickingGesture] ?: pickingGesture.defaultActionId
        AlertDialog(
            onDismissRequest = { activeGestureForPicker = null },
            title = {
                Column {
                    Text(
                        text = pickingGesture.label,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFF8FAFC)
                    )
                    Text(
                        text = pickingGesture.description,
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
                                    EdgeGestureDetector.setAssignedAction(prefs, pickingGesture, option.id)
                                    gestureAssignments[pickingGesture] = option.id
                                    broadcastSettingsUpdate()
                                    activeGestureForPicker = null
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
                TextButton(onClick = { activeGestureForPicker = null }) {
                    Text("CLOSE", color = activeAccent)
                }
            },
            containerColor = Color(0xFF1E293B),
            shape = RoundedCornerShape(20.dp)
        )
    }
}

@Composable
private fun EdgeGestureGlyph(
    gesture: EdgeGesture,
    tint: Color
) {
    Canvas(modifier = Modifier.size(20.dp)) {
        val w = size.width
        val h = size.height
        val stroke = Stroke(width = 2.2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)

        when (gesture) {
            EdgeGesture.TAP -> {
                drawCircle(color = tint, radius = w * 0.16f, center = Offset(w * 0.5f, h * 0.5f))
                drawCircle(color = tint.copy(alpha = 0.55f), radius = w * 0.34f, center = Offset(w * 0.5f, h * 0.5f), style = stroke)
            }
            EdgeGesture.DOUBLE_TAP -> {
                drawCircle(color = tint, radius = w * 0.14f, center = Offset(w * 0.32f, h * 0.5f))
                drawCircle(color = tint, radius = w * 0.14f, center = Offset(w * 0.68f, h * 0.5f))
            }
            EdgeGesture.TRIPLE_TAP -> {
                drawCircle(color = tint, radius = w * 0.11f, center = Offset(w * 0.22f, h * 0.55f))
                drawCircle(color = tint, radius = w * 0.11f, center = Offset(w * 0.5f, h * 0.35f))
                drawCircle(color = tint, radius = w * 0.11f, center = Offset(w * 0.78f, h * 0.55f))
            }
            EdgeGesture.LONG_PRESS -> {
                drawCircle(color = tint, radius = w * 0.34f, center = Offset(w * 0.5f, h * 0.5f), style = stroke)
                drawCircle(color = tint, radius = w * 0.14f, center = Offset(w * 0.5f, h * 0.5f))
            }
            EdgeGesture.SWIPE_UP -> drawArrow(Offset(w * 0.5f, h * 0.85f), Offset(w * 0.5f, h * 0.15f), tint, stroke, w)
            EdgeGesture.SWIPE_DOWN -> drawArrow(Offset(w * 0.5f, h * 0.15f), Offset(w * 0.5f, h * 0.85f), tint, stroke, w)
            EdgeGesture.SWIPE_LEFT -> drawArrow(Offset(w * 0.85f, h * 0.5f), Offset(w * 0.15f, h * 0.5f), tint, stroke, w)
            EdgeGesture.SWIPE_UP_LEFT -> drawArrow(Offset(w * 0.82f, h * 0.82f), Offset(w * 0.18f, h * 0.18f), tint, stroke, w)
            EdgeGesture.SWIPE_DOWN_LEFT -> drawArrow(Offset(w * 0.82f, h * 0.18f), Offset(w * 0.18f, h * 0.82f), tint, stroke, w)

            EdgeGesture.SWIPE_UP_HOLD -> drawSwipeHold(Offset(w * 0.5f, h * 0.85f), Offset(w * 0.5f, h * 0.25f), tint, stroke, w)
            EdgeGesture.SWIPE_DOWN_HOLD -> drawSwipeHold(Offset(w * 0.5f, h * 0.15f), Offset(w * 0.5f, h * 0.75f), tint, stroke, w)
            EdgeGesture.SWIPE_LEFT_HOLD -> drawSwipeHold(Offset(w * 0.85f, h * 0.5f), Offset(w * 0.25f, h * 0.5f), tint, stroke, w)
            EdgeGesture.SWIPE_UP_LEFT_HOLD -> drawSwipeHold(Offset(w * 0.82f, h * 0.82f), Offset(w * 0.26f, h * 0.26f), tint, stroke, w)
            EdgeGesture.SWIPE_DOWN_LEFT_HOLD -> drawSwipeHold(Offset(w * 0.82f, h * 0.18f), Offset(w * 0.26f, h * 0.74f), tint, stroke, w)

            EdgeGesture.UP_THEN_LEFT -> drawCompoundPath(Offset(w * 0.75f, h * 0.85f), Offset(w * 0.75f, h * 0.25f), Offset(w * 0.18f, h * 0.25f), tint, stroke, w)
            EdgeGesture.DOWN_THEN_LEFT -> drawCompoundPath(Offset(w * 0.75f, h * 0.15f), Offset(w * 0.75f, h * 0.75f), Offset(w * 0.18f, h * 0.75f), tint, stroke, w)
            EdgeGesture.LEFT_THEN_UP -> drawCompoundPath(Offset(w * 0.85f, h * 0.75f), Offset(w * 0.25f, h * 0.75f), Offset(w * 0.25f, h * 0.18f), tint, stroke, w)
            EdgeGesture.LEFT_THEN_DOWN -> drawCompoundPath(Offset(w * 0.85f, h * 0.25f), Offset(w * 0.25f, h * 0.25f), Offset(w * 0.25f, h * 0.82f), tint, stroke, w)
            EdgeGesture.UP_THEN_DOWN -> drawCompoundPath(Offset(w * 0.72f, h * 0.82f), Offset(w * 0.72f, h * 0.20f), Offset(w * 0.28f, h * 0.82f), tint, stroke, w)
            EdgeGesture.DOWN_THEN_UP -> drawCompoundPath(Offset(w * 0.72f, h * 0.18f), Offset(w * 0.72f, h * 0.80f), Offset(w * 0.28f, h * 0.18f), tint, stroke, w)
            EdgeGesture.UP_LEFT_THEN_DOWN -> drawCompoundPath(Offset(w * 0.85f, h * 0.78f), Offset(w * 0.28f, h * 0.22f), Offset(w * 0.28f, h * 0.82f), tint, stroke, w)
            EdgeGesture.DOWN_LEFT_THEN_UP -> drawCompoundPath(Offset(w * 0.85f, h * 0.22f), Offset(w * 0.28f, h * 0.78f), Offset(w * 0.28f, h * 0.18f), tint, stroke, w)
        }
    }
}

private fun DrawScope.drawArrow(
    start: Offset,
    end: Offset,
    tint: Color,
    stroke: Stroke,
    sizeW: Float
) {
    drawLine(color = tint, start = start, end = end, strokeWidth = stroke.width, cap = StrokeCap.Round)
    drawArrowHead(from = start, tip = end, tint = tint, stroke = stroke, sizeW = sizeW)
}

private fun DrawScope.drawSwipeHold(
    start: Offset,
    end: Offset,
    tint: Color,
    stroke: Stroke,
    sizeW: Float
) {
    drawLine(color = tint, start = start, end = end, strokeWidth = stroke.width, cap = StrokeCap.Round)
    drawCircle(color = tint, radius = sizeW * 0.14f, center = end)
}

private fun DrawScope.drawCompoundPath(
    p0: Offset,
    p1: Offset,
    p2: Offset,
    tint: Color,
    stroke: Stroke,
    sizeW: Float
) {
    val body = Path().apply {
        moveTo(p0.x, p0.y)
        lineTo(p1.x, p1.y)
        lineTo(p2.x, p2.y)
    }
    drawPath(path = body, color = tint, style = stroke)
    drawArrowHead(from = p1, tip = p2, tint = tint, stroke = stroke, sizeW = sizeW)
}

private fun DrawScope.drawArrowHead(
    from: Offset,
    tip: Offset,
    tint: Color,
    stroke: Stroke,
    sizeW: Float
) {
    val angle = kotlin.math.atan2((tip.y - from.y).toDouble(), (tip.x - from.x).toDouble())
    val headLen = sizeW * 0.27f
    val wingAngle = Math.toRadians(36.0)
    val a1 = Offset(
        (tip.x - headLen * kotlin.math.cos(angle - wingAngle)).toFloat(),
        (tip.y - headLen * kotlin.math.sin(angle - wingAngle)).toFloat()
    )
    val a2 = Offset(
        (tip.x - headLen * kotlin.math.cos(angle + wingAngle)).toFloat(),
        (tip.y - headLen * kotlin.math.sin(angle + wingAngle)).toFloat()
    )
    val headPath = Path().apply {
        moveTo(a1.x, a1.y)
        lineTo(tip.x, tip.y)
        lineTo(a2.x, a2.y)
    }
    drawPath(path = headPath, color = tint, style = stroke)
}
