package com.gmail.absolutebollockscreations.gattlibrary.core.ids

import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pins registry values that other code and captured devices depend on (LIB-19b). Literal expectations. */
class IdentifierRegistryTest {

    private fun sig(hex: String): UUID = UUID.fromString("0000$hex-0000-1000-8000-00805f9b34fb")

    @Test
    fun sigServicesAndCharacteristicsMatchAssignedNumbers() {
        assertEquals(sig("180a"), SigUuids.UUID_DEVICE_INFORMATION_SERVICE)
        assertEquals(sig("180d"), SigUuids.UUID_HRM_SERVICE)
        assertEquals(sig("2a37"), SigUuids.UUID_HEART_RATE_MEASUREMENT)
        assertEquals(sig("180f"), SigUuids.UUID_BATTERY_SERVICE)
        assertEquals(sig("2a19"), SigUuids.UUID_BATTERY_LEVEL)
        assertEquals(sig("1814"), SigUuids.UUID_RSC_SERVICE)
        assertEquals(sig("2a53"), SigUuids.UUID_RSC_MEASUREMENT)
        assertEquals(sig("1816"), SigUuids.UUID_CSC_SERVICE)
        assertEquals(sig("2a5b"), SigUuids.UUID_CSC_MEASUREMENT)
        assertEquals(sig("2a5d"), SigUuids.UUID_SENSOR_LOCATION)
        assertEquals(sig("1818"), SigUuids.UUID_POWER_SERVICE)
        assertEquals(SigUuids.UUID_POWER_SERVICE, SigUuids.UUID_CYCLING_POWER_SERVICE)
        assertEquals(sig("2a63"), SigUuids.UUID_CYCLING_POWER_MEASUREMENT)
        assertEquals(sig("1808"), SigUuids.UUID_GLUCOSE_SERVICE)
        assertEquals(sig("181c"), SigUuids.UUID_USER_DATA_SERVICE)
        assertEquals(sig("2902"), SigUuids.UUID_CCCD)
    }

    @Test
    fun concept2UuidsUseTheDocumentedBase() {
        assertEquals(UUID.fromString("ce060000-43e5-11e4-916c-0800200c9a66"), Concept2Uuids.UUID_C2_DISCOVERY_SERVICE)
        assertEquals(UUID.fromString("ce060021-43e5-11e4-916c-0800200c9a66"), Concept2Uuids.UUID_C2_CSAFE_REQUEST)
        assertEquals(UUID.fromString("ce060031-43e5-11e4-916c-0800200c9a66"), Concept2Uuids.UUID_C2_GENERAL_STATUS)
    }

    @Test
    fun precorAndWahooBasesShareTheRecordedSuffix() {
        assertEquals(UUID.fromString("a026ee07-0a7d-4ab3-97fa-f1500f9feb8b"), PrecorUuids.UUID_PRECOR_SERVICE)
        assertEquals(UUID.fromString("a026e01d-0a7d-4ab3-97fa-f1500f9feb8b"), PrecorUuids.UUID_PRECOR_MEASUREMENT)
    }

    @Test
    fun flywheelAndFecUseNordicUartDerivedBasesThatDiffer() {
        assertEquals(UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e"), FlywheelUuids.UUID_FLYWHEEL_SERVICE)
        assertTrue(FlywheelUuids.UUID_FLYWHEEL_SERVICE != FecBleUuids.UUID_FEC_SERVICE)
    }

    @Test
    fun fecAntEnvelopeVocabulary() {
        assertEquals(0xA4, FecBleUuids.FEC_ANT_SYNC)
        assertEquals(0x4E, FecBleUuids.FEC_ANT_MSG_BROADCAST_DATA)
        assertEquals(0x19, FecBleUuids.FEC_PAGE_SPECIFIC_TRAINER_DATA)
        assertEquals(25, FecBleUuids.FEC_EQUIPMENT_TRAINER)
        assertEquals(FecBleUuids.FEC_EQUIPMENT_TRAINER, FecBleUuids.FEC_EQUIPMENT_STATIONARY_BIKE)
    }

    @Test
    fun userProfileListHasNoDuplicates() {
        assertEquals(SigUuids.USER_PROFILE_CHARACTERISTICS.size, SigUuids.USER_PROFILE_CHARACTERISTICS.toSet().size)
    }
}
