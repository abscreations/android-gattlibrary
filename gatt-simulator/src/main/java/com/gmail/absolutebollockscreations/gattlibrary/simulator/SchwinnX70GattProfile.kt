package com.gmail.absolutebollockscreations.gattlibrary.simulator

import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import com.gmail.absolutebollockscreations.gattlibrary.central.BluetoothIds
import com.gmail.absolutebollockscreations.gattlibrary.central.SCHWINN_X70_INITIALISATION
import com.gmail.absolutebollockscreations.gattlibrary.central.SchwinnX70Measurement
import java.util.UUID
import com.gmail.absolutebollockscreations.gattlibrary.core.ids.SchwinnX70Uuids
import com.gmail.absolutebollockscreations.gattlibrary.core.ids.SigUuids

/**
 * Legacy advertising plan for the Schwinn x70 personas, decoded from the captured advertisement.
 *
 * ```text
 * ADV_IND
 *   Flags                                      05
 *   Incomplete list of 128-bit service UUIDs   3bf58980-3a2f-11e6-9011-0002a5d5c51b
 *   Manufacturer specific                      64 33 db d0 f4 d7 00 80
 *
 * SCAN_RSP
 *   Complete local name                        "SCHWINN 170/270"
 *   Slave connection interval range            20-40 ms
 *   TX power level                             0
 * ```
 *
 * ## Three divergences Android forces, recorded rather than hidden
 *
 * **D-X70-A1** — the capture carries the *incomplete* 128-bit UUID list (`0x06`); `addServiceUuid`
 * always emits the *complete* type (`0x07`). The sixteen UUID octets are identical and no known
 * client keys on the completeness bit.
 *
 * **D-X70-A2** — TX power is the radio's, not ours. The capture reads `00`.
 *
 * **D-X70-A3** — `AdvertiseData` cannot express a slave connection interval range at all, so the
 * 20-40 ms preference is omitted. Nothing decodes it.
 *
 * ## The advertisement is exactly full
 *
 * Flags 3 + UUID 18 + manufacturer 10 = **31 of 31 legacy octets**. One more element and the stack
 * truncates or refuses to start, which is why [advertisingBytes] is computed and asserted rather
 * than assumed.
 */
internal data class SchwinnX70AdvertisingPlan(
    val serviceUuid: UUID,
    val localName: String,
    val includeNameInAdvertising: Boolean,
    val includeNameInScanResponse: Boolean,
    val includeTxPowerInScanResponse: Boolean,
    val manufacturerId: Int,
    val manufacturerPayload: ByteArray,
    val advertisingBytes: Int,
    val scanResponseBytes: Int,
) {
    init {
        require(advertisingBytes <= MAX_LEGACY_BYTES) {
            "the x70 advertisement is already exactly $MAX_LEGACY_BYTES octets"
        }
        require(scanResponseBytes <= MAX_LEGACY_BYTES)
    }

    companion object {
        const val MAX_LEGACY_BYTES = 31

        /**
         * Little-endian `64 33` from the captured Manufacturer Specific Data field.
         *
         * **Not the Nautilus company identifier**, which the PnP ID reports as `0x00F4`. Reproduced
         * because it is what the console sends; understood as nothing. The two captures differ here
         * in a single nibble, and two samples is not a vocabulary.
         */
        const val CAPTURED_MANUFACTURER_ID = 0x3364

        /** The six octets following the company identifier, verbatim. */
        val CAPTURED_MANUFACTURER_PAYLOAD: ByteArray =
            byteArrayOf(0xDB.toByte(), 0xD0.toByte(), 0xF4.toByte(), 0xD7.toByte(), 0x00, 0x80.toByte())

        fun create(): SchwinnX70AdvertisingPlan {
            val name = SCHWINN_X70_ADVERTISED_NAME
            return SchwinnX70AdvertisingPlan(
                serviceUuid = SchwinnX70Uuids.UUID_X70_SERVICE,
                localName = name,
                // The captured name is in the scan response, not the advertisement. A client that
                // does not collect scan responses sees a nameless device advertising one service
                // UUID, which is exactly what a physical console looks like and one of the reasons
                // §7 registers no local-name matcher for this family.
                includeNameInAdvertising = false,
                includeNameInScanResponse = true,
                includeTxPowerInScanResponse = true,
                manufacturerId = CAPTURED_MANUFACTURER_ID,
                manufacturerPayload = CAPTURED_MANUFACTURER_PAYLOAD,
                advertisingBytes = FLAGS_BYTES + UUID128_BYTES +
                    (MANUFACTURER_HEADER_BYTES + CAPTURED_MANUFACTURER_PAYLOAD.size),
                scanResponseBytes = (AD_HEADER_BYTES + name.toByteArray(Charsets.UTF_8).size) +
                    TX_POWER_BYTES,
            )
        }

        private const val AD_HEADER_BYTES = 2
        private const val FLAGS_BYTES = 3
        private const val UUID128_BYTES = 18
        private const val MANUFACTURER_HEADER_BYTES = 4
        private const val TX_POWER_BYTES = 3
    }

    override fun equals(other: Any?): Boolean =
        this === other || (
            other is SchwinnX70AdvertisingPlan &&
                serviceUuid == other.serviceUuid &&
                localName == other.localName &&
                includeNameInAdvertising == other.includeNameInAdvertising &&
                includeNameInScanResponse == other.includeNameInScanResponse &&
                includeTxPowerInScanResponse == other.includeTxPowerInScanResponse &&
                manufacturerId == other.manufacturerId &&
                manufacturerPayload.contentEquals(other.manufacturerPayload) &&
                advertisingBytes == other.advertisingBytes &&
                scanResponseBytes == other.scanResponseBytes
            )

    override fun hashCode(): Int =
        serviceUuid.hashCode() * 31 + manufacturerPayload.contentHashCode()
}

/** One characteristic in the served manifest. */
internal data class SchwinnX70GattCharacteristic(
    val uuid: UUID,
    val properties: Int,
    val notifies: Boolean,
    val readValue: ByteArray? = null,
) {
    override fun equals(other: Any?): Boolean =
        this === other || (
            other is SchwinnX70GattCharacteristic &&
                uuid == other.uuid && properties == other.properties &&
                notifies == other.notifies && readValue.contentEquals(other.readValue)
            )

    override fun hashCode(): Int = uuid.hashCode() * 31 + properties
}

/** One service in the served manifest. */
internal data class SchwinnX70GattService(
    val uuid: UUID,
    val characteristics: List<SchwinnX70GattCharacteristic>,
)

/**
 * The GATT surface every x70 persona serves.
 *
 * ## Eight characteristics, not four
 *
 * The service exposes eight, of which FitBridge decodes three and writes one. The other four are
 * served **silent**: QZ subscribes to `b8066ec0` and `d57cda20` and receives nothing across 19,925
 * captured notifications, `ec865fc0` is never subscribed by anyone, and `35ddd0a0` notifies exactly
 * one invariant four-octet frame per session.
 *
 * Serving them silent is the captured behaviour. Inventing traffic for them would be inventing a
 * protocol, and omitting them would make the simulator's inventory narrower than any real console's.
 *
 * ## What is deliberately absent
 *
 * No FTMS service, no Heart Rate service, no Battery service. None appears in the captured GATT
 * inventory, and a client that finds `0x180F` on an x70 has learned something false about the family.
 */
internal object SchwinnX70GattProfile {

    private const val READ = BluetoothGattCharacteristic.PROPERTY_READ
    private const val WRITE = BluetoothGattCharacteristic.PROPERTY_WRITE
    private const val WRITE_NO_RESPONSE = BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE
    private const val NOTIFY = BluetoothGattCharacteristic.PROPERTY_NOTIFY

    /**
     * The one frame `35ddd0a0` ever sends, and the first notification on the link.
     *
     * Invariant across both captured consoles. Decoded by nobody; emitted because the console emits
     * it, and because a central that assumes its first notification is a measurement is wrong on
     * hardware.
     */
    val AUX_STATUS_FRAME: ByteArray = byteArrayOf(0x01, 0x1F, 0x00, 0x00)

    /** Notify characteristics, in the order a console arms and uses them. */
    val NOTIFY_CHARACTERISTICS: List<UUID> = listOf(
        SchwinnX70Uuids.UUID_X70_AUX_STATUS,
        SchwinnX70Uuids.UUID_X70_MEASUREMENT,
        SchwinnX70Uuids.UUID_X70_SUPPLEMENTAL,
        SchwinnX70Uuids.UUID_X70_SIBLING,
        SchwinnX70Uuids.UUID_X70_UNUSED_B8,
        SchwinnX70Uuids.UUID_X70_UNUSED_D5,
    )

    /** The three that carry measurements. The other four are served and stay quiet. */
    val MEASUREMENT_CHARACTERISTICS: List<UUID> = listOf(
        SchwinnX70Uuids.UUID_X70_MEASUREMENT,
        SchwinnX70Uuids.UUID_X70_SUPPLEMENTAL,
        SchwinnX70Uuids.UUID_X70_SIBLING,
    )

    fun manifest(): List<SchwinnX70GattService> = listOf(
        SchwinnX70GattService(
            SchwinnX70Uuids.UUID_X70_SERVICE,
            listOf(
                // **Write only.** This served both write types until 2026-08-19, on the argument
                // that accepting both cost a property bit and removed a class of failure. That was
                // a reasonable hedge under uncertainty and it is now simply wrong: the 570u
                // inventory lists `write` alone.
                notNotifying(SchwinnX70Uuids.UUID_X70_CONTROL, WRITE),
                notifying(SchwinnX70Uuids.UUID_X70_MEASUREMENT),
                notifying(SchwinnX70Uuids.UUID_X70_SUPPLEMENTAL),
                notifying(SchwinnX70Uuids.UUID_X70_SIBLING),
                // Read **and** notify. Served notify-only until the inventory said otherwise.
                SchwinnX70GattCharacteristic(
                    SchwinnX70Uuids.UUID_X70_AUX_STATUS,
                    READ or NOTIFY,
                    notifies = true,
                    readValue = AUX_STATUS_FRAME,
                ),
                notifying(SchwinnX70Uuids.UUID_X70_UNUSED_B8),
                notifying(SchwinnX70Uuids.UUID_X70_UNUSED_D5),
                // **A write characteristic, served as one.** This was a notify characteristic with
                // a CCCD until 2026-08-19 — a write surface presented as a read surface, which is
                // the kind of error a simulator exists to avoid rather than introduce.
                notNotifying(SchwinnX70Uuids.UUID_X70_WRITE_EC86, WRITE_NO_RESPONSE),
                // Five characteristics nothing reads and nothing decodes. Served readable and
                // empty: present, honest, and inventing no values.
                emptyRead(SchwinnX70Uuids.UUID_X70_READ_E3F9),
                SchwinnX70GattCharacteristic(SchwinnX70Uuids.UUID_X70_RW_5EC4, READ or WRITE, notifies = false),
                emptyRead(SchwinnX70Uuids.UUID_X70_READ_4E34),
                emptyRead(SchwinnX70Uuids.UUID_X70_READ_4ED1),
                emptyRead(SchwinnX70Uuids.UUID_X70_READ_7241),
            ),
        ),
        SchwinnX70GattService(
            SigUuids.UUID_DEVICE_INFORMATION_SERVICE,
            listOf(
                reading(SigUuids.UUID_MANUFACTURER_NAME, SchwinnX70Identity.MANUFACTURER),
                reading(SigUuids.UUID_MODEL_NUMBER, SchwinnX70Identity.MODEL_NUMBER),
                reading(SigUuids.UUID_HARDWARE_REVISION, SchwinnX70Identity.HARDWARE_REVISION),
                reading(SigUuids.UUID_FIRMWARE_REVISION, SchwinnX70Identity.FIRMWARE_REVISION),
                reading(SigUuids.UUID_SOFTWARE_REVISION, SchwinnX70Identity.SOFTWARE_REVISION),
                SchwinnX70GattCharacteristic(
                    SigUuids.UUID_PNP_ID,
                    READ,
                    notifies = false,
                    readValue = SchwinnX70Identity.PNP_ID,
                ),
            ),
        ),
    )

    /** Read-back values by characteristic, for the server's read handler. */
    fun readValues(): Map<UUID, ByteArray> =
        manifest().flatMap { it.characteristics }
            .mapNotNull { characteristic -> characteristic.readValue?.let { characteristic.uuid to it } }
            .toMap()

    fun buildServices(): List<BluetoothGattService> =
        manifest().map { definition ->
            BluetoothGattService(definition.uuid, BluetoothGattService.SERVICE_TYPE_PRIMARY).also { service ->
                definition.characteristics.forEach { spec ->
                    val permissions = when {
                        spec.properties and (WRITE or WRITE_NO_RESPONSE) != 0 ->
                            BluetoothGattCharacteristic.PERMISSION_WRITE
                        else -> BluetoothGattCharacteristic.PERMISSION_READ
                    }
                    val characteristic =
                        BluetoothGattCharacteristic(spec.uuid, spec.properties, permissions)
                    if (spec.notifies) {
                        characteristic.addDescriptor(
                            BluetoothGattDescriptor(
                                SigUuids.UUID_CCCD,
                                BluetoothGattDescriptor.PERMISSION_READ or
                                    BluetoothGattDescriptor.PERMISSION_WRITE,
                            ),
                        )
                    }
                    service.addCharacteristic(characteristic)
                }
            }
        }

    private fun notifying(uuid: UUID) =
        SchwinnX70GattCharacteristic(uuid, NOTIFY, notifies = true)

    private fun notNotifying(uuid: UUID, properties: Int) =
        SchwinnX70GattCharacteristic(uuid, properties, notifies = false)

    /**
     * A readable characteristic the family exposes whose value nothing has ever read.
     *
     * Served with an empty value rather than a plausible one. A simulator that invented content
     * here would be teaching a client something about the protocol that nobody knows.
     */
    private fun emptyRead(uuid: UUID) =
        SchwinnX70GattCharacteristic(uuid, READ, notifies = false, readValue = ByteArray(0))

    private fun reading(uuid: UUID, value: String) =
        SchwinnX70GattCharacteristic(uuid, READ, notifies = false, readValue = value.toByteArray(Charsets.UTF_8))
}

/**
 * The one stateful thing in this protocol: the console sends nothing until `1717b3c0-…` is written.
 *
 * ## Why a server may accept a family of commands where a client may send only one
 *
 * FitBridge's central writes QZ's literal `07 01 D3 00 1F 05 01` because that is the command a
 * physical console was observed accepting. TMIW writes `05 03 D9 00 1F`. Both satisfy the same two
 * structural rules — byte 0 is the total length, and every octet sums to zero mod 256 — and a
 * *server* can accept anything satisfying them where a *client* must pick one specific value.
 *
 * That asymmetry is the V13 lesson from Life Fitness, where four different vendor tokens were
 * observed and the peripheral could absorb all of them while the central had to choose. Accepting
 * the structural family here makes the simulator usable by TMIW and QZ without pretending FitBridge
 * knows which literal is canonical.
 *
 * **It validates and never generates.** [SchwinnX70Measurement.isWellFormedCommand] already exists
 * and is already tested; synthesising a command would be a third value for a field where two
 * already disagree and nothing can choose between them.
 */
internal object SchwinnX70SetupGate {

    /** QZ's literal, which FitBridge's own central sends. */
    val QZ_COMMAND: ByteArray = SCHWINN_X70_INITIALISATION

    /** TMIW's literal, a second unreconciled sample (V17). */
    val TMIW_COMMAND: ByteArray = byteArrayOf(0x05, 0x03, 0xD9.toByte(), 0x00, 0x1F)

    /** Both known literals, for tests and diagnostics. */
    val KNOWN_COMMANDS: List<ByteArray> = listOf(QZ_COMMAND, TMIW_COMMAND)

    /**
     * Whether [command] opens the gate.
     *
     * Accepts either known literal, or any command satisfying the framing rules. Rejects everything
     * else — an empty write, a wrong length prefix, a bad checksum — so a client that writes
     * nonsense gets `GATT_FAILURE` and no stream, which is what a console does.
     */
    fun accepts(command: ByteArray): Boolean =
        KNOWN_COMMANDS.any { it.contentEquals(command) } ||
            SchwinnX70Measurement.isWellFormedCommand(command)
}
