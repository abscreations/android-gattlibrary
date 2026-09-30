package com.gmail.absolutebollockscreations.gattlibrary.simulator

import com.gmail.absolutebollockscreations.gattlibrary.central.FeCControlEncoder
import com.gmail.absolutebollockscreations.gattlibrary.central.BluetoothIds
import com.gmail.absolutebollockscreations.gattlibrary.central.FeCEquipmentState as EngineFeState
import com.gmail.absolutebollockscreations.gattlibrary.central.FeCFrameCodec
import com.gmail.absolutebollockscreations.gattlibrary.central.FeCFrameDecodeResult
import com.gmail.absolutebollockscreations.gattlibrary.central.FeCPage
import com.gmail.absolutebollockscreations.gattlibrary.central.FeCPageParsers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.gmail.absolutebollockscreations.gattlibrary.core.ids.FecBleUuids

/**
 * Unit 9.10: cross-checks the FE-C over BLE peripheral simulator (`bluetooth.simulator`) against
 * the central this phase built (`bluetooth.engine`), per plan §16.10/§18's Phase-9 exit criteria.
 *
 * Both sides were deliberately implemented independently against the same external vectors
 * rather than one wrapping the other (see [FeCBleTelemetryEncoder]'s own KDoc: "the simulator and
 * the future central `FeCFrameCodec`/`FeCPageParsers` must be independently pinned against the
 * same external vectors so a single offset bug cannot pass both sides at once"). That
 * independence is only worth something if something actually feeds one side's output into the
 * other's input and checks they agree — which neither side's own unit tests do, since each only
 * exercises its own encode/decode pair. This file is that check, run entirely in-process without
 * BLE (no hardware, no `BluetoothGatt` — plan §21 FEC-Q1/Q4/Q9 remain about a physical trainer,
 * not this).
 *
 * Telemetry runs peripheral-encode → central-decode (the direction real data flows: FEC2
 * notifications from a real or simulated trainer). Control runs central-encode →
 * peripheral-decode (the direction real writes flow: FEC3 from FitBridge to a trainer).
 */
class FeCBleCentralInteropTest {

    private fun freshState(type: FeCEquipmentType = FeCEquipmentType.TRAINER) = FeCBleMachineState(type)

    private fun decodeSimulatorFrame(frame: ByteArray): FeCPage {
        val decoded = FeCFrameCodec.decode(frame) as FeCFrameDecodeResult.Decoded
        return FeCPageParsers.decodePage(decoded.frame.data)
    }

    // ── Telemetry: peripheral encodes, central decodes (page 16 / 25 / 81) ─────────────────

    @Test
    fun `page 16 from the simulator decodes correctly through the central's independent parser`() {
        val state = freshState().apply {
            feState = FeCEquipmentState.IN_USE
            elapsedQuarterSeconds = 37
            distanceMeters = 123.0
            distanceCapable = true
            speedMps = 5.25
            heartRateBpm = 142
            heartRateSource = FeCHeartRateSource.ANT
        }

        val frame = FeCBleTelemetryEncoder.encodePage16Frame(state)
        val decoded = FeCFrameCodec.decode(frame)
        assertTrue("frame must decode cleanly: $decoded", decoded is FeCFrameDecodeResult.Decoded)
        val page = FeCPageParsers.decodePage((decoded as FeCFrameDecodeResult.Decoded).frame.data)
        assertTrue("expected GeneralFe, got $page", page is FeCPage.GeneralFe)
        page as FeCPage.GeneralFe

        assertEquals(state.equipmentType.wireValue, page.equipmentType)
        assertEquals(37, page.elapsedTimeRaw)
        assertEquals(123, page.distanceTraveledRaw)
        assertTrue(page.distanceTraveledEnabled)
        assertEquals(5.25, page.speedMetersPerSecond, 0.001)
        assertEquals(142, page.heartRateBpm)
        assertEquals(EngineFeState.IN_USE, page.equipmentState)
    }

    @Test
    fun `page 16 with distance disabled and no heart rate round-trips those absences honestly`() {
        val state = freshState().apply {
            distanceCapable = false
            heartRateBpm = null
        }

        val decoded = FeCFrameCodec.decode(FeCBleTelemetryEncoder.encodePage16Frame(state))
        val page = FeCPageParsers.decodePage((decoded as FeCFrameDecodeResult.Decoded).frame.data) as FeCPage.GeneralFe

        assertTrue(
            "distance-disabled must survive the round trip, not silently become enabled",
            !page.distanceTraveledEnabled,
        )
        assertNull(page.heartRateBpm)
    }

    @Test
    fun `page 25 from the simulator decodes correctly through the central's independent parser`() {
        val state = freshState(FeCEquipmentType.STATIONARY_BIKE).apply {
            feState = FeCEquipmentState.IN_USE
            eventCount = 44
            cadenceRpm = 88
            accumulatedPower = 5000
            instantaneousPowerWatts = 245
        }

        val decoded = FeCFrameCodec.decode(FeCBleTelemetryEncoder.encodePage25Frame(state))
        assertTrue(decoded is FeCFrameDecodeResult.Decoded)
        val page = FeCPageParsers.decodePage((decoded as FeCFrameDecodeResult.Decoded).frame.data)
        assertTrue("expected TrainerData, got $page", page is FeCPage.TrainerData)
        page as FeCPage.TrainerData

        assertEquals(44, page.eventCountRaw)
        assertEquals(88, page.cadenceRpm)
        assertEquals(5000, page.accumulatedPowerRaw)
        assertEquals(245, page.instantaneousPowerWatts)
        assertEquals(EngineFeState.IN_USE, page.equipmentState)
    }

    @Test
    fun `page 25 with cadence and power unavailable round-trips those sentinels as null`() {
        val state = freshState(FeCEquipmentType.STATIONARY_BIKE).apply {
            cadenceRpm = null
            instantaneousPowerWatts = null
        }

        val decoded = FeCFrameCodec.decode(FeCBleTelemetryEncoder.encodePage25Frame(state))
        val page = FeCPageParsers.decodePage((decoded as FeCFrameDecodeResult.Decoded).frame.data) as FeCPage.TrainerData

        assertNull(page.cadenceRpm)
        assertNull(page.instantaneousPowerWatts)
    }

    @Test
    fun `page 81 from the simulator decodes correctly through the central's independent parser`() {
        val state = freshState(FeCEquipmentType.STATIONARY_BIKE)

        val decoded = FeCFrameCodec.decode(FeCBleTelemetryEncoder.encodePage81Frame(state))
        assertTrue(decoded is FeCFrameDecodeResult.Decoded)
        val page = FeCPageParsers.decodePage((decoded as FeCFrameDecodeResult.Decoded).frame.data)
        assertTrue("expected ProductInformation, got $page", page is FeCPage.ProductInformation)
        page as FeCPage.ProductInformation

        assertEquals(state.softwareRevisionSupplemental, page.supplementalSoftwareRevision)
        assertEquals(state.softwareRevisionMain, page.mainSoftwareRevision)
        assertNotNull(page.serialNumber)
        assertEquals(state.serialNumber, page.serialNumber)
    }

    @Test
    fun `simulator general settings metabolic and capabilities pages decode centrally`() {
        val state = freshState(FeCEquipmentType.ELLIPTICAL).apply {
            feState = FeCEquipmentState.IN_USE
            inclinePercent = -2.75
            resistanceLevelPercent = 42.5
            metabolicEquivalent = 9.25
            caloricBurnRateKcalPerHour = 612.4
            accumulatedCaloriesKcal = 241.8
        }

        val settings = decodeSimulatorFrame(FeCBleTelemetryEncoder.encodePage17Frame(state)) as FeCPage.GeneralSettings
        assertEquals(state.cycleLengthMeters, settings.cycleLengthMeters!!, 0.01)
        assertEquals(-2.75, settings.inclinePercent!!, 0.01)
        assertEquals(42.5, settings.resistancePercent!!, 0.01)

        val metabolic = decodeSimulatorFrame(FeCBleTelemetryEncoder.encodePage18Frame(state)) as FeCPage.GeneralMetabolicData
        assertEquals(9.25, metabolic.metabolicEquivalent!!, 0.01)
        assertEquals(612.4, metabolic.caloricBurnRateKcalPerHour!!, 0.1)
        assertEquals(241, metabolic.accumulatedCaloriesRaw)

        val capabilities = decodeSimulatorFrame(FeCBleTelemetryEncoder.encodePage54Frame(state)) as FeCPage.FeCapabilities
        assertEquals(state.equipmentType.maximumResistanceNewtons, capabilities.maximumResistanceNewtons)
        assertTrue(!capabilities.supportsBasicResistance)
        assertTrue(!capabilities.supportsTargetPower)
        assertTrue(!capabilities.supportsSimulation)
    }

    @Test
    fun `every simulator equipment-specific persona page decodes centrally`() {
        val elliptical = freshState(FeCEquipmentType.ELLIPTICAL).apply {
            movementCount = 77.0
            cadenceRpm = 64
            instantaneousPowerWatts = 210
            positiveVerticalDistanceMeters = 12.3
        }
        val ellipticalPage = decodeSimulatorFrame(FeCBleTelemetryEncoder.encodePage20Frame(elliptical)) as FeCPage.SpecificEllipticalData
        assertEquals(77, ellipticalPage.strideCountRaw)
        assertEquals(64, ellipticalPage.cadenceRpm)
        assertEquals(210, ellipticalPage.instantaneousPowerWatts)

        val climber = freshState(FeCEquipmentType.CLIMBER).apply {
            movementCount = 88.0
            cadenceRpm = 41
            instantaneousPowerWatts = 260
        }
        val climberPage = decodeSimulatorFrame(FeCBleTelemetryEncoder.encodePage23Frame(climber)) as FeCPage.SpecificClimberData
        assertEquals(88, climberPage.cycleCountRaw)
        assertEquals(41, climberPage.cadenceRpm)
        assertEquals(260, climberPage.instantaneousPowerWatts)

        val skier = freshState(FeCEquipmentType.NORDIC_SKIER).apply {
            movementCount = 99.0
            cadenceRpm = 53
            instantaneousPowerWatts = 310
        }
        val skierPage = decodeSimulatorFrame(FeCBleTelemetryEncoder.encodePage24Frame(skier)) as FeCPage.SpecificNordicSkierData
        assertEquals(99, skierPage.strideCountRaw)
        assertEquals(53, skierPage.cadenceRpm)
        assertEquals(310, skierPage.instantaneousPowerWatts)
    }

    @Test
    fun `simulator trainer torque page decodes centrally without losing high bits`() {
        val state = freshState().apply {
            torqueEventCount = 0xFE
            wheelTicks = 0xFD
            wheelPeriodTicks = 0xF234
            accumulatedTorqueThirtySecondNm = 0xE178.toDouble()
        }
        val page = decodeSimulatorFrame(FeCBleTelemetryEncoder.encodePage26Frame(state)) as FeCPage.TrainerTorqueData
        assertEquals(0xFE, page.eventCountRaw)
        assertEquals(0xFD, page.wheelTicksRaw)
        assertEquals(0xF234, page.accumulatedWheelPeriodRaw)
        assertEquals(0xE178, page.accumulatedTorqueRaw)
    }

    // ── Control: central encodes, peripheral decodes (pages 48 / 49 / 50 / 51) ─────────────

    @Test
    fun `central's page 48 write decodes correctly through the simulator's independent decoder`() {
        val wire = FeCControlEncoder.encodeBasicResistance(37.5)
        val command = FeCBleControlDecoder.decode(wire)
        assertTrue("expected BasicResistance, got $command", command is FeCBleControlCommand.BasicResistance)
        assertEquals(37.5, (command as FeCBleControlCommand.BasicResistance).resistancePercent, 0.01)
    }

    @Test
    fun `central page-54 request decodes through the simulator control parser`() {
        val command = FeCBleControlDecoder.decode(
            FeCControlEncoder.encodeRequestDataPage(FecBleUuids.FEC_PAGE_FE_CAPABILITIES),
        ) as FeCBleControlCommand.RequestDataPage
        assertEquals(FecBleUuids.FEC_PAGE_FE_CAPABILITIES, command.requestedPage)
        assertEquals(2, command.transmissionCount)
    }

    @Test
    fun `central's page 49 write decodes correctly through the simulator's independent decoder`() {
        val wire = FeCControlEncoder.encodeTargetPower(123.25)
        val command = FeCBleControlDecoder.decode(wire)
        assertTrue("expected TargetPower, got $command", command is FeCBleControlCommand.TargetPower)
        assertEquals(123.25, (command as FeCBleControlCommand.TargetPower).watts, 0.01)
    }

    @Test
    fun `central's page 50 write decodes correctly through the simulator's independent decoder`() {
        val wire = FeCControlEncoder.encodeWindResistance(
            windResistanceCoefficient = 0.30,
            windSpeedKmh = 10.0,
            draftingFactor = 0.20,
        )
        val command = FeCBleControlDecoder.decode(wire)
        assertTrue("expected WindResistance, got $command", command is FeCBleControlCommand.WindResistance)
        command as FeCBleControlCommand.WindResistance
        assertEquals(0.30, command.windCoefficient!!, 0.01)
        assertEquals(10, command.windSpeedEncodedUnit)
        assertEquals(0.20, command.draftingFactor!!, 0.01)
    }

    @Test
    fun `central's unavailable-sentinel page 50 write decodes every field back to null`() {
        val wire = FeCControlEncoder.encodeWindResistance()
        val command = FeCBleControlDecoder.decode(wire) as FeCBleControlCommand.WindResistance
        assertNull(command.windCoefficient)
        assertNull(command.windSpeedEncodedUnit)
        assertNull(command.draftingFactor)
    }

    @Test
    fun `central's page 51 write decodes correctly through the simulator's independent decoder`() {
        val wire = FeCControlEncoder.encodeTrackResistance(gradePercent = 2.5, rollingResistanceCoefficient = 0.004)
        val command = FeCBleControlDecoder.decode(wire)
        assertTrue("expected TrackResistance, got $command", command is FeCBleControlCommand.TrackResistance)
        command as FeCBleControlCommand.TrackResistance
        assertEquals(2.5, command.gradePercent, 0.01)
        assertEquals(0.004, command.crr, 0.0001)
    }

    @Test
    fun `the pinned setSlope-0 vector decodes back to zero grade and the Tacx PDF's rolling resistance`() {
        // Same pinned vector FeCControlEncoderTest asserts on the encode side - this checks the
        // simulator's independent decoder agrees with it too, not just the central's own encoder.
        val wire = FeCControlEncoder.encodeTrackResistance(gradePercent = 0.0, rollingResistanceCoefficient = 0.0033)
        val command = FeCBleControlDecoder.decode(wire) as FeCBleControlCommand.TrackResistance
        assertEquals(0.0, command.gradePercent, 0.01)
        assertEquals(0.0033, command.crr, 0.0001)
    }
}
