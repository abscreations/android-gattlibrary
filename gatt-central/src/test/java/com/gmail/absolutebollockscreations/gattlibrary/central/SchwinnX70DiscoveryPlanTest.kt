package com.gmail.absolutebollockscreations.gattlibrary.central

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class SchwinnX70DiscoveryPlanTest {
    private fun inventory(vararg characteristics: UUID): Map<UUID, Set<UUID>> =
        mapOf(BluetoothIds.UUID_X70_SERVICE to characteristics.toSet())

    @Test
    fun `a console offering only the main stream subscribes to it alone`() {
        // Captured logs show every x70 console emitting all three streams, so this tests a
        // partial GATT table, not a model of a 270.
        val targets = planSchwinnX70Discovery(
            inventory(BluetoothIds.UUID_X70_MEASUREMENT, BluetoothIds.UUID_X70_CONTROL),
        )
        assertEquals(listOf(BluetoothIds.UUID_X70_MEASUREMENT), targets.map { it.characteristicUuid })
    }

    @Test
    fun `a console offering only the paired streams subscribes to those and no more`() {
        val targets = planSchwinnX70Discovery(
            inventory(
                BluetoothIds.UUID_X70_SUPPLEMENTAL,
                BluetoothIds.UUID_X70_SIBLING,
                BluetoothIds.UUID_X70_CONTROL,
            ),
        )
        assertEquals(
            listOf(BluetoothIds.UUID_X70_SUPPLEMENTAL, BluetoothIds.UUID_X70_SIBLING),
            targets.map { it.characteristicUuid },
        )
    }

    @Test
    fun `a console exposing all three streams gets all three`() {
        // The 270's GATT database declares every characteristic even though it only notifies on
        // one. The inventory therefore cannot distinguish console models.
        val targets = planSchwinnX70Discovery(
            inventory(
                BluetoothIds.UUID_X70_MEASUREMENT,
                BluetoothIds.UUID_X70_SUPPLEMENTAL,
                BluetoothIds.UUID_X70_SIBLING,
            ),
        )
        assertEquals(3, targets.size)
    }

    @Test
    fun `a service with no measurement stream activates nothing`() {
        // A service UUID is evidence of identity, not a promise of data.
        assertTrue(planSchwinnX70Discovery(inventory(BluetoothIds.UUID_X70_CONTROL)).isEmpty())
        assertTrue(planSchwinnX70Discovery(emptyMap()).isEmpty())
        assertTrue(
            "a foreign service must not activate this handler",
            planSchwinnX70Discovery(mapOf(BluetoothIds.UUID_FTMS_SERVICE to setOf(BluetoothIds.UUID_X70_MEASUREMENT)))
                .isEmpty(),
        )
    }

    @Test
    fun `every planned target names the x70 service as its parent`() {
        // Queue targeting keys on the parent, so a wrong service would target another CCCD.
        val targets = planSchwinnX70Discovery(
            inventory(
                BluetoothIds.UUID_X70_MEASUREMENT,
                BluetoothIds.UUID_X70_SUPPLEMENTAL,
                BluetoothIds.UUID_X70_SIBLING,
            ),
        )
        assertTrue(targets.all { it.serviceUuid == BluetoothIds.UUID_X70_SERVICE })
    }
}
