package com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Production behavior of the Keiser decoder, dropout filter and mapper that the two fixture
 * corpora do not pin (plan §11.3).
 *
 * The corpora own every decoded field value; they are consumed by
 * [KeiserProtocolFixtureIntegrityTest] and [KeiserCaptureFixtureIntegrityTest], which now run the
 * production decoder rather than a local copy of it. What is left here is the behavior that has no
 * captured vector: how the filter bounds a substitution, what the mapper emits and withholds, and
 * the two firmware comparisons that a decimal reading of a hexadecimal byte would silently break.
 *
 * Every packet below is derived from the vendor's official 6.30 parse vector by changing named
 * bytes, so no value is invented; the cases themselves are authored, not captured, and are
 * labelled as such wherever the distinction matters.
 */
class KeiserMSeriesDecoderTest {

    /** The vendor's documented parse example, `0201063000383803460573000D00042701000A`. */
    private val official = byteArrayOf(
        0x02, 0x01, 0x06, 0x30, 0x00, 0x38, 0x38, 0x03, 0x46, 0x05,
        0x73, 0x00, 0x0D, 0x00, 0x04, 0x27, 0x01, 0x00, 0x0A,
    )

    private fun withByte(offset: Int, value: Int): ByteArray =
        official.copyOf().also { it[offset] = value.toByte() }

    private fun withWord(offset: Int, value: Int): ByteArray = official.copyOf().also {
        it[offset] = (value and 0xFF).toByte()
        it[offset + 1] = ((value shr 8) and 0xFF).toByte()
    }

    private fun parse(packet: ByteArray): KeiserMSeriesMeasurement.Reading =
        requireNotNull(KeiserMSeriesMeasurement.parse(packet))

    // ── firmware comparison ──────────────────────────────────────────────────────────────

    @Test
    fun `firmware bytes are compared as hexadecimal so 6_40 outranks 6_30`() {
        // Read as decimal, minor 0x40 is 64 and minor 0x30 is 48, so the ordering survives; read
        // as the rendered strings "6.40" and "6.30" parsed back as decimal fractions, 6.40 is
        // *below* 6.30 in the tenths place and this bike would fall back to the slow window.
        assertEquals(1_000L, parse(withByte(3, 0x40)).statsTimeoutMs)
        assertEquals(1_000L, parse(withByte(3, 0x30)).statsTimeoutMs)
        assertEquals(7_000L, parse(withByte(3, 0x22)).statsTimeoutMs)
        assertEquals("6.40", parse(withByte(3, 0x40)).firmware)
        assertEquals("6.30", parse(withByte(3, 0x30)).firmware)
    }

    @Test
    fun `the fast statistics window requires major six exactly`() {
        // Authored. gymnasticon's bikeVersion gates the new interval on `major === 6`, so an
        // unseen major 7 falls back to the seven-second window rather than being assumed newer.
        // The conservative direction: a slow window on a fast bike holds a reading too long,
        // which is visible; the reverse blanks live telemetry, which reads as a broken app.
        assertEquals(7_000L, KeiserMSeriesMeasurement.statsTimeoutMs(7, 0x40))
        assertEquals(7_000L, KeiserMSeriesMeasurement.statsTimeoutMs(5, 0x40))
        assertEquals(1_000L, KeiserMSeriesMeasurement.statsTimeoutMs(6, 0x30))
    }

    @Test
    fun `resistance presence is gated on the minor byte alone`() {
        // The vendor layout gates byte 18 on the minor byte without mentioning the major, unlike
        // the timeout rule. The asymmetry is preserved rather than harmonised; this pins it so a
        // later reader cannot quietly "fix" it into agreement.
        assertTrue(KeiserMSeriesMeasurement.carriesResistance(0x21))
        assertFalse(KeiserMSeriesMeasurement.carriesResistance(0x20))
        assertEquals(19, official.size)
        assertNotNull(KeiserMSeriesMeasurement.parse(withByte(3, 0x21)))
        assertNull(KeiserMSeriesMeasurement.parse(withByte(3, 0x20)))
        // The same firmware minor also decides the expected length, so a legacy-minor packet is
        // valid at eighteen octets and its resistance is null rather than zero.
        assertNull(parse(official.copyOf(18).also { it[3] = 0x20 }).resistance)
    }

    @Test
    fun `the firmware header is readable from a record the full decoder rejects`() {
        // The version fixtures live exactly here: bytes 2 and 3 varied on one 19-octet buffer,
        // which makes firmware 5.12 appear in a record its own resistance rule says is 18 octets.
        // gymnasticon splits bikeVersion from parse for the same reason, and a bike whose record
        // FitBridge cannot decode should still be able to say which firmware it is running.
        val mismatched = withByte(3, 0x12)

        assertNull(KeiserMSeriesMeasurement.parse(mismatched))
        val version = requireNotNull(KeiserMSeriesMeasurement.firmwareVersion(mismatched))
        assertEquals("6.12", version.display)
        assertEquals(7_000L, version.statsTimeoutMs)
        assertFalse(version.carriesResistance)

        // The magic prefix and the four-octet header are still required.
        assertNull(KeiserMSeriesMeasurement.firmwareVersion(byteArrayOf(0x02, 0x01, 0x06)))
        assertNull(KeiserMSeriesMeasurement.firmwareVersion(withByte(0, 0x03)))
    }

    // ── structural rejection ─────────────────────────────────────────────────────────────

    @Test
    fun `a trailing byte and a short header are both total rejections`() {
        assertNull(KeiserMSeriesMeasurement.parse(official + 0xAA.toByte()))
        assertNull(KeiserMSeriesMeasurement.parse(byteArrayOf(0x02, 0x01, 0x06)))
        assertNull(KeiserMSeriesMeasurement.parse(ByteArray(0)))
        assertFalse(KeiserMSeriesMeasurement.isKeiserField(official + 0xAA.toByte()))
    }

    // ── dropout filter ───────────────────────────────────────────────────────────────────

    @Test
    fun `a second consecutive zero power frame is published as zero`() {
        // Authored, and the reason the filter records decoded values rather than published ones.
        // The captures only ever show single-frame dropouts, so a sustained run of zeros is not a
        // dropout this project has evidence for: holding the last wattage through it would invent
        // power for as long as the rider kept spinning.
        val filter = KeiserDropoutFilter()
        val riding = parse(withWord(10, 115))
        val zeroPower = parse(withWord(10, 0))

        assertEquals(115, filter.filter(riding).powerWatts)
        assertEquals(115, filter.filter(zeroPower).powerWatts)
        assertTrue(filter.lastSubstitutedPower)
        assertEquals(0, filter.filter(zeroPower).powerWatts)
        assertFalse(filter.lastSubstitutedPower)
    }

    @Test
    fun `reset forgets the previous generation so a reselected bike cannot inherit it`() {
        val filter = KeiserDropoutFilter()
        val riding = parse(withWord(10, 115))
        val zeroPower = parse(withWord(10, 0))

        filter.filter(riding)
        filter.reset()

        assertEquals(0, filter.filter(zeroPower).powerWatts)
        assertFalse(filter.lastSubstitutedPower)
    }

    @Test
    fun `both fields zero is a coast and neither is substituted`() {
        val filter = KeiserDropoutFilter()
        filter.filter(parse(withWord(10, 115)))
        val coast = parse(withWord(6, 0).also { packet ->
            packet[10] = 0
            packet[11] = 0
        })

        val filtered = filter.filter(coast)

        assertEquals(0.0, filtered.cadenceRpm, 1e-9)
        assertEquals(0, filtered.powerWatts)
        assertFalse(filter.lastSubstitutedCadence)
        assertFalse(filter.lastSubstitutedPower)
    }

    // ── mapper ───────────────────────────────────────────────────────────────────────────

    @Test
    fun `a review frame maps to no samples at all`() {
        for (reviewType in listOf(0x01, 0x80, 0xFF)) {
            val reading = parse(withByte(4, reviewType))
            assertFalse(reading.isRealtime)
            assertEquals(emptyList<KeiserSample>(), keiserSamples(reading))
        }
    }

    @Test
    fun `the official vector maps to the documented measurement set and nothing else`() {
        val samples = keiserSamples(parse(official)).associateBy { it.metric }

        assertEquals(
            setOf(
                MetricKind.CADENCE,
                MetricKind.AVERAGE_SPEED,
                MetricKind.POWER,
                MetricKind.ENERGY,
                MetricKind.DISTANCE,
                MetricKind.ELAPSED,
                MetricKind.RESISTANCE,
                MetricKind.HEART_RATE,
            ),
            samples.keys,
        )
        assertEquals(82.4, requireNotNull(samples[MetricKind.CADENCE]).value, 1e-9)
        assertEquals(115.0, requireNotNull(samples[MetricKind.POWER]).value, 1e-9)
        assertEquals(13.0, requireNotNull(samples[MetricKind.ENERGY]).value, 1e-9)
        assertEquals(160.934, requireNotNull(samples[MetricKind.DISTANCE]).value, 1e-9)
        assertEquals(279.0, requireNotNull(samples[MetricKind.ELAPSED]).value, 1e-9)
        assertEquals(10.0, requireNotNull(samples[MetricKind.RESISTANCE]).value, 1e-9)
        assertEquals(135.0, requireNotNull(samples[MetricKind.HEART_RATE]).value, 1e-9)
        assertEquals(
            160.934 * 3.6 / 279.0,
            requireNotNull(samples[MetricKind.AVERAGE_SPEED]).value,
            1e-9,
        )
        assertEquals(MetricQuality.ESTIMATED, samples[MetricKind.AVERAGE_SPEED]?.quality)

        // Instantaneous speed needs the stateful source estimator; the pure mapper cannot invent it.
        assertNull(samples[MetricKind.SPEED])
        assertNull(samples[MetricKind.INSTANT_PACE])
    }

    @Test
    fun `speed estimator smooths three coarse metric distance increments`() {
        val estimator = KeiserSpeedEstimator()

        assertEquals(36.0, estimate(estimator, speedReading(100.0, 10)), 1e-9)
        assertEquals(36.0, estimate(estimator, speedReading(100.0, 12)), 1e-9)
        assertEquals(36.0, estimate(estimator, speedReading(200.0, 20)), 1e-9)
        assertEquals(300.0 * 3.6 / 30.0, estimate(estimator, speedReading(400.0, 40)), 1e-9)
        assertEquals(300.0 * 3.6 / 30.0, estimate(estimator, speedReading(500.0, 50)), 1e-9)
    }

    @Test
    fun `stateful speed and pure average samples carry estimated provenance`() {
        val reading = parse(official)
        val speed = estimate(KeiserSpeedEstimator(), reading)
        val samples = keiserSamples(reading, speed).associateBy { it.metric }

        assertEquals(MetricQuality.ESTIMATED, samples[MetricKind.SPEED]?.quality)
        assertEquals(MetricQuality.ESTIMATED, samples[MetricKind.AVERAGE_SPEED]?.quality)
        assertEquals(MetricQuality.NATIVE, samples[MetricKind.DISTANCE]?.quality)
        assertEquals(MetricQuality.NATIVE, samples[MetricKind.ELAPSED]?.quality)
    }

    @Test
    fun `speed estimator uses converted imperial distance and stops immediately`() {
        val estimator = KeiserSpeedEstimator()
        val first = speedReading(
            distanceMetres = 160.934,
            elapsedSeconds = 20,
            unit = KeiserMSeriesMeasurement.DistanceUnit.MILES,
        )
        val second = speedReading(
            distanceMetres = 321.868,
            elapsedSeconds = 40,
            unit = KeiserMSeriesMeasurement.DistanceUnit.MILES,
        )
        assertEquals(28.96812, estimate(estimator, first), 1e-9)
        assertEquals(28.96812, estimate(estimator, second), 1e-9)
        assertEquals(
            0.0,
            estimate(estimator, second.copy(elapsedSeconds = 41, cadenceRpm = 0.0, powerWatts = 0)),
            0.0,
        )
    }

    @Test
    fun `speed estimator rebases on unit counter and time changes and rejects a spike`() {
        val estimator = KeiserSpeedEstimator()
        assertEquals(3.6, estimate(estimator, speedReading(100.0, 100)), 1e-9)
        assertEquals(
            "a one-second 100 m quantization edge is rejected",
            3.6,
            estimate(estimator, speedReading(200.0, 101)),
            1e-9,
        )
        assertEquals(
            3.0,
            estimate(
                estimator,
                speedReading(
                    distanceMetres = 50.0,
                    elapsedSeconds = 60,
                    unit = KeiserMSeriesMeasurement.DistanceUnit.MILES,
                ),
            ),
            1e-9,
        )
        assertEquals(18.0, estimate(estimator, speedReading(25.0, 5)), 1e-9)
    }

    @Test
    fun `speed estimator reset forgets the prior generation slope`() {
        val estimator = KeiserSpeedEstimator()
        estimate(estimator, speedReading(100.0, 10))
        estimate(estimator, speedReading(200.0, 20))

        estimator.reset()

        assertEquals(72.0, estimate(estimator, speedReading(2_000.0, 100)), 1e-9)
    }

    private fun speedReading(
        distanceMetres: Double,
        elapsedSeconds: Int,
        unit: KeiserMSeriesMeasurement.DistanceUnit =
            KeiserMSeriesMeasurement.DistanceUnit.KILOMETRES,
    ): KeiserMSeriesMeasurement.Reading = parse(official).copy(
        distanceMetres = distanceMetres,
        distanceUnit = unit,
        elapsedSeconds = elapsedSeconds,
        cadenceRpm = 80.0,
        powerWatts = 150,
    )

    private fun estimate(
        estimator: KeiserSpeedEstimator,
        reading: KeiserMSeriesMeasurement.Reading,
    ): Double = requireNotNull(estimator.estimateKph(reading))

    @Test
    fun `only distance and energy carry a native cumulative counter`() {
        val samples = keiserSamples(parse(official)).associateBy { it.metric }
        val cumulative = samples.values.filter { it.nativeCumulative != null }.map { it.metric }

        assertEquals(setOf(MetricKind.DISTANCE, MetricKind.ENERGY), cumulative.toSet())
        assertEquals(160.934, requireNotNull(samples[MetricKind.DISTANCE]?.nativeCumulative), 1e-9)
        assertEquals(13.0, requireNotNull(samples[MetricKind.ENERGY]?.nativeCumulative), 1e-9)
    }

    @Test
    fun `every sample carries this firmware's statistics window`() {
        val fast = keiserSamples(parse(withByte(3, 0x30)))
        val slow = keiserSamples(parse(withByte(3, 0x22)))

        assertTrue(fast.isNotEmpty())
        assertTrue(fast.all { it.freshnessMs == 1_000L })
        assertTrue(slow.isNotEmpty())
        assertTrue(slow.all { it.freshnessMs == 7_000L })
    }

    @Test
    fun `zero heart rate is withheld while zero cadence and zero power are published`() {
        val quiet = parse(
            official.copyOf().also {
                it[6] = 0; it[7] = 0
                it[8] = 0; it[9] = 0
                it[10] = 0; it[11] = 0
            },
        )
        val samples = keiserSamples(quiet).associateBy { it.metric }

        assertNull(samples[MetricKind.HEART_RATE])
        assertEquals(0.0, requireNotNull(samples[MetricKind.CADENCE]).value, 1e-9)
        assertEquals(0.0, requireNotNull(samples[MetricKind.POWER]).value, 1e-9)
    }

    @Test
    fun `resistance is withheld when the firmware does not carry it`() {
        val legacy = parse(official.copyOf(18).also { it[2] = 0x05; it[3] = 0x12 })
        val samples = keiserSamples(legacy).associateBy { it.metric }

        assertEquals("5.12", legacy.firmware)
        assertNull(legacy.resistance)
        assertNull(samples[MetricKind.RESISTANCE])
        assertTrue(samples.containsKey(MetricKind.POWER))
    }

    @Test
    fun `mapper output survives the existing plausibility bounds for a captured extreme`() {
        // Plan §9: captured heart rate 239.5 bpm and power 676 W must pass the §11b bounds, while
        // the authored high-bit packet must not. Pinned here because the mapper is where a
        // mis-scaled field would first become a published metric.
        val extreme = parse(
            official.copyOf().also {
                it[8] = 0x5B; it[9] = 0x09
                it[10] = 0xA4.toByte(); it[11] = 0x02
            },
        )
        assertEquals(239.5, extreme.heartRateBpm, 1e-9)
        assertEquals(676, extreme.powerWatts)
        for (sample in keiserSamples(extreme)) {
            assertTrue(
                "${sample.metric} ${sample.value}",
                PlausibilityBounds.isPlausible(sample.metric, FusionPersona.RIDE, sample.value),
            )
        }

        val mangled = parse(withWord(6, 0x8001))
        assertEquals(3276.9, mangled.cadenceRpm, 1e-9)
        assertFalse(
            PlausibilityBounds.isPlausible(MetricKind.CADENCE, FusionPersona.RIDE, mangled.cadenceRpm),
        )
    }
}
