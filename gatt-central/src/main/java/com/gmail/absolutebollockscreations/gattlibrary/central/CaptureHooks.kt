package com.gmail.absolutebollockscreations.gattlibrary.central

import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import com.gmail.absolutebollockscreations.gattlibrary.core.capture.BleCapture
import com.gmail.absolutebollockscreations.gattlibrary.core.capture.AdvertisementSourceEvent
import com.gmail.absolutebollockscreations.gattlibrary.core.capture.CapturedCharacteristic
import com.gmail.absolutebollockscreations.gattlibrary.core.capture.CapturedService
import com.gmail.absolutebollockscreations.gattlibrary.core.capture.CaptureTrigger
import com.gmail.absolutebollockscreations.gattlibrary.core.capture.CccdEvent
import com.gmail.absolutebollockscreations.gattlibrary.core.capture.ConnectEvent
import com.gmail.absolutebollockscreations.gattlibrary.core.capture.DisconnectEvent
import com.gmail.absolutebollockscreations.gattlibrary.core.capture.GattInventoryEvent
import com.gmail.absolutebollockscreations.gattlibrary.core.capture.NotifyEvent
import com.gmail.absolutebollockscreations.gattlibrary.core.capture.ReadEvent
import com.gmail.absolutebollockscreations.gattlibrary.core.capture.RedactionReason
import com.gmail.absolutebollockscreations.gattlibrary.core.capture.SampleEvent
import com.gmail.absolutebollockscreations.gattlibrary.core.capture.ScanEvent
import com.gmail.absolutebollockscreations.gattlibrary.core.capture.WriteEvent
import com.gmail.absolutebollockscreations.gattlibrary.core.GattLibraryInternalApi
import java.util.UUID
import com.gmail.absolutebollockscreations.gattlibrary.core.catalog.MachineFamilyIds

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
@GattLibraryInternalApi
object CaptureHooks {

    fun advertisementSource(
        sourceKey: String?,
        systemId: Int?,
        firmware: String?,
        generation: Long,
        state: KeiserSourceState,
        decision: String,
        freshnessMs: Long?,
    ) {
        BleCapture.emit(
            AdvertisementSourceEvent(
                conn = sourceKey?.let { BleCapture.labelFor("advertisement:$it") },
                family = "keiser_m_series",
                candidate = MachineFamilyIds.KEISER_M_SERIES,
                systemId = systemId,
                firmware = firmware,
                generation = generation,
                state = state.name.lowercase(),
                decision = decision.lowercase(),
                freshnessMs = freshnessMs,
            ),
        )
    }

    /** Arms the retroactive ring when a protocol parser rejects a structurally invalid frame. */
    fun parserRejection() {
        BleCapture.arm(CaptureTrigger.PARSER_REJECTION)
    }

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
        val originLabel = when (val origin = sample.originId) {
            is MetricOriginId.Gatt -> BleCapture.labelFor(origin.connectionId.address)
            is MetricOriginId.Advertisement ->
                BleCapture.labelFor("advertisement:${origin.sourceKey}")
        }
        val endpointLabel = when (val endpoint = sample.sourceId.endpointId) {
            is MetricEndpointId.GattCharacteristic -> endpoint.uuid.toString()
            is MetricEndpointId.ManufacturerData ->
                "manufacturer:${endpoint.companyId.toString(16).padStart(4, '0')}"
        }
        BleCapture.emit(
            SampleEvent(
                conn = originLabel,
                sourceId = "${sample.protocol}:$endpointLabel",
                metric = sample.metric.name,
                value = sample.value,
                unit = sample.unit.name,
                quality = sample.quality.name,
            ),
        )
    }

    /**
     * A sample failed its §11b plausibility bound and was dropped.
     *
     * Arms the ring, which is the point: a bound firing means a field was decoded wrongly, and
     * `CaptureTrigger.PARSER_REJECTION` exists precisely so the packets that produced it are
     * already in the buffer when the user reports the symptom. §0.9.5's retroactive flush is
     * what makes that worth anything — a capture starting at the trigger records the
     * consequence and none of the cause.
     *
     * The sample itself is still emitted, so the capture shows what was decoded next to the
     * bytes it came from. A rejected value is more diagnostic than an accepted one, not less;
     * it just must not reach the user's session.
     */
    fun boundsRejection(sample: MetricSample, bound: String) {
        BleCapture.arm(CaptureTrigger.PARSER_REJECTION)
        sample(sample)
    }
}
