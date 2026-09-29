package com.gmail.absolutebollockscreations.gattlibrary.central

import android.Manifest
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattService
import androidx.annotation.RequiresPermission
import com.gmail.absolutebollockscreations.gattlibrary.core.BleDebug
import com.gmail.absolutebollockscreations.gattlibrary.core.GattLibraryInternalApi
import com.gmail.absolutebollockscreations.gattlibrary.central.BluetoothIds.UUID_RSC_FEATURE
import com.gmail.absolutebollockscreations.gattlibrary.central.BluetoothIds.UUID_RSC_MEASUREMENT
import com.gmail.absolutebollockscreations.gattlibrary.central.BluetoothIds.UUID_RSC_SERVICE
import com.gmail.absolutebollockscreations.gattlibrary.central.BluetoothIds.UUID_SENSOR_LOCATION
import java.util.UUID
import timber.log.Timber

internal enum class RscGattOperationKind { ENABLE_NOTIFICATIONS, READ }

internal data class RscGattOperation(
    val kind: RscGattOperationKind,
    val characteristicUuid: UUID,
)

internal data class RscDiscoveryPlan(
    val activate: Boolean,
    val operations: List<RscGattOperation>,
)

/** Required Measurement first, followed by the two best-effort reads in stable queue order. */
internal fun planRscDiscovery(characteristicUuids: Set<UUID>): RscDiscoveryPlan {
    if (UUID_RSC_MEASUREMENT !in characteristicUuids) {
        return RscDiscoveryPlan(activate = false, operations = emptyList())
    }
    return RscDiscoveryPlan(
        activate = true,
        operations = buildList {
            add(RscGattOperation(RscGattOperationKind.ENABLE_NOTIFICATIONS, UUID_RSC_MEASUREMENT))
            if (UUID_RSC_FEATURE in characteristicUuids) {
                add(RscGattOperation(RscGattOperationKind.READ, UUID_RSC_FEATURE))
            }
            if (UUID_SENSOR_LOCATION in characteristicUuids) {
                add(RscGattOperation(RscGattOperationKind.READ, UUID_SENSOR_LOCATION))
            }
        },
    )
}

internal sealed interface RscReadOutcome {
    data class Features(val bits: Int) : RscReadOutcome
    data class SensorLocation(val code: Int, val footMounted: Boolean?) : RscReadOutcome
    data class Unavailable(val characteristicUuid: UUID) : RscReadOutcome
    data object Ignored : RscReadOutcome
}

internal fun decodeRscRead(
    characteristicUuid: UUID,
    value: ByteArray,
    success: Boolean,
): RscReadOutcome = when (characteristicUuid) {
    UUID_SENSOR_LOCATION -> if (!success || value.isEmpty()) {
        RscReadOutcome.Unavailable(characteristicUuid)
    } else {
        val code = value[0].toInt() and 0xFF
        RscReadOutcome.SensorLocation(code, RscMeasurement.isFootMounted(code))
    }

    UUID_RSC_FEATURE -> if (!success || value.size < 2) {
        RscReadOutcome.Unavailable(characteristicUuid)
    } else {
        RscReadOutcome.Features(
            (value[0].toInt() and 0xFF) or ((value[1].toInt() and 0xFF) shl 8),
        )
    }

    else -> RscReadOutcome.Ignored
}

@GattLibraryInternalApi
fun emitRscMetrics(reading: RscMeasurement.Reading, emitter: MetricEmitter) {
    emitter.emit(MetricKind.SPEED, reading.speedKph)
    emitter.emit(MetricKind.CADENCE, reading.cadenceStepsPerMinute.toDouble())
    reading.totalDistanceMetres?.let {
        emitter.emit(MetricKind.DISTANCE, it, nativeCumulative = it)
    }
}

/**
 * Running Speed and Cadence (`0x1814`) — plan Phase 1.
 *
 * Owns `0x2A53` notifications and reads `0x2A54` once for the feature bits.
 * Decoding lives in [RscMeasurement], which is pure and fixture-tested against
 * the four TMIW corpora; this class is only plumbing.
 *
 * ### Two connection shapes, one handler
 *
 * A running strap such as the Garmin HRM-Pro exposes RSC **and** HRM on one
 * connection, so this handler must coexist with [HrmProtocolHandler] on the same
 * link - each activates on its own primary service and emits under its own
 * `sourceId`, which is what lets fusion tell them apart. A foot pod exposes RSC
 * alone on its own connection. Neither case is special-cased here; the
 * multi-handler routing from Phase 0.5 does the work.
 *
 * ### Divergence from TMIW
 *
 * TMIW admits RSC only from devices matching Stryd and Technogym name rules.
 * FitBridge admits anything advertising `0x1814`. A name allow-list cannot be
 * completed - it silently excludes every foot pod nobody thought of - and the
 * service UUID is the actual declaration of capability.
 */
internal class RscProtocolHandler : BtEngineGattCore.ProtocolHandler {

    override val primaryServices: Set<UUID> = setOf(UUID_RSC_SERVICE)

    override val extraCharacteristics: Set<UUID> = setOf(
        UUID_RSC_MEASUREMENT,
        UUID_RSC_FEATURE,
        UUID_SENSOR_LOCATION,
    )

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    override fun onServicesDiscovered(
        engine: BtEngine,
        gatt: BluetoothGatt,
        services: List<BluetoothGattService>,
    ): Boolean {
        val service = services.firstOrNull { it.uuid == UUID_RSC_SERVICE } ?: run {
            Timber.tag(BtEngineState.TAG).d(
                "RSC service (0x1814) not found on %s",
                gatt.device?.address,
            )
            return false
        }
        val context = engine.state.connectionRegistry.contextForGatt(gatt) ?: run {
            Timber.tag(BtEngineState.TAG).w("RSC discovery ignored for unregistered GATT")
            return false
        }

        val characteristics = service.characteristics.associateBy { it.uuid }
        val plan = planRscDiscovery(characteristics.keys)
        if (!plan.activate) {
            // 0x1814 without 0x2A53 is a device advertising a capability it does
            // not implement. Declining activation leaves the link to whichever
            // other handler can use it, rather than holding it open for a stream
            // that will never arrive.
            Timber.tag(BtEngineState.TAG).w(
                "RSC service present without Measurement (0x2A53) on %s; not activating",
                gatt.device?.address,
            )
            return false
        }

        plan.operations.forEach { operation ->
            when (operation.kind) {
                RscGattOperationKind.ENABLE_NOTIFICATIONS -> {
                    Timber.tag(BtEngineState.TAG).i(
                        "Queue CCCD enable for RSC Measurement on %s",
                        gatt.device?.address,
                    )
                    engine.enableNotifications(context, characteristics.getValue(operation.characteristicUuid))
                }
                RscGattOperationKind.READ -> engine.pushRead(context, operation.characteristicUuid)
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
        if (ch.uuid != UUID_RSC_MEASUREMENT) return

        val reading = RscMeasurement.parse(value) ?: run {
            Timber.tag(BtEngineState.TAG).w(
                "RSC measurement too short to decode: %d octet(s) from %s",
                value.size,
                gatt.device?.address,
            )
            return
        }

        val emitter = engine.metricEmitterFor(
            gatt = gatt,
            characteristicUuid = ch.uuid,
            protocol = MetricProtocol.RSC,
            sourceClass = MetricSourceClass.RSC,
        ) ?: return

        emitRscMetrics(reading, emitter)

        // Stride length and the walking/running flag are decoded by
        // [RscMeasurement] and deliberately NOT emitted yet: there is no
        // MetricKind for either, and adding one with no consumer would repeat
        // the built-but-unreachable pattern the Phase 0 audit spent its time
        // removing. They are carried in the parser so the wire format is fully
        // covered by fixtures, and remain owed for the second half of Phase 1.

        if (BleDebug.enabled && BleDebug.level > 4) {
            Timber.tag(BtEngineState.TAG).d(
                "RSC decoded speed=%.2f km/h cadence=%d stride=%s distance=%s gait=%s",
                reading.speedKph,
                reading.cadenceStepsPerMinute,
                reading.strideLengthMetres?.toString() ?: "absent",
                reading.totalDistanceMetres?.toString() ?: "absent",
                if (reading.walking) "walking" else "running",
            )
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
        when (val outcome = decodeRscRead(ch.uuid, value, status == BluetoothGatt.GATT_SUCCESS)) {
            is RscReadOutcome.Unavailable -> {
                if (outcome.characteristicUuid == UUID_SENSOR_LOCATION) {
                    Timber.tag(BtEngineState.TAG).i(
                        "RSC sensor location unavailable on %s (status=%d len=%d); " +
                            "speed priority falls back to the provisional rule",
                        gatt.device?.address,
                        status,
                        value.size,
                    )
                } else {
                    Timber.tag(BtEngineState.TAG).d(
                        "RSC feature read unavailable on %s (status=%d len=%d)",
                        gatt.device?.address,
                        status,
                        value.size,
                    )
                }
            }

            is RscReadOutcome.SensorLocation -> {
                Timber.tag(BtEngineState.TAG).i(
                    "RSC sensor location on %s: %s (foot-mounted=%s)",
                    gatt.device?.address,
                    RscMeasurement.describeSensorLocation(outcome.code),
                    outcome.footMounted?.toString() ?: "undetermined",
                )
                // Speed priority depends on this: a foot pod owns running speed,
                // while a torso strap's estimate is excluded beside a machine.
                engine.state.connectionRegistry.contextForGatt(gatt)?.let { context ->
                    engine.metricFusion.setRscMounting(context.connectionId, outcome.footMounted)
                }
            }

            is RscReadOutcome.Features -> Timber.tag(BtEngineState.TAG).i(
                "RSC features on %s: 0x%04X (%s)",
                gatt.device?.address,
                outcome.bits,
                RscMeasurement.describeFeatures(outcome.bits),
            )

            RscReadOutcome.Ignored -> Unit
        }
    }
}
