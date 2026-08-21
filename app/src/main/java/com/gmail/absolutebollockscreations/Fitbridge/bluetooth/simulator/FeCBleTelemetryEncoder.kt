package com.gmail.absolutebollockscreations.Fitbridge.bluetooth.simulator

import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine.BluetoothIds
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * Pure page 16 / page 25 / page 81 + ANT envelope encoder (plan §7, §10).
 *
 * Every encoder here takes an immutable snapshot of [FeCBleMachineState]; none of them advance
 * time or counters, which is [FeCBleSyntheticModel]'s job, so multiple pages rendered from one
 * tick stay coherent by construction.
 *
 * Deliberately **not** a thin call into a production central decoder (plan §4.2): the simulator
 * and the future central `FeCFrameCodec`/`FeCPageParsers` must be independently pinned against the
 * same external vectors so a single offset bug cannot pass both sides at once.
 */
internal object FeCBleTelemetryEncoder {

    /** ANT serial envelope around 8 page bytes: sync, length, msg-id, channel, page, XOR checksum. */
    fun buildAntFrame(pageBytes: ByteArray, messageType: Int = BluetoothIds.FEC_ANT_MSG_BROADCAST_DATA): ByteArray {
        require(pageBytes.size == 8) { "an FE-C page is exactly eight bytes, got ${pageBytes.size}" }
        val frame = ByteArray(13)
        frame[0] = BluetoothIds.FEC_ANT_SYNC.toByte()
        frame[1] = 0x09 // declared length: channel byte + 8 page bytes
        frame[2] = messageType.toByte()
        frame[3] = BluetoothIds.FEC_DEFAULT_CHANNEL.toByte()
        pageBytes.copyInto(frame, destinationOffset = 4)
        frame[12] = xorChecksum(frame, 0, 12).toByte()
        return frame
    }

    /** XOR of `frame[from until to)`, exclusive of `to`. */
    fun xorChecksum(frame: ByteArray, from: Int, to: Int): Int {
        var checksum = 0
        for (i in from until to) checksum = checksum xor (frame[i].toInt() and 0xFF)
        return checksum
    }

    /** Page 16 — General FE Data (`0x10`). Plan §10.1. */
    fun encodePage16(state: FeCBleMachineState): ByteArray {
        val page = ByteArray(8)
        page[0] = BluetoothIds.FEC_PAGE_GENERAL_FE_DATA.toByte()
        page[1] = (state.equipmentType.wireValue and 0x1F).toByte()
        page[2] = (state.elapsedQuarterSeconds and 0xFF).toByte()
        page[3] = if (state.distanceCapable) {
            (floor(state.distanceMeters).toLong() and 0xFF).toByte()
        } else {
            0x00
        }
        val speedRaw = (state.speedMps * 1000.0).roundToInt().coerceIn(0, 0xFFFF)
        page[4] = (speedRaw and 0xFF).toByte()
        page[5] = ((speedRaw ushr 8) and 0xFF).toByte()
        page[6] = (state.heartRateBpm?.coerceIn(0, 0xFE) ?: 0xFF).toByte()
        val hrSourceBits = if (state.heartRateBpm == null) 0 else state.heartRateSource.wireValue
        var capabilities = hrSourceBits and 0x03
        if (state.distanceCapable) capabilities = capabilities or 0x04
        if (state.virtualSpeed) capabilities = capabilities or 0x08
        capabilities = capabilities or ((state.feState.wireValue and 0x07) shl 4)
        if (state.lapToggle) capabilities = capabilities or 0x80
        page[7] = capabilities.toByte()
        return page
    }

    /** Page 25 — Specific Trainer Data (`0x19`). Plan §10.2. */
    fun encodePage25(state: FeCBleMachineState): ByteArray {
        val page = ByteArray(8)
        page[0] = BluetoothIds.FEC_PAGE_SPECIFIC_TRAINER_DATA.toByte()
        page[1] = (state.eventCount and 0xFF).toByte()
        page[2] = (state.cadenceRpm?.coerceIn(0, 0xFE) ?: 0xFF).toByte()
        val accPower = (state.accumulatedPower and 0xFFFF)
        page[3] = (accPower and 0xFF).toByte()
        page[4] = ((accPower ushr 8) and 0xFF).toByte()
        val instPower = state.instantaneousPowerWatts?.coerceIn(0, 0xFFE) ?: 0xFFF
        page[5] = (instPower and 0xFF).toByte()
        val nibbleByte = ((instPower ushr 8) and 0x0F) or ((state.trainerStatusRaw and 0x0F) shl 4)
        page[6] = nibbleByte.toByte()
        var byte7 = state.targetStatusRaw and 0x03
        byte7 = byte7 or ((state.feState.wireValue and 0x07) shl 4)
        page[7] = byte7.toByte()
        return page
    }

    /**
     * Page 19 — Specific Treadmill Data (`0x13`). Table 8-15: bytes 1-3 reserved, byte 4
     * cadence, bytes 5/6 negative/positive accumulated vertical distance (each independently
     * gated by its own capability bit). Vertical distance is not simulated (both distance
     * fields stay at zero, both capability bits stay clear) - this project has no incline model
     * for the treadmill persona yet, and an unset capability bit is the spec-honest way to say
     * "do not interpret these bytes" rather than transmitting a fabricated zero as if it were
     * real data (mirrors [FeCCapabilities]' "never claim a range we can't back" norm on the
     * central side).
     */
    fun encodePage19(state: FeCBleMachineState): ByteArray {
        val page = ByteArray(8)
        page[0] = BluetoothIds.FEC_PAGE_SPECIFIC_TREADMILL_DATA.toByte()
        page[1] = 0xFF.toByte()
        page[2] = 0xFF.toByte()
        page[3] = 0xFF.toByte()
        page[4] = (state.cadenceRpm?.coerceIn(0, 0xFE) ?: 0xFF).toByte()
        page[5] = (state.negativeVerticalDistanceDeciMeters and 0xFF).toByte()
        page[6] = (state.positiveVerticalDistanceDeciMeters and 0xFF).toByte()
        // Capability bits 0/1 (positive/negative vertical distance transmitted) stay clear -
        // see the class doc above. Bits 4-6 carry FE state, matching every other page's layout.
        val byte7 = (state.feState.wireValue and 0x07) shl 4
        page[7] = byte7.toByte()
        return page
    }

    /**
     * Page 22 — Specific Rower Data (`0x16`). Table 8-19: bytes 1-2 reserved, byte 3 accumulated
     * stroke count, byte 4 cadence, bytes 5-6 instantaneous power (uint16LE) - this correction
     * (bytes 1-2 both reserved, stroke count at byte 3) supersedes an earlier lower-confidence
     * single-source extraction of this page that had misplaced the stroke count at byte 2.
     */
    fun encodePage22(state: FeCBleMachineState): ByteArray {
        val page = ByteArray(8)
        page[0] = BluetoothIds.FEC_PAGE_SPECIFIC_ROWER_DATA.toByte()
        page[1] = 0xFF.toByte()
        page[2] = 0xFF.toByte()
        page[3] = (state.strokeCount and 0xFF).toByte()
        page[4] = (state.cadenceRpm?.coerceIn(0, 0xFE) ?: 0xFF).toByte()
        val instPower = state.instantaneousPowerWatts?.coerceIn(0, 0xFFFE) ?: 0xFFFF
        page[5] = (instPower and 0xFF).toByte()
        page[6] = ((instPower ushr 8) and 0xFF).toByte()
        // Bit 0 (accumulated strokes transmitted in byte 3) is always set: recordRowerEvent
        // always advances strokeCount while IN_USE, so byte 3 is always meaningful here.
        var byte7 = 0x01
        byte7 = byte7 or ((state.feState.wireValue and 0x07) shl 4)
        page[7] = byte7.toByte()
        return page
    }

    /** Page 81 — Product Information (`0x51`). Plan §10.3. Synthetic serial only; never host identity. */
    fun encodePage81(state: FeCBleMachineState): ByteArray {
        val page = ByteArray(8)
        page[0] = BluetoothIds.FEC_PAGE_PRODUCT_INFORMATION.toByte()
        page[1] = 0xFF.toByte()
        page[2] = (state.softwareRevisionSupplemental and 0xFF).toByte()
        page[3] = (state.softwareRevisionMain and 0xFF).toByte()
        val serial = state.serialNumber and 0xFFFFFFFFL
        page[4] = (serial and 0xFF).toByte()
        page[5] = ((serial ushr 8) and 0xFF).toByte()
        page[6] = ((serial ushr 16) and 0xFF).toByte()
        page[7] = ((serial ushr 24) and 0xFF).toByte()
        return page
    }

    fun encodePage16Frame(state: FeCBleMachineState): ByteArray = buildAntFrame(encodePage16(state))
    fun encodePage19Frame(state: FeCBleMachineState): ByteArray = buildAntFrame(encodePage19(state))
    fun encodePage22Frame(state: FeCBleMachineState): ByteArray = buildAntFrame(encodePage22(state))
    fun encodePage25Frame(state: FeCBleMachineState): ByteArray = buildAntFrame(encodePage25(state))
    fun encodePage81Frame(state: FeCBleMachineState): ByteArray = buildAntFrame(encodePage81(state))
}
