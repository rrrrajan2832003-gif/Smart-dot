package com.example

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import kotlin.math.sqrt

/**
 * Lightweight accelerometer shake detector (zero vibration / haptic feedback).
 * Requires [requiredShakes] firm peaks above [thresholdAccel] within [windowMs],
 * separated by at least [minPeakGapMs] and debounced by [cooldownMs].
 * Supports [suppressFor] so phone unlock / screen-on / fingerprint vibration does not false-trigger.
 */
class ShakeDetector(
    @Volatile private var thresholdAccel: Float = 16f,
    private val windowMs: Long = 800L,
    private val minPeakGapMs: Long = 100L,
    private val cooldownMs: Long = 900L,
    private val requiredShakes: Int = 3,
    private val onDoubleShake: () -> Unit
) : SensorEventListener {

    private val peakTimestamps = ArrayDeque<Long>()
    private var lastTriggerTimeMs = 0L

    @Volatile
    private var suppressUntilMs = 0L

    fun setThreshold(accelThreshold: Float) {
        thresholdAccel = accelThreshold.coerceIn(10f, 30f)
    }

    fun getThreshold(): Float = thresholdAccel

    fun suppressFor(durationMs: Long, nowMs: Long = System.currentTimeMillis()) {
        val target = nowMs + durationMs.coerceAtLeast(0L)
        if (target > suppressUntilMs) {
            suppressUntilMs = target
        }
        peakTimestamps.clear()
    }

    fun onAccelerationSample(x: Float, y: Float, z: Float, timestampMs: Long): Boolean {
        if (timestampMs < suppressUntilMs) {
            peakTimestamps.clear()
            return false
        }

        val magnitude = sqrt((x * x + y * y + z * z).toDouble()).toFloat()
        if (magnitude <= thresholdAccel) return false

        if (lastTriggerTimeMs != 0L && (timestampMs - lastTriggerTimeMs) < cooldownMs) {
            return false
        }

        val lastPeak = peakTimestamps.lastOrNull()
        if (lastPeak != null && (timestampMs - lastPeak) < minPeakGapMs) {
            return false
        }

        while (peakTimestamps.isNotEmpty() && (timestampMs - peakTimestamps.first()) > windowMs) {
            peakTimestamps.removeFirst()
        }
        peakTimestamps.addLast(timestampMs)

        val targetCount = requiredShakes.coerceAtLeast(2)
        if (peakTimestamps.size >= targetCount) {
            peakTimestamps.clear()
            lastTriggerTimeMs = timestampMs
            onDoubleShake()
            return true
        }
        return false
    }

    fun reset() {
        peakTimestamps.clear()
        lastTriggerTimeMs = 0L
        suppressUntilMs = 0L
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null || event.sensor.type != Sensor.TYPE_ACCELEROMETER) return
        val x = event.values.getOrNull(0) ?: return
        val y = event.values.getOrNull(1) ?: return
        val z = event.values.getOrNull(2) ?: return
        onAccelerationSample(x, y, z, System.currentTimeMillis())
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // No-op
    }
}
