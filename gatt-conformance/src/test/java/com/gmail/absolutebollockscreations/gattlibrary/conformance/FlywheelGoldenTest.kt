package com.gmail.absolutebollockscreations.gattlibrary.conformance

import com.gmail.absolutebollockscreations.gattlibrary.central.FixtureJson
import com.gmail.absolutebollockscreations.gattlibrary.central.FlywheelDecodeResult
import com.gmail.absolutebollockscreations.gattlibrary.central.FlywheelMeasurement
import com.gmail.absolutebollockscreations.gattlibrary.simulator.FlywheelSportsEncoder
import com.gmail.absolutebollockscreations.gattlibrary.simulator.FtmsMockSample
import com.gmail.absolutebollockscreations.gattlibrary.simulator.SimulatorPersona
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * L25 golden check for Flywheel against `gymnasticon/flywheel.json`. Evidence tier of that file is a
 * single permissive-source implementation (Gymnasticon v1.4.1) plus authored boundary cases, not
 * a hardware capture; the tests say what they are comparing to.
 */
class FlywheelGoldenTest {
    private val fixture = Fixtures.json("gymnasticon/flywheel.json")

    private fun sample(power: Int, cadence: Int) = FtmsMockSample(
        elapsedSeconds = 1, heartRate = 100, caloriesTotal = 1, caloriesPerHour = 1, caloriesPerMinute = 1,
        cadence = cadence.toDouble(), powerWatts = power, averagePowerWatts = power,
    )

    @Test
    fun sourceVectorIsReproducedOctetForOctet() {
        val case = Fixtures.objects(fixture["normative_cases"]).single()
        val expect = FixtureJson.obj(case["expect"])
        val golden = Fixtures.hex(FixtureJson.string(case["packet_hex"]))

        val encoded = FlywheelSportsEncoder.encode(
            sample(FixtureJson.int(expect["power_watts"]), FixtureJson.int(expect["cadence_rpm"])),
        )

        assertArrayEquals("all 34 octets of the source vector", golden, encoded)
    }

    @Test
    fun everyStructurallyValidCaseAgreesOnTheDecodedPrefix() {
        val cases = (Fixtures.objects(fixture["normative_cases"]) + Fixtures.objects(fixture["boundary_cases"]))
            .filter { FixtureJson.obj(it["expect"])["structurally_valid"] == true }
        assertTrue("fixture lists valid cases", cases.size >= 5)
        cases.forEach { case ->
            val id = FixtureJson.string(case["id"])
            val expect = FixtureJson.obj(case["expect"])
            val golden = if (case.containsKey("packet_hex")) Fixtures.hex(FixtureJson.string(case["packet_hex"])) else Fixtures.bytes(case["packet"])
            val encoded = FlywheelSportsEncoder.encode(
                sample(FixtureJson.int(expect["power_watts"]), FixtureJson.int(expect["cadence_rpm"])),
            )
            // Bytes 0..12 are the validated prefix; 13.. are declared unknown and not compared.
            assertArrayEquals("prefix of $id", golden.copyOfRange(0, 13), encoded.copyOfRange(0, 13))
            val reading = (FlywheelMeasurement.decode(encoded) as FlywheelDecodeResult.Decoded).reading
            assertEquals("power of $id", FixtureJson.int(expect["power_watts"]), reading.powerWatts)
            assertEquals("cadence of $id", FixtureJson.int(expect["cadence_rpm"]), reading.cadenceRpm)
        }
    }

    @Test
    fun advertisedNameMatchesTheGattContract() {
        val contract = FixtureJson.obj(fixture["gatt_contract"])
        assertEquals(FixtureJson.string(contract["local_name"]), SimulatorPersona.FlywheelSports.advertisedName)
    }
}
