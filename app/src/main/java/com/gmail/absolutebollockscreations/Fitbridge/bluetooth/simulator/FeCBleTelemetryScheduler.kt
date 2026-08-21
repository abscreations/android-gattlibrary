package com.gmail.absolutebollockscreations.Fitbridge.bluetooth.simulator

/**
 * Deterministic 250 ms page-rotation scheduler (plan §11).
 *
 * One tick is one scheduled FEC2 notification; the *total* cadence across all page types is
 * approximately 4 Hz, not 4 Hz per page. This is a documented simulator fixture (FEC-SQ1), never a
 * claim about a physical trainer's mandatory rotation.
 *
 * Pure and Android-free: [tick] advances the model and returns the next raw ANT frame to publish.
 */
internal class FeCBleTelemetryScheduler(private val equipmentType: FeCEquipmentType) {

    private var tickIndex: Long = 0
    private var trainerSpecificIndex: Long = 0

    /** Advance the model by one tick and encode the frame that tick's rotation calls for. */
    fun tick(state: FeCBleMachineState): ByteArray {
        FeCBleSyntheticModel.advanceTick(state)
        val cycleTick = (tickIndex % ROTATION_TICKS).toInt()
        val frame = when (cycleTick) {
            in 0 until ROTATION_TICKS step 2 -> FeCBleTelemetryEncoder.encodePage16Frame(state)
            15, 35 -> FeCBleTelemetryEncoder.encodePage17Frame(state)
            17, 37 -> FeCBleTelemetryEncoder.encodePage18Frame(state)
            31 -> FeCBleTelemetryEncoder.encodePage81Frame(state)
            else -> encodeSpecificPageFrame(state)
        }
        tickIndex++
        return frame
    }

    /**
     * Plan §11.2: every persona alternates page 16 with its own equipment-specific page (this
     * satisfies the official spec's minimum transmission rates - page 16 at 2.0Hz, specific
     * pages 19-26 at 0.8Hz - with room to spare, since 4Hz/2 = 2Hz exceeds both). Previously only
     * Trainer/Stationary Bike ever reached a specific page at all: Treadmill and Rower always
     * got page 16 twice as often as page 16 itself, i.e. exclusively - the root cause of "no
     * metrics received... only elapsed time advances" for those two personas, since cadence
     * (both) and power (rower) only ever live on their equipment-specific page, never on page 16.
     */
    private fun encodeSpecificPageFrame(state: FeCBleMachineState): ByteArray = when (equipmentType) {
        FeCEquipmentType.TRAINER -> {
            val frame = if (trainerSpecificIndex % 2L == 0L) {
                // Page 25 is mandatory for trainers, but 0xFFF makes both direct-power fields
                // unavailable. Page 26 is this persona's sole FE-C power source.
                FeCBleTelemetryEncoder.encodePage25Frame(state, powerAvailable = false)
            } else {
                FeCBleTelemetryEncoder.encodePage26Frame(state)
            }
            trainerSpecificIndex++
            frame
        }
        FeCEquipmentType.STATIONARY_BIKE -> {
            FeCBleSyntheticModel.recordTrainerEvent(state)
            FeCBleTelemetryEncoder.encodePage25Frame(state)
        }
        FeCEquipmentType.TREADMILL -> FeCBleTelemetryEncoder.encodePage19Frame(state)
        FeCEquipmentType.ELLIPTICAL -> FeCBleTelemetryEncoder.encodePage20Frame(state)
        FeCEquipmentType.ROWER -> FeCBleTelemetryEncoder.encodePage22Frame(state)
        FeCEquipmentType.CLIMBER -> FeCBleTelemetryEncoder.encodePage23Frame(state)
        FeCEquipmentType.NORDIC_SKIER -> FeCBleTelemetryEncoder.encodePage24Frame(state)
    }

    /** Current 0-based tick count, for tests and diagnostics. */
    fun tickCount(): Long = tickIndex

    companion object {
        /** Ten-second deterministic schedule at 4 Hz. */
        const val ROTATION_TICKS = 40

        /** Total scheduler cadence; the whole point of the 250 ms tick (plan §11.1). */
        const val TICK_PERIOD_MS = 250L
    }
}
