package com.gmail.absolutebollockscreations.gattlibrary.conformance

import com.gmail.absolutebollockscreations.gattlibrary.central.FixtureJson
import com.gmail.absolutebollockscreations.gattlibrary.core.ids.Concept2Uuids
import com.gmail.absolutebollockscreations.gattlibrary.simulator.Concept2Pm5Encoder
import com.gmail.absolutebollockscreations.gattlibrary.simulator.Concept2Pm5Snapshot
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID
import kotlin.math.roundToLong

/**
 * L25 golden check for the Concept2 PM5 encoder against `hardware/concept2_pm5_qz_debug.json`
 * (QZ application debug logs from two physical RowErgs and a BikeErg; application layer, not raw
 * HCI, so this is evidence of what the PM5 sent, not of the radio exchange).
 *
 * Two levels, because the fixture records different amounts per vector:
 *
 * - **FTMS Rower Data (0x2AD1)**: the fixture decodes every field, so each of the 36 captured
 *   notifications is rebuilt from its decoded fields and must match octet for octet (26 first
 *   notifications, 10 second notifications).
 * - **Native records 0x31, 0x32, 0x33, 0x35, 0x36**, individual and multiplexed: the fixture
 *   decodes only some fields per record, so the rest of each record cannot be checked. For the
 *   fields it does decode, the test first proves its own offset table against the captured octets,
 *   then requires the encoder, given the captured values, to place the same values at the same
 *   offsets. Octets the fixture does not decode (state bytes, rest time, force, work per stroke and
 *   so on) are NOT checked, and stay double entry with the PM5 specification.
 *
 * Records 0x37-0x3F and the CSAFE control point have no decoded expectations in the fixture; the
 * fixture's `evidence_limit` says the CSAFE codec is not validated by it.
 */
class Concept2GoldenTest {
    private val vectors = FixtureJson.obj(Fixtures.json("hardware/concept2_pm5_qz_debug.json")["vectors"])

    private fun entries(kind: String) = Fixtures.objects(vectors[kind])

    private fun raw(expect: Map<String, Any?>, key: String, scale: Double = 1.0): Int =
        (FixtureJson.double(expect.getValue(key)) / scale).roundToLong().toInt()

    private fun snapshot(
        elapsed: Int = 0, distanceDm: Int = 0, speedMmps: Int = 0, strokeRate: Int = 0,
        heartRate: Int = 0, pace: Int = 0, avgPace: Int = 0, power: Int = 0, avgPower: Int = 0,
        calories: Int = 0, caloriesPerHour: Int = 0, drag: Int = 0, strokeCount: Int = 0,
        averageCadence: Double = strokeRate.toDouble(), driveLengthCm: Int = 0, driveTimeCs: Int = 0,
    ) = Concept2Pm5Snapshot(
        elapsedCentiseconds = elapsed, distanceDecimetres = distanceDm,
        speedMillimetresPerSecond = speedMmps, strokeRate = strokeRate, heartRate = heartRate,
        currentPaceCentiseconds = pace, averagePaceCentiseconds = avgPace, currentPowerWatts = power,
        averagePowerWatts = avgPower, totalCalories = calories, caloriesPerHour = caloriesPerHour,
        dragFactor = drag, strokeCount = strokeCount, averageCadence = averageCadence,
        driveLengthCentimetres = driveLengthCm, driveTimeCentiseconds = driveTimeCs, machineType = 0,
    )

    @Test
    fun `every captured FTMS rower notification is reproduced octet for octet`() {
        val frames = entries("ftms_rower_data_2ad1")
        assertEquals(36, frames.size)
        var firsts = 0
        var seconds = 0
        for (entry in frames) {
            val e = FixtureJson.obj(entry["expect"])
            val captured = Fixtures.hex(entry["hex"] as String)
            val index = if (FixtureJson.int(e["flags"]) == 0x0AFF) 0 else 1
            val snapshot = if (index == 0) {
                firsts++
                snapshot(
                    averageCadence = FixtureJson.double(e["average_stroke_rate_spm"]),
                    distanceDm = raw(e, "total_distance_m", 0.1),
                    pace = raw(e, "instantaneous_pace_s", 0.01),
                    avgPace = raw(e, "average_pace_s", 0.01),
                    power = raw(e, "instantaneous_power_w"),
                    avgPower = raw(e, "average_power_w"),
                    drag = raw(e, "resistance_level"),
                    heartRate = raw(e, "heart_rate_bpm"),
                    elapsed = raw(e, "elapsed_time_s", 0.01),
                )
            } else {
                seconds++
                snapshot(
                    strokeRate = raw(e, "stroke_rate_spm"),
                    strokeCount = raw(e, "stroke_count"),
                    calories = raw(e, "total_energy_kcal"),
                    caloriesPerHour = raw(e, "energy_per_hour_kcal"),
                )
            }
            assertArrayEquals(
                "FTMS ${entry["hex"]}",
                captured,
                Concept2Pm5Encoder.ftmsRower(snapshot)[index],
            )
        }
        assertEquals("26 first and 10 second notifications", 26 to 10, firsts to seconds)
    }

    private class Field(val key: String, val offset: Int, val width: Int, val scale: Double = 1.0)

    private class Record(
        val kind: String,
        val id: Int,
        val multiplexed: Boolean,
        val fields: List<Field>,
        val uuid: UUID?,
        val build: (Map<String, Any?>, (String, Double) -> Int) -> Concept2Pm5Snapshot,
    )

    private fun le(payload: ByteArray, offset: Int, width: Int): Int {
        var value = 0
        for (i in 0 until width) value = value or ((payload[offset + i].toInt() and 0xFF) shl (8 * i))
        return value
    }

    private val elapsed = Field("elapsed_centiseconds", 0, 3)

    private val records = listOf(
        Record(
            "individual_0x31", 0x31, false, listOf(elapsed, Field("distance_m", 3, 3, 0.1)),
            Concept2Uuids.UUID_C2_GENERAL_STATUS,
        ) { _, r -> snapshot(elapsed = r("elapsed_centiseconds", 1.0), distanceDm = r("distance_m", 0.1)) },
        Record(
            "multiplexed_0x31", 0x31, true, listOf(elapsed, Field("distance_m", 3, 3, 0.1)), null,
        ) { _, r -> snapshot(elapsed = r("elapsed_centiseconds", 1.0), distanceDm = r("distance_m", 0.1)) },
        Record(
            "individual_0x32", 0x32, false,
            listOf(elapsed, Field("speed_mps", 3, 2, 0.001), Field("stroke_rate_spm", 5, 1), Field("pace_s_per_500m", 7, 2, 0.01)),
            Concept2Uuids.UUID_C2_ADDITIONAL_STATUS_1,
        ) { _, r ->
            snapshot(
                elapsed = r("elapsed_centiseconds", 1.0), speedMmps = r("speed_mps", 0.001),
                strokeRate = r("stroke_rate_spm", 1.0), pace = r("pace_s_per_500m", 0.01),
            )
        },
        Record(
            "multiplexed_0x32", 0x32, true,
            listOf(elapsed, Field("speed_mps", 3, 2, 0.001), Field("stroke_rate_spm", 5, 1), Field("pace_s_per_500m", 7, 2, 0.01)),
            null,
        ) { _, r ->
            snapshot(
                elapsed = r("elapsed_centiseconds", 1.0), speedMmps = r("speed_mps", 0.001),
                strokeRate = r("stroke_rate_spm", 1.0), pace = r("pace_s_per_500m", 0.01),
            )
        },
        Record(
            "individual_0x33", 0x33, false,
            listOf(elapsed, Field("average_power_w", 4, 2), Field("total_calories", 6, 2)),
            Concept2Uuids.UUID_C2_ADDITIONAL_STATUS_2,
        ) { _, r ->
            snapshot(elapsed = r("elapsed_centiseconds", 1.0), avgPower = r("average_power_w", 1.0), calories = r("total_calories", 1.0))
        },
        Record(
            "multiplexed_0x33", 0x33, true, listOf(elapsed, Field("total_calories", 4, 2)), null,
        ) { _, r -> snapshot(elapsed = r("elapsed_centiseconds", 1.0), calories = r("total_calories", 1.0)) },
        Record(
            "individual_0x35", 0x35, false,
            listOf(
                elapsed, Field("distance_m", 3, 3, 0.1), Field("drive_length_m", 6, 1, 0.01),
                Field("drive_time_s", 7, 1, 0.01), Field("stroke_count", 18, 2),
            ),
            Concept2Uuids.UUID_C2_STROKE_DATA,
        ) { _, r ->
            snapshot(
                elapsed = r("elapsed_centiseconds", 1.0), distanceDm = r("distance_m", 0.1),
                driveLengthCm = r("drive_length_m", 0.01), driveTimeCs = r("drive_time_s", 0.01),
                strokeCount = r("stroke_count", 1.0),
            )
        },
        Record(
            "multiplexed_0x35", 0x35, true,
            listOf(elapsed, Field("distance_m", 3, 3, 0.1), Field("drive_length_m", 6, 1, 0.01), Field("drive_time_s", 7, 1, 0.01)),
            null,
        ) { _, r ->
            snapshot(
                elapsed = r("elapsed_centiseconds", 1.0), distanceDm = r("distance_m", 0.1),
                driveLengthCm = r("drive_length_m", 0.01), driveTimeCs = r("drive_time_s", 0.01),
            )
        },
        Record(
            "individual_0x36", 0x36, false, listOf(elapsed, Field("stroke_power_w", 3, 2)),
            Concept2Uuids.UUID_C2_ADDITIONAL_STROKE_DATA,
        ) { _, r -> snapshot(elapsed = r("elapsed_centiseconds", 1.0), power = r("stroke_power_w", 1.0)) },
        Record(
            "multiplexed_0x36", 0x36, true, listOf(elapsed, Field("stroke_power_w", 3, 2)), null,
        ) { _, r -> snapshot(elapsed = r("elapsed_centiseconds", 1.0), power = r("stroke_power_w", 1.0)) },
    )

    @Test
    fun `the offset table reads the captured octets as the fixture decoded them`() {
        // Guards the table itself: a wrong offset here would make the encoder comparison below
        // meaningless, so it is checked against the capture before the encoder is involved.
        for (record in records) {
            val frames = entries(record.kind)
            assertTrue("${record.kind} has vectors", frames.isNotEmpty())
            for (entry in frames) {
                val captured = Fixtures.hex(entry["hex"] as String)
                val payload = if (record.multiplexed) {
                    assertEquals("${record.kind} id octet", record.id, captured[0].toInt() and 0xFF)
                    captured.copyOfRange(1, captured.size)
                } else captured
                assertEquals("${record.kind} length", FixtureJson.int(entry["octets"]), payload.size)
                val expect = FixtureJson.obj(entry["expect"])
                for (f in record.fields) {
                    assertEquals(
                        "${record.kind} ${f.key} in ${entry["hex"]}",
                        raw(expect, f.key, f.scale),
                        le(payload, f.offset, f.width),
                    )
                }
            }
        }
    }

    @Test
    fun `the encoder puts the captured values at the captured offsets`() {
        for (record in records) {
            for (entry in entries(record.kind)) {
                val expect = FixtureJson.obj(entry["expect"])
                val snapshot = record.build(expect) { key, scale -> raw(expect, key, scale) }
                val encoded = if (record.multiplexed) {
                    val frame = requireNotNull(Concept2Pm5Encoder.multiplexed(record.id, snapshot))
                    assertEquals("${record.kind} id octet", record.id, frame[0].toInt() and 0xFF)
                    frame.copyOfRange(1, frame.size)
                } else {
                    requireNotNull(Concept2Pm5Encoder.individual(requireNotNull(record.uuid), snapshot))
                }
                assertEquals("${record.kind} length", FixtureJson.int(entry["octets"]), encoded.size)
                for (f in record.fields) {
                    assertEquals(
                        "${record.kind} ${f.key} from ${entry["hex"]}",
                        raw(expect, f.key, f.scale),
                        le(encoded, f.offset, f.width),
                    )
                }
            }
        }
    }
}
