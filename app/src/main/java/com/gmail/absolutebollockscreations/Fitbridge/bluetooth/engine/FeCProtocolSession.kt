package com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine

import timber.log.Timber
import java.util.UUID

/**
 * Rollover-corrected, unit-scaled snapshot of what an FE-C over BLE session has decoded so
 * far. Immutable so a reader (the metric mapper, §9.4) never observes a partially-updated
 * view - [FeCProtocolSession] publishes a new instance each time a page changes state.
 *
 * `null` fields mean "not yet seen this connection generation" (for [productInfo]) or "the
 * device reported this value as unavailable" (the ANT+ sentinel fields) - the two cases are
 * indistinguishable from a snapshot alone, which is fine for Phase 9's scope: nothing here
 * needs to tell "never sent" apart from "explicitly invalid" yet.
 */
internal data class FeCMeasurementSnapshot(
    val equipmentType: Int? = null,
    val equipmentState: FeCEquipmentState? = null,
    val elapsedTimeSeconds: Double? = null,
    val distanceMeters: Double? = null,
    val distanceEnabled: Boolean = false,
    val speedMetersPerSecond: Double? = null,
    val heartRateBpm: Int? = null,
    val cadenceRpm: Int? = null,
    val instantaneousPowerWatts: Int? = null,
    val accumulatedPowerRaw: Long? = null,
    val eventCountRaw: Long? = null,
    val productInfo: FeCPage.ProductInformation? = null,
)

/**
 * [ProtocolSession] for FE-C over BLE.
 *
 * Owns exactly what plan §5.2 assigns it: page-16/page-25 rollover accumulators, the last
 * decoded equipment type/state and product info, and its connection-generation identity.
 * Control mode/target state and the 4Hz write scheduler are added in later work units
 * (§9.6-9.9); this class's Phase-9.2 job is the measurement decode path only, kept pure
 * enough to unit test with byte vectors and a fake clock.
 *
 * Every FEC2 notification is expected to carry exactly one complete ANT frame (plan
 * §2.2/§2.3) - there is no multi-notification reassembly here.
 */
internal class FeCProtocolSession(
    override val connectionId: ConnectionId,
) : ProtocolSession {

    override val protocolId: String = "fec-over-ble"

    private val lock = Any()

    private val elapsedAccumulator = UnsignedModuloAccumulator(modulus = 256)
    private val distanceAccumulator = UnsignedModuloAccumulator(modulus = 256)
    private val eventCountAccumulator = UnsignedModuloAccumulator(modulus = 256)
    private val accumulatedPowerAccumulator = UnsignedModuloAccumulator(modulus = 65536)

    @Volatile
    private var closed = false

    @Volatile
    private var snapshotValue = FeCMeasurementSnapshot()

    @Volatile
    private var lastAppliedPageValue: FeCPage? = null

    val snapshot: FeCMeasurementSnapshot get() = snapshotValue

    /**
     * The most recently applied page, alongside [snapshot] at the moment it was applied.
     *
     * The metric mapper (`FeCMetricMapper`, §9.4) needs both: per-notification fields like
     * instantaneous power come from this page, while rollover-corrected accumulators like
     * elapsed time and distance come from [snapshot]. [FeCPage.GeneralFe], [FeCPage.TrainerData],
     * [FeCPage.SpecificTreadmillData] and [FeCPage.SpecificRowerData] carry metrics; callers
     * should ignore any other value here.
     */
    val lastAppliedPage: FeCPage? get() = lastAppliedPageValue

    override fun onPacket(characteristicUuid: UUID, payload: ByteArray, arrivalMs: Long) {
        if (closed) return
        if (characteristicUuid != BluetoothIds.UUID_FEC_MEASUREMENT) return

        when (val result = FeCFrameCodec.decode(payload)) {
            is FeCFrameDecodeResult.Rejected -> {
                Timber.tag(TAG).w(
                    "FE-C frame rejected reason=%s connection=%s bytes=%s",
                    result.reason,
                    connectionId,
                    payload.joinToString(" ") { "%02X".format(it) },
                )
            }
            is FeCFrameDecodeResult.Decoded -> applyPage(FeCPageParsers.decodePage(result.frame.data))
        }
    }

    private fun applyPage(page: FeCPage) {
        synchronized(lock) {
            if (closed) return
            lastAppliedPageValue = page
            snapshotValue = when (page) {
                is FeCPage.GeneralFe -> applyGeneralFe(page)
                is FeCPage.TrainerData -> applyTrainerData(page)
                is FeCPage.SpecificTreadmillData -> applySpecificTreadmillData(page)
                is FeCPage.SpecificRowerData -> applySpecificRowerData(page)
                is FeCPage.ProductInformation -> snapshotValue.copy(productInfo = page)
                is FeCPage.Unknown -> {
                    Timber.tag(TAG).d(
                        "FE-C unhandled/short page=%d connection=%s",
                        page.page,
                        connectionId,
                    )
                    snapshotValue
                }
            }
        }
    }

    private fun applyGeneralFe(page: FeCPage.GeneralFe): FeCMeasurementSnapshot {
        val elapsedTotal = elapsedAccumulator.accumulate(page.elapsedTimeRaw.toLong())
        val distanceTotal = distanceAccumulator.accumulate(page.distanceTraveledRaw.toLong())
        return snapshotValue.copy(
            equipmentType = page.equipmentType,
            equipmentState = page.equipmentState,
            elapsedTimeSeconds = elapsedTotal / 4.0,
            distanceMeters = if (page.distanceTraveledEnabled) distanceTotal.toDouble() else snapshotValue.distanceMeters,
            distanceEnabled = page.distanceTraveledEnabled,
            speedMetersPerSecond = page.speedMetersPerSecond,
            heartRateBpm = page.heartRateBpm,
        )
    }

    private fun applyTrainerData(page: FeCPage.TrainerData): FeCMeasurementSnapshot {
        val eventCountTotal = eventCountAccumulator.accumulate(page.eventCountRaw.toLong())
        val accumulatedPowerTotal = accumulatedPowerAccumulator.accumulate(page.accumulatedPowerRaw.toLong())
        return snapshotValue.copy(
            // Page 25's equipment-state bits are the same enum as page 16's; prefer the more
            // recently observed one rather than letting a stale page-16 value win forever.
            equipmentState = page.equipmentState ?: snapshotValue.equipmentState,
            cadenceRpm = page.cadenceRpm,
            instantaneousPowerWatts = page.instantaneousPowerWatts,
            accumulatedPowerRaw = accumulatedPowerTotal,
            eventCountRaw = eventCountTotal,
        )
    }

    /**
     * Page 19 has no accumulated counter this session tracks (unlike page 25's event
     * count/accumulated power), so this is a direct field copy - no [UnsignedModuloAccumulator]
     * involved.
     */
    private fun applySpecificTreadmillData(page: FeCPage.SpecificTreadmillData): FeCMeasurementSnapshot =
        snapshotValue.copy(
            equipmentState = page.equipmentState ?: snapshotValue.equipmentState,
            cadenceRpm = page.cadenceRpm,
        )

    /** Page 22, like page 19, carries no accumulated counter this session reconstructs. */
    private fun applySpecificRowerData(page: FeCPage.SpecificRowerData): FeCMeasurementSnapshot =
        snapshotValue.copy(
            equipmentState = page.equipmentState ?: snapshotValue.equipmentState,
            cadenceRpm = page.cadenceRpm,
            instantaneousPowerWatts = page.instantaneousPowerWatts,
        )

    override fun onSubscriptionsReady(nowMs: Long) {
        // No control scheduler yet (added in §9.9); nothing to start here in Phase 9.2.
    }

    override fun onTick(nowMs: Long) {
        // No periodic write cadence yet (added in §9.9).
    }

    override fun close() {
        synchronized(lock) {
            closed = true
            snapshotValue = FeCMeasurementSnapshot()
            lastAppliedPageValue = null
            elapsedAccumulator.reset()
            distanceAccumulator.reset()
            eventCountAccumulator.reset()
            accumulatedPowerAccumulator.reset()
        }
    }

    companion object {
        private const val TAG = "FeCProtocolSession"
    }
}
