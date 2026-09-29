package com.gmail.absolutebollockscreations.gattlibrary.simulator

import com.gmail.absolutebollockscreations.gattlibrary.central.BtServiceProfiles.FtmsMachineProfile
import com.gmail.absolutebollockscreations.gattlibrary.peripheral.FtmsSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.roundToInt
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
    fun `autonomous profiles retain the ordinary workout bands`() {
        fun assertRange(expectedMin: Double, expectedMax: Double, actual: FtmsMockRange?) =
            assertEquals(FtmsMockRange(expectedMin, expectedMax), actual)

        FtmsMockProfile.forMachine(FtmsMachineProfile.INDOOR_BIKE).also {
            assertRange(24.0, 38.0, it.speed)
            assertRange(75.0, 95.0, it.cadence)
            assertRange(100.0, 260.0, it.power)
            assertRange(105.0, 160.0, it.heartRate)
            assertRange(3.0, 12.0, it.resistance)
        }
        FtmsMockProfile.forMachine(FtmsMachineProfile.TREADMILL).also {
            assertRange(8.0, 13.0, it.speed)
            assertRange(170.0, 330.0, it.power)
            assertRange(110.0, 168.0, it.heartRate)
            assertRange(0.0, 4.0, it.inclination)
        }
        FtmsMockProfile.forMachine(FtmsMachineProfile.ELLIPTICAL).also {
            assertRange(7.0, 11.0, it.speed)
            assertRange(110.0, 150.0, it.cadence)
            assertRange(90.0, 240.0, it.power)
            assertRange(105.0, 165.0, it.heartRate)
            assertRange(3.0, 12.0, it.resistance)
            assertRange(2.0, 10.0, it.inclination)
        }
        FtmsMockProfile.forMachine(FtmsMachineProfile.INDOOR_ROWER).also {
            assertRange(11.0, 15.0, it.speed)
            assertRange(20.0, 32.0, it.cadence)
            assertRange(80.0, 280.0, it.power)
            assertRange(105.0, 168.0, it.heartRate)
            assertRange(3.0, 8.0, it.resistance)
        }
        FtmsMockProfile.forMachine(FtmsMachineProfile.STAIR_STEPPER).also {
            assertRange(50.0, 80.0, it.cadence)
            assertRange(90.0, 190.0, it.power)
            assertRange(110.0, 165.0, it.heartRate)
        }
        FtmsMockProfile.forMachine(FtmsMachineProfile.STAIR_CLIMBER).also {
            assertRange(45.0, 75.0, it.cadence)
            assertRange(90.0, 180.0, it.power)
            assertRange(110.0, 165.0, it.heartRate)
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
    fun `every applicable persona uses the standardized advertised ranges`() {
        val speed = FtmsMockControlProfile.speedRange
        val inclination = FtmsMockControlProfile.inclinationRange
        val resistance = FtmsMockControlProfile.resistanceRange
        val power = FtmsMockControlProfile.powerRange

        assertEquals(Triple(0, 3_000, 10), speed.asTriple())
        assertEquals(Triple(-100, 300, 5), inclination.asTriple())
        assertEquals(Triple(10, 300, 10), resistance.asTriple())
        assertEquals(Triple(1, 1_000, 1), power.asTriple())

        for (machine in FtmsMockEngine.supportedMachines) {
            val caps = FtmsMockControlProfile.forMachine(machine)
            caps.speedRange?.let { assertSame("$machine speed", speed, it) }
            caps.inclinationRange?.let { assertSame("$machine inclination", inclination, it) }
            caps.resistanceRange?.let { assertSame("$machine resistance", resistance, it) }
            caps.powerRange?.let { assertSame("$machine power", power, it) }
        }
    }

    @Test
    fun `persona controls are present only where supported`() {
        fun capabilities(machine: FtmsMachineProfile) = FtmsMockControlProfile.forMachine(machine)

        val bike = capabilities(FtmsMachineProfile.INDOOR_BIKE)
        assertTrue(bike.supportsSpeed && bike.supportsResistance && bike.supportsPower)
        assertFalse(bike.supportsInclination)

        val treadmill = capabilities(FtmsMachineProfile.TREADMILL)
        assertTrue(treadmill.supportsSpeed && treadmill.supportsInclination && treadmill.supportsPower)
        assertFalse(treadmill.supportsResistance)

        val cross = capabilities(FtmsMachineProfile.ELLIPTICAL)
        assertTrue(
            cross.supportsSpeed && cross.supportsInclination &&
                cross.supportsResistance && cross.supportsPower,
        )

        val rower = capabilities(FtmsMachineProfile.INDOOR_ROWER)
        assertTrue(rower.supportsSpeed && rower.supportsResistance && rower.supportsPower)
        assertFalse(rower.supportsInclination)

        assertFalse(capabilities(FtmsMachineProfile.STAIR_STEPPER).any)
        assertFalse(capabilities(FtmsMachineProfile.STAIR_CLIMBER).any)
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
    fun `enabling controls starts in the autonomous workout bands with no overrides`() {
        val e = engine(FtmsMachineProfile.INDOOR_BIKE, controls = true)
        val s = e.snapshot()
        val profile = FtmsMockProfile.forMachine(FtmsMachineProfile.INDOOR_BIKE)
        assertTrue(s.speedKph!! in profile.speed!!.min..profile.speed.max)
        assertTrue(s.powerWatts!!.toDouble() in profile.power.min..profile.power.max)
        assertTrue(s.resistance!!.toDouble() in profile.resistance!!.min..profile.resistance.max)
        assertTrue(e.controlTargets().isEmpty())
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
    fun `bike simulation power is the shared road load at the published speed`() {
        val e = engine(FtmsMachineProfile.INDOOR_BIKE, controls = true)
        assertTrue(e.setTarget(FtmsMockTarget.POWER, 240.0))
        val sim = FtmsMockSimulationParams(
            windMps = 2.0,
            gradePercent = 4.0,
            crr = 0.0045,
            cw = 0.51,
        )
        assertTrue(e.setSimulationParams(sim))
        val sample = e.tick(0.5)
        val expected = CyclingSimulationPhysics.requiredRiderPowerWatts(
            speedKph = sample.speedKph!!,
            gradePercent = sim.gradePercent,
            crr = sim.crr,
            cwKgPerM = sim.cw,
            windMps = sim.windMps,
        ).roundToInt()

        assertEquals(expected, sample.powerWatts)
        assertFalse(FtmsMockTarget.POWER in e.controlTargets())
        assertEquals(SimulatorControlModeLabels.FTMS_SIM, e.controlModeLabel())
    }

    @Test
    fun `out of range targets are clamped, not rejected outright`() {
        val e = engine(FtmsMachineProfile.INDOOR_BIKE, controls = true)
        assertTrue(e.setTarget(FtmsMockTarget.POWER, 5_000.0))
        assertEquals(1_000, e.snapshot().powerWatts)
        assertTrue(e.setTarget(FtmsMockTarget.POWER, -100.0))
        assertEquals(1, e.snapshot().powerWatts)
    }

    @Test
    fun `resistance and power targets retain the requested precision`() {
        // The rower's autonomous resistance is only 3..8, but its full machine
        // range is the standardized 1..30 and must not leak the old max of 10.
        val e = engine(FtmsMachineProfile.INDOOR_ROWER, controls = true)
        for (level in listOf(12.0, 15.0, 13.0, 16.0)) {
            assertTrue(e.setTarget(FtmsMockTarget.RESISTANCE, level))
            assertEquals(level.toInt(), e.snapshot().resistance)
            assertEquals(level, e.controlTargets()[FtmsMockTarget.RESISTANCE]!!, 0.001)
            assertEquals(SimulatorControlModeLabels.FTMS_DIRECT, e.controlModeLabel())
        }

        assertTrue(e.setTarget(FtmsMockTarget.POWER, 237.0))
        assertEquals(237, e.snapshot().powerWatts)
        assertEquals(237.0, e.controlTargets()[FtmsMockTarget.POWER]!!, 0.001)
    }

    @Test
    fun `all advertised target boundaries are accepted`() {
        val e = engine(FtmsMachineProfile.ELLIPTICAL, controls = true)
        val cases = listOf(
            Triple(FtmsMockTarget.SPEED, 0.0, 30.0),
            Triple(FtmsMockTarget.INCLINATION, -10.0, 30.0),
            Triple(FtmsMockTarget.RESISTANCE, 1.0, 30.0),
            Triple(FtmsMockTarget.POWER, 1.0, 1_000.0),
        )
        for ((target, minimum, maximum) in cases) {
            assertTrue(e.setTarget(target, minimum))
            assertEquals(minimum, e.controlTargets()[target]!!, 0.001)
            assertTrue(e.setTarget(target, maximum))
            assertEquals(maximum, e.controlTargets()[target]!!, 0.001)
        }
    }

    @Test
    fun `targets clamp and snap in advertised wire units`() {
        val e = engine(FtmsMachineProfile.ELLIPTICAL, controls = true)
        assertTrue(e.setTarget(FtmsMockTarget.SPEED, 40.0))
        assertEquals(30.0, e.controlTargets()[FtmsMockTarget.SPEED]!!, 0.001)
        assertTrue(e.setTarget(FtmsMockTarget.SPEED, 12.34))
        assertEquals(12.3, e.controlTargets()[FtmsMockTarget.SPEED]!!, 0.001)

        assertTrue(e.setTarget(FtmsMockTarget.INCLINATION, -20.0))
        assertEquals(-10.0, e.controlTargets()[FtmsMockTarget.INCLINATION]!!, 0.001)
        assertTrue(e.setTarget(FtmsMockTarget.INCLINATION, 1.26))
        assertEquals(1.5, e.controlTargets()[FtmsMockTarget.INCLINATION]!!, 0.001)

        assertTrue(e.setTarget(FtmsMockTarget.RESISTANCE, 99.0))
        assertEquals(30.0, e.controlTargets()[FtmsMockTarget.RESISTANCE]!!, 0.001)
        assertTrue(e.setTarget(FtmsMockTarget.POWER, -5.0))
        assertEquals(1.0, e.controlTargets()[FtmsMockTarget.POWER]!!, 0.001)
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
        val first = e.tick(1.0)
        repeat(60) { e.tick(1.0) }
        val later = e.snapshot()

        assertEquals(200, later.powerWatts)
        assertTrue("speed should still drift", first.speedKph != later.speedKph)
        assertTrue("cadence should still drift", first.cadence != later.cadence)
        assertTrue("resistance should still drift", first.resistance != later.resistance)
        assertTrue("heart rate should still drift", first.heartRate != later.heartRate)
        assertTrue("elapsed time should advance", later.elapsedSeconds > first.elapsedSeconds)
        assertTrue("distance should advance", later.distanceMeters!! > first.distanceMeters!!)
    }

    @Test
    fun `multiple overrides coexist and replacing one preserves the others`() {
        val e = engine(FtmsMachineProfile.ELLIPTICAL, controls = true)
        assertTrue(e.setTarget(FtmsMockTarget.SPEED, 10.0))
        assertTrue(e.setTarget(FtmsMockTarget.INCLINATION, 7.5))
        assertTrue(e.setTarget(FtmsMockTarget.RESISTANCE, 16.0))
        assertTrue(e.setTarget(FtmsMockTarget.POWER, 237.0))
        repeat(60) { e.tick(0.5) }

        assertEquals(
            mapOf(
                FtmsMockTarget.SPEED to 10.0,
                FtmsMockTarget.INCLINATION to 7.5,
                FtmsMockTarget.RESISTANCE to 16.0,
                FtmsMockTarget.POWER to 237.0,
            ),
            e.controlTargets(),
        )

        assertTrue(e.setTarget(FtmsMockTarget.RESISTANCE, 13.0))
        assertEquals(10.0, e.controlTargets()[FtmsMockTarget.SPEED]!!, 0.001)
        assertEquals(7.5, e.controlTargets()[FtmsMockTarget.INCLINATION]!!, 0.001)
        assertEquals(13.0, e.controlTargets()[FtmsMockTarget.RESISTANCE]!!, 0.001)
        assertEquals(237.0, e.controlTargets()[FtmsMockTarget.POWER]!!, 0.001)
    }

    @Test
    fun `derived distance average speed and rower pace use overridden speed`() {
        val e = engine(FtmsMachineProfile.INDOOR_ROWER, controls = true)
        assertTrue(e.setTarget(FtmsMockTarget.SPEED, 18.0))
        val s = e.tick(10.0)

        assertEquals(18.0, s.speedKph!!, 0.001)
        assertEquals(50, s.distanceMeters)
        assertEquals(18.0, s.averageSpeedKph!!, 0.001)
        assertEquals(60.0 / 18.0, s.instantPaceMinPerKm!!, 0.001)
        assertEquals(60.0 / 18.0, s.averagePaceMinPerKm!!, 0.001)
    }

    @Test
    fun `derived calories and belt force use overridden power and speed`() {
        val lowPower = engine(FtmsMachineProfile.TREADMILL, controls = true)
        val highPower = engine(FtmsMachineProfile.TREADMILL, controls = true)
        assertTrue(lowPower.setTarget(FtmsMockTarget.SPEED, 10.0))
        assertTrue(highPower.setTarget(FtmsMockTarget.SPEED, 10.0))
        assertTrue(lowPower.setTarget(FtmsMockTarget.POWER, 100.0))
        assertTrue(highPower.setTarget(FtmsMockTarget.POWER, 250.0))

        assertEquals(90, highPower.snapshot().forceOnBeltNewtons)
        assertTrue(highPower.tick(600.0).caloriesTotal > lowPower.tick(600.0).caloriesTotal)
    }

    @Test
    fun `average cadence and power are time weighted and reset with the workout`() {
        val e = engine(FtmsMachineProfile.INDOOR_BIKE, controls = true)
        assertTrue(e.setTarget(FtmsMockTarget.POWER, 100.0))
        val first = e.tick(10.0)
        assertEquals(100, first.averagePowerWatts)

        assertTrue(e.setTarget(FtmsMockTarget.POWER, 300.0))
        val second = e.tick(30.0)
        assertEquals(250, second.averagePowerWatts)
        assertEquals(
            (first.cadence!! * 10.0 + second.cadence!! * 30.0) / 40.0,
            second.averageCadence!!,
            0.001,
        )

        e.reset()
        val reset = e.snapshot()
        assertEquals(reset.cadence!!, reset.averageCadence!!, 0.001)
        assertEquals(reset.powerWatts, reset.averagePowerWatts)
    }

    @Test
    fun `average metrics follow persona measurement availability`() {
        val treadmill = engine(FtmsMachineProfile.TREADMILL).tick(1.0)
        assertNull(treadmill.averageCadence)
        assertNotNull(treadmill.averagePowerWatts)

        val climber = engine(FtmsMachineProfile.STAIR_CLIMBER).tick(1.0)
        assertNotNull(climber.averageCadence)
        assertNull(climber.averagePowerWatts)
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
        assertTrue(e.controlTargets().isEmpty())
        assertTrue(s.powerWatts!!.toDouble() in e.profile.power.min..e.profile.power.max)

        val initialPower = s.powerWatts
        repeat(60) { e.tick(1.0) }
        assertTrue("autonomous power should resume after reset", e.snapshot().powerWatts != initialPower)
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

    private fun com.gmail.absolutebollockscreations.gattlibrary.peripheral.FtmsTargetRange.asTriple() =
        Triple(minimum, maximum, increment)
}
