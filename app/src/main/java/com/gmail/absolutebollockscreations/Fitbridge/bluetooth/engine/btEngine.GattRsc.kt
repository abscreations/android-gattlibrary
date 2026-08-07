package com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine

import android.Manifest
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattService
import androidx.annotation.RequiresPermission
import com.gmail.absolutebollockscreations.Fitbridge.DebugLevel
import com.gmail.absolutebollockscreations.Fitbridge.Debugging
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine.BluetoothIds.UUID_RSC_FEATURE
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine.BluetoothIds.UUID_RSC_MEASUREMENT
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine.BluetoothIds.UUID_RSC_SERVICE
import java.util.UUID
import timber.log.Timber

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

        val measurement = service.getCharacteristic(UUID_RSC_MEASUREMENT)
        if (measurement == null) {
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

        Timber.tag(BtEngineState.TAG).i(
            "Queue CCCD enable for RSC Measurement on %s",
            gatt.device?.address,
        )
        engine.enableNotifications(context, measurement)

        // Best-effort: the feature bits say which optional fields the device can
        // ever send. Useful for triage, never required - the per-packet flags are
        // authoritative for layout, so a device that refuses this read still
        // decodes correctly.
        service.getCharacteristic(UUID_RSC_FEATURE)?.let {
            engine.pushRead(context, UUID_RSC_FEATURE)
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

        emitter.emit(MetricKind.SPEED, reading.speedKph)
        emitter.emit(MetricKind.CADENCE, reading.cadenceStepsPerMinute.toDouble())
        reading.totalDistanceMetres?.let {
            emitter.emit(MetricKind.DISTANCE, it, nativeCumulative = it)
        }

        // Stride length and the walking/running flag are decoded by
        // [RscMeasurement] and deliberately NOT emitted yet: there is no
        // MetricKind for either, and adding one with no consumer would repeat
        // the built-but-unreachable pattern the Phase 0 audit spent its time
        // removing. They are carried in the parser so the wire format is fully
        // covered by fixtures, and remain owed for the second half of Phase 1.

        if (Debugging && DebugLevel > 4) {
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
        if (ch.uuid != UUID_RSC_FEATURE) return
        if (status != BluetoothGatt.GATT_SUCCESS || value.size < 2) {
            Timber.tag(BtEngineState.TAG).d(
                "RSC feature read unavailable on %s (status=%d len=%d)",
                gatt.device?.address,
                status,
                value.size,
            )
            return
        }
        val features = (value[0].toInt() and 0xFF) or ((value[1].toInt() and 0xFF) shl 8)
        Timber.tag(BtEngineState.TAG).i(
            "RSC features on %s: 0x%04X (%s)",
            gatt.device?.address,
            features,
            RscMeasurement.describeFeatures(features),
        )
    }
}
