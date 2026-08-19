package com.gmail.absolutebollockscreations.Fitbridge.bluetooth.simulator

import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.peripheral.CyclingRevolutionSnapshot
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.peripheral.CyclingSensorEncoders
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

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
    fun `22-3 representative Trainer page 25 vector`() {
        val state = FeCBleMachineState(FeCEquipmentType.TRAINER)
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
    fun `Trainer scheduler alternates page 16 and page 25 with page 81 every 32nd tick`() {
        val state = FeCBleMachineState(FeCEquipmentType.TRAINER)
        state.enterInUseWithDefaults()
        val scheduler = FeCBleTelemetryScheduler(FeCEquipmentType.TRAINER)
        val pages = (0 until 32).map { scheduler.tick(state)[4].toInt() and 0xFF }
        assertEquals(0x10, pages[0])
        assertEquals(0x19, pages[1])
        assertEquals(0x10, pages[2])
        assertEquals(0x51, pages[31])
    }

    @Test
    fun `Treadmill scheduler emits page 16 every tick except the 32nd`() {
        val state = FeCBleMachineState(FeCEquipmentType.TREADMILL)
        val scheduler = FeCBleTelemetryScheduler(FeCEquipmentType.TREADMILL)
        val pages = (0 until 32).map { scheduler.tick(state)[4].toInt() and 0xFF }
        assertTrue(pages.take(31).all { it == 0x10 })
        assertEquals(0x51, pages[31])
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
    fun `25-4 reset clears control state and stale targets`() {
        val state = FeCBleMachineState(FeCEquipmentType.TRAINER)
        FeCBleSyntheticModel.applyControlCommand(state, FeCBleControlCommand.TargetPower(400.0), null)
        state.reset()
        assertEquals(FeCBleControlMode.FREE, state.controlMode)
        assertNull(state.targetPowerWatts)
        assertEquals(FeCEquipmentState.READY, state.feState)
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
        assertTrue(!FeCEquipmentType.ROWER.supportsControl)
        assertEquals(25, FeCEquipmentType.TRAINER.wireValue)
        assertEquals(21, FeCEquipmentType.STATIONARY_BIKE.wireValue)
        assertEquals(19, FeCEquipmentType.TREADMILL.wireValue)
        assertEquals(22, FeCEquipmentType.ROWER.wireValue)
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
    fun `18-2 CPS instantaneous power equals the same tick's page-25 instantaneous power`() {
        listOf(0, 1, 200, 987, 0xFFE).forEach { watts ->
            val state = FeCBleMachineState(FeCEquipmentType.TRAINER)
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
