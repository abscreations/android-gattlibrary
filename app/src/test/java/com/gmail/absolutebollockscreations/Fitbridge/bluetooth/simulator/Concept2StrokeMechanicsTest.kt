package com.gmail.absolutebollockscreations.Fitbridge.bluetooth.simulator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class Concept2StrokeMechanicsTest {
    @Test
    fun `stroke timing work and force share one physical model`() {
        val mechanics = Concept2StrokeMechanicsModel.generate(
            machine = Concept2Machine.ROW_ERG,
            strokeIndex = 17,
            displayedStrokeRate = 28,
            displayedPowerWatts = 240,
            speedMetresPerSecond = 3.8,
        )

        assertTrue(
            abs((6_000.0 / 28.0).toInt() -
                (mechanics.driveTimeCentiseconds + mechanics.recoveryTimeCentiseconds)) <= 1,
        )
        val expectedWorkJoules = 240.0 * 60.0 / 28.0
        assertEquals(expectedWorkJoules, mechanics.workPerStrokeTenthsJoule / 10.0, 0.1)

        val advertisedAverageNewtons = mechanics.averageForceTenthsPound / 10.0 * 4.448_221_615_3
        val workFromAverageForce = advertisedAverageNewtons * mechanics.driveLengthCentimetres / 100.0
        assertEquals(expectedWorkJoules, workFromAverageForce, expectedWorkJoules * 0.04)
        assertEquals(
            mechanics.averageForceTenthsPound / 10.0,
            mechanics.forceCurvePounds.average(),
            0.6,
        )
        assertEquals(mechanics.forceCurvePounds.maxOrNull()!! * 10, mechanics.peakForceTenthsPound)
    }

    @Test
    fun `force curve has zero endpoints useful interior shoulders and a movable peak`() {
        val strokes = (1..20).map { stroke ->
            Concept2StrokeMechanicsModel.generate(
                machine = Concept2Machine.ROW_ERG,
                strokeIndex = stroke,
                displayedStrokeRate = 27,
                displayedPowerWatts = 220,
                speedMetresPerSecond = 3.7,
            )
        }

        assertTrue(strokes.all { it.forceCurvePounds.size == 24 })
        assertTrue(strokes.all { it.forceCurvePounds.first() == 0 && it.forceCurvePounds.last() == 0 })
        assertTrue(strokes.all { it.forceCurvePounds[1] > 0 && it.forceCurvePounds[it.forceCurvePounds.size - 2] > 0 })
        assertTrue(strokes.map { it.forceCurvePounds.indexOf(it.forceCurvePounds.maxOrNull()!!) }.distinct().size > 1)
        val shoulderRatios = strokes.map {
            it.forceCurvePounds.slice(1..4).sum().toDouble() /
                it.forceCurvePounds.slice((it.forceCurvePounds.size - 5)..(it.forceCurvePounds.size - 2)).sum()
        }
        assertTrue(shoulderRatios.maxOrNull()!! - shoulderRatios.minOrNull()!! > 0.05)
    }

    @Test
    fun `jitter is deterministic bounded and changes completed strokes`() {
        val first = Concept2StrokeMechanicsModel.generate(
            Concept2Machine.ROW_ERG,
            strokeIndex = 8,
            displayedStrokeRate = 26,
            displayedPowerWatts = 200,
            speedMetresPerSecond = 3.6,
        )
        val replay = Concept2StrokeMechanicsModel.generate(
            Concept2Machine.ROW_ERG,
            strokeIndex = 8,
            displayedStrokeRate = 26,
            displayedPowerWatts = 200,
            speedMetresPerSecond = 3.6,
        )
        val next = Concept2StrokeMechanicsModel.generate(
            Concept2Machine.ROW_ERG,
            strokeIndex = 9,
            displayedStrokeRate = 26,
            displayedPowerWatts = 200,
            speedMetresPerSecond = 3.6,
        )

        assertEquals(first, replay)
        assertNotEquals(first, next)
        assertTrue(abs(first.dragFactor - next.dragFactor) <= 2)
        assertTrue(first.driveLengthCentimetres in 70..180)
        assertTrue(first.driveTimeCentiseconds in 30..255)
        assertTrue(first.recoveryTimeCentiseconds in 20..0xFFFF)
        assertTrue(first.peakForceTenthsPound in 1..0xFFFF)
        assertTrue(first.averageForceTenthsPound in 1..0xFFFF)
        assertTrue(first.workPerStrokeTenthsJoule in 1..0xFFFF)
    }

    @Test
    fun `engine holds mechanics stable inside a stroke then advances them`() {
        val engine = Concept2Pm5MockEngine(Concept2Machine.ROW_ERG, startsPaused = true)
        engine.startWorkout()
        engine.tick(3.5)
        val first = engine.tick(0.1)
        val sameStroke = engine.tick(0.1)

        assertEquals(first.strokeCount, sameStroke.strokeCount)
        assertEquals(first.driveLengthCentimetres, sameStroke.driveLengthCentimetres)
        assertEquals(first.driveTimeCentiseconds, sameStroke.driveTimeCentiseconds)
        assertEquals(first.recoveryTimeCentiseconds, sameStroke.recoveryTimeCentiseconds)
        assertEquals(first.workPerStrokeTenthsJoule, sameStroke.workPerStrokeTenthsJoule)
        assertEquals(first.forceCurvePounds, sameStroke.forceCurvePounds)

        var nextStroke = sameStroke
        while (nextStroke.strokeCount == first.strokeCount) nextStroke = engine.tick(0.1)
        assertNotEquals(first.forceCurvePounds, nextStroke.forceCurvePounds)
    }
}
