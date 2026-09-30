package com.gmail.absolutebollockscreations.gattlibrary.central

import com.gmail.absolutebollockscreations.gattlibrary.core.ActivityType
import com.gmail.absolutebollockscreations.gattlibrary.core.ftms.FtmsMachineProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import com.gmail.absolutebollockscreations.gattlibrary.core.ftms.FtmsMachineType

class FtmsServiceDataParserTest {

    /** Test-local builder: the reader is checked against bytes assembled independently of the server's encoder. */
    private fun payloadFor(machine: FtmsMachineProfile, available: Boolean = true): ByteArray {
        val type = FtmsMachineType.fitnessMachineTypeFor(machine)
        val flags = if (available) FtmsMachineType.FM_FLAG_MACHINE_AVAILABLE else 0
        return byteArrayOf(flags.toByte(), (type and 0xFF).toByte(), ((type ushr 8) and 0xFF).toByte())
    }

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
            val payload = payloadFor(profile)
            assertEquals(
                profile.name,
                activity,
                FtmsServiceDataParser.activityType(payload),
            )
        }
    }

    @Test
    fun `machine availability flag does not hide the advertised type`() {
        val payload = payloadFor(
            FtmsMachineProfile.INDOOR_ROWER,
            available = false,
        )

        assertEquals(
            ActivityType.ROWING,
            FtmsServiceDataParser.activityType(payload),
        )
    }

    @Test
    fun `missing truncated and unknown payloads do not infer a type`() {
        assertNull(FtmsServiceDataParser.activityType(null))
        assertNull(FtmsServiceDataParser.activityType(byteArrayOf()))
        assertNull(FtmsServiceDataParser.activityType(byteArrayOf(1, 2)))
        assertNull(
            FtmsServiceDataParser.activityType(
                byteArrayOf(1, 0, 1),
            ),
        )
    }

    @Test
    fun `multiple advertised types follow full connected discovery priority`() {
        val priority = listOf(
            FtmsMachineType.FM_TYPE_TREADMILL to ActivityType.TREADMILL,
            FtmsMachineType.FM_TYPE_ROWER to ActivityType.ROWING,
            FtmsMachineType.FM_TYPE_INDOOR_BIKE to ActivityType.BIKING,
            FtmsMachineType.FM_TYPE_CROSS_TRAINER to ActivityType.ELLIPTICAL,
            FtmsMachineType.FM_TYPE_STEP_CLIMBER to ActivityType.STAIRSTEPPER,
            FtmsMachineType.FM_TYPE_STAIR_CLIMBER to ActivityType.STAIRCLIMBER,
        )

        priority.indices.forEach { expectedIndex ->
            val advertisedTypes = priority
                .drop(expectedIndex)
                .fold(0) { bits, (typeBit, _) -> bits or typeBit }
            val payload = byteArrayOf(
                FtmsMachineType.FM_FLAG_MACHINE_AVAILABLE.toByte(),
                advertisedTypes.toByte(),
                (advertisedTypes ushr 8).toByte(),
            )

            assertEquals(
                "priority index $expectedIndex",
                priority[expectedIndex].second,
                FtmsServiceDataParser.activityType(payload),
            )
        }
    }
}
