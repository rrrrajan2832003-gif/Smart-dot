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
    private val maxGapMs: Long = 380L,
    private val cooldownMs: Long = 1000L,
    private val maxLateralAccel: Float = 4.5f,
    private val maxZSpike: Float = MAX_Z_SPIKE,
    private val maxPeakDurationMs: Long = 60L,
    private val shakeSuppressMs: Long = 220L,
    private val lateralRatioLimit: Float = 0.75f,
    private val onDoubleBackTap: () -> Unit
) : SensorEventListener {

    private var firstTapTimeMs = 0L
    private var lastTriggerTimeMs = 0L
    private var lastShakeTimeMs = 0L
    private var zSpikeStartMs = 0L
    private var lastHighZSampleMs = 0L
    private var zSpikeRejected = false

    // High-pass filter state when reading raw TYPE_ACCELEROMETER
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
     * Strict double-tap criteria:
     * - Sharp isolated Z-axis impulse between [zSpikeThreshold] (7.5 m/s^2) and [maxZSpike] (18.0 m/s^2)
     * - Minimal lateral X/Y motion (|X| <= 4.5 and |Y| <= 4.5 m/s^2)
     * - Discrete tap pulse (< 60ms continuous duration), not sustained shaking/walking
     * - Two distinct taps separated by 120ms..380ms with 1000ms trigger cooldown
     */
    fun onLinearAccelerationSample(
        lx: Float,
        ly: Float,
        lz: Float,
        timestampMs: Long
    ): Boolean {
        val absX = abs(lx)
        val absY = abs(ly)
        val absZ = abs(lz)
        val maxLateral = max(absX, absY)

        // Reject walking, pocket jostling, or phone shaking where X/Y lateral motion is active
        if (absX > maxLateralAccel || absY > maxLateralAccel ||
            (absZ >= zSpikeThreshold && maxLateral > absZ * lateralRatioLimit)
        ) {
            firstTapTimeMs = 0L
            lastShakeTimeMs = timestampMs
            zSpikeStartMs = 0L
            lastHighZSampleMs = 0L
            zSpikeRejected = false
            return false
        }

        if (lastShakeTimeMs != 0L && (timestampMs - lastShakeTimeMs) < shakeSuppressMs) {
            return false
        }

        // Below threshold -> end of current impulse pulse
        if (absZ < zSpikeThreshold) {
            zSpikeStartMs = 0L
            lastHighZSampleMs = 0L
            zSpikeRejected = false
            return false
        }

        // Reject violent drops/slams (> 18.0 m/s^2)
        if (absZ > maxZSpike) {
            firstTapTimeMs = 0L
            lastShakeTimeMs = timestampMs
            zSpikeStartMs = 0L
            lastHighZSampleMs = 0L
            zSpikeRejected = true
            return false
        }

        // Enforce discrete tap peak duration (< 60ms). Continuous high-Z oscillations across adjacent
        // samples spanning >= 60ms are treated as shaking rather than a crisp finger back-tap.
        if (lastHighZSampleMs != 0L && (timestampMs - lastHighZSampleMs) <= 80L) {
            if (zSpikeRejected || (timestampMs - zSpikeStartMs) >= maxPeakDurationMs) {
                zSpikeRejected = true
                firstTapTimeMs = 0L
                lastShakeTimeMs = timestampMs
                lastHighZSampleMs = timestampMs
                return false
            }
        } else {
            zSpikeStartMs = timestampMs
            zSpikeRejected = false
        }
        lastHighZSampleMs = timestampMs

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
     * Evaluates a raw accelerometer sample using an exponential high-pass filter (alpha = 0.8f).
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
        lastShakeTimeMs = 0L
        zSpikeStartMs = 0L
        lastHighZSampleMs = 0L
        zSpikeRejected = false
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
        const val MAX_SENSITIVITY = 16f
        const val DEFAULT_SENSITIVITY = 7.5f
        const val MAX_Z_SPIKE = 18.0f
    }
}
