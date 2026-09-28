package com.gmail.absolutebollockscreations.Fitbridge.bluetooth.peripheral

import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine.BluetoothIds
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine.BtServiceProfiles.FtmsMachineProfile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FtmsFeatureConsistencyTest {

    private val complete = FtmsMetricSnapshot(
        speedKph = 25.0,
        averageSpeedKph = 20.0,
        paceSecondsPer500m = 120.0,
        averagePaceSecondsPer500m = 135.0,
        cadence = 90.0,
        averageCadence = 75.0,
        powerWatts = 250,
        averagePowerWatts = 200,
        forceOnBeltNewtons = 50,
        heartRate = 145,
        distanceMeters = 1_234,
        elapsedSeconds = 600,
        caloriesTotal = 100,
        resistance = 8f,
        inclinationPercent = 2.0,
        positiveElevationGainMeters = 30.0,
        negativeElevationGainMeters = 5.0,
        floors = 10,
        strokeCount = 300,
    )

    private fun flags(packet: ByteArray, width: Int): Int {
        var value = 0
        repeat(width) { value = value or ((packet[it].toInt() and 0xFF) shl (8 * it)) }
        return value
    }

    private fun Int.has(bit: Int) = this and (1 shl bit) != 0

    @Test
    fun `every declared persona capability is represented by complete records`() {
        for (profile in FtmsMachineProfile.entries) {
            val width = if (profile == FtmsMachineProfile.ELLIPTICAL) 3 else 2
            val packet = FtmsDataEncoders.encode(profile, complete, complete.speedKph, 182).single()
            val emitted = flags(packet, width)
            val caps = profile.measurementCapabilities

            val expectedOptionalBits = when (profile) {
                FtmsMachineProfile.INDOOR_BIKE -> listOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 11)
                FtmsMachineProfile.TREADMILL -> listOf(1, 2, 3, 4, 7, 8, 10, 12)
                FtmsMachineProfile.ELLIPTICAL -> listOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 13)
                FtmsMachineProfile.INDOOR_ROWER -> listOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 11)
                FtmsMachineProfile.STAIR_STEPPER -> listOf(1, 2, 3, 4, 5, 7)
                FtmsMachineProfile.STAIR_CLIMBER -> listOf(1, 2, 3, 4, 5, 6, 8)
            }
            for (bit in expectedOptionalBits) {
                assertTrue("$profile omitted expected field flag $bit", emitted.has(bit))
            }

            val featureBits = profile.featureBits
            assertEquals(caps.toFeatureBits(), featureBits)
            assertEquals(caps.averageSpeed, featureBits.has(FtmsSpec.Feature.AVERAGE_SPEED))
            assertEquals(caps.elevationGain, featureBits.has(FtmsSpec.Feature.ELEVATION_GAIN))
            assertEquals(caps.cadence, featureBits.has(FtmsSpec.Feature.CADENCE))
            assertEquals(caps.pace, featureBits.has(FtmsSpec.Feature.PACE))
            assertEquals(caps.power, featureBits.has(FtmsSpec.Feature.POWER_MEASUREMENT))
            assertEquals(
                caps.forceOnBeltAndPowerOutput,
                featureBits.has(FtmsSpec.Feature.FORCE_ON_BELT_AND_POWER),
            )
            assertEquals(caps.stepCount, featureBits.has(FtmsSpec.Feature.STEP_COUNT))
            assertEquals(caps.expendedEnergy, featureBits.has(FtmsSpec.Feature.EXPENDED_ENERGY))
        }
    }

    @Test
    fun `average fields are omitted when no valid average exists`() {
        val first = FtmsMetricSnapshot(speedKph = 20.0, cadence = 90.0, powerWatts = 250)
        val bike = FtmsDataEncoders.encode(FtmsMachineProfile.INDOOR_BIKE, first, 20.0, 182).single()
        val bikeFlags = flags(bike, 2)
        assertTrue(!bikeFlags.has(1))
        assertTrue(bikeFlags.has(2))
        assertTrue(!bikeFlags.has(3))
        assertTrue(bikeFlags.has(6))
        assertTrue(!bikeFlags.has(7))

        val cross = FtmsDataEncoders.encode(FtmsMachineProfile.ELLIPTICAL, first, 20.0, 182).single()
        assertTrue(!flags(cross, 3).has(1))
        assertTrue(!flags(cross, 3).has(3)) // grouped instant+average step rate

        val treadmill = FtmsDataEncoders.encode(FtmsMachineProfile.TREADMILL, first, 20.0, 182).single()
        assertTrue(!flags(treadmill, 2).has(1))
    }
}
