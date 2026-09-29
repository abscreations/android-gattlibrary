package com.gmail.absolutebollockscreations.gattlibrary.central

import com.gmail.absolutebollockscreations.gattlibrary.core.FitBridgeBleInternalApi

/**
 * The 4-value FE State enum ANT+ FE-C packs into the high bits of page 16 byte 7 and page 25
 * byte 7 (bits 4-6 of each). Values outside this set (0, 5, 6, 7) are reserved/undefined by
 * the spec and are surfaced as `null` rather than guessed at.
 */
@FitBridgeBleInternalApi
enum class FeCEquipmentState(val wireValue: Int) {
    ASLEEP_OR_OFF(1),
    READY(2),
    IN_USE(3),
    FINISHED_OR_PAUSED(4),
    ;

    companion object {
        fun fromWireValue(value: Int): FeCEquipmentState? = entries.firstOrNull { it.wireValue == value }
    }
}

/**
 * A decoded FE-C data page, per `docs/fe-c-ble_central_implementation.md` §2.4/§8.
 *
 * Each variant carries only the raw decoded fields for its page - unit conversion,
 * rollover-corrected accumulation, and mapping onto `MetricKind` are `FeCProtocolSession`'s
 * and (later) `FeCMetricMapper`'s job, not this file's. Keeping this layer pure and
 * side-effect-free is what makes it directly unit-testable against byte vectors without any
 * GATT or engine-state scaffolding.
 */
@FitBridgeBleInternalApi
sealed interface FeCPage {

    /**
     * Page 16 - General FE Data. Present on every FE-C device.
     *
     * @property elapsedTimeRaw Byte 2, quarter-second units, rolls over at 256 (~64s).
     * @property distanceTraveledRaw Byte 3, meters, rolls over at 256. Only meaningful when
     *   [distanceTraveledEnabled] is true (capabilities byte bit 2).
     * @property speedMetersPerSecond Bytes 4-5, uint16LE, 0.001 m/s resolution.
     * @property heartRateBpm Byte 6, or `null` for the `0xFF` "unavailable" sentinel.
     * @property capabilitiesByte Byte 7 verbatim, kept for diagnostics/future HR-source use.
     * @property equipmentState Bits 4-6 of byte 7, decoded via [FeCEquipmentState].
     */
    data class GeneralFe(
        val equipmentType: Int,
        val elapsedTimeRaw: Int,
        val distanceTraveledRaw: Int,
        val distanceTraveledEnabled: Boolean,
        val speedMetersPerSecond: Double,
        val heartRateBpm: Int?,
        val capabilitiesByte: Int,
        val equipmentState: FeCEquipmentState?,
    ) : FeCPage

    data class GeneralSettings(
        val cycleLengthMeters: Double?,
        val inclinePercent: Double?,
        val resistancePercent: Double?,
        val equipmentState: FeCEquipmentState?,
    ) : FeCPage

    data class GeneralMetabolicData(
        val metabolicEquivalent: Double?,
        val caloricBurnRateKcalPerHour: Double?,
        val accumulatedCaloriesRaw: Int,
        val accumulatedCaloriesEnabled: Boolean,
        val equipmentState: FeCEquipmentState?,
    ) : FeCPage

    /**
     * Page 25 - Specific Trainer Data.
     *
     * @property eventCountRaw Byte 1, rolls over at 256.
     * @property cadenceRpm Byte 2, or `null` for the `0xFF` sentinel.
     * @property accumulatedPowerRaw Bytes 3-4, uint16LE watt-seconds-equivalent counter,
     *   rolls over at 65536.
     * @property instantaneousPowerWatts 12-bit value spanning byte 5 (low 8 bits) and the
     *   low nibble of byte 6 (high 4 bits), or `null` for the `0xFFF` sentinel.
     * @property trainerStatus High nibble of byte 6, verbatim (flag bitmask, not decoded here).
     * @property targetPowerStatus Bits 0-1 of byte 7, verbatim.
     * @property equipmentState Bits 4-6 of byte 7, decoded via [FeCEquipmentState].
     */
    data class TrainerData(
        val eventCountRaw: Int,
        val cadenceRpm: Int?,
        val accumulatedPowerRaw: Int,
        val instantaneousPowerWatts: Int?,
        val trainerStatus: Int,
        val targetPowerStatus: Int,
        val equipmentState: FeCEquipmentState?,
    ) : FeCPage

    /**
     * Page 19 - Specific Treadmill Data. Per the official ANT+ FE-C spec (D000001231 Rev 5.0,
     * Table 8-15): bytes 1-3 are reserved (do not interpret, unlike page 25's meaningful byte
     * layout), byte 4 is cadence, bytes 5/6 are the negative/positive accumulated vertical
     * distance fields, each independently gated by its own capability bit rather than a shared
     * "distance enabled" flag the way page 16 has one.
     *
     * @property cadenceRpm Byte 4, instantaneous strides/min, or `null` for the `0xFF` sentinel.
     * @property negativeVerticalDistanceRaw Byte 5, accumulated, 0.1 m units, rolls over at 256
     *   (-25.6 m). Only meaningful when [negativeVerticalDistanceEnabled] is true.
     * @property positiveVerticalDistanceRaw Byte 6, accumulated, 0.1 m units, rolls over at 256
     *   (25.6 m). Only meaningful when [positiveVerticalDistanceEnabled] is true.
     */
    data class SpecificTreadmillData(
        val cadenceRpm: Int?,
        val negativeVerticalDistanceRaw: Int,
        val negativeVerticalDistanceEnabled: Boolean,
        val positiveVerticalDistanceRaw: Int,
        val positiveVerticalDistanceEnabled: Boolean,
        val equipmentState: FeCEquipmentState?,
    ) : FeCPage

    data class SpecificEllipticalData(
        val positiveVerticalDistanceRaw: Int,
        val positiveVerticalDistanceEnabled: Boolean,
        val strideCountRaw: Int,
        val strideCountEnabled: Boolean,
        val cadenceRpm: Int?,
        val instantaneousPowerWatts: Int?,
        val equipmentState: FeCEquipmentState?,
    ) : FeCPage

    /**
     * Page 22 - Specific Rower Data. Per the official ANT+ FE-C spec (D000001231 Rev 5.0, Table
     * 8-19): bytes 1-2 are reserved (do not interpret) - note this corrects an earlier
     * lower-confidence extraction of this same page that had wrongly placed stroke count at
     * byte 2 with only byte 1 reserved; the primary spec document confirms stroke count is at
     * byte 3, with bytes 1-2 both reserved.
     *
     * @property strokeCountRaw Byte 3, accumulated, 1 stroke units, rolls over at 256. Only
     *   meaningful when [strokeCountEnabled] is true (capabilities byte bit 0).
     * @property cadenceRpm Byte 4, instantaneous strokes/min, or `null` for the `0xFF` sentinel.
     * @property instantaneousPowerWatts Bytes 5-6, uint16LE, 1 W resolution, or `null` for the
     *   `0xFFFF` sentinel.
     */
    data class SpecificRowerData(
        val strokeCountRaw: Int,
        val strokeCountEnabled: Boolean,
        val cadenceRpm: Int?,
        val instantaneousPowerWatts: Int?,
        val equipmentState: FeCEquipmentState?,
    ) : FeCPage

    data class SpecificClimberData(
        val cycleCountRaw: Int,
        val cycleCountEnabled: Boolean,
        val cadenceRpm: Int?,
        val instantaneousPowerWatts: Int?,
        val equipmentState: FeCEquipmentState?,
    ) : FeCPage

    data class SpecificNordicSkierData(
        val strideCountRaw: Int,
        val strideCountEnabled: Boolean,
        val cadenceRpm: Int?,
        val instantaneousPowerWatts: Int?,
        val equipmentState: FeCEquipmentState?,
    ) : FeCPage

    data class TrainerTorqueData(
        val eventCountRaw: Int,
        val wheelTicksRaw: Int,
        val accumulatedWheelPeriodRaw: Int,
        val accumulatedTorqueRaw: Int,
        val equipmentState: FeCEquipmentState?,
    ) : FeCPage

    data class FeCapabilities(
        val maximumResistanceNewtons: Int?,
        val supportsBasicResistance: Boolean,
        val supportsTargetPower: Boolean,
        val supportsSimulation: Boolean,
    ) : FeCPage

    /** Common Page 80 (0x50) — static manufacturer/model identity, not workout telemetry. */
    data class ManufacturerInformation(
        val hardwareRevision: Int,
        val manufacturerId: Int,
        val modelNumber: Int,
    ) : FeCPage

    /**
     * Page 81 (0x51) - Product Information.
     *
     * @property serialNumber Bytes 4-7, **unsigned** uint32LE, or `null` for the
     *   `0xFFFFFFFF` sentinel. Incyclist reads this with a signed `readInt32LE()`, which
     *   turns any serial `>= 0x80000000` negative; this deliberately does not copy that bug.
     */
    data class ProductInformation(
        val supplementalSoftwareRevision: Int,
        val mainSoftwareRevision: Int,
        val serialNumber: Long?,
    ) : FeCPage

    data class Unknown(val page: Int) : FeCPage
}

@FitBridgeBleInternalApi
object FeCPageParsers {

    private const val HR_UNAVAILABLE = 0xFF
    private const val CADENCE_UNAVAILABLE = 0xFF
    private const val INSTANTANEOUS_POWER_UNAVAILABLE = 0xFFF
    private const val INSTANTANEOUS_POWER_16BIT_UNAVAILABLE = 0xFFFF
    private const val SERIAL_UNAVAILABLE = 0xFFFFFFFFL
    private const val UINT8_UNAVAILABLE = 0xFF
    private const val UINT16_UNAVAILABLE = 0xFFFF
    private const val INCLINE_UNAVAILABLE = 0x7FFF

    /**
     * Decodes [data] (page number in `data[0]`, up to 7 payload bytes following) into a
     * typed [FeCPage]. Callers are expected to have already validated frame-level integrity
     * (sync/length/checksum) via [FeCFrameCodec.decode] - this function trusts its input's
     * byte count for the page it identifies, which [FeCFrameCodec] guarantees via its
     * "short known page" rejection.
     */
    fun decodePage(data: ByteArray): FeCPage {
        if (data.isEmpty()) return FeCPage.Unknown(page = -1)
        val page = data[0].toInt() and 0xFF
        return when (page) {
            BluetoothIds.FEC_PAGE_GENERAL_FE_DATA -> decodeGeneralFe(data)
            BluetoothIds.FEC_PAGE_GENERAL_SETTINGS -> decodeGeneralSettings(data)
            BluetoothIds.FEC_PAGE_GENERAL_METABOLIC_DATA -> decodeGeneralMetabolicData(data)
            BluetoothIds.FEC_PAGE_SPECIFIC_TREADMILL_DATA -> decodeSpecificTreadmillData(data)
            BluetoothIds.FEC_PAGE_SPECIFIC_ELLIPTICAL_DATA -> decodeSpecificEllipticalData(data)
            BluetoothIds.FEC_PAGE_SPECIFIC_ROWER_DATA -> decodeSpecificRowerData(data)
            BluetoothIds.FEC_PAGE_SPECIFIC_CLIMBER_DATA -> decodeSpecificClimberData(data)
            BluetoothIds.FEC_PAGE_SPECIFIC_NORDIC_SKIER_DATA -> decodeSpecificNordicSkierData(data)
            BluetoothIds.FEC_PAGE_SPECIFIC_TRAINER_DATA -> decodeTrainerData(data)
            BluetoothIds.FEC_PAGE_SPECIFIC_TRAINER_TORQUE_DATA -> decodeTrainerTorqueData(data)
            BluetoothIds.FEC_PAGE_FE_CAPABILITIES -> decodeFeCapabilities(data)
            BluetoothIds.FEC_PAGE_MANUFACTURER_INFORMATION -> decodeManufacturerInformation(data)
            BluetoothIds.FEC_PAGE_PRODUCT_INFORMATION -> decodeProductInformation(data)
            else -> FeCPage.Unknown(page)
        }
    }

    private fun ByteArray.u8(index: Int): Int = this[index].toInt() and 0xFF

    private fun ByteArray.u16le(index: Int): Int = u8(index) or (u8(index + 1) shl 8)

    private fun ByteArray.i16le(index: Int): Int = u16le(index).toShort().toInt()

    private fun decodeGeneralFe(data: ByteArray): FeCPage {
        if (data.size < 8) return FeCPage.Unknown(BluetoothIds.FEC_PAGE_GENERAL_FE_DATA)
        val equipmentType = data.u8(1) and 0x1F
        val elapsedRaw = data.u8(2)
        val distanceRaw = data.u8(3)
        val speedRaw = data.u16le(4)
        val hrByte = data.u8(6)
        val capabilities = data.u8(7)
        val distanceEnabled = (capabilities and 0x04) != 0
        val equipmentStateBits = (capabilities shr 4) and 0x07
        return FeCPage.GeneralFe(
            equipmentType = equipmentType,
            elapsedTimeRaw = elapsedRaw,
            distanceTraveledRaw = distanceRaw,
            distanceTraveledEnabled = distanceEnabled,
            speedMetersPerSecond = speedRaw / 1000.0,
            heartRateBpm = if (hrByte == HR_UNAVAILABLE) null else hrByte,
            capabilitiesByte = capabilities,
            equipmentState = FeCEquipmentState.fromWireValue(equipmentStateBits),
        )
    }

    private fun decodeGeneralSettings(data: ByteArray): FeCPage {
        if (data.size < 8) return FeCPage.Unknown(BluetoothIds.FEC_PAGE_GENERAL_SETTINGS)
        val cycleLengthRaw = data.u8(3)
        val inclineRaw = data.u16le(4)
        val resistanceRaw = data.u8(6)
        return FeCPage.GeneralSettings(
            cycleLengthMeters = if (cycleLengthRaw == UINT8_UNAVAILABLE) null else cycleLengthRaw / 100.0,
            inclinePercent = if (inclineRaw == INCLINE_UNAVAILABLE) null else data.i16le(4) / 100.0,
            resistancePercent = if (resistanceRaw == UINT8_UNAVAILABLE) null else resistanceRaw / 2.0,
            equipmentState = FeCEquipmentState.fromWireValue((data.u8(7) shr 4) and 0x07),
        )
    }

    private fun decodeGeneralMetabolicData(data: ByteArray): FeCPage {
        if (data.size < 8) return FeCPage.Unknown(BluetoothIds.FEC_PAGE_GENERAL_METABOLIC_DATA)
        val metRaw = data.u16le(2)
        val burnRateRaw = data.u16le(4)
        val capabilities = data.u8(7)
        return FeCPage.GeneralMetabolicData(
            metabolicEquivalent = if (metRaw == UINT16_UNAVAILABLE) null else metRaw / 100.0,
            caloricBurnRateKcalPerHour = if (burnRateRaw == UINT16_UNAVAILABLE) null else burnRateRaw / 10.0,
            accumulatedCaloriesRaw = data.u8(6),
            accumulatedCaloriesEnabled = (capabilities and 0x01) != 0,
            equipmentState = FeCEquipmentState.fromWireValue((capabilities shr 4) and 0x07),
        )
    }

    private fun decodeTrainerData(data: ByteArray): FeCPage {
        if (data.size < 8) return FeCPage.Unknown(BluetoothIds.FEC_PAGE_SPECIFIC_TRAINER_DATA)
        val eventCount = data.u8(1)
        val cadenceByte = data.u8(2)
        val accumulatedPower = data.u16le(3)
        val powerLow = data.u8(5)
        val byte6 = data.u8(6)
        val instantaneousPowerRaw = powerLow or ((byte6 and 0x0F) shl 8)
        val trainerStatus = (byte6 shr 4) and 0x0F
        val byte7 = data.u8(7)
        val targetPowerStatus = byte7 and 0x03
        val equipmentStateBits = (byte7 shr 4) and 0x07
        return FeCPage.TrainerData(
            eventCountRaw = eventCount,
            cadenceRpm = if (cadenceByte == CADENCE_UNAVAILABLE) null else cadenceByte,
            accumulatedPowerRaw = accumulatedPower,
            instantaneousPowerWatts = if (instantaneousPowerRaw == INSTANTANEOUS_POWER_UNAVAILABLE) {
                null
            } else {
                instantaneousPowerRaw
            },
            trainerStatus = trainerStatus,
            targetPowerStatus = targetPowerStatus,
            equipmentState = FeCEquipmentState.fromWireValue(equipmentStateBits),
        )
    }

    private fun decodeSpecificTreadmillData(data: ByteArray): FeCPage {
        if (data.size < 8) return FeCPage.Unknown(BluetoothIds.FEC_PAGE_SPECIFIC_TREADMILL_DATA)
        val cadenceByte = data.u8(4)
        val negativeRaw = data.u8(5)
        val positiveRaw = data.u8(6)
        val byte7 = data.u8(7)
        val positiveEnabled = (byte7 and 0x01) != 0
        val negativeEnabled = (byte7 and 0x02) != 0
        val equipmentStateBits = (byte7 shr 4) and 0x07
        return FeCPage.SpecificTreadmillData(
            cadenceRpm = if (cadenceByte == CADENCE_UNAVAILABLE) null else cadenceByte,
            negativeVerticalDistanceRaw = negativeRaw,
            negativeVerticalDistanceEnabled = negativeEnabled,
            positiveVerticalDistanceRaw = positiveRaw,
            positiveVerticalDistanceEnabled = positiveEnabled,
            equipmentState = FeCEquipmentState.fromWireValue(equipmentStateBits),
        )
    }

    private fun decodeSpecificRowerData(data: ByteArray): FeCPage {
        if (data.size < 8) return FeCPage.Unknown(BluetoothIds.FEC_PAGE_SPECIFIC_ROWER_DATA)
        val strokeCountRaw = data.u8(3)
        val cadenceByte = data.u8(4)
        val powerRaw = data.u16le(5)
        val byte7 = data.u8(7)
        val strokeCountEnabled = (byte7 and 0x01) != 0
        val equipmentStateBits = (byte7 shr 4) and 0x07
        return FeCPage.SpecificRowerData(
            strokeCountRaw = strokeCountRaw,
            strokeCountEnabled = strokeCountEnabled,
            cadenceRpm = if (cadenceByte == CADENCE_UNAVAILABLE) null else cadenceByte,
            instantaneousPowerWatts = if (powerRaw == INSTANTANEOUS_POWER_16BIT_UNAVAILABLE) null else powerRaw,
            equipmentState = FeCEquipmentState.fromWireValue(equipmentStateBits),
        )
    }

    private fun decodeSpecificEllipticalData(data: ByteArray): FeCPage {
        if (data.size < 8) return FeCPage.Unknown(BluetoothIds.FEC_PAGE_SPECIFIC_ELLIPTICAL_DATA)
        val byte7 = data.u8(7)
        val cadence = data.u8(4)
        val power = data.u16le(5)
        return FeCPage.SpecificEllipticalData(
            positiveVerticalDistanceRaw = data.u8(2),
            positiveVerticalDistanceEnabled = (byte7 and 0x02) != 0,
            strideCountRaw = data.u8(3),
            strideCountEnabled = (byte7 and 0x01) != 0,
            cadenceRpm = if (cadence == CADENCE_UNAVAILABLE) null else cadence,
            instantaneousPowerWatts = if (power == UINT16_UNAVAILABLE) null else power,
            equipmentState = FeCEquipmentState.fromWireValue((byte7 shr 4) and 0x07),
        )
    }

    private fun decodeSpecificClimberData(data: ByteArray): FeCPage {
        if (data.size < 8) return FeCPage.Unknown(BluetoothIds.FEC_PAGE_SPECIFIC_CLIMBER_DATA)
        val byte7 = data.u8(7)
        val cadence = data.u8(4)
        val power = data.u16le(5)
        return FeCPage.SpecificClimberData(
            cycleCountRaw = data.u8(3),
            cycleCountEnabled = (byte7 and 0x01) != 0,
            cadenceRpm = if (cadence == CADENCE_UNAVAILABLE) null else cadence,
            instantaneousPowerWatts = if (power == UINT16_UNAVAILABLE) null else power,
            equipmentState = FeCEquipmentState.fromWireValue((byte7 shr 4) and 0x07),
        )
    }

    private fun decodeSpecificNordicSkierData(data: ByteArray): FeCPage {
        if (data.size < 8) return FeCPage.Unknown(BluetoothIds.FEC_PAGE_SPECIFIC_NORDIC_SKIER_DATA)
        val byte7 = data.u8(7)
        val cadence = data.u8(4)
        val power = data.u16le(5)
        return FeCPage.SpecificNordicSkierData(
            strideCountRaw = data.u8(3),
            strideCountEnabled = (byte7 and 0x01) != 0,
            cadenceRpm = if (cadence == CADENCE_UNAVAILABLE) null else cadence,
            instantaneousPowerWatts = if (power == UINT16_UNAVAILABLE) null else power,
            equipmentState = FeCEquipmentState.fromWireValue((byte7 shr 4) and 0x07),
        )
    }

    private fun decodeTrainerTorqueData(data: ByteArray): FeCPage {
        if (data.size < 8) return FeCPage.Unknown(BluetoothIds.FEC_PAGE_SPECIFIC_TRAINER_TORQUE_DATA)
        return FeCPage.TrainerTorqueData(
            eventCountRaw = data.u8(1),
            wheelTicksRaw = data.u8(2),
            accumulatedWheelPeriodRaw = data.u16le(3),
            accumulatedTorqueRaw = data.u16le(5),
            equipmentState = FeCEquipmentState.fromWireValue((data.u8(7) shr 4) and 0x07),
        )
    }

    private fun decodeFeCapabilities(data: ByteArray): FeCPage {
        if (data.size < 8) return FeCPage.Unknown(BluetoothIds.FEC_PAGE_FE_CAPABILITIES)
        val maxResistance = data.u16le(5)
        val capabilities = data.u8(7)
        return FeCPage.FeCapabilities(
            maximumResistanceNewtons = if (maxResistance == UINT16_UNAVAILABLE) null else maxResistance,
            supportsBasicResistance = (capabilities and 0x01) != 0,
            supportsTargetPower = (capabilities and 0x02) != 0,
            supportsSimulation = (capabilities and 0x04) != 0,
        )
    }

    private fun decodeManufacturerInformation(data: ByteArray): FeCPage {
        if (data.size < 8) return FeCPage.Unknown(BluetoothIds.FEC_PAGE_MANUFACTURER_INFORMATION)
        return FeCPage.ManufacturerInformation(
            hardwareRevision = data.u8(3),
            manufacturerId = data.u16le(4),
            modelNumber = data.u16le(6),
        )
    }

    private fun decodeProductInformation(data: ByteArray): FeCPage {
        if (data.size < 8) return FeCPage.Unknown(BluetoothIds.FEC_PAGE_PRODUCT_INFORMATION)
        val supplemental = data.u8(2)
        val main = data.u8(3)
        // Unsigned uint32LE. Deliberately not `readInt32LE()` (Incyclist's bug, see
        // FeCPage.ProductInformation KDoc) - a serial >= 0x80000000 must not go negative.
        val serialRaw =
            (data.u8(4).toLong()) or
                (data.u8(5).toLong() shl 8) or
                (data.u8(6).toLong() shl 16) or
                (data.u8(7).toLong() shl 24)
        return FeCPage.ProductInformation(
            supplementalSoftwareRevision = supplemental,
            mainSoftwareRevision = main,
            serialNumber = if (serialRaw == SERIAL_UNAVAILABLE) null else serialRaw,
        )
    }
}
