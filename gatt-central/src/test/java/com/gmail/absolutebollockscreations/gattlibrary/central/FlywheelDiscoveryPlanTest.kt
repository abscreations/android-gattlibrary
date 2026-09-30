package com.gmail.absolutebollockscreations.gattlibrary.central

import android.bluetooth.BluetoothGattCharacteristic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID
import com.gmail.absolutebollockscreations.gattlibrary.core.ids.FlywheelUuids

class FlywheelDiscoveryPlanTest {
    private val notify = BluetoothGattCharacteristic.PROPERTY_NOTIFY
    private val write = BluetoothGattCharacteristic.PROPERTY_WRITE
    private val writeNoResponse = BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE

    @Test
    fun `exact inventory plans one TX subscription and no RX operation`() {
        for (rxProperties in listOf(write, writeNoResponse, write or writeNoResponse)) {
            val plan = planFlywheelDiscovery(FLYWHEEL_CANDIDATE_ID, inventory(rxProperties = rxProperties))
            assertTrue(plan.activate)
            assertNull(plan.failure)
            assertEquals(
                listOf(
                    FlywheelGattOperation(
                        FlywheelGattOperationKind.ENABLE_NOTIFICATIONS,
                        FlywheelUuids.UUID_FLYWHEEL_TX,
                    ),
                ),
                plan.operations,
            )
            assertTrue(plan.operations.none { it.characteristicUuid == FlywheelUuids.UUID_FLYWHEEL_RX })
        }
    }

    @Test
    fun `advertisement identity is required even for a complete generic Nordic UART surface`() {
        val complete = inventory()
        for (candidate in listOf(null, "generic.nordic.uart", "vendor.fec.ble")) {
            val plan = planFlywheelDiscovery(candidate, complete)
            assertFalse(plan.activate)
            assertEquals(FlywheelDiscoveryFailure.ADVERTISEMENT_IDENTITY_NOT_CONFIRMED, plan.failure)
            assertTrue(plan.operations.isEmpty())
        }
    }

    @Test
    fun `missing service and endpoint failures are typed and plan no operation`() {
        val wrongBase = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dccaaa")
        val cases = listOf(
            FlywheelGattInventory(emptyMap()) to FlywheelDiscoveryFailure.MISSING_SERVICE,
            FlywheelGattInventory(
                mapOf(
                    wrongBase to mapOf(
                        FlywheelUuids.UUID_FLYWHEEL_TX to characteristic(notify, true),
                        FlywheelUuids.UUID_FLYWHEEL_RX to characteristic(writeNoResponse, false),
                    ),
                ),
            ) to FlywheelDiscoveryFailure.MISSING_SERVICE,
            inventory(includeTx = false) to FlywheelDiscoveryFailure.MISSING_TX,
            inventory(includeRx = false) to FlywheelDiscoveryFailure.MISSING_RX,
        )
        for ((inventory, expected) in cases) {
            val plan = planFlywheelDiscovery(FLYWHEEL_CANDIDATE_ID, inventory)
            assertFalse(plan.activate)
            assertEquals(expected, plan.failure)
            assertTrue(plan.operations.isEmpty())
        }
    }

    @Test
    fun `wrong properties and missing CCCD reject activation`() {
        val cases = listOf(
            inventory(txProperties = BluetoothGattCharacteristic.PROPERTY_INDICATE) to
                FlywheelDiscoveryFailure.TX_NOT_NOTIFY,
            inventory(txHasCccd = false) to FlywheelDiscoveryFailure.TX_MISSING_CCCD,
            inventory(rxProperties = BluetoothGattCharacteristic.PROPERTY_READ) to
                FlywheelDiscoveryFailure.RX_NOT_WRITABLE,
        )
        for ((inventory, expected) in cases) {
            val plan = planFlywheelDiscovery(FLYWHEEL_CANDIDATE_ID, inventory)
            assertFalse(plan.activate)
            assertEquals(expected, plan.failure)
            assertTrue(plan.operations.isEmpty())
        }
    }

    @Test
    fun `unrelated services and properties do not weaken the exact inventory match`() {
        val extraService = UUID.fromString("12345678-1234-5678-9abc-def012345678")
        val extraCharacteristic = UUID.fromString("87654321-4321-8765-cba9-876543210fed")
        val inventory = inventory(
            txProperties = notify or BluetoothGattCharacteristic.PROPERTY_READ,
            rxProperties = writeNoResponse or BluetoothGattCharacteristic.PROPERTY_READ,
            extras = mapOf(extraService to mapOf(extraCharacteristic to characteristic(notify, true))),
        )

        assertTrue(planFlywheelDiscovery(FLYWHEEL_CANDIDATE_ID, inventory).activate)
    }

    private fun inventory(
        includeTx: Boolean = true,
        includeRx: Boolean = true,
        txProperties: Int = notify,
        txHasCccd: Boolean = true,
        rxProperties: Int = writeNoResponse,
        extras: Map<UUID, Map<UUID, FlywheelCharacteristicInventory>> = emptyMap(),
    ): FlywheelGattInventory {
        val characteristics = linkedMapOf<UUID, FlywheelCharacteristicInventory>()
        if (includeTx) characteristics[FlywheelUuids.UUID_FLYWHEEL_TX] =
            characteristic(txProperties, txHasCccd)
        if (includeRx) characteristics[FlywheelUuids.UUID_FLYWHEEL_RX] =
            characteristic(rxProperties, false)
        return FlywheelGattInventory(
            extras + (FlywheelUuids.UUID_FLYWHEEL_SERVICE to characteristics),
        )
    }

    private fun characteristic(properties: Int, hasCccd: Boolean) =
        FlywheelCharacteristicInventory(properties, hasCccd)
}
