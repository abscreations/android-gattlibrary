/*  ┏━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━┓
 *  ┃ btEngine.Scan.kt — BLE scan layer + device catalog for BtEngine          ┃
 *  ┗━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━┛
 *
 *  PURPOSE
 *  - Owns the Bluetooth LE scan lifecycle for BtEngine:
 *      • startScanImpl(...)  → start scanning with HW or SW filters
 *      • stopScanImpl()      → stop scanning and clear callback
 *  - Maintains the in-memory “discovered devices” catalog in BtEngineState:
 *      • FTMS-capable fitness machines → state.devices
 *      • HRM / other sensors           → state.hrmDevices
 *  - Normalizes scan results into stable keys, safe names/addresses, and
 *    per-service classification.
 *
 *  KEY FEATURES
 *  - Hardware vs software filtering:
 *      • When useFtmsHardwareFilter = true:
 *          - Uses OS-level ScanFilter OR’ed on FTMS + HRM services.
 *          - If no FTMS/HRM devices are discovered within 5 seconds, the scan
 *            is automatically restarted with software filtering.
 *      • When useFtmsHardwareFilter = false:
 *          - Uses a wide-open scan and filters in software via detectPrimaryService().
 *          - Supports FTMS / HRM (+ commented hooks for CSC/RSC/Power/Glucose).
 *
 *  - Service detection & classification:
 *      • detectPrimaryService(result):
 *          - Inspects advertised service UUIDs and service data to find the
 *            “primary” supported service for a ScanResult.
 *          - Prefers FTMS, then HRM (with optional heuristic on device name
 *            for straps that don’t advertise the HRM service).
 *      • toServiceType(UUID) maps 128-bit UUIDs into BtServiceType (FTMS,
 *        HeartRate, CSC, RSC, Power, Glucose).
 *
 *  - Safe identity helpers:
 *      • safeDeviceName(result)   → prefers advertised name, falls back to
 *        BluetoothDevice.name while catching SecurityException.
 *      • safeDeviceAddress(result) → BluetoothDevice.address with the same
 *        SecurityException guard.
 *      • If the address is unavailable, a temporary “tmp:<hash>” key is used
 *        and later migrated to the real address when it becomes known.
 *
 *  - Upsert helpers (device catalog):
 *      • upsertDeviceFTMS(...)   → updates state.devices for FTMS machines:
 *          - Maintains last RSSI and lastSeenMs.
 *          - Minimizes churn by only updating when name improves or RSSI
 *            changes significantly.
 *          - Migrates tmp: keys to real addresses when available.
 *          - Sets services to a single BtServiceType, machineFeature left null
 *            (to be filled after FTMS feature discovery).
 *      • upsertDeviceSensor(...) → same pattern for HRM/other sensors, backing
 *        state.hrmDevices (reused bucket for non-FTMS peripherals).
 *
 *  - Scan callback management:
 *      • Per-BtEngine ScanCallback instances are tracked in a WeakHashMap to
 *        avoid leaking callbacks across engine lifetimes.
 *      • startScanImpl() wires ScanCallback that:
 *          - Applies service filtering (HW or SW).
 *          - Classifies each result and feeds it through the appropriate
 *            upsert helper.
 *          - Invokes an optional onDevice(name, address, result) hook.
 *      • stopScanImpl() looks up and unregisters the callback, and updates
 *        state.isScanning.
 */


package com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine

import android.annotation.SuppressLint
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.os.Handler
import android.os.Looper
import timber.log.Timber
import java.util.WeakHashMap
import java.util.UUID
import kotlin.math.abs

// Keep per-engine scan callbacks without extending BtEngineState
private val scanCallbacks = WeakHashMap<BtEngine, ScanCallback>()

internal fun BtEngine.startScanImpl(
    useFtmsHardwareFilter: Boolean = true,
    onDevice: (name: String, address: String, result: ScanResult) -> Unit = { _, _, _ -> }
) {
    if (state.isScanning.value) {
        Timber.tag(BtEngineState.TAG).w("startScan: already scanning")
        return
    }

    val scanner: BluetoothLeScanner? = adapter?.bluetoothLeScanner
    if (scanner == null) {
        Timber.tag(BtEngineState.TAG).w("startScan: scanner null (adapter=${adapter != null})")
        return
    }

    // Build OR filters when using hardware filtering (now covers all supported services)
    val filters: List<ScanFilter>? = if (useFtmsHardwareFilter) {
        listOf(
            ScanFilter.Builder().setServiceUuid(BluetoothIds.FTMS_SERVICE_PARCEL).build(),
            ScanFilter.Builder().setServiceUuid(BluetoothIds.HRM_SERVICE_PARCEL).build(),
            //ScanFilter.Builder().setServiceUuid(BluetoothIds.CSC_SERVICE_PARCEL).build(),
            //ScanFilter.Builder().setServiceUuid(BluetoothIds.RSC_SERVICE_PARCEL).build(),
            //ScanFilter.Builder().setServiceUuid(BluetoothIds.POWER_SERVICE_PARCEL).build(),
            //ScanFilter.Builder().setServiceUuid(BluetoothIds.GLUCOSE_SERVICE_PARCEL).build(),
        )
    } else null

    val settings = ScanSettings.Builder()
        .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
        .build()

    val cb = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val name = safeDeviceName(result)
            val address = safeDeviceAddress(result)
            val key = address.ifBlank { "tmp:${result.device.hashCode()}" }

            // When software filtering, keep only supported service types (ftms/hrm/csc/rsc/power/glucose)
            val serviceUuid: UUID? = detectPrimaryService(result)
            if (!useFtmsHardwareFilter && serviceUuid == null) return

            when (serviceUuid) {
                BluetoothIds.UUID_FTMS_SERVICE -> {
                    upsertDeviceFTMS(key, name, address, result, BluetoothIds.UUID_FTMS_SERVICE)
                }
                BluetoothIds.UUID_HRM_SERVICE,
                BluetoothIds.UUID_CSC_SERVICE,
                BluetoothIds.UUID_RSC_SERVICE,
                BluetoothIds.UUID_POWER_SERVICE,
                BluetoothIds.UUID_GLUCOSE_SERVICE -> {
                    // Reuse the "hrmDevices" bucket for non-FTMS sensors
                    upsertDeviceSensor(
                        key, name, address, result, serviceUuid
                    )
                }
                else -> {
                    // If we get here with null on HW filter path, ignore
                    if (!useFtmsHardwareFilter) return
                }
            }

            onDevice(name, address, result)
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            results.forEach { r -> onScanResult(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, r) }
        }

        override fun onScanFailed(errorCode: Int) {
            Timber.tag(BtEngineState.TAG).e("onScanFailed errorCode=%d", errorCode)
        }
    }

    try {
        scanner.startScan(filters, settings, cb)
        scanCallbacks[this] = cb
        state.setScanning(true)
        Timber.tag(BtEngineState.TAG).i("Scanning… useHWFilter=%s", useFtmsHardwareFilter)

        // If hardware filters find nothing in 5s, restart with software filtering
        if (useFtmsHardwareFilter) {
            Handler(Looper.getMainLooper()).postDelayed({
                val empty = state.devices.value.isEmpty() && state.hrmDevices.value.isEmpty()
                if (empty && state.isScanning.value) {
                    Timber.tag(BtEngineState.TAG).w("No supported services w/ HW filter → restarting with SW filter")
                    stopScanImpl()
                    startScanImpl(useFtmsHardwareFilter = false, onDevice = onDevice)
                }
            }, 5_000)
        }
    } catch (se: SecurityException) {
        Timber.tag(BtEngineState.TAG).e(se, "startScan: SecurityException")
    }
}

internal fun BtEngine.stopScanImpl() {
    if (!state.isScanning.value) return
    try {
        Timber.tag(BtEngineState.TAG).i("stopScan")
        val scanner: BluetoothLeScanner? = adapter?.bluetoothLeScanner
        scanCallbacks.remove(this)?.let { cb -> scanner?.stopScan(cb) }
    } catch (_: SecurityException) {
        Timber.tag(BtEngineState.TAG).w("stopScan: SecurityException ignored")
    } finally {
        state.setScanning(false)
    }
}

/* ───────────────────────────── helpers ───────────────────────────── */

@SuppressLint("MissingPermission")
private fun BtEngine.safeDeviceName(result: ScanResult): String {
    val advertised = result.scanRecord?.deviceName
    if (!advertised.isNullOrBlank()) return advertised
    // Ask for the OS-provided device name; if permissions are missing the platform will throw.
    return try { result.device.name ?: "" } catch (_: SecurityException) { "" }
}

@SuppressLint("MissingPermission")
private fun BtEngine.safeDeviceAddress(result: ScanResult): String {
    // Ask for the OS-provided device address; if permissions are missing the platform will throw.
    return try { result.device.address ?: "" } catch (_: SecurityException) { "" }
}

/** Returns the *primary* supported service UUID for this result, or null if none. */
private fun detectPrimaryService(result: ScanResult): UUID? {
    val rec = result.scanRecord ?: return null
    val has = { u: UUID ->
        rec.serviceUuids?.any { it.uuid == u } == true
                || rec.getServiceData(ParcelUuidCompat(u)) != null
    }

    // Prefer FTMS if multiple are present; then Power/HRM/CSC/RSC/Glucose.
    return when {
        has(BluetoothIds.UUID_FTMS_SERVICE) -> BluetoothIds.UUID_FTMS_SERVICE
        //has(BluetoothIds.UUID_POWER_SERVICE) -> BluetoothIds.UUID_POWER_SERVICE
        has(BluetoothIds.UUID_HRM_SERVICE) -> BluetoothIds.UUID_HRM_SERVICE
        //has(BluetoothIds.UUID_CSC_SERVICE) -> BluetoothIds.UUID_CSC_SERVICE
        //has(BluetoothIds.UUID_RSC_SERVICE) -> BluetoothIds.UUID_RSC_SERVICE
        //has(BluetoothIds.UUID_GLUCOSE_SERVICE) -> BluetoothIds.UUID_GLUCOSE_SERVICE
        // Heuristic: HRM by name (common for straps without explicit service in adv)
        isLikelyHrmName(rec.deviceName ?: "") -> BluetoothIds.UUID_HRM_SERVICE
        else -> null
    }
}

/** Small helper to avoid importing ParcelUuid at call sites. */
private fun ParcelUuidCompat(u: UUID) = android.os.ParcelUuid(u)

private fun isLikelyHrmName(nameRaw: String): Boolean {
    if (nameRaw.isBlank()) return false
    val n = nameRaw.lowercase()
    return n.contains("polar") || n.contains("wahoo") || n.contains("garmin") ||
            n.contains("coospo") || n.contains("tickr") || n.contains("hrm") ||
            n.contains("heart")
}

/** Map a 128-bit UUID to our BtServiceType enum. */
private fun toServiceType(u: UUID): BtEngineState.BtServiceType? = when (u) {
    BluetoothIds.UUID_FTMS_SERVICE   -> BtEngineState.BtServiceType.FTMS
    BluetoothIds.UUID_HRM_SERVICE    -> BtEngineState.BtServiceType.HeartRate
    BluetoothIds.UUID_CSC_SERVICE    -> BtEngineState.BtServiceType.CSC
    BluetoothIds.UUID_RSC_SERVICE    -> BtEngineState.BtServiceType.RSC
    BluetoothIds.UUID_POWER_SERVICE  -> BtEngineState.BtServiceType.Power
    BluetoothIds.UUID_GLUCOSE_SERVICE-> BtEngineState.BtServiceType.Glucose
    else -> null
}

/* ───────────────────────────── upsert ───────────────────────────── */

private fun BtEngine.upsertDeviceFTMS(
    key: String,
    name: String,
    address: String,
    result: ScanResult,
    serviceUuid: UUID
) {
    val display = name.ifBlank { result.scanRecord?.deviceName ?: "" }

    state.devices.value[key]?.let { current ->
        val nameImproved = current.name.isBlank() && display.isNotBlank()
        val rssiChanged = abs(current.rssi - result.rssi) >= 6
        if (!nameImproved && !rssiChanged) return
    }

    val map = state.devices.value.toMutableMap()
    val dev = BtEngineState.DiscoveredDevice(
        name = display,
        address = address,
        rssi = result.rssi,
        lastSeenMs = System.currentTimeMillis(),
        scanResult = result,
        services = setOfNotNull(toServiceType(serviceUuid)), // <<< changed
        machineFeature = null // placeholder: will be filled after FTMS feature read
    )

    if (address.isNotBlank()) {
        val tmpKey = map.entries.firstOrNull { (k, v) ->
            k.startsWith("tmp:") && v.scanResult?.device == result.device
        }?.key
        if (tmpKey != null) map.remove(tmpKey)
    }

    map[key] = dev
    if (map != state.devices.value) state.putDevices(map)
}

private fun BtEngine.upsertDeviceSensor(
    key: String,
    name: String,
    address: String,
    result: ScanResult,
    serviceUuid: UUID
) {
    val display = name.ifBlank { result.scanRecord?.deviceName ?: "" }

    state.hrmDevices.value[key]?.let { current ->
        val nameImproved = current.name.isBlank() && display.isNotBlank()
        val rssiChanged = abs(current.rssi - result.rssi) >= 6
        if (!nameImproved && !rssiChanged) return
    }

    val map = state.hrmDevices.value.toMutableMap()
    val dev = BtEngineState.DiscoveredDevice(
        name = display,
        address = address,
        rssi = result.rssi,
        lastSeenMs = System.currentTimeMillis(),
        scanResult = result,
        services = setOfNotNull(toServiceType(serviceUuid)), // <<< changed
        machineFeature = null // not applicable for non-FTMS; keep null
    )

    if (address.isNotBlank()) {
        val tmpKey = map.entries.firstOrNull { (k, v) ->
            k.startsWith("tmp:") && v.scanResult?.device == result.device
        }?.key
        if (tmpKey != null) map.remove(tmpKey)
    }

    map[key] = dev
    if (map != state.hrmDevices.value) state.putHrmDevices(map)
}
