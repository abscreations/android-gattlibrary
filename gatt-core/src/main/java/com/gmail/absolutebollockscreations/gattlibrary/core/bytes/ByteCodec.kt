package com.gmail.absolutebollockscreations.gattlibrary.core.bytes

/**
 * Little-endian integer reads and writes shared by every library module (L23, LIB-18d).
 *
 * LIB-18b found the same helpers redefined in about eighty places with different behavior, so the
 * behavior is part of each name here and callers choose it explicitly:
 *
 * - Plain readers (`u16(data, offset)`) index without a length check. The caller has already
 *   checked the packet length, as the parsers do, and an out-of-range offset throws.
 * - `…OrNull` readers return null when the bytes are not all present. They never return a
 *   substitute zero: zero is a legitimate reading for almost every field.
 * - `…Wrapping` writers keep the low bits, so an out-of-range value wraps (two's complement for
 *   the signed forms). This is what the FTMS encoders have always done.
 * - `…Saturating` writers clamp to the field's range first, which is what the simulator encoders
 *   rely on. The two produce different bytes for out-of-range input; neither is a default.
 *
 * Pure byte arithmetic: no protocol knowledge, no Android types.
 */
public object LittleEndian {
    // ── unchecked reads ─────────────────────────────────────────────────────────

    public fun u8(data: ByteArray, offset: Int): Int = data[offset].toInt() and 0xFF

    public fun u16(data: ByteArray, offset: Int): Int =
        u8(data, offset) or (u8(data, offset + 1) shl 8)

    public fun s16(data: ByteArray, offset: Int): Int = u16(data, offset).toShort().toInt()

    public fun u24(data: ByteArray, offset: Int): Int =
        u16(data, offset) or (u8(data, offset + 2) shl 16)

    public fun u32(data: ByteArray, offset: Int): Long =
        u16(data, offset).toLong() or (u16(data, offset + 2).toLong() shl 16)

    public fun u48(data: ByteArray, offset: Int): Long =
        u32(data, offset) or (u16(data, offset + 4).toLong() shl 32)

    // ── bounds-checked reads ────────────────────────────────────────────────────

    public fun u8OrNull(data: ByteArray, offset: Int): Int? =
        if (fits(data, offset, 1)) u8(data, offset) else null

    public fun u16OrNull(data: ByteArray, offset: Int): Int? =
        if (fits(data, offset, 2)) u16(data, offset) else null

    public fun s16OrNull(data: ByteArray, offset: Int): Int? =
        if (fits(data, offset, 2)) s16(data, offset) else null

    public fun u24OrNull(data: ByteArray, offset: Int): Int? =
        if (fits(data, offset, 3)) u24(data, offset) else null

    public fun u32OrNull(data: ByteArray, offset: Int): Long? =
        if (fits(data, offset, 4)) u32(data, offset) else null

    // ── wrapping writes ─────────────────────────────────────────────────────────

    public fun u8Wrapping(value: Int): ByteArray = byteArrayOf(value.toByte())

    public fun u16Wrapping(value: Int): ByteArray =
        byteArrayOf(value.toByte(), (value ushr 8).toByte())

    /** Same octets as [u16Wrapping]; the name documents a two's-complement field at the call site. */
    public fun s16Wrapping(value: Int): ByteArray = u16Wrapping(value)

    public fun u24Wrapping(value: Int): ByteArray =
        byteArrayOf(value.toByte(), (value ushr 8).toByte(), (value ushr 16).toByte())

    public fun u32Wrapping(value: Long): ByteArray = byteArrayOf(
        value.toByte(),
        (value ushr 8).toByte(),
        (value ushr 16).toByte(),
        (value ushr 24).toByte(),
    )

    public fun putU16Wrapping(target: ByteArray, offset: Int, value: Int) {
        target[offset] = value.toByte()
        target[offset + 1] = (value ushr 8).toByte()
    }

    public fun putU24Wrapping(target: ByteArray, offset: Int, value: Int) {
        putU16Wrapping(target, offset, value)
        target[offset + 2] = (value ushr 16).toByte()
    }

    // ── saturating writes ───────────────────────────────────────────────────────

    public fun u8Saturating(value: Long): ByteArray = u8Wrapping(value.coerceIn(0L, U8_MAX).toInt())

    public fun u16Saturating(value: Long): ByteArray = u16Wrapping(value.coerceIn(0L, U16_MAX).toInt())

    public fun s16Saturating(value: Long): ByteArray =
        u16Wrapping(value.coerceIn(S16_MIN, S16_MAX).toInt())

    public fun u24Saturating(value: Long): ByteArray = u24Wrapping(value.coerceIn(0L, U24_MAX).toInt())

    public fun u32Saturating(value: Long): ByteArray = u32Wrapping(value.coerceIn(0L, U32_MAX))

    public fun putU16Saturating(target: ByteArray, offset: Int, value: Long) {
        putU16Wrapping(target, offset, value.coerceIn(0L, U16_MAX).toInt())
    }

    public fun putU24Saturating(target: ByteArray, offset: Int, value: Long) {
        putU24Wrapping(target, offset, value.coerceIn(0L, U24_MAX).toInt())
    }

    public const val U8_MAX: Long = 0xFFL
    public const val U16_MAX: Long = 0xFFFFL
    public const val U24_MAX: Long = 0xFF_FFFFL
    public const val U32_MAX: Long = 0xFFFF_FFFFL
    public const val S16_MIN: Long = -0x8000L
    public const val S16_MAX: Long = 0x7FFFL
}

/**
 * Big-endian reads, for the protocols that use network order (Concept2 CSAFE framing).
 * Same unchecked / `…OrNull` convention as [LittleEndian].
 */
public object BigEndian {
    public fun u16(data: ByteArray, offset: Int): Int =
        (LittleEndian.u8(data, offset) shl 8) or LittleEndian.u8(data, offset + 1)

    public fun u32(data: ByteArray, offset: Int): Long =
        (u16(data, offset).toLong() shl 16) or u16(data, offset + 2).toLong()

    public fun u16OrNull(data: ByteArray, offset: Int): Int? =
        if (fits(data, offset, 2)) u16(data, offset) else null

    public fun u32OrNull(data: ByteArray, offset: Int): Long? =
        if (fits(data, offset, 4)) u32(data, offset) else null
}

/** Why the last [ByteCursor] read returned null. */
public enum class ByteCursorFailure {
    /** Fewer bytes remained than the field needs. */
    TRUNCATED,

    /** A varint ran past [ProtobufVarint.MAX_BYTES] or overflowed 64 bits. */
    OVERLONG_VARINT,
}

/**
 * Sequential, bounds-checked little-endian reader.
 *
 * Every read returns null instead of throwing when the field is not fully present, and a failed
 * read does not advance [offset] (a failed varint read may have consumed the bytes it examined,
 * as a protobuf decoder does; the caller abandons the packet either way).
 */
public class ByteCursor(private val data: ByteArray, start: Int = 0) {
    init {
        require(start in 0..data.size) { "start $start outside 0..${data.size}" }
    }

    public var offset: Int = start
        private set

    public val remaining: Int
        get() = data.size - offset

    /** Reason for the most recent null, or null if the most recent read succeeded. */
    public var lastFailure: ByteCursorFailure? = null
        private set

    public fun u8(): Int? = take(1) { LittleEndian.u8(data, it) }

    public fun u16(): Int? = take(2) { LittleEndian.u16(data, it) }

    public fun s16(): Int? = take(2) { LittleEndian.s16(data, it) }

    public fun u24(): Int? = take(3) { LittleEndian.u24(data, it) }

    public fun u32(): Long? = take(4) { LittleEndian.u32(data, it) }

    /** Copy of the next [count] bytes, or null when fewer remain or [count] is negative. */
    public fun bytes(count: Int): ByteArray? {
        if (count < 0) return fail<ByteArray>(ByteCursorFailure.TRUNCATED)
        return take(count) { data.copyOfRange(it, it + count) }
    }

    /** Advance past [count] bytes; false (without moving) when fewer remain or [count] is negative. */
    public fun skip(count: Int): Boolean {
        if (count < 0) {
            fail<Unit>(ByteCursorFailure.TRUNCATED)
            return false
        }
        return take(count) { } != null
    }

    /** Unsigned protobuf base-128 varint (at most [ProtobufVarint.MAX_BYTES] octets). */
    public fun varint(): Long? {
        var value = 0L
        for (index in 0 until ProtobufVarint.MAX_BYTES) {
            if (offset >= data.size) return fail<Long>(ByteCursorFailure.TRUNCATED)
            val next = data[offset++].toInt() and 0xFF
            if (index == ProtobufVarint.MAX_BYTES - 1 && next > 1) {
                return fail<Long>(ByteCursorFailure.OVERLONG_VARINT)
            }
            value = value or ((next and 0x7F).toLong() shl (index * 7))
            if (next and 0x80 == 0) {
                lastFailure = null
                return value
            }
        }
        return fail<Long>(ByteCursorFailure.OVERLONG_VARINT)
    }

    private inline fun <T> take(count: Int, read: (Int) -> T): T? {
        if (remaining < count) return fail<T>(ByteCursorFailure.TRUNCATED)
        val start = offset
        offset += count
        lastFailure = null
        return read(start)
    }

    private fun <T> fail(reason: ByteCursorFailure): T? {
        lastFailure = reason
        return null
    }
}

/** Protobuf wire-format primitives used by the RideOn protocol (encode side; decode is [ByteCursor.varint]). */
public object ProtobufVarint {
    public const val MAX_BYTES: Int = 10

    /** Base-128 varint of a non-negative value. */
    public fun encode(value: Long): ByteArray {
        require(value >= 0) { "varint value must be non-negative: $value" }
        var remaining = value
        val out = ArrayList<Byte>(MAX_BYTES)
        do {
            var next = (remaining and 0x7F).toInt()
            remaining = remaining ushr 7
            if (remaining != 0L) next = next or 0x80
            out += next.toByte()
        } while (remaining != 0L)
        return out.toByteArray()
    }

    /** ZigZag-encode a signed 32-bit value into its unsigned 32-bit wire form. */
    public fun zigZagEncode32(value: Int): Long =
        ((value.toLong() shl 1) xor (value.toLong() shr 31)) and LittleEndian.U32_MAX

    /** Inverse of [zigZagEncode32]; [value] must be an unsigned 32-bit quantity. */
    public fun zigZagDecode32(value: Long): Int {
        require(value in 0..LittleEndian.U32_MAX) { "zigzag32 value out of range: $value" }
        return ((value ushr 1) xor -(value and 1L)).toInt()
    }
}

private fun fits(data: ByteArray, offset: Int, width: Int): Boolean =
    offset >= 0 && offset <= data.size - width
