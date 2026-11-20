package com.putokulo.app.crash

import org.junit.Assert.assertEquals
import org.junit.Test

class CrashDetectorTest {
    private var crashes = 0
    private val detector = CrashDetector { crashes++ }
    private var now = 0L

    private val resting = Triple(0f, 0f, 9.81f)
    private val impact = Triple(30f, 20f, 25f) // ≈ 4.5g

    /** Feed accelerometer samples at 50 Hz, with a GPS fix every second. */
    private fun feed(seconds: Double, accel: (Long) -> Triple<Float, Float, Float>, speedKmh: Float? = null) {
        val end = now + (seconds * 1000).toLong()
        while (now < end) {
            if (speedKmh != null && now % 1000 == 0L) detector.onSpeed(speedKmh, now)
            val (x, y, z) = accel(now)
            detector.onAcceleration(x, y, z, now)
            now += 20
        }
    }

    private fun shaking(t: Long) = if ((t / 20) % 2 == 0L) Triple(0f, 0f, 15f) else Triple(0f, 0f, 4f)

    @Test
    fun crashWhileRidingThenStill_triggersAlert() {
        feed(5.0, { resting }, speedKmh = 40f)
        feed(0.1, { impact })
        feed(10.0, { resting }, speedKmh = 0f)
        assertEquals(1, crashes)
    }

    @Test
    fun impactWhileParked_isIgnored() {
        feed(5.0, { resting }, speedKmh = 0f)
        feed(0.1, { impact })
        feed(10.0, { resting }, speedKmh = 0f)
        assertEquals(0, crashes)
    }

    @Test
    fun potholeWhileStillRiding_isIgnored() {
        feed(5.0, { resting }, speedKmh = 40f)
        feed(0.1, { impact })
        feed(10.0, { resting }, speedKmh = 38f)
        assertEquals(0, crashes)
    }

    @Test
    fun riderGetsUpAndWalks_isIgnored() {
        feed(5.0, { resting }, speedKmh = 40f)
        feed(0.1, { impact })
        feed(10.0, ::shaking, speedKmh = 3f)
        assertEquals(0, crashes)
    }

    @Test
    fun gpsLostAfterCrash_stillTriggers() {
        feed(5.0, { resting }, speedKmh = 40f)
        feed(0.1, { impact })
        feed(10.0, { resting }) // no GPS fixes after the crash; the last speed (40) is stale
        assertEquals(1, crashes)
    }
}
