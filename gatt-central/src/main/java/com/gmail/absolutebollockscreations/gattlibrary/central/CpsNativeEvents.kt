package com.gmail.absolutebollockscreations.gattlibrary.central

data class CpsNativeEventSnapshot(
    val cumulativeWheelRevolutions: Long? = null,
    val lastWheelEventTimeTicks: Int? = null,
    val cumulativeCrankRevolutions: Int? = null,
    val lastCrankEventTimeTicks: Int? = null,
)

/** Session-only native CPS event fields from the selected single CPS link generation. */
internal class CpsNativeEventStore {
    private val entries = linkedMapOf<ConnectionId, CpsMeasurement.Reading>()

    @Synchronized
    fun publish(connectionId: ConnectionId, reading: CpsMeasurement.Reading) {
        val previous = entries[connectionId]
        entries[connectionId] = reading.copy(
            wheel = reading.wheel ?: previous?.wheel,
            crank = reading.crank ?: previous?.crank,
        )
    }

    @Synchronized
    fun remove(connectionId: ConnectionId) {
        entries.remove(connectionId)
    }

    @Synchronized
    fun clear() {
        entries.clear()
    }

    @Synchronized
    fun snapshot(): CpsNativeEventSnapshot? {
        val reading = entries.entries.maxByOrNull { it.key.linkGeneration }?.value ?: return null
        val wheel = reading.wheel
        val crank = reading.crank
        if (wheel == null && crank == null) return null
        return CpsNativeEventSnapshot(
            wheel?.cumulativeRevolutions,
            wheel?.lastEventTimeTicks,
            crank?.cumulativeRevolutions,
            crank?.lastEventTimeTicks,
        )
    }
}
