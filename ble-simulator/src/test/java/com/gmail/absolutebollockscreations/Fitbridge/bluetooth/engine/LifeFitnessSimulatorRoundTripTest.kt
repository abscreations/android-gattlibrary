package com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine

import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.simulator.FtmsMockSample
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.simulator.LifeFitnessMachine
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.simulator.LifeFitnessPacketEncoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class LifeFitnessSimulatorRoundTripTest {
    private val sample = FtmsMockSample(
        elapsedSeconds = 42,
        heartRate = 131,
        caloriesTotal = 17,
        caloriesPerHour = 430,
        caloriesPerMinute = 7,
        speedKph = 18.25,
        averageSpeedKph = 11.5,
        distanceMeters = 250,
        cadence = 81.0,
        averageCadence = 79.0,
        resistance = 12,
        inclinationPercent = 3.5,
        powerWatts = 215,
        averagePowerWatts = 202,
        instantPaceMinPerKm = 3.4,
        averagePaceMinPerKm = 3.6,
        movementCount = 48,
        positiveElevationMeters = 7.0,
        floors = 6,
    )

    @Test
    fun `all generated personas round trip shared metrics through production Phase 6 parsers`() {
        LifeFitnessMachine.entries.forEach { machine ->
            val fusion = replay(machine)
            assertMetric(machine, fusion, MetricKind.HEART_RATE, 131.0)
            assertMetric(machine, fusion, MetricKind.ENERGY, 17.0)
            assertNull("$machine must preserve unavailable energy rate", fusion.latestSample(MetricKind.ENERGY_RATE))
            assertMetric(machine, fusion, MetricKind.ELAPSED, 42.0)
        }
    }

    @Test
    fun `persona fields retain units layout and the captured bike cadence quirk`() {
        replay(LifeFitnessMachine.TREADMILL).also { fusion ->
            assertMetric(LifeFitnessMachine.TREADMILL, fusion, MetricKind.SPEED, 18.25)
            assertMetric(LifeFitnessMachine.TREADMILL, fusion, MetricKind.INCLINATION, 3.5)
            assertMetric(LifeFitnessMachine.TREADMILL, fusion, MetricKind.DISTANCE, 250.0)
            assertMetric(LifeFitnessMachine.TREADMILL, fusion, MetricKind.ELEVATION_GAIN_POSITIVE, 7.0)
            assertNull(
                "treadmill 0x7FFF negative elevation is unavailable, not 3276.7 m",
                fusion.latestSample(MetricKind.ELEVATION_GAIN_NEGATIVE),
            )
            assertEquals(
                "pace remains a fusion-derived convenience, not a native treadmill field",
                MetricSourceClass.DERIVED,
                fusion.latestSample(MetricKind.INSTANT_PACE)?.sourceClass,
            )
            assertEquals(
                "average pace remains derived from average speed",
                MetricSourceClass.DERIVED,
                fusion.latestSample(MetricKind.AVERAGE_PACE)?.sourceClass,
            )
        }
        replay(LifeFitnessMachine.CROSS_TRAINER).also { fusion ->
            assertMetric(LifeFitnessMachine.CROSS_TRAINER, fusion, MetricKind.CADENCE, 81.0)
            assertMetric(LifeFitnessMachine.CROSS_TRAINER, fusion, MetricKind.POWER, 215.0)
            assertMetric(LifeFitnessMachine.CROSS_TRAINER, fusion, MetricKind.TOTAL_STRIDES, 48.0)
            assertMetric(LifeFitnessMachine.CROSS_TRAINER, fusion, MetricKind.ELEVATION_GAIN_POSITIVE, 7.0)
            assertNull(
                "cross-trainer 0xFFFF negative elevation is unavailable, not 65535 m",
                fusion.latestSample(MetricKind.ELEVATION_GAIN_NEGATIVE),
            )
        }
        replay(LifeFitnessMachine.STAIR_CLIMBER).also { fusion ->
            assertMetric(LifeFitnessMachine.STAIR_CLIMBER, fusion, MetricKind.TOTAL_FLOORS, 6.0)
            assertMetric(LifeFitnessMachine.STAIR_CLIMBER, fusion, MetricKind.CADENCE, 81.0)
            assertMetric(LifeFitnessMachine.STAIR_CLIMBER, fusion, MetricKind.TOTAL_STRIDES, 48.0)
        }
        replay(LifeFitnessMachine.INDOOR_BIKE).also { fusion ->
            assertMetric(LifeFitnessMachine.INDOOR_BIKE, fusion, MetricKind.CADENCE, 81.0)
            assertMetric(LifeFitnessMachine.INDOOR_BIKE, fusion, MetricKind.AVERAGE_CADENCE, 79.0)
            assertMetric(LifeFitnessMachine.INDOOR_BIKE, fusion, MetricKind.POWER, 215.0)
        }
    }

    private fun replay(machine: LifeFitnessMachine): MetricFusion {
        val fusion = MetricFusion(BtEngineState()).also {
            it.setPersona(
                when (machine) {
                    LifeFitnessMachine.INDOOR_BIKE -> FusionPersona.RIDE
                    else -> FusionPersona.RUN
                },
            )
        }
        val emitter = MetricEmitter(
            fusion = fusion,
            sourceId = MetricSourceId(
                ConnectionId("lf-simulator", machine.ordinal.toLong() + 1L),
                MetricProtocol.FTMS,
                machine.measurementUuid,
            ),
            sourceClass = MetricSourceClass.FTMS,
            arrivalMs = 1_000L,
        )
        val packet = LifeFitnessPacketEncoder.encode(machine, sample)
        when (machine) {
            LifeFitnessMachine.TREADMILL -> BtEngineFtmsParsers.doTreadmill(emitter, packet)
            LifeFitnessMachine.CROSS_TRAINER -> BtEngineFtmsParsers.doElliptical(emitter, packet)
            LifeFitnessMachine.STAIR_CLIMBER -> BtEngineFtmsParsers.doClimber(emitter, packet)
            LifeFitnessMachine.INDOOR_BIKE -> BtEngineFtmsParsers.doBike(emitter, packet, "LF simulator")
        }
        return fusion
    }

    private fun assertMetric(
        machine: LifeFitnessMachine,
        fusion: MetricFusion,
        metric: MetricKind,
        expected: Double,
    ) {
        val actual = fusion.latestSample(metric)?.value
        assertNotNull("${machine.name} must emit ${metric.name}", actual)
        assertEquals("${machine.name} ${metric.name}", expected, actual!!, 0.001)
    }
}
