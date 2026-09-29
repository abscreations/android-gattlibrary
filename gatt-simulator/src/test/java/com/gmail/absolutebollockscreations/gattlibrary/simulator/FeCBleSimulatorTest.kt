package com.gmail.absolutebollockscreations.gattlibrary.simulator

import com.gmail.absolutebollockscreations.gattlibrary.central.BluetoothIds
import com.gmail.absolutebollockscreations.gattlibrary.peripheral.CyclingRevolutionSnapshot
import com.gmail.absolutebollockscreations.gattlibrary.peripheral.CyclingSensorEncoders
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.roundToInt

/**
 * Pure simulator tests against `docs/fe-c-ble_simulator_implementation.md` §21-§25.
 *
 * No Android dependency: everything here exercises [FeCBleMachineState],
 * [FeCBleTelemetryEncoder], [FeCBleControlDecoder], [FeCBleSyntheticModel], and
 * [FeCBleTelemetryScheduler] directly, the same layer the plan requires be pinned against
 * external vectors independently of any production central decoder (plan §4.2).
 */
class FeCBleSimulatorTest {

    private fun hex(frame: ByteArray): String = frame.joinToString(" ") { "%02X".format(it) }

    private fun bytes(spec: String): ByteArray =
        spec.trim().split(Regex("\\s+")).map { it.toInt(16).toByte() }.toByteArray()

    private fun u16(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)

    // ── §22 pinned vectors ──────────────────────────────────────────────────

    @Test
    fun `22-1 Tacx source READY page 16 vector`() {
        val state = FeCBleMachineState(FeCEquipmentType.TRAINER)
        state.elapsedQuarterSeconds = 4
        val frame = FeCBleTelemetryEncoder.encodePage16Frame(state)
        assertEquals("A4 09 4E 05 10 19 04 00 00 00 FF 24 30", hex(frame))
    }

    @Test
    fun `22-2 representative Trainer IN_USE page 16 vector`() {
        val state = FeCBleMachineState(FeCEquipmentType.TRAINER)
        state.feState = FeCEquipmentState.IN_USE
        state.elapsedQuarterSeconds = 4
        state.distanceMeters = 1.0
        state.speedMps = 8.333
        state.heartRateBpm = 140
        state.heartRateSource = FeCHeartRateSource.EM
        val frame = FeCBleTelemetryEncoder.encodePage16Frame(state)
        assertEquals("A4 09 4E 05 10 19 04 01 8D 20 8C 36 FD", hex(frame))
    }

    @Test
    fun `22-3 representative Stationary Bike page 25 vector`() {
        val state = FeCBleMachineState(FeCEquipmentType.STATIONARY_BIKE)
        state.feState = FeCEquipmentState.IN_USE
        state.eventCount = 1
        state.cadenceRpm = 90
        state.accumulatedPower = 200
        state.instantaneousPowerWatts = 200
        val frame = FeCBleTelemetryEncoder.encodePage25Frame(state)
        assertEquals("A4 09 4E 05 19 01 5A C8 00 C8 00 30 94", hex(frame))
    }

    @Test
    fun `22-4 product information vector`() {
        val state = FeCBleMachineState(FeCEquipmentType.TRAINER)
        val frame = FeCBleTelemetryEncoder.encodePage81Frame(state)
        assertEquals("A4 09 4E 05 51 FF 05 01 78 56 34 12 44", hex(frame))
    }

    @Test
    fun `physical Neo page 80 manufacturer information vector`() {
        val page = FeCBleTelemetryEncoder.encodePage80(
            hardwareRevision = 1,
            manufacturerId = 89,
            modelNumber = 2800,
        )
        assertEquals("50 FF FF 01 59 00 F0 0A", hex(page))
        assertEquals(
            "A4 09 4F 05 50 FF FF 01 59 00 F0 0A 15",
            hex(FeCBleTelemetryEncoder.buildAntFrame(page, BluetoothIds.FEC_ANT_MSG_ACKNOWLEDGED_DATA)),
        )
    }

    @Test
    fun `official page 17 layout encodes cycle incline resistance and state`() {
        val state = FeCBleMachineState(FeCEquipmentType.TREADMILL).apply {
            feState = FeCEquipmentState.IN_USE
            cycleLengthMeters = 1.23
            inclinePercent = -4.25
            resistanceLevelPercent = 37.5
        }
        val page = FeCBleTelemetryEncoder.encodePage17(state)
        assertEquals("11 FF FF 7B 57 FE 4B 30", hex(page))
    }

    @Test
    fun `official page 18 layout encodes METs rate accumulated calories and capability`() {
        val state = FeCBleMachineState(FeCEquipmentType.ELLIPTICAL).apply {
            feState = FeCEquipmentState.IN_USE
            metabolicEquivalent = 6.50
            caloricBurnRateKcalPerHour = 487.5
            accumulatedCaloriesKcal = 257.9
        }
        val page = FeCBleTelemetryEncoder.encodePage18(state)
        assertEquals("12 FF 8A 02 0B 13 01 31", hex(page))
    }

    @Test
    fun `pages 20 23 and 24 encode their official count cadence and power layout`() {
        val expectedPages = listOf(
            FeCEquipmentType.ELLIPTICAL to 0x14,
            FeCEquipmentType.CLIMBER to 0x17,
            FeCEquipmentType.NORDIC_SKIER to 0x18,
        )
        expectedPages.forEach { (type, pageNumber) ->
            val state = FeCBleMachineState(type).apply {
                feState = FeCEquipmentState.IN_USE
                movementCount = 258.9
                cadenceRpm = 77
                instantaneousPowerWatts = 345
                positiveVerticalDistanceMeters = 25.7
            }
            val page = when (type) {
                FeCEquipmentType.ELLIPTICAL -> FeCBleTelemetryEncoder.encodePage20(state)
                FeCEquipmentType.CLIMBER -> FeCBleTelemetryEncoder.encodePage23(state)
                FeCEquipmentType.NORDIC_SKIER -> FeCBleTelemetryEncoder.encodePage24(state)
                else -> error("unexpected test persona")
            }
            assertEquals(pageNumber, page[0].toInt() and 0xFF)
            assertEquals(2, page[3].toInt() and 0xFF)
            assertEquals(77, page[4].toInt() and 0xFF)
            assertEquals(345, u16(page, 5))
            assertEquals(
                if (type == FeCEquipmentType.ELLIPTICAL) 0x03 else 0x01,
                page[7].toInt() and 0x0F,
            )
        }
    }

    @Test
    fun `page 54 publishes persona max resistance and only honest control mode flags`() {
        FeCEquipmentType.entries.forEach { type ->
            val page = FeCBleTelemetryEncoder.encodePage54(FeCBleMachineState(type))
            assertEquals(type.maximumResistanceNewtons, u16(page, 5))
            assertEquals(if (type == FeCEquipmentType.TRAINER) 0x07 else 0x00, page[7].toInt() and 0xFF)
        }
    }

    @Test
    fun `common page 70 requests page 54 twice as broadcast data`() {
        val wire = FeCBleTelemetryEncoder.buildAntFrame(
            bytes("46 FF FF FF FF 02 36 01"),
            BluetoothIds.FEC_ANT_MSG_ACKNOWLEDGED_DATA,
        )
        val request = FeCBleControlDecoder.decode(wire) as FeCBleControlCommand.RequestDataPage
        assertEquals(0x36, request.requestedPage)
        assertEquals(2, request.transmissionCount)
        assertTrue(!request.acknowledgedResponse)
        assertTrue(!request.transmitUntilAcknowledged)
    }

    @Test
    fun `captured Garmin page 80 request decodes special until-acknowledged response mode`() {
        val request = FeCBleControlDecoder.decode(
            bytes("A4 09 4F 05 46 FF FF FF FF 80 50 01 70"),
        ) as FeCBleControlCommand.RequestDataPage
        assertEquals(BluetoothIds.FEC_PAGE_MANUFACTURER_INFORMATION, request.requestedPage)
        assertEquals(1, request.transmissionCount)
        assertTrue(request.acknowledgedResponse)
        assertTrue(request.transmitUntilAcknowledged)
    }

    @Test
    fun `22-5 basic resistance 50 percent decodes`() {
        val command = FeCBleControlDecoder.decode(bytes("A4 09 4F 05 30 FF FF FF FF FF FF 64 B3"))
        val resistance = command as FeCBleControlCommand.BasicResistance
        assertEquals(50.0, resistance.resistancePercent, 1e-9)
    }

    @Test
    fun `22-6 target power 250W decodes`() {
        val command = FeCBleControlDecoder.decode(bytes("A4 09 4F 05 31 FF FF FF FF FF E8 03 C2"))
        val power = command as FeCBleControlCommand.TargetPower
        assertEquals(250.0, power.watts, 1e-9)
    }

    @Test
    fun `22-7 wind fixture decodes`() {
        val command = FeCBleControlDecoder.decode(bytes("A4 09 4F 05 32 FF FF FF FF 33 7F 64 FD"))
        val wind = command as FeCBleControlCommand.WindResistance
        assertEquals(0.51, wind.windCoefficient!!, 1e-9)
        assertEquals(0, wind.windSpeedEncodedUnit)
        assertEquals(1.00, wind.draftingFactor!!, 1e-9)
    }

    @Test
    fun `22-8 Incyclist slope-zero vector decodes exactly`() {
        val command = FeCBleControlDecoder.decode(bytes("A4 09 4F 05 33 FF FF FF FF 20 4E 42 F8"))
        val track = command as FeCBleControlCommand.TrackResistance
        assertEquals(0.0, track.gradePercent, 1e-9)
        assertEquals(0.0033, track.crr, 1e-9)
    }

    @Test
    fun `22-9 user configuration fixture decodes weight`() {
        val command = FeCBleControlDecoder.decode(bytes("A4 09 4F 05 37 4C 1D FF 86 0C D1 53 76"))
        val config = command as FeCBleControlCommand.UserConfiguration
        assertEquals(75.00, config.userWeightKg!!, 1e-9)
    }

    @Test
    fun `checksum-0xFF edge case never silently passes a corrupted frame`() {
        val valid = bytes("A4 09 4E 05 10 19 04 00 00 00 FF 24 30")
        val corrupted = valid.copyOf()
        corrupted[12] = 0xFF.toByte()
        val result = FeCBleControlDecoder.decode(corrupted)
        assertTrue(result is FeCBleControlCommand.Rejected)
        assertEquals(RejectReason.BAD_CHECKSUM, (result as FeCBleControlCommand.Rejected).reason)
    }

    // ── §23 rollover ────────────────────────────────────────────────────────

    @Test
    fun `23-1 page 16 elapsed rolls over at 256 quarter-seconds`() {
        val state = FeCBleMachineState(FeCEquipmentType.TRAINER)
        state.elapsedQuarterSeconds = 255
        assertEquals(0xFF, FeCBleTelemetryEncoder.encodePage16(state)[2].toInt() and 0xFF)
        state.elapsedQuarterSeconds = 256
        assertEquals(0x00, FeCBleTelemetryEncoder.encodePage16(state)[2].toInt() and 0xFF)
        state.elapsedQuarterSeconds = 257
        assertEquals(0x01, FeCBleTelemetryEncoder.encodePage16(state)[2].toInt() and 0xFF)
    }

    @Test
    fun `23-2 page 16 distance rolls over at 256 meters`() {
        val state = FeCBleMachineState(FeCEquipmentType.TRAINER)
        state.distanceMeters = 255.0
        assertEquals(0xFF, FeCBleTelemetryEncoder.encodePage16(state)[3].toInt() and 0xFF)
        state.distanceMeters = 256.0
        assertEquals(0x00, FeCBleTelemetryEncoder.encodePage16(state)[3].toInt() and 0xFF)
        state.distanceMeters = 257.0
        assertEquals(0x01, FeCBleTelemetryEncoder.encodePage16(state)[3].toInt() and 0xFF)
    }

    @Test
    fun `23-3 page 25 event count rolls over modulo 256`() {
        val state = FeCBleMachineState(FeCEquipmentType.TRAINER)
        state.eventCount = 255
        assertEquals(0xFF, FeCBleTelemetryEncoder.encodePage25(state)[1].toInt() and 0xFF)
        state.eventCount = 256
        assertEquals(0x00, FeCBleTelemetryEncoder.encodePage25(state)[1].toInt() and 0xFF)
    }

    @Test
    fun `23-4 accumulated power rolls over at 65536`() {
        val state = FeCBleMachineState(FeCEquipmentType.TRAINER)
        state.accumulatedPower = 65535
        val before = FeCBleTelemetryEncoder.encodePage25(state)
        assertEquals(0xFF, before[3].toInt() and 0xFF)
        assertEquals(0xFF, before[4].toInt() and 0xFF)
        state.accumulatedPower = 65536
        val after = FeCBleTelemetryEncoder.encodePage25(state)
        assertEquals(0x00, after[3].toInt() and 0xFF)
        assertEquals(0x00, after[4].toInt() and 0xFF)
    }

    @Test
    fun `23-5 thirty-minute run never overflows and checksum stays valid`() {
        val state = FeCBleMachineState(FeCEquipmentType.TRAINER)
        state.enterInUseWithDefaults()
        val scheduler = FeCBleTelemetryScheduler(FeCEquipmentType.TRAINER)
        var lastDistance = state.distanceMeters
        repeat(30 * 60 * 4) { // 30 minutes at 4 Hz
            val frame = scheduler.tick(state)
            assertEquals(13, frame.size)
            assertEquals(frame[12], FeCBleTelemetryEncoder.xorChecksum(frame, 0, 12).toByte())
            assertTrue(state.distanceMeters >= lastDistance)
            lastDistance = state.distanceMeters
        }
    }

    // ── §24 GATT/scheduler shape ────────────────────────────────────────────

    @Test
    fun `all personas meet required rotation rates and use their own specific page`() {
        val specificPages = mapOf(
            FeCEquipmentType.TREADMILL to 0x13,
            FeCEquipmentType.ELLIPTICAL to 0x14,
            FeCEquipmentType.ROWER to 0x16,
            FeCEquipmentType.CLIMBER to 0x17,
            FeCEquipmentType.NORDIC_SKIER to 0x18,
        )
        specificPages.forEach { (type, specificPage) ->
            val state = FeCBleMachineState(type).apply { enterInUseWithDefaults() }
            val scheduler = FeCBleTelemetryScheduler(type)
            val pages = (0 until FeCBleTelemetryScheduler.ROTATION_TICKS)
                .map { scheduler.tick(state)[4].toInt() and 0xFF }
            assertEquals("$type page 16 count", 20, pages.count { it == 0x10 })
            assertEquals("$type specific count", 15, pages.count { it == specificPage })
            assertEquals("$type page 17 count", 2, pages.count { it == 0x11 })
            assertEquals("$type page 18 count", 2, pages.count { it == 0x12 })
            assertEquals("$type page 54 is on-demand only", 0, pages.count { it == 0x36 })
            assertEquals("$type page 81 count", 1, pages.count { it == 0x51 })
        }

        listOf(FeCEquipmentType.TRAINER, FeCEquipmentType.STATIONARY_BIKE).forEach { type ->
            val state = FeCBleMachineState(type).apply { enterInUseWithDefaults() }
            val scheduler = FeCBleTelemetryScheduler(type)
            val frames = (0 until FeCBleTelemetryScheduler.ROTATION_TICKS)
                .map { scheduler.tick(state) }
            val page25 = frames.filter { (it[4].toInt() and 0xFF) == 0x19 }
            assertEquals("$type page 25 count", 8, page25.size)
            assertEquals("$type page 26 count", 7, frames.count { (it[4].toInt() and 0xFF) == 0x1A })
            page25.forEach { frame ->
                val directPower = (frame[9].toInt() and 0xFF) or ((frame[10].toInt() and 0x0F) shl 8)
                assertTrue("$type direct power must not be the 4095 W unavailable sentinel", directPower != 0xFFF)
                assertTrue("$type direct power follows its synthetic workout range", directPower in 90..420)
            }
            assertEquals("$type page-25 events", 8, state.eventCount)
        }
    }

    @Test
    fun `trainer and bike page 26 deltas reproduce torque-derived power and roll over`() {
        listOf(FeCEquipmentType.TRAINER, FeCEquipmentType.STATIONARY_BIKE).forEach { type ->
            val state = FeCBleMachineState(type).apply { enterInUseWithDefaults() }
            repeat(2) { FeCBleSyntheticModel.advanceTick(state) }
            val first = FeCBleTelemetryEncoder.encodePage26(state)
            val contributingPowers = buildList {
                repeat(2) {
                    FeCBleSyntheticModel.advanceTick(state)
                    add(state.instantaneousPowerWatts!!)
                }
            }
            val second = FeCBleTelemetryEncoder.encodePage26(state)
            val periodDelta = (u16(second, 3) - u16(first, 3)) and 0xFFFF
            val torqueDelta = (u16(second, 5) - u16(first, 5)) and 0xFFFF
            val derivedPower = 128.0 * Math.PI * torqueDelta / periodDelta
            assertEquals("$type torque power", contributingPowers.average(), derivedPower, 1.0)

            state.torqueEventCount = 256
            state.wheelTicks = 257
            state.wheelPeriodTicks = 65_536
            state.accumulatedTorqueThirtySecondNm = 65_538.0
            val rolled = FeCBleTelemetryEncoder.encodePage26(state)
            assertEquals(0, rolled[1].toInt() and 0xFF)
            assertEquals(1, rolled[2].toInt() and 0xFF)
            assertEquals(0, u16(rolled, 3))
            assertEquals(2, u16(rolled, 5))
        }
    }

    // ── §25 control behaviour ───────────────────────────────────────────────

    @Test
    fun `25-1 mode switching follows the last accepted control page`() {
        val state = FeCBleMachineState(FeCEquipmentType.TRAINER)
        FeCBleSyntheticModel.applyControlCommand(state, FeCBleControlCommand.BasicResistance(50.0), null)
        assertEquals(FeCBleControlMode.BASIC_RESISTANCE, state.controlMode)
        FeCBleSyntheticModel.applyControlCommand(state, FeCBleControlCommand.TargetPower(300.0), null)
        assertEquals(FeCBleControlMode.ERG, state.controlMode)
        FeCBleSyntheticModel.applyControlCommand(state, FeCBleControlCommand.TrackResistance(3.0, 0.004), null)
        assertEquals(FeCBleControlMode.SIMULATION, state.controlMode)
        FeCBleSyntheticModel.applyControlCommand(state, FeCBleControlCommand.TargetPower(200.0), null)
        assertEquals(FeCBleControlMode.ERG, state.controlMode)
    }

    @Test
    fun `25-2 ERG power converges toward the requested target at 25W per tick`() {
        val state = FeCBleMachineState(FeCEquipmentType.TRAINER)
        state.feState = FeCEquipmentState.IN_USE
        state.instantaneousPowerWatts = 150
        FeCBleSyntheticModel.applyControlCommand(state, FeCBleControlCommand.TargetPower(300.0), null)
        val observed = mutableListOf<Int>()
        repeat(10) {
            FeCBleSyntheticModel.advanceTick(state)
            observed += state.instantaneousPowerWatts!!
        }
        assertEquals(listOf(175, 200, 225, 250, 275, 300, 300, 300, 300, 300), observed)
    }

    @Test
    fun `25-3 grade direction is monotonic`() {
        fun targetAfterGrade(grade: Double): Int {
            val state = FeCBleMachineState(FeCEquipmentType.TRAINER)
            state.feState = FeCEquipmentState.IN_USE
            state.instantaneousPowerWatts = 0
            FeCBleSyntheticModel.applyControlCommand(state, FeCBleControlCommand.TrackResistance(grade, 0.0033), null)
            repeat(50) { FeCBleSyntheticModel.advanceTick(state) }
            return state.instantaneousPowerWatts!!
        }
        val minus3 = targetAfterGrade(-3.0)
        val zero = targetAfterGrade(0.0)
        val plus3 = targetAfterGrade(3.0)
        val plus8 = targetAfterGrade(8.0)
        assertTrue(minus3 < zero)
        assertTrue(zero < plus3)
        assertTrue(plus3 < plus8)
    }

    @Test
    fun `simulation physics consumes FE-C rider mass wind and drafting`() {
        fun settledPower(riderMassKg: Double, windKph: Int, drafting: Double): Int {
            val state = FeCBleMachineState(FeCEquipmentType.TRAINER).apply { enterInUseWithDefaults() }
            FeCBleSyntheticModel.applyControlCommand(
                state,
                FeCBleControlCommand.UserConfiguration(riderMassKg, byteArrayOf()),
                null,
            )
            FeCBleSyntheticModel.applyControlCommand(
                state,
                FeCBleControlCommand.TrackResistance(5.0, 0.004),
                null,
            )
            FeCBleSyntheticModel.applyControlCommand(
                state,
                FeCBleControlCommand.WindResistance(0.51, windKph, drafting),
                null,
            )
            FeCBleSyntheticModel.advanceTick(state)
            return state.instantaneousPowerWatts!!
        }

        val baseline = settledPower(75.0, 0, 1.0)
        assertTrue(settledPower(95.0, 0, 1.0) > baseline)
        assertTrue(settledPower(75.0, 18, 1.0) > baseline)
        assertTrue(settledPower(75.0, 0, 0.5) < baseline)
    }

    @Test
    fun `decoded FE-C pages drive one physics power into page 25 page 26 and CPS`() {
        val state = FeCBleMachineState(FeCEquipmentType.TRAINER).apply { enterInUseWithDefaults() }
        listOf(
            "A4 09 4F 05 37 4C 1D FF 86 0C D1 53 76",
            "A4 09 4F 05 32 FF FF FF FF 33 7F 64 FD",
            "A4 09 4F 05 33 FF FF FF FF 20 4E 42 F8",
        ).forEach { wire ->
            FeCBleSyntheticModel.applyControlCommand(
                state,
                FeCBleControlDecoder.decode(bytes(wire)),
                null,
            )
        }

        FeCBleSyntheticModel.advanceTick(state)
        val expected = CyclingSimulationPhysics.requiredRiderPowerWatts(
            speedKph = state.speedMps * 3.6,
            gradePercent = 0.0,
            crr = 0.0033,
            cwKgPerM = 0.51,
            windMps = 0.0,
            totalMassKg = 85.0,
            draftingFactor = 1.0,
        ).roundToInt()
        assertEquals(expected, state.instantaneousPowerWatts)
        assertEquals(expected, page25PowerWatts(FeCBleTelemetryEncoder.encodePage25(state)))
        assertEquals(
            expected,
            cpsPowerWatts(
                CyclingSensorEncoders.cyclingPowerMeasurement(
                    state.instantaneousPowerWatts!!,
                    CyclingRevolutionSnapshot(),
                ),
            ),
        )

        val first = FeCBleTelemetryEncoder.encodePage26(state)
        val contributing = buildList {
            repeat(2) {
                FeCBleSyntheticModel.advanceTick(state)
                add(state.instantaneousPowerWatts!!)
            }
        }
        val second = FeCBleTelemetryEncoder.encodePage26(state)
        val periodDelta = (u16(second, 3) - u16(first, 3)) and 0xFFFF
        val torqueDelta = (u16(second, 5) - u16(first, 5)) and 0xFFFF
        val torquePower = 128.0 * Math.PI * torqueDelta / periodDelta
        assertEquals(contributing.average(), torquePower, 1.0)
    }

    @Test
    fun `FE-C simulation clamps at 12-bit power and ERG resumes afterward`() {
        val state = FeCBleMachineState(FeCEquipmentType.TRAINER).apply { enterInUseWithDefaults() }
        FeCBleSyntheticModel.applyControlCommand(
            state,
            FeCBleControlCommand.TrackResistance(200.0, 0.0255),
            null,
        )
        FeCBleSyntheticModel.applyControlCommand(
            state,
            FeCBleControlCommand.WindResistance(2.55, 127, 1.0),
            null,
        )
        FeCBleSyntheticModel.advanceTick(state)
        assertEquals(0xFFE, state.instantaneousPowerWatts)

        FeCBleSyntheticModel.applyControlCommand(state, FeCBleControlCommand.TargetPower(300.0), null)
        repeat(200) { FeCBleSyntheticModel.advanceTick(state) }
        assertEquals(FeCBleControlMode.ERG, state.controlMode)
        assertEquals(300, state.instantaneousPowerWatts)
    }

    @Test
    fun `25-4 reset clears control state and stale targets`() {
        val state = FeCBleMachineState(FeCEquipmentType.TRAINER)
        FeCBleSyntheticModel.applyControlCommand(state, FeCBleControlCommand.TargetPower(400.0), null)
        state.reset()
        assertEquals(FeCBleControlMode.FREE, state.controlMode)
        assertNull(state.targetPowerWatts)
        assertEquals(FeCEquipmentState.READY, state.feState)
    }

    @Test
    fun `persona interval metrics vary repeat and remain inside their synthetic bounds`() {
        FeCEquipmentType.entries.forEach { type ->
            val state = FeCBleMachineState(type).apply { enterInUseWithDefaults() }
            val speeds = mutableSetOf<Double>()
            val cadences = mutableSetOf<Int>()
            val powers = mutableSetOf<Int>()
            val mets = mutableSetOf<Double>()
            val totalTicks = FeCBleSyntheticModel.intervalCycleSeconds * 4

            repeat(totalTicks) {
                FeCBleSyntheticModel.advanceTick(state)
                val range = type.workoutRange
                val speedKph = state.speedMps * 3.6
                assertTrue("$type speed $speedKph", speedKph in range.speedKph)
                assertTrue("$type cadence ${state.cadenceRpm}", state.cadenceRpm!! in range.cadencePerMinute)
                assertTrue("$type HR ${state.heartRateBpm}", state.heartRateBpm!! in range.heartRateBpm)
                assertTrue("$type MET ${state.metabolicEquivalent}", state.metabolicEquivalent in range.metabolicEquivalent)
                range.powerWatts?.let { assertTrue("$type power", state.instantaneousPowerWatts!! in it) }
                    ?: assertNull("$type must not synthesize power", state.instantaneousPowerWatts)
                range.inclinePercent?.let { assertTrue("$type incline", state.inclinePercent in it) }
                range.resistancePercent?.let { assertTrue("$type resistance", state.resistanceLevelPercent in it) }
                speeds += speedKph
                cadences += state.cadenceRpm!!
                state.instantaneousPowerWatts?.let(powers::add)
                mets += state.metabolicEquivalent
            }

            assertTrue("$type speed varies", speeds.size > 50)
            assertTrue("$type cadence varies", cadences.size > 5)
            assertTrue("$type METs vary", mets.size > 50)
            if (type.workoutRange.powerWatts != null) assertTrue("$type power varies", powers.size > 5)
            assertTrue("$type distance accumulates", state.distanceMeters > 0.0)
            assertTrue("$type calories accumulate", state.accumulatedCaloriesKcal > 0.0)
            if (type in setOf(
                    FeCEquipmentType.ELLIPTICAL,
                    FeCEquipmentType.ROWER,
                    FeCEquipmentType.CLIMBER,
                    FeCEquipmentType.NORDIC_SKIER,
                )
            ) {
                assertTrue("$type movements accumulate", state.movementCount > 0.0)
            }
        }
    }

    @Test
    fun `interval profile repeats exactly after two minutes`() {
        val state = FeCBleMachineState(FeCEquipmentType.ROWER).apply { enterInUseWithDefaults() }
        FeCBleSyntheticModel.advanceTick(state)
        val first = listOf(
            state.intervalName,
            state.intervalIntensityPercent,
            state.speedMps,
            state.cadenceRpm,
            state.instantaneousPowerWatts,
            state.metabolicEquivalent,
        )
        repeat(FeCBleSyntheticModel.intervalCycleSeconds * 4) {
            FeCBleSyntheticModel.advanceTick(state)
        }
        assertEquals(first, listOf(
            state.intervalName,
            state.intervalIntensityPercent,
            state.speedMps,
            state.cadenceRpm,
            state.instantaneousPowerWatts,
            state.metabolicEquivalent,
        ))
    }

    @Test
    fun `trainer controls retain precedence over repeating workout load fields`() {
        val basic = FeCBleMachineState(FeCEquipmentType.TRAINER).apply { enterInUseWithDefaults() }
        FeCBleSyntheticModel.applyControlCommand(basic, FeCBleControlCommand.BasicResistance(57.5), null)
        repeat(FeCBleSyntheticModel.intervalCycleSeconds * 4) { FeCBleSyntheticModel.advanceTick(basic) }
        assertEquals(57.5, basic.resistanceLevelPercent, 0.0)

        val simulation = FeCBleMachineState(FeCEquipmentType.TRAINER).apply { enterInUseWithDefaults() }
        FeCBleSyntheticModel.applyControlCommand(
            simulation,
            FeCBleControlCommand.TrackResistance(6.25, 0.004),
            null,
        )
        repeat(100) { FeCBleSyntheticModel.advanceTick(simulation) }
        assertEquals(6.25, simulation.inclinePercent, 0.0)
        assertEquals(
            CyclingSimulationPhysics.requiredRiderPowerWatts(
                speedKph = simulation.speedMps * 3.6,
                gradePercent = 6.25,
                crr = 0.004,
                cwKgPerM = CyclingSimulationPhysics.DEFAULT_CW_KG_PER_M,
                windMps = 0.0,
            ).roundToInt(),
            simulation.instantaneousPowerWatts,
        )
    }

    @Test
    fun `malformed FEC3 writes never mutate machine state`() {
        val state = FeCBleMachineState(FeCEquipmentType.TRAINER)
        FeCBleSyntheticModel.applyControlCommand(state, FeCBleControlCommand.TargetPower(300.0), null)
        val before = state.targetPowerWatts
        val malformed = FeCBleControlDecoder.decode(bytes("A4 09 4F 05 31 FF FF FF FF FF E8 03 00"))
        assertTrue(malformed is FeCBleControlCommand.Rejected)
        assertEquals(before, state.targetPowerWatts)
    }

    @Test
    fun `equipment persona defaults match the plan's control table`() {
        assertTrue(FeCEquipmentType.TRAINER.supportsControl)
        assertTrue(!FeCEquipmentType.STATIONARY_BIKE.supportsControl)
        assertTrue(!FeCEquipmentType.TREADMILL.supportsControl)
        assertTrue(!FeCEquipmentType.ELLIPTICAL.supportsControl)
        assertTrue(!FeCEquipmentType.ROWER.supportsControl)
        assertTrue(!FeCEquipmentType.CLIMBER.supportsControl)
        assertTrue(!FeCEquipmentType.NORDIC_SKIER.supportsControl)
        // Per the official ANT+ FE-C spec (D000001231 Rev 5.0, Table 8-7), Trainer and
        // Stationary Bike share equipment-type wire value 25 - 21 is "Reserved - Do Not Use" for
        // this field, a distinct concept from data page 21 (deprecated Stationary Bike specific
        // data, §8.6.3). This was a real bug fixed alongside spec-accurate pages 19/22: the
        // simulator's Stationary Bike persona previously broadcast a reserved/invalid equipment
        // type on every page 16 frame.
        assertEquals(25, FeCEquipmentType.TRAINER.wireValue)
        assertEquals(25, FeCEquipmentType.STATIONARY_BIKE.wireValue)
        assertEquals(19, FeCEquipmentType.TREADMILL.wireValue)
        assertEquals(20, FeCEquipmentType.ELLIPTICAL.wireValue)
        assertEquals(22, FeCEquipmentType.ROWER.wireValue)
        assertEquals(23, FeCEquipmentType.CLIMBER.wireValue)
        assertEquals(24, FeCEquipmentType.NORDIC_SKIER.wireValue)
    }

    // ── §18.2 Incyclist CPS overlay coherence ──────────────────────────────

    /** Decode page 25's 12-bit instantaneous power field back out, mirroring plan §10.2. */
    private fun page25PowerWatts(page25: ByteArray): Int =
        (page25[5].toInt() and 0xFF) or ((page25[6].toInt() and 0x0F) shl 8)

    /** Decode the Cycling Power Measurement's signed 16-bit LE instantaneous power field. */
    private fun cpsPowerWatts(measurement: ByteArray): Int {
        val low = measurement[2].toInt() and 0xFF
        val high = measurement[3].toInt()
        return (high shl 8) or low
    }

    @Test
    fun `18-2 Stationary Bike CPS power equals the same tick's page-25 power`() {
        listOf(0, 1, 200, 987, 0xFFE).forEach { watts ->
            val state = FeCBleMachineState(FeCEquipmentType.STATIONARY_BIKE)
            state.feState = FeCEquipmentState.IN_USE
            state.cadenceRpm = 90
            state.instantaneousPowerWatts = watts

            val page25 = FeCBleTelemetryEncoder.encodePage25(state)
            val cpsMeasurement = CyclingSensorEncoders.cyclingPowerMeasurement(
                state.instantaneousPowerWatts ?: 0,
                CyclingRevolutionSnapshot(),
            )

            assertEquals(
                "CPS power must equal page-25 power for watts=$watts (plan §18.2)",
                page25PowerWatts(page25),
                cpsPowerWatts(cpsMeasurement),
            )
        }
    }
}
