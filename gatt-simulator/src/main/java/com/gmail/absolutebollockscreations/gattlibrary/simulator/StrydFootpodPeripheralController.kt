package com.gmail.absolutebollockscreations.gattlibrary.simulator

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import com.gmail.absolutebollockscreations.gattlibrary.central.BluetoothIds
import java.util.concurrent.atomic.AtomicLong
import com.gmail.absolutebollockscreations.gattlibrary.peripheral.AdapterNameCoordinator
import com.gmail.absolutebollockscreations.gattlibrary.peripheral.FtmsForegroundService
import com.gmail.absolutebollockscreations.gattlibrary.peripheral.advertisedName

/**
 * Lifecycle, gait integration and adapter-name owner for the Stryd footpod simulator peripheral.
 *
 * The controller owns the [RunningStrideAccumulator] rather than the advertiser because the
 * accumulator is what makes the RSC and CPS streams agree: both characteristics are rendered from
 * one snapshot taken once per [publish], so the cadence a client reads on 0x2A53 and the crank
 * counter it reads on 0x2A63 describe the same strides. Splitting them across two encoders that
 * each integrate their own phase is exactly the drift the captures show real hardware never has.
 */
internal class StrydFootpodPeripheralController(
    private val context: Context,
    private val onStatus: (Status) -> Unit = {},
) : StrydFootpodPeripheralAdvertiser.Listener {
    data class Status(
        val advertising: Boolean = false,
        val connected: Boolean = false,
        val subscribed: Boolean = false,
        val advertisedName: String? = null,
        val packetsSent: Long = 0,
        val lastError: String? = null,
    )

    private val main = Handler(Looper.getMainLooper())
    private val operationEpoch = AtomicLong(0)
    private val advertiser = StrydFootpodPeripheralAdvertiser(context, this)
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val strides = RunningStrideAccumulator()
    private val battery = SyntheticBatteryLevel()
    private var packets = 0L
    private var lastBatteryPublishMs = 0L
    private var lastBatteryPercent = -1
    @Volatile private var status = Status()

    private val adapterNames = AdapterNameCoordinator(
        gateway = object : AdapterNameCoordinator.Gateway {
            @get:SuppressLint("MissingPermission", "HardwareIds")
            override val currentName: String?
                get() = adapter()?.name

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

    val isAdvertising: Boolean get() = advertiser.isAdvertising

    /**
     * Render one generated ride tick as the footpod's three notifications.
     *
     * Battery is rate-limited to the captured five-second tick rather than sent every tick: a
     * battery level that changes once per ten minutes does not need 1 Hz, and the captures show
     * multiples of five seconds throughout.
     */
    fun publish(sample: FtmsMockSample, nowMs: Long = System.currentTimeMillis()) {
        val speedMps = sample.speedKph?.takeIf { it.isFinite() }?.div(3.6)
        val gait = strides.update(speedMps, nowMs)

        advertiser.publish(
            BluetoothIds.UUID_RSC_MEASUREMENT,
            StrydFootpodEncoder.rscMeasurement(
                speedMps = gait.speedMps,
                cadenceStridesPerMinute = gait.cadenceStridesPerMinute,
                strideLengthCm = gait.strideLengthCm,
                totalDistanceMetres = gait.totalDistanceMetres,
                running = gait.running,
            ),
        )
        advertiser.publish(
            BluetoothIds.UUID_CYCLING_POWER_MEASUREMENT,
            StrydFootpodEncoder.cyclingPowerMeasurement(
                instantaneousPowerWatts = sample.powerWatts ?: 0,
                cumulativeStrides = gait.cumulativeStrides,
                lastStrideEventTime1024 = gait.lastStrideEventTime1024,
            ),
        )

        val level = battery.levelAt(sample.elapsedSeconds)
        val due = lastBatteryPublishMs == 0L ||
            nowMs - lastBatteryPublishMs >= StrydFootpodEncoder.BATTERY_PERIOD_MS
        if (due || level != lastBatteryPercent) {
            lastBatteryPublishMs = nowMs
            lastBatteryPercent = level
            advertiser.publish(BluetoothIds.UUID_BATTERY_LEVEL, byteArrayOf(level.toByte()))
        }
    }

    @SuppressLint("MissingPermission")
    fun start(onResult: (Result<Unit>) -> Unit) {
        val epoch = operationEpoch.incrementAndGet()
        if (!hasPermissions()) {
            val error = IllegalStateException("Bluetooth advertising permission has not been granted")
            publishStatus(Status(lastError = error.message))
            onResult(Result.failure(error))
            return
        }
        stopResources(restoreName = false)
        packets = 0
        strides.reset()
        lastBatteryPublishMs = 0L
        lastBatteryPercent = -1
        val persona = SimulatorPersona.StrydFootpod
        adapterNames.apply(persona.advertisedName) nameCallback@{ nameResult ->
            if (operationEpoch.get() != epoch) return@nameCallback
            nameResult.fold(
                onSuccess = {
                    advertiser.start advertiserCallback@{ result ->
                        if (operationEpoch.get() != epoch) return@advertiserCallback
                        result.fold(
                            onSuccess = {
                                FtmsForegroundService.start(
                                    context,
                                    "Sharing as ${persona.advertisedName}",
                                    "Simulated running footpod; measurements only",
                                ) { foreground ->
                                    foreground.fold(
                                        onSuccess = {
                                            publishStatus(status.copy(advertisedName = persona.advertisedName))
                                            onResult(Result.success(Unit))
                                        },
                                        onFailure = { failure -> failStart(failure, onResult) },
                                    )
                                }
                            },
                            onFailure = { failure -> failStart(failure, onResult) },
                        )
                    }
                },
                onFailure = { failure -> failStart(failure, onResult) },
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
        adapterNames.cancelAll("Stryd footpod controller disposed")
    }

    override fun onState(advertising: Boolean, connected: Boolean, subscribed: Boolean, error: String?) {
        publishStatus(
            status.copy(
                advertising = advertising,
                connected = connected,
                subscribed = subscribed,
                advertisedName = SimulatorPersona.StrydFootpod.advertisedName,
                lastError = error,
            ),
        )
    }

    override fun onPacketSent() {
        packets++
        publishStatus(status.copy(packetsSent = packets))
    }

    private fun failStart(failure: Throwable, callback: (Result<Unit>) -> Unit) {
        stopResources(restoreName = true)
        publishStatus(Status(lastError = failure.message))
        callback(Result.failure(failure))
    }

    private fun stopResources(restoreName: Boolean) {
        advertiser.stop()
        strides.reset()
        FtmsForegroundService.stop(context)
        if (restoreName) adapterNames.restore()
    }

    private fun publishStatus(next: Status) {
        status = next
        onStatus(next)
    }

    private fun hasPermissions(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_ADVERTISE) ==
            PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    private fun adapter() =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    companion object {
        private const val PREFS = "com.gmail.absolutebollockscreations.Fitbridge.peripheral"
        private const val KEY_ORIGINAL_NAME = "original_adapter_name"
    }
}
