package com.gmail.absolutebollockscreations.gattlibrary.central

import com.gmail.absolutebollockscreations.gattlibrary.core.ftms.FtmsUuids
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SoleMeasurementTest {
    @Test
    fun `machine candidate can open a named Sole without FTMS advertising`() {
        val registry = DeviceMatcherRegistry.standard()
        val observation = MatchObservation(
            localName = "SOLE F65",
            advertisedServiceUuids = setOf(CanonicalUuid.fromUuid(SOLE_UART)),
        )
        assertTrue(registry.rankedCandidates(observation).any { it.candidateId == "sole" && it.machineRole })
        assertFalse(registry.rankedCandidates(observation.copy(localName = "Other UART"))
            .any { it.candidateId == "sole" })
        assertTrue(ProtocolKey.SOLE in MACHINE_PROTOCOLS)
        assertTrue(HandlerFactory.standard().create(ProtocolKey.SOLE) is SoleProtocolHandler)
    }

    @Test
    fun `F65 extracts speed and consumes only its exact zero HR placeholder`() {
        val speeds = mutableListOf<Double>()
        for ((hex, speed) in listOf("00016400" to 1.0, "00012c01" to 3.0, "00015802" to 6.0, "00018403" to 9.0)) {
            assertTrue(handleSoleShortTreadmillData(SoleModel.F65, false, hex.hexToByteArray()) { speeds += it })
            assertEquals(speed, speeds.last(), 0.001)
        }
        assertTrue(handleSoleShortTreadmillData(SoleModel.F65, false, "01010000".hexToByteArray()) { speeds += it })
        assertFalse(handleSoleShortTreadmillData(SoleModel.F65, false, "00012c018c".hexToByteArray()) { speeds += it })
        assertFalse(handleSoleShortTreadmillData(null, false, "00012c01".hexToByteArray()) { speeds += it })
        assertTrue(handleSoleShortTreadmillData(SoleModel.F80, true, "00010000".hexToByteArray()) { speeds += it })
        assertFalse(handleSoleShortTreadmillData(SoleModel.F80, false, "00010000".hexToByteArray()) { speeds += it })
        assertEquals(4, speeds.size)
    }

    @Test
    fun `split bike packets reassemble with the same metrics as whole packets`() {
        val frame = "5b1306000200000000004105e5000e0000000000005d".hexToByteArray()
        val resistance = "5b0213075d".hexToByteArray()
        val assembler = SoleFrameAssembler()
        assertTrue(assembler.accept(frame.copyOfRange(0, 20), 0).isEmpty())
        val result = assembler.accept(frame.copyOfRange(20, 22) + resistance, 25)
        assertEquals(2, result.size)
        assertArrayEquals(frame, result[0])
        val metrics = soleMeasurements(SoleModel.R92, result[0])
        assertEquals(65, metrics[MetricKind.CADENCE])
        assertEquals(14, metrics[MetricKind.POWER])
        assertEquals(24.285, metrics.getValue(MetricKind.SPEED).toDouble(), 0.001)
        assertEquals(7, soleMeasurements(SoleModel.R92, result[1])[MetricKind.RESISTANCE])
        assertTrue(soleMeasurements(SoleModel.F65, frame).isEmpty())
        assertTrue(assembler.accept(frame.copyOfRange(0, 20), 50).isEmpty())
        assertTrue(assembler.accept(frame.copyOfRange(20, 22), 1_051).isEmpty())
        assertTrue(soleMeasurements(SoleModel.R92, frame.copyOf(21)).isEmpty())
    }

    @Test
    fun `fresh FTMS speed wins while Sole UART fills incline and supplies stale speed fallback`() {
        val fusion = MetricFusion(BtEngineState()).also { it.setPersona(FusionPersona.RUN, 0) }
        val link = ConnectionId("C0:00:00:00:00:65", 1)
        val ftms = MetricSourceId(link, MetricProtocol.FTMS, FtmsUuids.UUID_TREADMILL_DATA)
        val uart = MetricSourceId(link, MetricProtocol.VENDOR, SOLE_NOTIFY)
        fusion.selectMachine(link)
        MetricEmitter(fusion, ftms, MetricSourceClass.FTMS, 1_000)
            .emit(MetricKind.SPEED, 9.0)
        val reading = soleMeasurements(SoleModel.F65, "5b0f060001000000008c320400000000ff5d".hexToByteArray())
        val vendor = MetricEmitter(fusion, uart, MetricSourceClass.VENDOR_NATIVE, 1_100)
        reading.forEach { (metric, value) -> vendor.emit(metric, value) }
        assertEquals(ftms, fusion.selectedSource(MetricKind.SPEED))
        assertEquals(uart, fusion.selectedSource(MetricKind.INCLINATION))
        assertEquals(4.0, checkNotNull(fusion.latestSample(MetricKind.INCLINATION)).value, 0.001)
        MetricEmitter(fusion, uart, MetricSourceClass.VENDOR_NATIVE, 10_000)
            .emit(MetricKind.SPEED, reading.getValue(MetricKind.SPEED))
        assertEquals(uart, fusion.selectedSource(MetricKind.SPEED))
    }
}
