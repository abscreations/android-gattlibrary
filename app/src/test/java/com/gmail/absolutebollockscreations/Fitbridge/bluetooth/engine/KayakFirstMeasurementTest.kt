package com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KayakFirstMeasurementTest {
    private val adversarialResource = "fixtures/simulated/kayak_first_adversarial_v1.json"
    private val hardwareResource = "fixtures/hardware/kayak_first_qz_202604.json"

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
                if (case["expect_speed_kmh"] != null) {
                    val reading = (result as KayakFirstMeasurementDecodeResult.Decoded).reading
                    assertEquals(FixtureJson.double(case["expect_speed_kmh"]), reading.speedKph, 1e-9)
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
        assertEquals(
            3000.0,
            requireNotNull(PlausibilityBounds.rangeFor(MetricKind.POWER, FusionPersona.ROW_PADDLE)).max,
            0.0,
        )
        assertEquals(40.0, KayakFirstMeasurement.MAX_SPEED_KPH, 0.0)
        assertEquals(120, KayakFirstMeasurement.MAX_CADENCE_SPM)
        assertEquals(3000, KayakFirstMeasurement.MAX_POWER_WATTS)
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
        assertEquals(FixtureJson.int(expected["power_watts"]), reading.powerWatts)
        assertEquals(FixtureJson.int(expected["elapsed_seconds"]).toLong(), reading.elapsedSeconds)
    }

    private fun rejectionFor(expected: String): KayakFirstMeasurementRejection = when (expected) {
        "reject-wrong-command" -> KayakFirstMeasurementRejection.WRONG_COMMAND
        "reject-empty-field" -> KayakFirstMeasurementRejection.EMPTY_FIELD
        "reject-signed-value" -> KayakFirstMeasurementRejection.SIGNED_VALUE
        "reject-numeric-grammar" -> KayakFirstMeasurementRejection.INVALID_NUMBER
        "reject-numeric-overflow" -> KayakFirstMeasurementRejection.NUMERIC_OVERFLOW
        "reject-plausibility-speed" -> KayakFirstMeasurementRejection.SPEED_OUT_OF_RANGE
        "reject-plausibility-cadence" -> KayakFirstMeasurementRejection.CADENCE_OUT_OF_RANGE
        "reject-plausibility-power" -> KayakFirstMeasurementRejection.POWER_OUT_OF_RANGE
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
