package com.gmail.absolutebollockscreations.gattlibrary.central

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 5 exit criterion: replay the real Orange UART transcript end to end.
 *
 * Every record is rebuilt as the exact bytes the device put on the wire — including the trailing
 * space padding it emits on its first sequence lines — and driven through the production line
 * assembler, response parser and session. Nothing here is synthesized except arrival time, which
 * the capture does not contain; the cadence below is a test fixture, not an observation.
 */
class KayakFirstOrangeTranscriptReplayTest {
    private data class Record(
        val commandLine: String,
        val sequenceLine: String,
        val timingLine: String,
        val command: String,
        val sequence: Long,
        val timingUs: Long,
    )

    @Test
    fun `transcript records the sequence and padding behaviour the parser must tolerate`() {
        val fixture = load()
        val properties = FixtureJson.obj(fixture["observed_properties"])

        assertEquals(RECORD_COUNT, FixtureJson.int(fixture["record_count"]))
        assertEquals(10, FixtureJson.int(properties["timing_digits"]))
        assertEquals(3, FixtureJson.int(properties["records_with_trailing_space_padding"]))
        // The reset acknowledgement FitBridge implements is not evidenced by this capture.
        assertEquals(0, FixtureJson.int(properties["reset_acknowledgement_records"]))
        assertEquals(0, FixtureJson.int(properties["reset_command_records"]))

        val records = records()
        // The sequence is a shared stroke counter, not a per-response counter: one cycle's four
        // commands normally carry the same value, and the global stream is not monotonic.
        assertTrue(records.zipWithNext().any { (a, b) -> b.sequence < a.sequence })
        val perCommand = records.groupBy { it.command }.mapValues { it.value.map(Record::sequence) }
        perCommand.values.forEach { sequences ->
            assertTrue(sequences.zipWithNext().all { (a, b) -> b >= a })
        }
        // ...and at least one command repeats a sequence, which is why the session rejects only
        // regressions rather than non-increases.
        assertTrue(perCommand.values.any { it.zipWithNext().any { (a, b) -> b == a } })
    }

    @Test
    fun `every captured response parses through the production wire path`() {
        val assembler = KayakFirstOrangeLineAssembler()
        val parser = KayakFirstOrangeResponseParser()
        val decoded = mutableListOf<KayakFirstOrangeTimingResponse>()
        var rejected = 0
        var frameFaults = 0

        records().forEach { record ->
            // One byte at a time: the assembler must reassemble regardless of fragmentation.
            wireBytes(record).forEach { byte ->
                assembler.accept(byteArrayOf(byte)).forEach { event ->
                    when (event) {
                        is KayakFirstOrangeFrameEvent.Line -> when (
                            val response = parser.accept(event.value)
                        ) {
                            is KayakFirstOrangeResponse.Timing -> decoded += response.value
                            KayakFirstOrangeResponse.Rejected -> rejected += 1
                            KayakFirstOrangeResponse.ResetOk, null -> Unit
                        }
                        KayakFirstOrangeFrameEvent.TooLong,
                        KayakFirstOrangeFrameEvent.InvalidAscii,
                        -> frameFaults += 1
                    }
                }
            }
        }

        assertEquals(0, frameFaults)
        assertEquals(0, rejected)
        assertEquals(RECORD_COUNT, decoded.size)
        records().zip(decoded).forEach { (record, response) ->
            assertEquals(record.command, response.command.wire.toInt().toChar().toString())
            assertEquals(record.sequence, response.sequence)
            assertEquals(record.timingUs, response.timingMicroseconds)
            assertEquals(record.timingUs / 1_000_000.0, response.timingSeconds, 0.0)
        }
    }

    @Test
    fun `session replays the transcript into stable physically plausible metrics`() {
        val session = KayakFirstOrangeSession { ATHLETE_WEIGHT_KG }
        session.assumeResetComplete()
        val readings = mutableListOf<KayakFirstOrangeReading>()
        var arrivalMs = 0L

        records().forEach { record ->
            arrivalMs += RESPONSE_INTERVAL_MS
            val response = KayakFirstOrangeResponse.Timing(
                KayakFirstOrangeTimingResponse(
                    requireNotNull(KayakFirstOrangeCommand.fromLine(record.command)),
                    record.sequence,
                    record.timingUs,
                ),
            )
            session.onResponse(response, arrivalMs).second?.let(readings::add)
        }

        // 26 of the 27 available cycles complete. The three leading responses precede the first
        // T_MIN, so the session is still waiting to synchronize and cannot consume them.
        assertEquals(EXPECTED_TUPLES, readings.size)

        // Cumulative channels never go backwards.
        readings.zipWithNext().forEach { (previous, current) ->
            assertTrue(current.distanceMetres >= previous.distanceMetres)
            assertTrue(current.strokeCount >= previous.strokeCount)
            assertTrue(current.elapsedSeconds > previous.elapsedSeconds)
        }

        // This is a steady synthetic effort, so every derived value should sit in a narrow band
        // rather than merely being finite.
        readings.forEach { reading ->
            val physics = reading.physics
            assertTrue("power ${physics.powerWatts}", physics.powerWatts in 48.4..48.7)
            assertTrue("force ${physics.forceNewtons}", physics.forceNewtons in 19.2..19.4)
            assertTrue("speed ${physics.speedMetresPerSecond}", physics.speedMetresPerSecond in 2.55..2.57)
            assertTrue("stroke rate ${physics.strokeRateSpm}", physics.strokeRateSpm in 88.8..88.9)
        }
        val powers = readings.map { it.physics.powerWatts }
        val spread = requireNotNull(powers.maxOrNull()) - requireNotNull(powers.minOrNull())
        assertTrue("steady transcript must not swing in power: $spread W", spread < 0.2)

        val last = readings.last()
        assertTrue(last.distanceMetres > 0.0)
        assertTrue("avg power ${last.averagePowerWatts}", last.averagePowerWatts in 48.4..48.7)
        assertTrue(last.averageSpeedMetresPerSecond in 2.55..2.57)
        assertTrue(last.averageStrokeRateSpm in 88.5..89.2)
        // Average speed and distance stay one consistent domain.
        assertEquals(
            last.distanceMetres / last.elapsedSeconds,
            last.averageSpeedMetresPerSecond,
            1e-9,
        )
    }

    @Test
    fun `a regressed sequence is still rejected while a repeat is accepted`() {
        fun session() = KayakFirstOrangeSession { ATHLETE_WEIGHT_KG }
        fun timing(command: KayakFirstOrangeCommand, sequence: Long, us: Long) =
            KayakFirstOrangeResponse.Timing(
                KayakFirstOrangeTimingResponse(command, sequence, us),
            )

        val repeat = session()
        repeat.assumeResetComplete()
        repeat.onResponse(timing(KayakFirstOrangeCommand.T_MIN, 441, 65_008), 1_000)
        repeat.onResponse(timing(KayakFirstOrangeCommand.T_H, 441, 335_046), 1_100)
        repeat.onResponse(timing(KayakFirstOrangeCommand.T_MAX, 441, 70_009), 1_200)
        repeat.onResponse(timing(KayakFirstOrangeCommand.T_V, 441, 340_048), 1_300)
        // The same command repeating its sequence must advance polling, exactly as the hardware does.
        assertEquals(
            KayakFirstOrangeCommand.T_H,
            repeat.onResponse(timing(KayakFirstOrangeCommand.T_MIN, 441, 65_010), 1_400).first,
        )

        val regression = session()
        regression.assumeResetComplete()
        regression.onResponse(timing(KayakFirstOrangeCommand.T_MIN, 441, 65_008), 1_000)
        regression.onResponse(timing(KayakFirstOrangeCommand.T_H, 441, 335_046), 1_100)
        regression.onResponse(timing(KayakFirstOrangeCommand.T_MAX, 441, 70_009), 1_200)
        regression.onResponse(timing(KayakFirstOrangeCommand.T_V, 441, 340_048), 1_300)
        assertEquals(
            null,
            regression.onResponse(timing(KayakFirstOrangeCommand.T_MIN, 440, 65_010), 1_400).first,
        )
    }

    private fun wireBytes(record: Record): ByteArray =
        "${record.commandLine}\n${record.sequenceLine}\n${record.timingLine}\n"
            .toByteArray(Charsets.US_ASCII)

    private fun records(): List<Record> = FixtureJson.array(load()["records"])
        .map(FixtureJson::obj)
        .map {
            Record(
                commandLine = FixtureJson.string(it["command_line"]),
                sequenceLine = FixtureJson.string(it["sequence_line"]),
                timingLine = FixtureJson.string(it["timing_line"]),
                command = FixtureJson.string(it["command"]),
                sequence = FixtureJson.int(it["sequence"]).toLong(),
                timingUs = FixtureJson.int(it["timing_us"]).toLong(),
            )
        }

    private fun load(): Map<String, Any?> = javaClass.classLoader
        ?.getResourceAsStream(FIXTURE)
        ?.bufferedReader()
        ?.use { FixtureJson.obj(FixtureJson.parse(it.readText())) }
        ?: error("missing $FIXTURE")

    private companion object {
        const val FIXTURE = "fixtures/hardware/kayak_first_orange_uart_202604.json"
        const val RECORD_COUNT = 110
        const val EXPECTED_TUPLES = 26
        const val ATHLETE_WEIGHT_KG = 80.0

        /**
         * Synthesized arrival cadence. The capture carries no timestamps, so this is a test
         * constant chosen to make one 1/2/3/4 cycle span one second; it is not an observed rate.
         */
        const val RESPONSE_INTERVAL_MS = 250L
    }
}
