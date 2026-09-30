package com.gmail.absolutebollockscreations.gattlibrary.conformance

import com.gmail.absolutebollockscreations.gattlibrary.central.FixtureJson
import com.gmail.absolutebollockscreations.gattlibrary.simulator.Concept2Machine
import com.gmail.absolutebollockscreations.gattlibrary.simulator.Concept2CsafeCodec
import com.gmail.absolutebollockscreations.gattlibrary.simulator.Concept2CsafeLink
import com.gmail.absolutebollockscreations.gattlibrary.simulator.Concept2Pm5Encoder
import com.gmail.absolutebollockscreations.gattlibrary.simulator.Concept2Pm5MockEngine
import com.gmail.absolutebollockscreations.gattlibrary.simulator.Concept2Pm5Snapshot
import com.gmail.absolutebollockscreations.gattlibrary.simulator.Concept2WorkoutGoalKind
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * L25 golden checks for the Concept2 PM5 against the ErgData exchanges in
 * `hardware/concept2_pm5_431225189_20260912_ergdata_workouts.json` (Android HCI snoop, one physical
 * RowErg; see `docs/evidence/pm5-ergdata-workouts/README.md`).
 *
 * - **CSAFE workout setups.** ErgData's three captured setups (fixed distance 1000 m, fixed time
 *   5:00:00, fixed time 5:00) are replayed into [Concept2CsafeLink]. The link must accept them and
 *   answer with the PM5's own response octets, apart from the status byte (its toggle bit follows
 *   the PM5's frame count, which a replay of a subset does not reproduce) and the checksum that
 *   covers it. The program the link builds, and the engine configured with it, must then report
 *   what the physical PM5 reported once the setup took effect: workout type, interval type,
 *   duration, duration type (record 0x31) and split size (record 0x3A).
 * - **Force curves.** Every captured curve is rebuilt from its points by the encoder and must match
 *   the captured packets octet for octet.
 * - **Records 0x37-0x3C.** Lengths; the offsets of elapsed time, distance and average pace, tied to
 *   the PM5's own record 0x31 and to each other (average pace = 500 * time / distance). The date,
 *   time and log-entry fields the PM5 fills in are not compared.
 *
 * Not covered: a fixed-calorie or interval setup (absent from the capture), the race-mode extended
 * frames (the link rejects extended frames as unsupported, which this test pins), and the
 * proprietary file-transfer traffic (not stored in the fixture).
 */
class Concept2ErgDataGoldenTest {
    private val fixture = Fixtures.json("hardware/concept2_pm5_431225189_20260912_ergdata_workouts.json")
    private val transactions = Fixtures.objects(fixture["csafe_transactions"])
    private val rare = FixtureJson.obj(fixture["records_seen_once_per_workout"])

    private fun le(b: ByteArray, offset: Int, width: Int): Int {
        var value = 0
        for (i in 0 until width) value = value or ((b[offset + i].toInt() and 0xFF) shl (8 * i))
        return value
    }

    /** Multiplexed frame octets with the record id removed. */
    private fun payload(hex: String): ByteArray = Fixtures.hex(hex).let { it.copyOfRange(1, it.size) }

    private fun records(kind: String) = Fixtures.objects(rare[kind])

    private fun snapshot(
        elapsed: Int = 0,
        distanceDm: Int = 0,
        averagePaceCs: Int = 0,
        forcePounds: List<Int> = emptyList(),
    ) = Concept2Pm5Snapshot(
        elapsedCentiseconds = elapsed, distanceDecimetres = distanceDm, speedMillimetresPerSecond = 0,
        strokeRate = 0, heartRate = 0, currentPaceCentiseconds = 0, averagePaceCentiseconds = averagePaceCs,
        currentPowerWatts = 0, averagePowerWatts = 0, totalCalories = 0, caloriesPerHour = 0, dragFactor = 0,
        strokeCount = 0, forceCurvePounds = forcePounds, machineType = 0,
    )

    // ── CSAFE ───────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `every captured request decodes, and extended frames are the ones the link declines`() {
        assertTrue(transactions.isNotEmpty())
        var standard = 0
        var extended = 0
        for (t in transactions) {
            val frame = Fixtures.hex(t["request_hex"] as String)
            if (frame[0] == 0xF1.toByte()) {
                standard++
                assertTrue("standard request ${t["request_hex"]}", Concept2CsafeCodec.decodeStandard(frame) is Concept2CsafeCodec.DecodeResult.Success)
            } else {
                extended++
                val result = Concept2CsafeCodec.decodeStandard(frame)
                assertEquals(
                    "extended request ${t["request_hex"]}",
                    Concept2CsafeCodec.FailureReason.EXTENDED_FRAME_UNSUPPORTED,
                    (result as Concept2CsafeCodec.DecodeResult.Failure).reason,
                )
            }
        }
        assertTrue("both frame classes are in the capture", standard > 0 && extended > 0)
    }

    /** Wire frame without start flag, status byte, checksum and stop flag. */
    private fun body(response: ByteArray) = response.copyOfRange(2, response.size - 2)

    private val setups = transactions.filter { "SET_WORKOUTTYPE" in it["request"].toString() }

    @Test
    fun `the three captured workout setups are accepted and answered as the PM5 answered`() {
        assertEquals("distance, time, time", 3, setups.size)
        for (t in setups) {
            val link = Concept2CsafeLink()
            val wire = Fixtures.hex((t["envelope_hex"] as String) + (t["request_hex"] as String))
            val outcome = link.accept(wire, 1_000L)
            assertTrue("${t["session"]} accepted: $outcome", outcome is Concept2CsafeLink.RequestResult.Accepted)
            val response = (outcome as Concept2CsafeLink.RequestResult.Accepted).response
            assertArrayEquals(
                "${t["session"]} response body",
                body(Fixtures.hex(t["response_hex"] as String)),
                body(response),
            )
        }
    }

    @Test
    fun `the program and the engine report what the physical PM5 reported after each setup`() {
        for (t in setups) {
            val session = t["session"] as String
            val status = payload(t["general_status_after_setup_hex"] as String)
            val workoutType = status[6].toInt() and 0xFF
            val intervalType = status[7].toInt() and 0xFF
            val duration = le(status, 14, 3)
            val durationType = status[17].toInt() and 0xFF

            val link = Concept2CsafeLink()
            link.accept(Fixtures.hex((t["envelope_hex"] as String) + (t["request_hex"] as String)), 1_000L)
            val program = requireNotNull(link.handshakeState.workoutProgram) { "$session built no program" }
            assertEquals("$session type", workoutType, program.workoutType)
            assertEquals("$session duration", duration, program.goalValue)
            assertEquals(
                "$session goal kind",
                when (durationType) {
                    0x80 -> Concept2WorkoutGoalKind.DISTANCE
                    0x00 -> Concept2WorkoutGoalKind.TIME
                    else -> error("unexpected duration type $durationType")
                },
                program.goalKind,
            )

            val engine = Concept2Pm5MockEngine(Concept2Machine.ROW_ERG)
            engine.configureWorkout(program)
            engine.startWorkout()
            val active = engine.tick(15.34)
            assertEquals("$session engine type", workoutType, active.workoutType)
            assertEquals("$session engine interval type", intervalType, active.intervalType)
            assertEquals("$session engine duration", duration, active.workoutDuration)
            assertEquals("$session engine duration type", durationType, active.workoutDurationType)

            // Record 0x3A: the PM5 reports the split size in metres for a distance split and in whole
            // seconds for a time split; the setup frame carries centiseconds for time.
            val summary = records("multiplexed_0x3a").single { it["session"] == session }
            val capturedSplit = le(payload(summary["hex"] as String), 4, 2)
            val emitted = le(Concept2Pm5Encoder.multiplexed(0x3A, active)!!.copyOfRange(1, 19), 4, 2)
            assertEquals("$session split size in record 0x3A", capturedSplit, emitted)
        }
    }

    // ── force curves ────────────────────────────────────────────────────────────────────────────

    @Test
    fun `every captured force curve is reproduced packet for packet`() {
        val curves = Fixtures.objects(fixture["force_curves"])
        assertTrue(curves.size >= 12)
        for (curve in curves) {
            val packets = FixtureJson.array(curve["packets_hex"]).map { Fixtures.hex(it as String) }
            val points = packets.flatMap { p ->
                val count = p[0].toInt() and 0x0F
                (0 until count).map { le(p, 2 + 2 * it, 2) }
            }
            val rebuilt = Concept2Pm5Encoder.forceCurve(snapshot(forcePounds = points))
            assertEquals("${curve["session"]} ${curve["t_s"]} packet count", packets.size, rebuilt.size)
            packets.indices.forEach { assertArrayEquals("packet $it of ${curve["session"]} ${curve["t_s"]}", packets[it], rebuilt[it]) }
        }
    }

    // ── records 0x37-0x3C ───────────────────────────────────────────────────────────────────────

    @Test
    fun `records 0x37 to 0x3C have the captured lengths`() {
        for ((id, kind) in listOf(
            0x37 to "multiplexed_0x37", 0x38 to "multiplexed_0x38", 0x39 to "multiplexed_0x39",
            0x3A to "multiplexed_0x3a", 0x3B to "multiplexed_0x3b", 0x3C to "multiplexed_0x3c",
        )) {
            val frames = records(kind)
            assertTrue("$kind present", frames.isNotEmpty())
            val encoded = requireNotNull(Concept2Pm5Encoder.multiplexed(id, snapshot()))
            for (f in frames) {
                assertEquals("$kind id octet", id, Fixtures.hex(f["hex"] as String)[0].toInt() and 0xFF)
                assertEquals("$kind payload length", FixtureJson.int(f["octets"]), encoded.size - 1)
            }
        }
    }

    @Test
    fun `elapsed time and distance sit where the PM5 puts them and agree with record 0x31`() {
        // (record, payload offset of elapsed centiseconds, payload offset of distance in decimetres or -1)
        for ((kind, offsets) in listOf(
            "multiplexed_0x37" to (0 to 3), "multiplexed_0x38" to (0 to -1), "multiplexed_0x39" to (4 to 7),
        )) {
            val frames = records(kind)
            assertTrue("$kind present", frames.isNotEmpty())
            for (f in frames) {
                val captured = payload(f["hex"] as String)
                val general = payload(f["latest_general_status_hex"] as String)
                val elapsed = le(captured, offsets.first, 3)
                val tolerance = if (kind == "multiplexed_0x39") 100 else 20
                assertTrue(
                    "$kind ${f["session"]} elapsed $elapsed vs 0x31 ${le(general, 0, 3)}",
                    abs(elapsed - le(general, 0, 3)) <= tolerance,
                )
                val distance = if (offsets.second >= 0) le(captured, offsets.second, 3) else null
                if (distance != null) {
                    assertTrue("$kind ${f["session"]} distance $distance vs 0x31 ${le(general, 3, 3)}", abs(distance - le(general, 3, 3)) <= 10)
                }

                // The encoder puts the same values at the same offsets.
                val built = Concept2Pm5Encoder.multiplexed(
                    captured.let { Fixtures.hex(f["hex"] as String)[0].toInt() and 0xFF },
                    snapshot(elapsed = elapsed, distanceDm = distance ?: 0),
                )!!.copyOfRange(1, captured.size + 1)
                assertEquals("$kind encoder elapsed", elapsed, le(built, offsets.first, 3))
                if (distance != null) assertEquals("$kind encoder distance", distance, le(built, offsets.second, 3))
            }
        }
    }

    @Test
    fun `record 0x3C carries the average pace that record 0x39 implies`() {
        val summaries = records("multiplexed_0x39")
        val paces = records("multiplexed_0x3c")
        assertTrue(summaries.isNotEmpty() && summaries.size == paces.size)
        for (summary in summaries) {
            val pace = paces.single { it["session"] == summary["session"] && abs(FixtureJson.double(it["t_s"]) - FixtureJson.double(summary["t_s"])) < 0.05 }
            val s = payload(summary["hex"] as String)
            val elapsedCs = le(s, 4, 3)
            val distanceDm = le(s, 7, 3)
            val capturedTenths = le(payload(pace["hex"] as String), 4, 2)

            // 500 m pace in tenths of a second: 500 * (elapsed s) / (distance m) * 10.
            val implied = 500.0 * elapsedCs / distanceDm
            assertTrue("${summary["session"]} implied $implied vs captured $capturedTenths", abs(implied - capturedTenths) <= 1.0)

            val built = Concept2Pm5Encoder.multiplexed(0x3C, snapshot(averagePaceCs = capturedTenths * 10))!!
            assertEquals("${summary["session"]} encoder average pace", capturedTenths, le(built.copyOfRange(1, built.size), 4, 2))
        }
    }
}
