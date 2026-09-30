package com.gmail.absolutebollockscreations.gattlibrary.simulator

/**
 * Which PM5 records the advertiser notifies for a snapshot. Pulled out of the advertiser's
 * companion (LIB-20c) so the conformance suite can check the selection without widening the
 * Android-facing advertiser class.
 */
@SimulatorProbeApi
object Concept2Pm5RecordSelection {
    internal const val ADDITIONAL_STATUS_3_ID = 0x3E
    internal val LIVE_IDS = listOf(0x31, 0x32, 0x33, 0x35, 0x36, 0x3B, ADDITIONAL_STATUS_3_ID)
    internal val STROKE_IDS = setOf(0x35, 0x36)

    fun liveIdsFor(snapshot: Concept2Pm5Snapshot): List<Int> =
        if (snapshot.strokeDataAvailable) LIVE_IDS else LIVE_IDS.filterNot(STROKE_IDS::contains)

    /** BikeErg has captured 0x35/0x36 records but no physical handle-force curve. */
    fun forceCurveAvailable(
        machine: Concept2Machine?,
        snapshot: Concept2Pm5Snapshot,
    ): Boolean = snapshot.strokeDataAvailable &&
        machine != Concept2Machine.BIKE_ERG
}
