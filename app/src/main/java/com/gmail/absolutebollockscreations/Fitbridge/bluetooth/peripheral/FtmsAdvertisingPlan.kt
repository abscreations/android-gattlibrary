package com.gmail.absolutebollockscreations.Fitbridge.bluetooth.peripheral

import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine.BluetoothIds
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine.BtServiceProfiles.FtmsMachineProfile
import java.util.UUID

/**
 * Pure, testable plan for the legacy advertising and scan-response sets.
 *
 * Legacy advertisements have a hard 31-octet budget and Android reports overflow
 * only through an asynchronous callback. Computing the complete AD structure
 * sizes here lets startup fail deterministically before touching the radio and
 * keeps payload policy out of Android callback code.
 *
 * The previous FitBridge advertiser tracked the budget inline while building the
 * AdvertiseData and simply skipped structures once it ran out, so an overlong
 * persona name silently dropped the FTMS Service Data a scanner needs to type
 * the machine.
 */
data class FtmsAdvertisingPlan(
    val localName: String,
    val serviceData: ByteArray,
    val advertisingBytes: Int,
    val scanResponseBytes: Int,
    val serviceUuids: List<UUID> = listOf(BluetoothIds.UUID_FTMS_SERVICE),
) {
    val appearanceSupported: Boolean = false

    init {
        require(advertisingBytes <= MAX_LEGACY_BYTES) {
            "Advertising payload is $advertisingBytes of $MAX_LEGACY_BYTES octets"
        }
        require(scanResponseBytes <= MAX_LEGACY_BYTES) {
            "Scan response is $scanResponseBytes of $MAX_LEGACY_BYTES octets"
        }
    }

    override fun equals(other: Any?): Boolean =
        other is FtmsAdvertisingPlan &&
            other.localName == localName &&
            other.serviceData.contentEquals(serviceData) &&
            other.advertisingBytes == advertisingBytes &&
            other.scanResponseBytes == scanResponseBytes &&
            other.serviceUuids == serviceUuids

    override fun hashCode(): Int {
        var result = localName.hashCode()
        result = 31 * result + serviceData.contentHashCode()
        result = 31 * result + advertisingBytes
        result = 31 * result + scanResponseBytes
        result = 31 * result + serviceUuids.hashCode()
        return result
    }

    companion object {
        const val MAX_LEGACY_BYTES = 31
        private const val CONTROLLER_FLAGS_BYTES = 3
        private const val AD_HEADER_BYTES = 2
        private const val UUID_16_BYTES = 2

        /**
         * @param advertisedMachineType machine type written into the FTMS Service Data. Equals
         * [profile] except for the simulator's deliberately misadvertising test persona; the
         * local name and service list always follow [profile].
         */
        fun forProfile(
            profile: FtmsMachineProfile,
            includeNativeBikingSensors: Boolean = false,
            advertisedMachineType: FtmsMachineProfile = profile,
        ): FtmsAdvertisingPlan {
            val name = profile.advertisedName
            val serviceData = ftmsServiceData(advertisedMachineType, available = true)
            val serviceUuids = if (
                includeNativeBikingSensors && profile == FtmsMachineProfile.INDOOR_BIKE
            ) {
                listOf(
                    BluetoothIds.UUID_FTMS_SERVICE,
                    BluetoothIds.UUID_CSC_SERVICE,
                    BluetoothIds.UUID_CYCLING_POWER_SERVICE,
                )
            } else {
                listOf(BluetoothIds.UUID_FTMS_SERVICE)
            }
            // Android/controller contributes the Flags structure. Every other
            // structure needs its two-byte length/type header included here.
            val used = CONTROLLER_FLAGS_BYTES +
                (AD_HEADER_BYTES + name.toByteArray(Charsets.UTF_8).size) +
                (AD_HEADER_BYTES + UUID_16_BYTES * serviceUuids.size) +
                (AD_HEADER_BYTES + UUID_16_BYTES + serviceData.size)
            return FtmsAdvertisingPlan(
                localName = name,
                serviceData = serviceData,
                advertisingBytes = used,
                scanResponseBytes = 0,
                serviceUuids = serviceUuids,
            )
        }
    }
}
