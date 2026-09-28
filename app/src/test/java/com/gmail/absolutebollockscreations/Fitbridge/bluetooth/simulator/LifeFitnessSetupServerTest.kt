package com.gmail.absolutebollockscreations.Fitbridge.bluetooth.simulator

import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine.BluetoothIds
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class LifeFitnessSetupServerTest {
    @Test
    fun `official app transcript unlocks streaming and emits captured statuses`() {
        val server = LifeFitnessSetupServer()

        val opened = server.write(BluetoothIds.UUID_LF_CONTROL_1, byteArrayOf(0x01))
        assertTrue(opened.accepted)
        assertArrayEquals(byteArrayOf(0x07), opened.statusNotification)
        assertFalse(server.write(BluetoothIds.UUID_LF_CONTROL_1, byteArrayOf(0x01)).accepted)
        writeProfile(
            server,
            token = "f77ab82b7a2440078f33c5aa6ee7a31b",
            token2 = 0,
            emptyProfile = false,
        )

        val magic = server.write(
            BluetoothIds.UUID_LF_CONTROL_2,
            byteArrayOf(0x00, 0x00, 0x00, 0x01, 0xB8.toByte(), 0x5B, 0x5D),
        )
        assertTrue(magic.accepted)
        assertEquals(LifeFitnessSetupServer.Stage.MAGIC_ACCEPTED, magic.stage)

        val unlocked = server.write(BluetoothIds.UUID_LF_CONTROL_1, byteArrayOf(0x02))
        assertTrue(unlocked.accepted)
        assertArrayEquals(byteArrayOf(0x04, 0x01), unlocked.statusNotification)
        assertEquals(LifeFitnessSetupServer.Stage.UNLOCKED, unlocked.stage)
    }

    @Test
    fun `current app two-byte age and synthetic twenty-hex token complete the transcript`() {
        val server = LifeFitnessSetupServer()
        assertTrue(server.write(BluetoothIds.UUID_LF_CONTROL_1, byteArrayOf(0x01)).accepted)
        writeProfile(
            server,
            token = "0123456789abcdefABCD",
            token2 = 0,
            emptyProfile = false,
            ageValue = byteArrayOf(42, 0),
            nulTerminateText = false,
        )

        assertTrue(
            server.write(
                BluetoothIds.UUID_LF_CONTROL_2,
                byteArrayOf(0x00, 0x00, 0x00, 0x01, 0xB8.toByte(), 0x5B, 0x5D),
            ).accepted,
        )
        val unlocked = server.write(BluetoothIds.UUID_LF_CONTROL_1, byteArrayOf(0x02))
        assertTrue(unlocked.accepted)
        assertArrayEquals(byteArrayOf(0x04, 0x01), unlocked.statusNotification)
    }

    @Test
    fun `unseen synthetic thirty-two-hex token completes the transcript`() {
        val server = LifeFitnessSetupServer()
        assertTrue(server.write(BluetoothIds.UUID_LF_CONTROL_1, byteArrayOf(0x01)).accepted)
        writeProfile(
            server,
            token = "0123456789abcdefABCDEF0123456789",
            token2 = 0,
            emptyProfile = false,
            ageValue = byteArrayOf(42, 0),
            nulTerminateText = false,
        )

        assertTrue(
            server.write(
                BluetoothIds.UUID_LF_CONTROL_2,
                byteArrayOf(0x00, 0x00, 0x00, 0x01, 0xB8.toByte(), 0x5B, 0x5D),
            ).accepted,
        )
        val unlocked = server.write(BluetoothIds.UUID_LF_CONTROL_1, byteArrayOf(0x02))
        assertTrue(unlocked.accepted)
        assertArrayEquals(byteArrayOf(0x04, 0x01), unlocked.statusNotification)
    }

    @Test
    fun `age and current-app token variants retain strict structural bounds`() {
        val invalidAge = LifeFitnessSetupServer()
        assertTrue(invalidAge.write(BluetoothIds.UUID_LF_CONTROL_1, byteArrayOf(0x01)).accepted)
        assertTrue(invalidAge.write(BluetoothIds.UUID_USER_FIRST_NAME, "A".toByteArray()).accepted)
        assertTrue(invalidAge.write(BluetoothIds.UUID_USER_LAST_NAME, "B".toByteArray()).accepted)
        assertTrue(invalidAge.write(BluetoothIds.UUID_USER_EMAIL, "a@b.invalid".toByteArray()).accepted)
        assertFalse(invalidAge.write(BluetoothIds.UUID_USER_AGE, byteArrayOf(1, 0, 0)).accepted)

        listOf(
            "0123456789abcdefABC",
            "0123456789abcdefABCDE",
            "0123456789abcdefABC!",
            "a".repeat(31),
            "a".repeat(33),
            "a".repeat(31) + "!",
        ).forEach { token ->
            val invalidToken = LifeFitnessSetupServer()
            assertTrue(invalidToken.write(BluetoothIds.UUID_LF_CONTROL_1, byteArrayOf(0x01)).accepted)
            val prefix = listOf(
                BluetoothIds.UUID_USER_FIRST_NAME to "A".toByteArray(),
                BluetoothIds.UUID_USER_LAST_NAME to "B".toByteArray(),
                BluetoothIds.UUID_USER_EMAIL to "a@b.invalid".toByteArray(),
                BluetoothIds.UUID_USER_AGE to byteArrayOf(42, 0),
                BluetoothIds.UUID_USER_GENDER to byteArrayOf(0),
                BluetoothIds.UUID_USER_LANGUAGE to "en".toByteArray(),
            )
            prefix.forEach { (uuid, value) -> assertTrue(invalidToken.write(uuid, value).accepted) }
            assertFalse(invalidToken.write(BluetoothIds.UUID_LF_VENDOR_TOKEN_1, token.toByteArray()).accepted)
        }
    }

    @Test
    fun `concurrent profile callbacks are processed in ATT request order`() {
        data class Write(val requestId: Int, val uuid: java.util.UUID, val value: ByteArray)

        val server = LifeFitnessSetupServer()
        val processedIds = Collections.synchronizedList(mutableListOf<Int>())
        val accepted = Collections.synchronizedMap(mutableMapOf<Int, Boolean>())
        val sequencer = LifeFitnessRequestSequencer<Write> { write ->
            processedIds += write.requestId
            accepted[write.requestId] = server.write(write.uuid, write.value).accepted
        }
        val requests = listOf(
            Write(3, BluetoothIds.UUID_LF_CONTROL_1, byteArrayOf(0x01)),
            Write(4, BluetoothIds.UUID_USER_FIRST_NAME, "Sim".toByteArray()),
            Write(5, BluetoothIds.UUID_USER_LAST_NAME, "Client".toByteArray()),
            Write(6, BluetoothIds.UUID_USER_EMAIL, "sim@example.invalid".toByteArray()),
            Write(7, BluetoothIds.UUID_USER_AGE, byteArrayOf(42, 0)),
            Write(8, BluetoothIds.UUID_USER_GENDER, byteArrayOf(0)),
            Write(9, BluetoothIds.UUID_USER_LANGUAGE, "en".toByteArray()),
            Write(10, BluetoothIds.UUID_LF_VENDOR_TOKEN_1, "0123456789abcdefABCD".toByteArray()),
            Write(11, BluetoothIds.UUID_LF_VENDOR_TOKEN_2, byteArrayOf(0)),
            Write(12, BluetoothIds.UUID_USER_WEIGHT, byteArrayOf(0xD4.toByte(), 0x30)),
            Write(13, BluetoothIds.UUID_USER_HEIGHT, byteArrayOf(0xB0.toByte(), 0x04)),
            Write(
                14,
                BluetoothIds.UUID_LF_CONTROL_2,
                byteArrayOf(0x00, 0x00, 0x00, 0x01, 0xB8.toByte(), 0x5B, 0x5D),
            ),
        )

        sequencer.enqueueWrite(requests.first().requestId, requests.first())
        val ready = CountDownLatch(requests.size - 1)
        val start = CountDownLatch(1)
        val submitted = Collections.synchronizedList(mutableListOf<Int>())
        val predecessorSubmitted = (4..14).associateWith { CountDownLatch(1) }
        val executor = Executors.newFixedThreadPool(requests.size - 1)
        requests.drop(1).reversed().forEach { write ->
            executor.execute {
                ready.countDown()
                start.await()
                if (write.requestId < 14) {
                    predecessorSubmitted.getValue(write.requestId + 1).await()
                }
                submitted += write.requestId
                sequencer.enqueueWrite(write.requestId, write)
                predecessorSubmitted.getValue(write.requestId).countDown()
            }
        }
        assertTrue(ready.await(5, TimeUnit.SECONDS))
        start.countDown()
        executor.shutdown()
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))

        assertEquals((14 downTo 4).toList(), submitted)
        assertEquals((3..14).toList(), processedIds)
        assertTrue(accepted.values.all { it })
        assertEquals(LifeFitnessSetupServer.Stage.MAGIC_ACCEPTED, server.stage)
        sequencer.enqueueWrite(15, Write(15, BluetoothIds.UUID_LF_CONTROL_1, byteArrayOf(0x02)))
        assertEquals(LifeFitnessSetupServer.Stage.UNLOCKED, server.stage)
    }

    @Test
    fun `completed request IDs bridge gaps and clear invalidates pending writes`() {
        val processed = mutableListOf<Int>()
        val sequencer = LifeFitnessRequestSequencer<Int> { processed += it }

        sequencer.enqueueWrite(10, 10)
        sequencer.enqueueWrite(12, 12)
        assertEquals(listOf(10), processed)
        sequencer.observeCompletedRequest(11)
        assertEquals(listOf(10, 12), processed)

        sequencer.enqueueWrite(14, 14)
        sequencer.clear()
        sequencer.observeCompletedRequest(13)
        sequencer.enqueueWrite(13, 13)
        assertEquals(listOf(10, 12), processed)
    }

    @Test
    fun `accepted setup replies bypass status CCCD exactly once while measurements remain gated`() {
        val server = LifeFitnessSetupServer()
        val subscriptions = mutableSetOf<java.util.UUID>()
        val measurementUuid = BluetoothIds.UUID_TREADMILL_DATA

        val opened = server.write(BluetoothIds.UUID_LF_CONTROL_1, byteArrayOf(0x01))
        assertArrayEquals(byteArrayOf(0x07), LifeFitnessNotificationPolicy.setupStatusReply(opened))
        assertFalse(BluetoothIds.UUID_LF_STATUS in subscriptions)
        assertNull(
            LifeFitnessNotificationPolicy.setupStatusReply(
                server.write(BluetoothIds.UUID_LF_CONTROL_1, byteArrayOf(0x01)),
            ),
        )
        assertFalse(
            LifeFitnessNotificationPolicy.mayStreamMeasurement(server.stage, measurementUuid, subscriptions),
        )

        writeProfile(
            server,
            token = "f77ab82b7a2440078f33c5aa6ee7a31b",
            token2 = 0,
            emptyProfile = false,
        )
        assertTrue(
            server.write(
                BluetoothIds.UUID_LF_CONTROL_2,
                byteArrayOf(0x00, 0x00, 0x00, 0x01, 0xB8.toByte(), 0x5B, 0x5D),
            ).accepted,
        )
        val unlocked = server.write(BluetoothIds.UUID_LF_CONTROL_1, byteArrayOf(0x02))
        assertArrayEquals(byteArrayOf(0x04, 0x01), LifeFitnessNotificationPolicy.setupStatusReply(unlocked))
        assertNull(
            LifeFitnessNotificationPolicy.setupStatusReply(
                server.write(BluetoothIds.UUID_LF_CONTROL_1, byteArrayOf(0x02)),
            ),
        )
        assertFalse(BluetoothIds.UUID_LF_STATUS in subscriptions)
        assertFalse(
            LifeFitnessNotificationPolicy.mayStreamMeasurement(server.stage, measurementUuid, subscriptions),
        )

        subscriptions += measurementUuid
        assertTrue(
            LifeFitnessNotificationPolicy.mayStreamMeasurement(server.stage, measurementUuid, subscriptions),
        )
    }

    @Test
    fun `out of order malformed and unknown writes cannot unlock streaming`() {
        val server = LifeFitnessSetupServer()
        assertFalse(server.write(BluetoothIds.UUID_USER_FIRST_NAME, byteArrayOf()).accepted)
        server.write(BluetoothIds.UUID_LF_CONTROL_1, byteArrayOf(0x01))
        assertFalse(server.write(BluetoothIds.UUID_USER_LAST_NAME, byteArrayOf()).accepted)
        assertFalse(server.write(BluetoothIds.UUID_LF_VENDOR_TOKEN_1, "unknown".toByteArray()).accepted)
        assertFalse(server.write(BluetoothIds.UUID_LF_CONTROL_1, byteArrayOf(0x02)).accepted)
        assertEquals(LifeFitnessSetupServer.Stage.SESSION_OPEN, server.stage)
    }

    @Test
    fun `QZ empty profile values and both corroborated alternate tokens are accepted`() {
        listOf(
            "8a24c84aa4c0400c96ed1faa20fa4594",
            "8feda8891dba401f89900230795f0860",
        ).forEachIndexed { index, token ->
            val server = LifeFitnessSetupServer()
            server.write(BluetoothIds.UUID_LF_CONTROL_1, byteArrayOf(0x01))
            writeProfile(server, token, token2 = index, emptyProfile = true)
            assertEquals(LifeFitnessSetupServer.Stage.PROFILE_RECEIVING, server.stage)
        }
    }

    @Test
    fun `disconnect reset removes all per-link setup state`() {
        val server = LifeFitnessSetupServer()
        server.write(BluetoothIds.UUID_LF_CONTROL_1, byteArrayOf(0x01))
        server.write(BluetoothIds.UUID_USER_FIRST_NAME, "A".toByteArray())
        server.clearSensitiveState()
        assertEquals(LifeFitnessSetupServer.Stage.CONNECTED, server.stage)
        assertFalse(server.write(BluetoothIds.UUID_USER_LAST_NAME, byteArrayOf()).accepted)
    }

    private fun writeProfile(
        server: LifeFitnessSetupServer,
        token: String,
        token2: Int,
        emptyProfile: Boolean,
        ageValue: ByteArray = if (emptyProfile) byteArrayOf() else byteArrayOf(35),
        nulTerminateText: Boolean = true,
    ) {
        fun text(value: String) = when {
            emptyProfile -> byteArrayOf()
            nulTerminateText -> "$value\u0000".toByteArray()
            else -> value.toByteArray()
        }
        val values = listOf(
            BluetoothIds.UUID_USER_FIRST_NAME to text("Fit"),
            BluetoothIds.UUID_USER_LAST_NAME to text("Bridge"),
            BluetoothIds.UUID_USER_EMAIL to text("sim@example.invalid"),
            BluetoothIds.UUID_USER_AGE to ageValue,
            BluetoothIds.UUID_USER_GENDER to if (emptyProfile) byteArrayOf() else byteArrayOf(0),
            BluetoothIds.UUID_USER_LANGUAGE to text("en-US"),
            BluetoothIds.UUID_LF_VENDOR_TOKEN_1 to token.toByteArray(Charsets.US_ASCII),
            BluetoothIds.UUID_LF_VENDOR_TOKEN_2 to byteArrayOf(token2.toByte()),
            BluetoothIds.UUID_USER_WEIGHT to if (emptyProfile) byteArrayOf() else byteArrayOf(0xD4.toByte(), 0x30),
            BluetoothIds.UUID_USER_HEIGHT to if (emptyProfile) byteArrayOf() else byteArrayOf(0xB0.toByte(), 0x04),
        )
        values.forEach { (uuid, value) -> assertTrue(server.write(uuid, value).accepted) }
    }
}
