package com.gmail.absolutebollockscreations.gattlibrary.server

import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import com.gmail.absolutebollockscreations.gattlibrary.core.BleDefaults
import com.gmail.absolutebollockscreations.gattlibrary.core.ftms.FtmsMachineProfile
import java.util.UUID
import kotlin.math.floor
import com.gmail.absolutebollockscreations.gattlibrary.core.bytes.LittleEndian.u8Wrapping
import com.gmail.absolutebollockscreations.gattlibrary.core.bytes.LittleEndian.u16Wrapping
import com.gmail.absolutebollockscreations.gattlibrary.core.bytes.LittleEndian.s16Wrapping
import com.gmail.absolutebollockscreations.gattlibrary.core.bytes.LittleEndian.u32Saturating
import com.gmail.absolutebollockscreations.gattlibrary.core.bytes.LittleEndian.u32Wrapping
import com.gmail.absolutebollockscreations.gattlibrary.core.ids.SigUuids

/**
 * Native CSC (0x1816) and Cycling Power (0x1818) sensor emulation for the Indoor
 * Bike persona.
 *
 * This replaces FitBridge's stubbed `ServiceMode.CSC_CPS`, which registered the
 * two services and their characteristics but never encoded a single measurement:
 * the old notifier had no CSC/CPS branch at all, so a client that subscribed
 * received nothing forever.
 *
 * The services are now published *alongside* FTMS rather than as an alternative
 * mode, because a watch that wants speed/cadence/power as native sensors and one
 * that wants FTMS are frequently the same session.
 */

/** Immutable native-cycling configuration for one published GATT database. */
public data class CyclingSensorConfig(
    val enabled: Boolean = false,
    val wheelCircumferenceMm: Int = DEFAULT_WHEEL_CIRCUMFERENCE_MM,
) {
    public fun effectiveFor(profile: FtmsMachineProfile): CyclingSensorConfig = copy(
        enabled = enabled && profile == FtmsMachineProfile.INDOOR_BIKE,
        wheelCircumferenceMm = wheelCircumferenceMm.coerceIn(
            MIN_WHEEL_CIRCUMFERENCE_MM,
            MAX_WHEEL_CIRCUMFERENCE_MM,
        ),
    )

    public companion object {
        public const val MIN_WHEEL_CIRCUMFERENCE_MM: Int = BleDefaults.MIN_CSC_WHEEL_CIRCUMFERENCE_MM
        public const val DEFAULT_WHEEL_CIRCUMFERENCE_MM: Int = BleDefaults.DEFAULT_CSC_WHEEL_CIRCUMFERENCE_MM
        public const val MAX_WHEEL_CIRCUMFERENCE_MM: Int = BleDefaults.MAX_CSC_WHEEL_CIRCUMFERENCE_MM

        public val DISABLED: CyclingSensorConfig = CyclingSensorConfig()
    }
}

public data class CyclingRevolutionSnapshot(
    val cumulativeWheelRevolutions: Long = 0L,
    val lastWheelEventTime: Int = 0,
    val cumulativeCrankRevolutions: Int = 0,
    val lastCrankEventTime: Int = 0,
)

/** Prefer each exact upstream CSC field pair independently; synthesize only its missing peer. */
public fun CyclingRevolutionSnapshot.withNativeEvents(
    native: CscNativeEventInput?,
): CyclingRevolutionSnapshot {
    val nativeWheel = native?.cumulativeWheelRevolutions?.let { revolutions ->
        native.lastWheelEventTimeTicks?.let { eventTime -> revolutions to eventTime }
    }
    val nativeCrank = native?.cumulativeCrankRevolutions?.let { revolutions ->
        native.lastCrankEventTimeTicks?.let { eventTime -> revolutions to eventTime }
    }
    return copy(
        cumulativeWheelRevolutions = nativeWheel?.first ?: cumulativeWheelRevolutions,
        lastWheelEventTime = nativeWheel?.second ?: lastWheelEventTime,
        cumulativeCrankRevolutions = nativeCrank?.first ?: cumulativeCrankRevolutions,
        lastCrankEventTime = nativeCrank?.second ?: lastCrankEventTime,
    )
}

/** A native wheel counter cannot be reset locally without violating D31's verbatim guarantee. */
internal fun FtmsMetricSnapshot.allowsCscCumulativeWheelReset(): Boolean =
    cscNativeEvents?.let {
        it.cumulativeWheelRevolutions == null || it.lastWheelEventTimeTicks == null
    } ?: true

/**
 * Converts instantaneous bike speed and cadence into physical-sensor events.
 *
 * The fractional phases are deliberately retained: notification timing must not
 * change the long-term wheel or crank count. All public methods are synchronized
 * because the notifier runs on a coroutine while CSC Control Point writes arrive
 * on Android's GATT callback thread.
 */
public class CyclingEventAccumulator(wheelCircumferenceMm: Int) {
    private val wheelCircumferenceMetres =
        wheelCircumferenceMm.coerceIn(
            CyclingSensorConfig.MIN_WHEEL_CIRCUMFERENCE_MM,
            CyclingSensorConfig.MAX_WHEEL_CIRCUMFERENCE_MM,
        ) / 1000.0

    private var lastUpdateMs: Long? = null
    private var wheelPhase = 0.0
    private var crankPhase = 0.0
    private var wheelRevolutions = 0L
    private var crankRevolutions = 0
    private var lastWheelEventTime = 0
    private var lastCrankEventTime = 0

    @Synchronized
    public fun update(speedKph: Double?, cadenceRpm: Double?, nowMs: Long): CyclingRevolutionSnapshot {
        val priorMs = lastUpdateMs
        lastUpdateMs = nowMs
        if (priorMs == null) return snapshotUnsafe()

        val elapsedMs = nowMs - priorMs
        if (elapsedMs !in 1..MAX_INTEGRATION_GAP_MS) return snapshotUnsafe()
        val elapsedSeconds = elapsedMs / 1000.0

        val speed = speedKph?.takeIf { it.isFinite() && it > 0.0 }
        if (speed != null && wheelRevolutions < MAX_WHEEL_REVOLUTIONS) {
            val rate = (speed / 3.6) / wheelCircumferenceMetres
            val total = wheelPhase + rate * elapsedSeconds
            val completed = floor(total).toLong()
            wheelPhase = total - completed
            if (completed > 0L) {
                wheelRevolutions = (wheelRevolutions + completed).coerceAtMost(MAX_WHEEL_REVOLUTIONS)
                val secondsSinceLastEvent = wheelPhase / rate
                lastWheelEventTime = eventTimeTicks(nowMs - secondsSinceLastEvent * 1000.0)
            }
        }

        val cadence = cadenceRpm?.takeIf { it.isFinite() && it > 0.0 }
        if (cadence != null) {
            val rate = cadence / 60.0
            val total = crankPhase + rate * elapsedSeconds
            val completed = floor(total).toInt()
            crankPhase = total - completed
            if (completed > 0) {
                crankRevolutions = (crankRevolutions + completed) and UINT16_MASK
                val secondsSinceLastEvent = crankPhase / rate
                lastCrankEventTime = eventTimeTicks(nowMs - secondsSinceLastEvent * 1000.0)
            }
        }

        return snapshotUnsafe()
    }

    @Synchronized
    public fun setCumulativeWheelRevolutions(value: Long) {
        wheelRevolutions = value.coerceIn(0L, MAX_WHEEL_REVOLUTIONS)
    }

    @Synchronized
    public fun snapshot(): CyclingRevolutionSnapshot = snapshotUnsafe()

    @Synchronized
    public fun reset() {
        lastUpdateMs = null
        wheelPhase = 0.0
        crankPhase = 0.0
        wheelRevolutions = 0L
        crankRevolutions = 0
        lastWheelEventTime = 0
        lastCrankEventTime = 0
    }

    private fun snapshotUnsafe() = CyclingRevolutionSnapshot(
        cumulativeWheelRevolutions = wheelRevolutions,
        lastWheelEventTime = lastWheelEventTime,
        cumulativeCrankRevolutions = crankRevolutions,
        lastCrankEventTime = lastCrankEventTime,
    )

    private fun eventTimeTicks(eventAtMs: Double): Int {
        val ticks = floor(eventAtMs * EVENT_TIME_HZ / 1000.0).toLong()
        return (((ticks % EVENT_TIME_MODULUS) + EVENT_TIME_MODULUS) % EVENT_TIME_MODULUS).toInt()
    }

    public companion object {
        public const val MAX_INTEGRATION_GAP_MS: Long = 5_000L
        public const val MAX_WHEEL_REVOLUTIONS: Long = 0xFFFF_FFFFL
        private const val EVENT_TIME_HZ = 1024.0
        private const val EVENT_TIME_MODULUS = 65_536L
        private const val UINT16_MASK = 0xFFFF
    }
}

/** Pure CSC/CPS characteristic encoders. */
public object CyclingSensorEncoders {
    public const val CSC_FEATURE_WHEEL_AND_CRANK: Int = 0x0003
    public const val CPS_FEATURE_CRANK_REVOLUTION_DATA: Int = 1 shl 3
    public const val CPS_FLAG_CRANK_REVOLUTION_DATA_PRESENT: Int = 1 shl 5
    public const val CPS_FLAG_WHEEL_REVOLUTION_DATA_PRESENT: Int = 1 shl 4
    public const val SENSOR_LOCATION_REAR_HUB: Int = 0x0D

    public fun cscMeasurement(revolutions: CyclingRevolutionSnapshot): ByteArray =
        u8Wrapping(CSC_FEATURE_WHEEL_AND_CRANK) +
            u32Saturating(revolutions.cumulativeWheelRevolutions) +
            u16Wrapping(revolutions.lastWheelEventTime) +
            u16Wrapping(revolutions.cumulativeCrankRevolutions) +
            u16Wrapping(revolutions.lastCrankEventTime)

    public fun cyclingPowerMeasurement(
        instantaneousPowerWatts: Int,
        revolutions: CyclingRevolutionSnapshot,
        native: CpsNativeEventInput? = null,
    ): ByteArray {
        val nativeWheel = native?.cumulativeWheelRevolutions?.let { count ->
            native.lastWheelEventTimeTicks?.let { time -> count to time }
        }
        val nativeCrank = native?.cumulativeCrankRevolutions?.let { count ->
            native.lastCrankEventTimeTicks?.let { time -> count to time }
        }
        val flags = CPS_FLAG_CRANK_REVOLUTION_DATA_PRESENT or
            if (nativeWheel != null) CPS_FLAG_WHEEL_REVOLUTION_DATA_PRESENT else 0
        return u16Wrapping(flags) +
            s16Wrapping(
                instantaneousPowerWatts.coerceIn(
                    Short.MIN_VALUE.toInt(),
                    Short.MAX_VALUE.toInt(),
                ),
            ) +
            (nativeWheel?.let { u32Saturating(it.first) + u16Wrapping(it.second) } ?: byteArrayOf()) +
            u16Wrapping(nativeCrank?.first ?: revolutions.cumulativeCrankRevolutions) +
            u16Wrapping(nativeCrank?.second ?: revolutions.lastCrankEventTime)
    }

    public fun cscFeature(): ByteArray = u16Wrapping(CSC_FEATURE_WHEEL_AND_CRANK)

    public fun cyclingPowerFeature(): ByteArray = u32Wrapping(CPS_FEATURE_CRANK_REVOLUTION_DATA.toLong())

    public fun sensorLocation(): ByteArray = u8Wrapping(SENSOR_LOCATION_REAR_HUB)
}

internal sealed interface CscControlPointRequest {
    data class SetCumulativeWheelRevolutions(val value: Long) : CscControlPointRequest
    data class Unsupported(val opcode: Int) : CscControlPointRequest
    data class Invalid(val opcode: Int) : CscControlPointRequest
}

internal object CscControlPoint {
    const val SET_CUMULATIVE_VALUE = 0x01
    const val RESPONSE_CODE = 0x10
    const val SUCCESS = 0x01
    const val OPCODE_NOT_SUPPORTED = 0x02
    const val INVALID_PARAMETER = 0x03
    const val OPERATION_FAILED = 0x04

    fun decode(value: ByteArray): CscControlPointRequest {
        val opcode = value.firstOrNull()?.toInt()?.and(0xFF) ?: -1
        if (opcode != SET_CUMULATIVE_VALUE) return CscControlPointRequest.Unsupported(opcode)
        if (value.size != 5) return CscControlPointRequest.Invalid(opcode)
        val cumulative =
            (value[1].toLong() and 0xFF) or
                ((value[2].toLong() and 0xFF) shl 8) or
                ((value[3].toLong() and 0xFF) shl 16) or
                ((value[4].toLong() and 0xFF) shl 24)
        return CscControlPointRequest.SetCumulativeWheelRevolutions(cumulative)
    }

    fun response(requestOpcode: Int, result: Int): ByteArray = byteArrayOf(
        RESPONSE_CODE.toByte(),
        requestOpcode.toByte(),
        result.toByte(),
    )
}

/** Builds the optional native CSC and CPS services for the Indoor Bike persona. */
public object CyclingServiceRegistry {
    public fun buildCscService(): BluetoothGattService = BluetoothGattService(
        SigUuids.UUID_CSC_SERVICE,
        BluetoothGattService.SERVICE_TYPE_PRIMARY,
    ).apply {
        addCharacteristic(notify(SigUuids.UUID_CSC_MEASUREMENT))
        addCharacteristic(readOnly(SigUuids.UUID_CSC_FEATURE))
        addCharacteristic(
            BluetoothGattCharacteristic(
                SigUuids.UUID_SC_CONTROL_POINT,
                BluetoothGattCharacteristic.PROPERTY_WRITE or
                    BluetoothGattCharacteristic.PROPERTY_INDICATE,
                BluetoothGattCharacteristic.PERMISSION_WRITE,
            ).apply { addDescriptor(cccd()) },
        )
    }

    public fun buildCyclingPowerService(): BluetoothGattService = BluetoothGattService(
        SigUuids.UUID_CYCLING_POWER_SERVICE,
        BluetoothGattService.SERVICE_TYPE_PRIMARY,
    ).apply {
        addCharacteristic(notify(SigUuids.UUID_CYCLING_POWER_MEASUREMENT))
        addCharacteristic(readOnly(SigUuids.UUID_CYCLING_POWER_FEATURE))
        addCharacteristic(readOnly(SigUuids.UUID_SENSOR_LOCATION))
    }

    private fun cccd() = BluetoothGattDescriptor(
        SigUuids.UUID_CCCD,
        BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE,
    )

    private fun notify(uuid: UUID) = BluetoothGattCharacteristic(
        uuid,
        BluetoothGattCharacteristic.PROPERTY_NOTIFY,
        0,
    ).apply { addDescriptor(cccd()) }

    private fun readOnly(uuid: UUID) = BluetoothGattCharacteristic(
        uuid,
        BluetoothGattCharacteristic.PROPERTY_READ,
        BluetoothGattCharacteristic.PERMISSION_READ,
    )
}
