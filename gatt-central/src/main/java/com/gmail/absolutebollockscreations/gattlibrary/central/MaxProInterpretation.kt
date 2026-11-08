package com.gmail.absolutebollockscreations.gattlibrary.central

import com.gmail.absolutebollockscreations.gattlibrary.core.capture.BleCapture
import com.gmail.absolutebollockscreations.gattlibrary.core.capture.MaxProInterpretationEvent

/** Numeric telemetry only: never log unknown text replies, addresses, profile or pairing data. */
internal fun maxProInterpretation(conn: String, state: MaxProState): MaxProInterpretationEvent {
    val values = linkedMapOf<String, Double?>()
    val raw = state.raw
    if (raw != null) {
        values["notifications_received"] = state.notificationsReceived.toDouble()
        values["maximum_notification_bytes"] = state.maximumNotificationBytes.toDouble()
        values["rejected_fragments"] = state.rejectedFragments.toDouble()
        values["event_raw"] = raw.event.toDouble()
        values["model_raw"] = raw.model.toDouble()
        values["error_raw"] = raw.error.toDouble()
        values["battery_raw"] = raw.batteryRaw.toDouble()
        fun side(prefix: String, rawSide: MaxProRawSide, derived: MaxProSide?, force: MaxProForceSummary) {
            values["${prefix}_knob_tenths"] = rawSide.knobTenths.toDouble()
            values["${prefix}_distance_mm"] = rawSide.distanceMillimeters.toDouble()
            values["${prefix}_time_ticks"] = rawSide.timeTicks.toDouble()
            values["${prefix}_velocity_raw"] = rawSide.velocityRaw.toDouble()
            values["${prefix}_acceleration_raw"] = rawSide.accelerationRaw.toDouble()
            values["${prefix}_device_reps"] = rawSide.deviceRepCount.toDouble()
            values["${prefix}_pull_time_ms"] = derived?.deviceTimeMillis?.toDouble()
            values["${prefix}_calibrated_travel_m"] = derived?.calibratedDistanceMeters
            values["${prefix}_resistance_level"] = derived?.resistanceLevel
            values["${prefix}_nominal_force_lbf"] = derived?.nominalForceNewtons?.div(4.4482216152605)
            values["${prefix}_force_last_lbf"] = force.lastPounds
            values["${prefix}_force_average_lbf"] = force.averagePounds
            values["${prefix}_force_maximum_lbf"] = force.maximumPounds
            values["${prefix}_force_observed_pulls"] = force.observedPulls.toDouble()
        }
        side("left", raw.left, state.snapshot?.left, state.leftForce)
        side("right", raw.right, state.snapshot?.right, state.rightForce)
    }
    return MaxProInterpretationEvent(conn, state.decodeStatus.name, state.completedFrames,
        state.lastTelemetryMillis ?: 0L, state.lastNotificationBytes, values)
}

internal fun captureMaxProInterpretation(id: ConnectionId, state: MaxProState) {
    BleCapture.emit(maxProInterpretation(BleCapture.labelFor(id.address), state))
}
