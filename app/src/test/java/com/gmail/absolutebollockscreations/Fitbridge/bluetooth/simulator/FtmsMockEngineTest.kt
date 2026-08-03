package com.gmail.absolutebollockscreations.Fitbridge.bluetooth.simulator

import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine.BtServiceProfiles.FtmsMachineProfile
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.peripheral.FtmsSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class FtmsMockEngineTest {

    private fun engine(
        machine: FtmsMachineProfile,
        controls: Boolean = false,
    ) = FtmsMockEngine(machine, controlsEnabled = controls, random = Random(1234))

    @Test
    fun `every supported persona has a profile`() {
        for (machine in FtmsMockEngine.supportedMachines) {
            assertNotNull("$machine has no mock profile", FtmsMockProfile.profiles[machine])
        }
        assertEquals(
            "every FTMS persona should be simulatable",
            FtmsMachineProfile.entries.size,
            FtmsMockEngine.supportedMachines.size,
        )
    }

    @Test
    fun `a persona only emits metrics its data characteristic can carry`() {
        // The rower has no speed field on 0x2AD1 but does have pace; the climbers
        // have neither speed nor power on the wire. Emitting a metric the encoder
        // will discard makes the simulator useless as a packing check.
        val rower = engine(FtmsMachineProfile.INDOOR_ROWER).tick(1.0)
        assertNotNull(rower.instantPaceMinPerKm)
        assertNotNull(rower.movementCount)
        assertNotNull(rower.powerWatts)

        val climber = engine(FtmsMachineProfile.STAIR_CLIMBER).tick(1.0)
        assertNull("climbers publish no speed", climber.speedKph)
        assertNull("climbers publish no power measurement", climber.powerWatts)
        assertNotNull(climber.floors)
        assertNotNull(climber.positiveElevationMeters)

        val bike = engine(FtmsMachineProfile.INDOOR_BIKE).tick(1.0)
        assertNull("only the treadmill reports belt force", bike.forceOnBeltNewtons)
        assertNotNull(bike.cadence)
        assertNotNull(bike.resistance)

        val treadmill = engine(FtmsMachineProfile.TREADMILL).tick(1.0)
        assertNotNull(treadmill.forceOnBeltNewtons)
        assertNotNull(treadmill.inclinationPercent)
        assertNull("treadmill persona has no cadence", treadmill.cadence)
    }

    @Test
    fun `metrics stay inside their profile range`() {
        for (machine in FtmsMockEngine.supportedMachines) {
            val e = engine(machine)
            val profile = FtmsMockProfile.forMachine(machine)
            repeat(600) { e.tick(0.5) }
            val s = e.snapshot()

            s.speedKph?.let {
                val r = profile.speed!!
                assertTrue("$machine speed $it out of ${r.min}..${r.max}", it in r.min..r.max)
            }
            s.cadence?.let {
                val r = profile.cadence!!
                assertTrue("$machine cadence $it out of range", it in r.min..r.max)
            }
            assertTrue(
                "$machine heart rate ${s.heartRate} out of range",
                s.heartRate.toDouble() in profile.heartRate.min..profile.heartRate.max,
            )
        }
    }

    @Test
    fun `cumulative counters only ever increase`() {
        val e = engine(FtmsMachineProfile.INDOOR_BIKE)
        var distance = 0
        var calories = 0
        var elapsed = 0
        repeat(120) {
            val s = e.tick(0.5)
            assertTrue(s.distanceMeters!! >= distance)
            assertTrue(s.caloriesTotal >= calories)
            assertTrue(s.elapsedSeconds >= elapsed)
            distance = s.distanceMeters
            calories = s.caloriesTotal
            elapsed = s.elapsedSeconds
        }
        assertTrue("a minute of riding should cover ground", distance > 0)
    }

    @Test
    fun `tick rate does not change the workout`() {
        // The generator uses exponential approach rather than a per-tick delta, so
        // halving the period must not halve the distance covered.
        val coarse = FtmsMockEngine(FtmsMachineProfile.INDOOR_BIKE, random = Random(7))
        val fine = FtmsMockEngine(FtmsMachineProfile.INDOOR_BIKE, random = Random(7))
        repeat(60) { coarse.tick(1.0) }
        repeat(120) { fine.tick(0.5) }

        assertEquals(60, coarse.snapshot().elapsedSeconds)
        assertEquals(60, fine.snapshot().elapsedSeconds)
        val ratio = fine.snapshot().distanceMeters!!.toDouble() /
            coarse.snapshot().distanceMeters!!.toDouble()
        assertTrue("distance ratio was $ratio", ratio in 0.9..1.1)
    }

    @Test
    fun `zero and negative dt are inert`() {
        val e = engine(FtmsMachineProfile.INDOOR_BIKE)
        val before = e.tick(1.0)
        assertEquals(before, e.tick(0.0))
        assertEquals(before, e.tick(-5.0))
    }

    @Test
    fun `control capabilities match the personas that can be steered`() {
        for (machine in listOf(
            FtmsMachineProfile.INDOOR_BIKE,
            FtmsMachineProfile.TREADMILL,
            FtmsMachineProfile.ELLIPTICAL,
            FtmsMachineProfile.INDOOR_ROWER,
        )) {
            assertTrue("$machine should be controllable", FtmsMockControlProfile.forMachine(machine).any)
        }
        // FTMS has no target step-rate procedure.
        for (machine in listOf(
            FtmsMachineProfile.STAIR_STEPPER,
            FtmsMachineProfile.STAIR_CLIMBER,
        )) {
            assertFalse(
                "$machine advertises a target it cannot honour",
                FtmsMockControlProfile.forMachine(machine).any,
            )
        }
    }

    @Test
    fun `advertised control ranges are encodable at their wire widths`() {
        // A range that cannot be encoded would be silently dropped from the GATT
        // database by TargetCapabilities, leaving a Target Setting Feature bit set
        // with no matching Supported-* characteristic.
        for (machine in FtmsMockEngine.supportedMachines) {
            val caps = FtmsMockControlProfile.forMachine(machine)
            caps.speedRange?.let { assertTrue("$machine speed", it.isValidU16) }
            caps.inclinationRange?.let { assertTrue("$machine incline", it.isValidS16) }
            caps.resistanceRange?.let { assertTrue("$machine resistance", it.isValidS16) }
            caps.powerRange?.let { assertTrue("$machine power", it.isValidS16) }
            assertEquals(caps.supportsSpeed, caps.speedRange != null)
            assertEquals(caps.supportsPower, caps.powerRange != null)
        }
    }

    @Test
    fun `target setting feature bits follow the advertised ranges`() {
        val bits = FtmsMockControlProfile.forMachine(FtmsMachineProfile.TREADMILL)
            .toTargetFeatureBits()
        val t = FtmsSpec.TargetFeature
        assertTrue(bits and (1 shl t.SPEED) != 0)
        assertTrue(bits and (1 shl t.INCLINATION) != 0)
        assertTrue(bits and (1 shl t.POWER) != 0)
        // The treadmill persona advertises no resistance target.
        assertEquals(0, bits and (1 shl t.RESISTANCE))
    }

    @Test
    fun `controlled metrics start at the advertised minimum`() {
        val e = engine(FtmsMachineProfile.INDOOR_BIKE, controls = true)
        val s = e.snapshot()
        // Wire 500 = 5.00 km/h, 50 W, resistance wire 10 = level 1.0.
        assertEquals(5.0, s.speedKph!!, 0.001)
        assertEquals(50, s.powerWatts)
        assertEquals(1, s.resistance)
        assertEquals(5.0, e.controlTargets()[FtmsMockTarget.SPEED]!!, 0.001)
    }

    @Test
    fun `a target write steers the machine and is held against drift`() {
        val e = engine(FtmsMachineProfile.INDOOR_BIKE, controls = true)
        assertTrue(e.setTarget(FtmsMockTarget.POWER, 240.0))
        assertEquals(240, e.snapshot().powerWatts)

        // Under control the free-running walk must not pull power away.
        repeat(60) { e.tick(0.5) }
        assertEquals(240, e.snapshot().powerWatts)
        assertEquals(240.0, e.controlTargets()[FtmsMockTarget.POWER]!!, 0.001)
    }

    @Test
    fun `out of range targets are clamped, not rejected outright`() {
        val e = engine(FtmsMachineProfile.INDOOR_BIKE, controls = true)
        assertTrue(e.setTarget(FtmsMockTarget.POWER, 5_000.0))
        assertEquals(600, e.snapshot().powerWatts)
        assertTrue(e.setTarget(FtmsMockTarget.POWER, -100.0))
        assertEquals(50, e.snapshot().powerWatts)
    }

    @Test
    fun `unsupported targets are refused`() {
        val bike = engine(FtmsMachineProfile.INDOOR_BIKE, controls = true)
        assertFalse("bike has no inclination target", bike.setTarget(FtmsMockTarget.INCLINATION, 5.0))

        val climber = engine(FtmsMachineProfile.STAIR_CLIMBER, controls = true)
        assertFalse(climber.setTarget(FtmsMockTarget.POWER, 150.0))

        // With controls off, nothing is steerable even on a capable persona.
        val uncontrolled = engine(FtmsMachineProfile.INDOOR_BIKE, controls = false)
        assertFalse(uncontrolled.setTarget(FtmsMockTarget.POWER, 200.0))
    }

    @Test
    fun `uncontrolled metrics keep moving while controlled ones are pinned`() {
        val e = engine(FtmsMachineProfile.INDOOR_BIKE, controls = true)
        e.setTarget(FtmsMockTarget.POWER, 200.0)
        val firstCadence = e.tick(1.0).cadence!!
        repeat(60) { e.tick(1.0) }
        val laterCadence = e.snapshot().cadence!!

        assertEquals(200, e.snapshot().powerWatts)
        assertTrue("cadence should still drift", firstCadence != laterCadence)
    }

    @Test
    fun `reset returns the engine to its starting condition`() {
        val e = engine(FtmsMachineProfile.INDOOR_BIKE, controls = true)
        e.setTarget(FtmsMockTarget.POWER, 300.0)
        repeat(60) { e.tick(1.0) }
        assertTrue(e.snapshot().distanceMeters!! > 0)

        e.reset()
        val s = e.snapshot()
        assertEquals(0, s.elapsedSeconds)
        assertEquals(0, s.distanceMeters)
        assertEquals(0, s.caloriesTotal)
        assertEquals(50, s.powerWatts) // back to the advertised minimum
    }

    @Test
    fun `rower average pace tracks the session, not the instant`() {
        val e = engine(FtmsMachineProfile.INDOOR_ROWER)
        repeat(120) { e.tick(0.5) }
        val s = e.snapshot()
        assertNotNull(s.averagePaceMinPerKm)
        assertTrue("pace must be positive", s.instantPaceMinPerKm!! > 0)
        assertTrue("average pace must be positive", s.averagePaceMinPerKm!! > 0)
    }

    @Test
    fun `climber floors follow accumulated elevation at three metres each`() {
        val e = engine(FtmsMachineProfile.STAIR_STEPPER)
        repeat(600) { e.tick(0.5) }
        val s = e.snapshot()
        assertEquals(
            (s.positiveElevationMeters!! / 3.0).toInt(),
            s.floors,
        )
    }

    @Test
    fun `treadmill belt force is derived from power and speed`() {
        val e = engine(FtmsMachineProfile.TREADMILL)
        val s = e.tick(1.0)
        val metresPerSecond = s.speedKph!! / 3.6
        // F = P / v, rounded. Allow one newton for the rounding of each term.
        assertEquals(
            s.powerWatts!! / metresPerSecond,
            s.forceOnBeltNewtons!!.toDouble(),
            1.0,
        )
    }

    @Test
    fun `the same seed produces the same workout`() {
        val a = FtmsMockEngine(FtmsMachineProfile.INDOOR_BIKE, random = Random(42))
        val b = FtmsMockEngine(FtmsMachineProfile.INDOOR_BIKE, random = Random(42))
        repeat(100) {
            assertEquals(a.tick(0.5), b.tick(0.5))
        }
    }
}
