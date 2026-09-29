package com.gmail.absolutebollockscreations.Fitbridge.bluetooth.simulator

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import java.util.concurrent.atomic.AtomicLong
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.peripheral.AdapterNameCoordinator
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.peripheral.FtmsForegroundService
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.peripheral.advertisedName

internal class FlywheelSportsPeripheralController(
    private val context: Context,
    private val onStatus: (Status) -> Unit = {},
) : FlywheelSportsPeripheralAdvertiser.Listener {
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
    private val advertiser = FlywheelSportsPeripheralAdvertiser(context, this)
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private var packets = 0L
    @Volatile private var status = Status()
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
            override fun cancel(token: Any) { tasks.remove(token)?.let(main::removeCallbacks) }
        },
    )

    val isAdvertising: Boolean get() = advertiser.isAdvertising
    fun publish(sample: FtmsMockSample) = advertiser.publish(sample)

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
        val persona = SimulatorPersona.FlywheelSports
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
                                    "Simulated Flywheel Sports bike; measurements only",
                                ) { foreground ->
                                    foreground.fold(
                                        onSuccess = {
                                            publishStatus(status.copy(advertisedName = persona.advertisedName))
                                            onResult(Result.success(Unit))
                                        },
                                        onFailure = { failStart(it, onResult) },
                                    )
                                }
                            },
                            onFailure = { failStart(it, onResult) },
                        )
                    }
                },
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
        adapterNames.cancelAll("Flywheel controller disposed")
    }

    override fun onState(advertising: Boolean, connected: Boolean, subscribed: Boolean, error: String?) =
        publishStatus(
            status.copy(
                advertising = advertising,
                connected = connected,
                subscribed = subscribed,
                advertisedName = SimulatorPersona.FlywheelSports.advertisedName,
                lastError = error,
            ),
        )

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
        FtmsForegroundService.stop(context)
        if (restoreName) adapterNames.restore()
    }

    private fun publishStatus(next: Status) { status = next; onStatus(next) }
    private fun hasPermissions() =
        ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_ADVERTISE) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    private fun adapter() = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    companion object {
        private const val PREFS = "com.gmail.absolutebollockscreations.Fitbridge.peripheral"
        private const val KEY_ORIGINAL_NAME = "original_adapter_name"
    }
}
