package com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine

import android.Manifest
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattService
import androidx.annotation.RequiresPermission
import timber.log.Timber
import java.util.UUID

internal data class FlywheelCharacteristicInventory(
    val properties: Int,
    val hasCccd: Boolean,
)

internal data class FlywheelGattInventory(
    val services: Map<UUID, Map<UUID, FlywheelCharacteristicInventory>>,
)

internal enum class FlywheelDiscoveryFailure {
    ADVERTISEMENT_IDENTITY_NOT_CONFIRMED,
    MISSING_SERVICE,
    MISSING_TX,
    TX_NOT_NOTIFY,
    TX_MISSING_CCCD,
    MISSING_RX,
    RX_NOT_WRITABLE,
}

internal enum class FlywheelGattOperationKind { ENABLE_NOTIFICATIONS }

internal data class FlywheelGattOperation(
    val kind: FlywheelGattOperationKind,
    val characteristicUuid: UUID,
)

internal data class FlywheelDiscoveryPlan(
    val activate: Boolean,
    val failure: FlywheelDiscoveryFailure?,
    val operations: List<FlywheelGattOperation>,
)

/**
 * Pure, exact post-connect admission decision for Flywheel's Nordic UART surface.
 *
 * Nordic UART is not a device identity. The scan-stage name/service conjunction must have admitted
 * this address before the exact service, endpoints, properties, and TX CCCD can confirm it here.
 * RX is deliberately required but produces no operation: no command vocabulary is evidenced.
 */
internal fun planFlywheelDiscovery(
    advertisementCandidateId: String?,
    inventory: FlywheelGattInventory,
): FlywheelDiscoveryPlan {
    fun reject(reason: FlywheelDiscoveryFailure) = FlywheelDiscoveryPlan(false, reason, emptyList())

    if (advertisementCandidateId != FLYWHEEL_CANDIDATE_ID) {
        return reject(FlywheelDiscoveryFailure.ADVERTISEMENT_IDENTITY_NOT_CONFIRMED)
    }
    val characteristics = inventory.services[BluetoothIds.UUID_FLYWHEEL_SERVICE]
        ?: return reject(FlywheelDiscoveryFailure.MISSING_SERVICE)
    val tx = characteristics[BluetoothIds.UUID_FLYWHEEL_TX]
        ?: return reject(FlywheelDiscoveryFailure.MISSING_TX)
    if (tx.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY == 0) {
        return reject(FlywheelDiscoveryFailure.TX_NOT_NOTIFY)
    }
    if (!tx.hasCccd) return reject(FlywheelDiscoveryFailure.TX_MISSING_CCCD)

    val rx = characteristics[BluetoothIds.UUID_FLYWHEEL_RX]
        ?: return reject(FlywheelDiscoveryFailure.MISSING_RX)
    val writeProperties = BluetoothGattCharacteristic.PROPERTY_WRITE or
        BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE
    if (rx.properties and writeProperties == 0) {
        return reject(FlywheelDiscoveryFailure.RX_NOT_WRITABLE)
    }

    return FlywheelDiscoveryPlan(
        activate = true,
        failure = null,
        operations = listOf(
            FlywheelGattOperation(
                kind = FlywheelGattOperationKind.ENABLE_NOTIFICATIONS,
                characteristicUuid = BluetoothIds.UUID_FLYWHEEL_TX,
            ),
        ),
    )
}

internal fun List<BluetoothGattService>.toFlywheelInventory(): FlywheelGattInventory =
    FlywheelGattInventory(
        services = associate { service ->
            service.uuid to service.characteristics.orEmpty().associate { characteristic ->
                characteristic.uuid to FlywheelCharacteristicInventory(
                    properties = characteristic.properties,
                    hasCccd = characteristic.getDescriptor(BluetoothIds.UUID_CCCD) != null,
                )
            }
        },
    )

internal enum class FlywheelSetupState { IDLE, SUBSCRIBING, READY, FAILED, CANCELLED }

/** Pure connection-generation gate for bounded TX subscription setup. */
internal class FlywheelSubscriptionGate(
    val connectionId: ConnectionId,
    private val timeoutMs: Long,
) {
    init {
        require(timeoutMs > 0L)
    }

    var state: FlywheelSetupState = FlywheelSetupState.IDLE
        private set

    private var deadlineMs: Long? = null

    fun begin(nowMs: Long): Boolean {
        if (state != FlywheelSetupState.IDLE) return false
        deadlineMs = if (nowMs > Long.MAX_VALUE - timeoutMs) Long.MAX_VALUE else nowMs + timeoutMs
        state = FlywheelSetupState.SUBSCRIBING
        return true
    }

    fun markReady(callbackConnectionId: ConnectionId, nowMs: Long): Boolean {
        if (callbackConnectionId != connectionId || state != FlywheelSetupState.SUBSCRIBING) return false
        val deadline = deadlineMs ?: return false
        if (nowMs >= deadline) {
            state = FlywheelSetupState.FAILED
            deadlineMs = null
            return false
        }
        state = FlywheelSetupState.READY
        deadlineMs = null
        return true
    }

    fun tick(nowMs: Long): Boolean {
        val deadline = deadlineMs ?: return false
        if (state != FlywheelSetupState.SUBSCRIBING || nowMs < deadline) return false
        state = FlywheelSetupState.FAILED
        deadlineMs = null
        return true
    }

    fun cancel() {
        deadlineMs = null
        state = FlywheelSetupState.CANCELLED
    }
}

/**
 * Flywheel handler: exact identity, one TX CCCD, and measurement-only telemetry.
 *
 * There is no transport writer, control session, keepalive, setup payload, or operation targeting
 * RX anywhere in this handler. One dropout filter belongs to this handler/link generation and is
 * discarded on disconnect before another generation can emit.
 */
internal class FlywheelProtocolHandler : BtEngineGattCore.ProtocolHandler {
    override val primaryServices: Set<UUID> = setOf(BluetoothIds.UUID_FLYWHEEL_SERVICE)
    override val extraCharacteristics: Set<UUID> = setOf(
        BluetoothIds.UUID_FLYWHEEL_TX,
        BluetoothIds.UUID_FLYWHEEL_RX,
    )

    private var setupGate: FlywheelSubscriptionGate? = null
    private var structuralMatch = false
    private var dropoutFilter = FlywheelDropoutFilter()
    private var syntheticMetrics = FlywheelSyntheticMetrics()

    internal fun setupState(): FlywheelSetupState = setupGate?.state ?: FlywheelSetupState.IDLE
    internal fun hasStructuralMatch(): Boolean = structuralMatch
    internal fun recordStructuralMatch() {
        structuralMatch = true
    }

    override fun onConnected(engine: BtEngine, gatt: BluetoothGatt) {
        resetLinkState()
    }

    override fun onDisconnected(engine: BtEngine) {
        resetLinkState()
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    override fun onServicesDiscovered(
        engine: BtEngine,
        gatt: BluetoothGatt,
        services: List<BluetoothGattService>,
    ): Boolean {
        val context = engine.state.connectionRegistry.contextForGatt(gatt) ?: return false
        val candidateId = engine.state.discoveredDevices.value.values.firstOrNull { device ->
            canonicalScanAddress(device.address) == canonicalScanAddress(context.address)
        }?.matchCandidateId
        val plan = planFlywheelDiscovery(candidateId, services.toFlywheelInventory())
        if (!plan.activate) {
            Timber.tag(BtEngineState.TAG).w(
                "Flywheel discovery declined connection=%s reason=%s",
                context.connectionId,
                plan.failure,
            )
            resetLinkState()
            return false
        }

        val service = services.first { it.uuid == BluetoothIds.UUID_FLYWHEEL_SERVICE }
        val tx = requireNotNull(service.getCharacteristic(BluetoothIds.UUID_FLYWHEEL_TX))
        structuralMatch = true
        setupGate = FlywheelSubscriptionGate(context.connectionId, SUBSCRIPTION_TIMEOUT_MS).also {
            it.begin(MonotonicMetricClock.SYSTEM.nowMs())
        }
        plan.operations.forEach { operation ->
            when (operation.kind) {
                FlywheelGattOperationKind.ENABLE_NOTIFICATIONS ->
                    engine.enableNotifications(context, tx)
            }
        }
        return true
    }

    override fun onCccdPlanReady(engine: BtEngine, gatt: BluetoothGatt) {
        val context = engine.state.connectionRegistry.contextForGatt(gatt) ?: return
        if (!context.cccd.ready.value) return
        val nowMs = MonotonicMetricClock.SYSTEM.nowMs()
        if (setupGate?.markReady(context.connectionId, nowMs) == true) {
            engine.state.adapterLiveness.setupSucceeded(context, nowMs)
        }
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    override fun onCharacteristicChanged(
        engine: BtEngine,
        gatt: BluetoothGatt,
        ch: BluetoothGattCharacteristic,
        value: ByteArray,
    ) {
        if (ch.uuid != BluetoothIds.UUID_FLYWHEEL_TX) return
        val context = engine.state.connectionRegistry.contextForGatt(gatt) ?: return
        val gate = setupGate ?: return
        if (gate.connectionId != context.connectionId || gate.state != FlywheelSetupState.READY) return

        val decoded = FlywheelMeasurement.decode(value)
        if (decoded !is FlywheelDecodeResult.Decoded) {
            Timber.tag(BtEngineState.TAG).w(
                "Flywheel notification rejected connection=%s reason=%s len=%d",
                context.connectionId,
                (decoded as FlywheelDecodeResult.Rejected).reason,
                value.size,
            )
            return
        }
        val arrivalMs = MonotonicMetricClock.SYSTEM.nowMs()
        val nativeEmitter = engine.metricEmitterFor(
            gatt,
            ch.uuid,
            MetricProtocol.VENDOR,
            MetricSourceClass.VENDOR_NATIVE,
            arrivalMs,
        ) ?: return
        val raw = decoded.reading
        val rawPowerPlausible = PlausibilityBounds.isPlausible(
            MetricKind.POWER,
            FusionPersona.RIDE,
            raw.powerWatts.toDouble(),
        )
        val rawCadencePlausible = PlausibilityBounds.isPlausible(
            MetricKind.CADENCE,
            FusionPersona.RIDE,
            raw.cadenceRpm.toDouble(),
        )
        if (!rawPowerPlausible || !rawCadencePlausible) {
            // Submission is intentional even for the bad field: fusion drops it and arms the
            // standard bounds diagnostic. No filter/estimator state is touched by this frame.
            nativeEmitter.emit(MetricKind.POWER, raw.powerWatts)
            nativeEmitter.emit(MetricKind.CADENCE, raw.cadenceRpm)
            return
        }

        val filtered = dropoutFilter.filter(raw)
        syntheticMetrics.samples(
            filtered,
            arrivalMs,
            engine.state.lifeFitnessProfile.weightKg.toDouble(),
        ).forEach { sample ->
            val emitter = if (sample.sourceClass == MetricSourceClass.VENDOR_NATIVE) {
                nativeEmitter
            } else {
                engine.metricEmitterFor(
                    gatt,
                    ch.uuid,
                    MetricProtocol.VENDOR,
                    sample.sourceClass,
                    arrivalMs,
                ) ?: return@forEach
            }
            emitter.emit(
                sample.metric,
                sample.value,
                nativeCumulative = sample.cumulative,
                quality = sample.quality,
            )
        }
    }

    override fun onLivenessTick(engine: BtEngine, gatt: BluetoothGatt) {
        val context = engine.state.connectionRegistry.contextForGatt(gatt) ?: return
        val gate = setupGate ?: return
        if (gate.connectionId != context.connectionId) return
        if (gate.tick(MonotonicMetricClock.SYSTEM.nowMs())) {
            engine.state.adapterLiveness.setupFailed(context, "Flywheel TX subscription timed out")
            Timber.tag(BtEngineState.TAG).w(
                "Flywheel TX subscription timed out connection=%s",
                context.connectionId,
            )
        }
    }

    private fun resetLinkState() {
        setupGate?.cancel()
        setupGate = null
        structuralMatch = false
        dropoutFilter = FlywheelDropoutFilter()
        syntheticMetrics = FlywheelSyntheticMetrics()
    }

    private companion object {
        const val SUBSCRIPTION_TIMEOUT_MS = 10_000L
    }
}
