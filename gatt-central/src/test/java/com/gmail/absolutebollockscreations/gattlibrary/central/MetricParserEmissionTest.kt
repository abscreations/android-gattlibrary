package com.gmail.absolutebollockscreations.gattlibrary.central

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class MetricParserEmissionTest {
    private val connection = ConnectionId("AA:BB:CC:DD:EE:10", 10L)

    private data class Harness(
        val state: BtEngineState,
        val fusion: MetricFusion,
        val emitter: MetricEmitter,
    )

    private fun harness(
        characteristic: UUID,
        protocol: MetricProtocol = MetricProtocol.FTMS,
        sourceClass: MetricSourceClass = MetricSourceClass.FTMS,
        arrivalMs: Long = 12_345L,
    ): Harness {
        val state = BtEngineState()
        val fusion = MetricFusion(state)
        return Harness(
            state,
            fusion,
            MetricEmitter(
                fusion = fusion,
                sourceId = MetricSourceId(connection, protocol, characteristic),
                sourceClass = sourceClass,
                arrivalMs = arrivalMs,
            ),
        )
    }

    @Test
    fun `bike vector emits source tagged speed`() {
        val harness = harness(BluetoothIds.UUID_BIKING_DATA)

        BtEngineFtmsParsers.doBike(harness.emitter, byteArrayOf(0, 0, 0xD2.toByte(), 0x04))

        val sample = harness.fusion.latestSample(MetricKind.SPEED)!!
        assertEquals(12.34, sample.value, 0.0)
        assertEquals(BluetoothIds.UUID_BIKING_DATA, sample.sourceId.characteristicUuid)
        assertEquals(connection, sample.connectionId)
        assertEquals(12_345L, sample.arrivalMs)
        assertEquals(12.34, harness.state.speedKph.value!!, 0.0)
    }

    @Test
    fun `rower vector emits stroke rate and count`() {
        val harness = harness(BluetoothIds.UUID_ROWING_MACHINE_DATA)

        BtEngineFtmsParsers.doRower(
            harness.emitter,
            byteArrayOf(0, 0, 48, 0x41, 0x01),
        )

        assertEquals(24.0, harness.fusion.latestSample(MetricKind.STROKE_RATE)!!.value, 0.0)
        assertEquals(321.0, harness.fusion.latestSample(MetricKind.STROKE_COUNT)!!.value, 0.0)
        assertEquals(24.0, harness.state.strokeRate.value!!, 0.0)
        assertEquals(321, harness.state.strokeCount.value)
    }

    @Test
    fun `treadmill vector emits speed`() {
        val harness = harness(BluetoothIds.UUID_TREADMILL_DATA)

        BtEngineFtmsParsers.doTreadmill(harness.emitter, byteArrayOf(0, 0, 0x20, 0x03))

        assertEquals(8.0, harness.fusion.latestSample(MetricKind.SPEED)!!.value, 0.0)
        assertEquals(8.0, harness.state.speedKph.value!!, 0.0)
    }

    @Test
    fun `treadmill inclination is percent in tenths and ramp angle is degrees in tenths`() {
        val harness = harness(BluetoothIds.UUID_TREADMILL_DATA)

        // Flags 0x0008 = inclination + ramp angle only, no speed (bit 0 clear means speed present,
        // so bit 0 is set here to omit it). Inclination -5.0 % = -50, ramp 2.5 deg = 25.
        BtEngineFtmsParsers.doTreadmill(
            harness.emitter,
            byteArrayOf(0x09, 0x00, 0xCE.toByte(), 0xFF.toByte(), 0x19, 0x00),
        )

        assertEquals(-5.0, harness.fusion.latestSample(MetricKind.INCLINATION)!!.value, 0.0001)
        assertEquals(2.5, harness.fusion.latestSample(MetricKind.RAMP_ANGLE)!!.value, 0.0001)
        assertEquals(-5.0, harness.state.inclination.value!!, 0.0001)
    }

    @Test
    fun `treadmill inclination keeps its sign and full positive scale`() {
        val harness = harness(BluetoothIds.UUID_TREADMILL_DATA)

        // Inclination +10.0 % = 100, ramp angle 0.
        BtEngineFtmsParsers.doTreadmill(
            harness.emitter,
            byteArrayOf(0x09, 0x00, 0x64, 0x00, 0x00, 0x00),
        )

        assertEquals(10.0, harness.state.inclination.value!!, 0.0001)
    }

    @Test
    fun `elliptical vector emits speed after three byte flags`() {
        val harness = harness(BluetoothIds.UUID_ELLIPTICAL_DATA)

        BtEngineFtmsParsers.doElliptical(harness.emitter, byteArrayOf(0, 0, 0, 0x58, 0x02))

        assertEquals(6.0, harness.fusion.latestSample(MetricKind.SPEED)!!.value, 0.0)
        assertEquals(6.0, harness.state.speedKph.value!!, 0.0)
    }

    @Test
    fun `stepper vector emits floors and steps`() {
        val harness = harness(BluetoothIds.UUID_STAIR_STEPPER_DATA)

        BtEngineFtmsParsers.doStepper(harness.emitter, byteArrayOf(0, 0, 5, 0, 120, 0))

        assertEquals(5.0, harness.fusion.latestSample(MetricKind.TOTAL_FLOORS)!!.value, 0.0)
        assertEquals(120.0, harness.fusion.latestSample(MetricKind.TOTAL_STEPS)!!.value, 0.0)
        assertEquals(5, harness.state.totalFloors.value)
        assertEquals(120, harness.state.totalSteps.value)
    }

    @Test
    fun `climber vector emits floors`() {
        val harness = harness(BluetoothIds.UUID_STAIR_CLIMBER_DATA)

        BtEngineFtmsParsers.doClimber(harness.emitter, byteArrayOf(0, 0, 14, 0))

        assertEquals(14.0, harness.fusion.latestSample(MetricKind.TOTAL_FLOORS)!!.value, 0.0)
        assertEquals(14, harness.state.totalFloors.value)
    }

    @Test
    fun `truncated persona vectors are bounds checked`() {
        val bike = harness(BluetoothIds.UUID_BIKING_DATA)
        val rower = harness(BluetoothIds.UUID_ROWING_MACHINE_DATA)
        val treadmill = harness(BluetoothIds.UUID_TREADMILL_DATA)
        val elliptical = harness(BluetoothIds.UUID_ELLIPTICAL_DATA)
        val stepper = harness(BluetoothIds.UUID_STAIR_STEPPER_DATA)
        val climber = harness(BluetoothIds.UUID_STAIR_CLIMBER_DATA)

        BtEngineFtmsParsers.doBike(bike.emitter, byteArrayOf(0))
        BtEngineFtmsParsers.doRower(rower.emitter, byteArrayOf(0, 0, 48, 0x41))
        BtEngineFtmsParsers.doTreadmill(treadmill.emitter, byteArrayOf(0, 0, 0x20))
        BtEngineFtmsParsers.doElliptical(elliptical.emitter, byteArrayOf(0, 0))
        BtEngineFtmsParsers.doStepper(stepper.emitter, byteArrayOf(0, 0, 5, 0, 120))
        BtEngineFtmsParsers.doClimber(climber.emitter, byteArrayOf(0, 0, 14))

        assertNull(bike.fusion.latestSample(MetricKind.SPEED))
        assertEquals(24.0, rower.fusion.latestSample(MetricKind.STROKE_RATE)!!.value, 0.0)
        assertNull(rower.fusion.latestSample(MetricKind.STROKE_COUNT))
        assertNull(treadmill.fusion.latestSample(MetricKind.SPEED))
        assertNull(elliptical.fusion.latestSample(MetricKind.SPEED))
        assertEquals(5.0, stepper.fusion.latestSample(MetricKind.TOTAL_FLOORS)!!.value, 0.0)
        assertNull(stepper.fusion.latestSample(MetricKind.TOTAL_STEPS))
        assertNull(climber.fusion.latestSample(MetricKind.TOTAL_FLOORS))
    }

    @Test
    fun `HRM vector decodes optional fields and emits HRM provenance`() {
        val decoded = decodeHrmMeasurement(
            byteArrayOf(0x1E, 150.toByte(), 100, 0, 0, 4),
        )!!
        val harness = harness(
            characteristic = BluetoothIds.UUID_HEART_RATE_MEASUREMENT,
            protocol = MetricProtocol.HRM,
            sourceClass = MetricSourceClass.HRM,
        )

        emitHrmMetrics(decoded, harness.emitter)

        assertEquals(150, decoded.bpm)
        assertEquals(true, decoded.sensorContact)
        assertEquals(100, decoded.energyKiloJoules)
        assertEquals(listOf(1_000), decoded.rrIntervalsMs)
        assertFalse(decoded.optionalFieldTruncated)
        val hr = harness.fusion.latestSample(MetricKind.HEART_RATE)!!
        assertEquals(MetricProtocol.HRM, hr.protocol)
        assertEquals(MetricSourceClass.HRM, hr.sourceClass)
        assertEquals(150, harness.state.heartRate.value)
        assertEquals(23.9006, harness.state.expEnergyTotal.value!!.toDouble(), 0.5001)
    }

    @Test
    fun `HRM high bit and truncation cases remain bounded`() {
        val sixteenBit = decodeHrmMeasurement(byteArrayOf(0x01, 0x2C, 0x01))
        val missingHighByte = decodeHrmMeasurement(byteArrayOf(0x01, 0x2C))
        val truncatedEnergy = decodeHrmMeasurement(byteArrayOf(0x08, 100, 1))

        assertEquals(300, sixteenBit!!.bpm)
        assertNull(missingHighByte)
        assertEquals(100, truncatedEnergy!!.bpm)
        assertNull(truncatedEnergy.energyKiloJoules)
        assertTrue(truncatedEnergy.optionalFieldTruncated)
    }
}
