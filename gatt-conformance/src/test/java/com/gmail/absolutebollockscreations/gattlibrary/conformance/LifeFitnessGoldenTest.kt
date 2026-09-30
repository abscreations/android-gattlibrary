package com.gmail.absolutebollockscreations.gattlibrary.conformance

import com.gmail.absolutebollockscreations.gattlibrary.simulator.FtmsMockSample
import com.gmail.absolutebollockscreations.gattlibrary.simulator.LifeFitnessMachine
import com.gmail.absolutebollockscreations.gattlibrary.simulator.LifeFitnessPacketEncoder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * L25 golden check for the Life Fitness data frames, against the physical captures in
 * `tmiw/life_fitness_issue_462_*` (official Life Fitness app, HCI, four machines) and
 * `qz/life_fitness_issue_1018_ftms_treadmill_capture.json` (a second treadmill, HCI).
 *
 * The fixtures hold raw packets with no decoded expectations on purpose ("do not manufacture
 * goldens from the FitBridge parser"). So the test decodes each captured packet itself, from the
 * FTMS field layout and scale for that characteristic, into a physical-unit sample; feeds the
 * sample to [LifeFitnessPacketEncoder]; and requires the captured octets back. That holds the
 * encoder's scales, field order, sentinels and flag words to the capture without reading the
 * parser.
 *
 * What a real console sends that the persona does not, each asserted rather than skipped:
 *
 * - **Heart rate.** The persona always sends the heart-rate octet (flag bit set). Consoles drop it
 *   (and its flag) when no heart rate is present, which is most frames. Those frames must equal the
 *   persona's frame with that octet and flag removed.
 * - **Treadmill Remaining Time** (bit 11, 7 frames in the 462 capture): not emitted. The frame must
 *   equal the persona's frame apart from that trailing field.
 * - **Cross trainer stride count.** Real consoles report fractional strides (tenths); the persona's
 *   sample holds whole strides, so it can only emit multiples of ten. Everything else in the frame
 *   must match, and the stride octets must be within five tenths of the capture.
 */
class LifeFitnessGoldenTest {
    private class Persona(
        val machine: LifeFitnessMachine,
        val files: List<String>,
        val flagWidth: Int,
        /** Flag word the persona emits, heart-rate bit included. */
        val personaFlags: Int,
        val hrFlag: Int,
        val decode: (ByteArray, Boolean) -> FtmsMockSample,
    )

    private fun u(p: ByteArray, offset: Int, width: Int): Int {
        var v = 0
        for (i in 0 until width) v = v or ((p[offset + i].toInt() and 0xFF) shl (8 * i))
        return v
    }

    private fun s16(p: ByteArray, offset: Int): Int = u(p, offset, 2).toShort().toInt()

    private val personas = listOf(
        Persona(
            LifeFitnessMachine.TREADMILL,
            listOf("tmiw/life_fitness_issue_462_treadmill_capture.json", "qz/life_fitness_issue_1018_ftms_treadmill_capture.json"),
            2, 0x059E, 0x0100,
        ) { p, hasHr ->
            FtmsMockSample(
                elapsedSeconds = u(p, p.size - 2, 2),
                heartRate = if (hasHr) p[p.size - 3].toInt() and 0xFF else 0,
                caloriesTotal = u(p, 17, 2),
                caloriesPerHour = 0,
                caloriesPerMinute = 0,
                speedKph = u(p, 2, 2) / 100.0,
                averageSpeedKph = u(p, 4, 2) / 100.0,
                distanceMeters = u(p, 6, 3),
                inclinationPercent = s16(p, 9) / 10.0,
                positiveElevationMeters = u(p, 13, 2) / 10.0,
            )
        },
        Persona(
            LifeFitnessMachine.CROSS_TRAINER,
            listOf("tmiw/life_fitness_issue_462_cross_trainer_capture.json"),
            3, 0x2FBE, 0x0800,
        ) { p, hasHr ->
            val resistanceRaw = s16(p, 20)
            assertEquals("cross trainer resistance is whole units in every capture", 0, resistanceRaw % 10)
            FtmsMockSample(
                elapsedSeconds = u(p, p.size - 2, 2),
                heartRate = if (hasHr) p[p.size - 3].toInt() and 0xFF else 0,
                caloriesTotal = u(p, 26, 2),
                caloriesPerHour = 0,
                caloriesPerMinute = 0,
                speedKph = u(p, 3, 2) / 100.0,
                averageSpeedKph = u(p, 5, 2) / 100.0,
                distanceMeters = u(p, 7, 3),
                cadence = u(p, 10, 2).toDouble(),
                averageCadence = u(p, 12, 2).toDouble(),
                movementCount = (u(p, 14, 2) + 5) / 10, // whole strides: the sample cannot hold tenths
                positiveElevationMeters = u(p, 16, 2).toDouble(),
                resistance = resistanceRaw / 10,
                powerWatts = s16(p, 22),
                averagePowerWatts = s16(p, 24),
            )
        },
        Persona(
            LifeFitnessMachine.STAIR_CLIMBER,
            listOf("tmiw/life_fitness_issue_462_stair_climber_capture.json"),
            2, 0x017E, 0x0040,
        ) { p, hasHr ->
            FtmsMockSample(
                elapsedSeconds = u(p, p.size - 2, 2),
                heartRate = if (hasHr) p[p.size - 3].toInt() and 0xFF else 0,
                caloriesTotal = u(p, 12, 2),
                caloriesPerHour = 0,
                caloriesPerMinute = 0,
                floors = u(p, 2, 2),
                cadence = u(p, 4, 2).toDouble(),
                averageCadence = u(p, 6, 2).toDouble(),
                positiveElevationMeters = u(p, 8, 2).toDouble(),
                movementCount = u(p, 10, 2),
            )
        },
        Persona(
            LifeFitnessMachine.INDOOR_BIKE,
            listOf("tmiw/life_fitness_issue_462_indoor_bike_capture.json"),
            2, 0x0BFA, 0x0200,
        ) { p, hasHr ->
            FtmsMockSample(
                elapsedSeconds = u(p, p.size - 2, 2),
                heartRate = if (hasHr) p[p.size - 3].toInt() and 0xFF else 0,
                caloriesTotal = u(p, 19, 2),
                caloriesPerHour = 0,
                caloriesPerMinute = 0,
                speedKph = u(p, 2, 2) / 100.0,
                averageSpeedKph = u(p, 4, 2) / 100.0,
                // The capture puts both cadence fields here with flag bit 2 clear (0.5 rpm units).
                cadence = u(p, 6, 2) / 2.0,
                averageCadence = u(p, 8, 2) / 2.0,
                distanceMeters = u(p, 10, 3),
                resistance = s16(p, 13),
                powerWatts = s16(p, 15),
                averagePowerWatts = s16(p, 17),
            )
        },
    )

    private fun flagsOf(p: ByteArray, width: Int): Int = u(p, 0, width)

    private fun withFlags(p: ByteArray, width: Int, flags: Int): ByteArray = p.copyOf().also {
        for (i in 0 until width) it[i] = (flags ushr (8 * i)).toByte()
    }

    private class Tally {
        var packets = 0
        var noHeartRate = 0
        var remainingTime = 0
        var strideInexact = 0
    }

    private fun check(persona: Persona, tally: Tally) {
        for (file in persona.files) {
            for (case in Fixtures.objects(Fixtures.json(file)["cases"])) {
                val captured = Fixtures.bytes(case["packet"])
                tally.packets++
                val label = "${persona.machine} $file #${case["sequence"]}"

                // Treadmill Remaining Time (bit 11): peel off the trailing u16 and clear the flag.
                var packet = captured
                var remaining: Int? = null
                if (persona.machine == LifeFitnessMachine.TREADMILL && flagsOf(packet, 2) and 0x0800 != 0) {
                    remaining = u(packet, packet.size - 2, 2)
                    packet = withFlags(packet.copyOf(packet.size - 2), 2, flagsOf(packet, 2) and 0x0800.inv())
                    tally.remainingTime++
                }

                val flags = flagsOf(packet, persona.flagWidth)
                val hasHr = flags and persona.hrFlag != 0
                assertEquals(
                    "$label: flag word is the persona's, apart from the heart-rate bit",
                    persona.personaFlags and (if (hasHr) -1 else persona.hrFlag.inv()),
                    flags,
                )

                var encoded = LifeFitnessPacketEncoder.encode(persona.machine, persona.decode(packet, hasHr))
                if (!hasHr) {
                    tally.noHeartRate++
                    val noHr = encoded.copyOfRange(0, encoded.size - 3) + encoded.copyOfRange(encoded.size - 2, encoded.size)
                    encoded = withFlags(noHr, persona.flagWidth, flagsOf(encoded, persona.flagWidth) and persona.hrFlag.inv())
                }

                if (persona.machine == LifeFitnessMachine.CROSS_TRAINER) {
                    val capturedStride = u(packet, 14, 2)
                    val encodedStride = u(encoded, 14, 2)
                    assertTrue("$label: stride within five tenths ($capturedStride vs $encodedStride)", abs(capturedStride - encodedStride) <= 5)
                    if (capturedStride != encodedStride) tally.strideInexact++
                    encoded = encoded.copyOf().also { it[14] = 0; it[15] = 0 }
                    packet = packet.copyOf().also { it[14] = 0; it[15] = 0 }
                }
                assertArrayEquals(label, packet, encoded)
                if (remaining != null) assertTrue("$label: captured Remaining Time is a plausible countdown", remaining in 0..86_400)
            }
        }
    }

    @Test
    fun `treadmill frames from both captures are reproduced, apart from Remaining Time`() {
        val tally = Tally()
        check(personas.single { it.machine == LifeFitnessMachine.TREADMILL }, tally)
        assertEquals("179 + 221 captured frames", 400, tally.packets)
        assertEquals("declared gap: frames carrying Remaining Time", 7, tally.remainingTime)
    }

    @Test
    fun `cross trainer frames are reproduced, apart from fractional stride count`() {
        val tally = Tally()
        check(personas.single { it.machine == LifeFitnessMachine.CROSS_TRAINER }, tally)
        assertEquals(77, tally.packets)
        assertEquals("declared gap: frames whose stride count has a tenths digit", 68, tally.strideInexact)
    }

    @Test
    fun `stair climber frames are reproduced octet for octet`() {
        val tally = Tally()
        check(personas.single { it.machine == LifeFitnessMachine.STAIR_CLIMBER }, tally)
        assertEquals(76, tally.packets)
    }

    @Test
    fun `indoor bike frames are reproduced octet for octet, including the cadence quirk`() {
        val tally = Tally()
        check(personas.single { it.machine == LifeFitnessMachine.INDOOR_BIKE }, tally)
        assertEquals(115, tally.packets)
    }

    @Test
    fun `every captured fingerprint is one of the variants this test accounts for`() {
        // Guards against a capture gaining a flag word the decoders above would silently misread.
        val accounted = mapOf(
            LifeFitnessMachine.TREADMILL to setOf(0x049E, 0x059E, 0x0C9E),
            LifeFitnessMachine.CROSS_TRAINER to setOf(0x27BE, 0x2FBE),
            LifeFitnessMachine.STAIR_CLIMBER to setOf(0x013E, 0x017E),
            LifeFitnessMachine.INDOOR_BIKE to setOf(0x09FA, 0x0BFA),
        )
        for (persona in personas) {
            val seen = persona.files.flatMap { Fixtures.objects(Fixtures.json(it)["cases"]) }
                .map { flagsOf(Fixtures.bytes(it["packet"]), persona.flagWidth) }
                .toSet()
            assertEquals("${persona.machine} flag words", accounted.getValue(persona.machine), seen)
        }
    }
}
