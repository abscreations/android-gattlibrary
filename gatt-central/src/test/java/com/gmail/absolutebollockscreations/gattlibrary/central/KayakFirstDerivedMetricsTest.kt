package com.gmail.absolutebollockscreations.gattlibrary.central

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KayakFirstDerivedMetricsTest {
    @Test
    fun `blue power inverts the manufacturer modeled speed relation`() {
        assertEquals(0.0, KayakFirstDerivedMetrics.estimatePowerFromSpeed(0.0, 80.0)!!, 0.0)
        val power = KayakFirstDerivedMetrics.estimatePowerFromSpeed(3.0, 80.0)!!
        assertEquals(
            0.1419262 * Math.pow(92.0, 2.0 / 3.0) * 27.0,
            power,
            1e-9,
        )
        assertNull(KayakFirstDerivedMetrics.estimatePowerFromSpeed(-1.0, 80.0))
        assertNull(KayakFirstDerivedMetrics.estimatePowerFromSpeed(Double.NaN, 80.0))
        assertNull(KayakFirstDerivedMetrics.estimatePowerFromSpeed(3.0, null))
    }

    @Test
    fun `average power is protocol time weighted and resettable`() {
        val average = KayakFirstAveragePowerAccumulator()
        // The weight is the represented window, not the frame count.
        assertEquals(0.0, average.sample(0L, 0.0), 0.0)
        assertEquals(50.0, average.sample(10L, 100.0), 1e-9)
        assertEquals(75.0, average.sample(10L, 100.0), 1e-9)
        // A duplicate covering zero seconds must not move the average.
        assertEquals(75.0, average.sample(0L, 500.0), 1e-9)
        // Inactive time is excluded, so a pause cannot drag the average down.
        assertEquals(75.0, average.sample(60L, 0.0, active = false), 1e-9)
        average.reset()
        assertEquals(200.0, average.sample(0L, 200.0), 0.0)
    }

    @Test
    fun `time weighting accepts fractional seconds for personas without a window field`() {
        // Orange declares no coverage window; its central weights by the real interval between
        // accepted tuples, which is not a whole number of seconds.
        val average = KayakFirstAveragePowerAccumulator()
        assertEquals(0.0, average.sample(0.0, 0.0), 0.0)
        assertEquals(50.0, average.sample(2.5, 100.0), 1e-9)
        // A second fractional frame at the same power: 375 W.s over 5.0 s.
        assertEquals(75.0, average.sample(2.5, 100.0), 1e-9)

        val estimator = KayakFirstEnergyEstimator()
        assertEquals(0.0, estimator.sample(0.0, 100.0).totalKilocalories, 0.0)
        val quarterMinute = estimator.sample(15.5, 100.0)
        assertEquals(
            kotlin.math.round(
                100.0 * 15.5 / (KayakFirstEnergyEstimator.JOULES_PER_KILOCALORIE *
                    KayakFirstEnergyEstimator.METABOLIC_EFFICIENCY),
            ),
            quarterMinute.totalKilocalories,
            0.0,
        )
    }

    @Test
    fun `estimated samples retain non-native provenance`() {
        val samples = kayakFirstEstimatedPowerSamples(123.0, 100.0)
        assertEquals(listOf(MetricKind.POWER, MetricKind.AVERAGE_POWER), samples.map { it.metric })
        assertTrue(samples.all { it.sourceClass == MetricSourceClass.DERIVED })
        assertTrue(samples.all { it.quality == MetricQuality.ESTIMATED })
    }

    @Test
    fun `energy integrates protocol time pauses and resets`() {
        val estimator = KayakFirstEnergyEstimator()
        val start = estimator.sample(0L, 100.0)
        assertEquals(0.0, start.totalKilocalories, 0.0)
        assertTrue(start.kilocaloriesPerHour in 374.0..375.0)

        val tenSeconds = estimator.sample(10L, 100.0)
        assertEquals(1.0, tenSeconds.totalKilocalories, 0.0)
        // A zero-coverage repeat integrates nothing.
        val held = estimator.sample(0L, 100.0)
        assertEquals(tenSeconds.totalKilocalories, held.totalKilocalories, 0.0)
        assertEquals(2.0, estimator.sample(10L, 100.0).totalKilocalories, 0.0)
        // Paused seconds are not integrated.
        assertEquals(2.0, estimator.sample(60L, 100.0, active = false).totalKilocalories, 0.0)

        estimator.reset()
        assertEquals(0.0, estimator.sample(0L, 200.0).totalKilocalories, 0.0)
    }

    @Test
    fun `estimated energy samples retain cumulative value and estimated provenance`() {
        val samples = kayakFirstEstimatedEnergySamples(KayakFirstEnergyEstimate(12.0, 345.0))
        assertEquals(listOf(MetricKind.ENERGY, MetricKind.ENERGY_RATE), samples.map { it.metric })
        assertEquals(12.0, samples.first().nativeCumulative!!, 0.0)
        assertTrue(samples.all { it.sourceClass == MetricSourceClass.DERIVED })
        assertTrue(samples.all { it.quality == MetricQuality.ESTIMATED })
    }

    @Test
    fun `pace consistency anomalies are non-fatal evidence`() {
        val reading = reading().copy(instantaneous500mPaceSeconds = 999)
        assertEquals(setOf(KayakFirstPaceAnomaly.INSTANT_500), reading.paceAnomalies())
    }

    @Test
    fun `held frame emits aggregates and elapsed but no instantaneous stroke sample`() {
        val held = reading().copy(activePaddling = false, elapsedSeconds = 11)
        val metrics = kayakFirstSamples(held).map { it.metric }
        assertEquals(
            listOf(MetricKind.DISTANCE, MetricKind.AVERAGE_SPEED, MetricKind.AVERAGE_CADENCE, MetricKind.ELAPSED),
            metrics,
        )
    }

    @Test
    fun `force and cadence power outranks the speed model and falls back when force is absent`() {
        // 20 N x 0.600 m x 60 spm / 60 = 12 W, and the speed model would not give 12 W here.
        assertEquals(
            12.0,
            KayakFirstDerivedMetrics.estimatePower(reading(), athleteWeightKg = 80.0)!!,
            1e-9,
        )
        // Cadence without force means the firmware is not populating force; fall back, never zero.
        val noForce = reading().copy(forceNewtons = 0.0)
        assertEquals(
            KayakFirstDerivedMetrics.estimatePowerFromSpeed(2.0, 80.0)!!,
            KayakFirstDerivedMetrics.estimatePower(noForce, athleteWeightKg = 80.0)!!,
            1e-9,
        )
        // No strokes is a real zero.
        assertEquals(
            0.0,
            KayakFirstDerivedMetrics.estimatePower(
                reading().copy(cadenceSpm = 0, speedMetresPerSecond = 0.0),
                athleteWeightKg = 80.0,
            )!!,
            0.0,
        )
    }

    @Test
    fun `represented window intersects declared coverage with the elapsed delta`() {
        val tracker = KayakFirstSampleWindowTracker()
        // First frame after a start legitimately covers everything since the workout began.
        assertEquals(5L, tracker.accept(reading().copy(elapsedSeconds = 5, windowSeconds = 5)))
        // Steady polling.
        assertEquals(1L, tracker.accept(reading().copy(elapsedSeconds = 6, windowSeconds = 1)))
        // A missed poll advances elapsed by two and the console says so.
        assertEquals(2L, tracker.accept(reading().copy(elapsedSeconds = 8, windowSeconds = 2)))
        // An over-declared window cannot exceed the elapsed delta.
        assertEquals(1L, tracker.accept(reading().copy(elapsedSeconds = 9, windowSeconds = 9)))
        // A repeated frame represents nothing.
        assertEquals(0L, tracker.accept(reading().copy(elapsedSeconds = 9, windowSeconds = 1)))
        // A console that omits field 23 falls back to the elapsed delta.
        assertEquals(3L, tracker.accept(reading().copy(elapsedSeconds = 12, windowSeconds = null)))
    }

    @Test
    fun `activity gate starts on field 7 and pauses only after the meaningful delay`() {
        val gate = KayakFirstActivityGate()
        val active = reading().copy(activePaddling = true)
        val idle = reading().copy(activePaddling = false)

        assertEquals(KayakFirstActivityTransition.STARTED, gate.accept(active, 1))
        assertEquals(KayakFirstActivityTransition.UNCHANGED, gate.accept(active, 1))
        // A brief clear between strokes must not fragment a continuous effort.
        assertEquals(KayakFirstActivityTransition.UNCHANGED, gate.accept(idle, 1))
        assertTrue(gate.isActive)
        assertEquals(KayakFirstActivityTransition.UNCHANGED, gate.accept(active, 1))
        // A real stop: the console keeps answering with held inactive frames.
        repeat(4) { assertEquals(KayakFirstActivityTransition.UNCHANGED, gate.accept(idle, 1)) }
        assertEquals(KayakFirstActivityTransition.PAUSED, gate.accept(idle, 1))
        assertTrue(!gate.isActive)
        assertEquals(KayakFirstActivityTransition.RESUMED, gate.accept(active, 1))
    }

    private fun reading() = KayakFirstMeasurement.Reading(
        eventTimestampMs = 10_000,
        activePaddling = true,
        distanceMetres = 10.0,
        speedMetresPerSecond = 2.0,
        cadenceSpm = 60,
        forceNewtons = 20.0,
        elapsedSeconds = 10,
        averageSpeedMetresPerSecond = 1.5,
        averageCadenceSpm = 50,
        average200mPaceSeconds = 100,
        instantaneous200mPaceSeconds = 90,
        average500mPaceSeconds = 250,
        instantaneous500mPaceSeconds = 225,
        average1000mPaceSeconds = 500,
        instantaneous1000mPaceSeconds = 450,
        averageForceNewtons = 18.0,
        windowSeconds = 1,
        diagnosticStatusRaw = "1",
        fieldCount = 24,
    )
}
