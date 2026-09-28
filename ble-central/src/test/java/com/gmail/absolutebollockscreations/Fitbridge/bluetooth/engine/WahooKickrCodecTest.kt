package com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WahooKickrCodecTest {
    private fun bytes(command: WahooKickrCommand): ByteArray {
        val result = WahooKickrCodec.encode(command)
        assertTrue("expected encoded result, got $result", result is WahooKickrEncodeResult.Encoded)
        return (result as WahooKickrEncodeResult.Encoded).bytes
    }

    private fun invalid(command: WahooKickrCommand, field: String) {
        val result = WahooKickrCodec.encode(command)
        assertTrue("expected invalid result, got $result", result is WahooKickrEncodeResult.Invalid)
        assertEquals(field, (result as WahooKickrEncodeResult.Invalid).error.field)
    }

    private fun assertPacket(expected: IntArray, command: WahooKickrCommand) {
        assertArrayEquals(expected.map(Int::toByte).toByteArray(), bytes(command))
    }

    @Test
    fun `unlock and discrete modes have exact wire bytes`() {
        assertPacket(intArrayOf(0x20, 0xEE, 0xFC), WahooKickrCommand.Unlock)
        assertPacket(intArrayOf(0x41, 0x01), WahooKickrCommand.StandardLevel(1))
        assertPacket(intArrayOf(0x41, 0x09), WahooKickrCommand.StandardLevel(9))
        assertPacket(intArrayOf(0x42, 0x00, 0x00), WahooKickrCommand.Erg(0))
        assertPacket(intArrayOf(0x42, 0xD0, 0x07), WahooKickrCommand.Erg(2000))
    }

    @Test
    fun `direct resistance is clamped and inverted across the 14-bit range`() {
        assertPacket(intArrayOf(0x40, 0xFF, 0x3F), WahooKickrCommand.Resistance(-1.0))
        assertPacket(intArrayOf(0x40, 0xFF, 0x2F), WahooKickrCommand.Resistance(0.25))
        assertPacket(intArrayOf(0x40, 0x00, 0x20), WahooKickrCommand.Resistance(0.5))
        assertPacket(intArrayOf(0x40, 0x00, 0x10), WahooKickrCommand.Resistance(0.75))
        assertPacket(intArrayOf(0x40, 0x00, 0x00), WahooKickrCommand.Resistance(2.0))
    }

    @Test
    fun `simulation uses approved mass CRR and wind coefficient scales`() {
        assertPacket(
            intArrayOf(0x43, 0xA6, 0x1D, 0x28, 0x00, 0xFE, 0x01),
            WahooKickrCommand.Simulation(75.90, 0.0040, 0.510),
        )
        assertPacket(
            intArrayOf(0x44, 0x28, 0x00),
            WahooKickrCommand.RollingResistance(0.0040),
        )
        assertPacket(
            intArrayOf(0x45, 0xFE, 0x01),
            WahooKickrCommand.WindResistance(0.510),
        )
    }

    @Test
    fun `grade clamps to unsigned offset endpoints and preserves midpoint rounding`() {
        assertPacket(intArrayOf(0x46, 0x00, 0x00), WahooKickrCommand.Grade(-101.0))
        assertPacket(intArrayOf(0x46, 0x5C, 0x7F), WahooKickrCommand.Grade(-0.5))
        assertPacket(intArrayOf(0x46, 0x00, 0x80), WahooKickrCommand.Grade(0.0))
        assertPacket(intArrayOf(0x46, 0x8F, 0x82), WahooKickrCommand.Grade(2.0))
        assertPacket(intArrayOf(0x46, 0x3D, 0x8A), WahooKickrCommand.Grade(8.0))
        assertPacket(intArrayOf(0x46, 0xFF, 0xFF), WahooKickrCommand.Grade(101.0))
    }

    @Test
    fun `wind speed uses unsigned offset binary including the high bit`() {
        assertPacket(intArrayOf(0x47, 0x00, 0x00), WahooKickrCommand.WindSpeed(-99.0))
        assertPacket(intArrayOf(0x47, 0xEA, 0x7E), WahooKickrCommand.WindSpeed(-1.0 / 3.6))
        assertPacket(intArrayOf(0x47, 0x00, 0x80), WahooKickrCommand.WindSpeed(0.0))
        assertPacket(intArrayOf(0x47, 0x16, 0x81), WahooKickrCommand.WindSpeed(1.0 / 3.6))
        assertPacket(intArrayOf(0x47, 0xFF, 0xFF), WahooKickrCommand.WindSpeed(99.0))
    }

    @Test
    fun `wheel circumference is encoded in tenths of a millimetre`() {
        assertPacket(intArrayOf(0x48, 0x01, 0x00), WahooKickrCommand.WheelCircumference(0.1))
        assertPacket(intArrayOf(0x48, 0x08, 0x52), WahooKickrCommand.WheelCircumference(2100.0))
        assertPacket(intArrayOf(0x48, 0xFF, 0xFF), WahooKickrCommand.WheelCircumference(6553.5))
    }

    @Test
    fun `only the three approved continuous fields clamp`() {
        invalid(WahooKickrCommand.StandardLevel(0), "standardLevel")
        invalid(WahooKickrCommand.StandardLevel(10), "standardLevel")
        invalid(WahooKickrCommand.Erg(-1), "watts")
        invalid(WahooKickrCommand.Erg(2001), "watts")
        invalid(WahooKickrCommand.Simulation(0.0, 0.004, 0.51), "totalMassKg")
        invalid(WahooKickrCommand.Simulation(327.68, 0.004, 0.51), "totalMassKg")
        invalid(WahooKickrCommand.RollingResistance(-0.0001), "crr")
        invalid(WahooKickrCommand.RollingResistance(0.0256), "crr")
        invalid(WahooKickrCommand.WindResistance(-0.01), "cw")
        invalid(WahooKickrCommand.WindResistance(2.56), "cw")
        invalid(WahooKickrCommand.WheelCircumference(0.09), "wheelCircumference")
        invalid(WahooKickrCommand.WheelCircumference(6553.6), "wheelCircumference")
    }

    @Test
    fun `every floating input rejects NaN and infinity rather than wrapping`() {
        for (bad in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            invalid(WahooKickrCommand.Resistance(bad), "resistance")
            invalid(WahooKickrCommand.Simulation(bad, 0.004, 0.51), "totalMassKg")
            invalid(WahooKickrCommand.Simulation(75.0, bad, 0.51), "crr")
            invalid(WahooKickrCommand.Simulation(75.0, 0.004, bad), "cw")
            invalid(WahooKickrCommand.RollingResistance(bad), "crr")
            invalid(WahooKickrCommand.WindResistance(bad), "cw")
            invalid(WahooKickrCommand.Grade(bad), "grade")
            invalid(WahooKickrCommand.WindSpeed(bad), "windSpeed")
            invalid(WahooKickrCommand.WheelCircumference(bad), "wheelCircumference")
        }
    }
}
