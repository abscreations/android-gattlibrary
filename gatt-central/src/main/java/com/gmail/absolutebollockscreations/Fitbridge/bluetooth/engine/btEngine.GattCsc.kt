package com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine

import android.Manifest
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattService
import androidx.annotation.RequiresPermission
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.core.BleDebug
import java.util.UUID
import timber.log.Timber

internal enum class CscGattOperationKind { READ, ENABLE_NOTIFICATIONS }

internal data class CscGattOperation(
    val kind: CscGattOperationKind,
    val characteristicUuid: UUID,
)

internal data class CscDiscoveryPlan(
    val activate: Boolean,
    val operations: List<CscGattOperation>,
)

/** Feature and Measurement are mandatory; Feature must reach the queue first. */
internal fun planCscDiscovery(characteristicUuids: Set<UUID>): CscDiscoveryPlan {
    if (BluetoothIds.UUID_CSC_MEASUREMENT !in characteristicUuids ||
        BluetoothIds.UUID_CSC_FEATURE !in characteristicUuids
    ) {
        return CscDiscoveryPlan(activate = false, operations = emptyList())
    }
    return CscDiscoveryPlan(
        activate = true,
        operations = listOf(
            CscGattOperation(CscGattOperationKind.READ, BluetoothIds.UUID_CSC_FEATURE),
            CscGattOperation(
                CscGattOperationKind.ENABLE_NOTIFICATIONS,
                BluetoothIds.UUID_CSC_MEASUREMENT,
            ),
        ),
    )
}

internal sealed interface CscFeatureReadOutcome {
    data class Features(
        val bits: Int,
        val capability: BtEngineState.CscRoleCapability?,
    ) : CscFeatureReadOutcome

    data object Unavailable : CscFeatureReadOutcome
    data object Ignored : CscFeatureReadOutcome
}

internal fun decodeCscFeatureRead(
    characteristicUuid: UUID,
    value: ByteArray,
    success: Boolean,
): CscFeatureReadOutcome {
    if (characteristicUuid != BluetoothIds.UUID_CSC_FEATURE) {
        return CscFeatureReadOutcome.Ignored
    }
    if (!success || value.size < 2) return CscFeatureReadOutcome.Unavailable
    val bits = (value[0].toInt() and 0xFF) or ((value[1].toInt() and 0xFF) shl 8)
    return CscFeatureReadOutcome.Features(bits, cscRoleCapabilityFromFeature(bits))
}

/** Cycling Speed and Cadence (`0x1816`) production central handler. */
internal class CscProtocolHandler(
    private val tracker: CscMeasurementTracker = CscMeasurementTracker(),
    private val derivation: CscDerivationState = CscDerivationState(),
) : BtEngineGattCore.ProtocolHandler {

    private var sessionGeneration: Long? = null

    override val primaryServices: Set<UUID> = setOf(BluetoothIds.UUID_CSC_SERVICE)

    override val extraCharacteristics: Set<UUID> = setOf(
        BluetoothIds.UUID_CSC_MEASUREMENT,
        BluetoothIds.UUID_CSC_FEATURE,
    )

    internal fun acceptMeasurement(value: ByteArray): CscMeasurementTracker.Update? =
        tracker.accept(value)

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    override fun onConnected(engine: BtEngine, gatt: BluetoothGatt) {
        tracker.reset()
        derivation.reset()
        sessionGeneration = null
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    override fun onDisconnected(engine: BtEngine) {
        tracker.reset()
        derivation.reset()
        sessionGeneration = null
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    override fun onServicesDiscovered(
        engine: BtEngine,
        gatt: BluetoothGatt,
        services: List<BluetoothGattService>,
    ): Boolean {
        val service = services.firstOrNull { it.uuid == BluetoothIds.UUID_CSC_SERVICE }
            ?: return false
        val context = engine.state.connectionRegistry.contextForGatt(gatt) ?: run {
            Timber.tag(BtEngineState.TAG).w("CSC discovery ignored for unregistered GATT")
            return false
        }
        val characteristics = service.characteristics.associateBy { it.uuid }
        val plan = planCscDiscovery(characteristics.keys)
        if (!plan.activate) {
            Timber.tag(BtEngineState.TAG).w(
                "CSC service on %s is missing mandatory Measurement or Feature; not activating",
                gatt.device?.address,
            )
            return false
        }

        plan.operations.forEach { operation ->
            when (operation.kind) {
                CscGattOperationKind.READ -> engine.pushRead(context, operation.characteristicUuid)
                CscGattOperationKind.ENABLE_NOTIFICATIONS -> engine.enableNotifications(
                    context,
                    characteristics.getValue(operation.characteristicUuid),
                )
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
        if (ch.uuid != BluetoothIds.UUID_CSC_MEASUREMENT) return
        val context = engine.state.connectionRegistry.contextForGatt(gatt)
        if (context == null) {
            Timber.tag(BtEngineState.TAG).w("CSC measurement ignored for unregistered GATT")
            return
        }
        val session = engine.cscSessionSnapshot()
        if (session == null) {
            acceptMeasurement(value)
            sessionGeneration = null
            return
        }
        if (sessionGeneration != session.generation) {
            tracker.reset()
            derivation.reset()
            sessionGeneration = session.generation
        }
        val update = acceptMeasurement(value)
        if (update == null) {
            Timber.tag(BtEngineState.TAG).w(
                "CSC measurement malformed: %d octet(s) from %s",
                value.size,
                gatt.device?.address,
            )
            return
        }
        if (session.persona == CscWorkoutPersona.ELLIPTICAL &&
            !engine.isSelectedEllipticalCsc(context)
        ) return
        val capability = context.cscRoleCapability ?: return
        if (session.persona == CscWorkoutPersona.BIKE) {
            engine.state.cscNativeEvents.publish(
                context.connectionId,
                update.reading,
                context.cscAssignedRoles,
            )
        }
        val metrics = derivation.derive(
            update = update,
            persona = session.persona,
            supportedRoles = capability.roles,
            assignedRoles = context.cscAssignedRoles,
            calibration = session.calibration,
        )
        if (metrics.isEmpty()) return
        val arrivalMs = MonotonicMetricClock.SYSTEM.nowMs()
        metrics.forEach { metric ->
            val emitter = engine.metricEmitterFor(
                gatt = gatt,
                characteristicUuid = ch.uuid,
                protocol = MetricProtocol.CSC,
                sourceClass = metric.sourceClass,
                arrivalMs = arrivalMs,
            ) ?: return
            emitter.emit(
                metric = metric.metric,
                value = metric.value,
                nativeCumulative = metric.nativeCumulative,
                quality = MetricQuality.DERIVED,
            )
            if (BleDebug.enabled && BleDebug.level > 4 &&
                (metric.metric == MetricKind.SPEED || metric.metric == MetricKind.DISTANCE)
            ) {
                Timber.tag(BtEngineState.TAG).d(
                    "CSC derived %s=%.3f persona=%s wheelMm=%d ellipticalStrideMm=%d roles=%s",
                    metric.metric,
                    metric.value,
                    session.persona,
                    session.calibration.wheelCircumferenceMm,
                    session.calibration.ellipticalStrideLengthMm,
                    context.cscAssignedRoles,
                )
            }
        }
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    override fun onCharacteristicRead(
        engine: BtEngine,
        gatt: BluetoothGatt,
        ch: BluetoothGattCharacteristic,
        value: ByteArray,
        status: Int,
    ) {
        val context = engine.state.connectionRegistry.contextForGatt(gatt) ?: run {
            Timber.tag(BtEngineState.TAG).w("CSC Feature read ignored for unregistered GATT")
            return
        }
        when (
            val outcome = decodeCscFeatureRead(
                ch.uuid,
                value,
                status == BluetoothGatt.GATT_SUCCESS,
            )
        ) {
            CscFeatureReadOutcome.Ignored -> Unit
            CscFeatureReadOutcome.Unavailable -> {
                Timber.tag(BtEngineState.TAG).w(
                    "Mandatory CSC Feature unavailable on %s (status=%d len=%d)",
                    gatt.device?.address,
                    status,
                    value.size,
                )
                engine.disconnectDevice(context.connectionKey())
            }
            is CscFeatureReadOutcome.Features -> {
                val capability = outcome.capability
                if (capability == null) {
                    Timber.tag(BtEngineState.TAG).w(
                        "CSC Feature 0x%04X on %s declares no wheel or crank data",
                        outcome.bits,
                        gatt.device?.address,
                    )
                    engine.disconnectDevice(context.connectionKey())
                    return
                }

                engine.state.setCscRoleCapability(context, capability)
                val resolution = resolveCscFeatureRoles(
                    resolvedConnectionKey = context.connectionKey(),
                    featureBits = outcome.bits,
                    activeHandlers = engine.state.connectionRegistry.connectedDevices.value
                        .filter { BtEngineState.BtServiceType.CSC in it.activeServices }
                        .map { link ->
                            ActiveCscHandler(
                                connectionKey = link.connectionKey,
                                linkGeneration = link.connectionKey.substringAfterLast('#')
                                    .toLongOrNull() ?: Long.MAX_VALUE,
                                address = link.address,
                                capability = link.cscRoleCapability,
                                assignedRoles = link.cscAssignedRoles,
                                requestedRoles = if (link.connectionKey == context.connectionKey()) {
                                    context.cscRequestedRoles
                                } else {
                                    engine.state.connectionRegistry.contextForKey(link.connectionKey)
                                        ?.cscRequestedRoles
                                },
                            )
                        },
                )
                Timber.tag(BtEngineState.TAG).i(
                    "CSC features on %s: 0x%04X roles=%s",
                    gatt.device?.address,
                    outcome.bits,
                    capability.roles,
                )
                resolution?.let { engine.state.setCscAssignedRoles(context, it.assignedRoles) }
                resolution?.disconnectConnectionKey?.let { loser ->
                    Timber.tag(BtEngineState.TAG).w(
                        "%s; disconnecting newer CSC link %s",
                        resolution.disconnectReason,
                        loser,
                    )
                    engine.disconnectDevice(loser)
                }
            }
        }
    }

    private fun ConnectionContext.connectionKey(): String =
        "${connectionId.address}#${connectionId.linkGeneration}"
}
