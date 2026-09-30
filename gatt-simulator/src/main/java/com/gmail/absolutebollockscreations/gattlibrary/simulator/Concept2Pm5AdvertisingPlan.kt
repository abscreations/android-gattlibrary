package com.gmail.absolutebollockscreations.gattlibrary.simulator

import com.gmail.absolutebollockscreations.gattlibrary.central.BluetoothIds
import java.util.UUID
import com.gmail.absolutebollockscreations.gattlibrary.peripheral.advertisedName
import com.gmail.absolutebollockscreations.gattlibrary.core.ftms.FtmsUuids

/** Legacy-PDU advertisement layout for a PM5 persona, kept separate from FTMS budgeting. */
internal data class Concept2Pm5AdvertisingPlan(
    val localName: String,
    val includeNameInAdvertisement: Boolean,
    val includeTxPowerInAdvertisement: Boolean,
    val advertisingServiceUuids: List<UUID>,
    val scanResponseServiceUuids: List<UUID>,
    val scanResponseFtmsServiceData: ByteArray?,
    val advertisingBytes: Int,
    val scanResponseBytes: Int,
) {
    init {
        require(advertisingBytes <= MAX_LEGACY_BYTES)
        require(scanResponseBytes <= MAX_LEGACY_BYTES)
    }

    companion object {
        const val MAX_LEGACY_BYTES = 31
        private const val FLAGS_STRUCTURE_BYTES = 3
        private const val UUID128_STRUCTURE_BYTES = 18
        private const val UUID16_STRUCTURE_BYTES = 4
        private const val TX_POWER_STRUCTURE_BYTES = 3
        private const val STRUCTURE_OVERHEAD_BYTES = 2
        private const val FTMS_SERVICE_DATA_STRUCTURE_BYTES = 7

        fun forPersona(persona: SimulatorPersona.Concept2Pm5): Concept2Pm5AdvertisingPlan {
            val nameBytes = persona.advertisedName.toByteArray(Charsets.UTF_8).size
            if (persona.compatibility.exposesFtms) {
                val ftmsData = when (persona.machine) {
                    com.gmail.absolutebollockscreations.gattlibrary.simulator.Concept2Machine.BIKE_ERG ->
                        byteArrayOf(0x01, 0x20, 0x00)
                    else -> byteArrayOf(0x01, 0x10, 0x00)
                }
                return Concept2Pm5AdvertisingPlan(
                    localName = persona.advertisedName,
                    includeNameInAdvertisement = true,
                    includeTxPowerInAdvertisement = true,
                    advertisingServiceUuids = emptyList(),
                    scanResponseServiceUuids = listOf(
                        FtmsUuids.UUID_FTMS_SERVICE,
                        BluetoothIds.UUID_C2_DISCOVERY_SERVICE,
                    ),
                    scanResponseFtmsServiceData = ftmsData,
                    // Android owns the Flags structure. Unlike the PM5 controller, its public
                    // advertiser API cannot add the captured zero-valued Appearance structure.
                    advertisingBytes = FLAGS_STRUCTURE_BYTES + TX_POWER_STRUCTURE_BYTES +
                        STRUCTURE_OVERHEAD_BYTES + nameBytes,
                    scanResponseBytes = UUID16_STRUCTURE_BYTES + UUID128_STRUCTURE_BYTES +
                        FTMS_SERVICE_DATA_STRUCTURE_BYTES,
                )
            }
            return Concept2Pm5AdvertisingPlan(
                localName = persona.advertisedName,
                includeNameInAdvertisement = false,
                includeTxPowerInAdvertisement = false,
                advertisingServiceUuids = listOf(BluetoothIds.UUID_C2_DISCOVERY_SERVICE),
                scanResponseServiceUuids = emptyList(),
                scanResponseFtmsServiceData = null,
                advertisingBytes = FLAGS_STRUCTURE_BYTES + UUID128_STRUCTURE_BYTES,
                scanResponseBytes = STRUCTURE_OVERHEAD_BYTES + nameBytes,
            )
        }
    }
}
