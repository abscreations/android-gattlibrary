package com.gmail.absolutebollockscreations.gattlibrary.simulator

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import java.util.concurrent.atomic.AtomicLong
import com.gmail.absolutebollockscreations.gattlibrary.peripheral.AdapterNameCoordinator
import com.gmail.absolutebollockscreations.gattlibrary.peripheral.FtmsForegroundService
import com.gmail.absolutebollockscreations.gattlibrary.peripheral.advertisedName

/** Adapter-name, foreground-service, and diagnostics owner for Life Fitness simulation. */
internal class LifeFitnessPeripheralController(
    private val context: Context,
    private val onStatus: (Status) -> Unit = {},
) : LifeFitnessPeripheralAdvertiser.Listener {
    data class Status(
        val advertising: Boolean = false,
        val connected: Boolean = false,
        val subscribed: Boolean = false,
        val statusSubscribed: Boolean = false,
        val setupStage: LifeFitnessSetupServer.Stage? = null,
        val advertisedName: String? = null,
        val packetsSent: Long = 0,
        val lastError: String? = null,
    )

    private val main = Handler(Looper.getMainLooper())
    private val operationEpoch = AtomicLong(0)
    private val advertiser = LifeFitnessPeripheralAdvertiser(context, this)
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private var packets = 0L
    private var machine: LifeFitnessMachine? = null
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
    fun start(machine: LifeFitnessMachine, onResult: (Result<Unit>) -> Unit) {
        val epoch = operationEpoch.incrementAndGet()
        if (!hasPermissions()) {
            val error = IllegalStateException("Bluetooth advertising permission has not been granted")
            publishStatus(Status(lastError = error.message))
            onResult(Result.failure(error))
            return
        }
        stopResources(restoreName = false)
        packets = 0
        this.machine = machine
        val persona = SimulatorPersona.LifeFitness(machine)
        adapterNames.apply(persona.advertisedName) nameCallback@{ nameResult ->
            if (operationEpoch.get() != epoch) return@nameCallback
            nameResult.fold(
                onSuccess = {
                    advertiser.start(machine) advertiserCallback@{ result ->
                        if (operationEpoch.get() != epoch) return@advertiserCallback
                        result.fold(
                            onSuccess = {
                                FtmsForegroundService.start(
                                    context,
                                    "Sharing as ${persona.advertisedName}",
                                    "Simulated Life Fitness ${machine.displayName}; local setup and measurements only",
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
        machine = null
        publishStatus(Status())
    }

    fun dispose() {
        stop()
        adapterNames.cancelAll("Life Fitness controller disposed")
    }

    override fun onState(
        advertising: Boolean,
        connected: Boolean,
        subscribed: Boolean,
        statusSubscribed: Boolean,
        setupStage: LifeFitnessSetupServer.Stage?,
        error: String?,
    ) {
        publishStatus(
            status.copy(
                advertising = advertising,
                connected = connected,
                subscribed = subscribed,
                statusSubscribed = statusSubscribed,
                setupStage = setupStage,
                advertisedName = machine?.let { SimulatorPersona.LifeFitness(it).advertisedName },
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
        FtmsForegroundService.stop(context)
        if (restoreName) adapterNames.restore()
    }

    private fun publishStatus(next: Status) { status = next; onStatus(next) }

    private fun hasPermissions(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_ADVERTISE) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    private fun adapter() = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    companion object {
        private const val PREFS = "com.gmail.absolutebollockscreations.Fitbridge.peripheral"
        private const val KEY_ORIGINAL_NAME = "original_adapter_name"
    }
}
