package com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine

import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Ignore
import org.junit.Test

class QueueTargetingCharacterizationTest {

    @Test
    fun `explicit HRM target wins while an FTMS default exists`() {
        val ftmsGatt = Any()
        val hrmGatt = Any()

        assertSame(hrmGatt, resolveGattTarget(hrmGatt, ftmsGatt))
        assertSame(ftmsGatt, resolveGattTarget(null, ftmsGatt))
    }

    @Test
    fun `body sensor location read is untargeted in the known baseline defect`() {
        assertNull(legacyBodySensorLocationReadTarget(Any()))
    }

    @Ignore("P0.1-D1: Body Sensor Location read must target HRM; scheduled for Phase 0.3")
    @Test
    fun `body sensor location read should target HRM`() {
        val hrmGatt = Any()

        assertSame(hrmGatt, legacyBodySensorLocationReadTarget(hrmGatt))
    }
}
