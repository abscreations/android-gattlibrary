package com.gmail.absolutebollockscreations.Fitbridge.bluetooth.simulator

import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine.RIDEON_GEAR_RATIOS
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine.RideOnControlTarget
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine.RideOnDecodeResult
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine.RideOnProtoCodec
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine.RideOnProtoField
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine.RideOnServiceForm
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine.RideOnUuids
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine.decodeRideOnTelemetry
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine.encodeRideOnTarget
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ZwiftTrainerPeripheralServerTest {
    @Test
    fun `CORE2 form accepts only 02 03 and uses shared FreeWheel vectors`() {
        val model = KickrSyntheticModel()
        val server = ZwiftTrainerPeripheralServer(RideOnServiceForm.CORE2, model)
        assertFalse(server.handle(RideOnUuids.HANDSHAKE_ORIGINAL, true).accepted)
        val handshake = server.handle(RideOnUuids.HANDSHAKE_CORE2, true)
        assertTrue(handshake.accepted)
        assertArrayEquals(RideOnUuids.HANDSHAKE_CORE2, handshake.frames.single().value)
        assertTrue(server.handle(hex("41 08 05"), true).accepted)
        assertTrue(server.handle(hex("00 08 00"), true).accepted)
        assertTrue(server.handle(hex("04 18 FA 01"), true).accepted)
        assertEquals(250, model.snapshot().targetPowerWatts)
        val shift = server.handle(RideOnProtoCodec.virtualShift(1, 0, 24_000), true)
        assertTrue(shift.accepted)
        assertArrayEquals(hex("40 10 00"), shift.frames.single().value)
        assertEquals(12, model.snapshot().virtualGearIndex)
    }

    @Test
    fun `original form requires both CCCDs and exact 02 02 marker`() {
        val server = subject()
        assertFalse(server.handle(RideOnUuids.HANDSHAKE_ORIGINAL, false).accepted)
        assertFalse(server.handle(RideOnUuids.HANDSHAKE_CORE2, true).accepted)
        val accepted = server.handle(RideOnUuids.HANDSHAKE_ORIGINAL, true)
        assertTrue(accepted.accepted && accepted.negotiated)
        assertArrayEquals(RideOnUuids.HANDSHAKE_ORIGINAL, accepted.frames.single().value)
        assertEquals(ZwiftTrainerStage.NEGOTIATED, server.stage)
    }

    @Test
    fun `captured initialization order gates commands`() {
        val server = subject()
        assertTrue(server.handle(RideOnUuids.HANDSHAKE_ORIGINAL, true).accepted)
        assertFalse(server.handle(hex("00 08 00"), true).accepted)
        assertFalse(server.handle(hex("04 18 FA 01"), true).accepted)
        assertTrue(server.handle(hex("41 08 05"), true).accepted)
        assertEquals(ZwiftTrainerStage.INITIALIZED, server.stage)
        val ready = server.handle(hex("00 08 00"), true)
        assertTrue(ready.accepted)
        assertTrue(ready.frames.single().async)
        assertEquals(ZwiftTrainerStage.READY, server.stage)
    }

    @Test
    fun `ERG and signed simulation use production vectors and one model`() {
        val model = KickrSyntheticModel()
        val server = ready(model)
        assertTrue(server.handle(hex("04 18 FA 01"), true).accepted)
        assertEquals(KickrControlMode.ERG, model.snapshot().mode)
        assertEquals(250, model.snapshot().targetPowerWatts)

        val simulation = RideOnControlTarget.Simulation(
            windMps = -1.25,
            gradePercent = -4.0,
            crr = 0.004,
            cw = 0.51,
            gearRatio = 2.4,
            bicycleMassKg = 10.0,
            riderMassKg = 75.0,
        )
        assertTrue(server.handle(encodeRideOnTarget(simulation)!!, true).accepted)
        val snapshot = model.snapshot()
        assertEquals(KickrControlMode.SIMULATION, snapshot.mode)
        assertEquals(-1.25, snapshot.simulation.windMps, 0.001)
        assertEquals(-4.0, snapshot.simulation.gradePercent, 0.001)
        assertEquals(0.004, snapshot.simulation.crr, 0.000001)
        assertEquals(0.51, snapshot.simulation.cw, 0.000001)
        assertEquals(85.0, snapshot.simulation.totalMassKg, 0.001)
        assertEquals(12, snapshot.virtualGearIndex)
        assertEquals(2.4, snapshot.gearRatio, 0.000001)
    }

    @Test
    fun `RideOn rider and bicycle masses change physics power at the same speed`() {
        fun powerFor(riderMassKg: Double, bicycleMassKg: Double): Int {
            val model = KickrSyntheticModel().also { it.startOrResume() }
            val server = ready(model)
            val target = RideOnControlTarget.Simulation(
                windMps = 0.0,
                gradePercent = 5.0,
                crr = 0.004,
                cw = 0.51,
                gearRatio = 2.4,
                bicycleMassKg = bicycleMassKg,
                riderMassKg = riderMassKg,
            )
            check(server.handle(encodeRideOnTarget(target)!!, true).accepted)
            repeat(80) { model.advance(0.5) }
            assertEquals(riderMassKg + bicycleMassKg, model.snapshot().simulation.totalMassKg, 0.001)
            assertEquals(28.0, model.snapshot().speedKph, 0.02)
            return model.snapshot().powerWatts
        }

        assertTrue(powerFor(90.0, 12.0) > powerFor(60.0, 8.0))
    }

    @Test
    fun `RideOn signed descent lowers live telemetry power after flat simulation`() {
        val model = KickrSyntheticModel().also { it.startOrResume() }
        val server = ready(model)
        fun send(gradePercent: Double) = server.handle(
            encodeRideOnTarget(
                RideOnControlTarget.Simulation(
                    windMps = 0.0,
                    gradePercent = gradePercent,
                    crr = 0.004,
                    cw = 0.51,
                    gearRatio = 2.4,
                    bicycleMassKg = 10.0,
                    riderMassKg = 75.0,
                ),
            )!!,
            true,
        )

        assertTrue(send(0.0).accepted)
        repeat(80) { model.advance(0.5) }
        val flat = model.snapshot()
        assertTrue(send(-5.0).accepted)
        val descent = model.advance(0.5)
        val telemetry = decodeRideOnTelemetry(server.telemetryFrame().value)!!

        assertEquals(flat.speedKph, descent.speedKph, 0.01)
        assertEquals(-5.0, descent.simulation.gradePercent, 0.001)
        assertTrue(descent.powerWatts < flat.powerWatts)
        assertEquals(0, descent.powerWatts)
        assertEquals(descent.powerWatts, telemetry.powerWatts)
    }

    @Test
    fun `all 24 gears update state and echo exact uint32 sequence`() {
        val model = KickrSyntheticModel()
        val server = ready(model)
        RIDEON_GEAR_RATIOS.forEachIndexed { index, ratio ->
            val sequence = if (index == 23) 0xFFFF_FFFFL else index.toLong()
            val outcome = server.handle(RideOnProtoCodec.virtualShift(1, sequence, ratio.toLong()), true)
            assertTrue("gear ${index + 1}: ${outcome.reason}", outcome.accepted)
            assertArrayEquals(RideOnProtoCodec.virtualShiftResponse(sequence), outcome.frames.single().value)
            assertEquals(index + 1, model.snapshot().virtualGearIndex)
            assertEquals(ratio / 10_000.0, model.snapshot().gearRatio, 0.000001)
        }
    }

    @Test
    fun `riding data is encoded from the same immutable snapshot`() {
        val model = KickrSyntheticModel()
        val server = ready(model)
        server.handle(hex("04 18 FA 01"), true)
        model.startOrResume()
        val snapshot = model.advance(2.0)
        val frame = server.telemetryFrame()
        val decoded = decodeRideOnTelemetry(frame.value)!!
        assertTrue(frame.async)
        assertEquals(snapshot.powerWatts, decoded.powerWatts)
        assertEquals(snapshot.cadenceRpm.toInt(), decoded.cadenceRpm)
        assertEquals(snapshot.speedKph, decoded.speedKph!!, 0.01)
        assertEquals(snapshot.heartRateBpm, decoded.heartRateBpm)
    }

    @Test
    fun `unsupported direct resistance malformed duplicates and invalid gears fail without mutation`() {
        val model = KickrSyntheticModel()
        val server = ready(model)
        val before = model.snapshot()
        listOf(
            hex("04 08 01"),
            hex("04 18 64 18 65"),
            hex("04 1A 01 00"),
            RideOnProtoCodec.virtualShift(2, 0, 24_000),
            RideOnProtoCodec.virtualShift(1, 0, 24_001),
            ByteArray(RideOnUuids.MAX_PACKET_BYTES + 1) { 0x04 },
        ).forEach { assertFalse(server.handle(it, true).accepted) }
        assertEquals(before, model.snapshot())
    }

    @Test
    fun `every target and shift truncation fails without throwing or mutation`() {
        val model = KickrSyntheticModel()
        val server = ready(model)
        val packets = listOf(
            hex("04 18 FA 01"),
            encodeRideOnTarget(
                RideOnControlTarget.Simulation(-1.25, -4.0, 0.004, 0.51, 2.4, 10.0, 75.0),
            )!!,
            RideOnProtoCodec.virtualShift(1, 0, 24_000),
        )
        packets.forEach { packet ->
            for (length in 0 until packet.size) {
                val before = model.snapshot()
                assertFalse("${packet[0]} at $length", server.handle(packet.copyOf(length), true).accepted)
                assertEquals(before, model.snapshot())
            }
        }
    }

    @Test
    fun `unknown supported protobuf fields are preserved structurally and do not block ERG`() {
        val model = KickrSyntheticModel()
        val server = ready(model)
        val packet = hex("04 18 FA 01 30 07")
        val decoded = (RideOnProtoCodec.decode(packet) as RideOnDecodeResult.Decoded).packet
        assertEquals(7L, (decoded.fields.single { it.number == 6 } as RideOnProtoField.Varint).value)
        assertTrue(server.handle(packet, true).accepted)
        assertEquals(250, model.snapshot().targetPowerWatts)
    }

    @Test
    fun `reset clears negotiation and rejects late commands`() {
        val server = ready(KickrSyntheticModel())
        server.reset()
        assertEquals(ZwiftTrainerStage.CONNECTED, server.stage)
        assertFalse(server.handle(hex("04 18 FA 01"), true).accepted)
    }

    private fun subject(model: KickrSyntheticModel = KickrSyntheticModel()) =
        ZwiftTrainerPeripheralServer(RideOnServiceForm.ORIGINAL, model)

    private fun ready(model: KickrSyntheticModel): ZwiftTrainerPeripheralServer = subject(model).also {
        check(it.handle(RideOnUuids.HANDSHAKE_ORIGINAL, true).accepted)
        check(it.handle(hex("41 08 05"), true).accepted)
        check(it.handle(hex("00 08 00"), true).accepted)
    }

    private fun hex(value: String): ByteArray = value.split(' ').map { it.toInt(16).toByte() }.toByteArray()
}
