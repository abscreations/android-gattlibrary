package com.gmail.absolutebollockscreations.Fitbridge.bluetooth.simulator

import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine.KayakFirstDisplayConfiguration
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine.KayakFirstDisplayMetric
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine.KayakFirstMeasurement
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine.KayakFirstMeasurementDecodeResult
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.peripheral.KayakFirstAdvertisingPlan
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.peripheral.KayakFirstGattProfile
import com.gmail.absolutebollockscreations.Fitbridge.simulatorPersonaOptions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KayakFirstSimulatorTest {
    @Test
    fun selector_places_kayak_once_between_flywheel_and_first_keiser() {
        val options = simulatorPersonaOptions(includeKickr = false)
        val kayak = options.indexOf(SimulatorPersona.KayakFirstBlue)
        val flywheel = options.indexOf(SimulatorPersona.FlywheelSports)
        val firstKeiser = options.indexOfFirst { it is SimulatorPersona.KeiserMSeries }

        assertEquals(1, options.count { it == SimulatorPersona.KayakFirstBlue })
        assertEquals(flywheel + 1, kayak)
        assertEquals(kayak + 1, firstKeiser)
        assertEquals("KayakFirst (Blue) Indoor Paddle", SimulatorPersona.KayakFirstBlue.describe().title)
        assertEquals("KFBLUE01", SimulatorPersona.KayakFirstBlue.advertisedName)
    }

    @Test
    fun advertising_plan_is_legacy_safe_and_capture_shaped() {
        val plan = KayakFirstAdvertisingPlan.create()
        assertTrue(plan.advertisingBytes <= 31)
        assertTrue(plan.scanResponseBytes <= 31)
        assertEquals(0xA759, plan.manufacturerId)
        assertEquals("88a0010203040506", plan.manufacturerPayload.toHex())
    }

    @Test
    fun gatt_surface_is_exactly_one_duplex_ffe1_with_cccd() {
        val characteristic = KayakFirstGattProfile.characteristics.single()
        assertTrue(characteristic.uuid.toString().startsWith("0000ffe1"))
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
    fun generated_frame_round_trips_through_independent_central_decoder() {
        val frame = KayakFirstSimulatorTelemetryEncoder.generated(sample(), running = true)
        val decoded = KayakFirstMeasurement.decode(frame.toString(Charsets.UTF_8).removeSuffix("\r\n"))
        assertTrue(decoded is KayakFirstMeasurementDecodeResult.Decoded)
        val reading = (decoded as KayakFirstMeasurementDecodeResult.Decoded).reading
        assertEquals(321.0, reading.distanceMetres, 0.0)
        assertEquals(3.0, reading.speedMetresPerSecond, 0.001)
        assertEquals(72, reading.cadenceSpm)
        assertEquals(180, reading.powerWatts)
        assertEquals(3_725L, reading.elapsedSeconds)
        assertEquals(24, frame.toString(Charsets.UTF_8).trim().split(';').size)
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
