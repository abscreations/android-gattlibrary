package com.gmail.absolutebollockscreations.Fitbridge.bluetooth.peripheral

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
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine.KeiserMSeriesMeasurement
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.simulator.FtmsMockSample
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.simulator.KeiserMSeriesSimulatorEncoder
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.simulator.SimulatorPersona
import java.util.concurrent.atomic.AtomicLong

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
        require(manufacturerPayload.size == KeiserMSeriesSimulatorEncoder.MANUFACTURER_PAYLOAD_LENGTH)
        require(advertisingBytes <= MAX_LEGACY_BYTES)
    }

    companion object {
        const val MAX_LEGACY_BYTES = 31
        const val FAST_INTERVAL_UNITS = 0x0237

        fun create(payload: ByteArray): KeiserMSeriesAdvertisingPlan {
            val name = SimulatorPersona.KeiserMSeries.advertisedName
            return KeiserMSeriesAdvertisingPlan(
                localName = name,
                manufacturerId = KeiserMSeriesMeasurement.COMPANY_ID,
                manufacturerPayload = payload.copyOf(),
                connectable = false,
                scannable = false,
                intervalUnits = FAST_INTERVAL_UNITS,
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
    @Volatile private var latestPayload: ByteArray = initialPayload()
    private var lastAppliedPayload: ByteArray? = null
    private var applyingPayload: ByteArray? = null
    private var updateInFlight = false

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

    fun publish(sample: FtmsMockSample) {
        latestPayload = KeiserMSeriesSimulatorEncoder.encode(sample)
        main.post { pumpPayloadUpdate() }
    }

    @SuppressLint("MissingPermission")
    fun start(onResult: (Result<Unit>) -> Unit) {
        val epoch = operationEpoch.incrementAndGet()
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
        latestPayload = initialPayload()
        advertiser = bleAdvertiser
        adapterNames.apply(SimulatorPersona.KeiserMSeries.advertisedName) nameCallback@{ result ->
            if (operationEpoch.get() != epoch) return@nameCallback
            result.fold(
                onSuccess = { beginAdvertising(epoch, onResult) },
                onFailure = { failStart(it, onResult) },
            )
        }
    }

    fun stop() {
        operationEpoch.incrementAndGet()
        stopResources(restoreName = true)
        publishStatus(Status())
    }

    fun dispose() {
        stop()
        adapterNames.cancelAll("Keiser M-series controller disposed")
    }

    @SuppressLint("MissingPermission")
    private fun beginAdvertising(epoch: Long, onResult: (Result<Unit>) -> Unit) {
        val plan = KeiserMSeriesAdvertisingPlan.create(latestPayload)
        val sessionCallback = object : AdvertisingSetCallback() {
            override fun onAdvertisingSetStarted(set: AdvertisingSet?, txPower: Int, result: Int) {
                if (operationEpoch.get() != epoch) return
                if (result != ADVERTISE_SUCCESS || set == null) {
                    failStart(IllegalStateException("Keiser advertising failed (code $result)"), onResult)
                    return
                }
                activeSet = set
                lastAppliedPayload = plan.manufacturerPayload
                publishStatus(
                    Status(
                        advertising = true,
                        advertisedName = plan.localName,
                        framesApplied = 1,
                    ),
                )
                FtmsForegroundService.start(
                    context,
                    "Sharing as ${plan.localName}",
                    "Simulated Keiser M-series advertisements; measurements only",
                ) { foreground ->
                    if (operationEpoch.get() != epoch) return@start
                    foreground.fold(
                        onSuccess = { onResult(Result.success(Unit)) },
                        onFailure = { failStart(it, onResult) },
                    )
                }
            }

            override fun onAdvertisingDataSet(set: AdvertisingSet?, result: Int) {
                if (operationEpoch.get() != epoch || set !== activeSet) return
                updateInFlight = false
                val applied = applyingPayload
                applyingPayload = null
                if (result == ADVERTISE_SUCCESS && applied != null) {
                    lastAppliedPayload = applied
                    publishStatus(status.copy(framesApplied = status.framesApplied + 1, lastError = null))
                    pumpPayloadUpdate()
                } else {
                    publishStatus(status.copy(lastError = "Keiser data update failed (code $result)"))
                }
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
        advertiser?.startAdvertisingSet(
            parameters,
            advertiseData(plan),
            null,
            null,
            null,
            sessionCallback,
        )
    }

    @SuppressLint("MissingPermission")
    private fun pumpPayloadUpdate() {
        val set = activeSet ?: return
        if (updateInFlight || lastAppliedPayload?.let(latestPayload::contentEquals) == true) return
        val payload = latestPayload.copyOf()
        applyingPayload = payload
        updateInFlight = true
        runCatching { set.setAdvertisingData(advertiseData(KeiserMSeriesAdvertisingPlan.create(payload))) }
            .onFailure { failure ->
                updateInFlight = false
                applyingPayload = null
                publishStatus(status.copy(lastError = failure.message ?: "Keiser data update failed"))
            }
    }

    private fun advertiseData(plan: KeiserMSeriesAdvertisingPlan): AdvertiseData =
        AdvertiseData.Builder()
            .setIncludeDeviceName(true)
            .setIncludeTxPowerLevel(false)
            .addManufacturerData(plan.manufacturerId, plan.manufacturerPayload)
            .build()

    private fun failStart(failure: Throwable, onResult: (Result<Unit>) -> Unit) {
        stopResources(restoreName = true)
        publishStatus(Status(lastError = failure.message))
        onResult(Result.failure(failure))
    }

    @SuppressLint("MissingPermission")
    private fun stopResources(restoreName: Boolean) {
        callback?.let { active -> runCatching { advertiser?.stopAdvertisingSet(active) } }
        callback = null
        activeSet = null
        advertiser = null
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
        private const val PREFS = "com.gmail.absolutebollockscreations.Fitbridge.peripheral"
        private const val KEY_ORIGINAL_NAME = "original_adapter_name"

        private fun initialPayload(): ByteArray = KeiserMSeriesSimulatorEncoder.encode(
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
        )
    }
}
