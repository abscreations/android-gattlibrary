package com.gmail.absolutebollockscreations.Fitbridge.bluetooth.simulator

import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine.KeiserMSeriesMeasurement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KeiserMSeriesValidationScenarioTest {
    @Test
    fun `five minute loop contains every requested adversarial event`() {
        val outputs = oneLoop(KeiserMSeriesSimulatorProfile.M3I_IMPERIAL)
        val events = outputs.map { it.event }.toSet()

        assertTrue(KeiserMSeriesValidationScenario.Event.POWER_DROPOUT in events)
        assertTrue(KeiserMSeriesValidationScenario.Event.CADENCE_DROPOUT in events)
        assertTrue(KeiserMSeriesValidationScenario.Event.MALFORMED in events)
        assertTrue(KeiserMSeriesValidationScenario.Event.REALTIME_BOUNDARY in events)
        assertTrue(KeiserMSeriesValidationScenario.Event.DISTANCE_BOUNDARY in events)
        assertTrue(KeiserMSeriesValidationScenario.Event.COAST in events)
        assertTrue(KeiserMSeriesValidationScenario.Event.REPEATED_UNCHANGED in events)
        assertTrue(KeiserMSeriesValidationScenario.Event.STALE_SILENCE in events)
        assertTrue(KeiserMSeriesValidationScenario.Event.RECOVERY in events)
        assertTrue(outputs.last().loopElapsedMs < KeiserMSeriesValidationScenario.LOOP_DURATION_MS)
    }

    @Test
    fun `single frame dropouts recover on the next generated advertisement`() {
        val outputs = oneLoop(KeiserMSeriesSimulatorProfile.M3I_IMPERIAL)
        val power = outputs.indexOfFirst { it.event == KeiserMSeriesValidationScenario.Event.POWER_DROPOUT }
        val cadence = outputs.indexOfFirst { it.event == KeiserMSeriesValidationScenario.Event.CADENCE_DROPOUT }

        assertEquals(0, outputs[power].sample.powerWatts)
        assertTrue(requireNotNull(outputs[power + 1].sample.powerWatts) > 0)
        assertEquals(0.0, outputs[cadence].sample.cadence!!, 0.0)
        assertTrue(requireNotNull(outputs[cadence + 1].sample.cadence) > 0.0)
    }

    @Test
    fun `coast lasts thirty seconds with zero motion and fixed distance`() {
        val coast = oneLoop(KeiserMSeriesSimulatorProfile.M3M_METRIC)
            .filter { it.loopElapsedMs in KeiserMSeriesValidationScenario.COAST_START_MS until
                KeiserMSeriesValidationScenario.COAST_END_MS }

        assertTrue(coast.isNotEmpty())
        assertTrue(coast.all { it.sample.powerWatts == 0 && it.sample.cadence == 0.0 })
        assertEquals(1, coast.map { it.sample.distanceMeters }.distinct().size)
        val coveredMs = coast.last().loopElapsedMs - coast.first().loopElapsedMs +
            KeiserMSeriesSimulatorEncoder.SAMPLE_PERIOD_MS
        assertTrue(coveredMs in 29_500L..30_500L)
    }

    @Test
    fun `repeat phase holds every advertisement byte unchanged for five seconds`() {
        val repeated = oneLoop(KeiserMSeriesSimulatorProfile.M3I_IMPERIAL)
            .filter { it.event == KeiserMSeriesValidationScenario.Event.REPEATED_UNCHANGED }
        val first = requireNotNull(repeated.first().manufacturerPayload)

        assertTrue(repeated.all { requireNotNull(it.manufacturerPayload).contentEquals(first) })
        val coveredMs = repeated.last().loopElapsedMs - repeated.first().loopElapsedMs +
            KeiserMSeriesSimulatorEncoder.SAMPLE_PERIOD_MS
        assertTrue(coveredMs in 4_500L..5_500L)
    }

    @Test
    fun `realtime and distance boundary sequences preserve their exact wire meanings`() {
        val outputs = oneLoop(KeiserMSeriesSimulatorProfile.M3M_METRIC)
        val realtime = outputs.filter { it.event == KeiserMSeriesValidationScenario.Event.REALTIME_BOUNDARY }
            .map { decode(it) }
        assertEquals(listOf(0x00, 0x01, 0x80, 0x81, 0xfe, 0xff), realtime.map { it.dataType })
        assertEquals(listOf(true, false, false, true, true, false), realtime.map { it.isRealtime })

        val distance = outputs.filter { it.event == KeiserMSeriesValidationScenario.Event.DISTANCE_BOUNDARY }
            .map { decode(it) }
        assertEquals(listOf(0x7ffe, 0x7fff, 0), distance.map { it.distanceRawTenths })
        assertTrue(distance.all { it.distanceUnit == KeiserMSeriesMeasurement.DistanceUnit.KILOMETRES })
    }

    @Test
    fun `imperial and metric identities decode as separate systems`() {
        KeiserMSeriesSimulatorProfile.entries.forEach { profile ->
            val reading = oneLoop(profile).first { it.manufacturerPayload != null }.let(::decode)
            assertEquals(profile.systemId, reading.systemId)
            val decodedUnit = when (profile.distanceUnit) {
                KeiserMSeriesSimulatorDistanceUnit.MILES -> KeiserMSeriesMeasurement.DistanceUnit.MILES
                KeiserMSeriesSimulatorDistanceUnit.KILOMETRES -> KeiserMSeriesMeasurement.DistanceUnit.KILOMETRES
            }
            assertEquals(decodedUnit, reading.distanceUnit)
        }
    }

    @Test
    fun `malformed cadence skips radio silence and every malformed value is rejected`() {
        val outputs = oneLoop(KeiserMSeriesSimulatorProfile.M3I_IMPERIAL)
        val malformed = outputs.filter { it.event == KeiserMSeriesValidationScenario.Event.MALFORMED }
        val silences = outputs.filter { it.event == KeiserMSeriesValidationScenario.Event.STALE_SILENCE }

        assertTrue(malformed.size >= 10)
        assertEquals(
            (1L..19L).toList(),
            malformed.map { it.loopElapsedMs / KeiserMSeriesValidationScenario.MALFORMED_PERIOD_MS },
        )
        malformed.forEach { output ->
            val payload = requireNotNull(output.manufacturerPayload)
            assertEquals(KeiserMSeriesSimulatorEncoder.MANUFACTURER_PAYLOAD_LENGTH - 1, payload.size)
            assertNull(KeiserMSeriesMeasurement.parse(KeiserMSeriesSimulatorEncoder.canonicalField(payload)))
        }
        assertTrue(silences.all { it.manufacturerPayload == null })
    }

    @Test
    fun `temporary pause crosses the stale threshold and recovers`() {
        KeiserMSeriesSimulatorProfile.entries.forEach { profile ->
            val outputs = oneLoop(profile)
            val stale = outputs.filter { it.event == KeiserMSeriesValidationScenario.Event.STALE_SILENCE }

            assertTrue(duration(stale, profile.samplePeriodMs) > profile.statsTimeoutMs)
            assertEquals(
                KeiserMSeriesValidationScenario.Event.RECOVERY,
                outputs[outputs.indexOf(stale.last()) + 1].event,
            )
        }
    }

    private fun oneLoop(profile: KeiserMSeriesSimulatorProfile): List<KeiserMSeriesValidationScenario.Output> {
        val scenario = KeiserMSeriesValidationScenario(profile)
        val outputs = mutableListOf<KeiserMSeriesValidationScenario.Output>()
        while (true) {
            val output = scenario.next(sample())
            if (output.loopIndex > 0) break
            outputs += output
        }
        return outputs
    }

    private fun decode(output: KeiserMSeriesValidationScenario.Output): KeiserMSeriesMeasurement.Reading =
        requireNotNull(
            KeiserMSeriesMeasurement.parse(
                KeiserMSeriesSimulatorEncoder.canonicalField(requireNotNull(output.manufacturerPayload)),
            ),
        )

    private fun duration(
        outputs: List<KeiserMSeriesValidationScenario.Output>,
        samplePeriodMs: Long = KeiserMSeriesSimulatorEncoder.SAMPLE_PERIOD_MS,
    ): Long =
        outputs.last().loopElapsedMs - outputs.first().loopElapsedMs +
            samplePeriodMs

    private fun sample() = FtmsMockSample(
        elapsedSeconds = 42,
        heartRate = 132,
        caloriesTotal = 12,
        caloriesPerHour = 300,
        caloriesPerMinute = 5,
        distanceMeters = 1_234,
        cadence = 82.5,
        resistance = 12,
        powerWatts = 215,
    )
}
