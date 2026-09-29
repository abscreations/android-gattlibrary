package com.gmail.absolutebollockscreations.gattlibrary.central

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KayakFirstMeasurementTest {
    private val adversarialResource = "fixtures/simulated/kayak_first_adversarial_v1.json"
    private val hardwareResource = "fixtures/hardware/kayak_first_qz_202604.json"

    @Test
    fun `display configuration including selectors 15 17 and 18 cannot mutate the C6 mapping`() {
        // Phase 8 exit item. Selectors are console *display* choices, in a vocabulary that
        // overlaps the C6 index space by coincidence of numbering; 15/17/18 are the later TMIW
        // additions and the ones most likely to be mistaken for field indexes. Configuring any of
        // them must leave measurement decoding byte-for-byte identical, so the assertion is that
        // the decoder is stateless with respect to display configuration and stays that way.
        val frame = "6;900000010449;0;0;0;0.00;0;1;0;11;0.94;2.75;98;76;229;72;573;180;1147;360;96;82;10;1"
        val before = KayakFirstMeasurement.decode(frame) as KayakFirstMeasurementDecodeResult.Decoded

        val selectors = requireNotNull(KayakFirstDisplayConfiguration.fromWireCodes(listOf(15, 17, 18, 15, 17)))
        assertEquals(listOf(15, 17, 18, 15, 17), selectors.slots.map { it.wireCode })

        val after = KayakFirstMeasurement.decode(frame) as KayakFirstMeasurementDecodeResult.Decoded
        assertEquals(before.reading, after.reading)
        // And the default configuration decodes the same frame to the same reading.
        assertEquals(
            before.reading,
            (KayakFirstMeasurement.decode(frame) as KayakFirstMeasurementDecodeResult.Decoded).reading,
        )
    }

    @Test
    fun `baseline and every physical vector decode at the specified indexes`() {
        val adversarial = load(adversarialResource)
        val baseline = FixtureJson.obj(adversarial["baseline_measurement"])
        assertDecoded(FixtureJson.string(baseline["frame_ascii"]), FixtureJson.obj(baseline["expect"]))

        val hardware = load(hardwareResource)
        FixtureJson.array(hardware["measurement_cases"]).map(FixtureJson::obj).forEach { case ->
            assertDecoded(FixtureJson.string(case["frame_ascii"]), FixtureJson.obj(case["expect"]))
        }
        FixtureJson.array(hardware["fragmentation_cases"]).map(FixtureJson::obj).forEach { case ->
            assertDecoded(FixtureJson.string(case["reassembled_ascii"]), FixtureJson.obj(case["expect"]))
        }
    }

    @Test
    fun `all truncation depths reject before any indexed access`() {
        val fixture = load(adversarialResource)
        val baselineFields = baselineFields(fixture)
        val truncation = FixtureJson.obj(fixture["truncation_matrix"])

        ints(truncation["prefix_field_counts"]).forEach { count ->
            val line = if (count == 0) "" else baselineFields.take(count).joinToString(";")
            val rejected = KayakFirstMeasurement.decode(line) as KayakFirstMeasurementDecodeResult.Rejected
            assertEquals(
                if (count == 0) {
                    KayakFirstMeasurementRejection.EMPTY_LINE
                } else {
                    KayakFirstMeasurementRejection.TOO_FEW_FIELDS
                },
                rejected.reason,
            )
        }

        val hardware = load(hardwareResource)
        FixtureJson.array(hardware["short_measurement_cases"]).map(FixtureJson::obj).forEach { case ->
            val rejected = KayakFirstMeasurement.decode(
                FixtureJson.string(case["frame_ascii"]),
            ) as KayakFirstMeasurementDecodeResult.Rejected
            assertEquals(KayakFirstMeasurementRejection.TOO_FEW_FIELDS, rejected.reason)
        }
    }

    @Test
    fun `all mutation cases produce their independently authored outcome`() {
        val fixture = load(adversarialResource)
        val baselineFields = baselineFields(fixture)
        val cases = FixtureJson.array(fixture["measurement_mutation_cases"]).map(FixtureJson::obj)

        cases.forEach { case ->
            val fields = baselineFields.toMutableList()
            case["mutations"]?.let { rawMutations ->
                FixtureJson.array(rawMutations).map(FixtureJson::obj).forEach { mutation ->
                    fields[FixtureJson.int(mutation["index"])] = FixtureJson.string(mutation["value"])
                }
            }
            case["append_fields"]?.let { fields += strings(it) }
            val result = KayakFirstMeasurement.decode(fields.joinToString(";"))
            val expected = FixtureJson.string(case["expect"])
            if (expected.startsWith("accept-measurement")) {
                assertTrue(FixtureJson.string(case["id"]), result is KayakFirstMeasurementDecodeResult.Decoded)
                val reading = (result as KayakFirstMeasurementDecodeResult.Decoded).reading
                if (case["expect_speed_kmh"] != null) {
                    assertEquals(FixtureJson.double(case["expect_speed_kmh"]), reading.speedKph, 1e-9)
                }
                if (case["expect_coverage_window_seconds"] != null) {
                    assertEquals(
                        FixtureJson.string(case["id"]),
                        FixtureJson.int(case["expect_coverage_window_seconds"]).toLong(),
                        reading.coverageWindowSeconds,
                    )
                }
                if (case["expect_coverage_window_null"] != null) {
                    assertNull(FixtureJson.string(case["id"]), reading.coverageWindowSeconds)
                }
            } else {
                val rejected = result as KayakFirstMeasurementDecodeResult.Rejected
                assertEquals(
                    FixtureJson.string(case["id"]),
                    rejectionFor(expected),
                    rejected.reason,
                )
            }
        }
    }

    @Test
    fun `decoder bounds are the row paddle source bounds`() {
        assertEquals(
            40.0,
            requireNotNull(PlausibilityBounds.rangeFor(MetricKind.SPEED, FusionPersona.ROW_PADDLE)).max,
            0.0,
        )
        assertEquals(
            120.0,
            requireNotNull(PlausibilityBounds.rangeFor(MetricKind.CADENCE, FusionPersona.ROW_PADDLE)).max,
            0.0,
        )
        assertEquals(40.0, KayakFirstMeasurement.MAX_SPEED_KPH, 0.0)
        assertEquals(120, KayakFirstMeasurement.MAX_CADENCE_SPM)
    }

    @Test
    fun `physical vector decodes average and paired pace fields at captured indexes`() {
        val reading = (KayakFirstMeasurement.decode(
            "6;900000040098;0;0;0;0.00;0;1;0;94;2.39;2.74;86;86;83;73;209;182;418;365;86;84;40;1",
        ) as KayakFirstMeasurementDecodeResult.Decoded).reading

        assertEquals(2.39, reading.averageSpeedMetresPerSecond, 0.0)
        assertEquals(8.604, reading.averageSpeedKph, 1e-9)
        assertEquals(86, reading.averageCadenceSpm)
        assertEquals(83L, reading.average200mPaceSeconds)
        assertEquals(73L, reading.instantaneous200mPaceSeconds)
        assertEquals(209L, reading.average500mPaceSeconds)
        assertEquals(182L, reading.instantaneous500mPaceSeconds)
        assertEquals(418L, reading.average1000mPaceSeconds)
        assertEquals(365L, reading.instantaneous1000mPaceSeconds)
        assertEquals(86.0, reading.averageForceNewtons, 0.0)
        assertEquals(84.0, reading.forceNewtons, 0.0)
        assertEquals(900000040098L, reading.eventTimestampMs)
        assertTrue(reading.activePaddling)
        assertEquals(2L * reading.average500mPaceSeconds, reading.average1000mPaceSeconds)
        assertEquals(2L * reading.instantaneous500mPaceSeconds + 1L, reading.instantaneous1000mPaceSeconds)
    }

    @Test
    fun `fresh flag accepts only zero or one and held elapsed remains decoded`() {
        val held = (KayakFirstMeasurement.decode(
            baselineLine().split(';').toMutableList().also {
                it[KayakFirstMeasurement.ACTIVE_PADDLING_INDEX] = "0"
                it[KayakFirstMeasurement.ELAPSED_INDEX] = "70"
            }.joinToString(";"),
        ) as KayakFirstMeasurementDecodeResult.Decoded).reading
        assertTrue(!held.activePaddling)
        assertEquals(70L, held.elapsedSeconds)

        val invalid = KayakFirstMeasurement.decode(
            baselineLine().split(';').toMutableList().also {
                it[KayakFirstMeasurement.ACTIVE_PADDLING_INDEX] = "2"
            }.joinToString(";"),
        ) as KayakFirstMeasurementDecodeResult.Rejected
        assertEquals(KayakFirstMeasurementRejection.INVALID_NUMBER, invalid.reason)
        assertEquals(KayakFirstMeasurement.ACTIVE_PADDLING_INDEX, invalid.fieldIndex)
    }

    @Test
    fun `field 23 decodes as the coverage window and never gates the measurement`() {
        fun windowOf(raw: String) = (KayakFirstMeasurement.decode(
            baselineLine().split(';').toMutableList().also {
                it[KayakFirstMeasurement.WINDOW_INDEX] = raw
            }.joinToString(";"),
        ) as KayakFirstMeasurementDecodeResult.Decoded).reading

        assertEquals(1L, windowOf("1").coverageWindowSeconds)
        // Two seconds when a poll was missed; larger on the first frame after a start.
        assertEquals(2L, windowOf("2").coverageWindowSeconds)
        assertEquals(5L, windowOf("5").coverageWindowSeconds)
        // Zero is not a usable weight.
        assertEquals(null, windowOf("0").coverageWindowSeconds)
        // A corrupt value must not reject an otherwise usable frame, and must not be a weight.
        val corrupt = windowOf("x")
        assertEquals(null, corrupt.windowSeconds)
        assertEquals("x", corrupt.diagnosticStatusRaw)
        // A wild value is clamped rather than injecting an enormous integration weight.
        assertEquals(
            KayakFirstMeasurement.MAX_WINDOW_SECONDS,
            windowOf("999999").coverageWindowSeconds,
        )
    }

    @Test
    fun `newly consumed fields reject malformed overflow and implausible values`() {
        val fields = baselineLine().split(';').toMutableList()

        fun rejection(index: Int, value: String): KayakFirstMeasurementDecodeResult.Rejected {
            val mutated = fields.toMutableList().also { it[index] = value }
            return KayakFirstMeasurement.decode(mutated.joinToString(";"))
                as KayakFirstMeasurementDecodeResult.Rejected
        }

        assertEquals(
            KayakFirstMeasurementRejection.INVALID_NUMBER,
            rejection(KayakFirstMeasurement.AVERAGE_500M_PACE_INDEX, "12.5").reason,
        )
        assertEquals(
            KayakFirstMeasurementRejection.NUMERIC_OVERFLOW,
            rejection(KayakFirstMeasurement.AVERAGE_1000M_PACE_INDEX, "999999999999999999999").reason,
        )
        assertEquals(
            KayakFirstMeasurementRejection.SPEED_OUT_OF_RANGE,
            rejection(KayakFirstMeasurement.AVERAGE_SPEED_INDEX, "12.00").reason,
        )
        assertEquals(
            KayakFirstMeasurementRejection.CADENCE_OUT_OF_RANGE,
            rejection(KayakFirstMeasurement.AVERAGE_CADENCE_INDEX, "121").reason,
        )
        assertEquals(
            KayakFirstMeasurementRejection.INVALID_NUMBER,
            rejection(KayakFirstMeasurement.AVERAGE_FORCE_INDEX, "force").reason,
        )
    }

    @Test
    fun `continuity rejects rollback and reset establishes a fresh baseline`() {
        val reading = decodedBaseline()
        val tracker = KayakFirstContinuityTracker()

        val first = tracker.accept(reading) as KayakFirstContinuityResult.Accepted
        assertTrue(first.establishedBaseline)
        val next = reading.copy(distanceMetres = 153.0, elapsedSeconds = 70)
        val accepted = tracker.accept(next) as KayakFirstContinuityResult.Accepted
        assertTrue(!accepted.establishedBaseline)

        assertEquals(
            KayakFirstContinuityRejection.DISTANCE_DECREASED,
            (tracker.accept(next.copy(distanceMetres = 10.0)) as KayakFirstContinuityResult.Rejected).reason,
        )
        assertEquals(
            KayakFirstContinuityRejection.ELAPSED_DECREASED,
            (tracker.accept(next.copy(elapsedSeconds = 5)) as KayakFirstContinuityResult.Rejected).reason,
        )

        tracker.reset()
        val rebased = tracker.accept(reading.copy(distanceMetres = 0.0, elapsedSeconds = 0))
            as KayakFirstContinuityResult.Accepted
        assertTrue(rebased.establishedBaseline)
    }

    @Test
    fun `extra fields are counted but status remains uninterpreted`() {
        val baseline = decodedBaseline()
        assertEquals(24, baseline.fieldCount)
        assertEquals("1", baseline.diagnosticStatusRaw)

        val withExtra = KayakFirstMeasurement.decode(
            baselineLine() + ";uninterpreted;999",
        ) as KayakFirstMeasurementDecodeResult.Decoded
        assertEquals(26, withExtra.reading.fieldCount)
        assertEquals("1", withExtra.reading.diagnosticStatusRaw)
    }

    private fun assertDecoded(frame: String, expected: Map<String, Any?>) {
        val reading = (KayakFirstMeasurement.decode(frame.removeSuffix("\r\n"))
            as KayakFirstMeasurementDecodeResult.Decoded).reading
        assertEquals(FixtureJson.double(expected["distance_m"]), reading.distanceMetres, 1e-9)
        assertEquals(FixtureJson.double(expected["speed_mps"]), reading.speedMetresPerSecond, 1e-9)
        assertEquals(FixtureJson.double(expected["speed_kmh"]), reading.speedKph, 1e-9)
        assertEquals(FixtureJson.int(expected["cadence_spm"]), reading.cadenceSpm)
        assertEquals(FixtureJson.int(expected["force_newtons"]).toDouble(), reading.forceNewtons, 0.0)
        assertEquals(FixtureJson.int(expected["elapsed_seconds"]).toLong(), reading.elapsedSeconds)
    }

    private fun rejectionFor(expected: String): KayakFirstMeasurementRejection = when (expected) {
        "reject-wrong-command" -> KayakFirstMeasurementRejection.WRONG_COMMAND
        "reject-empty-field" -> KayakFirstMeasurementRejection.EMPTY_FIELD
        "reject-signed-value" -> KayakFirstMeasurementRejection.SIGNED_VALUE
        "reject-numeric-grammar" -> KayakFirstMeasurementRejection.INVALID_NUMBER
        // A flag outside its domain is the same rejection as bad grammar; the corpus names it
        // separately so the case documents which rule it breaks.
        "reject-flag-domain" -> KayakFirstMeasurementRejection.INVALID_NUMBER
        "reject-numeric-overflow" -> KayakFirstMeasurementRejection.NUMERIC_OVERFLOW
        "reject-plausibility-speed" -> KayakFirstMeasurementRejection.SPEED_OUT_OF_RANGE
        "reject-plausibility-cadence" -> KayakFirstMeasurementRejection.CADENCE_OUT_OF_RANGE
        else -> error("Unhandled expected result $expected")
    }

    private fun decodedBaseline(): KayakFirstMeasurement.Reading =
        (KayakFirstMeasurement.decode(baselineLine()) as KayakFirstMeasurementDecodeResult.Decoded).reading

    private fun baselineLine(): String = FixtureJson.string(
        FixtureJson.obj(load(adversarialResource)["baseline_measurement"])["frame_ascii"],
    ).removeSuffix("\r\n")

    private fun baselineFields(fixture: Map<String, Any?>): List<String> = FixtureJson.string(
        FixtureJson.obj(fixture["baseline_measurement"])["frame_ascii"],
    ).removeSuffix("\r\n").split(';')

    private fun load(resource: String): Map<String, Any?> = javaClass.classLoader
        ?.getResourceAsStream(resource)
        ?.bufferedReader()
        ?.use { FixtureJson.obj(FixtureJson.parse(it.readText())) }
        ?: error("missing $resource")

    private fun strings(value: Any?): List<String> = FixtureJson.array(value).map(FixtureJson::string)

    private fun ints(value: Any?): List<Int> = FixtureJson.array(value).map(FixtureJson::int)
}
