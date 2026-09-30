package com.gmail.absolutebollockscreations.gattlibrary.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Compact framing is selected by payload budget, not by vendor.
 *
 * Evidence for the threshold: a 2026-08-06 session where a Connect IQ data field
 * subscribed at MTU 23, was sent roughly 3.4 notification fragments per record
 * for sixteen seconds without ever speaking again, then dropped the link
 * cleanly. FitBridge's own metrics were healthy throughout — staleness under
 * 170 ms, no failed or superseded records — so the link was fine and the client
 * simply could not use what it was being sent.
 *
 * Reassembling FTMS More Data continuations is an obscure corner of the spec
 * that thin clients routinely do not implement, so a client that leaves the MTU
 * near the BLE minimum is told less rather than told it in pieces.
 */
class CompactFramingSelectionTest {

    private fun prefersCompact(mtu: Int): Boolean =
        PeripheralConnection.prefersCompactFramingAt(mtu)

    @Test
    fun `the ble minimum mtu selects compact framing`() {
        assertTrue(prefersCompact(PeripheralConnection.DEFAULT_ATT_MTU))
    }

    @Test
    fun `a token mtu increase is still treated as constrained`() {
        // 27 leaves 24 octets - more than 20, but not enough headroom to trust a
        // populated record to fit, and a client asking for this little is not one
        // to rely on for reassembly either.
        assertTrue(prefersCompact(27))
    }

    @Test
    fun `the threshold itself does not select compact framing`() {
        assertFalse(prefersCompact(PeripheralConnection.COMPACT_FRAMING_MTU_THRESHOLD))
    }

    @Test
    fun `a phone-class mtu uses the full record`() {
        assertFalse(prefersCompact(247))
        assertFalse(prefersCompact(PeripheralConnection.MAX_ATT_MTU))
    }

    @Test
    fun `an unnegotiated link defaults to compact framing`() {
        // Until the client asks for more, the MTU is the BLE minimum. Defaulting
        // to fragmentation would mean every client that never negotiates gets the
        // layout this session proved a client cannot consume.
        assertTrue(prefersCompact(PeripheralConnection.DEFAULT_ATT_MTU))
    }

    @Test
    fun `the encoder budget matches the connection threshold`() {
        // These two are what previously disagreed: the flag was enabled for any
        // low MTU while the encoder only honoured it at exactly 20 octets, so a
        // link at 24-29 would have silently fallen back to fragmentation.
        assertEquals(
            PeripheralConnection.COMPACT_FRAMING_MTU_THRESHOLD -
                PeripheralConnection.ATT_NOTIFY_OVERHEAD,
            FtmsDataEncoders.COMPACT_FRAMING_MAX_PAYLOAD,
        )
    }

    @Test
    fun `every compact-framing link is within the encoder's budget`() {
        // The encoder's packGarminCrossTrainer requires each frame to fit, and
        // throws if it does not. Any MTU the selection rule accepts must
        // therefore leave at least as much room as the encoder expects.
        for (mtu in PeripheralConnection.DEFAULT_ATT_MTU until
            PeripheralConnection.COMPACT_FRAMING_MTU_THRESHOLD) {
            assertTrue("mtu $mtu", prefersCompact(mtu))
            val payload = mtu - PeripheralConnection.ATT_NOTIFY_OVERHEAD
            assertTrue(
                "mtu $mtu leaves $payload octets, encoder budget is " +
                    "${FtmsDataEncoders.COMPACT_FRAMING_MAX_PAYLOAD}",
                payload <= FtmsDataEncoders.COMPACT_FRAMING_MAX_PAYLOAD,
            )
        }
    }
}
