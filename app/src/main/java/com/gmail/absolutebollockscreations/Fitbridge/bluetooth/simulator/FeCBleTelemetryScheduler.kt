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

    /** Advance the model by one tick and encode the frame that tick's rotation calls for. */
    fun tick(state: FeCBleMachineState): ByteArray {
        FeCBleSyntheticModel.advanceTick(state)
        val emitProductInfo = (tickIndex % PRODUCT_INFO_EVERY_N_TICKS) == PRODUCT_INFO_EVERY_N_TICKS - 1
        val frame = when {
            emitProductInfo -> FeCBleTelemetryEncoder.encodePage81Frame(state)
            tickIndex % 2 == 0L -> FeCBleTelemetryEncoder.encodePage16Frame(state)
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
        FeCEquipmentType.TRAINER, FeCEquipmentType.STATIONARY_BIKE -> {
            FeCBleSyntheticModel.recordTrainerEvent(state)
            FeCBleTelemetryEncoder.encodePage25Frame(state)
        }
        FeCEquipmentType.TREADMILL -> FeCBleTelemetryEncoder.encodePage19Frame(state)
        FeCEquipmentType.ROWER -> {
            FeCBleSyntheticModel.recordRowerEvent(state)
            FeCBleTelemetryEncoder.encodePage22Frame(state)
        }
    }

    /** Current 0-based tick count, for tests and diagnostics. */
    fun tickCount(): Long = tickIndex

    companion object {
        /** Approximately every 8 seconds at the 4 Hz base cadence (plan §11.2/§11.3). */
        const val PRODUCT_INFO_EVERY_N_TICKS = 32L

        /** Total scheduler cadence; the whole point of the 250 ms tick (plan §11.1). */
        const val TICK_PERIOD_MS = 250L
    }
}
