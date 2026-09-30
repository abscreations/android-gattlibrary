package com.gmail.absolutebollockscreations.gattlibrary.conformance

import com.gmail.absolutebollockscreations.gattlibrary.central.FixtureJson
import com.gmail.absolutebollockscreations.gattlibrary.server.CyclingRevolutionSnapshot
import com.gmail.absolutebollockscreations.gattlibrary.server.CyclingSensorEncoders
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * L25 check for the CSC and CPS encoders behind the `IndoorBikeSpeedAndPower` persona.
 *
 * Two evidence tiers, and they prove different things:
 *
 * - **Hardware** (`hardware/csc_physical_sensors_20260810.json`, tier H): real wheel-only (flags
 *   0x01) and crank-only (0x02) sensor frames. The persona emits one *combined* frame (flags 0x03),
 *   which no physical sensor in the corpus sends, so a byte-for-byte comparison is impossible.
 *   What the hardware does pin is the field layout of each half: wheel revolutions u32 at offset 1
 *   and wheel event time u16 at 5; crank revolutions u16 and event time u16 directly after.
 *   The test feeds each captured wheel-only frame's values to the encoder and requires the same
 *   six octets where the combined frame keeps its wheel half, and likewise for the crank half.
 * - **Simulator** (`simulated/csc_tmiw_bike_be87.json`, `simulated/cps_tmiw_bike_8f8c.json`, tier S):
 *   captured from this project's own simulator as seen by a central. They cannot show the PM-style
 *   "does real hardware do this" question, only that the encoders have not drifted from the frames
 *   already accepted as the persona's wire behaviour (regression guard, not independent evidence).
 *
 * No real combined CSC sensor and no real bike CPS capture exists in the corpus; both stay open.
 */
class CyclingSensorGoldenTest {
    private fun hexOf(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    @Test
    fun `hardware wheel-only frames put wheel revolutions and time where the encoder puts them`() {
        var checked = 0
        for (session in Fixtures.objects(Fixtures.json("hardware/csc_physical_sensors_20260810.json")["sessions"])) {
            for (frame in Fixtures.objects(session["frames"])) {
                val captured = Fixtures.hex(frame["hex"] as String)
                if (captured[0].toInt() != 0x01) continue
                assertEquals("wheel-only frame is 7 octets", 7, captured.size)
                val e = FixtureJson.obj(frame["expect"])
                val encoded = CyclingSensorEncoders.cscMeasurement(
                    CyclingRevolutionSnapshot(
                        cumulativeWheelRevolutions = FixtureJson.double(e["counter"]).toLong(),
                        lastWheelEventTime = FixtureJson.int(e["event_ticks"]),
                    ),
                )
                assertArrayEquals(
                    "${session["id"]} ${frame["hex"]} wheel half",
                    captured.copyOfRange(1, 7),
                    encoded.copyOfRange(1, 7),
                )
                checked++
            }
        }
        assertTrue("hardware wheel-only frames found", checked >= 8)
    }

    @Test
    fun `hardware crank-only frames put crank revolutions and time where the encoder puts them`() {
        var checked = 0
        for (session in Fixtures.objects(Fixtures.json("hardware/csc_physical_sensors_20260810.json")["sessions"])) {
            for (frame in Fixtures.objects(session["frames"])) {
                val captured = Fixtures.hex(frame["hex"] as String)
                if (captured[0].toInt() != 0x02) continue
                assertEquals("crank-only frame is 5 octets", 5, captured.size)
                val e = FixtureJson.obj(frame["expect"])
                val encoded = CyclingSensorEncoders.cscMeasurement(
                    CyclingRevolutionSnapshot(
                        cumulativeCrankRevolutions = FixtureJson.int(e["counter"]),
                        lastCrankEventTime = FixtureJson.int(e["event_ticks"]),
                    ),
                )
                assertArrayEquals(
                    "${session["id"]} ${frame["hex"]} crank half",
                    captured.copyOfRange(1, 5),
                    encoded.copyOfRange(7, 11),
                )
                checked++
            }
        }
        assertTrue("hardware crank-only frames found", checked >= 4)
    }

    @Test
    fun `the persona emits only the combined frame, which no hardware sensor in the corpus sends`() {
        // Declared gap, asserted so a corpus that gains a combined frame forces this test to change.
        val flagsSeen = Fixtures.objects(Fixtures.json("hardware/csc_physical_sensors_20260810.json")["sessions"])
            .flatMap { Fixtures.objects(it["frames"]) }
            .map { Fixtures.hex(it["hex"] as String)[0].toInt() }
            .toSet()
        assertEquals(setOf(0x01, 0x02), flagsSeen)
        assertEquals(0x03, CyclingSensorEncoders.cscMeasurement(CyclingRevolutionSnapshot())[0].toInt())
        assertEquals(11, CyclingSensorEncoders.cscMeasurement(CyclingRevolutionSnapshot()).size)
    }

    @Test
    fun `CSC frames captured from the simulator persona are reproduced octet for octet`() {
        val cases = Fixtures.objects(Fixtures.json("simulated/csc_tmiw_bike_be87.json")["cases"])
        assertTrue(cases.isNotEmpty())
        for (case in cases) {
            val e = FixtureJson.obj(case["expect"])
            val encoded = CyclingSensorEncoders.cscMeasurement(
                CyclingRevolutionSnapshot(
                    cumulativeWheelRevolutions = FixtureJson.double(e["cumulativeWheelRevolutions"]).toLong(),
                    lastWheelEventTime = FixtureJson.int(e["lastWheelEventTimeTicks"]),
                    cumulativeCrankRevolutions = FixtureJson.int(e["cumulativeCrankRevolutions"]),
                    lastCrankEventTime = FixtureJson.int(e["lastCrankEventTimeTicks"]),
                ),
            )
            assertEquals("CSC ${case["hex"]}", case["hex"], hexOf(encoded))
        }
    }

    @Test
    fun `CPS frames captured from the simulator persona are reproduced octet for octet`() {
        val fixture = Fixtures.json("simulated/cps_tmiw_bike_8f8c.json")
        var frames = 0
        for (scenario in Fixtures.objects(fixture["scenarios"])) {
            for (frame in Fixtures.objects(scenario["frames"])) {
                val e = FixtureJson.obj(frame["expect"])
                val encoded = CyclingSensorEncoders.cyclingPowerMeasurement(
                    FixtureJson.int(e["powerWatts"]),
                    CyclingRevolutionSnapshot(
                        cumulativeCrankRevolutions = FixtureJson.int(e["crankRevolutions"]),
                        lastCrankEventTime = FixtureJson.int(e["crankEventTimeTicks"]),
                    ),
                )
                assertEquals("CPS ${scenario["id"]} ${frame["hex"]}", frame["hex"], hexOf(encoded))
                frames++
            }
        }
        assertEquals(5, frames)
        val last = FixtureJson.obj(fixture["last_frame"])
        val lastExpect = FixtureJson.obj(last["expect"])
        assertEquals(
            last["hex"],
            hexOf(
                CyclingSensorEncoders.cyclingPowerMeasurement(
                    FixtureJson.int(lastExpect["powerWatts"]),
                    CyclingRevolutionSnapshot(
                        cumulativeCrankRevolutions = FixtureJson.int(lastExpect["crankRevolutions"]),
                        lastCrankEventTime = FixtureJson.int(lastExpect["crankEventTimeTicks"]),
                    ),
                ),
            ),
        )
    }

    @Test
    fun `feature and sensor location values match the captured reads`() {
        val csc = Fixtures.json("simulated/csc_tmiw_bike_be87.json")
        val cps = Fixtures.json("simulated/cps_tmiw_bike_8f8c.json")
        assertEquals(csc["feature_hex"], hexOf(CyclingSensorEncoders.cscFeature()))
        assertEquals(cps["feature_hex"], hexOf(CyclingSensorEncoders.cyclingPowerFeature()))
        assertEquals(cps["sensor_location_hex"], hexOf(CyclingSensorEncoders.sensorLocation()))
    }
}
