package com.gmail.absolutebollockscreations.Fitbridge.bluetooth.simulator

import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine.WahooKickrCodec
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine.WahooKickrCommand
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine.WahooKickrEncodeResult
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine.WahooKickrUuids
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KickrLegacyControlServerTest {
    private val commands = listOf(
        WahooKickrCommand.Unlock,
        WahooKickrCommand.Resistance(0.5),
        WahooKickrCommand.StandardLevel(7),
        WahooKickrCommand.Erg(250),
        WahooKickrCommand.Simulation(75.9, 0.004, 0.51),
        WahooKickrCommand.RollingResistance(0.004),
        WahooKickrCommand.WindResistance(0.51),
        WahooKickrCommand.Grade(8.0),
        WahooKickrCommand.WindSpeed(-1.0 / 3.6),
        WahooKickrCommand.WheelCircumference(2100.0),
    )

    @Test
    fun `every production encoder shape is accepted by independent decoder`() {
        commands.forEach { command ->
            val result = KickrLegacyCommandDecoder.decode(bytes(command))
            assertTrue("$command decoded as $result", result is KickrLegacyDecodeResult.Decoded)
            assertEquals(command::class, (result as KickrLegacyDecodeResult.Decoded).command::class)
        }
    }

    @Test
    fun `every truncation and trailing byte is rejected`() {
        commands.forEach { command ->
            val packet = bytes(command)
            for (length in 0 until packet.size) {
                assertTrue(
                    "$command truncation $length",
                    KickrLegacyCommandDecoder.decode(packet.copyOf(length)) is KickrLegacyDecodeResult.Invalid,
                )
            }
            assertTrue(
                "$command trailing byte",
                KickrLegacyCommandDecoder.decode(packet + 0x00) is KickrLegacyDecodeResult.Invalid,
            )
        }
    }

    @Test
    fun `unknown opcode wrong unlock and invalid high-bit resistance fail closed`() {
        assertTrue(KickrLegacyCommandDecoder.decode(byteArrayOf(0x7F)) is KickrLegacyDecodeResult.Unsupported)
        assertTrue(KickrLegacyCommandDecoder.decode(byteArrayOf(0x20, 0xEE.toByte(), 0xFD.toByte())) is KickrLegacyDecodeResult.Invalid)
        assertTrue(KickrLegacyCommandDecoder.decode(byteArrayOf(0x40, 0x00, 0x40)) is KickrLegacyDecodeResult.Invalid)
        assertTrue(KickrLegacyCommandDecoder.decode(byteArrayOf(0x42, 0xD1.toByte(), 0x07)) is KickrLegacyDecodeResult.Invalid)
        assertTrue(KickrLegacyCommandDecoder.decode(byteArrayOf(0x48, 0x00, 0x00)) is KickrLegacyDecodeResult.Invalid)
    }

    @Test
    fun `unsigned offset grade and wind decode their high bits`() {
        val grade = decoded(byteArrayOf(0x46, 0x3D, 0x8A.toByte())) as WahooKickrCommand.Grade
        val wind = decoded(byteArrayOf(0x47, 0x16, 0x81.toByte())) as WahooKickrCommand.WindSpeed
        assertEquals(8.0, grade.percent, 0.002)
        assertEquals(1.0 / 3.6, wind.metresPerSecond, 0.001)
    }

    @Test
    fun `acknowledging server requires unlock and applies every model target`() {
        val model = KickrSyntheticModel()
        val server = KickrLegacyControlServer(model, KickrLegacyAckMode.ACKNOWLEDGING_SYNTHETIC)

        assertFalse(server.handle(bytes(WahooKickrCommand.Erg(250))).accepted)
        val unlock = server.handle(bytes(WahooKickrCommand.Unlock))
        assertTrue(unlock.accepted)
        assertArrayEquals(byteArrayOf(0x01, 0x20, 0x01, 0x00), unlock.response)

        assertTrue(server.handle(bytes(WahooKickrCommand.Erg(250))).accepted)
        assertEquals(KickrControlMode.ERG, model.snapshot().mode)
        assertEquals(250, model.snapshot().targetPowerWatts)

        server.handle(bytes(WahooKickrCommand.Simulation(75.9, 0.004, 0.51)))
        server.handle(bytes(WahooKickrCommand.Grade(8.0)))
        server.handle(bytes(WahooKickrCommand.WindSpeed(-1.0 / 3.6)))
        val simulation = model.snapshot().simulation
        assertEquals(0.004, simulation.crr, 0.00001)
        assertEquals(0.51, simulation.cw, 0.00001)
        assertEquals(8.0, simulation.gradePercent, 0.002)
        assertEquals(-1.0 / 3.6, simulation.windMps, 0.001)

        server.handle(bytes(WahooKickrCommand.Resistance(0.5)))
        assertEquals(KickrControlMode.RESISTANCE, model.snapshot().mode)
        assertEquals(8.0, model.snapshot().resistanceLevel, 0.001)
        server.handle(bytes(WahooKickrCommand.StandardLevel(7)))
        assertEquals(7.0, model.snapshot().resistanceLevel, 0.001)
        server.handle(bytes(WahooKickrCommand.WheelCircumference(2100.0)))
        assertEquals(2100.0, model.snapshot().wheelCircumferenceMm, 0.001)
    }

    @Test
    fun `silent policy acknowledges unlock only and reset requires a new unlock`() {
        val model = KickrSyntheticModel()
        val server = KickrLegacyControlServer(model, KickrLegacyAckMode.SILENT_CORROBORATED)
        assertArrayEquals(byteArrayOf(0x01, 0x20, 0x01, 0x00), server.handle(bytes(WahooKickrCommand.Unlock)).response)
        val erg = server.handle(bytes(WahooKickrCommand.Erg(300)))
        assertTrue(erg.accepted)
        assertNull(erg.response)
        server.reset()
        assertFalse(server.handle(bytes(WahooKickrCommand.Erg(350))).accepted)
        assertEquals(300, model.snapshot().targetPowerWatts)
    }

    @Test
    fun `malformed and unsupported commands never mutate the model`() {
        val model = KickrSyntheticModel()
        val server = KickrLegacyControlServer(model, KickrLegacyAckMode.ACKNOWLEDGING_SYNTHETIC)
        server.handle(bytes(WahooKickrCommand.Unlock))
        val before = model.snapshot()
        assertFalse(server.handle(byteArrayOf(WahooKickrUuids.OPCODE_ERG.toByte(), 0x01)).accepted)
        assertFalse(server.handle(byteArrayOf(0x7F)).accepted)
        assertEquals(before, model.snapshot())
    }

    private fun bytes(command: WahooKickrCommand): ByteArray =
        (WahooKickrCodec.encode(command) as WahooKickrEncodeResult.Encoded).bytes

    private fun decoded(packet: ByteArray): WahooKickrCommand =
        (KickrLegacyCommandDecoder.decode(packet) as KickrLegacyDecodeResult.Decoded).command
}
