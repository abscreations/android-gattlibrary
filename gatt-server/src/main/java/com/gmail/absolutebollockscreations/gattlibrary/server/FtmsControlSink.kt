package com.gmail.absolutebollockscreations.gattlibrary.server

/**
 * Downstream control seam.
 *
 * [FtmsControlPointHandler] owns protocol validation and response framing while
 * this interface isolates delivery to the physical machine. Splitting the two
 * is what makes the whole Control Point state machine unit-testable; the old
 * `BtServiceControlPoint` called `BtServiceEngineFacade` inline and could only
 * be exercised on real hardware.
 */
interface FtmsControlSink {

    /** False while no downstream write path exists. */
    val canControl: Boolean

    /** What the downstream can actually be asked to change. */
    val targetCapabilities: TargetCapabilities

    /**
     * True when accepted targets are followed by an authoritative asynchronous
     * Fitness Machine Status event from the controlled machine.
     *
     * The Control Point handler then sends only the procedure response and lets
     * that event publish the target. Otherwise a pass-through session would
     * publish an optimistic status and then publish the machine's echo again.
     */
    val reportsTargetChangesAsynchronously: Boolean
        get() = false

    /**
     * True for a local sink that normalizes requested targets itself. Real
     * machine pass-through keeps strict range validation and lets the upstream
     * machine remain authoritative.
     */
    val clampsTargetsToAdvertisedRange: Boolean
        get() = false

    fun requestControl(): Boolean

    fun reset(): Boolean

    fun startOrResume(): Boolean

    /** @param stop true for Stop (param 0x01), false for Pause (param 0x02). */
    fun stopOrPause(stop: Boolean): Boolean

    fun setTargetSpeedKph(kph: Double): Boolean

    fun setTargetInclinationPercent(percent: Double): Boolean

    fun setTargetResistance(level: Double): Boolean

    fun setTargetPowerWatts(watts: Int): Boolean

    fun setTargetHeartRate(bpm: Int): Boolean

    /** FTMS 0x12 value in millimetres (wire: uint16, 0.1 mm). */
    fun setWheelCircumferenceMm(millimetres: Double): Boolean = false

    /**
     * FTMS 0x11 Set Indoor Bike Simulation Parameters.
     *
     * Present because this is how Zwift and most sim-mode training apps drive a
     * trainer. FitBridge has no upstream path for it yet, so the default
     * implementation refuses it up front rather than reporting a synthetic
     * success.
     *
     * @param windMps      wind speed, m/s (wire: sint16, 0.001 m/s)
     * @param gradePercent grade, percent (wire: sint16, 0.01 %)
     * @param crr          coefficient of rolling resistance (wire: uint8, 0.0001)
     * @param cw           wind resistance coefficient, kg/m (wire: uint8, 0.01)
     */
    fun setIndoorBikeSimulation(
        windMps: Double,
        gradePercent: Double,
        crr: Double,
        cw: Double,
    ): Boolean
}

/**
 * Data-only implementation, used before the engine is attached.
 *
 * Control ownership, Reset, and the Start/Resume and Stop/Pause session
 * transitions are all accepted, because a data-only FTMS persona is a perfectly
 * valid machine: those procedures only move the *local* procedure state machine
 * (IDLE/RUNNING/STOPPED/PAUSED) and gate the Training Status the peripheral
 * reports. Answering OPERATION_FAILED here is strictly worse: strict centrals
 * (notably Garmin) treat a failed Start/Resume as fatal and abandon the
 * connection.
 *
 * Only the *target-setting* opcodes stay unsupported, because those genuinely
 * require a downstream we do not have; they are refused up front by [canControl]
 * and [TargetCapabilities], never reported as a synthetic success.
 */
object InertFtmsControlSink : FtmsControlSink {
    override val canControl: Boolean = false
    override val targetCapabilities: TargetCapabilities = TargetCapabilities.NONE

    override fun requestControl(): Boolean = true
    override fun reset(): Boolean = true
    override fun startOrResume(): Boolean = true
    override fun stopOrPause(stop: Boolean): Boolean = true

    override fun setTargetSpeedKph(kph: Double): Boolean = false
    override fun setTargetInclinationPercent(percent: Double): Boolean = false
    override fun setTargetResistance(level: Double): Boolean = false
    override fun setTargetPowerWatts(watts: Int): Boolean = false
    override fun setTargetHeartRate(bpm: Int): Boolean = false
    override fun setWheelCircumferenceMm(millimetres: Double): Boolean = false
    override fun setIndoorBikeSimulation(
        windMps: Double,
        gradePercent: Double,
        crr: Double,
        cw: Double,
    ): Boolean = false
}

/**
 * Routes Control Point procedures to whichever downstream currently owns the
 * machine.
 *
 * Normally that is the real one, [EngineFtmsControlSink]. The data simulator
 * installs itself as [override] so a watch's target writes steer the synthetic
 * machine instead - without it, the simulator could advertise target capability
 * that nothing would act on, which is exactly the class of dishonesty the port
 * was meant to remove.
 *
 * The switch is a volatile reference read per call, so it can be changed from
 * the UI thread while a Control Point write is being handled on the GATT
 * callback thread.
 *
 * [speedControlAllowed] is the treadmill speed opt-in. It gates only the pass-through to a real
 * machine: while an [override] is installed, speed follows the override's own capabilities
 * (LIB-9d).
 */
class SwitchableFtmsControlSink(
    private val default: FtmsControlSink,
    private val speedControlAllowed: () -> Boolean = { true },
) : FtmsControlSink {

    @Volatile
    var override: FtmsControlSink? = null

    private val active: FtmsControlSink
        get() = override ?: default

    /** The opt-in applies to the real machine only; a local override decides for itself. */
    private fun speedAllowed(): Boolean = override != null || speedControlAllowed()

    override val canControl: Boolean get() = active.canControl
    override val targetCapabilities: TargetCapabilities
        get() = active.targetCapabilities.let { caps ->
            if (speedAllowed()) caps else caps.copy(speed = false, speedRange = null)
        }
    override val reportsTargetChangesAsynchronously: Boolean
        get() = active.reportsTargetChangesAsynchronously
    override val clampsTargetsToAdvertisedRange: Boolean
        get() = active.clampsTargetsToAdvertisedRange

    override fun requestControl(): Boolean = active.requestControl()
    override fun reset(): Boolean = active.reset()
    override fun startOrResume(): Boolean = active.startOrResume()
    override fun stopOrPause(stop: Boolean): Boolean = active.stopOrPause(stop)
    override fun setTargetSpeedKph(kph: Double): Boolean =
        speedAllowed() && active.setTargetSpeedKph(kph)
    override fun setTargetInclinationPercent(percent: Double): Boolean =
        active.setTargetInclinationPercent(percent)

    override fun setTargetResistance(level: Double): Boolean = active.setTargetResistance(level)
    override fun setTargetPowerWatts(watts: Int): Boolean = active.setTargetPowerWatts(watts)
    override fun setTargetHeartRate(bpm: Int): Boolean = active.setTargetHeartRate(bpm)
    override fun setWheelCircumferenceMm(millimetres: Double): Boolean =
        active.setWheelCircumferenceMm(millimetres)
    override fun setIndoorBikeSimulation(
        windMps: Double,
        gradePercent: Double,
        crr: Double,
        cw: Double,
    ): Boolean = active.setIndoorBikeSimulation(windMps, gradePercent, crr, cw)
}
