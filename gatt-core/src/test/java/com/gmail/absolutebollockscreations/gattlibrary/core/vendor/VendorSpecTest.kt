package com.gmail.absolutebollockscreations.gattlibrary.core.vendor

import com.gmail.absolutebollockscreations.gattlibrary.core.catalog.MachineFamilyIds
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the proprietary vocabulary moved into core in LIB-19c. Literal expectations: the values are inherited
 * from external projects and treated as correct on their field feedback, so this test detects accidental edits
 * rather than proving the values against hardware.
 */
class VendorSpecTest {

    @Test
    fun keiserFraming() {
        assertEquals(0x0102, KeiserMSeriesSpec.COMPANY_ID)
        assertArrayEquals(intArrayOf(0x02, 0x01), KeiserMSeriesSpec.MAGIC_PREFIX)
        assertEquals(19, KeiserMSeriesSpec.CURRENT_FIELD_LENGTH)
        assertEquals(18, KeiserMSeriesSpec.LEGACY_FIELD_LENGTH)
    }

    @Test
    fun precorFraming() {
        assertEquals(19, PrecorSpec.FRAME_LENGTH)
        assertArrayEquals(intArrayOf(83, 89, 22), PrecorSpec.MAGIC_PREFIX)
        assertArrayEquals(byteArrayOf(0x24, 0x08, 0x00), PrecorSpec.auxiliaryFrame())
        assertTrue(PrecorSpec.isAuxiliaryFrame(byteArrayOf(0x24, 0x08, 0x00)))
        assertTrue(!PrecorSpec.isAuxiliaryFrame(byteArrayOf(0x24, 0x08)))
    }

    @Test
    fun precorAuxiliaryFrameIsDefensivelyCopied() {
        PrecorSpec.auxiliaryFrame()[0] = 0
        assertArrayEquals(byteArrayOf(0x24, 0x08, 0x00), PrecorSpec.auxiliaryFrame())
    }

    @Test
    fun schwinnFramingAndInitialisation() {
        assertEquals(17, SchwinnX70Spec.MAIN_FRAME_LENGTH)
        assertEquals(20, SchwinnX70Spec.SUPPLEMENTAL_FRAME_LENGTH)
        assertEquals(14, SchwinnX70Spec.SIBLING_FRAME_LENGTH)
        assertArrayEquals(intArrayOf(17, 32, 0), SchwinnX70Spec.MAIN_PREFIX)
        assertEquals(5, SchwinnX70Spec.STREAM_MARKER_LENGTH)
        assertArrayEquals(intArrayOf(0x05, 0x09), SchwinnX70Spec.STREAM_MARKER_PREFIX)
        assertEquals(0, SchwinnX70Spec.STREAM_MARKER_TRAILER)
        assertArrayEquals(
            byteArrayOf(0x07, 0x01, 0xD3.toByte(), 0x00, 0x1F, 0x05, 0x01),
            SchwinnX70Spec.INITIALISATION,
        )
        assertEquals(7, SchwinnX70Spec.INITIALISATION[0].toInt())
        assertEquals(0x100, SchwinnX70Spec.INITIALISATION.sumOf { it.toInt() and 0xFF })
        assertEquals(25, SchwinnX70Spec.RESISTANCE_POWER_COEFFICIENTS.size)
        assertEquals(Triple(-0.0014, 0.7920, -13.985), SchwinnX70Spec.RESISTANCE_POWER_COEFFICIENTS.first())
    }

    @Test
    fun rideOnVocabulary() {
        assertArrayEquals("RideOn".toByteArray(Charsets.US_ASCII) + byteArrayOf(0x02, 0x02), RideOnSpec.HANDSHAKE_ORIGINAL)
        assertArrayEquals("RideOn".toByteArray(Charsets.US_ASCII) + byteArrayOf(0x02, 0x03), RideOnSpec.HANDSHAKE_CORE2)
        assertEquals(0x00, RideOnSpec.OPCODE_HUB_REQUEST)
        assertEquals(0x03, RideOnSpec.OPCODE_RIDING_DATA)
        assertEquals(0x04, RideOnSpec.OPCODE_HUB_COMMAND)
        assertEquals(0x2A, RideOnSpec.OPCODE_STATUS_MESSAGE)
        assertEquals(0x37, RideOnSpec.OPCODE_CLICK_BUTTON_STATUS)
        assertEquals(0x3C, RideOnSpec.OPCODE_HUB_REQUEST_RESPONSE)
        assertEquals(0x3F, RideOnSpec.OPCODE_VIRTUAL_SHIFT)
        assertEquals(0x40, RideOnSpec.OPCODE_VIRTUAL_SHIFT_RESPONSE)
        assertEquals(0x41, RideOnSpec.OPCODE_DEVICE_INFO_REQUEST)
        assertEquals(240, RideOnSpec.MAX_PACKET_BYTES)
        assertEquals(512L, RideOnSpec.HUB_INFO_MARKER)
        assertEquals(0L, RideOnSpec.HUB_REQUEST_DATA_ID_GENERAL_INFO)
        assertEquals(520L, RideOnSpec.HUB_REQUEST_DATA_ID_GEAR_RATIO)
        assertEquals(24, RideOnSpec.GEAR_RATIOS.size)
        assertEquals(7500, RideOnSpec.GEAR_RATIOS.first())
        assertEquals(54899, RideOnSpec.GEAR_RATIOS.last())
        assertEquals(RideOnServiceForm.ORIGINAL, RideOnServiceForm.valueOf("ORIGINAL"))
    }

    @Test
    fun wahooKickrOpcodesAndRanges() {
        assertEquals(0x20, WahooKickrSpec.OPCODE_UNLOCK)
        assertEquals(0x40, WahooKickrSpec.OPCODE_RESISTANCE)
        assertEquals(0x41, WahooKickrSpec.OPCODE_STANDARD_LEVEL)
        assertEquals(0x42, WahooKickrSpec.OPCODE_ERG)
        assertEquals(0x43, WahooKickrSpec.OPCODE_SIMULATION)
        assertEquals(0x44, WahooKickrSpec.OPCODE_ROLLING_RESISTANCE)
        assertEquals(0x45, WahooKickrSpec.OPCODE_WIND_RESISTANCE)
        assertEquals(0x46, WahooKickrSpec.OPCODE_GRADE)
        assertEquals(0x47, WahooKickrSpec.OPCODE_WIND_SPEED)
        assertEquals(0x48, WahooKickrSpec.OPCODE_WHEEL_CIRCUMFERENCE)
        assertEquals(1, WahooKickrSpec.MIN_STANDARD_LEVEL)
        assertEquals(9, WahooKickrSpec.MAX_STANDARD_LEVEL)
        assertEquals(0, WahooKickrSpec.MIN_ERG_WATTS)
        assertEquals(2000, WahooKickrSpec.MAX_ERG_WATTS)
        assertEquals(327.67, WahooKickrSpec.MAX_TOTAL_MASS_KG, 0.0)
        assertEquals(0.0255, WahooKickrSpec.MAX_CRR, 0.0)
        assertEquals(2.55, WahooKickrSpec.MAX_CW, 0.0)
        assertEquals(6553.5, WahooKickrSpec.MAX_WHEEL_CIRCUMFERENCE_MM, 0.0)
    }

    @Test
    fun kayakFirstVocabulary() {
        assertEquals(12, KayakFirstSpec.DEFAULT_BOAT_WEIGHT_KG)
        assertEquals(0.600, KayakFirstSpec.DEFAULT_PULL_LENGTH_M, 0.0)
        assertEquals(0.02527962, KayakFirstSpec.FLYWHEEL_INERTIA, 0.0)
        assertEquals(0.027, KayakFirstSpec.FORCE_RADIUS_METRES, 0.0)
        assertEquals(0.1419262, KayakFirstSpec.DRAG_COEFFICIENT, 0.0)
        assertEquals(12.0, KayakFirstSpec.BOAT_MASS_OFFSET_KG, 0.0)
        assertEquals(listOf<Byte>(0x31, 0x32, 0x33, 0x34, 0x35, 0x39), KayakFirstOrangeCommand.entries.map { it.wire })
        assertEquals(KayakFirstOrangeCommand.T_H, KayakFirstOrangeCommand.fromLine("2"))
        assertEquals(null, KayakFirstOrangeCommand.fromLine("22"))
        assertEquals(17, KayakFirstDisplayMetric.entries.size)
        assertEquals(KayakFirstDisplayMetric.AVERAGE_POWER, KayakFirstDisplayMetric.fromWireCode(18))
        assertEquals(null, KayakFirstDisplayMetric.fromWireCode(1))
        assertEquals(listOf("LEGACY", "BULL"), KayakFirstHandshakeVariant.entries.map { it.name })
    }

    @Test
    fun machineFamilyIdsAreUniqueAndStable() {
        val ids = listOf(
            MachineFamilyIds.SIG_FTMS to "sig.ftms", MachineFamilyIds.SIG_HRM to "sig.hrm",
            MachineFamilyIds.SIG_CSC to "sig.csc", MachineFamilyIds.SIG_RSC to "sig.rsc",
            MachineFamilyIds.SIG_CPS to "sig.cps", MachineFamilyIds.SIG_GLUCOSE to "sig.glucose",
            MachineFamilyIds.CONCEPT2_PM to "vendor.concept2.pm", MachineFamilyIds.FEC_BLE to "vendor.fec.ble",
            MachineFamilyIds.PRECOR_CHRONO to "vendor.precor.chrono", MachineFamilyIds.SCHWINN_X70 to "vendor.schwinn.x70",
            MachineFamilyIds.WAHOO_KICKR_LEGACY to "vendor.wahoo.kickr.legacy",
            MachineFamilyIds.WAHOO_RIDEON_ORIGINAL to "vendor.wahoo.rideon.original",
            MachineFamilyIds.WAHOO_RIDEON_CORE2 to "vendor.wahoo.rideon.core2",
            MachineFamilyIds.KEISER_M_SERIES to "vendor.keiser.m", MachineFamilyIds.FLYWHEEL_SPORTS to "vendor.flywheel.sports",
            MachineFamilyIds.KAYAK_FIRST to "kayak.first", MachineFamilyIds.LIFE_FITNESS to "vendor.lifefitness",
        )
        ids.forEach { (actual, expected) -> assertEquals(expected, actual) }
        assertEquals(ids.size, ids.map { it.first }.toSet().size)
    }
}
