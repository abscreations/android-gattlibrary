package com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine

import com.gmail.absolutebollockscreations.Fitbridge.ActivityType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FeCMetricMapperTest {

    private fun generalFe(
        equipmentType: Int = 25,
        speedMps: Double = 5.0,
        distanceEnabled: Boolean = true,
        heartRateBpm: Int? = 145,
    ) = FeCPage.GeneralFe(
        equipmentType = equipmentType,
        elapsedTimeRaw = 0,
        distanceTraveledRaw = 0,
        distanceTraveledEnabled = distanceEnabled,
        speedMetersPerSecond = speedMps,
        heartRateBpm = heartRateBpm,
        capabilitiesByte = 0,
        equipmentState = FeCEquipmentState.IN_USE,
    )

    private fun trainerData(
        cadenceRpm: Int? = 85,
        instantaneousPowerWatts: Int? = 250,
    ) = FeCPage.TrainerData(
        eventCountRaw = 0,
        cadenceRpm = cadenceRpm,
        accumulatedPowerRaw = 0,
        instantaneousPowerWatts = instantaneousPowerWatts,
        trainerStatus = 0,
        targetPowerStatus = 0,
        equipmentState = FeCEquipmentState.IN_USE,
    )

    // ── General FE Data (page 16) ─────────────────────────────────────────────────

    @Test
    fun `general FE emits speed converted to kmh, elapsed, distance and heart rate`() {
        val snapshot = FeCMeasurementSnapshot(
            elapsedTimeSeconds = 12.0,
            distanceMeters = 340.0,
        )
        val samples = FeCMetricMapper.samplesForGeneralFe(generalFe(speedMps = 5.0), snapshot)

        val speed = samples.single { it.metric == MetricKind.SPEED }
        assertEquals(18.0, speed.value, 1e-9) // 5.0 m/s * 3.6

        val elapsed = samples.single { it.metric == MetricKind.ELAPSED }
        assertEquals(12.0, elapsed.value, 1e-9)

        val distance = samples.single { it.metric == MetricKind.DISTANCE }
        assertEquals(340.0, distance.value, 1e-9)
        assertEquals(340.0, distance.nativeCumulative)

        val hr = samples.single { it.metric == MetricKind.HEART_RATE }
        assertEquals(145.0, hr.value, 1e-9)
    }

    @Test
    fun `distance is omitted when the device reports it disabled`() {
        val snapshot = FeCMeasurementSnapshot(distanceMeters = 340.0)
        val samples = FeCMetricMapper.samplesForGeneralFe(
            generalFe(distanceEnabled = false),
            snapshot,
        )
        assertTrue(samples.none { it.metric == MetricKind.DISTANCE })
    }

    @Test
    fun `heart rate is omitted when unavailable`() {
        val samples = FeCMetricMapper.samplesForGeneralFe(
            generalFe(heartRateBpm = null),
            FeCMeasurementSnapshot(),
        )
        assertTrue(samples.none { it.metric == MetricKind.HEART_RATE })
    }

    @Test
    fun `general settings and metabolic data emit every canonical metric`() {
        val settings = FeCMetricMapper.samplesForGeneralSettings(
            FeCPage.GeneralSettings(2.1, -3.25, 32.0, FeCEquipmentState.IN_USE),
        )
        assertEquals(-3.25, settings.single { it.metric == MetricKind.INCLINATION }.value, 1e-9)
        assertEquals(32.0, settings.single { it.metric == MetricKind.RESISTANCE }.value, 1e-9)

        val metabolicPage = FeCPage.GeneralMetabolicData(8.5, 640.5, 12, true, FeCEquipmentState.IN_USE)
        val metabolic = FeCMetricMapper.samplesForMetabolicData(
            metabolicPage,
            FeCMeasurementSnapshot(accumulatedCalories = 268),
        )
        assertEquals(8.5, metabolic.single { it.metric == MetricKind.METABOLIC_EQUIVALENT }.value, 1e-9)
        assertEquals(640.5, metabolic.single { it.metric == MetricKind.ENERGY_RATE }.value, 1e-9)
        val energy = metabolic.single { it.metric == MetricKind.ENERGY }
        assertEquals(268.0, energy.value, 1e-9)
        assertEquals(268.0, energy.nativeCumulative)
    }

    @Test
    fun `persona-specific pages emit their counts cadence power and vertical metrics`() {
        val ellipticalPage = FeCPage.SpecificEllipticalData(1, true, 2, true, 72, 220, FeCEquipmentState.IN_USE)
        val elliptical = FeCMetricMapper.samplesForEllipticalData(
            ellipticalPage,
            FeCMeasurementSnapshot(positiveVerticalDistanceMeters = 25.8, strideCount = 257),
        )
        assertEquals(25.8, elliptical.single { it.metric == MetricKind.ELEVATION_GAIN_POSITIVE }.value, 1e-9)
        assertEquals(257.0, elliptical.single { it.metric == MetricKind.TOTAL_STRIDES }.value, 1e-9)
        assertEquals(72.0, elliptical.single { it.metric == MetricKind.CADENCE }.value, 1e-9)
        assertEquals(220.0, elliptical.single { it.metric == MetricKind.POWER }.value, 1e-9)

        val climberPage = FeCPage.SpecificClimberData(1, true, 38, 300, FeCEquipmentState.IN_USE)
        val climber = FeCMetricMapper.samplesForClimberData(
            climberPage,
            FeCMeasurementSnapshot(climberCycleCount = 201),
        )
        assertEquals(201.0, climber.single { it.metric == MetricKind.TOTAL_STRIDES }.value, 1e-9)
        assertEquals(402.0, climber.single { it.metric == MetricKind.TOTAL_STEPS }.value, 1e-9)
        assertEquals(300.0, climber.single { it.metric == MetricKind.POWER }.value, 1e-9)

        val skierPage = FeCPage.SpecificNordicSkierData(1, true, 52, 400, FeCEquipmentState.IN_USE)
        val skier = FeCMetricMapper.samplesForNordicSkierData(
            skierPage,
            FeCMeasurementSnapshot(strideCount = 99),
        )
        assertEquals(99.0, skier.single { it.metric == MetricKind.TOTAL_STRIDES }.value, 1e-9)
        assertEquals(52.0, skier.single { it.metric == MetricKind.STROKE_RATE }.value, 1e-9)
        assertTrue(skier.none { it.metric == MetricKind.CADENCE })
        assertEquals(400.0, skier.single { it.metric == MetricKind.POWER }.value, 1e-9)
    }

    @Test
    fun `torque page emits power only when session derived a fresh value`() {
        assertTrue(FeCMetricMapper.samplesForTrainerTorqueData(FeCMeasurementSnapshot()).isEmpty())
        val samples = FeCMetricMapper.samplesForTrainerTorqueData(FeCMeasurementSnapshot(torquePowerWatts = 275.5))
        assertEquals(275.5, samples.single().value, 1e-9)
        assertEquals(MetricKind.POWER, samples.single().metric)
    }

    // ── Specific Trainer Data (page 25) ───────────────────────────────────────────

    @Test
    fun `trainer data emits cadence and instantaneous power`() {
        val samples = FeCMetricMapper.samplesForTrainerData(
            trainerData(cadenceRpm = 85, instantaneousPowerWatts = 250),
            eventCountTotal = 10,
            accumulatedPowerTotal = 1000,
            previousEventCountTotal = null,
            previousAccumulatedPowerTotal = null,
        )
        assertEquals(85.0, samples.single { it.metric == MetricKind.CADENCE }.value, 1e-9)
        assertEquals(250.0, samples.single { it.metric == MetricKind.POWER }.value, 1e-9)
    }

    @Test
    fun `average power is emitted only when the event count advanced`() {
        val withProgress = FeCMetricMapper.samplesForTrainerData(
            trainerData(),
            eventCountTotal = 12,
            accumulatedPowerTotal = 1200,
            previousEventCountTotal = 10,
            previousAccumulatedPowerTotal = 1000,
        )
        val avg = withProgress.single { it.metric == MetricKind.AVERAGE_POWER }
        assertEquals(100.0, avg.value, 1e-9) // (1200-1000)/(12-10)

        val noProgress = FeCMetricMapper.samplesForTrainerData(
            trainerData(),
            eventCountTotal = 12,
            accumulatedPowerTotal = 1200,
            previousEventCountTotal = 12,
            previousAccumulatedPowerTotal = 1200,
        )
        assertTrue(
            "a repeated frame with no new event must not restate a stale average",
            noProgress.none { it.metric == MetricKind.AVERAGE_POWER },
        )
    }

    @Test
    fun `average power is not emitted on the first frame with no prior baseline`() {
        val samples = FeCMetricMapper.samplesForTrainerData(
            trainerData(),
            eventCountTotal = 5,
            accumulatedPowerTotal = 500,
            previousEventCountTotal = null,
            previousAccumulatedPowerTotal = null,
        )
        assertTrue(samples.none { it.metric == MetricKind.AVERAGE_POWER })
    }

    @Test
    fun `cadence and power are omitted when unavailable`() {
        val samples = FeCMetricMapper.samplesForTrainerData(
            trainerData(cadenceRpm = null, instantaneousPowerWatts = null),
            eventCountTotal = 1,
            accumulatedPowerTotal = 1,
            previousEventCountTotal = null,
            previousAccumulatedPowerTotal = null,
        )
        assertTrue(samples.none { it.metric == MetricKind.CADENCE })
        assertTrue(samples.none { it.metric == MetricKind.POWER })
    }

    // ── Equipment-type classification ─────────────────────────────────────────────

    @Test
    fun `equipment types map to their closest ActivityType`() {
        assertEquals(ActivityType.TREADMILL, FeCMetricMapper.activityTypeFor(19))
        assertEquals(ActivityType.ELLIPTICAL, FeCMetricMapper.activityTypeFor(20))
        assertEquals(ActivityType.ROWING, FeCMetricMapper.activityTypeFor(22))
        assertEquals(ActivityType.STAIRCLIMBER, FeCMetricMapper.activityTypeFor(23))
        assertEquals(ActivityType.ROWING, FeCMetricMapper.activityTypeFor(24))
        // Trainer and Stationary Bike share wire value 25 per the official ANT+ spec (Table
        // 8-7) - see BluetoothIds.FEC_EQUIPMENT_STATIONARY_BIKE's doc.
        assertEquals(ActivityType.BIKING, FeCMetricMapper.activityTypeFor(25))
    }

    @Test
    fun `Nordic Skier follows PM5 SkiErg rowing activity while retaining ski identity`() {
        assertEquals(ActivityType.ROWING, FeCMetricMapper.activityTypeFor(24))
        assertEquals(
            BtEngineState.MachineDeviceType.SKI_ERG,
            FeCMetricMapper.machineDeviceTypeFor(24),
        )
    }

    @Test
    fun `wire value 21 is spec-reserved, not Stationary Bike, and is left unclassified`() {
        // Regression: this project previously (wrongly) used 21 for Stationary Bike. The
        // official spec (Table 8-7) marks 21 "Reserved - Do Not Use" for this field; a real
        // stationary bike reports 25, same as Trainer (see the test above).
        assertNull(FeCMetricMapper.activityTypeFor(21))
    }

    @Test
    fun `an unrecognized equipment type is left unclassified`() {
        assertNull(FeCMetricMapper.activityTypeFor(99))
    }
}
