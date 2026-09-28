package com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Byte-exact tests for [FeCControlEncoder], per plan §16.6.
 *
 * [encodeTrackResistance emits the pinned setSlope-0 vector from the Tacx PDF] is the one
 * externally-sourced vector here. Every other case is computed from the field formulas
 * themselves (documented in [FeCControlEncoder]'s KDoc) rather than reverse-engineered from any
 * implementation - the same distinction [FeCFrameCodecTest] draws between its pinned vector and
 * its self-consistency round-trips.
 */
class FeCControlEncoderTest {

    private fun bytes(vararg v: Int) = ByteArray(v.size) { (v[it] and 0xFF).toByte() }

    @Test
    fun `encodeRequestDataPage requests page 54 twice as broadcast responses`() {
        val wire = FeCControlEncoder.encodeRequestDataPage(BluetoothIds.FEC_PAGE_FE_CAPABILITIES)
        assertArrayEquals(
            bytes(0xA4, 0x09, 0x4F, 0x05, 0x46, 0xFF, 0xFF, 0xFF, 0xFF, 0x02, 0x36, 0x01, 0x94),
            wire,
        )
    }

    @Test
    fun `encodeTrackResistance emits the pinned setSlope-0 vector from the Tacx PDF`() {
        val wire = FeCControlEncoder.encodeTrackResistance(
            gradePercent = 0.0,
            rollingResistanceCoefficient = 0.0033,
        )
        assertArrayEquals(
            bytes(0xA4, 0x09, 0x4F, 0x05, 0x33, 0xFF, 0xFF, 0xFF, 0xFF, 0x20, 0x4E, 0x42, 0xF8),
            wire,
        )
    }

    @Test
    fun `encodeTrackResistance handles a non-zero grade and coefficient`() {
        val wire = FeCControlEncoder.encodeTrackResistance(
            gradePercent = 5.5,
            rollingResistanceCoefficient = 0.004,
        )
        assertArrayEquals(
            bytes(0xA4, 0x09, 0x4F, 0x05, 0x33, 0xFF, 0xFF, 0xFF, 0xFF, 0x46, 0x50, 0x50, 0x92),
            wire,
        )
    }

    @Test
    fun `encodeTrackResistance rejects grade outside -200 to 200`() {
        assertThrows(IllegalArgumentException::class.java) {
            FeCControlEncoder.encodeTrackResistance(gradePercent = 201.0, rollingResistanceCoefficient = 0.004)
        }
    }

    /**
     * An omitted rolling resistance coefficient must encode the spec's own `0xFF` "use device
     * default" sentinel (Table 8-42), not a zero or any other invented value - this is what lets
     * [FeCBleMachineControlEndpoint.setTargetInclinationPercent] send an honest incline-only
     * write without a Crr figure to offer.
     */
    @Test
    fun `encodeTrackResistance with rollingResistanceCoefficient omitted encodes the unavailable sentinel`() {
        val wire = FeCControlEncoder.encodeTrackResistance(gradePercent = 0.0)
        assertArrayEquals(
            bytes(0xA4, 0x09, 0x4F, 0x05, 0x33, 0xFF, 0xFF, 0xFF, 0xFF, 0x20, 0x4E, 0xFF, 0x45),
            wire,
        )
    }

    @Test
    fun `encodeBasicResistance encodes 50 percent at half-percent resolution`() {
        val wire = FeCControlEncoder.encodeBasicResistance(50.0)
        assertArrayEquals(
            bytes(0xA4, 0x09, 0x4F, 0x05, 0x30, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0x64, 0xB3),
            wire,
        )
    }

    @Test
    fun `encodeBasicResistance rejects a percent outside 0 to 100`() {
        assertThrows(IllegalArgumentException::class.java) {
            FeCControlEncoder.encodeBasicResistance(-1.0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            FeCControlEncoder.encodeBasicResistance(100.1)
        }
    }

    @Test
    fun `encodeTargetPower encodes watts at quarter-watt resolution`() {
        val wire = FeCControlEncoder.encodeTargetPower(200.0)
        assertArrayEquals(
            bytes(0xA4, 0x09, 0x4F, 0x05, 0x31, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0x20, 0x03, 0x0A),
            wire,
        )
    }

    @Test
    fun `encodeTargetPower rejects negative and non-finite watts`() {
        assertThrows(IllegalArgumentException::class.java) { FeCControlEncoder.encodeTargetPower(-1.0) }
        assertThrows(IllegalArgumentException::class.java) {
            FeCControlEncoder.encodeTargetPower(Double.NaN)
        }
    }

    @Test
    fun `encodeWindResistance with all fields set`() {
        val wire = FeCControlEncoder.encodeWindResistance(
            windResistanceCoefficient = 0.51,
            windSpeedKmh = -10.0,
            draftingFactor = 0.25,
        )
        assertArrayEquals(
            bytes(0xA4, 0x09, 0x4F, 0x05, 0x32, 0xFF, 0xFF, 0xFF, 0xFF, 0x33, 0x75, 0x19, 0x8A),
            wire,
        )
    }

    @Test
    fun `encodeWindResistance with every field omitted encodes the unavailable sentinel`() {
        val wire = FeCControlEncoder.encodeWindResistance()
        assertArrayEquals(
            bytes(0xA4, 0x09, 0x4F, 0x05, 0x32, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0x2A),
            wire,
        )
    }

    @Test
    fun `encodeWindResistance rejects a wind speed outside the offset-encodable range`() {
        assertThrows(IllegalArgumentException::class.java) {
            FeCControlEncoder.encodeWindResistance(windSpeedKmh = 200.0)
        }
    }

    @Test
    fun `every control page uses acknowledged data, not broadcast`() {
        val pages = listOf(
            FeCControlEncoder.encodeBasicResistance(10.0),
            FeCControlEncoder.encodeTargetPower(100.0),
            FeCControlEncoder.encodeWindResistance(),
            FeCControlEncoder.encodeTrackResistance(0.0, 0.004),
            FeCControlEncoder.encodeRequestDataPage(BluetoothIds.FEC_PAGE_FE_CAPABILITIES),
        )
        for (wire in pages) {
            assertArrayEquals(byteArrayOf(0x4F), byteArrayOf(wire[2]))
        }
    }
}
