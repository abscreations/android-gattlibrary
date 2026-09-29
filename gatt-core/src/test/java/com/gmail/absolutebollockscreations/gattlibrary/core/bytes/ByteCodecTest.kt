package com.gmail.absolutebollockscreations.gattlibrary.core.bytes

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * LIB-18d. The "legacy" helpers at the bottom are verbatim copies of the implementations LIB-18b
 * found in central, peripheral and the simulator; the equivalence tests are what allow later
 * units to switch those call sites without changing a single emitted or decoded byte.
 */
class ByteCodecTest {
    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    private val samples = listOf(
        0, 1, 0x7F, 0x80, 0xFF, 0x100, 0x7FFF, 0x8000, 0xFFFF, 0x1_0000, 0x7F_FFFF, 0xFF_FFFF,
        0x100_0000, Int.MAX_VALUE, -1, -2, -0x80, -0x8000, -0x8001, -0x1_0000, Int.MIN_VALUE,
    )

    // ── reads ───────────────────────────────────────────────────────────────────

    @Test
    fun `unsigned reads are little-endian and never sign-extend`() {
        val data = bytes(0xFE, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF)
        assertEquals(0xFE, LittleEndian.u8(data, 0))
        assertEquals(0xFFFE, LittleEndian.u16(data, 0))
        assertEquals(0xFF_FFFE, LittleEndian.u24(data, 0))
        assertEquals(0xFFFF_FFFEL, LittleEndian.u32(data, 0))
        assertEquals(0xFFFF_FFFF_FFFEL, LittleEndian.u48(data, 0))
        assertEquals(0x0201, LittleEndian.u16(bytes(0x01, 0x02), 0))
        assertEquals(0x04030201L, LittleEndian.u32(bytes(0x01, 0x02, 0x03, 0x04), 0))
    }

    @Test
    fun `signed 16-bit reads cover the whole range`() {
        assertEquals(-1, LittleEndian.s16(bytes(0xFF, 0xFF), 0))
        assertEquals(-0x8000, LittleEndian.s16(bytes(0x00, 0x80), 0))
        assertEquals(0x7FFF, LittleEndian.s16(bytes(0xFF, 0x7F), 0))
        assertEquals(0, LittleEndian.s16(bytes(0x00, 0x00), 0))
    }

    @Test
    fun `unchecked reads throw rather than invent a value`() {
        try {
            LittleEndian.u16(bytes(0x01), 0)
            fail("a truncated unchecked read must throw")
        } catch (_: IndexOutOfBoundsException) {
        }
    }

    @Test
    fun `nullable reads return null for truncated, negative and past-the-end offsets`() {
        val data = bytes(0x01, 0x02, 0x03, 0x04)
        assertEquals(0x0201, LittleEndian.u16OrNull(data, 0))
        assertEquals(0x0403, LittleEndian.u16OrNull(data, 2))
        assertNull(LittleEndian.u16OrNull(data, 3))
        assertNull(LittleEndian.u16OrNull(data, -1))
        assertNull(LittleEndian.u16OrNull(data, 5))
        assertNull(LittleEndian.u24OrNull(data, 2))
        assertEquals(0x04030201L, LittleEndian.u32OrNull(data, 0))
        assertNull(LittleEndian.u32OrNull(data, 1))
        assertEquals(0x04, LittleEndian.u8OrNull(data, 3))
        assertNull(LittleEndian.u8OrNull(data, 4))
        assertNull(LittleEndian.s16OrNull(ByteArray(0), 0))
        assertEquals(-1, LittleEndian.s16OrNull(bytes(0xFF, 0xFF), 0))
    }

    @Test
    fun `big-endian reads match CSAFE network order`() {
        val data = bytes(0x01, 0x02, 0x03, 0x04)
        assertEquals(0x0102, BigEndian.u16(data, 0))
        assertEquals(0x01020304L, BigEndian.u32(data, 0))
        assertEquals(0xFFFF_FFFFL, BigEndian.u32(bytes(0xFF, 0xFF, 0xFF, 0xFF), 0))
        assertNull(BigEndian.u16OrNull(data, 3))
        assertNull(BigEndian.u32OrNull(data, 1))
        assertEquals(0x0304, BigEndian.u16OrNull(data, 2))
    }

    // ── writes ──────────────────────────────────────────────────────────────────

    @Test
    fun `wrapping writers match every legacy masking helper for any input`() {
        for (v in samples) {
            assertArrayEquals("u8 $v", legacyU8(v), LittleEndian.u8Wrapping(v))
            assertArrayEquals("u16 $v", legacyCentralU16le(v), LittleEndian.u16Wrapping(v))
            assertArrayEquals("u16 peripheral $v", legacyPeripheralU16le(v), LittleEndian.u16Wrapping(v))
            assertArrayEquals("s16 central $v", legacyCentralS16le(v), LittleEndian.s16Wrapping(v))
            assertArrayEquals("s16 peripheral $v", legacyPeripheralS16le(v), LittleEndian.s16Wrapping(v))
            assertArrayEquals("s16 control point $v", legacyControlPointS16le(v), LittleEndian.s16Wrapping(v))
            assertArrayEquals("u24 $v", legacyU24le(v), LittleEndian.u24Wrapping(v))
            assertArrayEquals("u32 $v", legacyPeripheralU32le(v), LittleEndian.u32Wrapping(v.toLong()))
            val target = ByteArray(5)
            LittleEndian.putU16Wrapping(target, 1, v)
            assertArrayEquals("putU16 $v", byteArrayOf(0) + legacyCentralU16le(v) + byteArrayOf(0, 0), target)
        }
    }

    @Test
    fun `wrapping and saturating writers differ only outside the field range`() {
        assertArrayEquals(bytes(0x00, 0x00), LittleEndian.u16Wrapping(0x1_0000))
        assertArrayEquals(bytes(0xFF, 0xFF), LittleEndian.u16Saturating(0x1_0000))
        assertArrayEquals(bytes(0xFF, 0xFF), LittleEndian.u16Wrapping(-1))
        assertArrayEquals(bytes(0x00, 0x00), LittleEndian.u16Saturating(-1))
        assertArrayEquals(bytes(0x34, 0x12), LittleEndian.u16Wrapping(0x1234))
        assertArrayEquals(bytes(0x34, 0x12), LittleEndian.u16Saturating(0x1234))
    }

    @Test
    fun `saturating writers match every legacy clamping helper`() {
        val wide = samples.map(Int::toLong) + listOf(Long.MAX_VALUE, Long.MIN_VALUE, 0x1_0000_0000L)
        for (v in wide) {
            assertArrayEquals("u8 $v", legacySimulatorWriterU8(v), LittleEndian.u8Saturating(v))
            assertArrayEquals("u16 $v", legacySimulatorWriterU16(v), LittleEndian.u16Saturating(v))
            assertArrayEquals("s16 $v", legacyLifeFitnessS16(v), LittleEndian.s16Saturating(v))
            assertArrayEquals("u24 $v", legacySimulatorWriterU24(v), LittleEndian.u24Saturating(v))
            assertArrayEquals("u32 $v", legacyCyclingU32(v), LittleEndian.u32Saturating(v))
            val target = ByteArray(4)
            LittleEndian.putU16Saturating(target, 1, v)
            val expected = ByteArray(4).also { legacyKeiserPutU16(it, 1, v) }
            assertArrayEquals("putU16 $v", expected, target)
            val target24 = ByteArray(4)
            LittleEndian.putU24Saturating(target24, 0, v)
            assertArrayEquals("putU24 $v", legacySimulatorWriterU24(v) + byteArrayOf(0), target24)
        }
    }

    @Test
    fun `reads invert writes across the unsigned ranges`() {
        for (v in listOf(0, 1, 0xFF, 0x100, 0x7FFF, 0x8000, 0xFFFF)) {
            assertEquals(v, LittleEndian.u16(LittleEndian.u16Wrapping(v), 0))
        }
        for (v in listOf(-0x8000, -1, 0, 1, 0x7FFF)) {
            assertEquals(v, LittleEndian.s16(LittleEndian.s16Wrapping(v), 0))
        }
        for (v in listOf(0, 0x80_0000, 0xFF_FFFF)) {
            assertEquals(v, LittleEndian.u24(LittleEndian.u24Wrapping(v), 0))
        }
        for (v in listOf(0L, 0x8000_0000L, 0xFFFF_FFFFL)) {
            assertEquals(v, LittleEndian.u32(LittleEndian.u32Wrapping(v), 0))
        }
    }

    // ── cursor ──────────────────────────────────────────────────────────────────

    @Test
    fun `cursor reads sequentially and stops without advancing when truncated`() {
        val cursor = ByteCursor(bytes(0x01, 0x02, 0x03, 0x04, 0x05))
        assertEquals(0x01, cursor.u8())
        assertEquals(0x0302, cursor.u16())
        assertNull(cursor.lastFailure)
        assertNull(cursor.u24())
        assertEquals(ByteCursorFailure.TRUNCATED, cursor.lastFailure)
        assertEquals(3, cursor.offset)
        assertEquals(2, cursor.remaining)
        assertEquals(0x0504, cursor.u16())
        assertNull(cursor.lastFailure)
        assertNull(cursor.u8())
        assertEquals(0, cursor.remaining)
    }

    @Test
    fun `cursor signed, 32-bit, bytes and skip`() {
        val cursor = ByteCursor(bytes(0xFF, 0xFF, 0x01, 0x00, 0x00, 0x80, 0xAA, 0xBB), start = 0)
        assertEquals(-1, cursor.s16())
        assertEquals(0x8000_0001L, cursor.u32())
        assertFalse(cursor.skip(3))
        assertEquals(6, cursor.offset)
        assertFalse(cursor.skip(-1))
        assertArrayEquals(bytes(0xAA, 0xBB), cursor.bytes(2))
        assertNull(cursor.bytes(1))
        assertNull(cursor.bytes(-1))
        assertTrue(cursor.skip(0))
    }

    @Test
    fun `cursor matches the legacy Concept2 and CPS cursors on every prefix`() {
        val packet = bytes(0x10, 0x20, 0x30, 0x40, 0x50, 0x60, 0x70)
        for (length in 0..packet.size) {
            val data = packet.copyOf(length)
            val legacy = LegacyCursor(data)
            val core = ByteCursor(data)
            assertEquals("u8 @$length", legacy.u8(), core.u8())
            assertEquals("u16 @$length", legacy.u16(), core.u16())
            assertEquals("u24 @$length", legacy.u24(), core.u24())
            assertEquals("u32 @$length", legacy.u32(), core.u32())
        }
    }

    @Test
    fun `cursor rejects an invalid start`() {
        try {
            ByteCursor(ByteArray(2), start = 3)
            fail("start past the end must be rejected")
        } catch (_: IllegalArgumentException) {
        }
    }

    // ── varint ──────────────────────────────────────────────────────────────────

    @Test
    fun `varint round-trips and matches the protobuf reference encodings`() {
        assertArrayEquals(bytes(0x00), ProtobufVarint.encode(0))
        assertArrayEquals(bytes(0x01), ProtobufVarint.encode(1))
        assertArrayEquals(bytes(0x7F), ProtobufVarint.encode(127))
        assertArrayEquals(bytes(0xAC, 0x02), ProtobufVarint.encode(300))
        assertArrayEquals(
            bytes(0xFF, 0xFF, 0xFF, 0xFF, 0x0F),
            ProtobufVarint.encode(0xFFFF_FFFFL),
        )
        for (v in listOf(0L, 1L, 127L, 128L, 300L, 0xFFFF_FFFFL, Long.MAX_VALUE)) {
            assertEquals(v, ByteCursor(ProtobufVarint.encode(v)).varint())
        }
    }

    @Test
    fun `varint decode reports truncation and overlong input like the RideOn reader`() {
        val truncated = ByteCursor(bytes(0x80, 0x80))
        assertNull(truncated.varint())
        assertEquals(ByteCursorFailure.TRUNCATED, truncated.lastFailure)

        val tenth = ByteArray(10) { 0xFF.toByte() }.also { it[9] = 0x02 }
        val overlong = ByteCursor(tenth)
        assertNull(overlong.varint())
        assertEquals(ByteCursorFailure.OVERLONG_VARINT, overlong.lastFailure)

        val maximal = ByteArray(10) { 0xFF.toByte() }.also { it[9] = 0x01 }
        assertEquals(-1L, ByteCursor(maximal).varint())
    }

    @Test
    fun `varint encode rejects negative values`() {
        try {
            ProtobufVarint.encode(-1)
            fail("negative varint must be rejected")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun `zigzag 32 matches the protobuf mapping and inverts`() {
        assertEquals(0L, ProtobufVarint.zigZagEncode32(0))
        assertEquals(1L, ProtobufVarint.zigZagEncode32(-1))
        assertEquals(2L, ProtobufVarint.zigZagEncode32(1))
        assertEquals(0xFFFF_FFFEL, ProtobufVarint.zigZagEncode32(Int.MAX_VALUE))
        assertEquals(0xFFFF_FFFFL, ProtobufVarint.zigZagEncode32(Int.MIN_VALUE))
        for (v in listOf(0, 1, -1, 1000, -1000, Int.MAX_VALUE, Int.MIN_VALUE)) {
            assertEquals(v, ProtobufVarint.zigZagDecode32(ProtobufVarint.zigZagEncode32(v)))
        }
        try {
            ProtobufVarint.zigZagDecode32(0x1_0000_0000L)
            fail("values above uint32 must be rejected")
        } catch (_: IllegalArgumentException) {
        }
    }

    // ── verbatim legacy implementations (LIB-18b §3) ────────────────────────────

    /** peripheral FtmsSpec.u8 */
    private fun legacyU8(v: Int): ByteArray = byteArrayOf((v and 0xFF).toByte())

    /** central bluetoothIds.u16le, btEngine.ControlPoint.u16le, WahooKickrCodec.u16 */
    private fun legacyCentralU16le(v: Int): ByteArray =
        byteArrayOf((v and 0xFF).toByte(), ((v ushr 8) and 0xFF).toByte())

    /** simulator Concept2Pm5GattProfile.u16le */
    private fun legacyPeripheralU16le(value: Int): ByteArray = byteArrayOf(value.toByte(), (value ushr 8).toByte())

    /** central bluetoothIds.s16le */
    private fun legacyCentralS16le(v: Int): ByteArray {
        val sv = v.toShort()
        return byteArrayOf((sv.toInt() and 0xFF).toByte(), ((sv.toInt() ushr 8) and 0xFF).toByte())
    }

    /** peripheral FtmsSpec.s16le */
    private fun legacyPeripheralS16le(v: Int): ByteArray {
        val s = v.toShort().toInt()
        return byteArrayOf((s and 0xFF).toByte(), ((s ushr 8) and 0xFF).toByte())
    }

    /** central btEngine.ControlPoint.s16le */
    private fun legacyControlPointS16le(v: Int): ByteArray = legacyCentralU16le(v and 0xFFFF)

    /** central bluetoothIds.u24le and peripheral FtmsSpec.u24le */
    private fun legacyU24le(v: Int): ByteArray =
        byteArrayOf((v and 0xFF).toByte(), ((v ushr 8) and 0xFF).toByte(), ((v ushr 16) and 0xFF).toByte())

    /** peripheral FtmsSpec.u32le (Int input, widened here exactly as a caller's Int would be) */
    private fun legacyPeripheralU32le(v: Int): ByteArray = byteArrayOf(
        (v and 0xFF).toByte(),
        ((v ushr 8) and 0xFF).toByte(),
        ((v ushr 16) and 0xFF).toByte(),
        ((v ushr 24) and 0xFF).toByte(),
    )

    /** simulator Concept2Pm5Encoder.Writer / LifeFitnessPacketEncoder.Writer */
    private fun legacySimulatorWriterU8(value: Long): ByteArray =
        byteArrayOf(value.coerceIn(0L, 0xFFL).toInt().toByte())

    private fun legacySimulatorWriterU16(value: Long): ByteArray {
        val v = value.coerceIn(0L, 0xFFFFL).toInt()
        return byteArrayOf((v and 0xFF).toByte(), ((v ushr 8) and 0xFF).toByte())
    }

    private fun legacySimulatorWriterU24(value: Long): ByteArray {
        val v = value.coerceIn(0L, 0xFF_FFFFL).toInt()
        return byteArrayOf((v and 0xFF).toByte(), ((v ushr 8) and 0xFF).toByte(), ((v ushr 16) and 0xFF).toByte())
    }

    /** simulator LifeFitnessPacketEncoder.Writer.s16 */
    private fun legacyLifeFitnessS16(value: Long): ByteArray {
        val v = value.coerceIn(Short.MIN_VALUE.toLong(), Short.MAX_VALUE.toLong()).toInt() and 0xFFFF
        return byteArrayOf((v and 0xFF).toByte(), ((v ushr 8) and 0xFF).toByte())
    }

    /** peripheral CyclingSensors.u32leLong (clamp bound is MAX_WHEEL_REVOLUTIONS = 0xFFFF_FFFF) */
    private fun legacyCyclingU32(value: Long): ByteArray {
        val v = value.coerceIn(0L, 0xFFFF_FFFFL)
        return byteArrayOf(
            (v and 0xFF).toByte(),
            ((v ushr 8) and 0xFF).toByte(),
            ((v ushr 16) and 0xFF).toByte(),
            ((v ushr 24) and 0xFF).toByte(),
        )
    }

    /** simulator KeiserMSeriesSimulatorEncoder.putU16 / PrecorChronoEncoder.putU16 */
    private fun legacyKeiserPutU16(target: ByteArray, offset: Int, value: Long) {
        val bounded = value.coerceIn(0L, 0xFFFFL).toInt()
        target[offset] = bounded.toByte()
        target[offset + 1] = (bounded ushr 8).toByte()
    }

    /** central Concept2Measurement.Cursor / CpsMeasurement.Cursor */
    private class LegacyCursor(private val data: ByteArray) {
        private var offset = 0
        fun u8(): Int? = if (offset < data.size) data[offset++].toInt() and 0xFF else null
        fun u16(): Int? {
            if (data.size - offset < 2) return null
            return u8()!! or (u8()!! shl 8)
        }
        fun u24(): Int? {
            if (data.size - offset < 3) return null
            return u8()!! or (u8()!! shl 8) or (u8()!! shl 16)
        }
        fun u32(): Long? {
            if (data.size - offset < 4) return null
            return u8()!!.toLong() or (u8()!!.toLong() shl 8) or
                (u8()!!.toLong() shl 16) or (u8()!!.toLong() shl 24)
        }
    }
}
