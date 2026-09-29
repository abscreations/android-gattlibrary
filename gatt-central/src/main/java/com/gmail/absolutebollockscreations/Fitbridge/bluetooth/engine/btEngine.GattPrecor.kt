package com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine

import android.Manifest
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattService
import androidx.annotation.RequiresPermission
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.core.ActivityType
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.core.FitBridgeBleInternalApi
import timber.log.Timber
import java.util.UUID

/**
 * Precor Spinner Chrono Power protocol handler (plan §5b), measurement-only.
 *
 * ## One characteristic, one subscription
 *
 * [Concept2ProtocolHandler] delegates to a `Concept2Plan` because a PM offers two delivery
 * paths, a fallback between them and a machine-type read that selects the persona. The Chrono
 * offers one notifying characteristic and is a bike whatever it says. [planPrecorDiscovery]
 * still makes that small inventory contract explicit: the handler activates only when the
 * service and measurement characteristic are both present, and it subscribes exactly once.
 *
 * ## Control
 *
 * None, and none is reachable. QZ's `chronobike` declares no write characteristic for this
 * family — its `writeCharacteristic` helper is commented out in the source and its
 * `requestResistance` branch logs and discards — and TMIW's descriptor implements
 * `executeControlOperation` as an error. There is no control point in the frame table and no
 * seam here through which a downstream request could reach the machine. Under D26 that is also
 * the correct posture regardless: this is a family FitBridge cannot test, so it ships
 * measurement only and advertises no target capability downstream.
 */
@FitBridgeBleInternalApi
class PrecorProtocolHandler : BtEngineGattCore.ProtocolHandler {

    override val primaryServices: Set<UUID> = setOf(BluetoothIds.UUID_PRECOR_SERVICE)

    override val extraCharacteristics: Set<UUID> = setOf(BluetoothIds.UUID_PRECOR_MEASUREMENT)

    /**
     * Whether byte 9 has ever been non-zero on this link (divergence V10).
     *
     * Logged once on the transition rather than per frame: the notification path runs at the
     * machine's cadence and a per-frame log line would be noise that hides the one event worth
     * seeing. Reset with the link, because the claim is about this device, not about the app.
     */
    private var cadenceHighOctetSeen = false

    /** Frames rejected by the length/prefix gate on this link generation. */
    private var rejectedFrames = 0

    /** Avoid rewriting the observable device type on every measurement notification. */
    private var deviceClassificationPublished = false

    @FitBridgeBleInternalApi
    fun resetLinkState() {
        cadenceHighOctetSeen = false
        rejectedFrames = 0
        deviceClassificationPublished = false
    }

    @FitBridgeBleInternalApi
    fun recordRejectedFrame() {
        rejectedFrames += 1
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    override fun onConnected(engine: BtEngine, gatt: BluetoothGatt) = resetLinkState()

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    override fun onDisconnected(engine: BtEngine) = resetLinkState()

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    override fun onServicesDiscovered(
        engine: BtEngine,
        gatt: BluetoothGatt,
        services: List<BluetoothGattService>,
    ): Boolean {
        val operation = planPrecorDiscovery(
            services.associate { service ->
                service.uuid to service.characteristics.mapTo(linkedSetOf()) { it.uuid }
            },
        ).singleOrNull() ?: return false
        val context = engine.state.connectionRegistry.contextForGatt(gatt) ?: run {
            Timber.tag(BtEngineState.TAG).w("Precor discovery ignored for unregistered GATT")
            return false
        }

        // The service without its measurement characteristic is not a usable Chrono. Declining
        // to activate leaves the link free for any other handler that does match, which is the
        // §0.7a behaviour: a service UUID is evidence of identity, not a promise of data.
        val measurement = services
            .first { it.uuid == operation.serviceUuid }
            .characteristics
            .first { it.uuid == operation.characteristicUuid }

        Timber.tag(BtEngineState.TAG).i("Precor Chrono activating on %s", gatt.device?.address)
        engine.enableNotifications(context, measurement)
        return true
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    override fun onCharacteristicChanged(
        engine: BtEngine,
        gatt: BluetoothGatt,
        ch: BluetoothGattCharacteristic,
        value: ByteArray,
    ) {
        if (ch.uuid != BluetoothIds.UUID_PRECOR_MEASUREMENT) return

        val reading = decodeNotification(value) ?: return

        if (!cadenceHighOctetSeen && reading.confirmsCadenceIsUint16()) {
            cadenceHighOctetSeen = true
            Timber.tag(BtEngineState.TAG).i(PRECOR_CADENCE_HIGH_OCTET_OBSERVED)
        }

        if (!deviceClassificationPublished) {
            gatt.device?.address?.let { address ->
                publishDeviceClassification(engine.state, address)
            }
        }

        val arrivalMs = MonotonicMetricClock.SYSTEM.nowMs()
        val emitter = engine.metricEmitterFor(
            gatt,
            ch.uuid,
            MetricProtocol.VENDOR,
            MetricSourceClass.VENDOR_NATIVE,
            arrivalMs,
        ) ?: return

        precorSamples(reading).forEach { sample ->
            emitter.emit(
                sample.metric,
                sample.value,
                eventMs = sample.eventMs,
                nativeCumulative = sample.nativeCumulative,
            )
        }
    }

    /**
     * Decode metric-bearing traffic while accepting the captured three-byte companion packet.
     *
     * The exact `24 08 00` value is observed after each measurement on a physical Chrono and
     * carries no established metric. Adjacent short values remain malformed and observable.
     */
    @FitBridgeBleInternalApi
    fun decodeNotification(value: ByteArray): PrecorMeasurement.Reading? {
        if (PrecorMeasurement.isAuxiliaryFrame(value)) return null
        val reading = PrecorMeasurement.parse(value)
        if (reading == null) {
            // Counted, and logged only at verbose. A device serving this service that sends
            // something else is either a Chrono variant nobody has captured or a different
            // machine on the same vendor base; either way the packet is not decodable and the
            // count is what a diagnostic capture needs. Nothing here consumes a rate window,
            // because FitBridge has none — see PrecorMeasurement's note on reject-before-throttle.
            recordRejectedFrame()
            Timber.tag(BtEngineState.TAG).v(
                "Precor frame rejected (len=%d, total=%d)",
                value.size,
                rejectedFrames,
            )
            return null
        }
        return reading
    }

    /**
     * The session persona this machine implies.
     *
     * Unconditional, unlike [Concept2ProtocolHandler.personaFor], which can return null because
     * a PM chassis may be a rower, a ski erg or a bike and the machine has to be asked. The
     * Chrono is one product and it is a spin bike. Returning null here to look symmetrical
     * would discard a fact for the sake of a shape.
     */
    @FitBridgeBleInternalApi
    fun persona(): FusionPersona = FusionPersona.RIDE

    /** Publish the product's fixed bike identity only after the Chrono frame gate has passed. */
    @FitBridgeBleInternalApi
    fun publishDeviceClassification(state: BtEngineState, address: String) {
        state.updateDeviceMachineFeatureByAddress(address, ActivityType.BIKING.ordinal)
        deviceClassificationPublished = true
    }

    /** Frames rejected on this link, for the §11 evidence record and diagnostic capture. */
    @FitBridgeBleInternalApi
    fun rejectedFrameCount(): Int = rejectedFrames
}

/**
 * Pure, production-used discovery contract for the measurement-only Chrono service.
 *
 * Unknown services and characteristics are deliberately ignored. The stable singleton result
 * means callers cannot accidentally subscribe to a vendor characteristic merely because it was
 * added by a later firmware revision.
 */
@FitBridgeBleInternalApi
fun planPrecorDiscovery(
    inventory: Map<UUID, Set<UUID>>,
): List<CccdTarget> {
    val characteristics = inventory[BluetoothIds.UUID_PRECOR_SERVICE] ?: return emptyList()
    if (BluetoothIds.UUID_PRECOR_MEASUREMENT !in characteristics) return emptyList()
    return listOf(
        CccdTarget(
            serviceUuid = BluetoothIds.UUID_PRECOR_SERVICE,
            characteristicUuid = BluetoothIds.UUID_PRECOR_MEASUREMENT,
        ),
    )
}
