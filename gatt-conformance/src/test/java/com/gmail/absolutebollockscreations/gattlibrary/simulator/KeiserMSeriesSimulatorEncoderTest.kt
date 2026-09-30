package com.gmail.absolutebollockscreations.gattlibrary.simulator

import com.gmail.absolutebollockscreations.gattlibrary.central.KeiserMSeriesMeasurement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.gmail.absolutebollockscreations.gattlibrary.core.vendor.KeiserMSeriesSpec

class KeiserMSeriesSimulatorEncoderTest {
    private val metricProfile = KeiserMSeriesSimulatorProfile.M3M_METRIC

    @Test
    fun `generated Android manufacturer value round trips through production decoder`() {
        val payload = KeiserMSeriesSimulatorEncoder.encode(sample(), metricProfile)
        val reading = KeiserMSeriesMeasurement.parse(
            KeiserMSeriesSimulatorEncoder.canonicalField(payload),
        )

        assertEquals(17, payload.size)
        assertNotNull(reading)
        requireNotNull(reading)
        assertEquals("6.30", reading.firmware)
        assertTrue(reading.isRealtime)
        assertEquals(metricProfile.systemId, reading.systemId)
        assertEquals(87.6, reading.cadenceRpm, 0.0)
        assertEquals(143.0, reading.heartRateBpm, 0.0)
        assertEquals(271, reading.powerWatts)
        assertEquals(42, reading.energyKcal)
        assertEquals(12 * 60 + 34, reading.elapsedSeconds)
        assertEquals(KeiserMSeriesMeasurement.DistanceUnit.KILOMETRES, reading.distanceUnit)
        assertEquals(123, reading.distanceRawTenths)
        assertEquals(12_300.0, reading.distanceMetres, 0.0)
        assertEquals(17, reading.resistance)
    }

    @Test
    fun `unsigned fields saturate and metric distance keeps its unit bit`() {
        val reading = requireNotNull(
            KeiserMSeriesMeasurement.parse(
                KeiserMSeriesSimulatorEncoder.canonicalField(
                    KeiserMSeriesSimulatorEncoder.encode(
                        sample().copy(
                            elapsedSeconds = Int.MAX_VALUE,
                            heartRate = Int.MAX_VALUE,
                            caloriesTotal = Int.MAX_VALUE,
                            distanceMeters = Int.MAX_VALUE,
                            cadence = Double.MAX_VALUE,
                            resistance = Int.MAX_VALUE,
                            powerWatts = Int.MAX_VALUE,
                        ),
                        metricProfile,
                    ),
                ),
            ),
        )

        assertEquals(6553.5, reading.cadenceRpm, 0.0)
        assertEquals(6553.5, reading.heartRateBpm, 0.0)
        assertEquals(0xffff, reading.powerWatts)
        assertEquals(0xffff, reading.energyKcal)
        assertEquals(255 * 60 + 59, reading.elapsedSeconds)
        assertEquals(0x7fff, reading.distanceRawTenths)
        assertEquals(255, reading.resistance)
    }

    @Test
    fun `legacy envelope is non connectable non scannable and fits thirty one bytes`() {
        val persona = SimulatorPersona.KeiserMSeries(metricProfile)
        val plan = KeiserMSeriesAdvertisingPlan.create(
                persona,
                KeiserMSeriesSimulatorEncoder.encode(sample(), metricProfile),
            )

        assertEquals("M3m", plan.localName)
        assertEquals(KeiserMSeriesSpec.COMPANY_ID, plan.manufacturerId)
        assertFalse(plan.connectable)
        assertFalse(plan.scannable)
        assertEquals(0x0237, plan.intervalUnits)
        assertEquals(29, plan.advertisingBytes)
        assertTrue(plan.advertisingBytes <= 31)
    }

    @Test
    fun `old persona emits firmware 6 22 at the captured slow interval`() {
        val profile = KeiserMSeriesSimulatorProfile.M3O_OLD_FIRMWARE
        val persona = SimulatorPersona.KeiserMSeries(profile)
        val payload = KeiserMSeriesSimulatorEncoder.encode(sample(), profile)
        val reading = requireNotNull(
            KeiserMSeriesMeasurement.parse(KeiserMSeriesSimulatorEncoder.canonicalField(payload)),
        )
        val plan = KeiserMSeriesAdvertisingPlan.create(persona, payload)

        assertEquals("6.22", reading.firmware)
        assertEquals(KeiserMSeriesMeasurement.SLOW_STATS_TIMEOUT_MS, reading.statsTimeoutMs)
        assertEquals(0x0C80, plan.intervalUnits)
        assertEquals(2_000L, profile.samplePeriodMs)
        assertEquals(17, payload.size)
        assertEquals(17, reading.resistance)
    }

    private fun sample() = FtmsMockSample(
        elapsedSeconds = 12 * 60 + 34,
        heartRate = 143,
        caloriesTotal = 42,
        caloriesPerHour = 0,
        caloriesPerMinute = 0,
        distanceMeters = 12_345,
        cadence = 87.6,
        resistance = 17,
        powerWatts = 271,
    )
}
