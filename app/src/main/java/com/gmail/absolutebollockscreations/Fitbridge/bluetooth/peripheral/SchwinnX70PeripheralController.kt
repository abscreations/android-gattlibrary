package com.gmail.absolutebollockscreations.Fitbridge.bluetooth.peripheral

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.simulator.SCHWINN_X70_ADVERTISED_NAME
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/** Lifecycle and adapter-name owner for the Schwinn x70 simulator peripheral. */
internal class SchwinnX70PeripheralController(
    private val context: Context,
    private val onStatus: (Status) -> Unit = {},
) : SchwinnX70PeripheralAdvertiser.Listener {

    /**
     * @param initialised whether any client has opened the gate with an accepted control write.
     * This is the field worth watching: a connected, subscribed client that is not initialised is
     * receiving nothing, and that is correct rather than broken.
     */
    data class Status(
        val advertising: Boolean = false,
        val connected: Boolean = false,
        val initialised: Boolean = false,
        val subscribed: Boolean = false,
        val advertisedName: String? = null,
        val packetsSent: Long = 0,
        val rejectedWrites: Int = 0,
        val lastError: String? = null,
    )

    private val main = Handler(Looper.getMainLooper())
    private val operationEpoch = AtomicLong(0)
    private val advertiser = SchwinnX70PeripheralAdvertiser(context, this)
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private var packets = 0L
    @Volatile private var status = Status()
    @Volatile private var active: Boolean = false

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

    fun publish(frames: List<Pair<UUID, ByteArray>>) = advertiser.publish(frames)

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
        active = true
        // Every persona takes the same adapter name, because every captured console does.
        adapterNames.apply(SCHWINN_X70_ADVERTISED_NAME) nameCallback@{ nameResult ->
            if (operationEpoch.get() != epoch) return@nameCallback
            nameResult.fold(
                onSuccess = {
                    advertiser.start(advertiserCallback@{ result ->
                        if (operationEpoch.get() != epoch) return@advertiserCallback
                        result.fold(
                            onSuccess = {
                                FtmsForegroundService.start(
                                    context,
                                    "Sharing as ${SCHWINN_X70_ADVERTISED_NAME}",
                                    "Simulated Schwinn 170/270; measurements only",
                                ) { foreground ->
                                    foreground.fold(
                                        onSuccess = {
                                            publishStatus(
                                                status.copy(
                                                    advertisedName = SCHWINN_X70_ADVERTISED_NAME,
                                                ),
                                            )
                                            onResult(Result.success(Unit))
                                        },
                                        onFailure = { failure -> failStart(failure, onResult) },
                                    )
                                }
                            },
                            onFailure = { failure -> failStart(failure, onResult) },
                        )
                    })
                },
                onFailure = { failure -> failStart(failure, onResult) },
            )
        }
    }

    fun stop() {
        operationEpoch.incrementAndGet()
        active = false
        stopResources(restoreName = true)
        publishStatus(Status())
    }

    fun dispose() {
        stop()
        adapterNames.cancelAll("Schwinn x70 controller disposed")
    }

    override fun onState(
        advertising: Boolean,
        connected: Boolean,
        initialised: Boolean,
        subscribed: Boolean,
        error: String?,
    ) {
        publishStatus(
            status.copy(
                advertising = advertising,
                connected = connected,
                initialised = initialised,
                subscribed = subscribed,
                advertisedName = SCHWINN_X70_ADVERTISED_NAME.takeIf { active },
                rejectedWrites = advertiser.rejectedWrites,
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
        active = false
        publishStatus(Status(lastError = failure.message))
        callback(Result.failure(failure))
    }

    private fun stopResources(restoreName: Boolean) {
        advertiser.stop()
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
