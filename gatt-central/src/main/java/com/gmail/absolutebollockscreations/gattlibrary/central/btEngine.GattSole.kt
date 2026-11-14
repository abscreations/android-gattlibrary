package com.gmail.absolutebollockscreations.gattlibrary.central

import android.Manifest
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import androidx.annotation.RequiresPermission
import com.gmail.absolutebollockscreations.gattlibrary.core.ActivityType
import com.gmail.absolutebollockscreations.gattlibrary.core.ftms.FtmsUuids
import com.gmail.absolutebollockscreations.gattlibrary.core.ids.SigUuids.UUID_CCCD
import java.util.UUID

internal val SOLE_UART = UUID.fromString("49535343-fe7d-4ae5-8fa9-9fafd205e455")
internal val SOLE_NOTIFY = UUID.fromString("49535343-1e4d-4bd9-ba61-23c647249616")
private val SOLE_WRITE = UUID.fromString("49535343-8841-43f4-a8d4-ecbe34729bb3")
internal val SOLE_GAMING_SERVICE = UUID.fromString("00001850-0000-1000-8000-00805f9b34fb")
internal val SOLE_NAME = Regex(
    """^(?:SOLE[ _-]*)?(F63|F65|F80|F85|ST90|LCB|R92)(?:[ _-]+[A-Z0-9_-]+)?$""",
    RegexOption.IGNORE_CASE,
)

internal enum class SoleModel {
    F63, F65, F80, F85, ST90, LCB, R92;

    val bike: Boolean get() = this == LCB || this == R92
    val activity: ActivityType get() = if (bike) ActivityType.BIKING else ActivityType.TREADMILL
    val ftmsDataUuid: UUID
        get() = if (bike) FtmsUuids.UUID_BIKING_DATA else FtmsUuids.UUID_TREADMILL_DATA
}

@RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
internal fun soleModel(engine: BtEngine, gatt: BluetoothGatt, services: List<BluetoothGattService>): SoleModel? {
    val uart = services.firstOrNull { it.uuid == SOLE_UART } ?: return null
    val measurement = uart.getCharacteristic(SOLE_NOTIFY) ?: return null
    if (measurement.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY == 0 ||
        measurement.getDescriptor(UUID_CCCD) == null
    ) return null
    val name = engine.state.devices.value.values
        .firstOrNull { it.address.equals(gatt.safeAddress(), ignoreCase = true) }?.name
    val match = sequenceOf(name, gatt.device?.name).filterNotNull()
        .mapNotNull { SOLE_NAME.matchEntire(it.trim()) }.firstOrNull() ?: return null
    return SoleModel.valueOf(match.groupValues[1].uppercase(java.util.Locale.ROOT))
}

internal class SoleProtocolHandler : BtEngineGattCore.ProtocolHandler {
    override val primaryServices: Set<UUID> = setOf(SOLE_UART)
    override val extraCharacteristics: Set<UUID> = setOf(SOLE_NOTIFY, SOLE_WRITE)
    private val frames = SoleFrameAssembler()
    private var model: SoleModel? = null
    private var queryPending = false
    private var f65Session: SoleF65Session? = null
    private var writeMode = StackEntry.WriteMode.WITHOUT_RESPONSE

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    override fun onServicesDiscovered(
        engine: BtEngine, gatt: BluetoothGatt, services: List<BluetoothGattService>,
    ): Boolean {
        frames.reset()
        queryPending = false
        f65Session = null
        val matched = soleModel(engine, gatt, services)
        model = matched
        if (matched == null) return false
        val context = engine.state.connectionRegistry.contextForGatt(gatt) ?: return false
        val uart = services.first { it.uuid == SOLE_UART }
        val notify = uart.getCharacteristic(SOLE_NOTIFY) ?: return false
        val write = uart.getCharacteristic(SOLE_WRITE)
        writeMode = if (write != null && write.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) {
            StackEntry.WriteMode.WITHOUT_RESPONSE
        } else {
            StackEntry.WriteMode.WITH_RESPONSE
        }
        // F65 incline is only on the UART, which needs the full startup handshake and polling.
        f65Session = if (matched == SoleModel.F65 && write != null &&
            write.properties and (BluetoothGattCharacteristic.PROPERTY_WRITE or
                BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0
        ) SoleF65Session() else null
        queryPending = f65Session == null && matched != SoleModel.ST90 &&
            services.none { it.uuid == SOLE_GAMING_SERVICE } &&
            write != null && write.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0
        if (services.none { it.uuid == FtmsUuids.UUID_FTMS_SERVICE }) {
            BtEngineCapabilities.applyFitnessMachineFeatures(engine.state, ByteArray(8))
            engine.state.updateDeviceMachineFeatureByAddress(context.address, matched.activity.ordinal)
            engine.state.setInferredActivity(matched.activity)
        }
        engine.enableNotifications(context, notify)
        return true
    }

    override fun onDisconnected(engine: BtEngine) {
        frames.reset()
        model = null
        queryPending = false
        f65Session?.reset()
        f65Session = null
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    override fun onDescriptorWrite(
        engine: BtEngine, gatt: BluetoothGatt, desc: BluetoothGattDescriptor, status: Int,
    ) {
        if (status != BluetoothGatt.GATT_SUCCESS || desc.uuid != UUID_CCCD ||
            desc.characteristic?.uuid != SOLE_NOTIFY || desc.characteristic?.service?.uuid != SOLE_UART
        ) return
        val context = engine.state.connectionRegistry.contextForGatt(gatt) ?: return
        f65Session?.let { session ->
            if (session.phase == SoleF65Session.Phase.IDLE) {
                send(engine, context, session.start(MonotonicMetricClock.SYSTEM.nowMs()))
            }
            return
        }
        if (!queryPending) return
        queryPending = false
        // F0 is the captured identification query. Later setup and workout commands are model-specific.
        engine.pushWrite(context, byteArrayOf(0x5b, 1, 0xf0.toByte(), 0x5d), SOLE_WRITE,
            StackEntry.WriteMode.WITHOUT_RESPONSE)
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    override fun onCharacteristicChanged(
        engine: BtEngine, gatt: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray,
    ) {
        val matched = model ?: return
        if (ch.uuid != SOLE_NOTIFY || ch.service?.uuid != SOLE_UART) return
        val emitter = engine.metricEmitterFor(gatt, ch.uuid, MetricProtocol.VENDOR, MetricSourceClass.VENDOR_NATIVE)
            ?: return
        frames.accept(value, emitter.arrivalMs).forEach { frame ->
            soleMeasurements(matched, frame).forEach { (metric, reading) -> emitter.emit(metric, reading) }
        }
        val session = f65Session ?: return
        val context = engine.state.connectionRegistry.contextForGatt(gatt) ?: return
        send(engine, context, session.onNotification(MonotonicMetricClock.SYSTEM.nowMs(), value))
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    override fun onLivenessTick(engine: BtEngine, gatt: BluetoothGatt) {
        val session = f65Session ?: return
        if (session.phase == SoleF65Session.Phase.IDLE) return
        val context = engine.state.connectionRegistry.contextForGatt(gatt) ?: return
        send(engine, context, session.tick(MonotonicMetricClock.SYSTEM.nowMs()))
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun send(engine: BtEngine, context: ConnectionContext, writes: List<ByteArray>) {
        for (write in writes) engine.pushWrite(context, write, SOLE_WRITE, writeMode)
    }
}
