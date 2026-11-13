package com.gmail.absolutebollockscreations.gattlibrary.central

import com.gmail.absolutebollockscreations.gattlibrary.core.GattLibraryInternalApi
import com.gmail.absolutebollockscreations.gattlibrary.core.bytes.ByteCursor

/** Bounds-checked decoder for Cycling Power Measurement, `0x2A63`. */
@GattLibraryInternalApi
internal object CpsMeasurement {
    data class WheelEvent(val cumulativeRevolutions: Long, val lastEventTimeTicks: Int)
    data class CrankEvent(val cumulativeRevolutions: Int, val lastEventTimeTicks: Int)

    data class Reading(
        val flags: Int,
        val instantaneousPowerWatts: Int,
        val pedalPowerBalancePercent: Double? = null,
        val pedalPowerBalanceRightReference: Boolean = false,
        val accumulatedTorqueNewtonMetres: Double? = null,
        val accumulatedTorqueCrankSource: Boolean = false,
        val wheel: WheelEvent? = null,
        val crank: CrankEvent? = null,
        val maximumForceNewtons: Int? = null,
        val minimumForceNewtons: Int? = null,
        val maximumTorqueNewtonMetres: Double? = null,
        val minimumTorqueNewtonMetres: Double? = null,
        val maximumAngleDegrees: Int? = null,
        val minimumAngleDegrees: Int? = null,
        val topDeadSpotAngleDegrees: Int? = null,
        val bottomDeadSpotAngleDegrees: Int? = null,
        val accumulatedEnergyKilojoules: Int? = null,
        val offsetCompensationIndicator: Boolean = false,
    )

    fun parse(data: ByteArray): Reading? {
        val cursor = ByteCursor(data)
        val flags = cursor.u16() ?: return null
        val power = cursor.s16() ?: return null
        val pedalBalance = if (flags.has(BIT_PEDAL_POWER_BALANCE)) {
            cursor.u8()?.div(2.0) ?: return null
        } else null
        val torque = if (flags.has(BIT_ACCUMULATED_TORQUE)) {
            cursor.u16()?.div(32.0) ?: return null
        } else null
        val wheel = if (flags.has(BIT_WHEEL_REVOLUTION_DATA)) {
            WheelEvent(cursor.u32() ?: return null, cursor.u16() ?: return null)
        } else null
        val crank = if (flags.has(BIT_CRANK_REVOLUTION_DATA)) {
            CrankEvent(cursor.u16() ?: return null, cursor.u16() ?: return null)
        } else null
        val maximumForce = if (flags.has(BIT_EXTREME_FORCE_MAGNITUDES)) cursor.s16() ?: return null else null
        val minimumForce = if (flags.has(BIT_EXTREME_FORCE_MAGNITUDES)) cursor.s16() ?: return null else null
        val maximumTorque = if (flags.has(BIT_EXTREME_TORQUE_MAGNITUDES)) {
            (cursor.s16() ?: return null) / 32.0
        } else null
        val minimumTorque = if (flags.has(BIT_EXTREME_TORQUE_MAGNITUDES)) {
            (cursor.s16() ?: return null) / 32.0
        } else null
        val angles = if (flags.has(BIT_EXTREME_ANGLES)) cursor.u24() ?: return null else null
        val topDeadSpot = if (flags.has(BIT_TOP_DEAD_SPOT_ANGLE)) cursor.u16() ?: return null else null
        val bottomDeadSpot = if (flags.has(BIT_BOTTOM_DEAD_SPOT_ANGLE)) cursor.u16() ?: return null else null
        val energy = if (flags.has(BIT_ACCUMULATED_ENERGY)) cursor.u16() ?: return null else null
        return Reading(
            flags = flags,
            instantaneousPowerWatts = power,
            pedalPowerBalancePercent = pedalBalance,
            pedalPowerBalanceRightReference = flags.has(BIT_PEDAL_POWER_BALANCE_REFERENCE),
            accumulatedTorqueNewtonMetres = torque,
            accumulatedTorqueCrankSource = flags.has(BIT_ACCUMULATED_TORQUE_SOURCE),
            wheel = wheel,
            crank = crank,
            maximumForceNewtons = maximumForce,
            minimumForceNewtons = minimumForce,
            maximumTorqueNewtonMetres = maximumTorque,
            minimumTorqueNewtonMetres = minimumTorque,
            maximumAngleDegrees = angles?.and(0x0FFF),
            minimumAngleDegrees = angles?.ushr(12)?.and(0x0FFF),
            topDeadSpotAngleDegrees = topDeadSpot,
            bottomDeadSpotAngleDegrees = bottomDeadSpot,
            accumulatedEnergyKilojoules = energy,
            offsetCompensationIndicator = flags.has(BIT_OFFSET_COMPENSATION_INDICATOR),
        )
    }

    private fun Int.has(bit: Int): Boolean = this and (1 shl bit) != 0

    private const val BIT_PEDAL_POWER_BALANCE = 0
    private const val BIT_PEDAL_POWER_BALANCE_REFERENCE = 1
    private const val BIT_ACCUMULATED_TORQUE = 2
    private const val BIT_ACCUMULATED_TORQUE_SOURCE = 3
    private const val BIT_WHEEL_REVOLUTION_DATA = 4
    private const val BIT_CRANK_REVOLUTION_DATA = 5
    private const val BIT_EXTREME_FORCE_MAGNITUDES = 6
    private const val BIT_EXTREME_TORQUE_MAGNITUDES = 7
    private const val BIT_EXTREME_ANGLES = 8
    private const val BIT_TOP_DEAD_SPOT_ANGLE = 9
    private const val BIT_BOTTOM_DEAD_SPOT_ANGLE = 10
    private const val BIT_ACCUMULATED_ENERGY = 11
    private const val BIT_OFFSET_COMPENSATION_INDICATOR = 12
}

@GattLibraryInternalApi
internal class CpsMeasurementTracker {
    data class WheelDelta(
        val revolutions: Long,
        val eventTimeTicks: Int,
        val counterRolledOver: Boolean,
        val rebaselinedAfterDecrease: Boolean,
    )
    data class CrankDelta(val revolutions: Int, val eventTimeTicks: Int)
    data class Update(
        val reading: CpsMeasurement.Reading,
        val wheelDelta: WheelDelta? = null,
        val crankDelta: CrankDelta? = null,
    )

    private var previousWheel: CpsMeasurement.WheelEvent? = null
    private var previousCrank: CpsMeasurement.CrankEvent? = null

    fun accept(data: ByteArray): Update? = CpsMeasurement.parse(data)?.let(::update)

    fun update(reading: CpsMeasurement.Reading): Update {
        val wheelDelta = reading.wheel?.let { current ->
            val previous = previousWheel
            previousWheel = current
            previous?.let {
                val rollover = it.cumulativeRevolutions >= ROLLOVER_HIGH &&
                    current.cumulativeRevolutions <= ROLLOVER_LOW
                val decreased = current.cumulativeRevolutions < it.cumulativeRevolutions
                WheelDelta(
                    revolutions = when {
                        rollover -> UINT32_MODULUS - it.cumulativeRevolutions + current.cumulativeRevolutions
                        decreased -> 0L
                        else -> current.cumulativeRevolutions - it.cumulativeRevolutions
                    },
                    eventTimeTicks = deltaU16(current.lastEventTimeTicks, it.lastEventTimeTicks),
                    counterRolledOver = rollover,
                    rebaselinedAfterDecrease = decreased && !rollover,
                )
            }
        }
        val crankDelta = reading.crank?.let { current ->
            val previous = previousCrank
            previousCrank = current
            previous?.let {
                CrankDelta(
                    deltaU16(current.cumulativeRevolutions, it.cumulativeRevolutions),
                    deltaU16(current.lastEventTimeTicks, it.lastEventTimeTicks),
                )
            }
        }
        return Update(reading, wheelDelta, crankDelta)
    }

    fun reset() {
        previousWheel = null
        previousCrank = null
    }

    private fun deltaU16(current: Int, previous: Int): Int = (current - previous) and 0xFFFF

    private companion object {
        const val UINT32_MODULUS = 0x1_0000_0000L
        const val ROLLOVER_HIGH = 0xF000_0000L
        const val ROLLOVER_LOW = 0x0FFF_FFFFL
    }
}
