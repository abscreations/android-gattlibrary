package com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine

import android.Manifest
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattService
import androidx.annotation.RequiresPermission
import timber.log.Timber
import java.util.UUID

/**
 * Concept2 PM protocol handler (plan §5a), measurement-only.
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
 * None, permanently, per D11. CSAFE (`0x0020`–`0x0022`) is out of scope for this plan and no
 * later phase adds it, so there is no control seam here and none should be added: CSAFE would
 * be a new machine-adapter capability rather than a change to this handler. No downstream
 * target-setting capability is advertised for this family.
 */
internal class Concept2ProtocolHandler : BtEngineGattCore.ProtocolHandler {

    override val primaryServices: Set<UUID> = setOf(BluetoothIds.UUID_C2_ERG_SERVICE)

    override val extraCharacteristics: Set<UUID> = setOf(
        BluetoothIds.UUID_C2_MULTIPLEXED_INFORMATION,
        BluetoothIds.UUID_C2_GENERAL_STATUS,
        BluetoothIds.UUID_C2_ADDITIONAL_STATUS_1,
        BluetoothIds.UUID_C2_ADDITIONAL_STATUS_2,
        BluetoothIds.UUID_C2_ADDITIONAL_STROKE_DATA,
    )

    private var path: Concept2DeliveryPath? = null
    private var framesReceived = 0
    private var cccdReadyAtMs: Long? = null
    private var fallbackApplied = false
    private var availableCharacteristics: Set<UUID> = emptySet()

    private fun reset() {
        path = null
        framesReceived = 0
        cccdReadyAtMs = null
        fallbackApplied = false
        availableCharacteristics = emptySet()
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
        plan.operations.forEach { operation ->
            characteristics[operation.characteristicUuid]?.let {
                engine.enableNotifications(context, it)
            }
        }
        cccdReadyAtMs = MonotonicMetricClock.SYSTEM.nowMs()
        return true
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    override fun onCharacteristicChanged(
        engine: BtEngine,
        gatt: BluetoothGatt,
        ch: BluetoothGattCharacteristic,
        value: ByteArray,
    ) {
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
     * Called on the engine's periodic tick. Falls back to the individual characteristics when
     * the multiplexed subscription has produced nothing within the liveness window.
     *
     * Safe to arm without unsubscribing `0x0080`: the specification states that an identifier is
     * multiplexed only while the corresponding individual notification is disabled, so these
     * CCCDs withdraw those identifiers from the multiplex rather than duplicating them.
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    internal fun onLivenessTick(engine: BtEngine, gatt: BluetoothGatt) {
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
