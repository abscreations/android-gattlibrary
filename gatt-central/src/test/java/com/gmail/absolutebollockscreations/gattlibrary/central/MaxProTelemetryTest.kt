package com.gmail.absolutebollockscreations.gattlibrary.central

import org.junit.Assert.*
import org.junit.Test

class MaxProTelemetryTest {
    private val payload = "0F0100640A08A00A852230E00060A08400945913C10006"
    private val frame = "{EVT,$payload}"
    private val first = ConnectionId("AA:00", 1)
    private val second = ConnectionId("AA:00", 2)

    @Test fun `a 52 byte frame survives ATT MTU 23 notification values of 20 20 12 bytes`() {
        val chunks = frame.chunked(20)
        assertEquals(listOf(20, 20, 12), chunks.map { it.length })
        val store = MaxProStore()
        store.begin(first)
        chunks.forEachIndexed { i, chunk ->
            store.feed(first, chunk.toByteArray(), i.toLong())
            if (i < 2) assertNull(store.state.value.snapshot)
        }
        assertEquals(1L, store.state.value.completedFrames)
        assertEquals(3L, store.state.value.notificationsReceived)
        assertEquals(20, store.state.value.maximumNotificationBytes)
        assertEquals(12, store.state.value.lastNotificationBytes)
        assertEquals(6, store.state.value.snapshot!!.left.deviceRepCount)
        assertEquals(6, store.state.value.snapshot!!.right.deviceRepCount)
    }

    @Test fun `truncating each frame to 20 bytes cannot masquerade as successful reassembly`() {
        val store = MaxProStore()
        store.begin(first)
        repeat(5) { store.feed(first, frame.take(20).toByteArray(), it.toLong()) }
        assertNull(store.state.value.snapshot)
        assertEquals(0L, store.state.value.completedFrames)
        assertEquals(4L, store.state.value.rejectedFragments)
    }

    @Test fun `captured stream remains decodable when notification values are synthetically capped at 20 bytes`() {
        val lines = javaClass.getResourceAsStream("/maxpro/v1-notifications.hex")!!
            .bufferedReader().use { it.readLines() }
        val store = MaxProStore()
        store.begin(first)
        lines.forEachIndexed { i, line ->
            val bytes = line.chunked(2).map { it.toInt(16).toByte() }
            bytes.chunked(20).forEach { store.feed(first, it.toByteArray(), i.toLong()) }
        }
        assertTrue(store.state.value.maximumNotificationBytes <= 20)
        assertEquals(78L, store.state.value.completedFrames)
        assertEquals(6, store.state.value.snapshot!!.left.deviceRepCount)
        assertEquals(6, store.state.value.snapshot!!.right.deviceRepCount)
    }

    @Test fun `every split and coalesced notifications preserve the frame`() {
        for (split in 0..frame.length) {
            val framer = MaxProFramer()
            val frames = framer.feed(frame.take(split).toByteArray()) +
                framer.feed(frame.drop(split).toByteArray())
            assertEquals("split $split", listOf(frame), frames)
        }
        assertEquals(listOf("{clr,OK}", frame, "{wnd,BG}"),
            MaxProFramer().feed("noise{clr,OK}$frame{wnd,BG}".toByteArray()))
    }

    @Test fun `malformed nested non ASCII and excess input resynchronize`() {
        val framer = MaxProFramer()
        assertEquals(listOf(frame), framer.feed(("{partial" + frame).toByteArray()))
        assertTrue(framer.feed(byteArrayOf(123, 65, 0xff.toByte(), 125)).isEmpty())
        assertTrue(framer.feed(("{" + "A".repeat(1024) + "}").toByteArray()).isEmpty())
        assertEquals(listOf(frame), framer.feed(frame.toByteArray()))
        assertEquals(3L, framer.rejectedFragments)
    }

    @Test fun `captured nibble offsets decode both sides without losing unsigned fields`() {
        val raw = requireNotNull(MaxProV1Decoder.raw(payload))
        assertEquals(15, raw.event)
        assertEquals(1, raw.model)
        assertEquals(100, raw.batteryRaw)
        assertEquals(MaxProRawSide(10, 2208, 168, 1314, 782, 6), raw.left)
        assertEquals(MaxProRawSide(10, 2112, 148, 1425, 961, 6), raw.right)
        val high = requireNotNull(MaxProV1Decoder.raw("F".repeat(46)))
        assertEquals(65535, high.left.deviceRepCount)
        assertEquals(65535, high.right.deviceRepCount)
        assertEquals(4095, MaxProV1Decoder.raw("F".repeat(46))!!.left.timeTicks)
        assertNull(MaxProV1Decoder.side(high.left.copy(knobTenths = 255)).resistanceLevel)
    }

    @Test fun `source backed motion bounds keep invalid fields raw and derived values absent`() {
        val raw = MaxProRawSide(10, 65535, 4095, 4095, 4095, 65535)
        val side = MaxProV1Decoder.side(raw)
        assertNull(side.calibratedDistanceMeters)
        assertNull(side.deviceTimeMillis)
        assertEquals(65535, side.deviceRepCount)
        assertEquals(65.535, side.rawDistanceMeters, 1e-9)
    }

    @Test fun `truncations invalid characters and extra bytes never enter decoder`() {
        for (length in 0 until payload.length) assertNull(MaxProV1Decoder.raw(payload.take(length)))
        assertNull(MaxProV1Decoder.raw(payload + "00"))
        assertNull(MaxProV1Decoder.raw("Z" + payload.drop(1)))
        assertNull(MaxProV1Decoder.raw("１" + payload.drop(1)))
    }

    @Test fun `legacy interpolation and zero calibration use declared units`() {
        assertEquals(26.0 * 4.4482216152605, MaxProV1Decoder.nominalForceNewtons(10.5)!!, 1e-9)
        assertEquals(158.0 * 4.4482216152605, MaxProV1Decoder.nominalForceNewtons(25.0)!!, 1e-9)
        listOf(0.0, 25.1, Double.NaN, Double.POSITIVE_INFINITY).forEach {
            assertNull(MaxProV1Decoder.nominalForceNewtons(it))
        }
        val side = MaxProV1Decoder.side(MaxProV1Decoder.raw(payload)!!.left)
        assertEquals(2.208, side.rawDistanceMeters, 1e-9)
        assertEquals(1.6425264, side.calibratedDistanceMeters!!, 1e-9)
        assertEquals(1680, side.deviceTimeMillis)
        assertEquals(0.0, MaxProV1Decoder.side(MaxProRawSide(10, 0, 0, 0, 0, 0)).calibratedDistanceMeters!!, 0.0)
    }

    @Test fun `generation resets partial data and old callbacks cannot overwrite replacement`() {
        val store = MaxProStore()
        store.begin(first)
        store.feed(first, frame.take(25).toByteArray(), 1)
        store.begin(second)
        store.feed(second, frame.drop(25).toByteArray(), 2)
        assertNull(store.state.value.snapshot)
        store.feed(first, frame.toByteArray(), 3)
        store.end(first)
        store.status(first, MaxProLinkStatus.FAILED)
        store.querySent(first)
        assertEquals(MaxProLinkStatus.SUBSCRIBING, store.state.value.linkStatus)
        store.feed(second, frame.toByteArray(), 4)
        assertEquals(6, store.state.value.snapshot!!.left.deviceRepCount)
        store.end(second)
        assertEquals(MaxProState(), store.state.value)
    }

    @Test fun `unknown model extended layout and malformed EVT clear derived values`() {
        val store = MaxProStore()
        store.begin(first)
        store.feed(first, frame.toByteArray(), 1)
        store.feed(first, "{EVT,${payload.replaceRange(2, 4, "02")}}".toByteArray(), 2)
        assertEquals(MaxProDecodeStatus.UNSUPPORTED, store.state.value.decodeStatus)
        assertNull(store.state.value.snapshot)
        assertNull(store.state.value.raw)
        store.feed(first, "{EVT,${payload}0000000000}".toByteArray(), 3)
        assertNull(store.state.value.raw)
        assertNull(store.state.value.snapshot)
        store.feed(first, "{EVT,ZZ}".toByteArray(), 4)
        assertEquals(MaxProDecodeStatus.MALFORMED, store.state.value.decodeStatus)
        assertEquals("{EVT,ZZ}", store.state.value.lastFrame)
        assertNull(store.state.value.firmware)
    }

    @Test fun `captured notifications preserve knob sweep independent counters and travel ordering`() {
        val lines = javaClass.getResourceAsStream("/maxpro/v1-notifications.hex")!!
            .bufferedReader().use { it.readLines() }
        val framer = MaxProFramer()
        val frames = lines.flatMap { line ->
            framer.feed(line.chunked(2).map { it.toInt(16).toByte() }.toByteArray())
        }
        val snapshots = frames.filter { it.startsWith("{EVT,") }
            .mapNotNull { MaxProV1Decoder.raw(it.substring(5, it.length - 1)) }
        assertEquals(71, snapshots.size)
        assertTrue(snapshots.map { it.left.knobTenths }.containsAll(listOf(10,25,60,55,30)))
        assertEquals(6, snapshots.last().left.deviceRepCount)
        assertEquals(6, snapshots.last().right.deviceRepCount)
        for (left in listOf(true, false)) {
            val reps = snapshots.map { if (left) it.left else it.right }
                .filter { it.deviceRepCount in 1..6 }.distinctBy { it.deviceRepCount }
            assertEquals((1..6).toList(), reps.map { it.deviceRepCount })
            assertTrue(reps.take(3).maxOf { it.distanceMillimeters } <
                reps.drop(3).minOf { it.distanceMillimeters })
        }
    }

    @Test fun `device counter decreases and wraps are raw snapshots not workout increments`() {
        val store = MaxProStore()
        store.begin(first)
        for (count in listOf("FFFF", "0000", "0001")) {
            store.feed(first, "{EVT,${payload.replaceRange(23, 27, count)}}".toByteArray(), 1)
            assertEquals(count.toInt(16), store.state.value.snapshot!!.left.deviceRepCount)
        }
    }
}
