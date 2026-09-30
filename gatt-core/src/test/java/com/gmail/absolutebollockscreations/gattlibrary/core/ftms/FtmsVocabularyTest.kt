package com.gmail.absolutebollockscreations.gattlibrary.core.ftms

import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the FTMS vocabulary to the values published in Fitness Machine Service v1.0 and the SIG
 * Assigned Numbers (LIB-19a). These are literal expectations, deliberately not derived from the
 * production constants, so an accidental edit to a bit or opcode fails here.
 */
class FtmsVocabularyTest {

    private fun sig(hex: String): UUID = UUID.fromString("0000$hex-0000-1000-8000-00805f9b34fb")

    @Test
    fun serviceAndCharacteristicUuidsMatchAssignedNumbers() {
        assertEquals(sig("1826"), FtmsUuids.UUID_FTMS_SERVICE)
        assertEquals(sig("2acc"), FtmsUuids.UUID_FITNESS_MACHINE_FEATURE)
        assertEquals(sig("2ad9"), FtmsUuids.UUID_FITNESS_MACHINE_CONTROL_POINT)
        assertEquals(sig("2ada"), FtmsUuids.UUID_FITNESS_MACHINE_STATUS)
        assertEquals(sig("2ad3"), FtmsUuids.UUID_TRAINING_STATUS)
        assertEquals(sig("2acd"), FtmsUuids.UUID_TREADMILL_DATA)
        assertEquals(sig("2ace"), FtmsUuids.UUID_ELLIPTICAL_DATA)
        assertEquals(sig("2acf"), FtmsUuids.UUID_STAIR_STEPPER_DATA)
        assertEquals(sig("2ad0"), FtmsUuids.UUID_STAIR_CLIMBER_DATA)
        assertEquals(sig("2ad1"), FtmsUuids.UUID_ROWING_MACHINE_DATA)
        assertEquals(sig("2ad2"), FtmsUuids.UUID_BIKING_DATA)
        assertEquals(sig("2ad4"), FtmsUuids.UUID_SUPPORTED_SPEED_RANGE)
        assertEquals(sig("2ad5"), FtmsUuids.UUID_SUPPORTED_INCLINATION_RANGE)
        assertEquals(sig("2ad6"), FtmsUuids.UUID_SUPPORTED_RESISTANCE_RANGE)
        assertEquals(sig("2ad7"), FtmsUuids.UUID_SUPPORTED_HEART_RATE_RANGE)
        assertEquals(sig("2ad8"), FtmsUuids.UUID_SUPPORTED_POWER_RANGE)
    }

    @Test
    fun everyProfileHasItsOwnDataCharacteristic() {
        val uuids = FtmsMachineProfile.entries.map { FtmsUuids.dataUuidForProfile(it) }
        assertEquals(FtmsMachineProfile.entries.size, uuids.toSet().size)
        assertEquals(FtmsUuids.UUID_BIKING_DATA, FtmsUuids.dataUuidForProfile(FtmsMachineProfile.INDOOR_BIKE))
        assertEquals(FtmsUuids.UUID_ROWING_MACHINE_DATA, FtmsUuids.dataUuidForProfile(FtmsMachineProfile.INDOOR_ROWER))
        assertEquals(FtmsUuids.UUID_STAIR_STEPPER_DATA, FtmsUuids.dataUuidForProfile(FtmsMachineProfile.STAIR_STEPPER))
        assertEquals(FtmsUuids.UUID_STAIR_CLIMBER_DATA, FtmsUuids.dataUuidForProfile(FtmsMachineProfile.STAIR_CLIMBER))
    }

    @Test
    fun controlPointOpcodesAndResultsMatchSection416() {
        val o = FtmsSpec.Opcode
        assertEquals(
            listOf(0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x11, 0x12, 0x13, 0x14, 0x80),
            listOf(
                o.REQUEST_CONTROL, o.RESET, o.SET_TARGET_SPEED, o.SET_TARGET_INCLINATION,
                o.SET_TARGET_RESISTANCE, o.SET_TARGET_POWER, o.SET_TARGET_HEART_RATE, o.START_RESUME,
                o.STOP_PAUSE, o.SET_INDOOR_BIKE_SIMULATION, o.SET_WHEEL_CIRCUMFERENCE,
                o.SPIN_DOWN_CONTROL, o.SET_TARGETED_CADENCE, o.RESPONSE_CODE,
            ).map { it.toInt() and 0xFF },
        )
        val r = FtmsSpec.Result
        assertEquals(
            listOf(1, 2, 3, 4, 5),
            listOf(r.SUCCESS, r.OPCODE_NOT_SUPPORTED, r.INVALID_PARAMETER, r.OPERATION_FAILED, r.CONTROL_NOT_PERMITTED)
                .map { it.toInt() },
        )
    }

    @Test
    fun statusCodesMatchSection417() {
        val s = FtmsSpec.Status
        assertEquals(
            listOf(0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09, 0x12, 0x13, 0xFF),
            listOf(
                s.NO_STATUS, s.RESET, s.STOPPED_OR_PAUSED_BY_USER, s.STOPPED_BY_SAFETY_KEY,
                s.STARTED_OR_RESUMED_BY_USER, s.TARGET_SPEED_CHANGED, s.TARGET_INCLINE_CHANGED,
                s.TARGET_RESISTANCE_CHANGED, s.TARGET_POWER_CHANGED, s.TARGET_HEART_RATE_CHANGED,
                s.INDOOR_BIKE_SIMULATION_PARAMETERS_CHANGED, s.WHEEL_CIRCUMFERENCE_CHANGED,
                s.CONTROL_PERMISSION_LOST,
            ).map { it.toInt() and 0xFF },
        )
    }

    @Test
    fun featureBitPositionsMatchTables43And44() {
        assertEquals(0, FtmsSpec.Feature.AVERAGE_SPEED)
        assertEquals(14, FtmsSpec.Feature.POWER_MEASUREMENT)
        assertEquals(16, FtmsSpec.Feature.USER_DATA_RETENTION)
        assertEquals(13, FtmsSpec.TargetFeature.INDOOR_BIKE_SIMULATION)
        assertEquals(15, FtmsSpec.TargetFeature.SPIN_DOWN_CONTROL)
        assertEquals(16, FtmsSpec.TargetFeature.TARGETED_CADENCE)
    }

    @Test
    fun controlPointUnitScales() {
        assertEquals(100f, FtmsSpec.SPEED_SCALE, 0f)
        assertEquals(10f, FtmsSpec.INCLINE_SCALE, 0f)
        assertEquals(10f, FtmsSpec.RESISTANCE_SCALE, 0f)
        assertEquals(1f, FtmsSpec.POWER_SCALE, 0f)
        assertEquals(0x7FFF, FtmsSpec.SINT16_NOT_AVAILABLE)
    }

    @Test
    fun machineTypeBitsFollowTable33WithTheCrossedStairNames() {
        assertEquals(0x01, FtmsMachineType.FM_TYPE_TREADMILL)
        assertEquals(0x02, FtmsMachineType.FM_TYPE_CROSS_TRAINER)
        assertEquals(0x04, FtmsMachineType.FM_TYPE_STEP_CLIMBER)
        assertEquals(0x08, FtmsMachineType.FM_TYPE_STAIR_CLIMBER)
        assertEquals(0x10, FtmsMachineType.FM_TYPE_ROWER)
        assertEquals(0x20, FtmsMachineType.FM_TYPE_INDOOR_BIKE)
        // 0x2ACF is spec "Step Climber" (bit 2); 0x2AD0 is spec "Stair Climber" (bit 3).
        assertEquals(0x04, FtmsMachineType.fitnessMachineTypeFor(FtmsMachineProfile.STAIR_STEPPER))
        assertEquals(0x08, FtmsMachineType.fitnessMachineTypeFor(FtmsMachineProfile.STAIR_CLIMBER))
        assertEquals(1, FtmsMachineType.FM_FLAG_MACHINE_AVAILABLE)
        val bits = FtmsMachineProfile.entries.map { FtmsMachineType.fitnessMachineTypeFor(it) }
        assertTrue(bits.all { Integer.bitCount(it) == 1 })
        assertEquals(bits.size, bits.toSet().size)
    }

    @Test
    fun dataFlagsAreDistinctSingleBitsPerCharacteristic() {
        val groups = mapOf(
            "treadmill" to listOf(
                FtmsDataFlags.TREAD_MOREDATA, FtmsDataFlags.TREAD_AVGSPEED, FtmsDataFlags.TREAD_TOTALDISTANCE,
                FtmsDataFlags.TREAD_INCLRAMPANGLE, FtmsDataFlags.TREAD_ELEVATIONGAIN, FtmsDataFlags.TREAD_INSTANTPACE,
                FtmsDataFlags.TREAD_AVGPACE, FtmsDataFlags.TREAD_EXPENERGY, FtmsDataFlags.TREAD_HEARTRATE,
                FtmsDataFlags.TREAD_METABOLIC, FtmsDataFlags.TREAD_ELAPSEDTIME, FtmsDataFlags.TREAD_REMAININGTIME,
                FtmsDataFlags.TREAD_BELTFORCEPOWER,
            ),
            "bike" to listOf(
                FtmsDataFlags.BIKE_MORE, FtmsDataFlags.BIKE_AVGSPEED, FtmsDataFlags.BIKE_CADENCE,
                FtmsDataFlags.BIKE_AVGCAD, FtmsDataFlags.BIKE_TOTALDIST, FtmsDataFlags.BIKE_RESISTANCE,
                FtmsDataFlags.BIKE_INSTPOWER, FtmsDataFlags.BIKE_AVGPOWER, FtmsDataFlags.BIKE_EXPENERGY,
                FtmsDataFlags.BIKE_HEARTRATE, FtmsDataFlags.BIKE_METABOLIC, FtmsDataFlags.BIKE_ELAPSED,
                FtmsDataFlags.BIKE_REMAINING,
            ),
        )
        for ((name, flags) in groups) {
            assertTrue("$name flags must be single bits", flags.all { Integer.bitCount(it) == 1 })
            assertEquals("$name flags must be distinct", flags.size, flags.toSet().size)
        }
        // Indoor Bike Data bit order per FTMS Table 4.8: bit 1 average speed, bit 2 instantaneous cadence.
        assertEquals(0x0002, FtmsDataFlags.BIKE_AVGSPEED)
        assertEquals(0x0004, FtmsDataFlags.BIKE_CADENCE)
        assertNotEquals(FtmsDataFlags.BIKE_AVGSPEED, FtmsDataFlags.BIKE_CADENCE)
    }
}
