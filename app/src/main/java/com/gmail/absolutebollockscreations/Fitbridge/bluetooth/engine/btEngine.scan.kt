/*  ┏━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━┓
 *  ┃ btEngine.Scan.kt — BLE scan layer + device catalog for BtEngine          ┃
 *  ┗━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━┛
 *
 *  PURPOSE
 *  - Owns the Bluetooth LE scan lifecycle for BtEngine:
 *      • startScanImpl(...)  → start scanning with HW or SW filters
 *      • stopScanImpl()      → stop scanning and clear callback
 *  - Maintains the in-memory “discovered devices” catalog in BtEngineState:
 *      • all supported machines and sensors → state.discoveredDevices
 *  - Normalizes scan results into stable keys, safe names/addresses, and
 *    per-service classification.
 *
 *  KEY FEATURES
 *  - Hardware vs software filtering:
 *      • When useFtmsHardwareFilter = true:
 *          - Uses OS-level ScanFilter OR’ed on FTMS, HRM, CSC, RSC, and CPS services.
 *          - If no supported devices are discovered within 5 seconds, the scan
 *            is automatically restarted with software filtering.
 *      • When useFtmsHardwareFilter = false:
 *          - Uses a wide-open scan and filters in software via ranked matcher candidates.
 *
 *  - Service detection & classification:
 *      • detectServiceCandidates(result):
 *          - Produces structured match evidence and a deterministic ranked list.
 *          - The current two-bucket UI consumes only the top candidate until Phase 0.8.
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
 *      • upsertDiscoveredDevice(...) → updates one canonical discovery registry:
 *          - Refreshes RSSI and monotonic lastSeenMs for every packet.
 *          - Collapses entries that share a nonblank MAC address.
 *          - Migrates tmp: keys to real addresses when available.
 *          - Decodes an optimistic machine-type hint from FTMS Service Data;
 *            machineFeature remains reserved for connected GATT discovery.
 *      • upsertDeviceSensor(...) → same pattern for HRM/other sensors, backing
 *        service and machine-candidate evidence are merged by canonical address.
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
 *        state.isScanning. A session-scoped maintenance task removes entries
 *        unseen for more than five seconds while scanning remains active.
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
import android.os.SystemClock
import com.gmail.absolutebollockscreations.Fitbridge.BuildConfig
import timber.log.Timber
import java.util.Locale
import java.util.WeakHashMap
import java.util.UUID

private data class ScanSession(
    val callback: ScanCallback,
    val handler: Handler,
    val cleanup: Runnable,
)

// Keep per-engine scan work without extending BtEngineState.
private val scanSessions = WeakHashMap<BtEngine, ScanSession>()

private const val SCAN_ENTRY_STALE_AFTER_MS = 5_000L
private const val SCAN_CLEANUP_INTERVAL_MS = 1_000L

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

    // Build OR filters at the Android boundary from the canonical matcher registry.
    val filters: List<ScanFilter>? = if (useFtmsHardwareFilter) {
        hardwareScanServiceUuids().map { uuid ->
            ScanFilter.Builder().setServiceUuid(ParcelUuidCompat(uuid)).build()
        }
    } else null

    val settings = ScanSettings.Builder()
        .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
        .build()

    val phase05EvidenceKeys = mutableSetOf<String>()
    val cb = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val name = safeDeviceName(result)
            val address = canonicalScanAddress(safeDeviceAddress(result))
            val key = address.ifBlank { "tmp:${result.device.hashCode()}" }

            if (BuildConfig.PHASE_05_HARDWARE_TEST && phase05EvidenceKeys.add(key)) {
                logPhase05AdvertisementEvidence(result)
            }

            // Recorded before the filter below, and with payloads unparsed. The
            // Keiser M3i case is exactly why: its entire metric stream lives in
            // manufacturer data FitBridge does not decode, so a device this scan
            // path discards is precisely the one a capture needs to have seen.
            captureAdvertisement(name, result)

            val candidates = detectServiceCandidates(result)
            val primary = candidates.firstOrNull { it.knownService != null }
            if (!useFtmsHardwareFilter && primary == null) return

            when (primary?.knownService) {
                KnownBleService.FTMS -> {
                    upsertDiscoveredDevice(
                        key, name, address, result, primary.serviceUuid.toUuid(),
                        machineCandidate = true,
                    )
                }
                KnownBleService.HRM,
                KnownBleService.CSC,
                KnownBleService.RSC,
                KnownBleService.CPS,
                KnownBleService.GLUCOSE -> {
                    upsertDiscoveredDevice(
                        key, name, address, result, primary.serviceUuid.toUuid(),
                        machineCandidate = false,
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

    val handler = Handler(Looper.getMainLooper())
    val cleanup = object : Runnable {
        override fun run() {
            val active = scanSessions[this@startScanImpl]
            if (active?.callback !== cb || !state.isScanning.value) return
            pruneStaleScanEntries(SystemClock.elapsedRealtime())
            handler.postDelayed(this, SCAN_CLEANUP_INTERVAL_MS)
        }
    }

    try {
        scanner.startScan(filters, settings, cb)
        scanSessions[this] = ScanSession(cb, handler, cleanup)
        state.setScanning(true)
        handler.postDelayed(cleanup, SCAN_CLEANUP_INTERVAL_MS)
        Timber.tag(BtEngineState.TAG).i("Scanning… useHWFilter=%s", useFtmsHardwareFilter)

        // If hardware filters find nothing in 5s, restart with software filtering
        if (useFtmsHardwareFilter) {
            handler.postDelayed({
                if (scanSessions[this@startScanImpl]?.callback === cb) {
                    val empty = state.discoveredDevices.value.isEmpty()
                    if (empty && state.isScanning.value) {
                        Timber.tag(BtEngineState.TAG).w("No supported services w/ HW filter → restarting with SW filter")
                        stopScanImpl()
                        startScanImpl(useFtmsHardwareFilter = false, onDevice = onDevice)
                    }
                }
            }, 5_000)
        }
    } catch (se: SecurityException) {
        Timber.tag(BtEngineState.TAG).e(se, "startScan: SecurityException")
    }
}

internal fun BtEngine.stopScanImpl() {
    val session = scanSessions.remove(this)
    if (!state.isScanning.value && session == null) return
    try {
        Timber.tag(BtEngineState.TAG).i("stopScan")
        val scanner: BluetoothLeScanner? = adapter?.bluetoothLeScanner
        session?.handler?.removeCallbacks(session.cleanup)
        session?.callback?.let { cb -> scanner?.stopScan(cb) }
    } catch (_: SecurityException) {
        Timber.tag(BtEngineState.TAG).w("stopScan: SecurityException ignored")
    } finally {
        state.setScanning(false)
    }
}

@SuppressLint("MissingPermission")
private fun BtEngine.pruneStaleScanEntries(nowMs: Long) {
    val connectedAddresses = state.connectedDevices.value
        .mapTo(linkedSetOf()) { canonicalScanAddress(it.address) }
    val fresh = retainFreshScanEntries(
        entries = state.discoveredDevices.value,
        nowMs = nowMs,
        staleAfterMs = SCAN_ENTRY_STALE_AFTER_MS,
        lastSeenMs = { it.lastSeenMs },
        alwaysRetain = { canonicalScanAddress(it.address) in connectedAddresses },
    )
    val removed = state.discoveredDevices.value.size - fresh.size
    if (removed > 0) {
        state.putDiscoveredDevices(fresh)
        Timber.tag(BtEngineState.TAG).d(
            "Pruned stale scan entries: removed=%d",
            removed,
        )
    }
}

internal fun <K, V> retainFreshScanEntries(
    entries: Map<K, V>,
    nowMs: Long,
    staleAfterMs: Long,
    lastSeenMs: (V) -> Long,
    alwaysRetain: (V) -> Boolean = { false },
): Map<K, V> = entries.filterValues { entry ->
    alwaysRetain(entry) || nowMs - lastSeenMs(entry) <= staleAfterMs
}

internal fun <K, V> duplicateAddressKeys(
    entries: Map<K, V>,
    keepKey: K,
    address: String,
    addressOf: (V) -> String,
): Set<K> {
    val canonicalAddress = canonicalScanAddress(address)
    if (canonicalAddress.isBlank()) return emptySet()
    return entries.entries
        .asSequence()
        .filter { (key, value) ->
            key != keepKey && canonicalScanAddress(addressOf(value)) == canonicalAddress
        }
        .map { it.key }
        .toSet()
}

internal fun canonicalScanAddress(address: String): String =
    address.trim().uppercase(Locale.US)

/** Parse GAP AD structures and return the little-endian Appearance (type 0x19), if valid. */
internal fun gapAppearanceFromAdvertisingData(bytes: ByteArray): Int? {
    var offset = 0
    while (offset < bytes.size) {
        val structureLength = bytes[offset].toInt() and 0xFF
        if (structureLength == 0) return null
        val structureEnd = offset + 1 + structureLength
        if (structureEnd > bytes.size || structureLength < 1) return null
        val type = bytes[offset + 1].toInt() and 0xFF
        if (type == 0x19) {
            if (structureLength < 3) return null
            val low = bytes[offset + 2].toInt() and 0xFF
            val high = bytes[offset + 3].toInt() and 0xFF
            return low or (high shl 8)
        }
        offset = structureEnd
    }
    return null
}

/* ───────────────────────────── helpers ───────────────────────────── */

@SuppressLint("MissingPermission")
/**
 * Publishes one advertisement to the §0.9 capture channel with **raw payloads**.
 *
 * Manufacturer data and service data are recorded exactly as broadcast rather
 * than parsed. §0.9.4 is explicit about this and the Keiser M3i is the reason:
 * its whole metric stream lives in manufacturer data FitBridge has no decoder
 * for, so a parsed-only record would contain nothing useful about the very
 * device someone is trying to add support for.
 *
 * No address is recorded — §0.9.6 permits addresses only in the file header, and
 * only when the user opts in while sharing.
 */
private fun captureAdvertisement(name: String, result: ScanResult) {
    val record = result.scanRecord ?: return
    val manufacturer = buildMap {
        val data = record.manufacturerSpecificData ?: return@buildMap
        for (index in 0 until data.size()) {
            val payload = data.valueAt(index) ?: continue
            put(data.keyAt(index), payload)
        }
    }
    val serviceData = record.serviceData.orEmpty()
        .mapNotNull { (parcel, bytes) -> bytes?.let { parcel.uuid to it } }
        .toMap()

    CaptureHooks.scan(
        name = name.ifBlank { null },
        rssi = result.rssi,
        serviceUuids = record.serviceUuids.orEmpty().map { it.uuid },
        manufacturerData = manufacturer,
        serviceData = serviceData,
    )
}

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

private val deviceMatcherRegistry = DeviceMatcherRegistry.standard()

internal fun rankedServiceCandidates(observation: MatchObservation): List<RankedMatchCandidate> =
    deviceMatcherRegistry.rankedCandidates(observation)

internal fun hardwareScanServiceUuids(): List<UUID> = listOf(
    KnownBleService.FTMS,
    KnownBleService.HRM,
    KnownBleService.CSC,
    KnownBleService.RSC,
    KnownBleService.CPS,
).map { service ->
    deviceMatcherRegistry.registeredServices()
        .first { deviceMatcherRegistry.knownService(it) == service }
        .toUuid()
}

private fun detectServiceCandidates(result: ScanResult): List<RankedMatchCandidate> {
    val record = result.scanRecord ?: return emptyList()
    val registeredServices = deviceMatcherRegistry.registeredServices()
    val manufacturerIds = buildSet {
        val data = record.manufacturerSpecificData
        for (index in 0 until data.size()) add(data.keyAt(index))
    }
    return rankedServiceCandidates(
        MatchObservation(
            localName = record.deviceName,
            manufacturerCompanyIds = manufacturerIds,
            advertisedServiceUuids = record.serviceUuids.orEmpty()
                .mapTo(linkedSetOf()) { CanonicalUuid.fromUuid(it.uuid) },
            serviceDataUuids = registeredServices.filterTo(linkedSetOf()) { uuid ->
                record.getServiceData(ParcelUuidCompat(uuid.toUuid())) != null
            },
        ),
    )
}

/** Small helper to avoid importing ParcelUuid at call sites. */
private fun ParcelUuidCompat(u: UUID) = android.os.ParcelUuid(u)

/** Structural advertisement evidence only: UUIDs and company identifiers, never raw payloads. */
private fun logPhase05AdvertisementEvidence(result: ScanResult) {
    val record = result.scanRecord
    if (record == null) {
        Timber.tag("Phase05Hw").i("PHASE05 advertisement scanRecord=missing rssi=%d", result.rssi)
        return
    }
    val serviceUuids = record.serviceUuids.orEmpty()
        .map { it.uuid.toString() }
        .sorted()
    val serviceDataUuids = supportedAdvertisementServiceUuids
        .filter { record.getServiceData(ParcelUuidCompat(it)) != null }
        .map(UUID::toString)
        .sorted()
    val manufacturerIds = buildList {
        val data = record.manufacturerSpecificData
        for (index in 0 until data.size()) add(data.keyAt(index))
    }.sorted()

    Timber.tag("Phase05Hw").i(
        "PHASE05 advertisement rssi=%d txPower=%d services=%s serviceData=%s manufacturerIds=%s",
        result.rssi,
        record.txPowerLevel,
        serviceUuids,
        serviceDataUuids,
        manufacturerIds,
    )
}

/** Map a full UUID through the canonical registry to our current scan-list service model. */
private fun toServiceType(u: UUID): BtEngineState.BtServiceType? =
    deviceMatcherRegistry.knownService(CanonicalUuid.fromUuid(u))?.toServiceType()

private fun KnownBleService.toServiceType(): BtEngineState.BtServiceType = when (this) {
    KnownBleService.FTMS -> BtEngineState.BtServiceType.FTMS
    KnownBleService.HRM -> BtEngineState.BtServiceType.HeartRate
    KnownBleService.CSC -> BtEngineState.BtServiceType.CSC
    KnownBleService.RSC -> BtEngineState.BtServiceType.RSC
    KnownBleService.CPS -> BtEngineState.BtServiceType.Power
    KnownBleService.GLUCOSE -> BtEngineState.BtServiceType.Glucose
}

private val supportedAdvertisementServiceUuids =
    deviceMatcherRegistry.registeredServices().map(CanonicalUuid::toUuid)

internal fun advertisedServiceTypes(
    advertisedUuids: Set<UUID>,
    serviceDataUuids: Set<UUID> = emptySet(),
): Set<BtEngineState.BtServiceType> =
    supportedAdvertisementServiceUuids.mapNotNullTo(linkedSetOf()) { uuid ->
        if (uuid in advertisedUuids || uuid in serviceDataUuids) toServiceType(uuid) else null
    }

/** Preserve every supported service in an advertisement, not only its scan-list category. */
private fun advertisedServiceTypes(result: ScanResult): Set<BtEngineState.BtServiceType> {
    val record = result.scanRecord ?: return emptySet()
    val advertisedUuids = record.serviceUuids.orEmpty().mapTo(linkedSetOf()) { it.uuid }
    val serviceDataUuids = supportedAdvertisementServiceUuids.filterTo(linkedSetOf()) { uuid ->
        record.getServiceData(ParcelUuidCompat(uuid)) != null
    }
    return advertisedServiceTypes(advertisedUuids, serviceDataUuids)
}

/* ───────────────────────────── upsert ───────────────────────────── */

private fun BtEngine.upsertDiscoveredDevice(
    key: String,
    name: String,
    address: String,
    result: ScanResult,
    serviceUuid: UUID,
    machineCandidate: Boolean,
) {
    synchronized(state) {
        val display = name.ifBlank { result.scanRecord?.deviceName ?: "" }
        val advertisedMachineFeature = if (machineCandidate) {
            BtServiceProfiles.activityTypeFromFtmsServiceData(
                result.scanRecord?.getServiceData(BluetoothIds.FTMS_SERVICE_PARCEL)
            )?.ordinal
        } else {
            null
        }
        val map = state.discoveredDevices.value.toMutableMap()
        val current = map[key] ?: map.values.firstOrNull { device ->
            address.isNotBlank() && canonicalScanAddress(device.address) == address
        }
        val retainedServices = if (
            advertisedMachineFeature != null &&
            current?.advertisedMachineFeature != null &&
            advertisedMachineFeature != current.advertisedMachineFeature
        ) {
            // A simulator can switch personas without changing its address. Do not carry
            // bike-only services such as CSC/CPS into a newly advertised rower persona.
            emptySet()
        } else {
            current?.services.orEmpty()
        }
        val dev = BtEngineState.DiscoveredDevice(
            name = display.ifBlank { current?.name.orEmpty() },
            address = address,
            rssi = result.rssi,
            lastSeenMs = SystemClock.elapsedRealtime(),
            scanResult = result,
            services = retainedServices +
                advertisedServiceTypes(result) +
                setOfNotNull(toServiceType(serviceUuid)),
            machineCandidate = current?.machineCandidate == true || machineCandidate,
            machineFeature = current?.machineFeature,
            advertisedMachineFeature =
                advertisedMachineFeature ?: current?.advertisedMachineFeature,
            appearance = result.scanRecord?.bytes
                ?.let(::gapAppearanceFromAdvertisingData)
                ?: current?.appearance,
        )

        if (address.isNotBlank()) {
            duplicateAddressKeys(map, key, address) { it.address }.forEach(map::remove)
            map.entries
                .filter { (candidateKey, device) ->
                    candidateKey != key && candidateKey.startsWith("tmp:") &&
                        device.scanResult?.device == result.device
                }
                .map { it.key }
                .forEach(map::remove)
        }

        map[key] = dev
        state.putDiscoveredDevices(map)
    }
}
