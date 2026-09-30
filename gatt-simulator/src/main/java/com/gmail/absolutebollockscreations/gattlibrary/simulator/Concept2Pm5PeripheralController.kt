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
import com.gmail.absolutebollockscreations.gattlibrary.server.AdapterNameCoordinator
import com.gmail.absolutebollockscreations.gattlibrary.server.FtmsForegroundService
import com.gmail.absolutebollockscreations.gattlibrary.server.advertisedName

/** PM5 lifecycle owner, deliberately independent of upstream and FTMS control paths. */
internal class Concept2Pm5PeripheralController(
    private val context: Context,
    private val onStatus: (Status) -> Unit = {},
) : Concept2Pm5PeripheralAdvertiser.Listener {
    data class Status(
        val advertising: Boolean = false,
        val connected: Boolean = false,
        val subscribed: Boolean = false,
        val advertisedName: String? = null,
        val machineType: String? = null,
        val packetsSent: Long = 0,
        val lastError: String? = null,
    )

    private val main = Handler(Looper.getMainLooper())
    private val operationEpoch = AtomicLong(0)
    private val advertiser = Concept2Pm5PeripheralAdvertiser(context, this)
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private var persona: SimulatorPersona.Concept2Pm5? = null
    private var packets = 0L
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
                tasks[token] = task; main.postDelayed(task, delayMs)
            }
            override fun cancel(token: Any) { tasks.remove(token)?.let(main::removeCallbacks) }
        },
    )

    val isAdvertising: Boolean get() = advertiser.isAdvertising
    val hasWorkoutMeasurementSubscription: Boolean
        get() = advertiser.hasWorkoutMeasurementSubscription()
    fun currentStatus(): Status = status
    fun publish(snapshot: Concept2Pm5Snapshot) = advertiser.publish(snapshot)
    fun workoutProgram(): Concept2WorkoutProgram? = advertiser.workoutProgram()
    fun consumeWorkoutTerminationRequest(): Boolean = advertiser.consumeWorkoutTerminationRequest()
    fun resetWorkoutState() = advertiser.resetWorkoutState()

    @SuppressLint("MissingPermission")
    fun start(persona: SimulatorPersona.Concept2Pm5, onResult: (Result<Unit>) -> Unit) {
        val epoch = operationEpoch.incrementAndGet()
        if (!hasPermissions()) {
            val error = IllegalStateException("Bluetooth advertising permission has not been granted")
            publishStatus(Status(lastError = error.message)); onResult(Result.failure(error)); return
        }
        stopResources(restoreName = false)
        this.persona = persona
        packets = 0
        adapterNames.apply(persona.advertisedName) nameCallback@{ nameResult ->
            if (operationEpoch.get() != epoch) return@nameCallback
            nameResult.fold(
                onSuccess = {
                    advertiser.start(persona) advertiserCallback@{ result ->
                        if (operationEpoch.get() != epoch) return@advertiserCallback
                        result.fold(
                            onSuccess = {
                                FtmsForegroundService.start(
                                    context,
                                    "Sharing as ${persona.advertisedName}",
                                    "Simulated Concept2 PM5; ErgData-compatible identity and CSAFE response notifications",
                                ) { foreground ->
                                    foreground.fold(
                                        onSuccess = {
                                            publishStatus(status.copy(advertisedName = persona.advertisedName, machineType = persona.machine.displayName))
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
        persona = null
        publishStatus(Status())
    }

    fun dispose() { stop(); advertiser.dispose(); adapterNames.cancelAll("PM5 controller disposed") }

    override fun onState(advertising: Boolean, connected: Boolean, subscribed: Boolean, error: String?) {
        val selected = persona
        publishStatus(status.copy(
            advertising = advertising,
            connected = connected,
            subscribed = subscribed,
            advertisedName = selected?.advertisedName,
            machineType = selected?.machine?.displayName,
            lastError = error,
        ))
    }

    override fun onPacketSent() { packets++; publishStatus(status.copy(packetsSent = packets)) }

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
        // Shared with the FTMS controller so either controller can recover a name stranded by
        // process death in the other protocol mode.
        private const val PREFS = "com.gmail.absolutebollockscreations.Fitbridge.peripheral"
        private const val KEY_ORIGINAL_NAME = "original_adapter_name"
    }
}
