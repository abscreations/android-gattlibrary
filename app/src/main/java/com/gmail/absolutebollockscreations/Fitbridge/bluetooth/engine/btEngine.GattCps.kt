package com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine

import android.Manifest
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattService
import androidx.annotation.RequiresPermission
import java.util.UUID
import timber.log.Timber

internal enum class CpsGattOperationKind { READ, ENABLE_NOTIFICATIONS }
internal data class CpsGattOperation(val kind: CpsGattOperationKind, val characteristicUuid: UUID)
internal data class CpsDiscoveryPlan(val activate: Boolean, val operations: List<CpsGattOperation>)

internal fun planCpsDiscovery(characteristicUuids: Set<UUID>): CpsDiscoveryPlan {
    if (BluetoothIds.UUID_CYCLING_POWER_MEASUREMENT !in characteristicUuids) {
        return CpsDiscoveryPlan(false, emptyList())
    }
    return CpsDiscoveryPlan(
        true,
        buildList {
            if (BluetoothIds.UUID_CYCLING_POWER_FEATURE in characteristicUuids) {
                add(CpsGattOperation(CpsGattOperationKind.READ, BluetoothIds.UUID_CYCLING_POWER_FEATURE))
            }
            if (BluetoothIds.UUID_SENSOR_LOCATION in characteristicUuids) {
                add(CpsGattOperation(CpsGattOperationKind.READ, BluetoothIds.UUID_SENSOR_LOCATION))
            }
            add(CpsGattOperation(CpsGattOperationKind.ENABLE_NOTIFICATIONS, BluetoothIds.UUID_CYCLING_POWER_MEASUREMENT))
        },
    )
}

internal sealed interface CpsReadOutcome {
    data class Features(val bits: Long) : CpsReadOutcome
    data class SensorLocation(val code: Int) : CpsReadOutcome
    data class Unavailable(val characteristicUuid: UUID) : CpsReadOutcome
    data object Ignored : CpsReadOutcome
}

internal fun decodeCpsRead(characteristicUuid: UUID, value: ByteArray, success: Boolean): CpsReadOutcome =
    when (characteristicUuid) {
        BluetoothIds.UUID_CYCLING_POWER_FEATURE -> if (!success || value.size < 4) {
            CpsReadOutcome.Unavailable(characteristicUuid)
        } else {
            CpsReadOutcome.Features(
                (value[0].toLong() and 0xFF) or ((value[1].toLong() and 0xFF) shl 8) or
                    ((value[2].toLong() and 0xFF) shl 16) or ((value[3].toLong() and 0xFF) shl 24),
            )
        }
        BluetoothIds.UUID_SENSOR_LOCATION -> if (!success || value.isEmpty()) {
            CpsReadOutcome.Unavailable(characteristicUuid)
        } else {
            CpsReadOutcome.SensorLocation(value[0].toInt() and 0xFF)
        }
        else -> CpsReadOutcome.Ignored
    }

internal fun cpsKilojoulesToKilocalories(kilojoules: Int): Double = kilojoules * 0.239005736

/** Standard Cycling Power Service (`0x1818`) central handler. */
internal class CpsProtocolHandler(
    private val tracker: CpsMeasurementTracker = CpsMeasurementTracker(),
    private val derivation: CpsDerivationState = CpsDerivationState(),
) : BtEngineGattCore.ProtocolHandler {
    private var sessionGeneration: Long? = null

    override val primaryServices: Set<UUID> = setOf(BluetoothIds.UUID_CYCLING_POWER_SERVICE)
    override val extraCharacteristics: Set<UUID> = setOf(
        BluetoothIds.UUID_CYCLING_POWER_MEASUREMENT,
        BluetoothIds.UUID_CYCLING_POWER_FEATURE,
        BluetoothIds.UUID_SENSOR_LOCATION,
    )

    internal fun acceptMeasurement(value: ByteArray): CpsMeasurementTracker.Update? = tracker.accept(value)

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
        val service = services.firstOrNull { it.uuid == BluetoothIds.UUID_CYCLING_POWER_SERVICE }
            ?: return false
        val context = engine.state.connectionRegistry.contextForGatt(gatt) ?: run {
            Timber.tag(BtEngineState.TAG).w("CPS discovery ignored for unregistered GATT")
            return false
        }
        val characteristics = service.characteristics.associateBy { it.uuid }
        val plan = planCpsDiscovery(characteristics.keys)
        if (!plan.activate) {
            Timber.tag(BtEngineState.TAG).w("CPS service missing Measurement on %s", gatt.device?.address)
            return false
        }
        plan.operations.forEach { operation ->
            when (operation.kind) {
                CpsGattOperationKind.READ -> engine.pushRead(context, operation.characteristicUuid)
                CpsGattOperationKind.ENABLE_NOTIFICATIONS -> engine.enableNotifications(
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
        if (ch.uuid != BluetoothIds.UUID_CYCLING_POWER_MEASUREMENT) return
        val context = engine.state.connectionRegistry.contextForGatt(gatt) ?: run {
            Timber.tag(BtEngineState.TAG).w("CPS measurement ignored for unregistered GATT")
            return
        }
        val session = engine.cscSessionSnapshot()
        if (session?.generation != null && sessionGeneration != session.generation) {
            tracker.reset()
            derivation.reset()
            sessionGeneration = session.generation
        } else if (session == null) {
            sessionGeneration = null
        }
        val update = acceptMeasurement(value) ?: run {
            Timber.tag(BtEngineState.TAG).w(
                "CPS measurement malformed: %d octet(s) from %s",
                value.size,
                gatt.device?.address,
            )
            return
        }
        val arrivalMs = MonotonicMetricClock.SYSTEM.nowMs()
        if (session?.persona == CscWorkoutPersona.BIKE) {
            engine.state.cpsNativeEvents.publish(context.connectionId, update.reading)
        }
        engine.metricEmitterFor(
            gatt,
            ch.uuid,
            MetricProtocol.CPS,
            MetricSourceClass.CPS,
            arrivalMs,
        )?.let { emitter ->
            emitter.emit(MetricKind.POWER, update.reading.instantaneousPowerWatts.toDouble())
            update.reading.accumulatedEnergyKilojoules?.let { kilojoules ->
                val kilocalories = cpsKilojoulesToKilocalories(kilojoules)
                emitter.emit(MetricKind.ENERGY, kilocalories, nativeCumulative = kilocalories)
            }
        }
        if (session?.persona == CscWorkoutPersona.BIKE) {
            derivation.derive(update, session.calibration.wheelCircumferenceMm).forEach { metric ->
                engine.metricEmitterFor(
                    gatt,
                    ch.uuid,
                    MetricProtocol.CPS,
                    metric.sourceClass,
                    arrivalMs,
                )?.emit(
                    metric.metric,
                    metric.value,
                    nativeCumulative = metric.nativeCumulative,
                    quality = MetricQuality.DERIVED,
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
        when (val outcome = decodeCpsRead(ch.uuid, value, status == BluetoothGatt.GATT_SUCCESS)) {
            is CpsReadOutcome.Features -> Timber.tag(BtEngineState.TAG).i(
                "CPS features on %s: 0x%08X",
                gatt.device?.address,
                outcome.bits,
            )
            is CpsReadOutcome.SensorLocation -> Timber.tag(BtEngineState.TAG).i(
                "CPS sensor location on %s: %d",
                gatt.device?.address,
                outcome.code,
            )
            is CpsReadOutcome.Unavailable -> Timber.tag(BtEngineState.TAG).i(
                "CPS read unavailable on %s characteristic=%s status=%d len=%d",
                gatt.device?.address,
                outcome.characteristicUuid,
                status,
                value.size,
            )
            CpsReadOutcome.Ignored -> Unit
        }
    }

    private fun reset() {
        tracker.reset()
        derivation.reset()
        sessionGeneration = null
    }
}
