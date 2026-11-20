package com.putokulo.app.crash

import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Decides when the phone's motion looks like a crash:
 *  1. an impact above [IMPACT_G],
 *  2. while the rider was moving faster than [MOVING_KMH] in the seconds before,
 *  3. followed by the phone lying still and GPS speed near zero.
 *
 * Plain Kotlin with no Android dependencies so it can be unit tested.
 * Times are milliseconds from any monotonic clock.
 */
class CrashDetector(private val onCrash: () -> Unit) {

    private var lastMovingAtMs: Long? = null
    private var speedKmh: Float? = null
    private var speedAtMs = 0L
    private var impactAtMs: Long? = null
    private val stillSamples = ArrayList<Float>()

    fun onSpeed(kmh: Float, nowMs: Long) {
        speedKmh = kmh
        speedAtMs = nowMs
        if (kmh >= MOVING_KMH) lastMovingAtMs = nowMs
    }

    /** Raw accelerometer values in m/s², including gravity. */
    fun onAcceleration(x: Float, y: Float, z: Float, nowMs: Long) {
        val magnitude = sqrt(x * x + y * y + z * z)
        val impactAt = impactAtMs

        if (impactAt == null) {
            val wasMoving = lastMovingAtMs?.let { nowMs - it <= MOVING_WINDOW_MS } ?: false
            if (magnitude >= IMPACT_G * GRAVITY && wasMoving) {
                impactAtMs = nowMs
                stillSamples.clear()
            }
            return
        }

        val elapsed = nowMs - impactAt
        when {
            elapsed < SETTLE_MS -> Unit // ignore tumbling right after the impact
            elapsed < SETTLE_MS + STILL_WINDOW_MS -> stillSamples.add(magnitude)
            else -> {
                impactAtMs = null
                if (isStill(stillSamples) && !isStillMoving(nowMs)) onCrash()
            }
        }
    }

    private fun isStill(samples: List<Float>): Boolean {
        if (samples.size < MIN_STILL_SAMPLES) return false
        val mean = samples.average()
        val variance = samples.sumOf { (it - mean).pow(2) } / samples.size
        return sqrt(variance) < STILL_STDDEV
    }

    /** A recent GPS fix says the rider is still going, so it was probably a pothole. Stale fixes are ignored. */
    private fun isStillMoving(nowMs: Long): Boolean {
        val speed = speedKmh ?: return false
        return nowMs - speedAtMs <= SPEED_FRESH_MS && speed >= STOPPED_KMH
    }

    companion object {
        const val GRAVITY = 9.81f
        const val IMPACT_G = 4f
        const val MOVING_KMH = 15f
        const val MOVING_WINDOW_MS = 5_000L
        const val SETTLE_MS = 2_000L
        const val STILL_WINDOW_MS = 5_000L
        const val STILL_STDDEV = 1.5f // m/s²
        const val STOPPED_KMH = 5f
        const val SPEED_FRESH_MS = 3_000L
        private const val MIN_STILL_SAMPLES = 10
    }
}
