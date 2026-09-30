package com.gmail.absolutebollockscreations.gattlibrary.simulator

import com.gmail.absolutebollockscreations.gattlibrary.core.bytes.LittleEndian.u16
import com.gmail.absolutebollockscreations.gattlibrary.core.ids.FecBleUuids

/** Typed outcome of decoding one FEC3 write. Never throws; unknown/malformed input is a value. */
@SimulatorProbeApi
sealed interface FeCBleControlCommand {
    data class BasicResistance(val resistancePercent: Double) : FeCBleControlCommand
    data class TargetPower(val watts: Double) : FeCBleControlCommand
    data class WindResistance(
        val windCoefficient: Double?,
        val windSpeedEncodedUnit: Int?,
        val draftingFactor: Double?,
    ) : FeCBleControlCommand
    data class TrackResistance(val gradePercent: Double, val crr: Double) : FeCBleControlCommand
    data class UserConfiguration(val userWeightKg: Double?, val rawPage: ByteArray) : FeCBleControlCommand
    data class RequestDataPage(
        val requestedPage: Int,
        val transmissionCount: Int,
        val acknowledgedResponse: Boolean,
        val transmitUntilAcknowledged: Boolean,
    ) : FeCBleControlCommand
    data class Unsupported(val page: Int) : FeCBleControlCommand

    /** Structural envelope rejection (plan §30.3): never mutates state. */
    data class Rejected(val reason: RejectReason) : FeCBleControlCommand
}

@SimulatorProbeApi
enum class RejectReason {
    BAD_LENGTH,
    BAD_SYNC,
    BAD_CHECKSUM,
    SHORT_PAGE,
}

/**
 * Strict ANT envelope + FE-C control-page decoder (plan §7.3, §13).
 *
 * Accepts message type `0x4F` (Incyclist's outbound type) and, for robustness, `0x4E` on FEC3
 * (plan §7.2). The channel byte is recorded but never used to reject a write — the Tacx guide
 * says the channel number is ignored when sending to the trainer.
 */
@SimulatorProbeApi
object FeCBleControlDecoder {

    /** Decode one raw FEC3 write. Malformed envelopes return [FeCBleControlCommand.Rejected]. */
    fun decode(raw: ByteArray): FeCBleControlCommand {
        if (raw.size < 5 || raw.size > 20) return FeCBleControlCommand.Rejected(RejectReason.BAD_LENGTH)
        if ((raw[0].toInt() and 0xFF) != FecBleUuids.FEC_ANT_SYNC) {
            return FeCBleControlCommand.Rejected(RejectReason.BAD_SYNC)
        }
        // ANT framing: sync + length + msg-id + [length bytes: channel + payload] + checksum, so
        // the declared length excludes the four bytes that are not part of it (plan §7.1's worked
        // example: total 13, declared 9 = channel + 8 page bytes).
        val declaredLength = raw[1].toInt() and 0xFF
        if (declaredLength != raw.size - 4) return FeCBleControlCommand.Rejected(RejectReason.BAD_LENGTH)
        val checksum = FeCBleTelemetryEncoder.xorChecksum(raw, 0, raw.size - 1)
        if (checksum != (raw[raw.size - 1].toInt() and 0xFF)) {
            return FeCBleControlCommand.Rejected(RejectReason.BAD_CHECKSUM)
        }
        // raw[2] message type: 0x4E/0x4F both accepted (plan §7.2); raw[3] channel is diagnostic only.
        val pageBytes = raw.copyOfRange(4, raw.size - 1)
        if (pageBytes.isEmpty()) return FeCBleControlCommand.Rejected(RejectReason.SHORT_PAGE)

        val page = pageBytes[0].toInt() and 0xFF
        val knownControlPage = page in KNOWN_CONTROL_PAGES
        if (knownControlPage && pageBytes.size != 8) {
            return FeCBleControlCommand.Rejected(RejectReason.SHORT_PAGE)
        }

        return when (page) {
            FecBleUuids.FEC_PAGE_BASIC_RESISTANCE -> decodeBasicResistance(pageBytes)
            FecBleUuids.FEC_PAGE_TARGET_POWER -> decodeTargetPower(pageBytes)
            FecBleUuids.FEC_PAGE_WIND_RESISTANCE -> decodeWindResistance(pageBytes)
            FecBleUuids.FEC_PAGE_TRACK_RESISTANCE -> decodeTrackResistance(pageBytes)
            FecBleUuids.FEC_PAGE_USER_CONFIGURATION -> decodeUserConfiguration(pageBytes)
            FecBleUuids.FEC_PAGE_REQUEST_DATA_PAGE -> decodeRequestDataPage(pageBytes)
            else -> FeCBleControlCommand.Unsupported(page)
        }
    }

    /** Page 48 (`0x30`): `30 FF FF FF FF FF FF RR`, resistance = RR * 0.5 %. */
    private fun decodeBasicResistance(page: ByteArray): FeCBleControlCommand {
        val rr = page[7].toInt() and 0xFF
        return FeCBleControlCommand.BasicResistance(rr * 0.5)
    }

    /** Page 49 (`0x31`): `31 FF FF FF FF FF PP PP`, watts = uint16LE(6,7) / 4.0. */
    private fun decodeTargetPower(page: ByteArray): FeCBleControlCommand {
        val raw = u16(page, 6)
        return FeCBleControlCommand.TargetPower(raw / 4.0)
    }

    /** Page 50 (`0x32`): `32 FF FF FF FF WC WS DF`. Incyclist-compatible inverse decode; §7 units unresolved. */
    private fun decodeWindResistance(page: ByteArray): FeCBleControlCommand {
        val wc = page[5].toInt() and 0xFF
        val ws = page[6].toInt() and 0xFF
        val df = page[7].toInt() and 0xFF
        return FeCBleControlCommand.WindResistance(
            windCoefficient = if (wc == 0xFF) null else wc * 0.01,
            windSpeedEncodedUnit = if (ws == 0xFF) null else ws - 127,
            draftingFactor = if (df == 0xFF) null else df * 0.01,
        )
    }

    /** Page 51 (`0x33`): `33 FF FF FF FF GG GG RR`. grade = uint16LE(5,6)*0.01 - 200; crr = RR*0.00005. */
    private fun decodeTrackResistance(page: ByteArray): FeCBleControlCommand {
        val grade = u16(page, 5) * 0.01 - 200.0
        val crr = (page[7].toInt() and 0xFF) * 0.00005
        return FeCBleControlCommand.TrackResistance(grade, crr)
    }

    /** Page 55 (`0x37`): `37 WW WW FF BD BW DD GR`. Only bytes 1-2 (weight) have an established meaning. */
    private fun decodeUserConfiguration(page: ByteArray): FeCBleControlCommand {
        val raw = u16(page, 1)
        val weight = if (raw == 0xFFFF) null else raw / 100.0
        return FeCBleControlCommand.UserConfiguration(weight, page.copyOf())
    }

    /** Common page 70: request a supported page N times, with command type 1. */
    private fun decodeRequestDataPage(page: ByteArray): FeCBleControlCommand {
        val response = page[5].toInt() and 0xFF
        // Common Pages Rev 3.1 gives 0x80 a special meaning: send acknowledged responses until
        // one succeeds. BLE does not surface the ANT transmit-completion event, so the peripheral
        // queues one response per request and lets a retry request another bounded response.
        val untilAcknowledged = response == 0x80
        val count = if (untilAcknowledged) 1 else response and 0x7F
        val commandType = page[7].toInt() and 0xFF
        if (count == 0 || commandType != 0x01) {
            return FeCBleControlCommand.Unsupported(FecBleUuids.FEC_PAGE_REQUEST_DATA_PAGE)
        }
        return FeCBleControlCommand.RequestDataPage(
            requestedPage = page[6].toInt() and 0xFF,
            transmissionCount = count,
            acknowledgedResponse = response and 0x80 != 0,
            transmitUntilAcknowledged = untilAcknowledged,
        )
    }
    private val KNOWN_CONTROL_PAGES = setOf(
        FecBleUuids.FEC_PAGE_BASIC_RESISTANCE,
        FecBleUuids.FEC_PAGE_TARGET_POWER,
        FecBleUuids.FEC_PAGE_WIND_RESISTANCE,
        FecBleUuids.FEC_PAGE_TRACK_RESISTANCE,
        FecBleUuids.FEC_PAGE_USER_CONFIGURATION,
        FecBleUuids.FEC_PAGE_REQUEST_DATA_PAGE,
    )
}
