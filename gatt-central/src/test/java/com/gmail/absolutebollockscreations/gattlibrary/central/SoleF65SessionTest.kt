package com.gmail.absolutebollockscreations.gattlibrary.central

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SoleF65SessionTest {
    private fun List<ByteArray>.hex() = map { it.toHexString() }

    /** A non-state notification, such as a command acknowledgement. */
    private val reply = "5b0400074f4b5d".hexToByteArray()
    private val state1 = "5b0203015d".hexToByteArray()
    private val state3 = "5b0203035d".hexToByteArray()

    private val handshake = listOf(
        "5b01f05d",
        "5b0400104f4b5d",
        "5b0203015d", "5b0203015d", "5b0203015d", "5b0203015d",
        "5b0400104f4b5d", "5b0203015d",
        "5b0400094f4b5d",
        "5b0400104f4b5d",
        "5b06070123009baa5d",
        "5b030810015d", "5b030810015d",
        "5b0504000000005d",
        "5b0222095d",
        "5b0202025d", "5b0202025d", "5b0202025d", "5b0202025d",
        "5b0400104f4b5d",
        "5b0400404f4b5d",
    )

    /** Drive the handshake with one notification per wait, as a responsive console would. */
    private fun completeHandshake(session: SoleF65Session, startMs: Long = 0L): List<String> {
        var now = startMs
        val writes = session.start(now).hex().toMutableList()
        while (session.phase == SoleF65Session.Phase.HANDSHAKE) {
            now += 20
            writes += session.onNotification(now, reply).hex()
        }
        return writes
    }

    @Test
    fun `handshake sends the QZ F65 sequence in order and then polls`() {
        val session = SoleF65Session()
        assertEquals(handshake, completeHandshake(session))
        assertEquals(SoleF65Session.Phase.POLLING, session.phase)
        assertEquals(1, session.handshakesCompleted)
        assertEquals(listOf("5b0400104f4b5d"), session.tick(1_000).hex())
        assertEquals(listOf("5b0400064f4b5d"), session.onNotification(1_010, reply).hex())
        assertTrue(session.onNotification(1_020, reply).isEmpty())
        // The next poll waits for the interval measured from the previous poll's start.
        assertTrue(session.tick(1_200).isEmpty())
        assertEquals(listOf("5b0400104f4b5d"), session.tick(1_300).hex())
    }

    @Test
    fun `a silent console still completes the handshake through timeouts`() {
        val session = SoleF65Session()
        val writes = session.start(0).hex().toMutableList()
        var now = 0L
        while (session.phase == SoleF65Session.Phase.HANDSHAKE && now < 120_000) {
            now += 250
            writes += session.tick(now).hex()
        }
        assertEquals(handshake, writes)
        assertEquals(SoleF65Session.Phase.POLLING, session.phase)
        // Twenty write waits of 2 s plus two 0.5 s packet waits, at 250 ms tick resolution.
        assertTrue(now in 41_000L..42_000L)
    }

    @Test
    fun `no start, stop or motion command is ever sent`() {
        val session = SoleF65Session()
        val writes = completeHandshake(session).toMutableList()
        var now = 1_000L
        repeat(200) {
            now += 250
            writes += session.tick(now).hex()
            writes += session.onNotification(now + 5, reply).hex()
        }
        val forbidden = setOf("5b0203045d", "5b0203065d", "5b0203075d")
        assertTrue(writes.none { it in forbidden || it.startsWith("5b02f1") })
    }

    @Test
    fun `any console state frame after the handshake restarts it at the next update, as QZ does`() {
        val session = SoleF65Session()
        completeHandshake(session)
        // A state frame arriving while a poll waits satisfies the wait; the poll pair finishes first.
        assertEquals(listOf("5b0400104f4b5d"), session.tick(1_000).hex())
        assertEquals(listOf("5b0400064f4b5d"), session.onNotification(1_010, state3).hex())
        assertTrue(session.onNotification(1_020, reply).isEmpty())
        // The next update runs the full handshake in place of the poll, even while the belt moves.
        assertTrue(session.tick(1_200).isEmpty())
        assertEquals(listOf("5b01f05d"), session.tick(1_300).hex())
        assertEquals(SoleF65Session.Phase.HANDSHAKE, session.phase)
        var now = 1_300L
        while (session.phase == SoleF65Session.Phase.HANDSHAKE) {
            now += 20
            session.onNotification(now, reply)
        }
        assertEquals(2, session.handshakesCompleted)
        // Unchanged state reports restart too; QZ does not compare states.
        session.onNotification(now + 10, state3)
        assertEquals(listOf("5b01f05d"), session.tick(now + 400).hex())
    }

    @Test
    fun `state echoes during the handshake are cleared when it completes`() {
        val session = SoleF65Session()
        var now = 0L
        session.start(now)
        while (session.phase == SoleF65Session.Phase.HANDSHAKE) {
            now += 20
            session.onNotification(now, state1)
        }
        assertEquals(1, session.handshakesCompleted)
        assertEquals(listOf("5b0400104f4b5d"), session.tick(now + 300).hex())
    }

    @Test
    fun `only an exact five-byte state notification requests a restart`() {
        val session = SoleF65Session()
        completeHandshake(session)
        // Coalesced, malformed, or other five-byte frames do not match QZ's whole-notification test.
        session.onNotification(900, state1 + reply)
        session.onNotification(910, "5b0213075d".hexToByteArray())
        session.onNotification(920, "5c0203015d".hexToByteArray())
        assertEquals(listOf("5b0400104f4b5d"), session.tick(1_000).hex())
    }

    @Test
    fun `F65 workout frame yields incline, speed and heart rate`() {
        val frame = "5b0f060000000000008c640601000000015d".hexToByteArray()
        val metrics = soleMeasurements(SoleModel.F65, frame)
        assertEquals(6, metrics[MetricKind.INCLINATION])
        assertEquals(140, metrics[MetricKind.HEART_RATE])
        assertEquals(16.09344, metrics.getValue(MetricKind.SPEED).toDouble(), 0.001)
    }
}
