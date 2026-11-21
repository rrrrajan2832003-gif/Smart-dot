package com.example

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import kotlin.math.sqrt

/**
 * Lightweight accelerometer double-shake detector (zero vibration / haptic feedback).
 * Triggers [onDoubleShake] when total acceleration exceeds [thresholdAccel] twice within [windowMs],
 * separated by at least [minPeakGapMs] and debounced by [cooldownMs].
 */
class ShakeDetector(
    private val thresholdAccel: Float = 16f,
    private val windowMs: Long = 500L,
    private val minPeakGapMs: Long = 100L,
    private val cooldownMs: Long = 900L,
    private val onDoubleShake: () -> Unit
) : SensorEventListener {

    private var firstShakeTimeMs = 0L
    private var lastTriggerTimeMs = 0L

    fun onAccelerationSample(x: Float, y: Float, z: Float, timestampMs: Long): Boolean {
        val magnitude = sqrt((x * x + y * y + z * z).toDouble()).toFloat()
        if (magnitude <= thresholdAccel) return false

        if (lastTriggerTimeMs != 0L && (timestampMs - lastTriggerTimeMs) < cooldownMs) {
            return false
        }

        if (firstShakeTimeMs == 0L || (timestampMs - firstShakeTimeMs) > windowMs) {
            firstShakeTimeMs = timestampMs
            return false
        }

        if ((timestampMs - firstShakeTimeMs) >= minPeakGapMs) {
            firstShakeTimeMs = 0L
            lastTriggerTimeMs = timestampMs
            onDoubleShake()
            return true
        }
        return false
    }

    fun reset() {
        firstShakeTimeMs = 0L
        lastTriggerTimeMs = 0L
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
