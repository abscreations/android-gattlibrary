package com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine

import android.Manifest
import androidx.annotation.RequiresPermission
import timber.log.Timber

/**
 * Protocol-neutral machine control surface, per Unit 9.7 of
 * `docs/fe-c-ble_central_implementation.md`.
 *
 * [BtServiceEngineFacade] is the "upstream FTMS bridge for btService" — the seam
 * `btService`'s own FTMS-peripheral-server code uses to fire control commands upstream to
 * whatever real machine [BtEngine] is connected to as a central. Before this unit every one of
 * its control methods hardcoded [BtEngineControlPoint], i.e. FTMS only, even though the same
 * connection slot (`LegacyConnectionRole.FTMS` — a generic "the machine connection" name, not
 * an FTMS-only one; see `ConnectionContext.kt`) already carries Concept2, Precor, Schwinn x70
 * and now FE-C links equally.
 *
 * This interface is the seam that lets [BtEngine] pick an implementation per connection —
 * [FtmsMachineControlEndpoint] for a standard FTMS link, [FeCBleMachineControlEndpoint] for an
 * FE-C over BLE link — without [BtServiceEngineFacade]'s own public methods (or `btService`,
 * which only ever sees that facade) needing to know which protocol is live. Method names and
 * units mirror [BtServiceEngineFacade] deliberately, since that facade is the only caller.
 *
 * ## Units are FTMS's, not FE-C's, and that is a documented ambiguity, not an equivalence
 *
 * [setTargetResistance]'s `level` parameter is FTMS's own arbitrary, device-specific "level" at
 * 0.1 resolution (see `BtEngineControlPoint.setTargetResistance`'s comment on the uint8 wire
 * format) — it does not have a physical unit and different FTMS trainers place different real
 * resistances at the same level value. FE-C's Basic Resistance (page 48) is, by contrast, a
 * spec-defined 0-100% figure. There is no honest conversion between the two without a specific
 * trainer's calibration, which this project has no evidence for (plan §21 FEC-Q1 is still
 * open). [FeCBleMachineControlEndpoint.setTargetResistance] resolves this by treating the
 * incoming `level` as if it were already a 0-100 percent figure — the same assumption
 * `btService`'s own resistance UI already makes today for FTMS (its slider is 0-100) — and
 * documents that this is an assumption carried over, not a proven equivalence.
 */
internal interface MachineControlEndpoint {

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun requestControl(): Boolean

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun startOrResume(): Boolean

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun stopOrPause(): Boolean

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun setTargetSpeedKph(kph: Float): Boolean

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun setTargetInclinationPercent(percent: Float): Boolean

    /** See this file's class-level doc for what `level` means on each implementation. */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun setTargetResistance(level: Float): Boolean

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun setTargetPowerWatts(watts: Int): Boolean

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun setWheelCircumferenceMm(millimetres: Float): Boolean

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun setIndoorBikeSimulation(
        windSpeedMps: Float,
        gradePercent: Float,
        rollingResistanceCoefficient: Float,
        windResistanceCoefficient: Float,
    ): Boolean
}

internal enum class MachineControlRoute {
    FTMS,
    FEC,
    WAHOO,
    RIDEON,

    /**
     * The selected machine broadcasts and cannot receive (phase 11.5).
     *
     * Distinct from [UNAVAILABLE], which means a control surface was expected and is not up yet.
     * Here there is no surface and never will be, so the difference is worth keeping: one is a
     * link that has not converged and is worth complaining about, the other is a bike.
     */
    ADVERTISEMENT_ONLY,

    UNAVAILABLE,
}

/** Exact downstream ranges approved for the legacy KICKR protocol surface. */
internal fun wahooKickrProviderCapabilities() = BtEngineState.FtmsCapabilities(
    featuresRead = true,
    supportsResistance = true,
    supportsPower = true,
    supportsIndoorBikeSimulation = true,
    supportsWheelCircumference = true,
    resistanceMin = 0f,
    resistanceMax = 1_000f,
    resistanceIncr = 1f,
    resistanceRangeKnown = true,
    powerMin = 0f,
    powerMax = 2_000f,
    powerIncr = 1f,
    powerRangeKnown = true,
)

/** Pure selected-adapter routing decision; a half-activated FE-C link safely remains on FTMS. */
internal fun machineControlRoute(
    selectedAdapterId: String?,
    hasFeCHandler: Boolean,
    hasWahooControl: Boolean = false,
    hasRideOnControl: Boolean = false,
    hasAdvertisementMachine: Boolean = false,
): MachineControlRoute =
    when {
        // First, and unconditionally. Selecting an advertisement-only machine already disconnected
        // whatever GATT machine held the slot (§6.6), so a stale connection context left behind by
        // that teardown must not route control at a bike that cannot receive it.
        hasAdvertisementMachine -> MachineControlRoute.ADVERTISEMENT_ONLY
        selectedAdapterId == AdapterLivenessCoordinator.RIDEON_ADAPTER_ID && hasRideOnControl ->
            MachineControlRoute.RIDEON
        selectedAdapterId == AdapterLivenessCoordinator.RIDEON_ADAPTER_ID ->
            MachineControlRoute.UNAVAILABLE
        selectedAdapterId == AdapterLivenessCoordinator.WAHOO_KICKR_ADAPTER_ID && hasWahooControl ->
            MachineControlRoute.WAHOO
        selectedAdapterId == AdapterLivenessCoordinator.WAHOO_KICKR_ADAPTER_ID ->
            MachineControlRoute.UNAVAILABLE
        selectedAdapterId == AdapterLivenessCoordinator.FEC_ADAPTER_ID && hasFeCHandler ->
            MachineControlRoute.FEC
        else -> MachineControlRoute.FTMS
    }

/** Honest closed route used while a selected adapter has no published endpoint. */
internal object UnavailableMachineControlEndpoint : MachineControlEndpoint {
    override fun requestControl() = false
    override fun startOrResume() = false
    override fun stopOrPause() = false
    override fun setTargetSpeedKph(kph: Float) = false
    override fun setTargetInclinationPercent(percent: Float) = false
    override fun setTargetResistance(level: Float) = false
    override fun setTargetPowerWatts(watts: Int) = false
    override fun setWheelCircumferenceMm(millimetres: Float) = false
    override fun setIndoorBikeSimulation(
        windSpeedMps: Float,
        gradePercent: Float,
        rollingResistanceCoefficient: Float,
        windResistanceCoefficient: Float,
    ) = false
}

/**
 * The selected machine is a broadcaster. Every control call is declined, and none of them warns.
 *
 * Silence is the point. Before this existed the run screen's ordinary start-up sequence ran the
 * full FTMS control dance against a Keiser — `requestControl`, an eight-second wait for CCCDs that
 * do not exist, a second eight-second wait for a control grant that will never come — and logged
 * `CP: requestControl ignored (controlPointPresent=false)`, `startOrResume(): CCCDs not ready`
 * and `did not gain control within timeout` on the way through. None of that was a fault: there is
 * no link, no CCCD and no control point, exactly as the phase intended. A warning that fires every
 * time the system works as designed trains a reader to skim past warnings, and it buried the one
 * line in that capture that actually mattered.
 *
 * Declining rather than pretending to succeed: `false` is the honest answer to "did the machine
 * take this command", and the run screen already treats a machine it cannot drive as one it simply
 * displays. [KeiserMSeriesMeasurement]'s own header is the authority — there is nothing writable.
 */
internal object AdvertisementOnlyMachineControlEndpoint : MachineControlEndpoint {
    override fun requestControl() = false
    override fun startOrResume() = false
    override fun stopOrPause() = false
    override fun setTargetSpeedKph(kph: Float) = false
    override fun setTargetInclinationPercent(percent: Float) = false
    override fun setTargetResistance(level: Float) = false
    override fun setTargetPowerWatts(watts: Int) = false
    override fun setWheelCircumferenceMm(millimetres: Float) = false
    override fun setIndoorBikeSimulation(
        windSpeedMps: Float,
        gradePercent: Float,
        rollingResistanceCoefficient: Float,
        windResistanceCoefficient: Float,
    ) = false
}

/**
 * Wraps the existing [BtEngineControlPoint] FTMS Control Point helpers behind
 * [MachineControlEndpoint]. Every method here is a direct pass-through — no behavior changes
 * for FTMS links, since [BtEngine]'s facade methods called these exact functions before this
 * unit existed.
 */
internal class FtmsMachineControlEndpoint(private val engine: BtEngine) : MachineControlEndpoint {

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    override fun requestControl(): Boolean = engine.requestControl()

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    override fun startOrResume(): Boolean = BtEngineControlPoint.startOrResume(engine)

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    override fun stopOrPause(): Boolean = BtEngineControlPoint.stopOrPause(engine)

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    override fun setTargetSpeedKph(kph: Float): Boolean =
        BtEngineControlPoint.setTargetSpeedKph(engine, kph)

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    override fun setTargetInclinationPercent(percent: Float): Boolean =
        BtEngineControlPoint.setTargetInclinationPercent(engine, percent)

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    override fun setTargetResistance(level: Float): Boolean =
        BtEngineControlPoint.setTargetResistance(engine, level)

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    override fun setTargetPowerWatts(watts: Int): Boolean =
        BtEngineControlPoint.setTargetPowerWatts(engine, watts)

    override fun setWheelCircumferenceMm(millimetres: Float): Boolean = false

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    override fun setIndoorBikeSimulation(
        windSpeedMps: Float,
        gradePercent: Float,
        rollingResistanceCoefficient: Float,
        windResistanceCoefficient: Float,
    ): Boolean = BtEngineControlPoint.setIndoorBikeSimulation(
        engine,
        windSpeedMps,
        gradePercent,
        rollingResistanceCoefficient,
        windResistanceCoefficient,
    )
}

/**
 * Drives an FE-C over BLE machine's FEC3 control channel, per plan §10.
 *
 * ## What has real wire semantics here, and what is local-only
 *
 * [setTargetResistance], [setTargetPowerWatts] and [setIndoorBikeSimulation] each submit a
 * [FeCControlTarget] to this link's [FeCControlScheduler] (§9.9) rather than writing FEC3
 * directly — the scheduler owns the 250ms write cadence and latest-only coalescing, so a caller
 * here never needs to know whether its value actually reached the wire yet, only that it is now
 * the link's current target. The pages themselves (48, 49, and 50+51) are the only wire commands
 * FE-C over BLE defines that this project has pinned evidence for.
 *
 * [requestControl], [startOrResume] and [stopOrPause] are **local session semantics only**.
 * FE-C has no ANT+ page equivalent to FTMS's REQUEST_CONTROL/START/STOP opcodes that this
 * project has evidence for (plan §21 FEC-Q9's serial-export gap covers the same uncertainty),
 * so:
 *  - [requestControl] reports whether FEC3 is present on this link — a purely local
 *    availability check, not a request sent to the device.
 *  - [startOrResume] and [stopOrPause] do not send anything and always succeed once a control
 *    channel is available. In particular, [stopOrPause] deliberately does **not** fake a stop
 *    by writing zero watts or zero resistance: a real "stop" on FE-C is the rider's own action
 *    (dismounting, the trainer reporting `FINISHED_OR_PAUSED` on page 16), and writing a zero
 *    target would misrepresent a session pause as a demanded-zero-effort target, which is a
 *    different (and false) instruction to the trainer.
 *
 * [setTargetSpeedKph] is declined (returns `false`, logged): FE-C has no direct speed-target
 * page and no other page from which speed could be honestly derived as a control input.
 *
 * [setTargetInclinationPercent] is **not** declined. The official ANT+ FE-C spec (D000001231
 * Rev 5.0, §8.8.4.1) states page 51's Grade field *is* the incline control - there is no
 * separate "set incline" page: "Controllable fitness equipment that is capable of adjusting the
 * incline directly should apply the grade simulation parameter in this way. Other fitness
 * equipment that is not capable of adjusting the incline (e.g. controllable trainers) shall use
 * the grade field to calculate gravitational resistance to apply to the user." The rolling
 * resistance coefficient this call site has no source for is not a blocker either: §8.8.4.2
 * requires non-trainer equipment to ignore that field entirely, so omitting it (which
 * [FeCControlEncoder.encodeTrackResistance] encodes as the spec's own `0xFF` "use device
 * default" sentinel, not an invented value) is spec-correct, not a guess. Plan §21 FEC-Q1's
 * remaining open question - whether a *specific real trainer* honors the full legal range - is
 * a physical-device question no document resolves; ANT+ requires certified devices to implement
 * the spec's data pages correctly, so this project treats spec compliance as the reasonable
 * default assumption for a certified device rather than continuing to withhold the control.
 */
internal class FeCBleMachineControlEndpoint(
    private val engine: BtEngine,
    private val handler: FeCOverBleProtocolHandler,
) : MachineControlEndpoint {

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    override fun requestControl(): Boolean = handler.controlChannelAvailable()

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    override fun startOrResume(): Boolean {
        if (!handler.controlChannelAvailable()) {
            Timber.tag(BtEngineState.TAG).w("FE-C control: startOrResume ignored, no FEC3 on this link")
            return false
        }
        return true
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    override fun stopOrPause(): Boolean {
        if (!handler.controlChannelAvailable()) {
            Timber.tag(BtEngineState.TAG).w("FE-C control: stopOrPause ignored, no FEC3 on this link")
            return false
        }
        return true
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    override fun setTargetSpeedKph(kph: Float): Boolean {
        Timber.tag(BtEngineState.TAG).w(
            "FE-C control: setTargetSpeedKph not supported (no pinned FE-C speed-target page)",
        )
        return false
    }

    /**
     * Submits a [FeCControlTarget.Sim] carrying only [percent] as grade - wind and rolling
     * resistance are left `null`, which the scheduler encodes as page 50/51's own "use the
     * device's default" sentinels rather than this call site inventing values it does not have.
     * See this file's class-level doc for why that is spec-correct, not a shortcut.
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    override fun setTargetInclinationPercent(percent: Float): Boolean {
        if (!engine.state.featuresRead || !engine.state.supportsInclination) {
            return unsupported("inclination")
        }
        return submit(
            FeCControlTarget.Sim(
                windSpeedMps = null,
                gradePercent = percent.toDouble(),
                rollingResistanceCoefficient = null,
                windResistanceCoefficient = null,
            ),
        )
    }

    /** `level` is treated as a 0-100 percent figure for FE-C's page 48. See file-level doc. */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    override fun setTargetResistance(level: Float): Boolean {
        if (!engine.state.featuresRead || !engine.state.supportsResistance) {
            return unsupported("basic resistance")
        }
        val percent = level.toDouble().coerceIn(0.0, 100.0)
        return submit(FeCControlTarget.BasicResistance(percent))
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    override fun setTargetPowerWatts(watts: Int): Boolean {
        if (!engine.state.featuresRead || !engine.state.supportsPower) {
            return unsupported("target power")
        }
        val safeWatts = watts.coerceAtLeast(0)
        return submit(FeCControlTarget.Erg(safeWatts.toDouble()))
    }

    override fun setWheelCircumferenceMm(millimetres: Float): Boolean = false

    /**
     * Coalesced into one [FeCControlTarget.Sim], which the scheduler expands into page 50
     * (Wind Resistance) then page 51 (Track Resistance) on each tick — two ANT frames sent
     * back-to-back, since FE-C has no combined simulation page the way FTMS's `0x11` opcode
     * does, but always as a matched pair rather than half an update landing.
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    override fun setIndoorBikeSimulation(
        windSpeedMps: Float,
        gradePercent: Float,
        rollingResistanceCoefficient: Float,
        windResistanceCoefficient: Float,
    ): Boolean {
        if (!engine.state.featuresRead || !engine.state.supportsIndoorBikeSimulation) {
            return unsupported("simulation")
        }
        return submit(
            FeCControlTarget.Sim(
                windSpeedMps = windSpeedMps.toDouble(),
                gradePercent = gradePercent.toDouble(),
                rollingResistanceCoefficient = rollingResistanceCoefficient.toDouble(),
                windResistanceCoefficient = windResistanceCoefficient.toDouble(),
            ),
        )
    }

    private fun unsupported(mode: String): Boolean {
        Timber.tag(BtEngineState.TAG).w("FE-C control: %s withheld by unresolved/page-54 capabilities", mode)
        return false
    }

    /**
     * Hands [target] to this link's [FeCControlScheduler]. Returns `false` only when there is no
     * scheduler to submit to (no FEC3 on this link) — a successful submission does not mean the
     * value has reached the wire yet, only that it is now the link's current coalesced target;
     * see [FeCControlScheduler]'s KDoc for why that is the honest contract for a
     * write-without-response channel with a coalescing cadence.
     */
    private fun submit(target: FeCControlTarget): Boolean {
        val scheduler = handler.controlScheduler() ?: run {
            Timber.tag(BtEngineState.TAG).w(
                "FE-C control: %s ignored, no FEC3 control scheduler on this link",
                target.mode,
            )
            return false
        }
        scheduler.submit(target)
        return true
    }
}

/** Legacy KICKR control is reachable only after this exact link's unlock completed. */
internal class WahooKickrMachineControlEndpoint(
    private val session: WahooKickrControlSession,
    private val athleteMassKg: () -> Double,
) : MachineControlEndpoint {
    override fun requestControl(): Boolean = session.controlGranted
    override fun startOrResume(): Boolean = session.controlGranted
    override fun stopOrPause(): Boolean {
        if (!session.controlGranted) return false
        session.clearPending()
        return true
    }

    override fun setTargetSpeedKph(kph: Float): Boolean = false
    override fun setTargetInclinationPercent(percent: Float): Boolean = false

    override fun setTargetResistance(level: Float): Boolean = session.submit(
        WahooKickrControlTarget.Resistance((level.toDouble() / 100.0).coerceIn(0.0, 1.0)),
    )

    override fun setTargetPowerWatts(watts: Int): Boolean =
        session.submit(WahooKickrControlTarget.Erg(watts.coerceIn(0, 2_000)))

    override fun setWheelCircumferenceMm(millimetres: Float): Boolean =
        session.submit(WahooKickrControlTarget.WheelCircumference(millimetres.toDouble()))

    override fun setIndoorBikeSimulation(
        windSpeedMps: Float,
        gradePercent: Float,
        rollingResistanceCoefficient: Float,
        windResistanceCoefficient: Float,
    ): Boolean = session.submit(
        WahooKickrControlTarget.Simulation(
            totalMassKg = athleteMassKg() + DEFAULT_BICYCLE_MASS_KG,
            crr = rollingResistanceCoefficient.toDouble(),
            cw = windResistanceCoefficient.toDouble(),
            gradePercent = gradePercent.toDouble(),
            windSpeedMps = windSpeedMps.toDouble(),
        ),
    )

    private companion object {
        const val DEFAULT_BICYCLE_MASS_KG = 10.0
    }
}
