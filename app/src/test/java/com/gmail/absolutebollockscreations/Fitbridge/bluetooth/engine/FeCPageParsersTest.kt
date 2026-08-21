package com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Page-decode tests for [FeCPageParsers], per plan §16.3-16.5.
 *
 * Vectors are hand-built from the field layouts in
 * `docs/fe-c-ble_central_implementation.md` §2.4 (itself sourced from the ANT+ FE-C bicycle
 * power/fitness equipment device profile), not reverse-engineered from any implementation.
 */
class FeCPageParsersTest {

    private fun bytes(vararg v: Int) = ByteArray(v.size) { (v[it] and 0xFF).toByte() }

    @Test
    fun `decodes page 16 General FE Data`() {
        // equipment=25 (Trainer), elapsed raw=40 (10.0s), distance raw=120m, speed=5000mm/s,
        // hr=145, capabilities=0x34 (distance-enabled bit2 set, equipment-state=IN_USE in bits4-6)
        val data = bytes(0x10, 25, 40, 120, 0x88, 0x13, 145, 0x34)
        val page = FeCPageParsers.decodePage(data) as FeCPage.GeneralFe

        assertEquals(25, page.equipmentType)
        assertEquals(40, page.elapsedTimeRaw)
        assertEquals(120, page.distanceTraveledRaw)
        assertTrue(page.distanceTraveledEnabled)
        assertEquals(5.0, page.speedMetersPerSecond, 1e-9)
        assertEquals(145, page.heartRateBpm)
        assertEquals(FeCEquipmentState.IN_USE, page.equipmentState)
    }

    @Test
    fun `page 16 treats HR 0xFF and distance-disabled correctly`() {
        // equipment=21 (Stationary Bike), elapsed raw=250, distance raw=5, speed=5000mm/s,
        // hr=0xFF (unavailable), capabilities=0x00 (distance disabled, equipment-state unknown/0)
        val data = bytes(0x10, 21, 250, 5, 0x88, 0x13, 0xFF, 0x00)
        val page = FeCPageParsers.decodePage(data) as FeCPage.GeneralFe

        assertEquals(21, page.equipmentType)
        assertFalse(page.distanceTraveledEnabled)
        assertNull(page.heartRateBpm)
        assertNull(page.equipmentState) // wire value 0 is reserved, not a defined state
    }

    @Test
    fun `decodes page 25 Specific Trainer Data`() {
        // eventCount=10, cadence=85, accumulatedPower=1200, instPower raw=250 (low=0xFA,
        // high nibble=0), trainerStatus=5 (byte6 high nibble), targetPowerStatus=1,
        // equipmentState=IN_USE(3) in byte7 bits4-6 -> byte7=0x31
        val data = bytes(0x19, 0x0A, 0x55, 0xB0, 0x04, 0xFA, 0x50, 0x31)
        val page = FeCPageParsers.decodePage(data) as FeCPage.TrainerData

        assertEquals(10, page.eventCountRaw)
        assertEquals(85, page.cadenceRpm)
        assertEquals(1200, page.accumulatedPowerRaw)
        assertEquals(250, page.instantaneousPowerWatts)
        assertEquals(5, page.trainerStatus)
        assertEquals(1, page.targetPowerStatus)
        assertEquals(FeCEquipmentState.IN_USE, page.equipmentState)
    }

    @Test
    fun `page 25 treats cadence 0xFF and power 0xFFF as unavailable`() {
        val data = bytes(0x19, 0xC8, 0xFF, 0xFF, 0xFF, 0xFF, 0x0F, 0x00)
        val page = FeCPageParsers.decodePage(data) as FeCPage.TrainerData

        assertNull(page.cadenceRpm)
        assertNull(page.instantaneousPowerWatts)
    }

    @Test
    fun `decodes page 81 Product Information with unsigned serial`() {
        // serial = 0x89ABCDEF, which is >= 0x80000000: a signed 32-bit read (Incyclist's bug)
        // would turn this negative. It must not.
        val data = bytes(0x51, 0xFF, 0x01, 0x02, 0xEF, 0xCD, 0xAB, 0x89)
        val page = FeCPageParsers.decodePage(data) as FeCPage.ProductInformation

        assertEquals(1, page.supplementalSoftwareRevision)
        assertEquals(2, page.mainSoftwareRevision)
        assertEquals(0x89ABCDEFL, page.serialNumber)
        assertTrue(page.serialNumber!! > 0)
    }

    @Test
    fun `page 81 treats 0xFFFFFFFF serial as unavailable`() {
        val data = bytes(0x51, 0xFF, 0x00, 0x00, 0xFF, 0xFF, 0xFF, 0xFF)
        val page = FeCPageParsers.decodePage(data) as FeCPage.ProductInformation
        assertNull(page.serialNumber)
    }

    @Test
    fun `unrecognized page decodes as Unknown`() {
        val data = bytes(0x05, 1, 2, 3, 4, 5, 6, 7)
        val page = FeCPageParsers.decodePage(data)
        assertEquals(FeCPage.Unknown(5), page)
    }

    @Test
    fun `empty data decodes as Unknown`() {
        val page = FeCPageParsers.decodePage(ByteArray(0))
        assertEquals(FeCPage.Unknown(-1), page)
    }
}
