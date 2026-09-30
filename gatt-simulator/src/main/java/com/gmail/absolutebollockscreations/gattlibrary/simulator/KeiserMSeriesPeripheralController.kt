package com.gmail.absolutebollockscreations.gattlibrary.simulator

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertisingSet
import android.bluetooth.le.AdvertisingSetCallback
import android.bluetooth.le.AdvertisingSetParameters
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import com.gmail.absolutebollockscreations.gattlibrary.central.KeiserMSeriesMeasurement
import java.util.concurrent.atomic.AtomicLong
import timber.log.Timber
import com.gmail.absolutebollockscreations.gattlibrary.peripheral.AdapterNameCoordinator
import com.gmail.absolutebollockscreations.gattlibrary.peripheral.FtmsForegroundService
import com.gmail.absolutebollockscreations.gattlibrary.peripheral.advertisedName
import com.gmail.absolutebollockscreations.gattlibrary.core.vendor.KeiserMSeriesSpec

/** Pure legacy-envelope facts, kept separate from Android objects for focused unit coverage. */
internal data class KeiserMSeriesAdvertisingPlan(
    val localName: String,
    val manufacturerId: Int,
    val manufacturerPayload: ByteArray,
    val connectable: Boolean,
    val scannable: Boolean,
    val intervalUnits: Int,
    val advertisingBytes: Int,
) {
    init {
        require(manufacturerPayload.size in 1..KeiserMSeriesSimulatorEncoder.MANUFACTURER_PAYLOAD_LENGTH)
        require(advertisingBytes <= MAX_LEGACY_BYTES)
    }

    companion object {
        const val MAX_LEGACY_BYTES = 31
        const val FAST_INTERVAL_UNITS = 0x0237

        fun create(persona: SimulatorPersona.KeiserMSeries, payload: ByteArray): KeiserMSeriesAdvertisingPlan {
            val name = persona.advertisedName
            return KeiserMSeriesAdvertisingPlan(
                localName = name,
                manufacturerId = KeiserMSeriesSpec.COMPANY_ID,
                manufacturerPayload = payload.copyOf(),
                connectable = false,
                scannable = false,
                intervalUnits = persona.profile.advertisingIntervalUnits,
                // Flags AD + complete-name AD + manufacturer AD (including company ID).
                advertisingBytes = 3 + 2 + name.toByteArray(Charsets.UTF_8).size + 4 + payload.size,
            )
        }
    }
}

/**
 * Android-hosted M-series beacon.
 *
 * There is deliberately no GATT server: physical Keiser M-series bikes are non-connectable
 * manufacturer-data broadcasters. Android can request their legacy envelope and interval, but the
 * platform and controller retain final control over on-air timing.
 */
internal class KeiserMSeriesPeripheralController(
    private val context: Context,
    private val onStatus: (Status) -> Unit = {},
) {
    data class Status(
        val advertising: Boolean = false,
        val advertisedName: String? = null,
        /** Successful initial/data applications, not a count of controller-level air packets. */
        val framesApplied: Long = 0,
        val lastError: String? = null,
    )

    private val main = Handler(Looper.getMainLooper())
    private val operationEpoch = AtomicLong(0)
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    @Volatile private var status = Status()
    @Volatile private var activeSet: AdvertisingSet? = null
    private var advertiser: BluetoothLeAdvertiser? = null
    private var callback: AdvertisingSetCallback? = null
    @Volatile private var latestPayload: ByteArray? = null
    private var lastAppliedPayload: ByteArray? = null
    private var applyingPayload: ByteArray? = null
    private var updateInFlight = false
    private var startInFlight = false
    private var sessionActive = false
    private var activePersona: SimulatorPersona.KeiserMSeries? = null

    private val adapterNames = AdapterNameCoordinator(
        gateway = object : AdapterNameCoordinator.Gateway {
            @get:SuppressLint("MissingPermission", "HardwareIds")
            override val currentName: String? get() = adapter()?.name

            @SuppressLint("MissingPermission")
            override fun requestName(name: String): Boolean = adapter()?.setName(name) == true
        },
        store = object : AdapterNameCoordinator.OriginalNameStore {
            override fun read(): String? = prefs.getString(KEY_ORIGINAL_NAME, null)
            override fun write(name: String): Boolean = prefs.edit().putString(KEY_ORIGINAL_NAME, name).commit()
            override fun clear(): Boolean = prefs.edit().remove(KEY_ORIGINAL_NAME).commit()
        },
        scheduler = object : AdapterNameCoordinator.Scheduler {
            private val tasks = mutableMapOf<Any, Runnable>()
            override fun postDelayed(token: Any, delayMs: Long, action: () -> Unit) {
                cancel(token)
                val task = Runnable { tasks.remove(token); action() }
                tasks[token] = task
                main.postDelayed(task, delayMs)
            }

            override fun cancel(token: Any) {
                tasks.remove(token)?.let(main::removeCallbacks)
            }
        },
    )

    val isAdvertising: Boolean get() = status.advertising

    fun publish(manufacturerPayload: ByteArray?) {
        latestPayload = manufacturerPayload?.copyOf()
        main.post {
            if (manufacturerPayload == null) {
                suspendAdvertising()
            } else if (activeSet == null && !startInFlight && sessionActive) {
                val persona = activePersona ?: return@post
                Timber.tag(TAG).i(
                    "Keiser advertising restart requested epoch=%d persona=%s payloadBytes=%d",
                    operationEpoch.get(),
                    persona.advertisedName,
                    manufacturerPayload.size,
                )
                beginAdvertising(operationEpoch.get(), persona, null)
            } else {
                pumpPayloadUpdate()
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun start(persona: SimulatorPersona.KeiserMSeries, onResult: (Result<Unit>) -> Unit) {
        val epoch = operationEpoch.incrementAndGet()
        Timber.tag(TAG).i("Keiser advertising session start requested epoch=%d persona=%s", epoch, persona.advertisedName)
        if (!hasPermissions()) {
            failStart(IllegalStateException("Bluetooth advertising permission has not been granted"), onResult)
            return
        }
        val adapter = adapter()
        val bleAdvertiser = adapter?.bluetoothLeAdvertiser
        if (adapter == null || !adapter.isEnabled || bleAdvertiser == null) {
            failStart(IllegalStateException("Bluetooth LE advertising is unavailable"), onResult)
            return
        }
        if (!adapter.isMultipleAdvertisementSupported) {
            failStart(IllegalStateException("This Android device does not support BLE peripheral advertising"), onResult)
            return
        }

        stopResources(restoreName = false)
        publishStatus(Status())
        activePersona = persona
        latestPayload = initialPayload(persona.profile)
        advertiser = bleAdvertiser
        sessionActive = true
        adapterNames.apply(persona.advertisedName) nameCallback@{ result ->
            if (operationEpoch.get() != epoch) return@nameCallback
            result.fold(
                onSuccess = { beginAdvertising(epoch, persona, onResult) },
                onFailure = { failStart(it, onResult) },
            )
        }
    }

    fun stop() {
        val epoch = operationEpoch.incrementAndGet()
        Timber.tag(TAG).i("Keiser advertising session stop requested epoch=%d", epoch)
        stopResources(restoreName = true)
        publishStatus(Status())
    }

    fun dispose() {
        Timber.tag(TAG).i("Keiser advertising controller disposed")
        stop()
        adapterNames.cancelAll("Keiser M-series controller disposed")
    }

    @SuppressLint("MissingPermission")
    private fun beginAdvertising(
        epoch: Long,
        persona: SimulatorPersona.KeiserMSeries,
        initialResult: ((Result<Unit>) -> Unit)?,
    ) {
        val payload = latestPayload ?: return
        val plan = KeiserMSeriesAdvertisingPlan.create(persona, payload)
        Timber.tag(TAG).i(
            "Calling startAdvertisingSet epoch=%d persona=%s payloadBytes=%d intervalUnits=0x%04X initial=%s",
            epoch,
            persona.advertisedName,
            payload.size,
            plan.intervalUnits,
            initialResult != null,
        )
        startInFlight = true
        val sessionCallback = object : AdvertisingSetCallback() {
            override fun onAdvertisingSetStarted(set: AdvertisingSet?, txPower: Int, result: Int) {
                if (operationEpoch.get() != epoch) {
                    Timber.tag(TAG).w(
                        "Ignoring stale advertising-start callback callbackEpoch=%d activeEpoch=%d result=%d setPresent=%s",
                        epoch,
                        operationEpoch.get(),
                        result,
                        set != null,
                    )
                    return
                }
                startInFlight = false
                if (result != ADVERTISE_SUCCESS || set == null) {
                    Timber.tag(TAG).e(
                        "Keiser advertising start failed epoch=%d persona=%s result=%d setPresent=%s",
                        epoch,
                        persona.advertisedName,
                        result,
                        set != null,
                    )
                    val failure = IllegalStateException("Keiser advertising failed (code $result)")
                    if (initialResult != null) {
                        failStart(failure, initialResult)
                    } else {
                        publishStatus(status.copy(advertising = false, lastError = failure.message))
                    }
                    return
                }
                activeSet = set
                lastAppliedPayload = plan.manufacturerPayload
                Timber.tag(TAG).i(
                    "Keiser advertising started epoch=%d persona=%s txPower=%d payloadBytes=%d initial=%s",
                    epoch,
                    plan.localName,
                    txPower,
                    plan.manufacturerPayload.size,
                    initialResult != null,
                )
                publishStatus(
                    status.copy(
                        advertising = true,
                        advertisedName = plan.localName,
                        framesApplied = status.framesApplied + 1,
                        lastError = null,
                    ),
                )
                pumpPayloadUpdate()
                if (initialResult != null) {
                    FtmsForegroundService.start(
                        context,
                        "Sharing as ${plan.localName}",
                        "Simulated Keiser M-series advertisements; measurements only",
                    ) { foreground ->
                        if (operationEpoch.get() != epoch) return@start
                        foreground.fold(
                            onSuccess = { initialResult(Result.success(Unit)) },
                            onFailure = { failStart(it, initialResult) },
                        )
                    }
                }
            }

            override fun onAdvertisingDataSet(set: AdvertisingSet?, result: Int) {
                if (operationEpoch.get() != epoch || set !== activeSet) {
                    Timber.tag(TAG).w(
                        "Ignoring stale advertising-data callback callbackEpoch=%d activeEpoch=%d result=%d setMatches=%s",
                        epoch,
                        operationEpoch.get(),
                        result,
                        set === activeSet,
                    )
                    return
                }
                updateInFlight = false
                val applied = applyingPayload
                applyingPayload = null
                if (result == ADVERTISE_SUCCESS && applied != null) {
                    lastAppliedPayload = applied
                    publishStatus(status.copy(framesApplied = status.framesApplied + 1, lastError = null))
                    pumpPayloadUpdate()
                } else {
                    Timber.tag(TAG).e("Keiser advertising data update failed epoch=%d result=%d", epoch, result)
                    publishStatus(status.copy(lastError = "Keiser data update failed (code $result)"))
                }
            }

            override fun onAdvertisingSetStopped(set: AdvertisingSet?) {
                Timber.tag(TAG).i(
                    "Keiser advertising stopped callbackEpoch=%d activeEpoch=%d setMatches=%s",
                    epoch,
                    operationEpoch.get(),
                    set === activeSet,
                )
            }
        }
        callback = sessionCallback
        val parameters = AdvertisingSetParameters.Builder()
            .setLegacyMode(true)
            .setConnectable(plan.connectable)
            .setScannable(plan.scannable)
            .setInterval(plan.intervalUnits)
            .setTxPowerLevel(AdvertisingSetParameters.TX_POWER_MEDIUM)
            .build()
        runCatching {
            val activeAdvertiser = checkNotNull(advertiser) { "Bluetooth LE advertising is unavailable" }
            activeAdvertiser.startAdvertisingSet(
                parameters,
                advertiseData(plan),
                null,
                null,
                null,
                sessionCallback,
            )
        }.onFailure { failure ->
            startInFlight = false
            Timber.tag(TAG).e(failure, "startAdvertisingSet threw epoch=%d persona=%s", epoch, persona.advertisedName)
            if (initialResult != null) {
                failStart(failure, initialResult)
            } else {
                publishStatus(status.copy(advertising = false, lastError = failure.message))
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun pumpPayloadUpdate() {
        val set = activeSet ?: return
        val latest = latestPayload ?: return
        if (updateInFlight || lastAppliedPayload?.let(latest::contentEquals) == true) return
        val payload = latest.copyOf()
        val persona = activePersona ?: return
        applyingPayload = payload
        updateInFlight = true
        runCatching { set.setAdvertisingData(advertiseData(KeiserMSeriesAdvertisingPlan.create(persona, payload))) }
            .onFailure { failure ->
                updateInFlight = false
                applyingPayload = null
                Timber.tag(TAG).e(failure, "setAdvertisingData threw epoch=%d", operationEpoch.get())
                publishStatus(status.copy(lastError = failure.message ?: "Keiser data update failed"))
            }
    }

    @SuppressLint("MissingPermission")
    private fun suspendAdvertising() {
        val activeCallback = callback
        if (activeCallback == null) {
            if (activeSet != null || startInFlight) {
                Timber.tag(TAG).w(
                    "Keiser advertising suspend found inconsistent state epoch=%d activeSet=%s startInFlight=%s",
                    operationEpoch.get(),
                    activeSet != null,
                    startInFlight,
                )
            }
            return
        }
        Timber.tag(TAG).i(
            "Keiser advertising suspend requested epoch=%d activeSet=%s startInFlight=%s updateInFlight=%s",
            operationEpoch.get(),
            activeSet != null,
            startInFlight,
            updateInFlight,
        )
        runCatching { advertiser?.stopAdvertisingSet(activeCallback) }
            .onFailure { Timber.tag(TAG).e(it, "stopAdvertisingSet threw during suspend epoch=%d", operationEpoch.get()) }
        callback = null
        activeSet = null
        startInFlight = false
        updateInFlight = false
        applyingPayload = null
        lastAppliedPayload = null
        publishStatus(status.copy(advertising = false, lastError = null))
    }

    private fun advertiseData(plan: KeiserMSeriesAdvertisingPlan): AdvertiseData =
        AdvertiseData.Builder()
            .setIncludeDeviceName(true)
            .setIncludeTxPowerLevel(false)
            .addManufacturerData(plan.manufacturerId, plan.manufacturerPayload)
            .build()

    private fun failStart(failure: Throwable, onResult: (Result<Unit>) -> Unit) {
        Timber.tag(TAG).e(failure, "Keiser advertising session start failed epoch=%d", operationEpoch.get())
        stopResources(restoreName = true)
        publishStatus(Status(lastError = failure.message))
        onResult(Result.failure(failure))
    }

    @SuppressLint("MissingPermission")
    private fun stopResources(restoreName: Boolean) {
        callback?.let { active ->
            runCatching { advertiser?.stopAdvertisingSet(active) }
                .onFailure { Timber.tag(TAG).e(it, "stopAdvertisingSet threw during teardown epoch=%d", operationEpoch.get()) }
        }
        callback = null
        activeSet = null
        advertiser = null
        sessionActive = false
        activePersona = null
        latestPayload = null
        startInFlight = false
        updateInFlight = false
        applyingPayload = null
        lastAppliedPayload = null
        FtmsForegroundService.stop(context)
        if (restoreName) adapterNames.restore()
    }

    private fun publishStatus(next: Status) {
        status = next
        onStatus(next)
    }

    private fun hasPermissions(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_ADVERTISE) ==
                PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

    @SuppressLint("MissingPermission")
    private fun adapter(): BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    companion object {
        private const val TAG = "KeiserPeripheral"
        private const val PREFS = "com.gmail.absolutebollockscreations.Fitbridge.peripheral"
        private const val KEY_ORIGINAL_NAME = "original_adapter_name"

        private fun initialPayload(profile: KeiserMSeriesSimulatorProfile): ByteArray = KeiserMSeriesSimulatorEncoder.encode(
            FtmsMockSample(
                elapsedSeconds = 0,
                heartRate = 0,
                caloriesTotal = 0,
                caloriesPerHour = 0,
                caloriesPerMinute = 0,
                distanceMeters = 0,
                cadence = 0.0,
                resistance = 0,
                powerWatts = 0,
            ),
            profile,
        )
    }
}
