package com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine

/** Typed reasons a Flywheel statistics notification can be rejected before field access. */
internal enum class FlywheelRejection {
    TOO_SHORT,
    BAD_MAGIC,
}

internal sealed interface FlywheelDecodeResult {
    data class Decoded(val reading: FlywheelMeasurement.Reading) : FlywheelDecodeResult
    data class Rejected(val reason: FlywheelRejection) : FlywheelDecodeResult
}

/**
 * Pure decoder for the proprietary Flywheel Sports statistics frame (Phase 12.2).
 *
 * gymnasticon v1.4.1 (`e142f539c518d6d4ced3abdd587a825b918a9367`, MIT) establishes the
 * minimum 13-octet layout: `ff 1f 0c`, unsigned big-endian power at 3..4, and unsigned cadence at
 * 12. Offsets 5..11 and every byte after 12 remain unknown. A longer notification is accepted only
 * because the source vector is 34 octets; this decoder deliberately assigns no meaning to its tail.
 *
 * This object owns no transport, clock, connection, or filter state. A Phase 12.3 connection
 * handler will own one [FlywheelDropoutFilter] per link generation and offer only successfully
 * decoded readings to it.
 */
internal object FlywheelMeasurement {
    const val MINIMUM_FRAME_BYTES = 13

    private const val POWER_OFFSET = 3
    private const val CADENCE_OFFSET = 12
    private val MAGIC = intArrayOf(0xff, 0x1f, 0x0c)

    data class Reading(
        val powerWatts: Int,
        val cadenceRpm: Int,
    )

    fun decode(bytes: ByteArray): FlywheelDecodeResult {
        if (bytes.size < MINIMUM_FRAME_BYTES) {
            return FlywheelDecodeResult.Rejected(FlywheelRejection.TOO_SHORT)
        }
        if (MAGIC.indices.any { bytes[it].u8() != MAGIC[it] }) {
            return FlywheelDecodeResult.Rejected(FlywheelRejection.BAD_MAGIC)
        }

        val powerWatts = (bytes[POWER_OFFSET].u8() shl 8) or bytes[POWER_OFFSET + 1].u8()
        val cadenceRpm = bytes[CADENCE_OFFSET].u8()
        return FlywheelDecodeResult.Decoded(Reading(powerWatts, cadenceRpm))
    }

    private fun Byte.u8(): Int = toInt() and 0xff
}

/**
 * Reproduces gymnasticon's shared isolated-zero behavior for both Flywheel fields (D96/V26).
 *
 * Power and cadence retain independent last-nonzero values and dropped flags. The first zero after
 * a nonzero repeats that field's last value; a second consecutive zero is published; any nonzero
 * stores a new last value and re-arms the one-zero repair. Although only Flywheel power dropouts are
 * described as physically observed by the source, applying the same behavior to cadence is the
 * explicit compatibility decision in D96, not an upgraded evidence claim.
 *
 * One instance belongs to one connection generation. [reset] is mandatory on teardown so a new
 * link cannot inherit values or dropped state from the previous bike session.
 */
internal class FlywheelDropoutFilter {
    private var lastPowerWatts = 0
    private var lastCadenceRpm = 0
    private var powerDropped = false
    private var cadenceDropped = false

    var lastSubstitutedPower: Boolean = false
        private set

    var lastSubstitutedCadence: Boolean = false
        private set

    fun filter(reading: FlywheelMeasurement.Reading): FlywheelMeasurement.Reading {
        val powerWatts = filterPower(reading.powerWatts)
        val cadenceRpm = filterCadence(reading.cadenceRpm)
        return if (powerWatts == reading.powerWatts && cadenceRpm == reading.cadenceRpm) {
            reading
        } else {
            reading.copy(powerWatts = powerWatts, cadenceRpm = cadenceRpm)
        }
    }

    fun reset() {
        lastPowerWatts = 0
        lastCadenceRpm = 0
        powerDropped = false
        cadenceDropped = false
        lastSubstitutedPower = false
        lastSubstitutedCadence = false
    }

    private fun filterPower(value: Int): Int {
        if (value != 0) {
            lastPowerWatts = value
            powerDropped = false
            lastSubstitutedPower = false
            return value
        }

        lastSubstitutedPower = !powerDropped
        if (powerDropped) return 0
        powerDropped = true
        return lastPowerWatts
    }

    private fun filterCadence(value: Int): Int {
        if (value != 0) {
            lastCadenceRpm = value
            cadenceDropped = false
            lastSubstitutedCadence = false
            return value
        }

        lastSubstitutedCadence = !cadenceDropped
        if (cadenceDropped) return 0
        cadenceDropped = true
        return lastCadenceRpm
    }
}
