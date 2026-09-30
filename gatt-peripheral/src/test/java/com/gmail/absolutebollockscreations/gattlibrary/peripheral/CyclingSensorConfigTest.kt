package com.gmail.absolutebollockscreations.gattlibrary.peripheral

import com.gmail.absolutebollockscreations.gattlibrary.central.BluetoothIds
import com.gmail.absolutebollockscreations.gattlibrary.core.ftms.FtmsMachineProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CyclingSensorConfigTest {
    @Test
    fun `native sensors are only effective for indoor bike`() {
        val requested = CyclingSensorConfig(enabled = true, wheelCircumferenceMm = 2_105)

        assertTrue(requested.effectiveFor(FtmsMachineProfile.INDOOR_BIKE).enabled)
        assertFalse(requested.effectiveFor(FtmsMachineProfile.TREADMILL).enabled)
    }

    @Test
    fun `effective config bounds malformed circumference values`() {
        val low = CyclingSensorConfig(enabled = true, wheelCircumferenceMm = 1)
            .effectiveFor(FtmsMachineProfile.INDOOR_BIKE)
        val high = CyclingSensorConfig(enabled = true, wheelCircumferenceMm = 99_999)
            .effectiveFor(FtmsMachineProfile.INDOOR_BIKE)

        assertEquals(CyclingSensorConfig.MIN_WHEEL_CIRCUMFERENCE_MM, low.wheelCircumferenceMm)
        assertEquals(CyclingSensorConfig.MAX_WHEEL_CIRCUMFERENCE_MM, high.wheelCircumferenceMm)
    }
}
