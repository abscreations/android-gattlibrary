package com.gmail.absolutebollockscreations.gattlibrary.central

import com.gmail.absolutebollockscreations.gattlibrary.core.GattLibraryInternalApi

/** Pure decoder for Cycling Speed and Cadence Measurement, `0x2A5B`. */
@GattLibraryInternalApi
object CscMeasurement {
    const val FLAG_WHEEL_REVOLUTION_DATA = 0x01
    const val FLAG_CRANK_REVOLUTION_DATA = 0x02
    const val MINIMUM_LENGTH = 1

    data class WheelEvent(
        val cumulativeRevolutions: Long,
        val lastEventTimeTicks: Int,
    )

    data class CrankEvent(
        val cumulativeRevolutions: Int,
        val lastEventTimeTicks: Int,
    )

    data class Reading(
        val wheel: WheelEvent? = null,
        val crank: CrankEvent? = null,
    )

    /**
     * Decode the complete flag-declared frame or return null.
     *
     * This intentionally differs from a prefix decoder: a truncated combined frame must not
     * advance the wheel baseline before the missing crank bytes are discovered.
     */
    fun parse(data: ByteArray): Reading? {
        if (data.size < MINIMUM_LENGTH) return null

        val flags = u8(data, 0)
        val hasWheel = flags and FLAG_WHEEL_REVOLUTION_DATA != 0
        val hasCrank = flags and FLAG_CRANK_REVOLUTION_DATA != 0
        val requiredLength = MINIMUM_LENGTH +
            (if (hasWheel) WHEEL_FIELD_LENGTH else 0) +
            (if (hasCrank) CRANK_FIELD_LENGTH else 0)
        if (data.size < requiredLength) return null

        var offset = MINIMUM_LENGTH
        val wheel = if (hasWheel) {
            WheelEvent(
                cumulativeRevolutions = u32(data, offset),
                lastEventTimeTicks = u16(data, offset + 4),
            ).also { offset += WHEEL_FIELD_LENGTH }
        } else {
            null
        }
        val crank = if (hasCrank) {
            CrankEvent(
                cumulativeRevolutions = u16(data, offset),
                lastEventTimeTicks = u16(data, offset + 2),
            )
        } else {
            null
        }
        return Reading(wheel = wheel, crank = crank)
    }

    private fun u8(data: ByteArray, offset: Int): Int = data[offset].toInt() and 0xFF

    private fun u16(data: ByteArray, offset: Int): Int =
        u8(data, offset) or (u8(data, offset + 1) shl 8)

    private fun u32(data: ByteArray, offset: Int): Long =
        u8(data, offset).toLong() or
            (u8(data, offset + 1).toLong() shl 8) or
            (u8(data, offset + 2).toLong() shl 16) or
            (u8(data, offset + 3).toLong() shl 24)

    private const val WHEEL_FIELD_LENGTH = 6
    private const val CRANK_FIELD_LENGTH = 4
}

/** Per-link-generation CSC baselines and wrap-safe revolution/event-time deltas. */
@GattLibraryInternalApi
class CscMeasurementTracker {
    data class WheelDelta(
        val revolutions: Long,
        val eventTimeTicks: Int,
        val rebaselinedAfterDecrease: Boolean,
    )

    data class CrankDelta(
        val revolutions: Int,
        val eventTimeTicks: Int,
    )

    data class Update(
        val reading: CscMeasurement.Reading,
        val wheelDelta: WheelDelta? = null,
        val crankDelta: CrankDelta? = null,
    )

    private var previousWheel: CscMeasurement.WheelEvent? = null
    private var previousCrank: CscMeasurement.CrankEvent? = null

    /** Parse first, then mutate baselines; malformed input is therefore atomic. */
    fun accept(data: ByteArray): Update? = CscMeasurement.parse(data)?.let(::update)

    fun update(reading: CscMeasurement.Reading): Update {
        val wheelDelta = reading.wheel?.let { current ->
            val previous = previousWheel
            previousWheel = current
            previous?.let {
                val decreased = current.cumulativeRevolutions < it.cumulativeRevolutions
                WheelDelta(
                    revolutions = if (decreased) {
                        0L
                    } else {
                        current.cumulativeRevolutions - it.cumulativeRevolutions
                    },
                    eventTimeTicks = deltaU16(
                        current.lastEventTimeTicks,
                        it.lastEventTimeTicks,
                    ),
                    rebaselinedAfterDecrease = decreased,
                )
            }
        }
        val crankDelta = reading.crank?.let { current ->
            val previous = previousCrank
            previousCrank = current
            previous?.let {
                CrankDelta(
                    revolutions = deltaU16(
                        current.cumulativeRevolutions,
                        it.cumulativeRevolutions,
                    ),
                    eventTimeTicks = deltaU16(
                        current.lastEventTimeTicks,
                        it.lastEventTimeTicks,
                    ),
                )
            }
        }
        return Update(reading = reading, wheelDelta = wheelDelta, crankDelta = crankDelta)
    }

    fun reset() {
        previousWheel = null
        previousCrank = null
    }

    private fun deltaU16(current: Int, previous: Int): Int = (current - previous) and UINT16_MASK

    private companion object {
        const val UINT16_MASK = 0xFFFF
    }
}
