package com.gmail.absolutebollockscreations.gattlibrary.simulator

import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import com.gmail.absolutebollockscreations.gattlibrary.central.BluetoothIds
import java.util.UUID
import com.gmail.absolutebollockscreations.gattlibrary.peripheral.advertisedName

/**
 * Advertising and GATT plans for the Stryd footpod persona.
 *
 * See `docs/stryd-footpod-simulator-implementation.md` for the attributed wire specification and
 * the evidence record. Two things about this plan are worth stating where they will be read:
 *
 * **The advertisement is partly undetermined and the persona does not pretend otherwise.** Four
 * captures agree on the local name and on manufacturer company ID `0xAAAA`, and all four report an
 * *empty* advertised service UUID list. The captures cannot distinguish "Stryd advertises no
 * service UUIDs" from "CoreBluetooth dropped them", so this persona advertises the two service
 * UUIDs it actually serves: a footpod nobody can discover by service is useless as a test target,
 * and over-advertising is the failure that shows up immediately rather than the one that hides.
 * The manufacturer payload goes the other way - the six octets are a per-unit identifier that was
 * redacted out of the corpus, so nothing is advertised under `0xAAAA` rather than six invented
 * bytes.
 *
 * **Handles cannot be pinned on Android.** The captures give a handle map that was identical on
 * all four units, and a client caching handles across reconnects would benefit from it, but
 * `BluetoothGattServer` allocates handles itself. Services are added in the captured relative
 * order so the layout is as close as the platform permits, and the specification records the
 * captured map for anyone porting this to a stack that can honour it.
 */
internal data class StrydFootpodAdvertisingPlan(
    val localName: String,
    val serviceUuids: List<UUID>,
    val includeNameInAdvertising: Boolean,
    val advertisingBytes: Int,
) {
    init {
        require(advertisingBytes <= MAX_LEGACY_BYTES) {
            "Stryd footpod advertising must fit one legacy PDU"
        }
    }

    companion object {
        const val MAX_LEGACY_BYTES = 31

        fun create(): StrydFootpodAdvertisingPlan {
            val name = SimulatorPersona.StrydFootpod.advertisedName
            val uuids = listOf(BluetoothIds.UUID_RSC_SERVICE, BluetoothIds.UUID_POWER_SERVICE)
            return StrydFootpodAdvertisingPlan(
                localName = name,
                serviceUuids = uuids,
                includeNameInAdvertising = true,
                // flags (3) + complete 16-bit UUID list (2 header + 2 per UUID) + name (2 + len)
                advertisingBytes = 3 + 2 + uuids.size * 2 + 2 + name.toByteArray(Charsets.UTF_8).size,
            )
        }

        /**
         * Captured on all four units, recorded for the specification and deliberately not
         * advertised: the six-octet payload that accompanies it is a per-unit identifier and was
         * never part of the sanitized corpus. `0xAAAA` is also not a SIG-assigned member ID.
         */
        const val CAPTURED_MANUFACTURER_ID = 0xAAAA
    }
}

/**
 * Client-compatible manufacturer identity with openly synthetic model and revision fields.
 *
 * No capture in the corpus ever read 0x2A29/0x2A24/0x2A26/0x2A28, so there is nothing to reproduce
 * and the plan forbids presenting the other values as captured. The manufacturer was changed to
 * `Stryd` after both validation clients rejected `FitBridge` immediately after reading 0x2A29.
 * The remaining fields follow the house convention for a declared simulator identity, and the
 * `9` prefix marks the serial as synthetic.
 */
internal object StrydFootpodIdentity {
    const val MANUFACTURER_NAME = "Stryd"
    const val MODEL_NUMBER = "Footpod persona"
    const val FIRMWARE_REVISION = "sim-1"
    const val SOFTWARE_REVISION = "sim-1"
    const val SERIAL_NUMBER = "900000004"
}

internal object StrydFootpodGattProfile {

    val UUID_PROPRIETARY_SERVICE_A: UUID = UUID.fromString("7e78aa18-72cd-d3b8-a81f-5b7e589bea0f")
    val UUID_PROPRIETARY_MEASUREMENT_A: UUID = UUID.fromString("7e78aa19-72cd-d3b8-a81f-5b7e589bea0f")
    val UUID_PROPRIETARY_SERVICE_B: UUID = UUID.fromString("3fe7aa12-44e3-cdb5-fc08-7db6a30b0131")
    val UUID_PROPRIETARY_MEASUREMENT_B: UUID = UUID.fromString("3fe7aa13-44e3-cdb5-fc08-7db6a30b0131")

    /**
     * Services in the captured relative order (proprietary B, battery, RSC, proprietary A,
     * device information, cycling power).
     *
     * Their captured notifying characteristics are present for client fingerprint compatibility,
     * but this profile invents no proprietary frames: `3fe7aa13-…` fired zero times in all five
     * captures, while most fields in `7e78aa19-…` remain unresolved. The captured `7e78aa20-…`
     * characteristic stays absent because neither its properties nor its behavior are known.
     */
    fun buildServices(): List<BluetoothGattService> = listOf(
        buildProprietaryServiceB(),
        buildBatteryService(),
        buildRscService(),
        buildProprietaryServiceA(),
        buildDeviceInformationService(),
        buildCyclingPowerService(),
    )

    fun buildProprietaryServiceA(): BluetoothGattService =
        primary(UUID_PROPRIETARY_SERVICE_A).apply {
            addCharacteristic(notify(UUID_PROPRIETARY_MEASUREMENT_A))
        }

    fun buildProprietaryServiceB(): BluetoothGattService =
        primary(UUID_PROPRIETARY_SERVICE_B).apply {
            addCharacteristic(notify(UUID_PROPRIETARY_MEASUREMENT_B))
        }

    fun buildBatteryService(): BluetoothGattService = primary(BluetoothIds.UUID_BATTERY_SERVICE).apply {
        addCharacteristic(readNotify(BluetoothIds.UUID_BATTERY_LEVEL))
    }

    fun buildRscService(): BluetoothGattService = primary(BluetoothIds.UUID_RSC_SERVICE).apply {
        addCharacteristic(notify(BluetoothIds.UUID_RSC_MEASUREMENT))
        addCharacteristic(readOnly(BluetoothIds.UUID_RSC_FEATURE))
    }

    /**
     * Cycling Power without the Control Point.
     *
     * 0x2A66 exists on the captured units, but it was never subscribed and never written in any
     * capture, so there is no reference behaviour for a single procedure. Publishing a control
     * point this persona could not answer honestly would breach the downstream-honesty invariant;
     * an absent one is visibly absent.
     */
    fun buildCyclingPowerService(): BluetoothGattService =
        primary(BluetoothIds.UUID_CYCLING_POWER_SERVICE).apply {
            addCharacteristic(notify(BluetoothIds.UUID_CYCLING_POWER_MEASUREMENT))
            addCharacteristic(readOnly(BluetoothIds.UUID_CYCLING_POWER_FEATURE))
            addCharacteristic(readOnly(BluetoothIds.UUID_SENSOR_LOCATION))
        }

    fun buildDeviceInformationService(): BluetoothGattService =
        primary(BluetoothIds.UUID_DEVICE_INFORMATION_SERVICE).apply {
            addCharacteristic(readOnly(BluetoothIds.UUID_MANUFACTURER_NAME))
            addCharacteristic(readOnly(BluetoothIds.UUID_MODEL_NUMBER))
            addCharacteristic(readOnly(BluetoothIds.UUID_FIRMWARE_REVISION))
            addCharacteristic(readOnly(BluetoothIds.UUID_SOFTWARE_REVISION))
        }

    /** Static read values, by characteristic, for the read-request handler. */
    fun staticReadValues(): Map<UUID, ByteArray> = mapOf(
        BluetoothIds.UUID_MANUFACTURER_NAME to StrydFootpodIdentity.MANUFACTURER_NAME.toByteArray(Charsets.UTF_8),
        BluetoothIds.UUID_MODEL_NUMBER to StrydFootpodIdentity.MODEL_NUMBER.toByteArray(Charsets.UTF_8),
        BluetoothIds.UUID_FIRMWARE_REVISION to StrydFootpodIdentity.FIRMWARE_REVISION.toByteArray(Charsets.UTF_8),
        BluetoothIds.UUID_SOFTWARE_REVISION to StrydFootpodIdentity.SOFTWARE_REVISION.toByteArray(Charsets.UTF_8),
    )

    /** Notifiable endpoints this persona accepts subscriptions for, paired with their services. */
    val notifyingCharacteristics: List<Pair<UUID, UUID>> = listOf(
        UUID_PROPRIETARY_SERVICE_B to UUID_PROPRIETARY_MEASUREMENT_B,
        BluetoothIds.UUID_RSC_SERVICE to BluetoothIds.UUID_RSC_MEASUREMENT,
        UUID_PROPRIETARY_SERVICE_A to UUID_PROPRIETARY_MEASUREMENT_A,
        BluetoothIds.UUID_CYCLING_POWER_SERVICE to BluetoothIds.UUID_CYCLING_POWER_MEASUREMENT,
        BluetoothIds.UUID_BATTERY_SERVICE to BluetoothIds.UUID_BATTERY_LEVEL,
    )

    private fun primary(uuid: UUID) =
        BluetoothGattService(uuid, BluetoothGattService.SERVICE_TYPE_PRIMARY)

    private fun cccd() = BluetoothGattDescriptor(
        BluetoothIds.UUID_CCCD,
        BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE,
    )

    private fun notify(uuid: UUID) = BluetoothGattCharacteristic(
        uuid,
        BluetoothGattCharacteristic.PROPERTY_NOTIFY,
        0,
    ).apply { addDescriptor(cccd()) }

    private fun readNotify(uuid: UUID) = BluetoothGattCharacteristic(
        uuid,
        BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
        BluetoothGattCharacteristic.PERMISSION_READ,
    ).apply { addDescriptor(cccd()) }

    private fun readOnly(uuid: UUID) = BluetoothGattCharacteristic(
        uuid,
        BluetoothGattCharacteristic.PROPERTY_READ,
        BluetoothGattCharacteristic.PERMISSION_READ,
    )
}
