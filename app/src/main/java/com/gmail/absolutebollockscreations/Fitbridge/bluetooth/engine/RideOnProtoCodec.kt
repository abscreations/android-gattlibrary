package com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine

internal sealed interface RideOnProtoField {
    val number: Int
    data class Varint(override val number: Int, val value: Long) : RideOnProtoField
    data class Fixed64(override val number: Int, val bytes: ByteArray) : RideOnProtoField
    data class Bytes(override val number: Int, val value: ByteArray) : RideOnProtoField
    data class Fixed32(override val number: Int, val bytes: ByteArray) : RideOnProtoField
}

internal data class RideOnDecodedPacket(val opcode: Int, val fields: List<RideOnProtoField>)

internal sealed interface RideOnDecodeResult {
    data class Decoded(val packet: RideOnDecodedPacket) : RideOnDecodeResult
    data class Invalid(val reason: String) : RideOnDecodeResult
}

internal data class RideOnSimulationParam(
    val windMpsX100: Int,
    val gradePercentX100: Int,
    val aerodynamicCoefficientX10000: Long,
    val rollingResistanceX100000: Long,
)

internal data class RideOnPhysicalParam(
    val gearRatioX10000: Long? = null,
    val bicycleMassKgX100: Long,
    val riderMassKgX100: Long,
)

/** Small protobuf-v2 wire codec for only the independently authored RideOn schema. */
internal object RideOnProtoCodec {
    fun decode(packet: ByteArray): RideOnDecodeResult {
        if (packet.isEmpty()) return RideOnDecodeResult.Invalid("missing opcode")
        if (packet.size > RideOnUuids.MAX_PACKET_BYTES) {
            return RideOnDecodeResult.Invalid("packet exceeds ${RideOnUuids.MAX_PACKET_BYTES} bytes")
        }
        val reader = Reader(packet, 1)
        val fields = mutableListOf<RideOnProtoField>()
        while (reader.remaining > 0) {
            val key = reader.varint() ?: return RideOnDecodeResult.Invalid(reader.error)
            if (key == 0L || key > Int.MAX_VALUE) return RideOnDecodeResult.Invalid("invalid field key")
            val number = (key ushr 3).toInt()
            if (number == 0) return RideOnDecodeResult.Invalid("field number zero")
            when ((key and 0x07).toInt()) {
                0 -> fields += RideOnProtoField.Varint(
                    number,
                    reader.varint() ?: return RideOnDecodeResult.Invalid(reader.error),
                )
                1 -> fields += RideOnProtoField.Fixed64(
                    number,
                    reader.bytes(8) ?: return RideOnDecodeResult.Invalid(reader.error),
                )
                2 -> {
                    val length = reader.varint()
                        ?: return RideOnDecodeResult.Invalid(reader.error)
                    if (length > Int.MAX_VALUE || length > reader.remaining.toLong()) {
                        return RideOnDecodeResult.Invalid("length exceeds remaining packet")
                    }
                    fields += RideOnProtoField.Bytes(
                        number,
                        reader.bytes(length.toInt())
                            ?: return RideOnDecodeResult.Invalid(reader.error),
                    )
                }
                5 -> fields += RideOnProtoField.Fixed32(
                    number,
                    reader.bytes(4) ?: return RideOnDecodeResult.Invalid(reader.error),
                )
                else -> return RideOnDecodeResult.Invalid("unsupported wire type ${key and 0x07}")
            }
        }
        return RideOnDecodeResult.Decoded(
            RideOnDecodedPacket(packet[0].toInt() and 0xFF, fields),
        )
    }

    fun hubRequest(dataId: Long): ByteArray = packet(
        RideOnUuids.OPCODE_HUB_REQUEST,
        uint32Field(1, dataId),
    )

    fun hubCommand(
        targetPowerWatts: Long? = null,
        simulation: RideOnSimulationParam? = null,
        physical: RideOnPhysicalParam? = null,
    ): ByteArray {
        val body = mutableListOf<Byte>()
        targetPowerWatts?.let { body += uint32Field(3, it).toList() }
        simulation?.let {
            val nested = sint32Field(1, it.windMpsX100) +
                sint32Field(2, it.gradePercentX100) +
                uint32Field(3, it.aerodynamicCoefficientX10000) +
                uint32Field(4, it.rollingResistanceX100000)
            body += bytesField(4, nested).toList()
        }
        physical?.let {
            var nested = byteArrayOf()
            it.gearRatioX10000?.let { ratio -> nested += uint32Field(2, ratio) }
            nested += uint32Field(4, it.bicycleMassKgX100)
            nested += uint32Field(5, it.riderMassKgX100)
            body += bytesField(5, nested).toList()
        }
        return packet(RideOnUuids.OPCODE_HUB_COMMAND, body.toByteArray())
    }

    fun virtualShift(commandType: Long, sequence: Long, gearRatioX10000: Long): ByteArray {
        val payload = uint32Field(7, gearRatioX10000)
        val body = uint32Field(1, commandType) + uint32Field(2, sequence) + bytesField(4, payload)
        return packet(RideOnUuids.OPCODE_VIRTUAL_SHIFT, body)
    }

    fun zigZagEncode32(value: Int): Long =
        ((value.toLong() shl 1) xor (value.toLong() shr 31)) and UINT32_MAX

    fun zigZagDecode32(value: Long): Int {
        requireUint32(value)
        return ((value ushr 1) xor -(value and 1L)).toInt()
    }

    private fun sint32Field(number: Int, value: Int): ByteArray =
        key(number, 0) + varint(zigZagEncode32(value))

    private fun uint32Field(number: Int, value: Long): ByteArray {
        requireUint32(value)
        return key(number, 0) + varint(value)
    }

    private fun bytesField(number: Int, value: ByteArray): ByteArray =
        key(number, 2) + varint(value.size.toLong()) + value

    private fun key(number: Int, wireType: Int): ByteArray {
        require(number > 0)
        return varint((number.toLong() shl 3) or wireType.toLong())
    }

    private fun packet(opcode: Int, body: ByteArray): ByteArray {
        require(opcode in 0..0xFF)
        require(body.size + 1 <= RideOnUuids.MAX_PACKET_BYTES)
        return byteArrayOf(opcode.toByte()) + body
    }

    private fun varint(value: Long): ByteArray {
        require(value >= 0)
        var remaining = value
        val out = mutableListOf<Byte>()
        do {
            var next = (remaining and 0x7F).toInt()
            remaining = remaining ushr 7
            if (remaining != 0L) next = next or 0x80
            out += next.toByte()
        } while (remaining != 0L)
        return out.toByteArray()
    }

    private fun requireUint32(value: Long) = require(value in 0..UINT32_MAX)

    private class Reader(private val data: ByteArray, start: Int) {
        private var offset = start
        var error: String = "truncated field"
            private set
        val remaining get() = data.size - offset

        fun varint(): Long? {
            var value = 0L
            for (index in 0 until MAX_VARINT_BYTES) {
                if (offset >= data.size) {
                    error = "truncated varint"
                    return null
                }
                val next = data[offset++].toInt() and 0xFF
                if (index == MAX_VARINT_BYTES - 1 && next > 1) {
                    error = "overlong varint"
                    return null
                }
                value = value or ((next and 0x7F).toLong() shl (index * 7))
                if (next and 0x80 == 0) return value
            }
            error = "overlong varint"
            return null
        }

        fun bytes(length: Int): ByteArray? {
            if (length < 0 || length > remaining) {
                error = "truncated fixed or byte field"
                return null
            }
            val result = data.copyOfRange(offset, offset + length)
            offset += length
            return result
        }
    }

    private const val MAX_VARINT_BYTES = 10
    private const val UINT32_MAX = 0xFFFF_FFFFL
}
