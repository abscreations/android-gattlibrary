package com.gmail.absolutebollockscreations.Fitbridge.bluetooth.capture

import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Plan §0.9.6: personal data must be **impossible** to log, not scrubbed on the
 * way out.
 *
 * These tests exercise the mechanism. The full end-to-end exercise through a
 * real profile-write sequence is a Phase 6 obligation (§0.9.9), because the Life
 * Fitness user-service characteristics are discovered at runtime and do not
 * exist yet.
 */
class CaptureRedactionTest {

    private val ordinary: UUID = UUID.fromString("00002ad9-0000-1000-8000-00805f9b34fb")
    private val vendorToken: UUID = UUID.fromString("5da54cf2-9cb2-11e5-8994-feff819cdc9f")

    /** Bytes that must never appear in a capture, in any encoding. */
    private val secret = "jeff@example.com".toByteArray()

    private fun encode(event: CaptureEvent): String =
        CaptureJson.encode(CaptureRecord(atMs = 1L, seq = 1L, event = event))

    @Test
    fun `an ordinary write keeps its payload`() {
        val event = WriteEvent.of(
            conn = "c1",
            characteristic = ordinary,
            payload = byteArrayOf(0x00),
            withoutResponse = false,
        )

        assertTrue("expected a payload-carrying write", event is WriteEvent)
        assertTrue(encode(event).contains("\"hex\":\"00\""))
    }

    @Test
    fun `a registered sensitive characteristic cannot produce a payload-carrying event`() {
        val event = WriteEvent.of(
            conn = "c1",
            characteristic = vendorToken,
            payload = secret,
            withoutResponse = false,
        )

        assertTrue("expected redaction", event is RedactedWriteEvent)
        val line = encode(event)
        assertFalse("payload leaked: $line", line.contains("hex"))
        assertTrue(line, line.contains("\"redacted\":\"vendor_token\""))
        assertTrue("length is safe and useful", line.contains("\"len\":${secret.size}"))
    }

    @Test
    fun `every registered characteristic redacts`() {
        // Enumerated rather than spot-checked, so adding a UUID to the registry
        // without it taking effect fails here.
        for ((uuid, reason) in SensitiveCharacteristics.all()) {
            val event = WriteEvent.of("c1", uuid, secret, withoutResponse = false)
            assertTrue("$uuid produced a payload-carrying event", event is RedactedWriteEvent)
            assertEquals(reason, (event as RedactedWriteEvent).reason)
        }
    }

    @Test
    fun `a caller can declare redaction for a runtime-discovered characteristic`() {
        // The Life Fitness user service case: the characteristic UUID is not
        // known at build time, so the call site declares intent instead.
        val event = WriteEvent.of(
            conn = "c2",
            characteristic = ordinary,
            payload = secret,
            withoutResponse = false,
            redactAs = RedactionReason.USER_PROFILE,
        )

        assertTrue(event is RedactedWriteEvent)
        assertFalse(encode(event).contains("hex"))
    }

    @Test
    fun `a caller cannot opt out of the registry`() {
        // redactAs is a way to add redaction, never to remove it.
        val event = WriteEvent.of(
            conn = "c1",
            characteristic = vendorToken,
            payload = secret,
            withoutResponse = false,
            redactAs = null,
        )

        assertTrue(event is RedactedWriteEvent)
    }

    @Test
    fun `no secret byte survives in any redacted encoding`() {
        val hex = secret.joinToString("") { "%02x".format(it) }
        val line = encode(
            WriteEvent.of("c1", vendorToken, secret, withoutResponse = true),
        )

        assertFalse("hex leak", line.contains(hex))
        assertFalse("ascii leak", line.contains("jeff@example.com"))
    }

    @Test
    fun `connection labels are session-scoped and never a MAC`() {
        val labels = ConnectionLabels()
        val first = labels.labelFor("42:C0:2D:90:2F:4E")
        val second = labels.labelFor("AA:BB:CC:DD:EE:FF")

        assertEquals("c1", first)
        assertEquals("c2", second)
        assertEquals("labels are stable within a session", "c1", labels.labelFor("42:c0:2d:90:2f:4e"))
        assertNull(labels.existingLabel("11:22:33:44:55:66"))
    }

    @Test
    fun `a cleared label set restarts numbering, carrying nothing across sessions`() {
        // D14: nothing about a device is persisted between sessions, so there is
        // no cross-session identity for a capture to leak.
        val labels = ConnectionLabels()
        labels.labelFor("42:C0:2D:90:2F:4E")
        labels.clear()

        assertNull(labels.existingLabel("42:C0:2D:90:2F:4E"))
        assertEquals("c1", labels.labelFor("AA:BB:CC:DD:EE:FF"))
    }

    @Test
    fun `no event body can carry a MAC address because none accepts one`() {
        // Every conn field is a label produced by ConnectionLabels. This test
        // documents the invariant at the point it would be violated: if someone
        // passes an address where a label belongs, the capture is compromised.
        val labels = ConnectionLabels()
        val line = encode(ConnectEvent(conn = labels.labelFor("42:C0:2D:90:2F:4E"), generation = 1))

        assertFalse(line, line.contains("42:C0"))
        assertFalse(line, line.contains("42:c0"))
        assertTrue(line, line.contains("\"conn\":\"c1\""))
    }
}
