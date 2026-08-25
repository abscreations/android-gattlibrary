package com.gmail.absolutebollockscreations.Fitbridge.bluetooth.simulator

import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine.BluetoothIds
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine.BtEngineFtmsParsers
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine.BtEngineState
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine.ConnectionId
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine.CpsMeasurement
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine.MetricEmitter
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine.MetricFusion
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine.MetricKind
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine.MetricProtocol
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine.MetricSourceClass
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine.MetricSourceId
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.peripheral.FtmsSpec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KickrSyntheticModelTest {
    @Test
    fun `same controls and ticks produce identical deterministic snapshots`() {
        val first = KickrSyntheticModel()
        val second = KickrSyntheticModel()
        listOf(first, second).forEach {
            it.startOrResume()
            it.setTargetPower(250)
        }

        repeat(20) {
            assertEquals(first.advance(0.5), second.advance(0.5))
        }
    }

    @Test
    fun `CPS and FTMS frames are coherent capture-shaped views of one snapshot`() {
        val model = KickrSyntheticModel()
        model.startOrResume()
        model.setTargetPower(250)
        repeat(20) { model.advance(0.5) }
        val snapshot = model.snapshot()

        val cpsBytes = KickrStandardEncoders.cyclingPowerMeasurement(snapshot)
        val cps = requireNotNull(CpsMeasurement.parse(cpsBytes))
        assertEquals(16, cpsBytes.size)
        assertEquals(KickrStandardEncoders.CPS_FLAGS_CAPTURE_SHAPE, cps.flags)
        assertEquals(snapshot.powerWatts, cps.instantaneousPowerWatts)
        assertEquals(snapshot.cumulativeWheelRevolutions, cps.wheel?.cumulativeRevolutions)
        assertEquals(snapshot.cumulativeCrankRevolutions, cps.crank?.cumulativeRevolutions)

        val bikeBytes = KickrStandardEncoders.indoorBikeData(snapshot)
        assertEquals(8, bikeBytes.size)
        val parsed = parseBike(bikeBytes)
        assertEquals(snapshot.speedKph, parsed.state.speedKph.value!!, 0.01)
        assertEquals(snapshot.cadenceRpm, parsed.state.instantCadence.value!!, 0.5)
        assertEquals(snapshot.powerWatts, parsed.state.instantPower.value)
    }

    @Test
    fun `Core features and ranges retain captured bytes`() {
        assertArrayEquals(
            byteArrayOf(0x03, 0x40, 0x00, 0x00, 0x0C, 0x60, 0x00, 0x00),
            KickrStandardEncoders.CORE_FTMS_FEATURE,
        )
        assertArrayEquals(
            byteArrayOf(0x0A, 0x00, 0x96.toByte(), 0x00, 0x0A, 0x00),
            KickrStandardEncoders.SUPPORTED_RESISTANCE_RANGE,
        )
        assertEquals(0x600C, KickrFtmsCapabilities.VALUE.toTargetFeatureBits())
    }

    @Test
    fun `CORE 2 has no invented standard characteristic values`() {
        assertNull(
            KickrStandardEncoders.readValue(
                KickrSimulatorPersona.CORE2_FC82_PROVISIONAL,
                BluetoothIds.UUID_FITNESS_MACHINE_FEATURE,
            ),
        )
    }

    @Test
    fun `FTMS control owns session and applies power simulation resistance and circumference`() {
        val processor = KickrFtmsControlProcessor()

        assertResult(FtmsSpec.Result.CONTROL_NOT_PERMITTED, processor.handle(bytes(0x05, 200, 0)))
        assertResult(FtmsSpec.Result.SUCCESS, processor.handle(bytes(0x00)))
        assertResult(FtmsSpec.Result.SUCCESS, processor.handle(bytes(0x07)))
        assertResult(FtmsSpec.Result.SUCCESS, processor.handle(bytes(0x05, 250, 0)))
        assertEquals(250, processor.model.snapshot().targetPowerWatts)
        assertEquals(KickrControlMode.ERG, processor.model.snapshot().mode)

        assertResult(FtmsSpec.Result.SUCCESS, processor.handle(bytes(0x11, 0, 0, 0xF4, 0x01, 40, 51)))
        assertEquals(KickrControlMode.SIMULATION, processor.model.snapshot().mode)
        assertEquals(5.0, processor.model.snapshot().simulation.gradePercent, 0.0)

        assertResult(FtmsSpec.Result.SUCCESS, processor.handle(bytes(0x04, 100, 0)))
        assertEquals(KickrControlMode.RESISTANCE, processor.model.snapshot().mode)
        assertEquals(10.0, processor.model.snapshot().resistanceLevel, 0.0)

        assertResult(FtmsSpec.Result.SUCCESS, processor.handle(bytes(0x12, 0x52, 0x52)))
        assertEquals(2107.4, processor.model.snapshot().wheelCircumferenceMm, 0.0)
    }

    @Test
    fun `malformed and out of range controls do not mutate model`() {
        val processor = KickrFtmsControlProcessor()
        assertResult(FtmsSpec.Result.SUCCESS, processor.handle(bytes(0x00)))
        val before = processor.model.snapshot()

        assertResult(FtmsSpec.Result.INVALID_PARAMETER, processor.handle(bytes(0x12, 1)))
        assertResult(FtmsSpec.Result.INVALID_PARAMETER, processor.handle(bytes(0x12, 0, 0)))
        assertResult(FtmsSpec.Result.INVALID_PARAMETER, processor.handle(bytes(0x05, 0x01, 0x08)))
        assertResult(FtmsSpec.Result.INVALID_PARAMETER, processor.handle(bytes(0x04, 0xA0, 0x00)))
        assertEquals(before, processor.model.snapshot())
        assertEquals(before, processor.model.advance(Double.NaN))
        assertEquals(before, processor.model.advance(11.0))
    }

    @Test
    fun `disconnect clears procedure authority without resetting machine state`() {
        val processor = KickrFtmsControlProcessor()
        assertResult(FtmsSpec.Result.SUCCESS, processor.handle(bytes(0x00)))
        assertTrue(processor.session.hasControl)
        processor.disconnect()
        assertFalse(processor.session.hasControl)
        assertResult(FtmsSpec.Result.CONTROL_NOT_PERMITTED, processor.handle(bytes(0x07)))
    }

    private data class BikeHarness(val state: BtEngineState, val fusion: MetricFusion)

    private fun parseBike(value: ByteArray): BikeHarness {
        val state = BtEngineState()
        val fusion = MetricFusion(state)
        BtEngineFtmsParsers.doBike(
            MetricEmitter(
                fusion = fusion,
                sourceId = MetricSourceId(
                    ConnectionId("00:00:00:00:00:01", 1L),
                    MetricProtocol.FTMS,
                    BluetoothIds.UUID_BIKING_DATA,
                ),
                sourceClass = MetricSourceClass.FTMS,
                arrivalMs = 1L,
            ),
            value,
        )
        assertTrue(fusion.latestSample(MetricKind.SPEED) != null)
        return BikeHarness(state, fusion)
    }

    private fun assertResult(expected: Byte, outcome: com.gmail.absolutebollockscreations.Fitbridge.bluetooth.peripheral.FtmsControlPointHandler.Outcome) {
        assertEquals(expected, outcome.response[2])
    }

    private fun bytes(vararg values: Int): ByteArray = ByteArray(values.size) { values[it].toByte() }
}
