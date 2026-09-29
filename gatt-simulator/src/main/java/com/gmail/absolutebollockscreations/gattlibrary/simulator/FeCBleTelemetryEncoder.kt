package com.gmail.absolutebollockscreations.gattlibrary.simulator

import com.gmail.absolutebollockscreations.gattlibrary.central.BluetoothIds
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

    /** Page 17 — General Settings (official FE profile Rev 5.0, Table 8-11). */
    fun encodePage17(state: FeCBleMachineState): ByteArray {
        val page = ByteArray(8)
        page[0] = BluetoothIds.FEC_PAGE_GENERAL_SETTINGS.toByte()
        page[1] = 0xFF.toByte()
        page[2] = 0xFF.toByte()
        page[3] = (state.cycleLengthMeters * 100.0).roundToInt().coerceIn(0, 0xFE).toByte()
        val inclineRaw = (state.inclinePercent * 100.0).roundToInt().coerceIn(-10_000, 10_000)
        page[4] = (inclineRaw and 0xFF).toByte()
        page[5] = ((inclineRaw ushr 8) and 0xFF).toByte()
        page[6] = (state.resistanceLevelPercent * 2.0).roundToInt().coerceIn(0, 200).toByte()
        page[7] = ((state.feState.wireValue and 0x07) shl 4).toByte()
        return page
    }

    /** Page 18 — General FE Metabolic Data (Table 8-13). */
    fun encodePage18(state: FeCBleMachineState): ByteArray {
        val page = ByteArray(8)
        page[0] = BluetoothIds.FEC_PAGE_GENERAL_METABOLIC_DATA.toByte()
        page[1] = 0xFF.toByte()
        val metsRaw = (state.metabolicEquivalent * 100.0).roundToInt().coerceIn(0, 10_000)
        page[2] = (metsRaw and 0xFF).toByte()
        page[3] = ((metsRaw ushr 8) and 0xFF).toByte()
        val burnRateRaw = (state.caloricBurnRateKcalPerHour * 10.0).roundToInt().coerceIn(0, 0xFFFE)
        page[4] = (burnRateRaw and 0xFF).toByte()
        page[5] = ((burnRateRaw ushr 8) and 0xFF).toByte()
        page[6] = (floor(state.accumulatedCaloriesKcal).toLong() and 0xFF).toByte()
        page[7] = (0x01 or ((state.feState.wireValue and 0x07) shl 4)).toByte()
        return page
    }

    /** Page 25 — Specific Trainer Data (`0x19`). Plan §10.2. */
    fun encodePage25(state: FeCBleMachineState, powerAvailable: Boolean = true): ByteArray {
        val page = ByteArray(8)
        page[0] = BluetoothIds.FEC_PAGE_SPECIFIC_TRAINER_DATA.toByte()
        page[1] = (state.eventCount and 0xFF).toByte()
        page[2] = (state.cadenceRpm?.coerceIn(0, 0xFE) ?: 0xFF).toByte()
        val accPower = if (powerAvailable) state.accumulatedPower and 0xFFFF else 0L
        page[3] = (accPower and 0xFF).toByte()
        page[4] = ((accPower ushr 8) and 0xFF).toByte()
        val instPower = if (powerAvailable) {
            state.instantaneousPowerWatts?.coerceIn(0, 0xFFE) ?: 0xFFF
        } else {
            0xFFF
        }
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
     * gated by its own capability bit). The general-settings incline fixture drives coherent
     * accumulated vertical distance, so both capability bits are set.
     */
    fun encodePage19(state: FeCBleMachineState): ByteArray {
        val page = ByteArray(8)
        page[0] = BluetoothIds.FEC_PAGE_SPECIFIC_TREADMILL_DATA.toByte()
        page[1] = 0xFF.toByte()
        page[2] = 0xFF.toByte()
        page[3] = 0xFF.toByte()
        page[4] = (state.cadenceRpm?.coerceIn(0, 0xFE) ?: 0xFF).toByte()
        page[5] = (floor(state.negativeVerticalDistanceMeters * 10.0).toLong() and 0xFF).toByte()
        page[6] = (floor(state.positiveVerticalDistanceMeters * 10.0).toLong() and 0xFF).toByte()
        // Both accumulated vertical-distance fields are present in this deterministic fixture.
        val byte7 = 0x03 or ((state.feState.wireValue and 0x07) shl 4)
        page[7] = byte7.toByte()
        return page
    }

    /** Page 20 — Specific Elliptical Data (Table 8-17). */
    fun encodePage20(state: FeCBleMachineState): ByteArray {
        val page = ByteArray(8)
        page[0] = BluetoothIds.FEC_PAGE_SPECIFIC_ELLIPTICAL_DATA.toByte()
        page[1] = 0xFF.toByte()
        page[2] = (floor(state.positiveVerticalDistanceMeters * 10.0).toLong() and 0xFF).toByte()
        page[3] = (floor(state.movementCount).toLong() and 0xFF).toByte()
        page[4] = (state.cadenceRpm?.coerceIn(0, 0xFE) ?: 0xFF).toByte()
        putU16(page, 5, state.instantaneousPowerWatts?.coerceIn(0, 0xFFFE) ?: 0xFFFF)
        page[7] = (0x03 or ((state.feState.wireValue and 0x07) shl 4)).toByte()
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
        page[3] = (floor(state.movementCount).toLong() and 0xFF).toByte()
        page[4] = (state.cadenceRpm?.coerceIn(0, 0xFE) ?: 0xFF).toByte()
        val instPower = state.instantaneousPowerWatts?.coerceIn(0, 0xFFFE) ?: 0xFFFF
        page[5] = (instPower and 0xFF).toByte()
        page[6] = ((instPower ushr 8) and 0xFF).toByte()
        // Bit 0 is set because the model advances movementCount from the rower's cadence.
        var byte7 = 0x01
        byte7 = byte7 or ((state.feState.wireValue and 0x07) shl 4)
        page[7] = byte7.toByte()
        return page
    }

    /** Page 23 — Specific Climber Data (Table 8-21). */
    fun encodePage23(state: FeCBleMachineState): ByteArray = encodeStridePowerPage(
        state = state,
        pageNumber = BluetoothIds.FEC_PAGE_SPECIFIC_CLIMBER_DATA,
    )

    /** Page 24 — Specific Nordic Skier Data (Table 8-23). */
    fun encodePage24(state: FeCBleMachineState): ByteArray = encodeStridePowerPage(
        state = state,
        pageNumber = BluetoothIds.FEC_PAGE_SPECIFIC_NORDIC_SKIER_DATA,
    )

    /** Page 26 — Specific Trainer Torque Data (Table 8-29). */
    fun encodePage26(state: FeCBleMachineState): ByteArray {
        val page = ByteArray(8)
        page[0] = BluetoothIds.FEC_PAGE_SPECIFIC_TRAINER_TORQUE_DATA.toByte()
        page[1] = (state.torqueEventCount and 0xFF).toByte()
        page[2] = (state.wheelTicks and 0xFF).toByte()
        putU16(page, 3, (state.wheelPeriodTicks and 0xFFFF).toInt())
        putU16(page, 5, (floor(state.accumulatedTorqueThirtySecondNm).toLong() and 0xFFFF).toInt())
        page[7] = ((state.feState.wireValue and 0x07) shl 4).toByte()
        return page
    }

    /** Page 54 — FE Capabilities (Tables 8-45/8-46). */
    fun encodePage54(state: FeCBleMachineState): ByteArray {
        val page = ByteArray(8)
        page[0] = BluetoothIds.FEC_PAGE_FE_CAPABILITIES.toByte()
        for (offset in 1..4) page[offset] = 0xFF.toByte()
        putU16(page, 5, state.equipmentType.maximumResistanceNewtons)
        page[7] = if (state.controlEnabled && state.equipmentType.supportsControl) 0x07 else 0x00
        return page
    }

    /** Common Page 80 — Manufacturer's Information (Common Data Pages Rev 3.1, Table 8-2). */
    fun encodePage80(hardwareRevision: Int, manufacturerId: Int, modelNumber: Int): ByteArray {
        require(hardwareRevision in 0..0xFF)
        require(manufacturerId in 0..0xFFFF)
        require(modelNumber in 0..0xFFFF)
        val page = ByteArray(8)
        page[0] = BluetoothIds.FEC_PAGE_MANUFACTURER_INFORMATION.toByte()
        page[1] = 0xFF.toByte()
        page[2] = 0xFF.toByte()
        page[3] = hardwareRevision.toByte()
        putU16(page, 4, manufacturerId)
        putU16(page, 6, modelNumber)
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
    fun encodePage17Frame(state: FeCBleMachineState): ByteArray = buildAntFrame(encodePage17(state))
    fun encodePage18Frame(state: FeCBleMachineState): ByteArray = buildAntFrame(encodePage18(state))
    fun encodePage19Frame(state: FeCBleMachineState): ByteArray = buildAntFrame(encodePage19(state))
    fun encodePage20Frame(state: FeCBleMachineState): ByteArray = buildAntFrame(encodePage20(state))
    fun encodePage22Frame(state: FeCBleMachineState): ByteArray = buildAntFrame(encodePage22(state))
    fun encodePage23Frame(state: FeCBleMachineState): ByteArray = buildAntFrame(encodePage23(state))
    fun encodePage24Frame(state: FeCBleMachineState): ByteArray = buildAntFrame(encodePage24(state))
    fun encodePage25Frame(
        state: FeCBleMachineState,
        powerAvailable: Boolean = true,
    ): ByteArray = buildAntFrame(encodePage25(state, powerAvailable))
    fun encodePage26Frame(state: FeCBleMachineState): ByteArray = buildAntFrame(encodePage26(state))
    fun encodePage54Frame(
        state: FeCBleMachineState,
        messageType: Int = BluetoothIds.FEC_ANT_MSG_BROADCAST_DATA,
    ): ByteArray = buildAntFrame(encodePage54(state), messageType)
    fun encodePage80Frame(
        hardwareRevision: Int,
        manufacturerId: Int,
        modelNumber: Int,
        messageType: Int = BluetoothIds.FEC_ANT_MSG_BROADCAST_DATA,
    ): ByteArray = buildAntFrame(
        encodePage80(hardwareRevision, manufacturerId, modelNumber),
        messageType,
    )
    fun encodePage81Frame(state: FeCBleMachineState): ByteArray = buildAntFrame(encodePage81(state))

    private fun encodeStridePowerPage(state: FeCBleMachineState, pageNumber: Int): ByteArray {
        val page = ByteArray(8)
        page[0] = pageNumber.toByte()
        page[1] = 0xFF.toByte()
        page[2] = 0xFF.toByte()
        page[3] = (floor(state.movementCount).toLong() and 0xFF).toByte()
        page[4] = (state.cadenceRpm?.coerceIn(0, 0xFE) ?: 0xFF).toByte()
        putU16(page, 5, state.instantaneousPowerWatts?.coerceIn(0, 0xFFFE) ?: 0xFFFF)
        page[7] = (0x01 or ((state.feState.wireValue and 0x07) shl 4)).toByte()
        return page
    }

    private fun putU16(page: ByteArray, offset: Int, value: Int) {
        page[offset] = (value and 0xFF).toByte()
        page[offset + 1] = ((value ushr 8) and 0xFF).toByte()
    }
}
