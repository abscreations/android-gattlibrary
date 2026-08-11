package com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Concept2 PM5 decoders — plan §5a.
 *
 * Two fixture sources, deliberately different in kind:
 *
 *  - **TMIW parity** for `0x0032` and `0x0033`, from the pinned corpora
 *    `test/c2_status1_sensor_test.dart` and `test/c2_status2_sensor_test.dart`.
 *  - **Hand-built vectors** for `0x0031` and `0x0036`, constructed from the field table in the
 *    *Concept2 PM Bluetooth Smart Communication Interface Definition* revision 1.30. §5a calls
 *    for these explicitly because TMIW has no vectors for either characteristic, so parity
 *    cannot cover them and a vendor specification is the only available reference.
 *
 * The distinction matters for what a passing test proves. The parity vectors show FitBridge
 * agrees with a working implementation on real traffic. The hand-built ones show only that
 * FitBridge agrees with the document — which is why they are constructed from the published
 * field offsets rather than reverse-engineered from anything.
 */
class Concept2MeasurementTest {

    private fun bytes(vararg v: Int) = ByteArray(v.size) { (v[it] and 0xFF).toByte() }

    // ── TMIW parity: 0x0032 Additional Status 1 ──────────────────────────────────

    /** `test/c2_status1_sensor_test.dart`, both pinned vectors. */
    private val tmiwStatus1 = listOf(
        bytes(139, 47, 0, 164, 8, 40, 255, 123, 88, 132, 103, 0, 0, 0, 0, 0, 0),
        bytes(39, 47, 0, 164, 8, 40, 255, 123, 88, 132, 103, 0, 0, 0, 0, 0, 0),
    )

    @Test
    fun `additional status 1 matches the pinned TMIW vectors`() {
        val first = Concept2Measurement.additionalStatus1(tmiwStatus1[0])!!
        assertEquals(Concept2Measurement.Length.ADDITIONAL_STATUS_1, tmiwStatus1[0].size)
        assertEquals(12_171, first.elapsedCentiseconds)
        assertEquals(121.71, first.elapsedSeconds, 1e-9)
        assertEquals(2.212, first.speedMetresPerSecond!!, 1e-9)
        assertEquals(40, first.strokeRatePerMinute)
        assertEquals(226.51, first.paceSecondsPer500m!!, 1e-9)

        // The two vectors differ only in elapsed time, which is exactly the field TMIW discards.
        val second = Concept2Measurement.additionalStatus1(tmiwStatus1[1])!!
        assertEquals(12_071, second.elapsedCentiseconds)
        assertEquals(first.speedMetresPerSecond, second.speedMetresPerSecond)
        assertEquals(first.paceSecondsPer500m, second.paceSecondsPer500m)
    }

    @Test
    fun `a heart rate of 255 is the documented invalid marker and is dropped`() {
        // Both pinned vectors carry 255. The specification names it invalid rather than leaving
        // it to a plausibility bound, so it is dropped at decode: 255 bpm published downstream
        // would be a reading nobody sent. TMIW drops it too, by a different route.
        for (vector in tmiwStatus1) {
            assertNull(Concept2Measurement.additionalStatus1(vector)!!.heartRateBpm)
        }

        // A real heart rate in the same octet survives.
        val withHr = tmiwStatus1[0].copyOf().also { it[6] = 142.toByte() }
        assertEquals(142, Concept2Measurement.additionalStatus1(withHr)!!.heartRateBpm)

        // Zero is not the documented marker, so it is decoded rather than filtered here; the
        // §11b bound is what excludes it from publication.
        val zeroHr = tmiwStatus1[0].copyOf().also { it[6] = 0 }
        assertEquals(0, Concept2Measurement.additionalStatus1(zeroHr)!!.heartRateBpm)
    }

    // ── TMIW parity: 0x0033 Additional Status 2 ──────────────────────────────────

    private val tmiwStatus2 = listOf(
        bytes(245, 46, 0, 0, 19, 0, 12, 0, 132, 103, 19, 0, 12, 0, 0, 0, 0, 179, 0, 0),
        bytes(90, 47, 0, 0, 19, 0, 12, 0, 132, 103, 19, 0, 12, 0, 0, 0, 0, 179, 0, 0),
    )

    @Test
    fun `additional status 2 matches the pinned TMIW vectors`() {
        val first = Concept2Measurement.additionalStatus2(tmiwStatus2[0])!!
        assertEquals(Concept2Measurement.Length.ADDITIONAL_STATUS_2, tmiwStatus2[0].size)
        assertEquals(12_021, first.elapsedCentiseconds)
        assertEquals(12, first.totalCalories)
        // Average power at octets 4..5 is in the vendor field table but not in plan §5a's
        // consumed list. Decoded because the frame must be walked past it regardless.
        assertEquals(19, first.averagePowerWatts)

        assertEquals(12_122, Concept2Measurement.additionalStatus2(tmiwStatus2[1])!!.elapsedCentiseconds)
    }

    // ── Hand-built from the vendor field table: 0x0031 General Status ────────────

    /**
     * 19 octets. Elapsed 121.71 s, distance 2500.0 m, then the five enum octets and the
     * unconsumed tail.
     *
     * Distance is uint24 in 0.1 m, so 2500.0 m is raw 25000 = `0x0061A8`, little-endian
     * `A8 61 00`.
     */
    private val generalStatus = bytes(
        0x8B, 0x2F, 0x00,       // elapsed 12171 cs
        0xA8, 0x61, 0x00,       // distance 25000 -> 2500.0 m
        0x01,                   // workout type
        0x00,                   // interval type
        0x01,                   // workout state
        0x01,                   // rowing state
        0x02,                   // stroke state
        0x00, 0x00, 0x00,       // total work distance
        0x64,                   // drag factor
        0x00, 0x00, 0x00, 0x00, // tail
    )

    @Test
    fun `general status decodes distance in tenths of a metre`() {
        assertEquals(Concept2Measurement.Length.GENERAL_STATUS, generalStatus.size)

        val r = Concept2Measurement.generalStatus(generalStatus)!!
        assertEquals(12_171, r.elapsedCentiseconds)
        assertEquals(2500.0, r.distanceMetres!!, 1e-9)
    }

    @Test
    fun `general status distance spans the full uint24 range`() {
        // 0.1 m resolution in 24 bits reaches 1,677,721.5 m, so no rowing session can overflow
        // it. Pinned so a future change to the width or scale is visible.
        val max = generalStatus.copyOf().also {
            it[3] = 0xFF.toByte(); it[4] = 0xFF.toByte(); it[5] = 0xFF.toByte()
        }
        assertEquals(1_677_721.5, Concept2Measurement.generalStatus(max)!!.distanceMetres!!, 1e-9)
    }

    // ── Hand-built from the vendor field table: 0x0036 Additional Stroke Data ────

    /** 15 octets. Elapsed 121.71 s, stroke power 203 W, then unconsumed stroke fields. */
    private val strokeData = bytes(
        0x8B, 0x2F, 0x00,       // elapsed 12171 cs
        0xCB, 0x00,             // stroke power 203 W
        0xBC, 0x02,             // stroke calories 700 cal/hr
        0x58, 0x00,             // stroke count 88
        0x00, 0x00, 0x00,       // projected work time
        0x00, 0x00, 0x00,       // projected work distance
    )

    @Test
    fun `additional stroke data decodes stroke power`() {
        assertEquals(Concept2Measurement.Length.ADDITIONAL_STROKE_DATA, strokeData.size)

        val r = Concept2Measurement.additionalStrokeData(strokeData)!!
        assertEquals(12_171, r.elapsedCentiseconds)
        assertEquals(203, r.strokePowerWatts)
    }

    // ── The shared time base ─────────────────────────────────────────────────────

    /**
     * The improvement over TMIW that §5a calls for. All four characteristics begin with the
     * same uint24 elapsed-time field, so frames from different characteristics can be aligned
     * to a common instant rather than merely to arrival order.
     */
    @Test
    fun `all four characteristics expose the same elapsed time base`() {
        val elapsed = 12_171
        val readings = listOf(
            Concept2Measurement.generalStatus(generalStatus),
            Concept2Measurement.additionalStatus1(tmiwStatus1[0]),
            Concept2Measurement.additionalStrokeData(strokeData),
        )
        for (r in readings) {
            assertNotNull(r)
            assertEquals(
                "every Concept2 frame carries its own elapsed time in the same units",
                elapsed,
                r!!.elapsedCentiseconds,
            )
        }
    }

    // ── Multiplexed dispatch (D41) ───────────────────────────────────────────────

    @Test
    fun `a multiplexed frame dispatches on its identification byte`() {
        val framed = bytes(Concept2Measurement.Id.ADDITIONAL_STATUS_1) + tmiwStatus1[0]

        val r = Concept2Measurement.multiplexed(framed)!!
        assertEquals(Concept2Measurement.Id.ADDITIONAL_STATUS_1, r.id)
        assertEquals(2.212, r.speedMetresPerSecond!!, 1e-9)
        assertEquals(12_171, r.elapsedCentiseconds)
    }

    @Test
    fun `every consumed characteristic round-trips through the multiplexed path`() {
        val cases = listOf(
            Concept2Measurement.Id.GENERAL_STATUS to generalStatus,
            Concept2Measurement.Id.ADDITIONAL_STATUS_1 to tmiwStatus1[0],
            Concept2Measurement.Id.ADDITIONAL_STATUS_2 to tmiwStatus2[0],
            Concept2Measurement.Id.ADDITIONAL_STROKE_DATA to strokeData,
        )
        for ((id, payload) in cases) {
            val direct = Concept2Measurement.parse(id, payload)
            val viaMultiplex = Concept2Measurement.multiplexed(bytes(id) + payload)
            assertEquals(
                "0x%02X must decode identically on both delivery paths".format(id),
                direct,
                viaMultiplex,
            )
        }
    }

    @Test
    fun `an unconsumed multiplexed identifier is skipped, not misparsed`() {
        // The PM multiplexes ten identifiers; FitBridge consumes four. The other six are real
        // frames carrying other data, so they must be ignored rather than decoded as though
        // they were one of ours.
        for (id in listOf(0x35, 0x37, 0x38, 0x39, 0x3A, 0x3B)) {
            assertNull(Concept2Measurement.multiplexed(bytes(id) + generalStatus))
        }
    }

    @Test
    fun `an empty or identifier-only multiplexed frame decodes nothing`() {
        assertNull(Concept2Measurement.multiplexed(bytes()))
        assertNull(Concept2Measurement.multiplexed(bytes(Concept2Measurement.Id.GENERAL_STATUS)))
    }

    // ── Truncation ───────────────────────────────────────────────────────────────

    @Test
    fun `every truncation is bounded and yields only the fields ahead of the cut`() {
        // Each vector carries its own elapsed time — the 0x0033 sample is from a different
        // instant than the rest — so the expectation is per case rather than shared.
        val cases = listOf(
            Triple(Concept2Measurement.Id.GENERAL_STATUS, generalStatus, 12_171),
            Triple(Concept2Measurement.Id.ADDITIONAL_STATUS_1, tmiwStatus1[0], 12_171),
            Triple(Concept2Measurement.Id.ADDITIONAL_STATUS_2, tmiwStatus2[0], 12_021),
            Triple(Concept2Measurement.Id.ADDITIONAL_STROKE_DATA, strokeData, 12_171),
        )

        for ((id, full, expectedElapsed) in cases) {
            for (length in 0..full.size) {
                val r = Concept2Measurement.parse(id, full.copyOf(length))

                if (length < 3) {
                    assertNull(
                        "0x%02X at length %d cannot contain the elapsed-time prefix"
                            .format(id, length),
                        r,
                    )
                    continue
                }
                assertNotNull("0x%02X at length %d has a complete prefix".format(id, length), r)
                assertEquals(expectedElapsed, r!!.elapsedCentiseconds)

                // A frame that stops right after the prefix carries no measurement at all.
                if (length == 3) {
                    assertNull(r.distanceMetres)
                    assertNull(r.speedMetresPerSecond)
                    assertNull(r.strokePowerWatts)
                    assertNull(r.totalCalories)
                }
            }
        }
    }

    @Test
    fun `parse rejects an identifier it does not own`() {
        assertNull(Concept2Measurement.parse(0x35, generalStatus))
    }

    // ── Plausibility (§11b groundwork) ───────────────────────────────────────────

    @Test
    fun `decoded values sit inside the rower plausibility bounds`() {
        val persona = FusionPersona.ROW_PADDLE
        val s1 = Concept2Measurement.additionalStatus1(tmiwStatus1[0])!!
        val stroke = Concept2Measurement.additionalStrokeData(strokeData)!!

        // 2.212 m/s is 7.96 km/h.
        val speedKph = s1.speedMetresPerSecond!! * 3.6
        assertTrue(PlausibilityBounds.isPlausible(MetricKind.SPEED, persona, speedKph))
        assertTrue(
            PlausibilityBounds.isPlausible(
                MetricKind.STROKE_RATE,
                persona,
                s1.strokeRatePerMinute!!.toDouble(),
            ),
        )
        assertTrue(
            PlausibilityBounds.isPlausible(
                MetricKind.POWER,
                persona,
                stroke.strokePowerWatts!!.toDouble(),
            ),
        )
        // Pace: 226.51 s per 500 m is 7.55 min/km.
        val paceMinPerKm = s1.paceSecondsPer500m!! * 2.0 / 60.0
        assertTrue(PlausibilityBounds.isPlausible(MetricKind.INSTANT_PACE, persona, paceMinPerKm))
    }
}
