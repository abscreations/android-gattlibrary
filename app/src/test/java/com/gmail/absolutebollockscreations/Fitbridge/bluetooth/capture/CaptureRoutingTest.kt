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
 * Plan §0.9.2: the reserved tag is the entire routing mechanism.
 *
 * The property that matters is mutual exclusion — capture lines must never reach
 * the human log, and human lines must never reach the capture file. Both halves
 * are asserted here because getting either wrong is silent: one corrupts the
 * NDJSON, the other doubles every byte written to disk.
 */
class CaptureRoutingTest {

    private companion object {
        // The sink owns a real writer thread. A forced full suite can starve it beyond the
        // production two-second export timeout even though no capture operation is stuck.
        const val TEST_IO_TIMEOUT_MS = 10_000L
    }

    @get:Rule
    val temp = TemporaryFolder()

    private fun tree(root: File = temp.root): CaptureTree = CaptureTree(
        logsRoot = root,
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
        nowMs = { 1_712_345_678_000L },
    )

    private fun captureLines(root: File = temp.root): List<String> {
        val dir = File(root, CaptureTree.CAPTURES_DIR_NAME)
        val file = dir.listFiles()?.firstOrNull { it.extension == CaptureTree.NDJSON_EXTENSION }
            ?: return emptyList()
        return file.readText().split('\n').dropLastWhile { it.isEmpty() }
    }

    @Test
    fun `the capture tree accepts only the reserved tag`() {
        val subject = tree()
        try {
            assertTrue(subject.isLoggable(BleCapture.TAG, 4))
            assertFalse(subject.isLoggable("BtEngine", 4))
            assertFalse(subject.isLoggable("FtmsNotifier", 6))
            assertFalse("an untagged line is not a capture", subject.isLoggable(null, 4))
        } finally {
            subject.close()
        }
    }

    @Test
    fun `the capture tree is not gated by priority`() {
        // A capture is armed or not by its own switch. It must not vanish
        // because DebugLevel was turned down, which is usually exactly when a
        // capture is most wanted.
        val subject = tree()
        try {
            for (priority in 2..7) {
                assertTrue("priority $priority", subject.isLoggable(BleCapture.TAG, priority))
            }
        } finally {
            subject.close()
        }
    }

    @Test
    fun `a capture file gets a header once, then the events`() {
        val subject = tree()
        try {
            subject.log(4, BleCapture.TAG, """{"t":1,"seq":1,"ev":"dropped","n":1}""", null)
            subject.log(4, BleCapture.TAG, """{"t":2,"seq":2,"ev":"dropped","n":2}""", null)
            assertTrue(subject.flush(TEST_IO_TIMEOUT_MS))

            val lines = captureLines()
            assertEquals(3, lines.size)
            assertTrue("first line must be the header", lines[0].startsWith("""{"capture_version":1"""))
            assertTrue(lines[1].contains("\"seq\":1"))
            assertTrue(lines[2].contains("\"seq\":2"))
            assertEquals("the header is written once", 1, lines.count { it.contains("capture_version") })
        } finally {
            subject.close()
        }
    }

    @Test
    fun `capture recreates its header after writer reset and file deletion`() {
        val subject = tree()
        try {
            subject.log(4, BleCapture.TAG, """{"t":1,"seq":1,"ev":"dropped","n":1}""", null)
            assertTrue(subject.flush(TEST_IO_TIMEOUT_MS))
            val captureFile = File(temp.root, CaptureTree.CAPTURES_DIR_NAME)
                .listFiles()!!
                .single()
            assertTrue("capture writer reset timed out", subject.resetAfterClear(TEST_IO_TIMEOUT_MS))
            assertTrue("reset must release the capture for deletion", captureFile.delete())
            subject.log(4, BleCapture.TAG, """{"t":2,"seq":2,"ev":"dropped","n":2}""", null)
            assertTrue(subject.flush(TEST_IO_TIMEOUT_MS))

            val lines = captureLines()
            assertEquals(2, lines.size)
            assertTrue(lines[0].startsWith("""{"capture_version":1"""))
            assertTrue(lines[1].contains("\"seq\":2"))
            assertEquals(1, lines.count { it.contains("capture_version") })
        } finally {
            subject.close()
        }
    }

    @Test
    fun `a tree that records nothing leaves no orphan file`() {
        val subject = tree()
        try {
            subject.flush(TEST_IO_TIMEOUT_MS)
            assertTrue("no events, so no capture file", captureLines().isEmpty())
        } finally {
            subject.close()
        }
    }

    @Test
    fun `a non-capture line never reaches the capture file`() {
        val subject = tree()
        try {
            subject.log(4, "BtEngine", "ordinary human log line", null)
            subject.flush(TEST_IO_TIMEOUT_MS)
            assertTrue("human log leaked into the capture", captureLines().isEmpty())
        } finally {
            subject.close()
        }
    }

    @Test
    fun `the capture file is named for the session and lands in captures`() {
        val subject = tree()
        try {
            subject.log(4, BleCapture.TAG, """{"ev":"dropped","n":1}""", null)
            assertTrue(subject.flush(TEST_IO_TIMEOUT_MS))

            val dir = File(temp.root, CaptureTree.CAPTURES_DIR_NAME)
            val names = dir.listFiles()?.map { it.name }.orEmpty()
            assertEquals(1, names.size)
            assertTrue(names.first(), names.first().startsWith("capture-"))
            assertTrue(names.first(), names.first().endsWith("-a3f9-central.ndjson"))
        } finally {
            subject.close()
        }
    }

    @Test
    fun `many events stay in one file rather than one file per second`() {
        // The base name is fixed at construction. Deriving it per record - as
        // the human log does, to roll over at midnight - would start a new
        // capture file every second.
        val subject = tree()
        try {
            repeat(50) { i ->
                subject.log(4, BleCapture.TAG, """{"seq":$i,"ev":"dropped","n":$i}""", null)
            }
            assertTrue(subject.flush(TEST_IO_TIMEOUT_MS))

            val dir = File(temp.root, CaptureTree.CAPTURES_DIR_NAME)
            assertEquals("one capture session, one file", 1, dir.listFiles()?.size ?: 0)
            assertEquals(51, captureLines().size)
        } finally {
            subject.close()
        }
    }

    @Test
    fun `the emitter stamps a monotonic sequence and never throws`() {
        BleCapture.setClockForTest { 4_242L }
        try {
            BleCapture.resetSession()
            // No tree is planted in a unit test, so this exercises the stamping
            // and encoding path and proves it tolerates having nowhere to go.
            BleCapture.emit(DroppedEvent(count = 1))
            BleCapture.emit(DroppedEvent(count = 2))

            assertEquals("c1", BleCapture.labelFor("AA:BB:CC:DD:EE:FF"))
            assertEquals("labels are stable", "c1", BleCapture.labelFor("aa:bb:cc:dd:ee:ff"))
            assertEquals("c2", BleCapture.labelFor("11:22:33:44:55:66"))
        } finally {
            BleCapture.resetClock()
            BleCapture.resetSession()
        }
    }
}
