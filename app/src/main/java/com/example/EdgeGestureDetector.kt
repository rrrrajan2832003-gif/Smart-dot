package com.example

import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * High-precision 22-Gesture Edge Engine enforcing 6 strict rules:
 * 1. Trigger Zone Rule: 25px..35px along the Right screen edge.
 * 2. Minimum Distance Rule: Step 1 >= 80px (smaller movement = Tap / Double Tap / Triple Tap / Long Press).
 * 3. Turn Rule (L-Shape / U-Turn): After Step 1 (>= 80px), angle change > 70° and Step 2 >= 60px.
 * 4. Time Rule: Gesture window <= 900ms max (Tap 0..200ms, Double Tap <= 250ms, Triple Tap <= 400ms,
 *    Long Press >= 500ms, Swipe+Hold >= 350ms hold at end, L-Shape 300ms..900ms).
 * 5. Direction Priority Rule: Clean 45° sector thresholds for Up, Down, Left, Up-Left, Down-Left.
 * 6. Conflict Rule: Strictly excludes Rightward directions (Right, Up-Right, Down-Right) and
 *    avoids the bottom-edge system navigation gesture zone.
 *
 * Strict No-Vibration Policy: Never triggers Vibrator or performHapticFeedback.
 */
class EdgeGestureDetector(
    private val density: Float,
    private val screenHeightProvider: () -> Int,
    private val onGestureDetected: (EdgeGesture) -> Unit,
    private val onTapWindowCheck: (EdgeGesture) -> Boolean = { true },
    private val handler: Handler = Handler(Looper.getMainLooper())
) {

    enum class GesturePart(val title: String) {
        TAP_HOLD("Part 1 — Tap & Hold (4)"),
        SWIPE("Part 2 — Straight & Diagonal Swipes (5)"),
        SWIPE_HOLD("Part 3 — Swipe + Hold at End (5)"),
        COMPOUND("Part 4 — L-Shape & U-Turn Compound (8)")
    }

    enum class BaseDirection {
        UP,
        DOWN,
        LEFT,
        UP_LEFT,
        DOWN_LEFT
    }

    enum class EdgeGesture(
        val prefKey: String,
        val label: String,
        val description: String,
        val part: GesturePart,
        val defaultActionId: String = "none"
    ) {
        // Part 1 — Tap & Hold (4)
        TAP(
            prefKey = "gesture_edge_tap",
            label = "Tap",
            description = "Quick single touch (< 200ms, < 80px)",
            part = GesturePart.TAP_HOLD,
            defaultActionId = "open_menu"
        ),
        DOUBLE_TAP(
            prefKey = "gesture_edge_double_tap",
            label = "Double Tap",
            description = "2 quick taps within 250ms",
            part = GesturePart.TAP_HOLD
        ),
        TRIPLE_TAP(
            prefKey = "gesture_edge_triple_tap",
            label = "Triple Tap",
            description = "3 quick taps within 400ms",
            part = GesturePart.TAP_HOLD
        ),
        LONG_PRESS(
            prefKey = "gesture_edge_long_press",
            label = "Long Press / Hold",
            description = "Hold steady >= 500ms without moving",
            part = GesturePart.TAP_HOLD
        ),

        // Part 2 — Straight & Diagonal Swipes (5 — keeps existing SwipeDirection prefKeys)
        SWIPE_UP(
            prefKey = "gesture_swipe_up",
            label = "Swipe Up",
            description = "Swipe straight upward >= 80px",
            part = GesturePart.SWIPE
        ),
        SWIPE_DOWN(
            prefKey = "gesture_swipe_down",
            label = "Swipe Down",
            description = "Swipe straight downward >= 80px",
            part = GesturePart.SWIPE
        ),
        SWIPE_LEFT(
            prefKey = "gesture_swipe_left",
            label = "Swipe Left",
            description = "Swipe inward left >= 80px",
            part = GesturePart.SWIPE
        ),
        SWIPE_UP_LEFT(
            prefKey = "gesture_swipe_up_left",
            label = "Swipe Up-Left",
            description = "Diagonal upward-left >= 80px",
            part = GesturePart.SWIPE
        ),
        SWIPE_DOWN_LEFT(
            prefKey = "gesture_swipe_down_left",
            label = "Swipe Down-Left",
            description = "Diagonal downward-left >= 80px",
            part = GesturePart.SWIPE
        ),

        // Part 3 — Swipe + Hold at End (5)
        SWIPE_UP_HOLD(
            prefKey = "gesture_swipe_up_hold",
            label = "Swipe Up + Hold",
            description = "Swipe up >= 80px & hold >= 350ms",
            part = GesturePart.SWIPE_HOLD
        ),
        SWIPE_DOWN_HOLD(
            prefKey = "gesture_swipe_down_hold",
            label = "Swipe Down + Hold",
            description = "Swipe down >= 80px & hold >= 350ms",
            part = GesturePart.SWIPE_HOLD
        ),
        SWIPE_LEFT_HOLD(
            prefKey = "gesture_swipe_left_hold",
            label = "Swipe Left + Hold",
            description = "Swipe left >= 80px & hold >= 350ms",
            part = GesturePart.SWIPE_HOLD
        ),
        SWIPE_UP_LEFT_HOLD(
            prefKey = "gesture_swipe_up_left_hold",
            label = "Swipe Up-Left + Hold",
            description = "Swipe up-left >= 80px & hold >= 350ms",
            part = GesturePart.SWIPE_HOLD
        ),
        SWIPE_DOWN_LEFT_HOLD(
            prefKey = "gesture_swipe_down_left_hold",
            label = "Swipe Down-Left + Hold",
            description = "Swipe down-left >= 80px & hold >= 350ms",
            part = GesturePart.SWIPE_HOLD
        ),

        // Part 4 — L-Shape & U-Turn Compound Gestures (8)
        UP_THEN_LEFT(
            prefKey = "gesture_up_then_left",
            label = "Up then Left",
            description = "Swipe up >= 80px, turn > 70° left >= 60px",
            part = GesturePart.COMPOUND
        ),
        DOWN_THEN_LEFT(
            prefKey = "gesture_down_then_left",
            label = "Down then Left",
            description = "Swipe down >= 80px, turn > 70° left >= 60px",
            part = GesturePart.COMPOUND
        ),
        LEFT_THEN_UP(
            prefKey = "gesture_left_then_up",
            label = "Left then Up",
            description = "Swipe left >= 80px, turn > 70° up >= 60px",
            part = GesturePart.COMPOUND
        ),
        LEFT_THEN_DOWN(
            prefKey = "gesture_left_then_down",
            label = "Left then Down",
            description = "Swipe left >= 80px, turn > 70° down >= 60px",
            part = GesturePart.COMPOUND
        ),
        UP_THEN_DOWN(
            prefKey = "gesture_up_then_down",
            label = "Up then Down (U-Turn)",
            description = "Swipe up >= 80px, reverse down >= 60px",
            part = GesturePart.COMPOUND
        ),
        DOWN_THEN_UP(
            prefKey = "gesture_down_then_up",
            label = "Down then Up (U-Turn)",
            description = "Swipe down >= 80px, reverse up >= 60px",
            part = GesturePart.COMPOUND
        ),
        UP_LEFT_THEN_DOWN(
            prefKey = "gesture_up_left_then_down",
            label = "Up-Left then Down",
            description = "Swipe up-left >= 80px, turn > 70° down >= 60px",
            part = GesturePart.COMPOUND
        ),
        DOWN_LEFT_THEN_UP(
            prefKey = "gesture_down_left_then_up",
            label = "Down-Left then Up",
            description = "Swipe down-left >= 80px, turn > 70° up >= 60px",
            part = GesturePart.COMPOUND
        )
    }

    // Active pointer tracking state
    private var trackingActive = false
    private var gestureConsumedByHold = false
    private var gestureStartTimeMs = 0L
    private var startRawX = 0f
    private var startRawY = 0f
    private var maxDistanceFromStart = 0f

    private var step1Direction: BaseDirection? = null
    private var step1VecX = 0f
    private var step1VecY = 0f
    private var maxStep1Proj = 0f
    private var pivotX = 0f
    private var pivotY = 0f

    private var stationaryAnchorX = 0f
    private var stationaryAnchorY = 0f
    private var stationarySinceMs = 0L

    // Multi-tap state
    private var pendingTapCount = 0
    private var firstTapTimeMs = 0L
    private var lastTapTimeMs = 0L

    var minStep1DistancePx: Float = MIN_STEP1_DISTANCE_PX
        set(value) {
            field = value.coerceAtLeast(MIN_STEP1_DISTANCE_PX)
        }

    var inwardIsRight: Boolean = false

    private val longPressRunnable = Runnable {
        if (trackingActive && !gestureConsumedByHold && step1Direction == null &&
            maxDistanceFromStart < minStep1DistancePx
        ) {
            gestureConsumedByHold = true
            resetTapSequence()
            onGestureDetected(EdgeGesture.LONG_PRESS)
        }
    }

    private val swipeHoldRunnable = Runnable {
        val dir1 = step1Direction ?: return@Runnable
        if (!trackingActive || gestureConsumedByHold) return@Runnable
        val distFromPivot = hypot(stationaryAnchorX - pivotX, stationaryAnchorY - pivotY)
        val turnFromPivot = angleBetweenVectorsDeg(
            step1VecX,
            step1VecY,
            stationaryAnchorX - pivotX,
            stationaryAnchorY - pivotY
        )
        // Only fire Swipe + Hold if pointer did NOT turn into Step 2
        if (distFromPivot < MIN_STEP2_DISTANCE_PX || turnFromPivot <= MIN_TURN_ANGLE_DEG) {
            gestureConsumedByHold = true
            resetTapSequence()
            onGestureDetected(mapSwipeHold(dir1))
        }
    }

    private val pendingTapCommitRunnable = Runnable {
        val count = pendingTapCount
        pendingTapCount = 0
        when (count) {
            1 -> onGestureDetected(EdgeGesture.TAP)
            2 -> onGestureDetected(EdgeGesture.DOUBLE_TAP)
            3 -> onGestureDetected(EdgeGesture.TRIPLE_TAP)
        }
    }

    fun onTouchEvent(event: MotionEvent): Boolean {
        val now = event.eventTime
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val screenH = screenHeightProvider()
                if (isInBottomSystemNavConflictZone(event.rawY, screenH, density)) {
                    trackingActive = false
                    return false
                }
                cancelHoldCallbacks()
                trackingActive = true
                gestureConsumedByHold = false
                gestureStartTimeMs = now
                startRawX = event.rawX
                startRawY = event.rawY
                maxDistanceFromStart = 0f
                step1Direction = null
                step1VecX = 0f
                step1VecY = 0f
                maxStep1Proj = 0f
                pivotX = event.rawX
                pivotY = event.rawY
                stationaryAnchorX = event.rawX
                stationaryAnchorY = event.rawY
                stationarySinceMs = now

                handler.postDelayed(longPressRunnable, LONG_PRESS_MIN_DURATION_MS)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (!trackingActive || gestureConsumedByHold) return trackingActive
                val dx = event.rawX - startRawX
                val dy = event.rawY - startRawY
                val dist = hypot(dx, dy)
                if (dist > maxDistanceFromStart) {
                    maxDistanceFromStart = dist
                }

                // Rule 2: Once distance >= 80px, lock Step 1 & cancel Long Press
                if (maxDistanceFromStart >= minStep1DistancePx) {
                    handler.removeCallbacks(longPressRunnable)
                }

                val currentStep1 = step1Direction
                if (currentStep1 == null) {
                    if (dist >= minStep1DistancePx) {
                        val classified = classifyDirection(dx, dy, inwardIsRight)
                        if (classified != null) {
                            step1Direction = classified
                            step1VecX = dx
                            step1VecY = dy
                            val (ux, uy) = unitVector(classified, inwardIsRight)
                            maxStep1Proj = dx * ux + dy * uy
                            pivotX = event.rawX
                            pivotY = event.rawY
                            stationaryAnchorX = event.rawX
                            stationaryAnchorY = event.rawY
                            stationarySinceMs = now
                            handler.removeCallbacks(swipeHoldRunnable)
                            handler.postDelayed(swipeHoldRunnable, SWIPE_HOLD_DURATION_MS)
                        }
                    }
                } else {
                    // Advance pivot along Step 1 until pointer turns away from Step 1
                    val (ux, uy) = unitVector(currentStep1, inwardIsRight)
                    val proj = dx * ux + dy * uy
                    val moveSincePivotX = event.rawX - pivotX
                    val moveSincePivotY = event.rawY - pivotY
                    val turnFromPivot = if (hypot(moveSincePivotX, moveSincePivotY) > 12f) {
                        angleBetweenVectorsDeg(step1VecX, step1VecY, moveSincePivotX, moveSincePivotY)
                    } else {
                        0f
                    }
                    if (proj > maxStep1Proj && turnFromPivot <= 55f) {
                        maxStep1Proj = proj
                        pivotX = event.rawX
                        pivotY = event.rawY
                    }

                    // Reset Swipe+Hold timer whenever finger keeps moving > 20px
                    val drift = hypot(event.rawX - stationaryAnchorX, event.rawY - stationaryAnchorY)
                    if (drift > STATIONARY_JITTER_PX) {
                        stationaryAnchorX = event.rawX
                        stationaryAnchorY = event.rawY
                        stationarySinceMs = now
                        handler.removeCallbacks(swipeHoldRunnable)
                        val dist2 = hypot(event.rawX - pivotX, event.rawY - pivotY)
                        if (dist2 < MIN_STEP2_DISTANCE_PX) {
                            handler.postDelayed(swipeHoldRunnable, SWIPE_HOLD_DURATION_MS)
                        }
                    }
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                if (!trackingActive) return false
                cancelHoldCallbacks()
                trackingActive = false

                if (gestureConsumedByHold) {
                    gestureConsumedByHold = false
                    return true
                }

                val dx = event.rawX - startRawX
                val dy = event.rawY - startRawY
                val dist = hypot(dx, dy)
                if (dist > maxDistanceFromStart) {
                    maxDistanceFromStart = dist
                }

                val elapsed = (now - gestureStartTimeMs).coerceAtLeast(0L)
                val step1 = step1Direction

                // Case A: Distance stayed < 80px -> Evaluate Tap / Multi-Tap / Long Press ONLY (Rule 2)
                if (maxDistanceFromStart < minStep1DistancePx) {
                    if (elapsed >= LONG_PRESS_MIN_DURATION_MS) {
                        resetTapSequence()
                        onGestureDetected(EdgeGesture.LONG_PRESS)
                        return true
                    }
                    if (elapsed <= TAP_MAX_DURATION_MS) {
                        handleValidTapRelease(now)
                        return true
                    }
                    // Movement < 80px between 200ms..500ms: fall back cleanly to tap check if stationary
                    if (elapsed < LONG_PRESS_MIN_DURATION_MS && maxDistanceFromStart < 24f * density.coerceAtLeast(1f)) {
                        handleValidTapRelease(now)
                    }
                    return true
                }

                // Rule 4: Complete gesture window max 900ms for swipes/compounds (unless Swipe+Hold)
                val step2Dx = event.rawX - pivotX
                val step2Dy = event.rawY - pivotY
                val step2Dist = hypot(step2Dx, step2Dy)
                val endDriftPx = hypot(event.rawX - stationaryAnchorX, event.rawY - stationaryAnchorY)
                val stationaryHoldMs = if (endDriftPx <= STATIONARY_JITTER_PX) {
                    (now - stationarySinceMs).coerceAtLeast(0L)
                } else {
                    0L
                }

                if (step1 != null) {
                    // Check Rule 3: L-Shape / U-Turn Compound Gesture (turn > 70°, Step 2 >= 60px, window <= 900ms)
                    if (step2Dist >= MIN_STEP2_DISTANCE_PX && elapsed <= MAX_GESTURE_WINDOW_MS) {
                        val turnAngle = angleBetweenVectorsDeg(step1VecX, step1VecY, step2Dx, step2Dy)
                        if (turnAngle > MIN_TURN_ANGLE_DEG) {
                            val step2Dir = classifyDirection(step2Dx, step2Dy, inwardIsRight)
                            val compound = if (step2Dir != null) mapCompoundGesture(step1, step2Dir) else null
                            if (compound != null) {
                                resetTapSequence()
                                onGestureDetected(compound)
                                return true
                            }
                        }
                    }

                    // Check Part 3: Swipe + Hold at End (>= 80px and stationary >= 350ms)
                    if (stationaryHoldMs >= SWIPE_HOLD_DURATION_MS) {
                        resetTapSequence()
                        onGestureDetected(mapSwipeHold(step1))
                        return true
                    }

                    // Check Part 2: Straight / Diagonal Swipe (within 900ms window)
                    if (elapsed <= MAX_GESTURE_WINDOW_MS) {
                        resetTapSequence()
                        onGestureDetected(mapSimpleSwipe(step1))
                        return true
                    }
                    return true
                }

                // Distance exceeded >= 80px on final sample without an intermediate ACTION_MOVE
                if (elapsed <= MAX_GESTURE_WINDOW_MS) {
                    val dir = classifyDirection(dx, dy, inwardIsRight)
                    if (dir != null) {
                        resetTapSequence()
                        onGestureDetected(mapSimpleSwipe(dir))
                    }
                }
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                cancelHoldCallbacks()
                trackingActive = false
                gestureConsumedByHold = false
                return true
            }
        }
        return false
    }

    private fun handleValidTapRelease(nowMs: Long) {
        handler.removeCallbacks(pendingTapCommitRunnable)

        if (pendingTapCount == 0 || (nowMs - firstTapTimeMs > TRIPLE_TAP_WINDOW_MS)) {
            pendingTapCount = 1
            firstTapTimeMs = nowMs
            lastTapTimeMs = nowMs
        } else if (pendingTapCount == 1 && (nowMs - lastTapTimeMs <= DOUBLE_TAP_WINDOW_MS)) {
            pendingTapCount = 2
            lastTapTimeMs = nowMs
        } else if (pendingTapCount == 2 && (nowMs - firstTapTimeMs <= TRIPLE_TAP_WINDOW_MS)) {
            pendingTapCount = 0
            onGestureDetected(EdgeGesture.TRIPLE_TAP)
            return
        } else {
            pendingTapCount = 1
            firstTapTimeMs = nowMs
            lastTapTimeMs = nowMs
        }

        if (pendingTapCount == 1) {
            val waitDouble = onTapWindowCheck(EdgeGesture.DOUBLE_TAP)
            val waitTriple = onTapWindowCheck(EdgeGesture.TRIPLE_TAP)
            if (!waitDouble && !waitTriple) {
                pendingTapCount = 0
                onGestureDetected(EdgeGesture.TAP)
            } else {
                handler.postDelayed(pendingTapCommitRunnable, DOUBLE_TAP_WINDOW_MS)
            }
        } else if (pendingTapCount == 2) {
            val waitTriple = onTapWindowCheck(EdgeGesture.TRIPLE_TAP)
            if (!waitTriple) {
                pendingTapCount = 0
                onGestureDetected(EdgeGesture.DOUBLE_TAP)
            } else {
                val remaining = (TRIPLE_TAP_WINDOW_MS - (nowMs - firstTapTimeMs)).coerceIn(80L, DOUBLE_TAP_WINDOW_MS)
                handler.postDelayed(pendingTapCommitRunnable, remaining)
            }
        }
    }

    fun cancelAll() {
        cancelHoldCallbacks()
        resetTapSequence()
        trackingActive = false
        gestureConsumedByHold = false
    }

    private fun cancelHoldCallbacks() {
        handler.removeCallbacks(longPressRunnable)
        handler.removeCallbacks(swipeHoldRunnable)
    }

    private fun resetTapSequence() {
        handler.removeCallbacks(pendingTapCommitRunnable)
        pendingTapCount = 0
    }

    companion object {
        // 6 Strict Rules Constants
        const val MIN_TRIGGER_WIDTH_PX = 25
        const val MAX_TRIGGER_WIDTH_PX = 45
        const val MIN_STEP1_DISTANCE_PX = 80f
        const val MIN_STEP2_DISTANCE_PX = 60f
        const val MIN_TURN_ANGLE_DEG = 70f
        const val TAP_MAX_DURATION_MS = 200L
        const val DOUBLE_TAP_WINDOW_MS = 250L
        const val TRIPLE_TAP_WINDOW_MS = 400L
        const val LONG_PRESS_MIN_DURATION_MS = 500L
        const val SWIPE_HOLD_DURATION_MS = 350L
        const val MAX_GESTURE_WINDOW_MS = 900L
        const val L_SHAPE_MIN_DURATION_MS = 300L
        private const val STATIONARY_JITTER_PX = 20f

        /**
         * Rule 1: Trigger Zone Rule — enforce 25px..45px width along screen edge.
         */
        fun clampTriggerWidthPx(requestedPx: Int): Int {
            return requestedPx.coerceIn(MIN_TRIGGER_WIDTH_PX, MAX_TRIGGER_WIDTH_PX)
        }

        /**
         * Maps user's visual bar thickness (3dp..16dp) linearly to a comfortable 25px..45px touch zone.
         */
        fun mapSidebarWidthDpToTouchPx(widthDp: Int): Int {
            val clampedDp = widthDp.coerceIn(3, 16)
            val ratio = (clampedDp - 3) / 13f
            return (MIN_TRIGGER_WIDTH_PX + ratio * (MAX_TRIGGER_WIDTH_PX - MIN_TRIGGER_WIDTH_PX))
                .toInt()
                .coerceIn(MIN_TRIGGER_WIDTH_PX, MAX_TRIGGER_WIDTH_PX)
        }

        /**
         * Converts Touch Sensitivity slider (0 = Low, 100 = Ultra-High) into Step 1 distance threshold,
         * never dropping below the strict 80px Minimum Distance Rule floor.
         */
        fun sensitivityToStep1Px(sensitivityPercent: Int): Float {
            val clamped = sensitivityPercent.coerceIn(0, 100)
            // 100 (Ultra-High) -> 80px, 50 (Medium) -> 100px, 0 (Low) -> 120px
            return MIN_STEP1_DISTANCE_PX + ((100 - clamped) / 100f) * 40f
        }

        /**
         * Rule 6: Avoid bottom-edge system gesture navigation conflict zone.
         */
        fun isInBottomSystemNavConflictZone(rawY: Float, screenHeightPx: Int, density: Float): Boolean {
            if (screenHeightPx <= 0) return false
            val zonePx = (24f * density.coerceAtLeast(1f)).coerceAtMost(screenHeightPx / 8f)
            return rawY >= (screenHeightPx - zonePx)
        }

        /**
         * Rules 5 & 6: Classify vector using exact 45-degree sectors and strictly exclude all
         * Rightward gestures (Right, Up-Right, Down-Right) relative to the screen edge.
         */
        fun classifyDirection(
            dx: Float,
            dy: Float,
            inwardIsRight: Boolean = false
        ): BaseDirection? {
            if (dx == 0f && dy == 0f) return null
            // Normalize so inward horizontal motion is ALWAYS -X (i.e. Left on Right Edge)
            val normDx = if (inwardIsRight) -dx else dx
            val angleDeg = Math.toDegrees(atan2(dy.toDouble(), normDx.toDouble())).toFloat()

            return when {
                // Up (-90° ± 22.5°)
                angleDeg in -112.5f..-67.5f -> BaseDirection.UP
                // Up-Left (-135° ± 22.5°)
                angleDeg >= -157.5f && angleDeg < -112.5f -> BaseDirection.UP_LEFT
                // Left (±180° ± 22.5°)
                angleDeg < -157.5f || angleDeg > 157.5f -> BaseDirection.LEFT
                // Down-Left (+135° ± 22.5°)
                angleDeg > 112.5f && angleDeg <= 157.5f -> BaseDirection.DOWN_LEFT
                // Down (+90° ± 22.5°)
                angleDeg in 67.5f..112.5f -> BaseDirection.DOWN
                // Rightward (-67.5° .. +67.5°) is strictly excluded per Rule 6
                else -> null
            }
        }

        fun angleBetweenVectorsDeg(x1: Float, y1: Float, x2: Float, y2: Float): Float {
            val mag1 = hypot(x1, y1)
            val mag2 = hypot(x2, y2)
            if (mag1 < 1e-3f || mag2 < 1e-3f) return 0f
            val dot = ((x1 * x2 + y1 * y2) / (mag1 * mag2)).coerceIn(-1f, 1f)
            return Math.toDegrees(acos(dot.toDouble())).toFloat()
        }

        private fun unitVector(dir: BaseDirection, inwardIsRight: Boolean): Pair<Float, Float> {
            val signX = if (inwardIsRight) 1f else -1f
            val invSqrt2 = 0.70710677f
            return when (dir) {
                BaseDirection.UP -> 0f to -1f
                BaseDirection.DOWN -> 0f to 1f
                BaseDirection.LEFT -> signX to 0f
                BaseDirection.UP_LEFT -> (signX * invSqrt2) to -invSqrt2
                BaseDirection.DOWN_LEFT -> (signX * invSqrt2) to invSqrt2
            }
        }

        fun mapSimpleSwipe(dir: BaseDirection): EdgeGesture = when (dir) {
            BaseDirection.UP -> EdgeGesture.SWIPE_UP
            BaseDirection.DOWN -> EdgeGesture.SWIPE_DOWN
            BaseDirection.LEFT -> EdgeGesture.SWIPE_LEFT
            BaseDirection.UP_LEFT -> EdgeGesture.SWIPE_UP_LEFT
            BaseDirection.DOWN_LEFT -> EdgeGesture.SWIPE_DOWN_LEFT
        }

        fun mapSwipeHold(dir: BaseDirection): EdgeGesture = when (dir) {
            BaseDirection.UP -> EdgeGesture.SWIPE_UP_HOLD
            BaseDirection.DOWN -> EdgeGesture.SWIPE_DOWN_HOLD
            BaseDirection.LEFT -> EdgeGesture.SWIPE_LEFT_HOLD
            BaseDirection.UP_LEFT -> EdgeGesture.SWIPE_UP_LEFT_HOLD
            BaseDirection.DOWN_LEFT -> EdgeGesture.SWIPE_DOWN_LEFT_HOLD
        }

        fun mapCompoundGesture(step1: BaseDirection, step2: BaseDirection): EdgeGesture? {
            return when {
                step1 == BaseDirection.UP && step2 == BaseDirection.LEFT -> EdgeGesture.UP_THEN_LEFT
                step1 == BaseDirection.DOWN && step2 == BaseDirection.LEFT -> EdgeGesture.DOWN_THEN_LEFT
                step1 == BaseDirection.LEFT && step2 == BaseDirection.UP -> EdgeGesture.LEFT_THEN_UP
                step1 == BaseDirection.LEFT && step2 == BaseDirection.DOWN -> EdgeGesture.LEFT_THEN_DOWN
                step1 == BaseDirection.UP && step2 == BaseDirection.DOWN -> EdgeGesture.UP_THEN_DOWN
                step1 == BaseDirection.DOWN && step2 == BaseDirection.UP -> EdgeGesture.DOWN_THEN_UP
                step1 == BaseDirection.UP_LEFT && step2 == BaseDirection.DOWN -> EdgeGesture.UP_LEFT_THEN_DOWN
                step1 == BaseDirection.DOWN_LEFT && step2 == BaseDirection.UP -> EdgeGesture.DOWN_LEFT_THEN_UP
                else -> null
            }
        }

        fun getAssignedAction(prefs: SharedPreferences, gesture: EdgeGesture): String {
            return prefs.getString(gesture.prefKey, gesture.defaultActionId) ?: gesture.defaultActionId
        }

        fun setAssignedAction(prefs: SharedPreferences, gesture: EdgeGesture, actionId: String) {
            prefs.edit().putString(gesture.prefKey, actionId).apply()
        }

        fun resetAllGestures(prefs: SharedPreferences) {
            prefs.edit().apply {
                EdgeGesture.entries.forEach { gesture ->
                    putString(gesture.prefKey, gesture.defaultActionId)
                }
            }.apply()
        }
    }
}
