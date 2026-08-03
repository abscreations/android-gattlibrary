package com.gmail.absolutebollockscreations.Fitbridge.bluetooth.peripheral

import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine.BluetoothIds
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine.BtServiceProfiles.FtmsMachineProfile

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FtmsAdvertisingPlanTest {

    @Test
    fun `every persona fits one explicit legacy advertising set`() {
        for (profile in FtmsMachineProfile.entries) {
            val plan = FtmsAdvertisingPlan.forProfile(profile)
            val expectedBytes = 3 + (2 + profile.advertisedName.toByteArray().size) + 4 + 7
            assertEquals(expectedBytes, plan.advertisingBytes)
            assertTrue(plan.advertisingBytes <= 31)
            assertEquals(0, plan.scanResponseBytes)
            assertArrayEquals(ftmsServiceData(profile), plan.serviceData)
        }
    }

    @Test
    fun `appearance is deliberately not declared`() {
        for (profile in FtmsMachineProfile.entries) {
            assertTrue(!FtmsAdvertisingPlan.forProfile(profile).appearanceSupported)
        }
    }

    @Test
    fun `native bike mode advertises ftms csc and cycling power within legacy budget`() {
        val plan = FtmsAdvertisingPlan.forProfile(
            FtmsMachineProfile.INDOOR_BIKE,
            includeNativeBikingSensors = true,
        )

        assertEquals(
            listOf(
                BluetoothIds.UUID_FTMS_SERVICE,
                BluetoothIds.UUID_CSC_SERVICE,
                BluetoothIds.UUID_CYCLING_POWER_SERVICE,
            ),
            plan.serviceUuids,
        )
        // 3 (Flags) + 6 ("Bike" name) + 8 (three 16-bit UUIDs) + 7 (service data)
        assertEquals(24, plan.advertisingBytes)
        assertTrue(plan.advertisingBytes <= FtmsAdvertisingPlan.MAX_LEGACY_BYTES)
    }

    @Test
    fun `native bike request is ignored for non bike personas`() {
        val plan = FtmsAdvertisingPlan.forProfile(
            FtmsMachineProfile.INDOOR_ROWER,
            includeNativeBikingSensors = true,
        )

        assertEquals(listOf(BluetoothIds.UUID_FTMS_SERVICE), plan.serviceUuids)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `oversized plans fail before Android advertising is called`() {
        FtmsAdvertisingPlan(
            localName = "too long",
            serviceData = byteArrayOf(1, 2, 3),
            advertisingBytes = 32,
            scanResponseBytes = 0,
        )
    }
}
