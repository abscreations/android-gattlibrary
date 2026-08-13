package com.gmail.absolutebollockscreations.Fitbridge.bluetooth.simulator

import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine.BluetoothIds
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine.BtServiceProfiles.FtmsMachineProfile
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.peripheral.advertisedName
import java.util.UUID

/** Protocol-level identity selected by the debug peripheral simulator. */
sealed interface SimulatorPersona {
    val advertisedName: String

    data class Ftms(val profile: FtmsMachineProfile) : SimulatorPersona {
        override val advertisedName: String = profile.advertisedName
    }

    data class Concept2Pm5(
        val machine: Concept2Machine,
        val firmware: Concept2FirmwareProfile = Concept2FirmwareProfile.PM5_V1_30,
        val serialNumber: String = machine.syntheticSerial,
    ) : SimulatorPersona {
        init {
            require(serialNumber.length == 9 && serialNumber.all(Char::isDigit)) {
                "A PM5 simulator serial number must contain exactly nine digits"
            }
        }

        override val advertisedName: String = "PM5 $serialNumber"
    }

    /** Physical-capture-backed Precor Spinner Chrono Power fixture. */
    data object PrecorChrono : SimulatorPersona {
        override val advertisedName: String = "CHRONO 0"
    }
}

/** Physical PM5 identities; Concept2's internal simulator machine codes are deliberately absent. */
enum class Concept2Machine(
    val displayName: String,
    val wireMachineType: Int,
    val syntheticSerial: String,
) {
    ROW_ERG("RowErg", 0, "900000001"),
    SKI_ERG("SkiErg", 128, "900000002"),
    BIKE_ERG("BikeErg", 192, "900000003"),
}

data class Concept2CharacteristicManifest(
    val uuid: UUID,
    val readable: Boolean = false,
    val writable: Boolean = false,
    val notifiable: Boolean = false,
)

/** Immutable PM5 GATT surface for one evidence-backed firmware profile. */
enum class Concept2FirmwareProfile(
    val displayName: String,
    val hardwareRevision: String,
    val firmwareRevision: String,
    val characteristics: List<Concept2CharacteristicManifest>,
) {
    PM5_V1_30(
        displayName = "PM5 Bluetooth 1.30 baseline (simulated)",
        hardwareRevision = "001",
        firmwareRevision = "V199.99",
        characteristics = listOf(
            c2Notify(BluetoothIds.UUID_C2_GENERAL_STATUS),
            c2Notify(BluetoothIds.UUID_C2_ADDITIONAL_STATUS_1),
            c2Notify(BluetoothIds.UUID_C2_ADDITIONAL_STATUS_2),
            Concept2CharacteristicManifest(
                BluetoothIds.UUID_C2_STATUS_SAMPLE_RATE,
                readable = true,
                writable = true,
            ),
            c2Notify(BluetoothIds.UUID_C2_STROKE_DATA),
            c2Notify(BluetoothIds.UUID_C2_ADDITIONAL_STROKE_DATA),
            c2Notify(BluetoothIds.UUID_C2_SPLIT_INTERVAL_DATA),
            c2Notify(BluetoothIds.UUID_C2_ADDITIONAL_SPLIT_INTERVAL),
            c2Notify(BluetoothIds.UUID_C2_WORKOUT_SUMMARY),
            c2Notify(BluetoothIds.UUID_C2_ADDITIONAL_WORKOUT_SUMMARY),
            c2Notify(BluetoothIds.UUID_C2_HEART_RATE_BELT_INFO),
            c2Notify(BluetoothIds.UUID_C2_ADDITIONAL_WORKOUT_SUMMARY_2),
            c2Notify(BluetoothIds.UUID_C2_FORCE_CURVE_DATA),
            c2Notify(BluetoothIds.UUID_C2_ADDITIONAL_STATUS_3),
            c2Notify(BluetoothIds.UUID_C2_LOGGED_WORKOUT),
            c2Notify(BluetoothIds.UUID_C2_MULTIPLEXED_INFORMATION),
        ),
    );
}

private fun c2Notify(uuid: UUID) = Concept2CharacteristicManifest(uuid, notifiable = true)
