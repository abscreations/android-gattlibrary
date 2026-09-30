package com.gmail.absolutebollockscreations.gattlibrary.central

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.gmail.absolutebollockscreations.gattlibrary.core.ftms.FtmsUuids

/** Replays FTMS, CSC, and CPS frames observed together on simulated link 8f8c-c6. */
class MultiServiceSimulatedEvidenceTest {

    private fun load(): Map<String, Any?> {
        val stream = javaClass.classLoader
            ?.getResourceAsStream("fixtures/simulated/ftms_csc_cps_tmiw_bike_8f8c_c6.json")
        assertNotNull("multi-service simulator evidence fixture is missing", stream)
        return FixtureJson.obj(FixtureJson.parse(stream!!.bufferedReader().use { it.readText() }))
    }

    @Test
    fun `representative frames reproduce all three handler measurements`() {
        val frames = FixtureJson.obj(load()["frames"])

        val ftms = FixtureJson.obj(frames["ftmsIndoorBike"])
        val ftmsExpected = FixtureJson.obj(ftms["expect"])
        val state = BtEngineState()
        val fusion = MetricFusion(state)
        BtEngineFtmsParsers.doBike(
            MetricEmitter(
                fusion = fusion,
                sourceId = MetricSourceId(
                    ConnectionId("capture-c6", 5L),
                    MetricProtocol.FTMS,
                    FtmsUuids.UUID_BIKING_DATA,
                ),
                sourceClass = MetricSourceClass.FTMS,
                arrivalMs = 1L,
            ),
            hexBytes(FixtureJson.string(ftms["hex"])),
        )
        assertEquals(
            FixtureJson.double(ftmsExpected["speedKph"]),
            requireNotNull(fusion.latestSample(MetricKind.SPEED)).value,
            0.0,
        )
        assertEquals(
            FixtureJson.double(ftmsExpected["cadenceRpm"]),
            requireNotNull(fusion.latestSample(MetricKind.CADENCE)).value,
            0.0,
        )
        assertEquals(
            FixtureJson.double(ftmsExpected["powerWatts"]),
            requireNotNull(fusion.latestSample(MetricKind.POWER)).value,
            0.0,
        )

        val cscTracker = CscMeasurementTracker()
        cscTracker.accept(hexBytes(FixtureJson.string(FixtureJson.obj(frames["cscBaseline"])["hex"])))
        val csc = FixtureJson.obj(frames["cscUpdate"])
        val cscExpected = FixtureJson.obj(csc["expect"])
        val cscUpdate = requireNotNull(cscTracker.accept(hexBytes(FixtureJson.string(csc["hex"]))))
        assertEquals(FixtureJson.int(cscExpected["wheelDeltaRevolutions"]).toLong(), cscUpdate.wheelDelta?.revolutions)
        assertEquals(FixtureJson.int(cscExpected["wheelDeltaTicks"]), cscUpdate.wheelDelta?.eventTimeTicks)
        assertEquals(FixtureJson.int(cscExpected["crankDeltaRevolutions"]), cscUpdate.crankDelta?.revolutions)
        assertEquals(FixtureJson.int(cscExpected["crankDeltaTicks"]), cscUpdate.crankDelta?.eventTimeTicks)
        val cscMetrics = CscDerivationState().derive(
            cscUpdate,
            CscWorkoutPersona.BIKE,
            BtEngineState.CscRole.entries.toSet(),
            BtEngineState.CscRole.entries.toSet(),
            CscCalibration(wheelCircumferenceMm = 2405),
        )
        assertEquals(FixtureJson.double(cscExpected["speedKph"]), cscMetrics.metric(MetricKind.SPEED).value, 1e-9)
        assertEquals(FixtureJson.double(cscExpected["cadenceRpm"]), cscMetrics.metric(MetricKind.CADENCE).value, 1e-9)

        val cpsTracker = CpsMeasurementTracker()
        val cpsBaseline = FixtureJson.obj(frames["cpsBaseline"])
        assertEquals(
            FixtureJson.int(FixtureJson.obj(cpsBaseline["expect"])["powerWatts"]),
            cpsTracker.accept(hexBytes(FixtureJson.string(cpsBaseline["hex"])))?.reading?.instantaneousPowerWatts,
        )
        val cps = FixtureJson.obj(frames["cpsUpdate"])
        val cpsExpected = FixtureJson.obj(cps["expect"])
        val cpsUpdate = requireNotNull(cpsTracker.accept(hexBytes(FixtureJson.string(cps["hex"]))))
        val cpsDelta = requireNotNull(cpsUpdate.crankDelta)
        assertEquals(FixtureJson.int(cpsExpected["powerWatts"]), cpsUpdate.reading.instantaneousPowerWatts)
        assertEquals(FixtureJson.int(cpsExpected["crankDeltaRevolutions"]), cpsDelta.revolutions)
        assertEquals(FixtureJson.int(cpsExpected["crankDeltaTicks"]), cpsDelta.eventTimeTicks)
        assertEquals(
            FixtureJson.double(cpsExpected["cadenceRpm"]),
            requireNotNull(CpsDerivationState.crankCadenceRpm(cpsDelta)),
            1e-9,
        )
    }

    @Test
    fun `fixture pins one-link three-handler topology and simulator limits`() {
        val fixture = load()
        assertEquals("S", FixtureJson.string(fixture["tier"]))
        assertEquals("c6", FixtureJson.string(fixture["capture_connection"]))
        assertEquals(5, FixtureJson.int(fixture["link_generation"]))
        assertEquals(listOf("FTMS", "CSC", "CPS"), FixtureJson.array(fixture["activation_order"])
            .map(FixtureJson::string))
        assertEquals(setOf("FTMS", "CSC", "CPS"), FixtureJson.array(fixture["active_handlers"])
            .map(FixtureJson::string).toSet())
        assertEquals(setOf("1826", "1816", "1818"), FixtureJson.array(fixture["advertised_services"])
            .map(FixtureJson::string).toSet())
        assertEquals(81987, FixtureJson.int(fixture["triple_stream_overlap_ms"]))
        assertEquals(
            "d5d960d607351e6bbe480153b65688be63d8b1a2e8b46357022eb7295ec1e219",
            FixtureJson.string(fixture["archive_sha256"]),
        )
        assertEquals(
            "54137e0559a2493e2bd498dd380b97fea7856b76df1cd926e0a3572d8bbb6cb2",
            FixtureJson.string(fixture["source_sha256"]),
        )
        assertEquals(null, fixture["simulator_revision"])
        assertTrue(FixtureJson.string(fixture["note"]).contains("No separate CPS link"))
    }

    private fun hexBytes(hex: String): ByteArray {
        assertTrue("hex input must contain complete bytes", hex.length % 2 == 0)
        return hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }

    private fun List<CscDerivedMetric>.metric(kind: MetricKind): CscDerivedMetric =
        single { it.metric == kind }
}
