package com.gmail.absolutebollockscreations.gattlibrary.conformance

import com.gmail.absolutebollockscreations.gattlibrary.central.FixtureJson
import com.gmail.absolutebollockscreations.gattlibrary.central.KeiserMSeriesMeasurement
import com.gmail.absolutebollockscreations.gattlibrary.simulator.FtmsMockSample
import com.gmail.absolutebollockscreations.gattlibrary.simulator.KeiserMSeriesSimulatorEncoder
import com.gmail.absolutebollockscreations.gattlibrary.simulator.KeiserMSeriesSimulatorProfile
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * L25 golden check for Keiser M Series against `gymnasticon/keiser_m.json` (vendor-documented
 * example plus Gymnasticon version cases; evidence tier is not a hardware capture). Personas use
 * system IDs 1-3, the vendor example uses 56, so that one octet is compared separately.
 */
class KeiserGoldenTest {
    private val fixture = Fixtures.json("gymnasticon/keiser_m.json")

    /** The fixture's documented ride, in the simulator's own input type. */
    private fun vendorSample(expect: Map<String, Any?>) = FtmsMockSample(
        elapsedSeconds = FixtureJson.int(expect["elapsed_seconds"]),
        heartRate = FixtureJson.double(expect["heart_rate_bpm"]).toInt(),
        caloriesTotal = FixtureJson.int(expect["energy_kcal"]),
        caloriesPerHour = 0,
        caloriesPerMinute = 0,
        cadence = FixtureJson.double(expect["cadence_rpm"]),
        resistance = FixtureJson.int(expect["resistance"]),
        powerWatts = FixtureJson.int(expect["power_watts"]),
    )

    private fun payloadOf(packet: ByteArray) = packet.copyOfRange(2, packet.size)

    private fun sameExceptSystemId(golden: ByteArray, simulated: ByteArray, label: String) {
        assertEquals("length of $label", payloadOf(golden).size, simulated.size)
        val goldenPayload = payloadOf(golden).also { it[3] = 0 }
        val simulatedPayload = simulated.copyOf().also { it[3] = 0 }
        assertArrayEquals("bytes of $label", goldenPayload, simulatedPayload)
    }

    @Test
    fun vendorExampleIsReproducedOctetForOctet() {
        val case = Fixtures.objects(fixture["official_cases"]).single()
        val expect = FixtureJson.obj(case["expect"])
        val golden = Fixtures.bytes(case["packet"])
        val profile = KeiserMSeriesSimulatorProfile.M3I_IMPERIAL

        val payload = KeiserMSeriesSimulatorEncoder.encode(
            vendorSample(expect), profile, distanceRawTenths = FixtureJson.int(expect["distance_raw_tenths"]),
        )

        sameExceptSystemId(golden, payload, "vendor example")
        assertEquals(profile.systemId, payload[3].toInt() and 0xff)
    }

    @Test
    fun centralReadsSimulatorOutputLikeTheGoldenPacket() {
        val case = Fixtures.objects(fixture["official_cases"]).single()
        val expect = FixtureJson.obj(case["expect"])
        val golden = requireNotNull(KeiserMSeriesMeasurement.parse(Fixtures.bytes(case["packet"])))
        val simulated = requireNotNull(
            KeiserMSeriesMeasurement.parse(
                KeiserMSeriesSimulatorEncoder.canonicalField(
                    KeiserMSeriesSimulatorEncoder.encode(
                        vendorSample(expect), KeiserMSeriesSimulatorProfile.M3I_IMPERIAL,
                        distanceRawTenths = FixtureJson.int(expect["distance_raw_tenths"]),
                    ),
                ),
            ),
        )

        assertEquals(golden.firmware, simulated.firmware)
        assertEquals(golden.isRealtime, simulated.isRealtime)
        assertEquals(golden.cadenceRpm, simulated.cadenceRpm, 0.0)
        assertEquals(golden.heartRateBpm, simulated.heartRateBpm, 0.0)
        assertEquals(golden.powerWatts, simulated.powerWatts)
        assertEquals(golden.energyKcal, simulated.energyKcal)
        assertEquals(golden.elapsedSeconds, simulated.elapsedSeconds)
        assertEquals(golden.distanceUnit, simulated.distanceUnit)
        assertEquals(golden.distanceRawTenths, simulated.distanceRawTenths)
        assertEquals(golden.distanceMetres, simulated.distanceMetres, 0.0)
        assertEquals(golden.resistance, simulated.resistance)
    }

    @Test
    fun versionBytesOfTheFastAndSlowPersonasMatchTheVersionCases() {
        val sample = vendorSample(FixtureJson.obj(Fixtures.objects(fixture["official_cases"]).single()["expect"]))
        val cases = Fixtures.objects(fixture["version_cases"]).associateBy { FixtureJson.string(FixtureJson.obj(it["expect"])["firmware"]) }
        listOf(
            "6.30" to KeiserMSeriesSimulatorProfile.M3I_IMPERIAL,
            "6.22" to KeiserMSeriesSimulatorProfile.M3O_OLD_FIRMWARE,
        ).forEach { (firmware, profile) ->
            val golden = Fixtures.bytes(requireNotNull(cases[firmware]) { "no $firmware case" }["packet"])
            val payload = KeiserMSeriesSimulatorEncoder.encode(sample, profile, distanceRawTenths = 1)
            sameExceptSystemId(golden, payload, "firmware $firmware")
            assertNotNull(KeiserMSeriesMeasurement.parse(KeiserMSeriesSimulatorEncoder.canonicalField(payload)))
        }
    }
}
