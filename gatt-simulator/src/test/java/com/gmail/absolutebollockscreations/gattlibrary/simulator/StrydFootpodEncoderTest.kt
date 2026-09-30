package com.gmail.absolutebollockscreations.gattlibrary.simulator

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Encoder and gait-model coverage for the Stryd footpod persona.
 *
 * The fixtures in the first three tests are **verbatim captured frames** from the sanitized corpus
 * (`gatt-fixtures/.../fixtures/hardware/stryd/`), not values computed from this implementation. That is the point: an
 * encoder that agrees with itself proves nothing, so each one feeds the frame's own decoded field
 * values back in and asserts the octets come out identical. Every one of the corpus's 3,717 RSC
 * and CPS frames round-trips this way; these are four representative ones.
 *
 * The remaining tests cover the properties no single frame can show - the cross-service agreement
 * the two characteristics must keep, and the accumulator's behaviour at gaps, wraps and stalls
 * (plan §11a: for a family with no hardware, adversarial fixtures are the core of the phase, not
 * trailing coverage).
 */
class StrydFootpodEncoderTest {

    // ─────────────────────────────────────────────────────────────
    // Captured-frame parity
    // ─────────────────────────────────────────────────────────────

    @Test
    fun `captured running RSC frame re-encodes octet for octet`() {
        // 07 e7 01 56 92 00 d6 06 00 00
        // flags 0x07 running, 0x01e7 = 487/256 = 1.9023 m/s, 86 strides/min, 146 cm, 1750 = 175.0 m
        val encoded = StrydFootpodEncoder.rscMeasurement(
            speedMps = 487.0 / 256.0,
            cadenceStridesPerMinute = 86,
            strideLengthCm = 146,
            totalDistanceMetres = 175.0,
            running = true,
        )

        assertEquals(10, encoded.size)
        assertArrayEquals(
            byteArrayOf(0x07, 0xE7.toByte(), 0x01, 0x56, 0x92.toByte(), 0x00, 0xD6.toByte(), 0x06, 0x00, 0x00),
            encoded,
        )
    }

    @Test
    fun `captured walking RSC frame re-encodes octet for octet`() {
        // 03 e0 00 2f 6d 00 8c 0a 00 00
        // flags 0x03 walking, 0x00e0 = 224/256 = 0.875 m/s, 47 strides/min, 109 cm, 2700 = 270.0 m
        val encoded = StrydFootpodEncoder.rscMeasurement(
            speedMps = 224.0 / 256.0,
            cadenceStridesPerMinute = 47,
            strideLengthCm = 109,
            totalDistanceMetres = 270.0,
            running = false,
        )

        assertArrayEquals(
            byteArrayOf(0x03, 0xE0.toByte(), 0x00, 0x2F, 0x6D, 0x00, 0x8C.toByte(), 0x0A, 0x00, 0x00),
            encoded,
        )
    }

    @Test
    fun `captured CPS frames re-encode octet for octet`() {
        // 20 00 9f 00 de 37 5b fc  -> 159 W, 14302 strides, 64603 ticks
        assertArrayEquals(
            byteArrayOf(0x20, 0x00, 0x9F.toByte(), 0x00, 0xDE.toByte(), 0x37, 0x5B, 0xFC.toByte()),
            StrydFootpodEncoder.cyclingPowerMeasurement(159, 14_302, 64_603),
        )
        // 20 00 a1 00 df 37 25 ff  -> the next second: one more stride, 714 ticks later
        assertArrayEquals(
            byteArrayOf(0x20, 0x00, 0xA1.toByte(), 0x00, 0xDF.toByte(), 0x37, 0x25, 0xFF.toByte()),
            StrydFootpodEncoder.cyclingPowerMeasurement(161, 14_303, 65_317),
        )
    }

    @Test
    fun `measurement frames are fixed length with the captured flag values`() {
        assertEquals(0x03, StrydFootpodEncoder.RSC_FLAGS_WALKING)
        assertEquals(0x07, StrydFootpodEncoder.RSC_FLAGS_RUNNING)
        assertEquals(0x0020, StrydFootpodEncoder.CPS_FLAGS)
        assertEquals(10, StrydFootpodEncoder.rscMeasurement(2.0, 86, 140, 100.0, true).size)
        assertEquals(8, StrydFootpodEncoder.cyclingPowerMeasurement(0, 0, 0).size)
    }

    // ─────────────────────────────────────────────────────────────
    // Feature values are derived from what the persona emits
    // ─────────────────────────────────────────────────────────────

    @Test
    fun `feature values declare exactly the presence bits every frame sets`() {
        // No capture ever read these characteristics, so the only defensible value is one derived
        // from the emitter. If a frame's presence bits ever change, this test fails rather than
        // letting the persona advertise a capability it does not exercise.
        val frame = StrydFootpodEncoder.rscMeasurement(2.0, 86, 140, 10.0, running = true)
        val presence = frame[0].toInt() and
            (StrydFootpodEncoder.RSC_FLAG_STRIDE_LENGTH or StrydFootpodEncoder.RSC_FLAG_TOTAL_DISTANCE)
        assertArrayEquals(byteArrayOf(presence.toByte(), 0x00), StrydFootpodEncoder.rscFeature())

        assertArrayEquals(byteArrayOf(0x08, 0x00, 0x00, 0x00), StrydFootpodEncoder.cyclingPowerFeature())
        assertArrayEquals(byteArrayOf(0x02), StrydFootpodEncoder.sensorLocation())
    }

    // ─────────────────────────────────────────────────────────────
    // Gait model: the identity real hardware never breaks
    // ─────────────────────────────────────────────────────────────

    @Test
    fun `speed equals cadence times stride over sixty across the ride range`() {
        // Walking through the corpus range and running through the treadmill ride's 8-13 km/h.
        var speed = 0.5
        while (speed <= 3.7) {
            val cadence = RunningGait.cadenceStridesPerMinute(speed)
            val strideCm = RunningGait.strideLengthCm(speed, cadence)
            val implied = cadence * (strideCm / 100.0) / 60.0
            assertTrue(
                "speed=$speed cadence=$cadence stride=$strideCm implied=$implied",
                kotlin.math.abs(implied - speed) < 0.02,
            )
            speed += 0.1
        }
    }

    @Test
    fun `cadence stays inside observed running rates instead of extrapolating`() {
        // A linear fit to the walking corpus would reach 128 strides/min at 13 km/h. The
        // saturating running segment is why it does not.
        assertEquals(0, RunningGait.cadenceStridesPerMinute(0.0))
        assertTrue(RunningGait.cadenceStridesPerMinute(2.22) in 80..92)
        assertTrue(RunningGait.cadenceStridesPerMinute(3.61) in 84..96)
        assertTrue(RunningGait.cadenceStridesPerMinute(3.61) <= 0xFF)
    }

    @Test
    fun `stationary emits zero speed cadence and stride while holding distance`() {
        val accumulator = RunningStrideAccumulator()
        accumulator.update(2.5, 0L)
        accumulator.update(2.5, 4_000L)
        val moved = accumulator.snapshot().totalDistanceMetres

        val stopped = accumulator.update(0.0, 5_000L)

        assertEquals(0.0, stopped.speedMps, 0.0)
        assertEquals(0, stopped.cadenceStridesPerMinute)
        assertEquals(0, stopped.strideLengthCm)
        assertEquals(moved, stopped.totalDistanceMetres, 0.001)
        assertTrue("standing still is not running", !stopped.running)
    }

    // ─────────────────────────────────────────────────────────────
    // Accumulator behaviour
    // ─────────────────────────────────────────────────────────────

    @Test
    fun `stride phase carries across ticks so the count does not depend on timing`() {
        val coarse = RunningStrideAccumulator()
        coarse.update(3.0, 0L)
        repeat(60) { tick -> coarse.update(3.0, (tick + 1) * 1_000L) }

        val fine = RunningStrideAccumulator()
        fine.update(3.0, 0L)
        repeat(240) { tick -> fine.update(3.0, (tick + 1) * 250L) }

        // Same 60 seconds at the same speed: the two counts may differ by at most the one stride
        // straddling the boundary, never accumulate a per-tick rounding error.
        val difference = kotlin.math.abs(coarse.snapshot().cumulativeStrides - fine.snapshot().cumulativeStrides)
        assertTrue("coarse and fine ticking diverged by $difference strides", difference <= 1)
    }

    @Test
    fun `a gap longer than the integration bound is treated as a stall`() {
        val accumulator = RunningStrideAccumulator()
        accumulator.update(3.0, 0L)
        val before = accumulator.snapshot()

        val after = accumulator.update(3.0, RunningStrideAccumulator.MAX_INTEGRATION_GAP_MS + 1_000L)

        assertEquals(before.cumulativeStrides, after.cumulativeStrides)
        assertEquals(before.totalDistanceMetres, after.totalDistanceMetres, 0.0)
    }

    @Test
    fun `the stride counter wraps at sixteen bits like the wire field`() {
        val accumulator = RunningStrideAccumulator()
        var now = 0L
        accumulator.update(4.0, now)
        // Long enough to pass 65535 strides at ~95 strides/min.
        repeat(45_000) {
            now += 1_000L
            accumulator.update(4.0, now)
        }
        val snapshot = accumulator.snapshot()

        assertTrue("counter escaped uint16", snapshot.cumulativeStrides in 0..0xFFFF)
        assertTrue("event time escaped uint16", snapshot.lastStrideEventTime1024 in 0..0xFFFF)
        assertTrue("distance should keep growing past the counter wrap", snapshot.totalDistanceMetres > 100_000.0)
    }

    @Test
    fun `distance never goes backwards within a session`() {
        val accumulator = RunningStrideAccumulator()
        var previous = 0.0
        var now = 0L
        listOf(0.0, 1.2, 3.4, 3.4, 0.0, 2.8, 0.0).forEach { speed ->
            repeat(5) {
                now += 1_000L
                val distance = accumulator.update(speed, now).totalDistanceMetres
                assertTrue("distance fell from $previous to $distance", distance >= previous)
                previous = distance
            }
        }
    }

    @Test
    fun `reset clears the session the way a fresh advertise must`() {
        val accumulator = RunningStrideAccumulator()
        // Ticked inside MAX_INTEGRATION_GAP_MS. A single 10-second step would be treated as a
        // stall by `a gap longer than the integration bound is treated as a stall` above, so
        // nothing would accumulate and this test would pass for the wrong reason.
        var now = 0L
        accumulator.update(3.0, now)
        repeat(10) {
            now += 1_000L
            accumulator.update(3.0, now)
        }
        assertTrue(accumulator.snapshot().cumulativeStrides > 0)

        accumulator.reset()

        val cleared = accumulator.snapshot()
        assertEquals(0, cleared.cumulativeStrides)
        assertEquals(0.0, cleared.totalDistanceMetres, 0.0)
        assertEquals(0, cleared.lastStrideEventTime1024)
    }

    // ─────────────────────────────────────────────────────────────
    // Cross-service agreement
    // ─────────────────────────────────────────────────────────────

    @Test
    fun `RSC cadence and the CPS crank counter describe the same strides`() {
        val accumulator = RunningStrideAccumulator()
        accumulator.update(3.0, 0L)
        var now = 0L
        var previousStrides = 0
        repeat(30) {
            now += 1_000L
            val gait = accumulator.update(3.0, now)
            val rsc = StrydFootpodEncoder.rscMeasurement(
                gait.speedMps,
                gait.cadenceStridesPerMinute,
                gait.strideLengthCm,
                gait.totalDistanceMetres,
                gait.running,
            )
            val cps = StrydFootpodEncoder.cyclingPowerMeasurement(
                200,
                gait.cumulativeStrides,
                gait.lastStrideEventTime1024,
            )
            val rscCadence = rsc[3].toInt() and 0xFF
            val crankDelta = (cps[4].toInt() and 0xFF or ((cps[5].toInt() and 0xFF) shl 8)) - previousStrides
            previousStrides += crankDelta

            // One second of ticking must advance the crank counter by the cadence the RSC frame
            // reports, to within the one stride the fractional phase can hold back.
            assertTrue(
                "cadence $rscCadence/min but crank advanced $crankDelta in one second",
                kotlin.math.abs(crankDelta - rscCadence / 60.0) <= 1.0,
            )
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Battery
    // ─────────────────────────────────────────────────────────────

    @Test
    fun `generated battery falls monotonically and clamps`() {
        val battery = SyntheticBatteryLevel()
        assertEquals(SyntheticBatteryLevel.START_PERCENT, battery.levelAt(0))
        assertEquals(SyntheticBatteryLevel.START_PERCENT - 1, battery.levelAt(600))
        assertEquals(SyntheticBatteryLevel.START_PERCENT - 3, battery.levelAt(1_800))
        assertEquals(SyntheticBatteryLevel.MIN_PERCENT, battery.levelAt(Int.MAX_VALUE))

        var previous = 101
        for (second in 0..20_000 step 137) {
            val level = battery.levelAt(second)
            assertTrue("battery rose at ${second}s", level <= previous)
            assertTrue("battery escaped 1..100", level in 1..100)
            previous = level
        }
    }

    @Test
    fun `battery notifies on the captured five second tick`() {
        assertEquals(5_000L, StrydFootpodEncoder.BATTERY_PERIOD_MS)
        assertEquals(1_000L, StrydFootpodEncoder.MEASUREMENT_PERIOD_MS)
    }
}
