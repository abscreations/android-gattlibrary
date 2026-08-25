package com.gmail.absolutebollockscreations.Fitbridge.bluetooth.peripheral

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.simulator.KickrPersonaManifest
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.simulator.KickrLegacyAckMode
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.simulator.KickrSimulatorPersona
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.simulator.KickrSyntheticModel
import java.util.concurrent.atomic.AtomicLong

/** Transactional adapter-name, foreground-service and telemetry lifecycle for KICKR simulation. */
internal class KickrPeripheralController(
    private val context: Context,
    private val onStatus: (Status) -> Unit = {},
) : KickrPeripheralAdvertiser.Listener {
    data class Status(
        val persona: KickrSimulatorPersona? = null,
        val advertising: Boolean = false,
        val connected: Boolean = false,
        val subscribed: Boolean = false,
        val advertisedName: String? = null,
        val packetsSent: Long = 0,
        val legacyAckMode: KickrLegacyAckMode? = null,
        val lastError: String? = null,
    )

    private val main = Handler(Looper.getMainLooper())
    private val operationEpoch = AtomicLong(0)
    private val model = KickrSyntheticModel()
    private val advertiser = KickrPeripheralAdvertiser(context, model, this)
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private var activePersona: KickrSimulatorPersona? = null
    private var packets = 0L
    private var ticker: Runnable? = null
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

            override fun cancel(token: Any) {
                tasks.remove(token)?.let(main::removeCallbacks)
            }
        },
    )

    val isAdvertising: Boolean get() = advertiser.isAdvertising

    @SuppressLint("MissingPermission")
    fun start(
        persona: KickrSimulatorPersona,
        legacyAckModeOverride: KickrLegacyAckMode? = null,
        onResult: (Result<Unit>) -> Unit,
    ) {
        val epoch = operationEpoch.incrementAndGet()
        if (!hasPermissions()) {
            val error = IllegalStateException("Bluetooth advertising permission has not been granted")
            publishStatus(Status(lastError = error.message))
            onResult(Result.failure(error))
            return
        }
        stopResources(restoreName = false)
        val plan = KickrPersonaManifest.forPersona(persona).advertising
        activePersona = persona
        packets = 0
        model.reset()
        model.startOrResume()
        adapterNames.apply(plan.localName) nameCallback@{ nameResult ->
            if (operationEpoch.get() != epoch) return@nameCallback
            nameResult.fold(
                onSuccess = {
                    advertiser.start(persona, legacyAckModeOverride) advertiserCallback@{ result ->
                        if (operationEpoch.get() != epoch) return@advertiserCallback
                        result.fold(
                            onSuccess = {
                                startTicker()
                                FtmsForegroundService.start(
                                    context,
                                    "Sharing as ${plan.localName}",
                                    "Debug-only KICKR interoperability simulator",
                                ) { foreground ->
                                    foreground.fold(
                                        onSuccess = {
                                            publishStatus(
                                                status.copy(
                                                    persona = persona,
                                                    advertisedName = plan.localName,
                                                    legacyAckMode = legacyAckModeOverride ?:
                                                        KickrPersonaManifest.forPersona(persona).legacyAckMode,
                                                ),
                                            )
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
        activePersona = null
        publishStatus(Status())
    }

    fun dispose() {
        stop()
        adapterNames.cancelAll("KICKR controller disposed")
    }

    fun onAdapterNameObserved(name: String?) = adapterNames.onNameObserved(name)

    override fun onState(advertising: Boolean, connected: Boolean, subscribed: Boolean, error: String?) {
        val persona = activePersona
        publishStatus(
            status.copy(
                persona = persona,
                advertising = advertising,
                connected = connected,
                subscribed = subscribed,
                advertisedName = persona?.let { KickrPersonaManifest.forPersona(it).advertising.localName },
                lastError = error,
            ),
        )
    }

    override fun onPacketSent() {
        packets++
        publishStatus(status.copy(packetsSent = packets))
    }

    private fun startTicker() {
        ticker?.let(main::removeCallbacks)
        lateinit var task: Runnable
        task = Runnable {
            advertiser.publish(model.advance(TICK_SECONDS))
            if (activePersona != null) main.postDelayed(task, TICK_MS)
        }
        ticker = task
        main.post(task)
    }

    private fun failStart(failure: Throwable, callback: (Result<Unit>) -> Unit) {
        stopResources(restoreName = true)
        activePersona = null
        publishStatus(Status(lastError = failure.message))
        callback(Result.failure(failure))
    }

    private fun stopResources(restoreName: Boolean) {
        ticker?.let(main::removeCallbacks)
        ticker = null
        advertiser.stop()
        FtmsForegroundService.stop(context)
        if (restoreName) adapterNames.restore()
    }

    private fun publishStatus(next: Status) {
        status = next
        onStatus(next)
    }

    private fun hasPermissions(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_ADVERTISE) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    private fun adapter() = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    companion object {
        private const val TICK_MS = 500L
        private const val TICK_SECONDS = 0.5
        private const val PREFS = "com.gmail.absolutebollockscreations.Fitbridge.peripheral"
        private const val KEY_ORIGINAL_NAME = "original_adapter_name"
    }
}
