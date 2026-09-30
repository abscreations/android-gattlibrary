package com.gmail.absolutebollockscreations.gattlibrary.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The emission period is a floor, not a fixed tick.
 *
 * The defect: a fixed `delay(periodMs)` produced records faster than a 990 ms
 * link could carry them, and the surplus was banked rather than dropped. Pacing
 * off completion means a slow link simply emits less often, and a fast one is
 * still capped.
 */
class NotifierPacingTest {

    @Test
    fun `a fast send waits out the remainder of the floor`() {
        assertEquals(230L, emissionDelayMs(periodMs = 250, elapsedMs = 20))
    }

    @Test
    fun `a send that outlasts the floor is followed immediately`() {
        // A 990 ms Garmin connection interval: the next emission starts at once,
        // carrying a snapshot encoded now rather than one encoded a tick ago.
        assertEquals(0L, emissionDelayMs(periodMs = 250, elapsedMs = 990))
    }

    @Test
    fun `an exactly-on-the-floor send does not wait`() {
        assertEquals(0L, emissionDelayMs(periodMs = 250, elapsedMs = 250))
    }

    @Test
    fun `the delay is never negative`() {
        assertEquals(0L, emissionDelayMs(periodMs = 250, elapsedMs = 10_000))
    }

    @Test
    fun `the default floor stays clear of the android one-second idle timeout`() {
        // A 1,000 ms cadence sits on Android's no-holder GATT idle timer, and
        // scheduling jitter then lets the timer fire first on affected stacks.
        assertTrue(
            "emission floor must stay well below the 1s idle timeout",
            FtmsPeripheralNotifier.DEFAULT_PERIOD_MS <= 500L,
        )
        assertTrue(
            "a floor below the minimum would spin the pump",
            FtmsPeripheralNotifier.MIN_PERIOD_MS > 0L,
        )
    }
}
