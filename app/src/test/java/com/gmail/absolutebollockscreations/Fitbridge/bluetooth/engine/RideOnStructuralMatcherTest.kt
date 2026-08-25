package com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine

import android.bluetooth.BluetoothGattCharacteristic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RideOnStructuralMatcherTest {
    private val notify = BluetoothGattCharacteristic.PROPERTY_NOTIFY
    private val writeNoResponse = BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE
    private val indicate = BluetoothGattCharacteristic.PROPERTY_INDICATE

    private fun inventory(
        service: java.util.UUID = RideOnUuids.ORIGINAL_SERVICE,
        asyncProperties: Int = notify,
        rxProperties: Int = writeNoResponse,
        txProperties: Int = indicate,
    ) = RideOnGattInventory(
        mapOf(
            service to mapOf(
                RideOnUuids.ASYNC_TX to asyncProperties,
                RideOnUuids.SYNC_RX to rxProperties,
                RideOnUuids.SYNC_TX to txProperties,
            ),
        ),
    )

    @Test
    fun `both exact service forms match the complete surface`() {
        assertEquals(RideOnUuids.ORIGINAL_SERVICE, matchRideOnGatt(inventory())?.serviceUuid)
        assertEquals(
            RideOnUuids.CORE2_SERVICE,
            matchRideOnGatt(inventory(RideOnUuids.CORE2_SERVICE))?.serviceUuid,
        )
    }

    @Test
    fun `required properties tolerate unrelated additional properties`() {
        assertNotNull(matchRideOnGatt(inventory(
            asyncProperties = notify or BluetoothGattCharacteristic.PROPERTY_READ,
            rxProperties = writeNoResponse or BluetoothGattCharacteristic.PROPERTY_WRITE,
            txProperties = indicate or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
        )))
    }

    @Test
    fun `partial and wrong-property surfaces fail closed`() {
        val complete = inventory().services.getValue(RideOnUuids.ORIGINAL_SERVICE)
        for (missing in complete.keys) {
            assertNull(matchRideOnGatt(RideOnGattInventory(
                mapOf(RideOnUuids.ORIGINAL_SERVICE to (complete - missing)),
            )))
        }
        assertNull(matchRideOnGatt(inventory(asyncProperties = BluetoothGattCharacteristic.PROPERTY_READ)))
        assertNull(matchRideOnGatt(inventory(rxProperties = BluetoothGattCharacteristic.PROPERTY_WRITE)))
        assertNull(matchRideOnGatt(inventory(txProperties = BluetoothGattCharacteristic.PROPERTY_NOTIFY)))
    }

    @Test
    fun `characteristics split across parents do not form a union match`() {
        assertNull(matchRideOnGatt(RideOnGattInventory(
            mapOf(
                RideOnUuids.ORIGINAL_SERVICE to mapOf(
                    RideOnUuids.ASYNC_TX to notify,
                    RideOnUuids.SYNC_RX to writeNoResponse,
                ),
                RideOnUuids.CORE2_SERVICE to mapOf(RideOnUuids.SYNC_TX to indicate),
            ),
        )))
    }

    @Test
    fun `handler is reachable but hardware allowlist keeps candidate inactive`() {
        assertTrue(ProtocolKey.RIDEON in MACHINE_PROTOCOLS)
        assertTrue(HandlerFactory.standard(false).create(ProtocolKey.RIDEON) is RideOnProtocolHandler)
        val match = matchRideOnGatt(inventory())!!
        assertNull(rideOnAdapterCandidate(match, "KICKR CORE 2", "unknown", 3_000))
        assertFalse(RideOnActivationPolicy.isQualified(null, null))
        assertEquals(90, AdapterLivenessCoordinator.RANK_RIDEON)
    }
}
