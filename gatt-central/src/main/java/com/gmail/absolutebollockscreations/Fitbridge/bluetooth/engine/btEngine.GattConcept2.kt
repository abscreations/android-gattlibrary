package com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine

import android.Manifest
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattService
import androidx.annotation.RequiresPermission
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.core.FitBridgeBleInternalApi
import timber.log.Timber
import java.util.UUID

/**
 * Concept2 PM measurement handler with the user-authorized Just Row startup (plan §5a/D11).
 *
 * ## This class is wiring, on purpose
 *
 * Every decision it would otherwise make lives in a pure function it calls:
 * [planConcept2Discovery] chooses the delivery path, [concept2FallbackDecision] decides when to
 * abandon a silent multiplexed subscription, [Concept2Measurement] decodes, and
 * [concept2Samples] converts. That split is the direct lesson of Phase 4 §11.7 — logic sealed
 * inside a handler needs a live `BluetoothGatt` to reach, so it goes untested, and a defect
 * shipped through exactly that gap. What remains here cannot be unit-tested and therefore
 * should contain nothing worth testing.
 *
 * ## Control
 *
 * Only one fixed Just Row/Prepare startup command is permitted by D11 (2026-09-12).
 * No generic command forwarding, movement targets, or downstream target capabilities exist.
 */
@FitBridgeBleInternalApi
class Concept2ProtocolHandler : BtEngineGattCore.ProtocolHandler {

    override val primaryServices: Set<UUID> = setOf(BluetoothIds.UUID_C2_ERG_SERVICE)

    override val extraCharacteristics: Set<UUID> = setOf(
        BluetoothIds.UUID_C2_MULTIPLEXED_INFORMATION,
        BluetoothIds.UUID_C2_GENERAL_STATUS,
        BluetoothIds.UUID_C2_ADDITIONAL_STATUS_1,
        BluetoothIds.UUID_C2_ADDITIONAL_STATUS_2,
        BluetoothIds.UUID_C2_ADDITIONAL_STROKE_DATA,
        // Read-only identification, not a notification source. Owned so the read callback
        // routes here rather than to whichever handler happens to claim the Device Info
        // service; it is never subscribed.
        BluetoothIds.UUID_C2_MACHINE_TYPE,
        BluetoothIds.UUID_C2_CSAFE_REQUEST,
        BluetoothIds.UUID_C2_CSAFE_RESPONSE,
    )

    private var path: Concept2DeliveryPath? = null
    private var framesReceived = 0
    private var cccdReadyAtMs: Long? = null
    private var fallbackApplied = false
    private var availableCharacteristics: Set<UUID> = emptySet()
    @Volatile private var startup = Concept2StartupHandshake()

    /** Decoded `0x0016`, when the firmware exposes it. Null means "the machine did not say". */
    private var ergFamily: Concept2ErgFamily? = null

    private fun reset() {
        path = null
        framesReceived = 0
        cccdReadyAtMs = null
        fallbackApplied = false
        availableCharacteristics = emptySet()
        ergFamily = null
        startup = Concept2StartupHandshake()
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    override fun onConnected(engine: BtEngine, gatt: BluetoothGatt) = reset()

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    override fun onDisconnected(engine: BtEngine) = reset()

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    override fun onServicesDiscovered(
        engine: BtEngine,
        gatt: BluetoothGatt,
        services: List<BluetoothGattService>,
    ): Boolean {
        val service = services.firstOrNull { it.uuid == BluetoothIds.UUID_C2_ERG_SERVICE }
            ?: return false
        val context = engine.state.connectionRegistry.contextForGatt(gatt) ?: run {
            Timber.tag(BtEngineState.TAG).w("Concept2 discovery ignored for unregistered GATT")
            return false
        }

        val characteristics = service.characteristics.associateBy { it.uuid }
        availableCharacteristics = characteristics.keys
        val plan = planConcept2Discovery(availableCharacteristics)
        if (!plan.activate) {
            Timber.tag(BtEngineState.TAG).w(
                "Concept2 Erg service on %s exposes none of the consumed characteristics",
                gatt.device?.address,
            )
            return false
        }

        path = plan.path
        Timber.tag(BtEngineState.TAG).i(
            "Concept2 activating on %s via %s",
            gatt.device?.address,
            plan.path,
        )
        // Queue response CCCD before the one setup write, and setup before measurement CCCDs.
        // Each operation stays on this physical context's serialized, generation-bound queue.
        val control = services.firstOrNull { it.uuid == BluetoothIds.UUID_C2_PM_CONTROL_SERVICE }
        val request = control?.getCharacteristic(BluetoothIds.UUID_C2_CSAFE_REQUEST)
        val response = control?.getCharacteristic(BluetoothIds.UUID_C2_CSAFE_RESPONSE)
        val mode = concept2StartupWriteMode(request?.properties ?: 0, response?.properties ?: 0,
            response?.getDescriptor(BluetoothIds.UUID_CCCD) != null)
        if (request != null && response != null && mode != null) {
            engine.enableNotifications(context, response)
            val issuedStartup = startup
            issuedStartup.issue(MonotonicMetricClock.SYSTEM.nowMs())?.let { command ->
                engine.pushWrite(context, command, request.uuid, mode) { success ->
                    if (context.isActive && startup === issuedStartup) {
                        issuedStartup.writeCompleted(success)
                        if (!success) Timber.tag(BtEngineState.TAG).w("Concept2 Just Row startup write failed; not retried")
                    }
                }
            }
        } else {
            Timber.tag(BtEngineState.TAG).w("Concept2 startup unavailable: no usable CSAFE request/response surface")
        }
        plan.operations.forEach { operation ->
            characteristics[operation.characteristicUuid]?.let {
                engine.enableNotifications(context, it)
            }
        }
        cccdReadyAtMs = MonotonicMetricClock.SYSTEM.nowMs()

        // Identification, queued only now that activation has succeeded. `0x0016` lives in the
        // Device Information service, so it is a separate discovery and a read rather than a
        // subscription.
        //
        // **Ordering is deliberate and was corrected.** The read was originally queued before
        // the `plan.activate` check, which meant a device exposing the Erg service but none of
        // the consumed characteristics — a handler that returns false and does not drive the
        // link — still had a read placed on the connection's queue. That is an operation issued
        // on behalf of a handler that is not running: harmless in its result, but it occupies
        // the queue, produces a callback that must be routed somewhere, and makes the handler's
        // "I did not activate" return value untrue in its side effects. A subscription and a
        // read now share one precondition.
        //
        // Nothing depends on the answer arriving before the first frame. A late classification
        // simply refines a device row; a session already under way keeps the user's selection.
        services.firstOrNull { it.uuid == BluetoothIds.UUID_C2_DEVICE_INFO_SERVICE }?.let { info ->
            val infoCharacteristics = info.characteristics.associateBy { it.uuid }
            planConcept2Identification(infoCharacteristics.keys).forEach { operation ->
                engine.pushRead(context, operation.characteristicUuid)
            }
        }
        return true
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    override fun onCharacteristicChanged(
        engine: BtEngine,
        gatt: BluetoothGatt,
        ch: BluetoothGattCharacteristic,
        value: ByteArray,
    ) {
        if (ch.uuid == BluetoothIds.UUID_C2_CSAFE_RESPONSE) {
            startup.tick(MonotonicMetricClock.SYSTEM.nowMs())
            if (startup.acceptAcknowledgement(value)) {
                Timber.tag(BtEngineState.TAG).i("Concept2 Just Row startup acknowledged")
            }
            return
        }
        val reading = when (ch.uuid) {
            BluetoothIds.UUID_C2_MULTIPLEXED_INFORMATION -> Concept2Measurement.multiplexed(value)
            BluetoothIds.UUID_C2_GENERAL_STATUS ->
                Concept2Measurement.parse(Concept2Measurement.Id.GENERAL_STATUS, value)
            BluetoothIds.UUID_C2_ADDITIONAL_STATUS_1 ->
                Concept2Measurement.parse(Concept2Measurement.Id.ADDITIONAL_STATUS_1, value)
            BluetoothIds.UUID_C2_ADDITIONAL_STATUS_2 ->
                Concept2Measurement.parse(Concept2Measurement.Id.ADDITIONAL_STATUS_2, value)
            BluetoothIds.UUID_C2_ADDITIONAL_STROKE_DATA ->
                Concept2Measurement.parse(Concept2Measurement.Id.ADDITIONAL_STROKE_DATA, value)
            else -> return
        }

        // A frame arrived on a subscribed characteristic even if it carried an identifier
        // FitBridge does not consume. That still proves the path is alive, so it counts for
        // the fallback decision — otherwise a PM whose multiplex happened to carry only
        // unconsumed identifiers early on would trigger a needless fallback.
        framesReceived++

        if (reading == null) {
            Timber.tag(BtEngineState.TAG).v(
                "Concept2 frame not consumed: %s len=%d",
                ch.uuid,
                value.size,
            )
            return
        }

        val arrivalMs = MonotonicMetricClock.SYSTEM.nowMs()
        val emitter = engine.metricEmitterFor(
            gatt,
            ch.uuid,
            MetricProtocol.VENDOR,
            MetricSourceClass.VENDOR_NATIVE,
            arrivalMs,
        ) ?: return

        concept2Samples(reading).forEach { sample ->
            emitter.emit(
                sample.metric,
                sample.value,
                eventMs = sample.eventMs,
                nativeCumulative = sample.nativeCumulative,
            )
        }
    }

    /**
     * The `0x0016` Erg Machine Type read landing, and the point at which it changes something.
     *
     * This is the vendor's own identification of the ergometer, and the only reliable one: a
     * stock PM advertises `PM5 <serial>` with no discipline in the name, which is why TMIW —
     * whose sole path is the name — prompts the user on every real unit.
     *
     * The decoded family is published to `DiscoveredDevice.machineFeature`, the same field
     * `btEngine.GattFtms` writes after inferring a machine type from its data characteristics.
     * That drives the device row's friendly label and `currentMachineActivity`. It classifies
     * the **device**; it does not start, alter or override a **session**, whose persona comes
     * from the user's selection via `markSessionStarted`.
     *
     * A read that fails or decodes to nothing writes nothing. Leaving the field untouched is
     * the correct behaviour rather than a gap: an absent classification falls back to the name
     * and then to the user's choice, and overwriting a previously-known good value with a guess
     * would be worse than saying nothing.
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    override fun onCharacteristicRead(
        engine: BtEngine,
        gatt: BluetoothGatt,
        ch: BluetoothGattCharacteristic,
        value: ByteArray,
        status: Int,
    ) {
        if (ch.uuid != BluetoothIds.UUID_C2_MACHINE_TYPE) return
        if (status != BluetoothGatt.GATT_SUCCESS) {
            Timber.tag(BtEngineState.TAG).w(
                "Concept2 machine-type read failed (status=%d); classification falls back to the name",
                status,
            )
            return
        }

        ergFamily = decodeConcept2MachineType(value)
        Timber.tag(BtEngineState.TAG).i(
            "Concept2 erg machine type 0x%02X -> %s",
            value.firstOrNull()?.toInt()?.and(0xFF) ?: -1,
            ergFamily ?: "unrecognised",
        )

        val activity = concept2ActivityTypeFor(ergFamily) ?: return
        val address = gatt.device?.address ?: return
        engine.state.updateDeviceMachineFeatureByAddress(address, activity.ordinal)
        val context = engine.state.connectionRegistry.contextForGatt(gatt) ?: return
        engine.state.connectionRegistry.setMachineDeviceType(
            context,
            concept2MachineDeviceTypeFor(ergFamily),
        )
    }

    /**
     * The session persona this machine implies, or null when it has not said.
     *
     * Prefers the `0x0016` read and falls back to the advertised name. Null is an answer — the
     * user's session-mode selection stands — not a failure to classify.
     *
     * Distinct from the classification written in [onCharacteristicRead]: that names what the
     * device *is*, this names how fusion should *rank sources* for it. They agree today and are
     * derived from the same read, but they answer different questions and have different
     * owners, so they are not folded together.
     */
    internal fun personaFor(deviceName: String?): FusionPersona? =
        concept2PersonaFor(machineType = ergFamily, deviceName = deviceName)

    /** The decoded erg family, or null when the machine has not said. Test seam. */
    internal fun ergFamilyOrNull(): Concept2ErgFamily? = ergFamily

    /**
     * Called on the engine's periodic tick. Falls back to the individual characteristics when
     * the multiplexed subscription has produced nothing within the liveness window.
     *
     * Safe to arm without unsubscribing `0x0080`: the specification states that an identifier is
     * multiplexed only while the corresponding individual notification is disabled, so these
     * CCCDs withdraw those identifiers from the multiplex rather than duplicating them.
     *
     * **This was `internal fun` rather than `override fun` until 2026-08-14, and nothing called
     * it.** The interface had no tick, so D41's fallback was decoded, unit-tested and unreachable
     * for two phases: a PM whose multiplexed subscription went silent stayed silent for the whole
     * session. Making it an override is the fix; the seam that delivers the tick is the part that
     * stops the next handler repeating it.
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    override fun onLivenessTick(engine: BtEngine, gatt: BluetoothGatt) {
        val startupBefore = startup.state
        startup.tick(MonotonicMetricClock.SYSTEM.nowMs())
        if (startupBefore != startup.state && startup.state == Concept2StartupHandshake.State.TIMED_OUT) {
            Timber.tag(BtEngineState.TAG).w("Concept2 Just Row startup acknowledgement timed out; not retried")
        }
        if (fallbackApplied) return
        val activePath = path ?: return
        val readyAt = cccdReadyAtMs ?: return
        val elapsed = MonotonicMetricClock.SYSTEM.nowMs() - readyAt
        if (!concept2FallbackDecision(activePath, framesReceived, elapsed)) return

        val context = engine.state.connectionRegistry.contextForGatt(gatt) ?: return
        val service = gatt.getService(BluetoothIds.UUID_C2_ERG_SERVICE) ?: return
        val characteristics = service.characteristics.associateBy { it.uuid }
        val operations = planConcept2IndividualFallback(availableCharacteristics)
        if (operations.isEmpty()) {
            Timber.tag(BtEngineState.TAG).w(
                "Concept2 multiplexed silent for %d ms and no individual characteristics exist",
                elapsed,
            )
            fallbackApplied = true
            return
        }

        Timber.tag(BtEngineState.TAG).w(
            "Concept2 multiplexed silent for %d ms; falling back to %d individual characteristic(s)",
            elapsed,
            operations.size,
        )
        operations.forEach { operation ->
            characteristics[operation.characteristicUuid]?.let {
                engine.enableNotifications(context, it)
            }
        }
        path = Concept2DeliveryPath.INDIVIDUAL
        fallbackApplied = true
    }
}
