package com.example

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import kotlin.math.abs
import kotlin.math.max

/**
 * Lightweight Back-Tap Gesture Detector (zero vibration / haptics).
 * Uses [Sensor.TYPE_LINEAR_ACCELERATION] (or high-pass filtered [Sensor.TYPE_ACCELEROMETER])
 * focusing strictly on sharp Z-axis impulse spikes while X/Y lateral motion stays comparatively
 * steady so normal walking, pocket jostling, or lateral shaking does not trigger false positives.
 *
 * Fires [onDoubleBackTap] when two sharp Z-axis impulses occur within [minGapMs]..[maxGapMs],
 * debounced by [cooldownMs].
 */
class BackTapDetector(
    @Volatile private var zSpikeThreshold: Float = DEFAULT_SENSITIVITY,
    private val minGapMs: Long = 120L,
    private val maxGapMs: Long = 400L,
    private val cooldownMs: Long = 1200L,
    private val lateralRatioLimit: Float = 0.85f,
    private val onDoubleBackTap: () -> Unit
) : SensorEventListener {

    private var firstTapTimeMs = 0L
    private var lastTriggerTimeMs = 0L

    // High-pass filter state when falling back to raw TYPE_ACCELEROMETER
    private var hasGravityEstimate = false
    private var gravityX = 0f
    private var gravityY = 0f
    private var gravityZ = 0f

    fun setThreshold(threshold: Float) {
        zSpikeThreshold = threshold.coerceIn(MIN_SENSITIVITY, MAX_SENSITIVITY)
    }

    fun getThreshold(): Float = zSpikeThreshold

    /**
     * Evaluates a gravity-free linear acceleration sample (m/s^2).
     * Returns true if a valid double back-tap was detected on this sample.
     */
    fun onLinearAccelerationSample(
        lx: Float,
        ly: Float,
        lz: Float,
        timestampMs: Long
    ): Boolean {
        val absZ = abs(lz)
        if (absZ < zSpikeThreshold) return false

        // Reject lateral walking/shaking impulses where X/Y energy rivals or exceeds Z-axis tap
        val maxLateral = max(abs(lx), abs(ly))
        if (maxLateral > absZ * lateralRatioLimit) {
            return false
        }

        if (lastTriggerTimeMs != 0L && (timestampMs - lastTriggerTimeMs) < cooldownMs) {
            return false
        }

        if (firstTapTimeMs == 0L || (timestampMs - firstTapTimeMs) > maxGapMs) {
            firstTapTimeMs = timestampMs
            return false
        }

        val elapsed = timestampMs - firstTapTimeMs
        if (elapsed >= minGapMs) {
            firstTapTimeMs = 0L
            lastTriggerTimeMs = timestampMs
            onDoubleBackTap()
            return true
        }
        return false
    }

    /**
     * Evaluates a raw accelerometer sample using an exponential high-pass filter
     * when [Sensor.TYPE_LINEAR_ACCELERATION] is unavailable.
     */
    fun onRawAccelerometerSample(
        x: Float,
        y: Float,
        z: Float,
        timestampMs: Long
    ): Boolean {
        if (!hasGravityEstimate) {
            gravityX = x
            gravityY = y
            gravityZ = z
            hasGravityEstimate = true
            return false
        }
        val alpha = 0.8f
        gravityX = alpha * gravityX + (1f - alpha) * x
        gravityY = alpha * gravityY + (1f - alpha) * y
        gravityZ = alpha * gravityZ + (1f - alpha) * z

        val lx = x - gravityX
        val ly = y - gravityY
        val lz = z - gravityZ
        return onLinearAccelerationSample(lx, ly, lz, timestampMs)
    }

    fun reset() {
        firstTapTimeMs = 0L
        lastTriggerTimeMs = 0L
        hasGravityEstimate = false
        gravityX = 0f
        gravityY = 0f
        gravityZ = 0f
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null) return
        val x = event.values.getOrNull(0) ?: return
        val y = event.values.getOrNull(1) ?: return
        val z = event.values.getOrNull(2) ?: return
        val nowMs = System.currentTimeMillis()
        when (event.sensor.type) {
            Sensor.TYPE_LINEAR_ACCELERATION -> onLinearAccelerationSample(x, y, z, nowMs)
            Sensor.TYPE_ACCELEROMETER -> onRawAccelerometerSample(x, y, z, nowMs)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // No-op
    }

    companion object {
        const val MIN_SENSITIVITY = 4f
        const val MAX_SENSITIVITY = 14f
        const val DEFAULT_SENSITIVITY = 7.5f
    }
}
