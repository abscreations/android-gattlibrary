package com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine

import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.capture.BleCapture
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.capture.CapturedCharacteristic
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.capture.CapturedService
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.capture.CccdEvent
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.capture.ConnectEvent
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.capture.DisconnectEvent
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.capture.GattInventoryEvent
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.capture.NotifyEvent
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.capture.ReadEvent
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.capture.RedactionReason
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.capture.SampleEvent
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.capture.ScanEvent
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.capture.WriteEvent
import java.util.UUID

/**
 * One-line call sites for the §0.9 capture channel, so the GATT callbacks stay
 * readable and every event is built the same way.
 *
 * These exist because the capture channel shipped with a schema, a ring buffer,
 * arming, routing and export — and almost nothing feeding it. Arming a capture
 * produced an empty file, because ten of the thirteen event types had no
 * emitter. This file is the missing input side.
 *
 * ### Cost
 *
 * Every hook runs on a GATT callback thread. Disarmed, an event is one small
 * object appended to a bounded in-memory ring; armed, it is a non-blocking
 * enqueue. Neither touches disk on this thread. §0.9.5 requires buffering even
 * when disarmed, because the retroactive flush is the entire value of
 * trigger-armed capture: a capture that starts at the trigger records the
 * symptom and none of the cause.
 *
 * ### Redaction
 *
 * Writes go through [WriteEvent.of], never the constructor, so a characteristic
 * registered in `SensitiveCharacteristics` cannot produce a payload-carrying
 * event by any route. Vendor setup sequences that write profile data pass their
 * own [RedactionReason] instead.
 */
internal object CaptureHooks {

    /** One advertisement, payloads unparsed per §0.9.4. */
    fun scan(
        name: String?,
        rssi: Int,
        serviceUuids: List<UUID>,
        manufacturerData: Map<Int, ByteArray>,
        serviceData: Map<UUID, ByteArray>,
    ) {
        BleCapture.emit(
            ScanEvent(
                name = name,
                rssi = rssi,
                serviceUuids = serviceUuids.map { it.toString() },
                manufacturerData = manufacturerData,
                serviceData = serviceData.mapKeys { it.key.toString() },
            ),
        )
    }

    fun connected(connectionId: ConnectionId) {
        BleCapture.emit(
            ConnectEvent(
                conn = BleCapture.labelFor(connectionId.address),
                generation = connectionId.linkGeneration.toInt(),
            ),
        )
    }

    fun disconnected(connectionId: ConnectionId, reason: String) {
        BleCapture.emit(
            DisconnectEvent(
                conn = BleCapture.labelFor(connectionId.address),
                reason = reason,
                generation = connectionId.linkGeneration.toInt(),
            ),
        )
    }

    /**
     * The full GATT database, once per connection.
     *
     * §0.9.1 calls this the single most useful event for triage: "it connects but
     * shows nothing" is usually answered by looking at what the device actually
     * exposes, and no other event carries that.
     */
    fun gattInventory(connectionId: ConnectionId, gatt: BluetoothGatt) {
        val services = runCatching {
            gatt.services.orEmpty().map { service ->
                CapturedService(
                    uuid = service.uuid.toString(),
                    characteristics = service.characteristics.orEmpty().map { characteristic ->
                        CapturedCharacteristic(
                            uuid = characteristic.uuid.toString(),
                            // Raw bitmask, not a decoded list: a decode can be
                            // wrong later, the bitmask cannot.
                            properties = characteristic.properties,
                            descriptors = characteristic.descriptors.orEmpty()
                                .map { it.uuid.toString() },
                        )
                    },
                )
            }
        }.getOrDefault(emptyList())
        if (services.isEmpty()) return
        BleCapture.emit(
            GattInventoryEvent(conn = BleCapture.labelFor(connectionId.address), services = services),
        )
    }

    fun cccd(
        connectionId: ConnectionId,
        descriptor: BluetoothGattDescriptor,
        enabled: Boolean,
        result: String,
    ) {
        BleCapture.emit(
            CccdEvent(
                conn = BleCapture.labelFor(connectionId.address),
                characteristic = runCatching { descriptor.characteristic.uuid.toString() }
                    .getOrDefault("unknown"),
                enabled = enabled,
                result = result,
            ),
        )
    }

    fun notified(connectionId: ConnectionId, characteristic: UUID, payload: ByteArray) {
        BleCapture.emit(
            NotifyEvent(
                conn = BleCapture.labelFor(connectionId.address),
                characteristic = characteristic.toString(),
                payload = payload.copyOf(),
            ),
        )
    }

    fun read(connectionId: ConnectionId, characteristic: UUID, payload: ByteArray) {
        BleCapture.emit(
            ReadEvent(
                conn = BleCapture.labelFor(connectionId.address),
                characteristic = characteristic.toString(),
                payload = payload.copyOf(),
            ),
        )
    }

    /**
     * A characteristic write.
     *
     * [redactAs] is for payloads whose sensitivity is known at the call site
     * rather than from the characteristic UUID — the Life Fitness user service
     * case, whose UUIDs are discovered at runtime. It can add redaction, never
     * remove it.
     */
    fun wrote(
        connectionId: ConnectionId,
        characteristic: UUID,
        payload: ByteArray,
        withoutResponse: Boolean,
        redactAs: RedactionReason? = null,
    ) {
        BleCapture.emit(
            WriteEvent.of(
                conn = BleCapture.labelFor(connectionId.address),
                characteristic = characteristic,
                payload = payload,
                withoutResponse = withoutResponse,
                redactAs = redactAs,
            ),
        )
    }

    /**
     * A decoded metric, recorded after parsing so the bytes that produced it can
     * be correlated with the value. That pairing is what makes a capture
     * convertible into a §5 fixture rather than merely readable.
     */
    fun sample(sample: MetricSample) {
        BleCapture.emit(
            SampleEvent(
                conn = BleCapture.labelFor(sample.connectionId.address),
                sourceId = "${sample.protocol}:${sample.sourceId.characteristicUuid}",
                metric = sample.metric.name,
                value = sample.value,
                unit = sample.unit.name,
                quality = sample.quality.name,
            ),
        )
    }
}
