package com.gmail.absolutebollockscreations.Fitbridge.bluetooth.simulator

import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine.HEART_RATE_FRESHNESS_MS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The live-strap override for simulated heart rate.
 *
 * Pure, and the whole point of extracting it: the decision has three outcomes with different
 * consequences for a validation capture, and none of them is observable if the logic lives inside a
 * coroutine that only runs on a device.
 */
class SimulatorHeartRateOverrideTest {

    private fun resolve(
        generated: Int = 120,
        external: Int? = null,
        lastAt: Long = 0L,
        now: Long = 10_000L,
    ) = SimulatorHeartRateOverride.resolve(generated, external, lastAt, now)

    @Test
    fun `a fresh strap reading replaces the generated value`() {
        val result = resolve(generated = 120, external = 147, lastAt = 9_000L, now = 10_000L)

        assertEquals(147, result.bpm)
        assertEquals(SimulatorHeartRateOverride.Outcome.OVERRIDDEN, result.outcome)
    }

    @Test
    fun `no strap at all leaves the generated value untouched and silent`() {
        // The ordinary case. It must be distinguishable from a fallback, because logging every
        // second that no strap is attached would bury the one message that matters.
        val result = resolve(generated = 120, external = null, lastAt = 0L)

        assertEquals(120, result.bpm)
        assertEquals(SimulatorHeartRateOverride.Outcome.NO_EXTERNAL_SOURCE, result.outcome)
    }

    @Test
    fun `a value with no timestamp is not trusted`() {
        // `lastExternalAt` starts at zero. A reading present with no arrival time recorded is a
        // state that should not occur; treating it as fresh would pin an unaged value forever.
        val result = resolve(external = 150, lastAt = 0L)

        assertEquals(120, result.bpm)
        assertEquals(SimulatorHeartRateOverride.Outcome.NO_EXTERNAL_SOURCE, result.outcome)
    }

    @Test
    fun `a strap that goes quiet falls back and says so`() {
        // The case the user asked to be visible: a capture must not be able to be half real and
        // half synthetic without the log saying where the boundary is.
        val justStale = SimulatorHeartRateOverride.FRESHNESS_MS + 1
        val result = resolve(generated = 118, external = 150, lastAt = 0L + 1, now = justStale + 1)

        assertEquals(118, result.bpm)
        assertEquals(SimulatorHeartRateOverride.Outcome.STALE_FELL_BACK, result.outcome)
    }

    @Test
    fun `the freshness boundary is inclusive and one millisecond past it is not`() {
        val exactlyAtBound = resolve(external = 150, lastAt = 1_000L, now = 1_000L + SimulatorHeartRateOverride.FRESHNESS_MS)
        assertEquals(SimulatorHeartRateOverride.Outcome.OVERRIDDEN, exactlyAtBound.outcome)

        val oneMsPast = resolve(external = 150, lastAt = 1_000L, now = 1_001L + SimulatorHeartRateOverride.FRESHNESS_MS)
        assertEquals(SimulatorHeartRateOverride.Outcome.STALE_FELL_BACK, oneMsPast.outcome)
    }

    @Test
    fun `a timestamp from the future is treated as stale, not as fresh`() {
        // The wall clock can move — NTP, the user changing it — and a negative age would otherwise
        // satisfy any upper bound and hold a dead reading in place indefinitely.
        val result = resolve(external = 150, lastAt = 20_000L, now = 10_000L)

        assertEquals(SimulatorHeartRateOverride.Outcome.STALE_FELL_BACK, result.outcome)
        assertEquals(120, result.bpm)
    }

    @Test
    fun `the window matches the one fusion uses for heart rate`() {
        // Not a second constant that could drift. A strap notifies about once a second, so this
        // rides out three missed notifications and no more.
        assertEquals(HEART_RATE_FRESHNESS_MS, SimulatorHeartRateOverride.FRESHNESS_MS)
        assertTrue("the window must tolerate a few dropped notifications", SimulatorHeartRateOverride.FRESHNESS_MS >= 3_000L)
    }

    @Test
    fun `every persona carrying heart rate is covered by one substitution point`() {
        // A structural claim rather than a behavioural one, and it is the reason this override sits
        // in `publish` rather than in each encoder. The proprietary personas encode straight from
        // the sample; the standard FTMS path goes through BtEngineState. Substituting before that
        // fork means a persona added later — the Schwinn 170 — inherits it without another edit.
        val source = java.io.File(
            "ble-simulator/src/main/java/com/gmail/absolutebollockscreations/Fitbridge/bluetooth/simulator/FtmsSimulator.kt",
        ).takeIf { it.isFile } ?: java.io.File(
            "src/main/java/com/gmail/absolutebollockscreations/Fitbridge/bluetooth/simulator/FtmsSimulator.kt",
        )
        assertTrue("the simulator source is findable", source.isFile)
        val text = source.readText()

        val substitution = text.indexOf("withLiveHeartRate(generated)")
        val keiser = text.indexOf("peripheral.publishKeiser(output.manufacturerPayload)")
        val precor = text.indexOf("peripheral.publishPrecor(sample)")
        val lifeFitness = text.indexOf("peripheral.publishLifeFitness(sample)")
        val engineWrite = text.indexOf("engineState.setHeartRate(sample.heartRate)")

        assertTrue("the substitution must exist", substitution > 0)
        assertTrue("it must precede the Keiser encoder", substitution < keiser)
        assertTrue("it must precede the Precor encoder", substitution < precor)
        assertTrue("it must precede the Life Fitness encoder", substitution < lifeFitness)
        assertTrue("it must precede the engine-state write", substitution < engineWrite)

        // And it must read the strap's own field, not the fused one the simulator itself writes.
        assertTrue(
            "the override must read externalHeartRate, never the fused heartRate",
            text.contains("engineState.externalHeartRate.value"),
        )
    }
}
