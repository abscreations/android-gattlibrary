package com.gmail.absolutebollockscreations.gattlibrary.central

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.gmail.absolutebollockscreations.gattlibrary.core.ftms.FtmsUuids

/** Pins the redacted, user-submitted physical KICKR application-log and raw-HCI corpora. */
class WahooKickrCaptureCorpusTest {

    private fun load(resource: String = "fixtures/hardware/wahoo_kickr_core_v5_qz_debug.json"): Map<String, Any?> {
        val stream = javaClass.classLoader
            ?.getResourceAsStream(resource)
        assertNotNull("KICKR hardware corpus fixture is missing", stream)
        return FixtureJson.obj(FixtureJson.parse(stream!!.bufferedReader().use { it.readText() }))
    }

    @Test
    fun `physical cycling power vectors replay through the existing standard parser`() {
        val fixture = load()
        for (rawCase in FixtureJson.array(fixture["cycling_power_measurements"])) {
            val case = FixtureJson.obj(rawCase)
            val expected = FixtureJson.obj(case["expect"])
            val reading = CpsMeasurement.parse(hex(FixtureJson.string(case["hex"])))
            assertNotNull(FixtureJson.string(case["session"]), reading)
            reading!!
            assertEquals(FixtureJson.int(expected["flags"]), reading.flags)
            assertEquals(FixtureJson.int(expected["power_watts"]), reading.instantaneousPowerWatts)
            assertEquals(
                FixtureJson.double(expected["accumulated_torque_nm"]),
                reading.accumulatedTorqueNewtonMetres!!,
                1e-9,
            )
            assertEquals(FixtureJson.int(expected["wheel_revolutions"]).toLong(), reading.wheel!!.cumulativeRevolutions)
            assertEquals(FixtureJson.int(expected["wheel_event_ticks"]), reading.wheel.lastEventTimeTicks)
            assertEquals(FixtureJson.int(expected["crank_revolutions"]), reading.crank!!.cumulativeRevolutions)
            assertEquals(FixtureJson.int(expected["crank_event_ticks"]), reading.crank.lastEventTimeTicks)
        }
    }

    @Test
    fun `corpus pins both real models and the proprietary-control evidence boundary`() {
        val fixture = load()
        val sources = FixtureJson.array(fixture["sources"]).map(FixtureJson::obj)
        assertEquals(7, sources.size)
        assertEquals(
            setOf(
                "ed2eaec64ee68b321627664fe24765a5482b3de1cc154059272aa47777df3b9c",
                "09faafec4712d405a62d32bdf019efc5821c5df945caf325e5f52d756d3356bf",
                "783f91e8fe1b160928e50e9dd784da3d1dfbac266bd301c2354de1e6eae6dcbe",
                "944e202c3b130e0928e5f49507f5a12db97f59159a0f94c2d55127019198ddb9",
                "4fc59801bae04fae38c705cd70a82a603556b9eda7962ef679a0a1109fcb5e71",
                "3399817ce74759f85d10d777145258fae961de1a0e37ab059ec4ece394d648c0",
                "8cb52934570eb243560592f59ddc9de49ea37180572a723fd1b72e693b121e62",
            ),
            sources.mapTo(linkedSetOf()) { FixtureJson.string(it["source_sha256"]) },
        )
        assertEquals(setOf("KICKR Core", "KICKR v5"), sources.mapTo(linkedSetOf()) { FixtureJson.string(it["model"]) })
        assertEquals(5, sources.count { it["usable_link_evidence"] == true })
        assertTrue(FixtureJson.string(fixture["capture_kind"]).contains("not raw Bluetooth HCI"))

        val observations = FixtureJson.obj(fixture["observations"])
        assertEquals(
            listOf("00001818-0000-1000-8000-00805f9b34fb"),
            strings(observations["core_advertised_services"]),
        )
        assertTrue(strings(observations["v5_advertised_services"]).contains("00001826-0000-1000-8000-00805f9b34fb"))
        assertTrue(strings(observations["services_discovered_on_both_models"]).contains("a026ee01-0a7d-4ab3-97fa-f1500f9feb8b"))
        assertTrue(strings(observations["services_discovered_on_both_models"]).contains("a026ee0b-0a7d-4ab3-97fa-f1500f9feb8b"))

        val controlPoint = FixtureJson.obj(observations["wahoo_control_point"])
        assertEquals("00001818-0000-1000-8000-00805f9b34fb", FixtureJson.string(controlPoint["observed_parent_service"]))
        assertEquals(0, FixtureJson.int(controlPoint["proprietary_writes_observed"]))
        assertEquals(0, FixtureJson.int(controlPoint["proprietary_indications_observed"]))
        assertFalse(FixtureJson.array(fixture["standard_ftms_control"]).isEmpty())
    }

    @Test
    fun `raw Core HCI capture pins advertising GATT coexistence and the control evidence boundary`() {
        val fixture = load("fixtures/hardware/wahoo_kickr_core_climb_btsnoop.json")
        val sources = FixtureJson.array(fixture["sources"]).map(FixtureJson::obj)
        assertEquals(2, sources.size)
        assertEquals(
            setOf(
                "85ae23a6a9caf96102b0ea273aa50bb960d06228c7dbcfcff18be55fb11e8afc",
                "e3dcc49cb74512016ddc222fbde97d464aacdb0a237bb77214436f863e63d85c",
            ),
            sources.mapTo(linkedSetOf()) { FixtureJson.string(it["source_sha256"]) },
        )
        assertEquals(
            "9c4a1c6baf2901e15bd8a54b0cc638ff43fb27db79eab3b5556f8348f497b45e",
            FixtureJson.string(sources.first()["member_sha256"]),
        )

        val advertising = FixtureJson.obj(fixture["advertising"])
        val core = FixtureJson.obj(advertising["core"])
        val climb = FixtureJson.obj(advertising["climb"])
        assertEquals("02010605031818261810094b49434b5220434f52452043334642", FixtureJson.string(core["payload_hex"]))
        assertTrue(strings(core["service_uuids"]).contains("00001826-0000-1000-8000-00805f9b34fb"))
        assertEquals("02010611094b49434b5220434c494d422033443146", FixtureJson.string(climb["payload_hex"]))
        assertTrue(strings(climb["service_uuids"]).isEmpty())

        val gatt = FixtureJson.obj(fixture["core_gatt"])
        val identity = FixtureJson.obj(gatt["device_identity"])
        assertEquals("Wahoo Fitness", FixtureJson.string(identity["manufacturer"]))
        assertEquals("1.5.36", FixtureJson.string(identity["firmware_revision"]))
        val feature = FixtureJson.obj(gatt["ftms_feature"])
        assertEquals("034000000c600000", FixtureJson.string(feature["value_hex"]))
        assertEquals(16_387, FixtureJson.int(feature["machine_features"]))
        assertEquals(24_588, FixtureJson.int(feature["target_setting_features"]))
        val services = strings(gatt["services"])
        assertTrue(services.contains("00001818-0000-1000-8000-00805f9b34fb"))
        assertTrue(services.contains("00001826-0000-1000-8000-00805f9b34fb"))
        assertTrue(services.contains("a026ee01-0a7d-4ab3-97fa-f1500f9feb8b"))
        assertTrue(services.contains("a026ee0b-0a7d-4ab3-97fa-f1500f9feb8b"))
        assertTrue(services.contains("00000001-19ca-4651-86e5-fa29dcdd09d1"))

        val wahooControl = FixtureJson.obj(gatt["wahoo_control_point"])
        val ftmsControl = FixtureJson.obj(gatt["ftms_control_point"])
        assertEquals(0, FixtureJson.int(wahooControl["value_writes"]))
        assertEquals(0, FixtureJson.int(wahooControl["notifications_or_indications"]))
        assertEquals(0, FixtureJson.int(ftmsControl["value_writes"]))
        assertEquals(0, FixtureJson.int(ftmsControl["notifications_or_indications"]))

        val rideOn = FixtureJson.obj(gatt["rideon_service"])
        assertEquals(142, FixtureJson.int(rideOn["write_commands"]))
        assertEquals(12, FixtureJson.int(rideOn["indications"]))
        assertEquals(FixtureJson.string(rideOn["handshake_request_hex"]), FixtureJson.string(rideOn["handshake_response_hex"]))

        val connections = FixtureJson.obj(fixture["connection_observations"])
        assertFalse(connections["phone_connected_to_climb"] as Boolean)
        assertEquals(373, FixtureJson.int(connections["cps_measurement_notifications"]))
        assertEquals(372, FixtureJson.int(connections["ftms_indoor_bike_notifications"]))
    }

    @Test
    fun `raw Core measurement vectors replay through standard CPS and FTMS parsers`() {
        val fixture = load("fixtures/hardware/wahoo_kickr_core_climb_btsnoop.json")
        val cpsCase = FixtureJson.obj(FixtureJson.array(fixture["cycling_power_measurements"]).single())
        val cpsExpected = FixtureJson.obj(cpsCase["expect"])
        val cps = CpsMeasurement.parse(hex(FixtureJson.string(cpsCase["hex"])))
        assertNotNull(cps)
        assertEquals(FixtureJson.int(cpsExpected["power_watts"]), cps!!.instantaneousPowerWatts)
        assertEquals(FixtureJson.double(cpsExpected["accumulated_torque_nm"]), cps.accumulatedTorqueNewtonMetres!!, 1e-9)
        assertEquals(FixtureJson.int(cpsExpected["wheel_revolutions"]).toLong(), cps.wheel!!.cumulativeRevolutions)
        assertEquals(FixtureJson.int(cpsExpected["crank_revolutions"]), cps.crank!!.cumulativeRevolutions)

        for (rawCase in FixtureJson.array(fixture["ftms_indoor_bike_measurements"])) {
            val case = FixtureJson.obj(rawCase)
            val expected = FixtureJson.obj(case["expect"])
            val fusion = MetricFusion(BtEngineState()).also { it.setPersona(FusionPersona.RIDE) }
            val emitter = MetricEmitter(
                fusion = fusion,
                sourceId = MetricSourceId(
                    ConnectionId("kickr-core-raw", 1L),
                    MetricProtocol.FTMS,
                    FtmsUuids.UUID_BIKING_DATA,
                ),
                sourceClass = MetricSourceClass.FTMS,
                arrivalMs = 1_000L,
            )
            BtEngineFtmsParsers.doBike(emitter, hex(FixtureJson.string(case["hex"])))
            assertEquals(FixtureJson.double(expected["speed_kph"]), fusion.latestSample(MetricKind.SPEED)!!.value, 1e-9)
            assertEquals(FixtureJson.double(expected["cadence_rpm"]), fusion.latestSample(MetricKind.CADENCE)!!.value, 1e-9)
            assertEquals(FixtureJson.double(expected["power_watts"]), fusion.latestSample(MetricKind.POWER)!!.value, 1e-9)
        }
    }

    private fun strings(value: Any?): List<String> = FixtureJson.array(value).map(FixtureJson::string)

    private fun hex(value: String): ByteArray {
        assertTrue("hex input must contain complete bytes", value.length % 2 == 0)
        return value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }
}
