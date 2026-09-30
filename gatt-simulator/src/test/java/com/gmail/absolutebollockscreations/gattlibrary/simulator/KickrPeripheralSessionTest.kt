package com.gmail.absolutebollockscreations.gattlibrary.simulator

import com.gmail.absolutebollockscreations.gattlibrary.central.RideOnProtoCodec
import com.gmail.absolutebollockscreations.gattlibrary.core.vendor.RideOnServiceForm
import com.gmail.absolutebollockscreations.gattlibrary.central.RideOnUuids
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.gmail.absolutebollockscreations.gattlibrary.peripheral.PeripheralConnection
import com.gmail.absolutebollockscreations.gattlibrary.core.vendor.RideOnSpec

class KickrPeripheralSessionTest {
    @Test
    fun `Zwift Click is unavailable on a persona with no zwiftServiceForm`() {
        val session = KickrPeripheralSession(generation = 1, model = KickrSyntheticModel())
        assertNull(session.zwift)
        assertFalse(session.sendZwiftClickShift(ZwiftClickDirection.UP))
        assertEquals(0, session.pendingCount)
    }

    @Test
    fun `Zwift Click is rejected before the RideOn connection has negotiated`() {
        // zwiftServiceForm is set (this persona does carry a RideOn connection to ride Click on),
        // but nothing has negotiated it yet - controlReady is false, the same gate
        // enqueueZwiftTelemetry() uses.
        val session = KickrPeripheralSession(
            generation = 1,
            model = KickrSyntheticModel(),
            zwiftServiceForm = RideOnServiceForm.ORIGINAL,
        )
        session.subscribe(RideOnSpec.ASYNC_TX, PeripheralConnection.CccdMode.NOTIFY)
        session.subscribe(RideOnSpec.SYNC_TX, PeripheralConnection.CccdMode.INDICATE)
        assertFalse(session.sendZwiftClickShift(ZwiftClickDirection.UP))
        assertEquals(0, session.pendingCount)
    }

    @Test
    fun `Zwift Click is rejected while negotiated but not both TX characteristics are subscribed`() {
        val session = ready()
        session.subscribe(RideOnSpec.SYNC_TX, PeripheralConnection.CccdMode.DISABLED)
        assertFalse(session.sendZwiftClickShift(ZwiftClickDirection.UP))
    }

    @Test
    fun `Zwift Click UP enqueues a pressed frame then a released frame, both on notify`() {
        val session = ready()
        assertTrue(session.sendZwiftClickShift(ZwiftClickDirection.UP))
        assertEquals(2, session.pendingCount)

        val pressed = session.beginNext()!!
        assertEquals(RideOnSpec.ASYNC_TX, pressed.characteristicUuid)
        assertFalse(pressed.indicate)
        assertArrayEquals(
            RideOnProtoCodec.clickKeyPadStatus(upPressed = true, downPressed = false),
            pressed.value,
        )
        session.completeInFlight()

        val released = session.beginNext()!!
        assertArrayEquals(
            RideOnProtoCodec.clickKeyPadStatus(upPressed = false, downPressed = false),
            released.value,
        )
    }

    @Test
    fun `Zwift Click DOWN reports Button_Minus pressed, not Button_Plus`() {
        val session = ready()
        assertTrue(session.sendZwiftClickShift(ZwiftClickDirection.DOWN))
        val pressed = session.beginNext()!!
        assertArrayEquals(
            RideOnProtoCodec.clickKeyPadStatus(upPressed = false, downPressed = true),
            pressed.value,
        )
    }

    /** A fresh session with both RideOn TX characteristics subscribed and negotiated to READY. */
    private fun ready(): KickrPeripheralSession {
        val session = KickrPeripheralSession(
            generation = 1,
            model = KickrSyntheticModel(),
            zwiftServiceForm = RideOnServiceForm.ORIGINAL,
        )
        session.subscribe(RideOnSpec.ASYNC_TX, PeripheralConnection.CccdMode.NOTIFY)
        session.subscribe(RideOnSpec.SYNC_TX, PeripheralConnection.CccdMode.INDICATE)
        check(session.handleZwiftControl(RideOnSpec.HANDSHAKE_ORIGINAL)?.accepted == true)
        check(session.handleZwiftControl(hex("00 08 00"))?.accepted == true)
        // Draining the negotiation's own frames (handshake ack + 2 status frames + general-info
        // reply) so pendingCount below reflects only what sendZwiftClickShift() itself enqueues.
        while (session.beginNext() != null) session.completeInFlight()
        return session
    }

    private fun hex(value: String): ByteArray = value.split(' ').map { it.toInt(16).toByte() }.toByteArray()
}
