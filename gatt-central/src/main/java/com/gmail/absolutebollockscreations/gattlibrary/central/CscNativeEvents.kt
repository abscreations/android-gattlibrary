package com.gmail.absolutebollockscreations.gattlibrary.central

import com.gmail.absolutebollockscreations.gattlibrary.core.FitBridgeBleInternalApi

data class CscNativeEventSnapshot(
    val cumulativeWheelRevolutions: Long? = null,
    val lastWheelEventTimeTicks: Int? = null,
    val cumulativeCrankRevolutions: Int? = null,
    val lastCrankEventTimeTicks: Int? = null,
)

/** Session-only selected native CSC fields, composed across exact link generations. */
@FitBridgeBleInternalApi
class CscNativeEventStore {
    private data class Entry(
        val wheel: CscMeasurement.WheelEvent? = null,
        val crank: CscMeasurement.CrankEvent? = null,
    )

    private val entries = linkedMapOf<ConnectionId, Entry>()

    @Synchronized
    fun publish(
        connectionId: ConnectionId,
        reading: CscMeasurement.Reading,
        assignedRoles: Set<BtEngineState.CscRole>,
    ) {
        val previous = entries[connectionId]
        val wheel = if (BtEngineState.CscRole.SPEED in assignedRoles) {
            reading.wheel ?: previous?.wheel
        } else {
            null
        }
        val crank = if (BtEngineState.CscRole.CADENCE in assignedRoles) {
            reading.crank ?: previous?.crank
        } else {
            null
        }
        if (wheel == null && crank == null) {
            entries.remove(connectionId)
        } else {
            entries[connectionId] = Entry(wheel, crank)
        }
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
    fun snapshot(): CscNativeEventSnapshot? {
        val ordered = entries.entries.sortedBy { it.key.linkGeneration }
        val wheel = ordered.firstNotNullOfOrNull { it.value.wheel }
        val crank = ordered.firstNotNullOfOrNull { it.value.crank }
        return if (wheel == null && crank == null) {
            null
        } else {
            CscNativeEventSnapshot(
                cumulativeWheelRevolutions = wheel?.cumulativeRevolutions,
                lastWheelEventTimeTicks = wheel?.lastEventTimeTicks,
                cumulativeCrankRevolutions = crank?.cumulativeRevolutions,
                lastCrankEventTimeTicks = crank?.lastEventTimeTicks,
            )
        }
    }
}
