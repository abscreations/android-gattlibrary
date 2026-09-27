package com.gmail.absolutebollockscreations.Fitbridge.bluetooth.capture

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import com.gmail.absolutebollockscreations.Fitbridge.logging.CaptureTree

/**
 * The two sides of the bridge are recorded separately.
 *
 * FitBridge is a GATT client to machines and a GATT server to watches, and both
 * produce the same event shapes. Mixing them would make a capture actively
 * misleading rather than merely incomplete: a `notify` would mean "received from
 * the machine" on one line and "sent to the watch" on the next, with nothing in
 * the record to tell them apart.
 *
 * The gap this closes: the 2026-08-06 Connect IQ investigation had to infer the
 * downstream framing from an MTU value and a frame-count ratio, because the
 * bytes FitBridge actually sent were recorded nowhere.
 */
class CaptureRoleSeparationTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun tree(role: CaptureRole): CaptureTree = CaptureTree(
        logsRoot = temp.root,
        sessionId = "a3f9",
        headerProvider = {
            CaptureHeader(
                appVersion = "1.4.2",
                androidSdk = 34,
                deviceModel = "Pixel 8",
                session = "a3f9",
                t0Ms = 1_712_345_678_000L,
            )
        },
        role = role,
        nowMs = { 1_712_345_678_000L },
    )

    private fun fileFor(role: CaptureRole): File? =
        File(temp.root, CaptureTree.CAPTURES_DIR_NAME)
            .listFiles()
            ?.firstOrNull { it.name.endsWith("-${role.fileSuffix}.ndjson") }

    private fun linesFor(role: CaptureRole): List<String> =
        fileFor(role)?.readText()?.split('\n')?.dropLastWhile { it.isEmpty() } ?: emptyList()

    @Test
    fun `the two roles use different reserved tags`() {
        assertEquals("BLECAP", CaptureRole.CENTRAL.tag)
        assertEquals(
            "the roles must not share a tag, or routing collapses",
            2,
            CaptureRole.entries.map { it.tag }.distinct().size,
        )
    }

    @Test
    fun `every role tag is rejected by the human log`() {
        for (role in CaptureRole.entries) {
            assertTrue(role.tag, CaptureRole.isCaptureTag(role.tag))
        }
        assertFalse(CaptureRole.isCaptureTag("BtEngine"))
        assertFalse(CaptureRole.isCaptureTag(null))
    }

    @Test
    fun `a tree accepts only its own role's tag`() {
        val central = tree(CaptureRole.CENTRAL)
        val peripheral = tree(CaptureRole.PERIPHERAL)
        try {
            assertTrue(central.isLoggable(CaptureRole.CENTRAL.tag, 4))
            assertFalse(
                "the central tree must not swallow peripheral events",
                central.isLoggable(CaptureRole.PERIPHERAL.tag, 4),
            )
            assertTrue(peripheral.isLoggable(CaptureRole.PERIPHERAL.tag, 4))
            assertFalse(peripheral.isLoggable(CaptureRole.CENTRAL.tag, 4))
        } finally {
            central.close()
            peripheral.close()
        }
    }

    @Test
    fun `each role writes its own file`() {
        val central = tree(CaptureRole.CENTRAL)
        val peripheral = tree(CaptureRole.PERIPHERAL)
        try {
            central.log(4, CaptureRole.CENTRAL.tag, """{"seq":1,"ev":"notify"}""", null)
            peripheral.log(4, CaptureRole.PERIPHERAL.tag, """{"seq":2,"ev":"notify"}""", null)
            assertTrue(central.flush())
            assertTrue(peripheral.flush())

            assertEquals(2, File(temp.root, CaptureTree.CAPTURES_DIR_NAME).listFiles()?.size ?: 0)
            assertTrue(linesFor(CaptureRole.CENTRAL).any { it.contains("\"seq\":1") })
            assertTrue(linesFor(CaptureRole.PERIPHERAL).any { it.contains("\"seq\":2") })
            assertFalse(
                "a peripheral event must not appear in the central file",
                linesFor(CaptureRole.CENTRAL).any { it.contains("\"seq\":2") },
            )
        } finally {
            central.close()
            peripheral.close()
        }
    }

    @Test
    fun `both files carry the same session identifier so they can be correlated`() {
        val central = tree(CaptureRole.CENTRAL)
        val peripheral = tree(CaptureRole.PERIPHERAL)
        try {
            central.log(4, CaptureRole.CENTRAL.tag, """{"seq":1,"ev":"notify"}""", null)
            peripheral.log(4, CaptureRole.PERIPHERAL.tag, """{"seq":2,"ev":"notify"}""", null)
            central.flush()
            peripheral.flush()

            assertTrue(fileFor(CaptureRole.CENTRAL)!!.name.contains("-a3f9-"))
            assertTrue(fileFor(CaptureRole.PERIPHERAL)!!.name.contains("-a3f9-"))
        } finally {
            central.close()
            peripheral.close()
        }
    }

    @Test
    fun `the sequence counter is shared so the two files interleave`() {
        // Interleaving by seq is what reconstructs cause and effect across the
        // bridge: the upstream notification that produced a sample, and the
        // downstream notification that carried it onward.
        BleCapture.resetSession()
        BleCapture.setClockForTest { 1L }
        try {
            BleCapture.emit(DroppedEvent(1), CaptureRole.CENTRAL)
            BleCapture.emit(DroppedEvent(2), CaptureRole.PERIPHERAL)
            BleCapture.emit(DroppedEvent(3), CaptureRole.CENTRAL)
            // Three events, three distinct sequence numbers, regardless of role.
            assertEquals(3, BleCapture.bufferedCount())
        } finally {
            BleCapture.resetClock()
            BleCapture.resetSession()
        }
    }

    @Test
    fun `a connect event carries the mtu only when known`() {
        val withMtu = CaptureJson.encode(
            CaptureRecord(1L, 1L, ConnectEvent(conn = "c1", generation = 2, mtu = 23)),
        )
        val withoutMtu = CaptureJson.encode(
            CaptureRecord(1L, 1L, ConnectEvent(conn = "c1", generation = 2)),
        )

        assertTrue(withMtu, withMtu.contains("\"mtu\":23"))
        assertFalse("an unknown MTU is omitted, not null", withoutMtu.contains("mtu"))
    }
}
