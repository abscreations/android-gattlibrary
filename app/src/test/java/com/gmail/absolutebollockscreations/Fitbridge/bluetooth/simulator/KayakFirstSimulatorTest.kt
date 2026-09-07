package com.gmail.absolutebollockscreations.Fitbridge.bluetooth.simulator

import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine.KayakFirstDisplayConfiguration
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine.KayakFirstDisplayMetric
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine.KayakFirstDerivedMetrics
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine.KayakFirstHandshakeVariant
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine.KayakFirstMeasurement
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine.KayakFirstMeasurementDecodeResult
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine.KayakFirstOrangeCommand
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine.KayakFirstOrangeFrameEvent
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine.KayakFirstOrangeLineAssembler
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine.KayakFirstOrangeResponse
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine.KayakFirstOrangeResponseParser
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine.KayakFirstOrangeSession
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.peripheral.KayakFirstAdvertisingPlan
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.peripheral.KayakFirstGattProfile
import com.gmail.absolutebollockscreations.Fitbridge.simulatorPersonaOptions
import com.gmail.absolutebollockscreations.Fitbridge.simulatorAdvertisedNameLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KayakFirstSimulatorTest {
    @Test
    fun setup_ready_idle_sample_is_stationary_and_encodes_as_not_running() {
        val idle = kayakFirstIdleSample()
        val fields = KayakFirstSimulatorTelemetryEncoder.generated(idle, running = false)
            .toString(Charsets.UTF_8).trim().split(';')

        assertEquals(0, idle.elapsedSeconds)
        assertEquals(0.0, idle.speedKph)
        assertEquals(0.0, idle.cadence)
        assertEquals(0, idle.powerWatts)
        assertEquals("0", fields[9])
        assertEquals("0.00", fields[11])
        assertEquals("0", fields[13])
        assertEquals("0", fields[21])
        assertEquals("0", fields[22])
        assertEquals("0", fields[23])
    }

    @Test
    fun selector_places_both_kayak_generations_between_flywheel_and_first_keiser() {
        val options = simulatorPersonaOptions(includeKickr = false)
        val blue = options.indexOf(SimulatorPersona.KayakFirstBlue)
        val orange = options.indexOf(SimulatorPersona.KayakFirstOrange)
        val flywheel = options.indexOf(SimulatorPersona.FlywheelSports)
        val firstKeiser = options.indexOfFirst { it is SimulatorPersona.KeiserMSeries }

        assertEquals(1, options.count { it == SimulatorPersona.KayakFirstBlue })
        assertEquals(1, options.count { it == SimulatorPersona.KayakFirstOrange })
        assertEquals(flywheel + 1, blue)
        assertEquals(blue + 1, orange)
        assertEquals(orange + 1, firstKeiser)
        assertEquals("KayakFirst (Blue) Indoor Paddle", SimulatorPersona.KayakFirstBlue.describe().title)
        assertEquals("KayakFirst (Orange) Indoor Paddle", SimulatorPersona.KayakFirstOrange.describe().title)
        assertEquals("25100001", SimulatorPersona.KayakFirstBlue.advertisedName)
        assertEquals("17100001", SimulatorPersona.KayakFirstOrange.advertisedName)
        assertEquals("Name: 25100001", simulatorAdvertisedNameLine(SimulatorPersona.KayakFirstBlue))
        assertEquals("Name: 17100001", simulatorAdvertisedNameLine(SimulatorPersona.KayakFirstOrange))
    }

    @Test
    fun orange_captured_responses_round_trip_through_central_session() {
        val session = KayakFirstOrangeSession { 80.0 }
        session.onResponse(KayakFirstOrangeResponse.ResetOk, 0L)
        var reading: com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine.KayakFirstOrangeReading? = null
        listOf(
            KayakFirstOrangeCommand.T_MIN,
            KayakFirstOrangeCommand.T_H,
            KayakFirstOrangeCommand.T_MAX,
            KayakFirstOrangeCommand.T_V,
        ).forEachIndexed { index, command ->
            val timing = requireNotNull(
                KayakFirstOrangeSimulatorProtocol.timingMicroseconds(
                    command,
                    KayakFirstOrangeSimulatorProtocol.capturedTuple(),
                    auxiliaryRpm = 0.0,
                ),
            )
            val parser = KayakFirstOrangeResponseParser()
            val response = KayakFirstOrangeSimulatorProtocol.response(command, index + 1L, timing)
                .toString(Charsets.US_ASCII)
                .split('\n')
                .filter(String::isNotEmpty)
                .mapNotNull(parser::accept)
                .single()
            reading = session.onResponse(response, 1_000L).second ?: reading
        }
        val accepted = requireNotNull(reading)
        val oracle = requireNotNull(
            KayakFirstOrangeSimulatorProtocol.oracle(
                KayakFirstOrangeSimulatorProtocol.capturedTuple(),
            ),
        )
        assertEquals(oracle.powerWatts, accepted.physics.powerWatts, 1e-9)
        assertEquals(oracle.speedMetresPerSecond, accepted.physics.speedMetresPerSecond, 1e-9)
    }

    @Test
    fun orange_dynamic_tuple_reproduces_requested_power_and_cadence() {
        val tuple = requireNotNull(KayakFirstOrangeSimulatorProtocol.dynamicTuple(180.0, 72.0))
        val result = requireNotNull(KayakFirstOrangeSimulatorProtocol.oracle(tuple))
        assertEquals(180.0, result.powerWatts, 1e-6)
        assertEquals(72.0, result.strokeRateSpm, 1e-9)
        assertTrue(tuple.tMinSeconds < tuple.tMaxSeconds)
        assertEquals("OK\n", KayakFirstOrangeSimulatorProtocol.resetResponse().toString(Charsets.US_ASCII))
        assertEquals(
            KayakFirstOrangeCommand.AUXILIARY,
            KayakFirstOrangeSimulatorProtocol.decodeCommand(byteArrayOf('5'.code.toByte())),
        )
        assertTrue(KayakFirstOrangeSimulatorProtocol.decodeCommand("1\n".toByteArray()) == null)
    }

    @Test
    fun orange_sequence_is_a_stroke_counter_not_a_response_counter() {
        val tuple = KayakFirstOrangeSimulatorProtocol.capturedTuple()
        // One stroke is T_H + T_V = 675.094 ms on the captured tuple.
        val strokeMs = ((tuple.tHSeconds + tuple.tVSeconds) * 1_000.0).toLong()

        // Every command answered inside one stroke period carries the same value, exactly as the
        // real transcript does; a response counter would have produced four different values.
        val withinOneStroke = listOf(10L, 200L, 400L, 650L)
            .map { KayakFirstOrangeSimulatorProtocol.sequenceFor(tuple, it) }
        assertEquals(listOf(0L, 0L, 0L, 0L), withinOneStroke)

        // It advances once per stroke, and skips values when strokes pass between polls.
        assertEquals(1L, KayakFirstOrangeSimulatorProtocol.sequenceFor(tuple, strokeMs + 10))
        assertEquals(2L, KayakFirstOrangeSimulatorProtocol.sequenceFor(tuple, 2 * strokeMs + 10))
        assertEquals(7L, KayakFirstOrangeSimulatorProtocol.sequenceFor(tuple, 7 * strokeMs + 10))
        assertEquals(0L, KayakFirstOrangeSimulatorProtocol.sequenceFor(tuple, 0))
    }

    @Test
    fun orange_response_is_zero_padded_like_the_captured_device() {
        val response = KayakFirstOrangeSimulatorProtocol.response(
            KayakFirstOrangeCommand.T_MIN,
            438L,
            65_008L,
        ).toString(Charsets.US_ASCII)
        assertEquals("1\n00438\n0000065008\n", response)

        // A padded sequence still decodes to its numeric value through the production parser.
        val parser = KayakFirstOrangeResponseParser()
        val decoded = response.trimEnd('\n').split('\n').mapNotNull(parser::accept).single()
        assertTrue(decoded is KayakFirstOrangeResponse.Timing)
        assertEquals(438L, (decoded as KayakFirstOrangeResponse.Timing).value.sequence)
        assertEquals(65_008L, decoded.value.timingMicroseconds)
    }

    @Test
    fun orange_offers_only_faults_its_protocol_can_produce() {
        val supported = KayakFirstOrangeSimulatorProtocol.SUPPORTED_SCENARIOS
        // Blue-only faults have no Orange meaning: there is no command 6, no display
        // configuration and no binary reset acknowledgement on this protocol.
        listOf(
            KayakFirstSimulatorScenario.BINARY_RESET_RESPONSE,
            KayakFirstSimulatorScenario.ROGUE_COMMAND_6,
            KayakFirstSimulatorScenario.CONCATENATED_61,
        ).forEach { assertFalse(KayakFirstOrangeSimulatorProtocol.supports(it)) }
        assertTrue(KayakFirstOrangeSimulatorProtocol.supports(KayakFirstSimulatorScenario.HAPPY_PATH))
        assertEquals(supported.size, supported.distinct().size)
        assertTrue(supported.size > 10)
    }

    @Test
    fun every_orange_fault_reaches_the_central_as_its_intended_failure() {
        fun drive(scenario: KayakFirstSimulatorScenario): Pair<List<KayakFirstOrangeFrameEvent>, List<KayakFirstOrangeResponse>> {
            val bytes = KayakFirstOrangeSimulatorProtocol.renderResponse(
                scenario,
                KayakFirstOrangeCommand.T_MIN,
                438L,
                65_008L,
            ) ?: return emptyList<KayakFirstOrangeFrameEvent>() to emptyList()
            val assembler = KayakFirstOrangeLineAssembler()
            val parser = KayakFirstOrangeResponseParser()
            val events = assembler.accept(bytes)
            val responses = events.filterIsInstance<KayakFirstOrangeFrameEvent.Line>()
                .mapNotNull { parser.accept(it.value) }
            return events to responses
        }

        // Silence: nothing to render at all.
        assertNull(
            KayakFirstOrangeSimulatorProtocol.renderResponse(
                KayakFirstSimulatorScenario.MISSING_RESPONSE,
                KayakFirstOrangeCommand.T_MIN,
                438L,
                65_008L,
            ),
        )

        // Framing faults must surface as frame events, never as decoded values.
        listOf(
            KayakFirstSimulatorScenario.MALFORMED_UTF8,
            KayakFirstSimulatorScenario.NUL_BYTE,
        ).forEach { scenario ->
            val (events, responses) = drive(scenario)
            assertTrue("$scenario", events.any { it is KayakFirstOrangeFrameEvent.InvalidAscii })
            assertTrue("$scenario", responses.none { it is KayakFirstOrangeResponse.Timing })
        }
        val (overlongEvents, overlongResponses) = drive(KayakFirstSimulatorScenario.OVERLONG_LINE)
        assertTrue(overlongEvents.any { it is KayakFirstOrangeFrameEvent.TooLong })
        assertTrue(overlongResponses.none { it is KayakFirstOrangeResponse.Timing })

        // A truncated triple is held, not emitted: two lines cannot complete a response.
        val (shortEvents, shortResponses) = drive(KayakFirstSimulatorScenario.SHORT_RESPONSE)
        assertEquals(2, shortEvents.filterIsInstance<KayakFirstOrangeFrameEvent.Line>().size)
        assertTrue(shortResponses.isEmpty())

        // The grammar fault is rejected by value, not by framing.
        val (_, implausible) = drive(KayakFirstSimulatorScenario.IMPLAUSIBLE_METRICS)
        assertTrue(implausible.any { it is KayakFirstOrangeResponse.Rejected })
        assertTrue(implausible.none { it is KayakFirstOrangeResponse.Timing })

        // A stray delimiter is rejected without eating the well-formed triple behind it.
        val (_, empty) = drive(KayakFirstSimulatorScenario.EMPTY_LINE)
        assertTrue(empty.any { it is KayakFirstOrangeResponse.Rejected })
        assertTrue(empty.any { it is KayakFirstOrangeResponse.Timing })

        // The happy path still decodes exactly.
        val (_, happy) = drive(KayakFirstSimulatorScenario.HAPPY_PATH)
        val timing = happy.filterIsInstance<KayakFirstOrangeResponse.Timing>().single()
        assertEquals(438L, timing.value.sequence)
        assertEquals(65_008L, timing.value.timingMicroseconds)
    }

    @Test
    fun orange_counter_faults_hold_or_regress_the_stroke_sequence() {
        // FROZEN_METRICS repeats a tuple the central must suppress downstream.
        assertEquals(
            7L,
            KayakFirstOrangeSimulatorProtocol.scenarioSequence(
                KayakFirstSimulatorScenario.FROZEN_METRICS,
                naturalSequence = 12L,
                frozenSequence = 7L,
            ),
        )
        // RESET_METRICS is the regression the session must reject outright.
        assertEquals(
            0L,
            KayakFirstOrangeSimulatorProtocol.scenarioSequence(
                KayakFirstSimulatorScenario.RESET_METRICS,
                naturalSequence = 12L,
                frozenSequence = 7L,
            ),
        )
        // Everything else leaves the stroke counter alone.
        assertEquals(
            12L,
            KayakFirstOrangeSimulatorProtocol.scenarioSequence(
                KayakFirstSimulatorScenario.HAPPY_PATH,
                naturalSequence = 12L,
                frozenSequence = 7L,
            ),
        )
    }

    @Test
    fun advertising_plan_is_legacy_safe_and_capture_shaped() {
        val plan = KayakFirstAdvertisingPlan.create()
        assertTrue(plan.advertisingBytes <= 31)
        assertTrue(plan.scanResponseBytes <= 31)
        assertEquals(0xA759, plan.manufacturerId)
        assertEquals("88a0010203040506", plan.manufacturerPayload.toHex())
        assertEquals(
            "0201060502e0ffe1ff0bff59a788a0010203040506",
            plan.primaryPayload.toHex(),
        )
        assertEquals(
            "09093235313030303031051208000800020a00",
            plan.scanResponsePayload.toHex(),
        )
        assertEquals(21, plan.advertisingBytes)
        assertEquals(19, plan.scanResponseBytes)

        val capturedAddress = KayakFirstAdvertisingPlan.create(localAddress = "AA:BB:CC:DD:EE:FF")
        assertEquals("88a0ffeeddccbbaa", capturedAddress.manufacturerPayload.toHex())
        val orange = KayakFirstAdvertisingPlan.create(
            localName = SimulatorPersona.KayakFirstOrange.advertisedName,
        )
        assertEquals("17100001", orange.localName)
        assertEquals("09093137313030303031051208000800020a00", orange.scanResponsePayload.toHex())
        assertTrue(orange.primaryPayload.contentEquals(plan.primaryPayload))
    }

    @Test
    fun gatt_surface_is_exactly_one_duplex_ffe1_accepting_both_write_modes_with_cccd() {
        val characteristic = KayakFirstGattProfile.characteristics.single()
        assertTrue(characteristic.uuid.toString().startsWith("0000ffe1"))
        assertTrue(characteristic.writeWithResponse)
        assertTrue(characteristic.writeWithoutResponse)
        assertTrue(characteristic.notifiable)
        assertTrue(characteristic.hasCccd)
    }

    @Test
    fun fragmented_commands_are_reassembled_and_display_is_atomic() {
        val assembler = KayakFirstSimulatorCommandAssembler()
        assertTrue(assembler.append("5;0;2".toByteArray()).isEmpty())
        val accepted = assembler.append(";5;11;15\r\n".toByteArray()).single()
        assertEquals(
            KayakFirstSimulatorInput.Command(
                KayakFirstSimulatorCommand.Display(KayakFirstDisplayConfiguration.DEFAULT),
            ),
            accepted,
        )
        val invalid = assembler.append("5;0;2;5;11;16\r\n".toByteArray()).single()
        assertTrue(invalid is KayakFirstSimulatorInput.Rejected)
    }

    @Test
    fun parser_handles_two_lines_crlf_split_and_rejects_untrusted_bytes() {
        val assembler = KayakFirstSimulatorCommandAssembler(maximumBytes = 32)
        assertTrue(assembler.append("6\r".toByteArray()).isEmpty())
        val twoLines = assembler.append("\n9;1\r\n".toByteArray())
        assertEquals(KayakFirstSimulatorCommand.Poll, command(twoLines[0]))
        assertEquals(KayakFirstSimulatorCommand.Start, command(twoLines[1]))
        assertTrue(assembler.append(byteArrayOf(0)).single() is KayakFirstSimulatorInput.Rejected)
        assertTrue(assembler.append(ByteArray(33) { '1'.code.toByte() }).single() is KayakFirstSimulatorInput.Rejected)
        assertTrue(assembler.append(byteArrayOf(0xC3.toByte(), 0x28, 13, 10)).single() is KayakFirstSimulatorInput.Rejected)
    }

    @Test
    fun handshake_accepts_kayak_and_canoe_but_rejects_unknown_sports() {
        val assembler = KayakFirstSimulatorCommandAssembler()

        val kayak = command(assembler.append("2;1788720295;-240;80;1\r\n".toByteArray()).single())
        val canoeLine = "2;1788720422;-240;80;2\r\n".toByteArray()
        assertTrue(assembler.append(canoeLine.copyOfRange(0, 20)).isEmpty())
        val canoe = command(assembler.append(canoeLine.copyOfRange(20, canoeLine.size)).single())
        val unsupported = assembler.append("2;1788720422;-240;80;3\r\n".toByteArray()).single()
        val invalidWeight = assembler.append("2;1788720422;-240;500;1\r\n".toByteArray()).single()

        val kayakHandshake = kayak as KayakFirstSimulatorCommand.Handshake
        assertEquals(1, kayakHandshake.sport)
        assertEquals(80.0, kayakHandshake.bodyWeightKg, 0.0)
        assertEquals(2, (canoe as KayakFirstSimulatorCommand.Handshake).sport)

        // The Bull dialect is accepted alongside the legacy one.
        val bull = command(
            KayakFirstSimulatorCommandAssembler().append("21;1788737313;-240;93;12\r\n".toByteArray()).single(),
        ) as KayakFirstSimulatorCommand.Handshake
        assertEquals(KayakFirstHandshakeVariant.BULL, bull.variant)
        assertEquals(93.0, bull.bodyWeightKg, 0.0)
        assertEquals(12.0, bull.boatWeightKg!!, 0.0)
        assertEquals(null, bull.sport)

        // An out-of-range boat weight is not accepted as a handshake.
        assertTrue(
            KayakFirstSimulatorCommandAssembler()
                .append("21;1788737313;-240;93;999\r\n".toByteArray())
                .single() is KayakFirstSimulatorInput.Rejected,
        )
        val otherWeight = command(
            assembler.append("2;1788720422;-240;72.5;1\r\n".toByteArray()).single(),
        ) as KayakFirstSimulatorCommand.Handshake
        assertEquals(72.5, otherWeight.bodyWeightKg, 0.0)
        assertTrue(unsupported is KayakFirstSimulatorInput.Rejected)
        assertTrue(invalidWeight is KayakFirstSimulatorInput.Rejected)
    }

    @Test
    fun generated_frame_round_trips_through_independent_central_decoder() {
        val frame = KayakFirstSimulatorTelemetryEncoder.generated(sample(), running = true)
        val decoded = KayakFirstMeasurement.decode(frame.toString(Charsets.UTF_8).removeSuffix("\r\n"))
        assertTrue(decoded is KayakFirstMeasurementDecodeResult.Decoded)
        val reading = (decoded as KayakFirstMeasurementDecodeResult.Decoded).reading
        assertEquals(321.0, reading.distanceMetres, 0.0)
        assertEquals(3.0, reading.speedMetresPerSecond, 0.001)
        assertEquals(3.0, reading.averageSpeedMetresPerSecond, 0.001)
        assertEquals(72, reading.cadenceSpm)
        assertEquals(70, reading.averageCadenceSpm)
        assertEquals(67L, reading.instantaneous200mPaceSeconds)
        assertEquals(167L, reading.instantaneous500mPaceSeconds)
        assertEquals(333L, reading.instantaneous1000mPaceSeconds)
        // Force is inverted from the snapshot's watts through the same relation the central uses,
        // so decoding it back reproduces the snapshot power rather than a second force model.
        assertEquals(
            180.0,
            KayakFirstDerivedMetrics.estimatePowerFromForceAndCadence(
                reading.forceNewtons,
                reading.cadenceSpm,
            )!!,
            0.5,
        )
        assertEquals(
            165.0,
            KayakFirstDerivedMetrics.estimatePowerFromForceAndCadence(
                reading.averageForceNewtons,
                reading.averageCadenceSpm,
            )!!,
            0.5,
        )
        assertTrue(reading.activePaddling)
        assertEquals(3_725L, reading.elapsedSeconds)
        assertEquals(24, frame.toString(Charsets.UTF_8).trim().split(';').size)
    }

    @Test
    fun generated_held_frame_clears_only_the_fresh_measurement_flag() {
        val fresh = KayakFirstSimulatorTelemetryEncoder.generated(
            sample(),
            running = true,
            activePaddling = true,
        ).toString(Charsets.UTF_8).trim().split(';')
        val held = KayakFirstSimulatorTelemetryEncoder.generated(
            sample(),
            running = true,
            activePaddling = false,
        ).toString(Charsets.UTF_8).trim().split(';')

        assertEquals("1", fresh[7])
        assertEquals("0", held[7])
        assertEquals(fresh.filterIndexed { index, _ -> index != 7 }, held.filterIndexed { index, _ -> index != 7 })
    }

    @Test
    fun captured_replay_preserves_observed_four_and_five_chunk_boundaries() {
        val idle = KayakFirstSimulatorTelemetryEncoder.captured(0)
        val active = KayakFirstSimulatorTelemetryEncoder.captured(1)
        assertEquals(4, idle.asList().chunked(20).size)
        assertEquals(5, active.asList().chunked(20).size)
        assertFalse(idle.contentEquals(active))
    }

    @Test
    fun captured_replay_keeps_cumulative_fields_monotonic_across_loop_boundary() {
        fun fields(index: Int) = KayakFirstSimulatorTelemetryEncoder.captured(index)
            .toString(Charsets.UTF_8).trim().split(';')

        val last = fields(2)
        val wrappedIdle = fields(3)
        val wrappedActive = fields(4)

        assertEquals(20L, last[22].toLong())
        assertEquals(30L, wrappedIdle[22].toLong())
        assertEquals(40L, wrappedActive[22].toLong())
        assertTrue(wrappedIdle[1].toLong() >= last[1].toLong())
        assertTrue(wrappedIdle[9].toLong() >= last[9].toLong())
        assertEquals(4, KayakFirstSimulatorTelemetryEncoder.captured(3).asList().chunked(20).size)
        assertEquals(5, KayakFirstSimulatorTelemetryEncoder.captured(4).asList().chunked(20).size)
        assertEquals("0", fields(0)[22])
    }

    @Test
    fun captured_replay_idle_poll_stays_zero_without_consuming_cursor() {
        val firstRunning = kayakFirstCapturedPoll(cursor = 0, running = true, elapsedSeconds = 0)
        val idle = kayakFirstCapturedPoll(cursor = 7, running = false, elapsedSeconds = 70)
        val started = kayakFirstCapturedPoll(
            cursor = idle.nextCursor,
            running = true,
            elapsedSeconds = 70,
        )

        assertEquals(1, firstRunning.nextCursor)
        assertEquals("0", firstRunning.frame.toString(Charsets.UTF_8).trim().split(';')[22])
        assertEquals(7, idle.nextCursor)
        assertEquals("0", idle.frame.toString(Charsets.UTF_8).trim().split(';')[22])
        assertEquals(8, started.nextCursor)
        assertEquals("70", started.frame.toString(Charsets.UTF_8).trim().split(';')[22])
    }

    @Test
    fun captured_replay_uses_workout_time_instead_of_poll_count() {
        fun elapsed(atSeconds: Int) = kayakFirstCapturedPoll(
            cursor = 0,
            running = true,
            elapsedSeconds = atSeconds,
        ).frame.toString(Charsets.UTF_8).trim().split(';')[22].toLong()

        assertEquals(0L, elapsed(0))
        assertEquals(0L, elapsed(9))
        assertEquals(10L, elapsed(10))
        assertEquals(10L, elapsed(19))
        assertEquals(20L, elapsed(20))
        assertEquals(30L, elapsed(30))
    }

    @Test
    fun panel_has_five_rows_defaults_duplicates_and_safe_missing_values() {
        val rows = KayakFirstPanelPresenter.rows(KayakFirstDisplayConfiguration.DEFAULT, sample())
        assertEquals(5, rows.size)
        assertEquals("1:02:05", rows[0].value)
        assertEquals("321 m", rows[1].value)
        assertEquals("70 spm", rows[2].value)
        assertEquals("333 sec", rows[3].value)
        assertEquals("42 kcal", rows[4].value)

        val duplicate = KayakFirstDisplayConfiguration.fromWireCodes(listOf(13, 13, 4, 17, 18))!!
        val duplicateRows = KayakFirstPanelPresenter.rows(duplicate, sample())
        assertEquals("—", duplicateRows[0].value)
        assertEquals("—", duplicateRows[1].value)
        assertEquals("10.8 km/h", duplicateRows[2].value)
        assertEquals("180 W", duplicateRows[3].value)
        assertEquals("165 W", duplicateRows[4].value)
    }

    @Test
    fun all_seventeen_panel_metrics_render_without_nonfinite_values() {
        KayakFirstDisplayMetric.entries.forEach { metric ->
            val config = KayakFirstDisplayConfiguration(metric, metric, metric, metric, metric)
            val rows = KayakFirstPanelPresenter.rows(config, sample())
            assertEquals(5, rows.size)
            assertTrue(rows.none { it.value.contains("NaN") || it.value.contains("Infinity") })
        }
    }

    @Test
    fun every_fault_scenario_has_a_deterministic_name_and_renderer_is_stable() {
        assertEquals(KayakFirstSimulatorScenario.entries.size, KayakFirstSimulatorScenario.entries.map { it.name }.distinct().size)
        val normal = KayakFirstSimulatorTelemetryEncoder.captured(1)
        KayakFirstSimulatorScenario.entries.forEach { scenario ->
            assertTrue(
                KayakFirstScenarioRenderer.render(scenario, normal)
                    .contentEquals(KayakFirstScenarioRenderer.render(scenario, normal)),
            )
        }
    }

    private fun sample() = FtmsMockSample(
        elapsedSeconds = 3_725,
        heartRate = 140,
        caloriesTotal = 42,
        caloriesPerHour = 500,
        caloriesPerMinute = 8,
        speedKph = 10.8,
        averageSpeedKph = 10.8,
        distanceMeters = 321,
        cadence = 72.0,
        averageCadence = 70.0,
        powerWatts = 180,
        averagePowerWatts = 165,
    )

    private fun command(input: KayakFirstSimulatorInput?) =
        (input as KayakFirstSimulatorInput.Command).value

    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
