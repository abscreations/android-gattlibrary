package com.gmail.absolutebollockscreations.Fitbridge.bluetooth.simulator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CyclingSimulationPhysicsTest {
    @Test
    fun `flat road vector combines rolling aerodynamic and drivetrain losses`() {
        val watts = CyclingSimulationPhysics.requiredRiderPowerWatts(
            speedKph = 30.0,
            gradePercent = 0.0,
            crr = 0.004,
            cwKgPerM = 0.51,
            windMps = 0.0,
            totalMassKg = 85.0,
        )

        assertEquals(331.21, watts, 0.02)
    }

    @Test
    fun `grade headwind mass and drafting move demand in physical directions`() {
        fun power(
            grade: Double = 0.0,
            wind: Double = 0.0,
            mass: Double = 85.0,
            drafting: Double = 1.0,
        ) = CyclingSimulationPhysics.requiredRiderPowerWatts(
            30.0, grade, 0.004, 0.51, wind, mass, drafting,
        )

        val baseline = power()
        assertTrue(power(grade = 5.0) > baseline)
        assertTrue(power(grade = -5.0) < baseline)
        assertTrue(power(wind = 5.0) > baseline)
        assertTrue(power(wind = -5.0) < baseline)
        assertTrue(power(grade = 5.0, mass = 100.0) > power(grade = 5.0, mass = 70.0))
        assertTrue(power(drafting = 0.5) < baseline)
    }

    @Test
    fun `zero speed and downhill braking demand publish zero rider power`() {
        assertEquals(
            0.0,
            CyclingSimulationPhysics.requiredRiderPowerWatts(0.0, 10.0, 0.004, 0.51, 0.0),
            0.0,
        )
        assertEquals(
            0.0,
            CyclingSimulationPhysics.requiredRiderPowerWatts(30.0, -20.0, 0.004, 0.51, 0.0),
            0.0,
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `invalid physical inputs are rejected`() {
        CyclingSimulationPhysics.requiredRiderPowerWatts(
            speedKph = Double.NaN,
            gradePercent = 0.0,
            crr = 0.004,
            cwKgPerM = 0.51,
            windMps = 0.0,
        )
    }

    @Test
    fun `every constrained physical input rejects invalid values`() {
        val invalidCalls = listOf<() -> Unit>(
            { power(speed = -1.0) },
            { power(grade = Double.POSITIVE_INFINITY) },
            { power(crr = -0.001) },
            { power(cw = -0.1) },
            { power(wind = Double.NaN) },
            { power(mass = 0.0) },
            { power(drafting = -0.1) },
            { power(efficiency = 0.0) },
            { power(efficiency = 1.01) },
        )

        invalidCalls.forEach { call ->
            assertThrows(IllegalArgumentException::class.java) { call() }
        }
    }

    @Test
    fun `strong tailwind cannot create negative published rider power`() {
        assertEquals(0.0, power(wind = -30.0), 0.0)
    }

    private fun power(
        speed: Double = 30.0,
        grade: Double = 0.0,
        crr: Double = 0.004,
        cw: Double = 0.51,
        wind: Double = 0.0,
        mass: Double = 85.0,
        drafting: Double = 1.0,
        efficiency: Double = 0.975,
    ) = CyclingSimulationPhysics.requiredRiderPowerWatts(
        speed,
        grade,
        crr,
        cw,
        wind,
        mass,
        drafting,
        efficiency,
    )
}
