package com.gmail.absolutebollockscreations.gattlibrary.peripheral

import com.gmail.absolutebollockscreations.gattlibrary.central.BluetoothIds
import com.gmail.absolutebollockscreations.gattlibrary.core.ftms.FtmsMachineProfile
import org.junit.Assert.assertEquals
import org.junit.Test

class CyclingEventAccumulatorTest {
    @Test
    fun `speed and cadence integrate independently into sensor events`() {
        val accumulator = CyclingEventAccumulator(wheelCircumferenceMm = 2_000)
        accumulator.update(speedKph = 36.0, cadenceRpm = 120.0, nowMs = 0)

        val snapshot = accumulator.update(
            speedKph = 36.0,
            cadenceRpm = 120.0,
            nowMs = 1_000,
        )

        assertEquals(5L, snapshot.cumulativeWheelRevolutions)
        assertEquals(2, snapshot.cumulativeCrankRevolutions)
        assertEquals(1_024, snapshot.lastWheelEventTime)
        assertEquals(1_024, snapshot.lastCrankEventTime)
    }

    @Test
    fun `fractional wheel and crank progress survives notification boundaries`() {
        val accumulator = CyclingEventAccumulator(wheelCircumferenceMm = 2_000)
        accumulator.update(speedKph = 7.2, cadenceRpm = 60.0, nowMs = 0)

        val halfSecond = accumulator.update(7.2, 60.0, 500)
        val fullSecond = accumulator.update(7.2, 60.0, 1_000)

        assertEquals(0L, halfSecond.cumulativeWheelRevolutions)
        assertEquals(0, halfSecond.cumulativeCrankRevolutions)
        assertEquals(1L, fullSecond.cumulativeWheelRevolutions)
        assertEquals(1, fullSecond.cumulativeCrankRevolutions)
    }

    @Test
    fun `zero values freeze counts and long gaps are not backfilled`() {
        val accumulator = CyclingEventAccumulator(wheelCircumferenceMm = 2_000)
        accumulator.update(speedKph = 36.0, cadenceRpm = 120.0, nowMs = 0)
        val moving = accumulator.update(36.0, 120.0, 1_000)
        val stopped = accumulator.update(0.0, 0.0, 2_000)
        val afterGap = accumulator.update(36.0, 120.0, 8_000)

        assertEquals(moving, stopped)
        assertEquals(stopped, afterGap)
    }

    @Test
    fun `event timestamps roll over at the bluetooth sixteen bit boundary`() {
        val accumulator = CyclingEventAccumulator(wheelCircumferenceMm = 2_000)
        accumulator.update(speedKph = 7.2, cadenceRpm = 60.0, nowMs = 63_000)

        val snapshot = accumulator.update(7.2, 60.0, 65_000)

        assertEquals(1_024, snapshot.lastWheelEventTime)
        assertEquals(1_024, snapshot.lastCrankEventTime)
    }

    @Test
    fun `control point can replace cumulative wheel revolutions`() {
        val accumulator = CyclingEventAccumulator(wheelCircumferenceMm = 2_000)

        accumulator.setCumulativeWheelRevolutions(0x1234_5678L)

        assertEquals(0x1234_5678L, accumulator.snapshot().cumulativeWheelRevolutions)
    }
}
