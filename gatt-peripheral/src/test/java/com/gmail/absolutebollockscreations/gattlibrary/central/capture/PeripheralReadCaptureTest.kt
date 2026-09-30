package com.gmail.absolutebollockscreations.gattlibrary.central.capture

import com.gmail.absolutebollockscreations.gattlibrary.peripheral.PeripheralCaptureHooks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.gmail.absolutebollockscreations.gattlibrary.core.capture.CaptureJson
import com.gmail.absolutebollockscreations.gattlibrary.core.capture.CaptureRecord
import com.gmail.absolutebollockscreations.gattlibrary.core.capture.CaptureRole
import com.gmail.absolutebollockscreations.gattlibrary.core.capture.ReadEvent

/**
 * Downstream reads are recorded.
 *
 * ### The gap this closes
 *
 * [PeripheralCaptureHooks.clientRead] existed from §0.9.4 but nothing called it
 * until 2026-08-06, so every capture recorded what a client *wrote* and nothing
 * about what it had been *told*.
 *
 * The cost was concrete. In the 2026-08-06 inclination investigation the watch
 * wrote targets of 0.0 %, 5.0 % and 110.0 % for requested values of 1.0, 3.0 and
 * 10.0. Those writes were in the capture; the Supported Inclination Range that
 * caused them - advertised ten times too wide - was not, because it left
 * FitBridge as a read response. The advertisement had to be reconstructed by
 * reading source. A read is half of any downstream negotiation, and a capture
 * holding only the other half invites exactly the wrong conclusion: the first
 * hypothesis from those writes was that the peripheral data encoding was at
 * fault, which it was not.
 */
class PeripheralReadCaptureTest {

    @Test
    fun `only the first fragment of a blob read is recorded`() {
        // A client fetching a value longer than the ATT payload issues a Read
        // Request then Read Blob Requests at rising offsets. Recording each one
        // would put the same value in the file several times, in pieces, with
        // nothing marking them as one value.
        assertTrue("the initial read is recorded", PeripheralCaptureHooks.shouldCaptureRead(0))
        assertFalse("a blob continuation is not", PeripheralCaptureHooks.shouldCaptureRead(1))
        assertFalse(PeripheralCaptureHooks.shouldCaptureRead(20))
        assertFalse(PeripheralCaptureHooks.shouldCaptureRead(512))
    }

    @Test
    fun `a negative offset is treated as an initial read rather than dropped`() {
        // The framework should never produce one, but losing the record of a
        // read is worse than recording an odd one: a missing read is invisible,
        // whereas a surprising offset is at least a question someone can ask.
        assertTrue(PeripheralCaptureHooks.shouldCaptureRead(-1))
    }

    @Test
    fun `a read event carries the characteristic and the value the client was given`() {
        // The whole point is that the advertised value is in the file. A read
        // event with no payload would have been just as useless as no event.
        val event = ReadEvent(
            conn = "c2",
            characteristic = "00002ad5-0000-1000-8000-00805f9b34fb",
            payload = byteArrayOf(0x9C.toByte(), 0xFF.toByte(), 0x2C, 0x01, 0x05, 0x00),
        )

        assertEquals("read", event.ev)
        assertEquals(6, event.payload.size)

        val line = CaptureJson.encode(
            CaptureRecord(
                atMs = 1_712_345_678_000L,
                seq = 7,
                event = event,
                role = CaptureRole.PERIPHERAL,
            ),
        )
        // -100 / 300 / 5 in 0.1 % units: the real Supported Inclination Range,
        // which is what should have been visible all along.
        assertTrue("the payload must be in the line", line.contains("9cff2c010500"))
        assertTrue(line.contains("\"ev\":\"read\""))
        assertTrue(line.contains("00002ad5-0000-1000-8000-00805f9b34fb"))
    }
}
