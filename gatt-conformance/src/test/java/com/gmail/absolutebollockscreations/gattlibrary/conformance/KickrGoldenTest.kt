package com.gmail.absolutebollockscreations.gattlibrary.conformance

import com.gmail.absolutebollockscreations.gattlibrary.central.FixtureJson
import com.gmail.absolutebollockscreations.gattlibrary.core.vendor.RideOnServiceForm
import com.gmail.absolutebollockscreations.gattlibrary.simulator.KickrControlMode
import com.gmail.absolutebollockscreations.gattlibrary.simulator.KickrGattProperty
import com.gmail.absolutebollockscreations.gattlibrary.simulator.KickrMachineSnapshot
import com.gmail.absolutebollockscreations.gattlibrary.simulator.KickrPersonaManifest
import com.gmail.absolutebollockscreations.gattlibrary.simulator.KickrSimulationTarget
import com.gmail.absolutebollockscreations.gattlibrary.simulator.KickrSimulatorPersona
import com.gmail.absolutebollockscreations.gattlibrary.simulator.KickrStandardEncoders
import com.gmail.absolutebollockscreations.gattlibrary.simulator.KickrSyntheticModel
import com.gmail.absolutebollockscreations.gattlibrary.simulator.ZwiftTrainerPeripheralServer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * L25 golden check for the original KICKR CORE persona against
 * `hardware/wahoo_kickr_core_climb_btsnoop.json`, a sanitized Android HCI snoop of a physical KICKR
 * CORE (firmware 1.5.36) in a Zwift session.
 *
 * Covered: the exact legacy advertisement, Device Information strings, FTMS Feature value, the
 * GATT service surface, the RideOn service characteristics and handshake, and the captured CPS and
 * FTMS Indoor Bike notifications, each rebuilt by the encoders from the fixture's independently
 * decoded fields.
 *
 * Not covered, because the capture does not contain it (its own `evidence_limit`): any control
 * request on either control point, so the proprietary opcodes, control-route precedence and every
 * individual RideOn frame after the handshake stay unverified by capture.
 */
class KickrGoldenTest {
    private val fixture = Fixtures.json("hardware/wahoo_kickr_core_climb_btsnoop.json")
    private val core = KickrPersonaManifest.forPersona(KickrSimulatorPersona.CORE_ORIGINAL_ZWIFT)

    private fun section(name: String): Map<String, Any?> = FixtureJson.obj(fixture[name])

    private fun snapshot(
        power: Int = 0,
        speedKph: Double = 0.0,
        cadenceRpm: Double = 0.0,
        torqueRaw: Int = 0,
        wheelRevs: Long = 0,
        wheelTicks: Int = 0,
        crankRevs: Int = 0,
        crankTicks: Int = 0,
    ) = KickrMachineSnapshot(
        elapsedSeconds = 0.0, running = true, mode = KickrControlMode.AUTONOMOUS,
        speedKph = speedKph, cadenceRpm = cadenceRpm, powerWatts = power, heartRateBpm = 0,
        distanceMetres = 0.0, energyKcal = 0.0, resistanceLevel = 0.0, targetPowerWatts = null,
        simulation = KickrSimulationTarget(), wheelCircumferenceMm = 2105.0,
        cumulativeWheelRevolutions = wheelRevs, lastWheelEventTicks = wheelTicks,
        cumulativeCrankRevolutions = crankRevs, lastCrankEventTicks = crankTicks,
        accumulatedTorqueRaw = torqueRaw, virtualGearIndex = 0, gearRatio = 1.0, controlLabel = "",
    )

    @Test
    fun `the captured cycling power notification is reproduced octet for octet`() {
        for (entry in Fixtures.objects(fixture["cycling_power_measurements"])) {
            val expect = FixtureJson.obj(entry["expect"])
            // Accumulated torque is in 1/32 Nm on the wire.
            val torqueRaw = Math.round(FixtureJson.double(expect["accumulated_torque_nm"]) * 32.0).toInt()
            val built = KickrStandardEncoders.cyclingPowerMeasurement(
                snapshot(
                    power = FixtureJson.int(expect["power_watts"]),
                    torqueRaw = torqueRaw,
                    wheelRevs = FixtureJson.int(expect["wheel_revolutions"]).toLong(),
                    wheelTicks = FixtureJson.int(expect["wheel_event_ticks"]),
                    crankRevs = FixtureJson.int(expect["crank_revolutions"]),
                    crankTicks = FixtureJson.int(expect["crank_event_ticks"]),
                ),
            )
            assertArrayEquals("CPS ${entry["hex"]}", Fixtures.hex(entry["hex"] as String), built)
            assertEquals(FixtureJson.int(expect["flags"]), KickrStandardEncoders.CPS_FLAGS_CAPTURE_SHAPE)
        }
    }

    @Test
    fun `the captured indoor bike notifications are reproduced octet for octet`() {
        val frames = Fixtures.objects(fixture["ftms_indoor_bike_measurements"])
        assertTrue(frames.isNotEmpty())
        for (entry in frames) {
            val expect = FixtureJson.obj(entry["expect"])
            val built = KickrStandardEncoders.indoorBikeData(
                snapshot(
                    power = FixtureJson.int(expect["power_watts"]),
                    speedKph = FixtureJson.double(expect["speed_kph"]),
                    cadenceRpm = FixtureJson.double(expect["cadence_rpm"]),
                ),
            )
            assertArrayEquals("FTMS ${entry["hex"]}", Fixtures.hex(entry["hex"] as String), built)
            assertEquals(FixtureJson.int(expect["flags"]), KickrStandardEncoders.FTMS_FLAGS_CAPTURE_SHAPE)
        }
    }

    @Test
    fun `the advertisement, identity and FTMS feature are the captured ones`() {
        val advertising = FixtureJson.obj(FixtureJson.obj(section("advertising")["core"]))
        assertEquals(advertising["name"], core.advertising.localName)
        assertArrayEquals(Fixtures.hex(advertising["payload_hex"] as String), core.advertising.exactLegacyPayload)
        assertEquals(
            FixtureJson.array(advertising["service_uuids"]).map { it.toString() },
            core.advertising.advertisedServices.map { it.toString() },
        )

        val identity = FixtureJson.obj(section("core_gatt")["device_identity"])
        assertEquals(identity["manufacturer"], core.identity.manufacturer)
        assertEquals(identity["hardware_revision"], core.identity.hardwareRevision)
        assertEquals(identity["firmware_revision"], core.identity.firmwareRevision)

        val feature = FixtureJson.obj(section("core_gatt")["ftms_feature"])
        assertArrayEquals(Fixtures.hex(feature["value_hex"] as String), KickrStandardEncoders.CORE_FTMS_FEATURE)
    }

    @Test
    fun `every captured service except GAP and GATT is served, and the RideOn characteristics match`() {
        val gatt = section("core_gatt")
        val captured = FixtureJson.array(gatt["services"]).map { it.toString() }.toSet() -
            setOf("00001800-0000-1000-8000-00805f9b34fb", "00001801-0000-1000-8000-00805f9b34fb")
        val served = core.services.map { it.uuid.toString() }.toSet()
        assertTrue("captured services missing from the persona: ${captured - served}", served.containsAll(captured))

        val rideOn = FixtureJson.obj(gatt["rideon_service"])
        val service = core.services.single { it.uuid.toString() == rideOn["uuid"] }
        val byUuid = service.characteristics.associate { it.uuid.toString() to it.properties }
        assertEquals(
            mapOf(
                rideOn["notify_characteristic"] as String to setOf(KickrGattProperty.NOTIFY),
                rideOn["write_command_characteristic"] as String to setOf(KickrGattProperty.WRITE_WITHOUT_RESPONSE),
                rideOn["indicate_characteristic"] as String to setOf(KickrGattProperty.INDICATE),
            ),
            byUuid,
        )
        assertEquals(RideOnServiceForm.ORIGINAL, core.zwiftServiceForm)
    }

    @Test
    fun `the captured RideOn handshake is accepted and answered as captured`() {
        val rideOn = FixtureJson.obj(section("core_gatt")["rideon_service"])
        val request = Fixtures.hex(rideOn["handshake_request_hex"] as String)
        val response = Fixtures.hex(rideOn["handshake_response_hex"] as String)

        val server = ZwiftTrainerPeripheralServer(RideOnServiceForm.ORIGINAL, KickrSyntheticModel())
        val outcome = server.handle(request, true)
        assertTrue(outcome.accepted)
        assertArrayEquals("first frame is the synchronous handshake reply", response, outcome.frames.first().value)
        assertFalse(outcome.frames.first().async)
    }
}
