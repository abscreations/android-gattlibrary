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
        val evidence: String? = null,
        val routes: Set<String> = emptySet(),
        val protocolStages: Set<String> = emptySet(),
        val controlMode: String? = null,
        val speedKph: Double? = null,
        val cadenceRpm: Double? = null,
        val powerWatts: Int? = null,
        val heartRateBpm: Int? = null,
        val virtualGearIndex: Int? = null,
        val gearRatio: Double? = null,
        /**
         * Set only while a resistance write is the active control mode; null otherwise, so a
         * stale level left over from an earlier ERG or simulation switch never displays as live.
         */
        val resistanceLevel: Double? = null,
        /** Set only while [controlMode] reflects an active simulation write; null otherwise. */
        val simWindMps: Double? = null,
        val simGradePercent: Double? = null,
        val simCrr: Double? = null,
        val simCw: Double? = null,
        val pendingFrames: Int = 0,
        val droppedFrames: Long = 0,
        val rejectedWrites: Long = 0,
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
    private val generationGate = SimulatorGenerationGate()
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

    /** UI entry point for the simulator's Zwift Click section - see KickrPeripheralAdvertiser.sendZwiftClickShift()'s KDoc. */
    fun sendZwiftClickShift(direction: ZwiftClickDirection): Boolean = advertiser.sendZwiftClickShift(direction)

    @SuppressLint("MissingPermission")
    fun start(
        persona: KickrSimulatorPersona,
        legacyAckModeOverride: KickrLegacyAckMode? = null,
        ftmsControlEnabled: Boolean = true,
        onResult: (Result<Unit>) -> Unit,
    ) {
        generationGate.reset()
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
        model.ftmsControlEnabled = ftmsControlEnabled
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
        generationGate.reset()
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
        publishStatus(withDiagnostics(
            status.copy(
                persona = persona,
                advertising = advertising,
                connected = connected,
                subscribed = subscribed,
                advertisedName = persona?.let { KickrPersonaManifest.forPersona(it).advertising.localName },
                lastError = error,
            ),
        ))
    }

    override fun onPacketSent() {
        packets++
        publishStatus(withDiagnostics(status.copy(packetsSent = packets)))
    }

    private fun startTicker() {
        ticker?.let(main::removeCallbacks)
        lateinit var task: Runnable
        task = Runnable {
            if (generationGate.observe(status.subscribed)) {
                advertiser.publish(model.advance(TICK_SECONDS))
            }
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

    private fun withDiagnostics(base: Status): Status {
        val diagnostics = advertiser.diagnostics()
        val snapshot = diagnostics.snapshot
        return base.copy(
            evidence = activePersona?.let(::evidenceBadge),
            routes = diagnostics.routes.mapTo(linkedSetOf()) { it.name },
            protocolStages = diagnostics.protocolStages,
            controlMode = snapshot.controlLabel,
            speedKph = snapshot.speedKph,
            cadenceRpm = snapshot.cadenceRpm,
            powerWatts = snapshot.powerWatts,
            heartRateBpm = snapshot.heartRateBpm,
            virtualGearIndex = snapshot.virtualGearIndex,
            gearRatio = snapshot.gearRatio,
            resistanceLevel = snapshot.resistanceLevel.takeIf {
                snapshot.mode == KickrControlMode.RESISTANCE
            },
            // Only meaningful while a simulation write is the active control mode; a stale
            // simulation payload from an earlier ERG/Direct switch must not display as live.
            simWindMps = snapshot.simulation.windMps.takeIf { snapshot.mode == KickrControlMode.SIMULATION },
            simGradePercent = snapshot.simulation.gradePercent.takeIf {
                snapshot.mode == KickrControlMode.SIMULATION
            },
            simCrr = snapshot.simulation.crr.takeIf { snapshot.mode == KickrControlMode.SIMULATION },
            simCw = snapshot.simulation.cw.takeIf { snapshot.mode == KickrControlMode.SIMULATION },
            pendingFrames = diagnostics.pendingFrames,
            droppedFrames = diagnostics.droppedFrames,
            rejectedWrites = diagnostics.rejectedWrites,
        )
    }

    private fun evidenceBadge(persona: KickrSimulatorPersona): String = when (persona) {
        KickrSimulatorPersona.V5_STANDARD_LEGACY -> "log-corroborated"
        KickrSimulatorPersona.CORE_ORIGINAL_ZWIFT -> "capture-backed (HCI)"
        KickrSimulatorPersona.CORE2_FC82_PROVISIONAL -> "advertising HCI; GATT capture-log aligned"
        KickrSimulatorPersona.ZWIFT_HUB_CAPTURED -> "Zwift Hub GATT capture-log aligned"
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
