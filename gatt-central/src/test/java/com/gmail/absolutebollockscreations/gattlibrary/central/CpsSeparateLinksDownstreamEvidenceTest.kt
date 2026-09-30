package com.gmail.absolutebollockscreations.gattlibrary.central

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.gmail.absolutebollockscreations.gattlibrary.core.ftms.FtmsUuids

/** Replays separate-link CPS selection and downstream elliptical output from session 8f8c. */
class CpsSeparateLinksDownstreamEvidenceTest {

    private fun load(): Map<String, Any?> {
        val stream = javaClass.classLoader
            ?.getResourceAsStream("fixtures/simulated/cps_separate_links_downstream_8f8c.json")
        assertNotNull("separate-link CPS evidence fixture is missing", stream)
        return FixtureJson.obj(FixtureJson.parse(stream!!.bufferedReader().use { it.readText() }))
    }

    @Test
    fun `downstream elliptical update contains separate CPS power and CSC stride count`() {
        val elliptical = FixtureJson.obj(load()["elliptical"])
        val sources = FixtureJson.obj(elliptical["source_snapshot"])
        val cpsSource = FixtureJson.obj(sources["cps"])
        val ftmsSource = FixtureJson.obj(sources["ftms"])
        assertEquals(
            FixtureJson.int(cpsSource["powerWatts"]),
            CpsMeasurement.parse(hexBytes(FixtureJson.string(cpsSource["hex"])))?.instantaneousPowerWatts,
        )

        val upstreamState = BtEngineState()
        val upstreamFusion = MetricFusion(upstreamState)
        BtEngineFtmsParsers.doBike(
            emitter(upstreamFusion, FtmsUuids.UUID_BIKING_DATA, 1L),
            hexBytes(FixtureJson.string(ftmsSource["hex"])),
        )
        assertEquals(
            FixtureJson.double(ftmsSource["powerWatts"]),
            requireNotNull(upstreamFusion.latestSample(MetricKind.POWER)).value,
            0.0,
        )

        val downstream = FixtureJson.obj(elliptical["downstream_update"])
        val expected = FixtureJson.obj(downstream["expect"])
        val outputState = BtEngineState()
        val outputFusion = MetricFusion(outputState)
        val outputEmitter = emitter(outputFusion, FtmsUuids.UUID_ELLIPTICAL_DATA, 2L)
        BtEngineFtmsParsers.doElliptical(
            outputEmitter,
            hexBytes(FixtureJson.string(FixtureJson.obj(downstream["core"])["hex"])),
        )
        BtEngineFtmsParsers.doElliptical(
            outputEmitter,
            hexBytes(FixtureJson.string(FixtureJson.obj(downstream["extension"])["hex"])),
        )

        assertMetric(outputFusion, expected, "speedKph", MetricKind.SPEED)
        assertMetric(outputFusion, expected, "averageSpeedKph", MetricKind.AVERAGE_SPEED)
        assertMetric(outputFusion, expected, "distanceMetres", MetricKind.DISTANCE)
        assertMetric(outputFusion, expected, "cadenceRpm", MetricKind.CADENCE)
        assertMetric(outputFusion, expected, "averageCadenceRpm", MetricKind.AVERAGE_CADENCE)
        assertMetric(outputFusion, expected, "totalStrides", MetricKind.TOTAL_STRIDES)
        assertMetric(outputFusion, expected, "resistance", MetricKind.RESISTANCE)
        assertMetric(outputFusion, expected, "elapsedSeconds", MetricKind.ELAPSED)
        assertMetric(outputFusion, expected, "powerWatts", MetricKind.POWER)
        assertMetric(outputFusion, expected, "averagePowerWatts", MetricKind.AVERAGE_POWER)
        assertMetric(outputFusion, expected, "energyKilocalories", MetricKind.ENERGY)

        val cscSource = FixtureJson.obj(sources["csc"])
        assertEquals(
            FixtureJson.double(cscSource["totalStrides"]),
            requireNotNull(outputFusion.latestSample(MetricKind.TOTAL_STRIDES)).value,
            0.0,
        )
        assertEquals(
            FixtureJson.double(cpsSource["powerWatts"]),
            requireNotNull(outputFusion.latestSample(MetricKind.POWER)).value,
            0.0,
        )
        assertTrue(
            "downstream power must differ from simultaneous FTMS power",
            FixtureJson.double(cpsSource["powerWatts"]) != FixtureJson.double(ftmsSource["powerWatts"]),
        )
    }

    @Test
    fun `fixture pins two independent links recovery delivery and evidence limits`() {
        val fixture = load()
        val bike = FixtureJson.obj(fixture["bike"])
        val elliptical = FixtureJson.obj(fixture["elliptical"])
        val bikeMachine = FixtureJson.obj(bike["machine"])
        val bikeSensor = FixtureJson.obj(bike["sensor"])
        val ellipticalMachine = FixtureJson.obj(elliptical["machine"])
        val ellipticalSensor = FixtureJson.obj(elliptical["sensor"])
        val downstream = FixtureJson.obj(elliptical["downstream"])

        assertEquals("S", FixtureJson.string(fixture["tier"]))
        assertTrue(bikeMachine["connection"] != bikeSensor["connection"])
        assertTrue(ellipticalMachine["connection"] != ellipticalSensor["connection"])
        assertEquals(listOf("FTMS"), FixtureJson.array(bikeMachine["handlers"]).map(FixtureJson::string))
        assertEquals(setOf("CSC", "CPS"), FixtureJson.array(bikeSensor["handlers"])
            .map(FixtureJson::string).toSet())
        assertEquals(12, FixtureJson.int(ellipticalSensor["failedGeneration"]))
        assertEquals(13, FixtureJson.int(ellipticalSensor["generation"]))
        assertEquals("ELLIPTICAL", FixtureJson.string(downstream["profile"]))
        assertEquals(90, FixtureJson.int(downstream["crossTrainerNotifications"]))
        assertEquals(36, FixtureJson.int(downstream["diagnosticDeliveredRecords"]))
        assertEquals(
            "1bb295a06a6c8a482b94d7455d68eb6d19cf25068df1c4d65d8698229cf88f87",
            FixtureJson.string(fixture["archive_sha256"]),
        )
        assertTrue(FixtureJson.string(fixture["note"]).contains("did not subscribe"))
    }

    private fun emitter(fusion: MetricFusion, characteristic: java.util.UUID, arrivalMs: Long) =
        MetricEmitter(
            fusion = fusion,
            sourceId = MetricSourceId(
                ConnectionId("capture-evidence", 1L),
                MetricProtocol.FTMS,
                characteristic,
            ),
            sourceClass = MetricSourceClass.FTMS,
            arrivalMs = arrivalMs,
        )

    private fun assertMetric(
        fusion: MetricFusion,
        expected: Map<String, Any?>,
        key: String,
        kind: MetricKind,
    ) {
        assertEquals(
            FixtureJson.double(expected[key]),
            requireNotNull(fusion.latestSample(kind)).value,
            1e-9,
        )
    }

    private fun hexBytes(hex: String): ByteArray {
        assertTrue("hex input must contain complete bytes", hex.length % 2 == 0)
        return hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }
}
