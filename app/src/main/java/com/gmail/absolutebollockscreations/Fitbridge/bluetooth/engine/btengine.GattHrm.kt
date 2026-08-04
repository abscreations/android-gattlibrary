/**
 * btEngine.GattHrm.kt
 *
 * ROLE
 *  - Protocol handler for the standard Heart Rate Service (UUID 0x180D).
 *  - Plugged into [BtEngineGattCore] as the HRM profile implementation.
 *  - Discovers HRM characteristics, enables notifications, and parses incoming
 *    Heart Rate Measurement frames into [BtEngineState] / HR arbitration.
 *
 * SERVICE & CHARACTERISTICS
 *  - Primary service:
 *      • Heart Rate Service                    (UUID_HRM_SERVICE / 0x180D)
 *  - Characteristics handled:
 *      • Heart Rate Measurement                (UUID_HEART_RATE_MEASUREMENT / 0x2A37)
 *          - Flags:
 *              bit0 → HR format (UInt8 vs UInt16)
 *              bits1..2 → sensor contact support + detection
 *              bit3 → Energy Expended present (UInt16, kJ)
 *              bit4 → RR-interval(s) present (1/1024 s)
 *          - Parses:
 *              • BPM (8/16 bit)
 *              • Sensor contact state
 *              • Energy Expended (kJ → kcal)
 *              • RR-interval list (→ ms)
 *      • Body Sensor Location                  (UUID_BODY_SENSOR_LOCATION / 0x2A38)
 *          - Single-byte location code mapped to [BtEngineState.BodyLocation].
 *      • Heart Rate Control Point              (UUID_HEART_RATE_CONTROL_POINT / 0x2A39)
 *          - Exposed in extraCharacteristics but not actively used or written.
 *
 * DATA FLOW & INTEGRATION
 *  - Heart rate:
 *      • Parsed BPM is fed into [HrmArbitration.setExternalHeartRate], which merges
 *        HRM and FTMS-native heart rate and updates:
 *          - state.heartRate
 *          - state.averageHeartRate
 *          - state.hrSeries
 *          - state.heartRateSource / heartRateZone (via arbitration logic).
 *
 *  - Energy:
 *      • When Energy Expended is present, reads UInt16 in **kiloJoules**,
 *        converts to kcal (via KJ_TO_KCAL), and forwards to
 *        [EnergyArbitration.setHrmEnergy] so HRM becomes the top-priority
 *        energy source vs FTMS/Wear.
 *
 *  - RR-intervals & contact:
 *      • RR-intervals are converted from 1/1024s to milliseconds and passed to
 *        [HrmStateSink.setHrmRrIntervals].
 *      • Sensor contact support/detection bits are mapped to a nullable Boolean
 *        via [HrmStateSink.setHrmSensorContact].
 *
 *  - Body location:
 *      • Body Sensor Location byte is mapped to [BtEngineState.BodyLocation] and
 *        pushed through [HrmStateSink.setHrmBodySensorLocation] for UI/debugging.
 *
 * STATE SINK CONTRACT
 *  - [BtEngineState] implements [HrmStateSink] so HRM-specific fields can be
 *    populated without tying this handler to a concrete state type.
 *  - Consolidated kcal / energy display should always come from
 *    [EnergyArbitration] rather than any raw HRM field.
 */

package com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine

import android.Manifest
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGatt.GATT_SUCCESS
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattService
import androidx.annotation.RequiresPermission
import com.gmail.absolutebollockscreations.Fitbridge.DebugLevel
import com.gmail.absolutebollockscreations.Fitbridge.Debugging
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine.BluetoothIds.UUID_BODY_SENSOR_LOCATION
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine.BluetoothIds.UUID_HEART_RATE_CONTROL_POINT
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine.BluetoothIds.UUID_HEART_RATE_MEASUREMENT
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine.BluetoothIds.UUID_HRM_SERVICE
import timber.log.Timber
import java.util.UUID
import kotlin.math.roundToInt

internal class HrmProtocolHandler : BtEngineGattCore.ProtocolHandler {

    override val primaryServices: Set<UUID> = setOf(UUID_HRM_SERVICE)

    // We expose HRM Measurement + Body Location + CP (read-only / notifications only).
    override val extraCharacteristics: Set<UUID> = setOf(
        UUID_HEART_RATE_MEASUREMENT,
        UUID_BODY_SENSOR_LOCATION,
        UUID_HEART_RATE_CONTROL_POINT // optional, not actively used
    )

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    override fun onServicesDiscovered(
        engine: BtEngine,
        gatt: BluetoothGatt,
        services: List<BluetoothGattService>
    ): Boolean {
        Timber.tag(BtEngineState.TAG).d(
            "HRM onServicesDiscovered(device=%s) services=%d",
            gatt.device?.address,
            services.size
        )

        val svc = services.firstOrNull { it.uuid == UUID_HRM_SERVICE }
        if (svc == null) {
            Timber.tag(BtEngineState.TAG).d(
                "HRM service (0x180D) not found on %s",
                gatt.device?.address
            )
            return false
        }

        // Log HRM characteristics inventory for debug
        runCatching {
            svc.characteristics.forEach { ch ->
                Timber.tag(BtEngineState.TAG).d(
                    "HRM char %s props=[%s]",
                    short(ch.uuid),
                    propsString(ch.properties)
                )
            }
        }

        // 1) Enable HRM notifications (0x2A37) via the shared GATT queue.
        //    This mirrors FTMS behavior and benefits from the same 201/backoff handling
        //    implemented in BtEngineGattCore.enableNotifications(...).
        svc.getCharacteristic(UUID_HEART_RATE_MEASUREMENT)?.let { ch ->
            Timber.tag(BtEngineState.TAG).i(
                "Queue CCCD enable for HRM %s on %s",
                short(UUID_HEART_RATE_MEASUREMENT),
                gatt.device?.address
            )
            engine.enableNotifications(gatt, ch)
        } ?: Timber.tag(BtEngineState.TAG).w(
            "HRM Measurement char (0x2A37) missing on %s",
            gatt.device?.address
        )

        // 2) Best-effort read Body Sensor Location (0x2A38) through the queue.
        svc.getCharacteristic(UUID_BODY_SENSOR_LOCATION)?.let {
            Timber.tag(BtEngineState.TAG).d(
                "Queue READ Body Sensor Location (0x2A38) on %s",
                gatt.device?.address
            )
            engine.pushRead(
                UUID_BODY_SENSOR_LOCATION,
                legacyBodySensorLocationReadTarget(gatt),
            )
        } ?: Timber.tag(BtEngineState.TAG).d(
            "Body Sensor Location (0x2A38) not present on %s",
            gatt.device?.address
        )

        return true
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    override fun onCharacteristicChanged(
        engine: BtEngine,
        gatt: BluetoothGatt,
        ch: BluetoothGattCharacteristic,
        value: ByteArray
    ) {
        Timber.tag(BtEngineState.TAG).v(
            "HRM onCharacteristicChanged from %s char=%s len=%d",
            gatt.device?.address,
            short(ch.uuid),
            value.size
        )

        when (ch.uuid) {
            UUID_HEART_RATE_MEASUREMENT -> handleHeartRateMeasurement(engine, value)
            UUID_HEART_RATE_CONTROL_POINT -> {
                // We don't actively use CP, but log for debugging if a device sends something.
                if (Debugging && DebugLevel > 4) {
                    Timber.tag(BtEngineState.TAG).d(
                        "HRM Control Point notification/indication received payload=%s",
                        value.toHex()
                    )
                }
            }
            else -> if (Debugging && DebugLevel > 4) {
                Timber.tag(BtEngineState.TAG).d(
                    "HRM notify from unhandled char %s payload=%s",
                    short(ch.uuid),
                    value.toHex()
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
        status: Int
    ) {
        Timber.tag(BtEngineState.TAG).d(
            "HRM onCharacteristicRead %s status=%d len=%d",
            short(ch.uuid),
            status,
            value.size
        )

        if (status != GATT_SUCCESS) {
            Timber.tag(BtEngineState.TAG).w(
                "HRM read failed %s status=%d",
                short(ch.uuid),
                status
            )
            return
        }

        when (ch.uuid) {
            UUID_BODY_SENSOR_LOCATION -> handleBodySensorLocation(engine, value)
            UUID_HEART_RATE_CONTROL_POINT -> {
                // Rare, but some stacks might allow reading CP; just log it.
                Timber.tag(BtEngineState.TAG).d(
                    "HRM Control Point read payload=%s",
                    value.toHex()
                )
            }
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Parsers
    // ─────────────────────────────────────────────────────────────

    /**
     * Heart Rate Measurement (0x2A37)
     *
     * Layout (LSO → MSO):
     *  [0] flags
     *  [1..?] heart rate (8 or 16)
     *  [?] Energy Expended (opt, uint16, **kJ**)
     *  [?] RR-Intervals (opt, 1..n, uint16 each, unit = 1/1024 s)
     */
    private fun handleHeartRateMeasurement(engine: BtEngine, value: ByteArray) {
        if (value.isEmpty()) {
            Timber.tag(BtEngineState.TAG).w("HRM meas: empty payload")
            return
        }

        var offset = 0
        val flags = value[offset].toInt() and 0xFF
        offset += 1

        val hrIs16           = (flags and 0x01) != 0
        val contactSupported = (flags and 0x02) != 0
        val contactDetected  = (flags and 0x04) != 0
        val energyPresent    = (flags and 0x08) != 0
        val rrPresent        = (flags and 0x10) != 0

        if (Debugging && DebugLevel > 4) {
            Timber.tag(BtEngineState.TAG).d(
                "HRM meas flags=0x%02X hrIs16=%s contactSupported=%s contactDetected=%s energyPresent=%s rrPresent=%s raw=%s",
                flags,
                hrIs16,
                contactSupported,
                contactDetected,
                energyPresent,
                rrPresent,
                value.toHex()
            )
        }

        // ─── Heart Rate (8 or 16) ────────────────────────────────
        val bpm: Int? = if (hrIs16 && value.size >= offset + 2) {
            ((value[offset].toInt() and 0xFF) or ((value[offset + 1].toInt() and 0xFF) shl 8))
                .coerceAtLeast(0).also { offset += 2 }
        } else if (!hrIs16 && value.size >= offset + 1) {
            (value[offset].toInt() and 0xFF).also { offset += 1 }
        } else {
            Timber.tag(BtEngineState.TAG).w(
                "HRM meas: insufficient bytes for HR (hrIs16=%s, size=%d, offset=%d)",
                hrIs16,
                value.size,
                offset
            )
            null
        }

        if (Debugging && DebugLevel > 4) {
            Timber.tag(BtEngineState.TAG).d(
                "HRM meas decoded bpm=%s",
                bpm?.toString() ?: "∅"
            )
        }

        // ─── Sensor Contact (bits 1..2) ──────────────────────────
        (engine.state as? HrmStateSink)?.let { sink ->
            val contact = if (contactSupported) contactDetected else null
            sink.setHrmSensorContact(contact)
            if (Debugging && DebugLevel > 4) {
                Timber.tag(BtEngineState.TAG).d(
                    "HRM sensor contact: supported=%s detected=%s → %s",
                    contactSupported,
                    contactDetected,
                    contact
                )
            }
        }

        // ─── Energy Expended (uint16, **kiloJoules** per spec) ───
        if (energyPresent && value.size >= offset + 2) {
            val energyKj =
                (value[offset].toInt() and 0xFF) or ((value[offset + 1].toInt() and 0xFF) shl 8)
            offset += 2

            // Convert kJ → kcal for our consolidated energy slot
            // 1 kJ = 0.239006 kcal (keep a simple factor)
            val energyKcal = energyKj * KJ_TO_KCAL
            if (Debugging && DebugLevel > 4) {
                Timber.tag(BtEngineState.TAG).d(
                    "HRM energy: raw=%d kJ → %.1f kcal",
                    energyKj,
                    energyKcal
                )
            }

            // 1) Publish to arbitration so HRM is top priority source
            EnergyArbitration.setHrmEnergy(engine, energyKcal)
        } else if (energyPresent) {
            Timber.tag(BtEngineState.TAG).w(
                "HRM energyPresent=TRUE but not enough bytes (size=%d, offset=%d)",
                value.size,
                offset
            )
        }

        // ─── RR-Intervals (1/1024s → ms) ─────────────────────────
        if (rrPresent) {
            val rr = ArrayList<Int>(4)
            while (value.size >= offset + 2) {
                val rr1024 =
                    (value[offset].toInt() and 0xFF) or ((value[offset + 1].toInt() and 0xFF) shl 8)
                offset += 2
                // 1/1024s → ms
                val rrMs = ((rr1024 * 1000.0) / 1024.0).roundToInt()
                rr.add(rrMs)
            }
            if (rr.isNotEmpty()) {
                (engine.state as? HrmStateSink)?.setHrmRrIntervals(rr)
                if (Debugging && DebugLevel > 4) {
                    Timber.tag(BtEngineState.TAG).d(
                        "HRM RR intervals count=%d values=%s",
                        rr.size,
                        rr.joinToString(",")
                    )
                }
            } else {
                Timber.tag(BtEngineState.TAG).v("HRM RR flag set but no intervals decoded")
            }
        }

        // ─── Feed arbitration as External HRM input (bpm) ─────────
        HrmArbitration.setExternalHeartRate(engine, bpm)

        if (Debugging && DebugLevel > 4) {
            Timber.tag(BtEngineState.TAG).d(
                "HRM meas final bpm=%s rrCount=%d",
                bpm?.toString() ?: "∅",
                (engine.state as? HrmStateSink)?.hrmRrCount() ?: 0
            )
        }
    }

    private fun handleBodySensorLocation(engine: BtEngine, value: ByteArray) {
        if (value.isEmpty()) {
            Timber.tag(BtEngineState.TAG).w("Body Sensor Location read: empty payload")
            return
        }
        val code = value[0].toInt() and 0xFF
        val mapped = when (code) {
            0 -> BtEngineState.BodyLocation.OTHER
            1 -> BtEngineState.BodyLocation.CHEST
            2 -> BtEngineState.BodyLocation.WRIST
            3 -> BtEngineState.BodyLocation.FINGER
            4 -> BtEngineState.BodyLocation.HAND
            5 -> BtEngineState.BodyLocation.EAR_LOBE
            6 -> BtEngineState.BodyLocation.FOOT
            else -> BtEngineState.BodyLocation.UNKNOWN
        }
        (engine.state as? HrmStateSink)?.setHrmBodySensorLocation(mapped)

        Timber.tag(BtEngineState.TAG).d(
            "Body sensor location code=%d → %s",
            code,
            mapped
        )
    }

    companion object {
        // Simple global factor; good enough for kcal dashboarding
        private const val KJ_TO_KCAL = 0.239006

        private fun short(u: UUID?): String = when (u) {
            null -> "null"
            else -> u.toString().let { s ->
                if (s.startsWith("0000") && s.endsWith("-0000-1000-8000-00805f9b34fb")) {
                    s.substring(4, 8)
                } else {
                    s
                }
            }
        }

        private fun propsString(props: Int): String {
            val list = mutableListOf<String>()
            if ((props and BluetoothGattCharacteristic.PROPERTY_READ) != 0) list += "READ"
            if ((props and BluetoothGattCharacteristic.PROPERTY_WRITE) != 0) list += "WRITE"
            if ((props and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0) list += "WRITE_NR"
            if ((props and BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0) list += "NOTIFY"
            if ((props and BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0) list += "INDICATE"
            if ((props and BluetoothGattCharacteristic.PROPERTY_SIGNED_WRITE) != 0) list += "SIGNED"
            if ((props and BluetoothGattCharacteristic.PROPERTY_BROADCAST) != 0) list += "BCAST"
            if ((props and BluetoothGattCharacteristic.PROPERTY_EXTENDED_PROPS) != 0) list += "EXT"
            return list.joinToString("|")
        }

        private fun ByteArray.toHex(): String =
            joinToString(" ") { b -> "%02X".format(b) }
    }
}

/**
 * Known Phase 0.1 baseline defect P0.1-D1: the optional HRM body-location read is
 * untargeted and therefore falls back to the FTMS GATT when both links exist.
 */
@Suppress("UNUSED_PARAMETER")
internal fun <T> legacyBodySensorLocationReadTarget(hrmGatt: T): T? = null

/**
 * Optional sink interface: if your EngineState implements this, HRM data will be populated.
 * NOTE: setHrmEnergyExpended(...) stays in **raw HRM units (kJ)** to mirror the 0x2A37 spec.
 * Consolidated kcal flow should come from EnergyArbitration, not from here.
 */
internal interface HrmStateSink {
    fun setHrmSensorContact(contact: Boolean?)
    fun setHrmRrIntervals(rrMs: List<Int>)
    fun setHrmBodySensorLocation(loc: BtEngineState.BodyLocation)
    fun hrmRrCount(): Int = 0
}
