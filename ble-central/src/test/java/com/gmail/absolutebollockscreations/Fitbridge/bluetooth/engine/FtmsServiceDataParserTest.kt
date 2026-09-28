package com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine

import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.core.ActivityType
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine.BtServiceProfiles.FtmsMachineProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FtmsServiceDataParserTest {

    @Test
    fun `each simulated persona round trips through advertised service data`() {
        val expected = mapOf(
            FtmsMachineProfile.INDOOR_BIKE to ActivityType.BIKING,
            FtmsMachineProfile.ELLIPTICAL to ActivityType.ELLIPTICAL,
            FtmsMachineProfile.INDOOR_ROWER to ActivityType.ROWING,
            FtmsMachineProfile.STAIR_CLIMBER to ActivityType.STAIRCLIMBER,
            FtmsMachineProfile.STAIR_STEPPER to ActivityType.STAIRSTEPPER,
            FtmsMachineProfile.TREADMILL to ActivityType.TREADMILL,
        )

        expected.forEach { (profile, activity) ->
            val payload = BtServiceProfiles.ftmsServiceDataFor(profile)
            assertEquals(
                profile.name,
                activity,
                BtServiceProfiles.activityTypeFromFtmsServiceData(payload),
            )
        }
    }

    @Test
    fun `machine availability flag does not hide the advertised type`() {
        val payload = BtServiceProfiles.ftmsServiceDataFor(
            FtmsMachineProfile.INDOOR_ROWER,
            available = false,
        )

        assertEquals(
            ActivityType.ROWING,
            BtServiceProfiles.activityTypeFromFtmsServiceData(payload),
        )
    }

    @Test
    fun `missing truncated and unknown payloads do not infer a type`() {
        assertNull(BtServiceProfiles.activityTypeFromFtmsServiceData(null))
        assertNull(BtServiceProfiles.activityTypeFromFtmsServiceData(byteArrayOf()))
        assertNull(BtServiceProfiles.activityTypeFromFtmsServiceData(byteArrayOf(1, 2)))
        assertNull(
            BtServiceProfiles.activityTypeFromFtmsServiceData(
                byteArrayOf(1, 0, 1),
            ),
        )
    }

    @Test
    fun `multiple advertised types follow full connected discovery priority`() {
        val priority = listOf(
            BtServiceProfiles.FM_TYPE_TREADMILL to ActivityType.TREADMILL,
            BtServiceProfiles.FM_TYPE_ROWER to ActivityType.ROWING,
            BtServiceProfiles.FM_TYPE_INDOOR_BIKE to ActivityType.BIKING,
            BtServiceProfiles.FM_TYPE_CROSS_TRAINER to ActivityType.ELLIPTICAL,
            BtServiceProfiles.FM_TYPE_STEP_CLIMBER to ActivityType.STAIRSTEPPER,
            BtServiceProfiles.FM_TYPE_STAIR_CLIMBER to ActivityType.STAIRCLIMBER,
        )

        priority.indices.forEach { expectedIndex ->
            val advertisedTypes = priority
                .drop(expectedIndex)
                .fold(0) { bits, (typeBit, _) -> bits or typeBit }
            val payload = byteArrayOf(
                BtServiceProfiles.FM_FLAG_MACHINE_AVAILABLE.toByte(),
                advertisedTypes.toByte(),
                (advertisedTypes ushr 8).toByte(),
            )

            assertEquals(
                "priority index $expectedIndex",
                priority[expectedIndex].second,
                BtServiceProfiles.activityTypeFromFtmsServiceData(payload),
            )
        }
    }
}
