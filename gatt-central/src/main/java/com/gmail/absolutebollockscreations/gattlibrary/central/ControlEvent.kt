package com.gmail.absolutebollockscreations.gattlibrary.central

/**
 * Something the connected machine reported about its own control state, via a Fitness Machine
 * Status notification or a vendor control acknowledgement.
 *
 * These are momentary events, not state: one is delivered per report, in order, and an equal
 * repeat is delivered again, because a console that sets the same target twice has acted twice.
 * Collect [FitnessGattClient.controlEvents] to mirror them; nothing is replayed to a late
 * collector, and a collector that falls far behind loses the oldest events rather than stalling
 * the engine.
 *
 * Values are canonical: speed in km/h, incline in percent, resistance in the machine's level
 * units, power in watts.
 */
public sealed interface ControlEvent {
    /** The machine took control back, or the control request failed. */
    public data object ControlPermissionLost : ControlEvent

    /** The machine stopped because its safety key was pulled. */
    public data object SafetyKeyPulled : ControlEvent

    public data class TargetSpeedKph(val kph: Float) : ControlEvent

    public data class TargetInclinePercent(val percent: Float) : ControlEvent

    public data class TargetResistanceLevel(val level: Float) : ControlEvent

    public data class TargetPowerWatts(val watts: Int) : ControlEvent

    /** A heart-rate target; 0 means the target was cleared. */
    public data class TargetHeartRateBpm(val bpm: Int) : ControlEvent

    /** The wheel circumference the machine accepted. Reported only by the legacy KICKR path today. */
    public data class WheelCircumferenceMm(val millimetres: Double) : ControlEvent
}
