package com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine

import timber.log.Timber
import java.util.UUID

/**
 * Rollover-corrected, unit-scaled snapshot of what an FE-C over BLE session has decoded so
 * far. Immutable so a reader (the metric mapper, §9.4) never observes a partially-updated
 * view - [FeCProtocolSession] publishes a new instance each time a page changes state.
 *
 * `null` fields mean "not yet seen this connection generation" (for [manufacturerInfo] and
 * [productInfo]) or "the
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
    val cycleLengthMeters: Double? = null,
    val inclinePercent: Double? = null,
    val resistancePercent: Double? = null,
    val metabolicEquivalent: Double? = null,
    val caloricBurnRateKcalPerHour: Double? = null,
    val accumulatedCalories: Long? = null,
    val cadenceRpm: Int? = null,
    val instantaneousPowerWatts: Int? = null,
    val positiveVerticalDistanceMeters: Double? = null,
    val negativeVerticalDistanceMeters: Double? = null,
    val strideCount: Long? = null,
    val strokeCount: Long? = null,
    val climberCycleCount: Long? = null,
    val torquePowerWatts: Double? = null,
    val torqueEventCount: Long? = null,
    val torqueWheelTicks: Long? = null,
    val accumulatedWheelPeriodRaw: Long? = null,
    val accumulatedTorqueRaw: Long? = null,
    val accumulatedPowerRaw: Long? = null,
    val eventCountRaw: Long? = null,
    val feCapabilities: FeCPage.FeCapabilities? = null,
    val manufacturerInfo: FeCPage.ManufacturerInformation? = null,
    val productInfo: FeCPage.ProductInformation? = null,
)

/**
 * [ProtocolSession] for FE-C over BLE.
 *
 * Owns the rollover accumulators for every implemented cumulative page, the last decoded
 * equipment type/state/capabilities/product info, and its connection-generation identity.
 * Control target scheduling stays outside this class; its job remains the pure measurement
 * decode/session-math path.
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
    private val caloriesAccumulator = UnsignedModuloAccumulator(modulus = 256)
    private val treadmillPositiveVerticalAccumulator = UnsignedModuloAccumulator(modulus = 256)
    private val treadmillNegativeVerticalAccumulator = UnsignedModuloAccumulator(modulus = 256)
    private val ellipticalPositiveVerticalAccumulator = UnsignedModuloAccumulator(modulus = 256)
    private val ellipticalStrideAccumulator = UnsignedModuloAccumulator(modulus = 256)
    private val rowerStrokeAccumulator = UnsignedModuloAccumulator(modulus = 256)
    private val climberCycleAccumulator = UnsignedModuloAccumulator(modulus = 256)
    private val nordicStrideAccumulator = UnsignedModuloAccumulator(modulus = 256)
    private val torqueEventAccumulator = UnsignedModuloAccumulator(modulus = 256)
    private val torqueWheelTicksAccumulator = UnsignedModuloAccumulator(modulus = 256)
    private val torquePeriodAccumulator = UnsignedModuloAccumulator(modulus = 65536)
    private val torqueAccumulator = UnsignedModuloAccumulator(modulus = 65536)

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
                // Prevent the handler from re-emitting the previous valid page for a malformed
                // notification. A rejected frame is not fresh telemetry and must not satisfy
                // adapter liveness by replaying stale samples.
                lastAppliedPageValue = null
                CaptureHooks.parserRejection()
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
                is FeCPage.GeneralSettings -> applyGeneralSettings(page)
                is FeCPage.GeneralMetabolicData -> applyGeneralMetabolicData(page)
                is FeCPage.TrainerData -> applyTrainerData(page)
                is FeCPage.SpecificTreadmillData -> applySpecificTreadmillData(page)
                is FeCPage.SpecificEllipticalData -> applySpecificEllipticalData(page)
                is FeCPage.SpecificRowerData -> applySpecificRowerData(page)
                is FeCPage.SpecificClimberData -> applySpecificClimberData(page)
                is FeCPage.SpecificNordicSkierData -> applySpecificNordicSkierData(page)
                is FeCPage.TrainerTorqueData -> applyTrainerTorqueData(page)
                is FeCPage.FeCapabilities -> snapshotValue.copy(feCapabilities = page)
                is FeCPage.ManufacturerInformation -> snapshotValue.copy(manufacturerInfo = page)
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

    private fun applyGeneralSettings(page: FeCPage.GeneralSettings): FeCMeasurementSnapshot =
        snapshotValue.copy(
            equipmentState = page.equipmentState ?: snapshotValue.equipmentState,
            cycleLengthMeters = page.cycleLengthMeters,
            inclinePercent = page.inclinePercent,
            resistancePercent = page.resistancePercent,
        )

    private fun applyGeneralMetabolicData(page: FeCPage.GeneralMetabolicData): FeCMeasurementSnapshot {
        val calories = if (page.accumulatedCaloriesEnabled) {
            caloriesAccumulator.accumulate(page.accumulatedCaloriesRaw.toLong())
        } else {
            null
        }
        return snapshotValue.copy(
            equipmentState = page.equipmentState ?: snapshotValue.equipmentState,
            metabolicEquivalent = page.metabolicEquivalent,
            caloricBurnRateKcalPerHour = page.caloricBurnRateKcalPerHour,
            accumulatedCalories = calories ?: snapshotValue.accumulatedCalories,
        )
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

    /** Page 19's positive and negative vertical counters have independent rollover domains. */
    private fun applySpecificTreadmillData(page: FeCPage.SpecificTreadmillData): FeCMeasurementSnapshot {
        val negative = if (page.negativeVerticalDistanceEnabled) {
            treadmillNegativeVerticalAccumulator.accumulate(page.negativeVerticalDistanceRaw.toLong())
        } else null
        val positive = if (page.positiveVerticalDistanceEnabled) {
            treadmillPositiveVerticalAccumulator.accumulate(page.positiveVerticalDistanceRaw.toLong())
        } else null
        return snapshotValue.copy(
            equipmentState = page.equipmentState ?: snapshotValue.equipmentState,
            cadenceRpm = page.cadenceRpm,
            negativeVerticalDistanceMeters = negative?.div(10.0) ?: snapshotValue.negativeVerticalDistanceMeters,
            positiveVerticalDistanceMeters = positive?.div(10.0) ?: snapshotValue.positiveVerticalDistanceMeters,
        )
    }

    private fun applySpecificEllipticalData(page: FeCPage.SpecificEllipticalData): FeCMeasurementSnapshot {
        val vertical = if (page.positiveVerticalDistanceEnabled) {
            ellipticalPositiveVerticalAccumulator.accumulate(page.positiveVerticalDistanceRaw.toLong())
        } else null
        val strides = if (page.strideCountEnabled) {
            ellipticalStrideAccumulator.accumulate(page.strideCountRaw.toLong())
        } else null
        return snapshotValue.copy(
            equipmentState = page.equipmentState ?: snapshotValue.equipmentState,
            cadenceRpm = page.cadenceRpm,
            instantaneousPowerWatts = page.instantaneousPowerWatts,
            positiveVerticalDistanceMeters = vertical?.div(10.0) ?: snapshotValue.positiveVerticalDistanceMeters,
            strideCount = strides ?: snapshotValue.strideCount,
        )
    }

    /** Page 22 stroke count is independently rollover-corrected from every other persona count. */
    private fun applySpecificRowerData(page: FeCPage.SpecificRowerData): FeCMeasurementSnapshot {
        val strokes = if (page.strokeCountEnabled) {
            rowerStrokeAccumulator.accumulate(page.strokeCountRaw.toLong())
        } else null
        return snapshotValue.copy(
            equipmentState = page.equipmentState ?: snapshotValue.equipmentState,
            cadenceRpm = page.cadenceRpm,
            instantaneousPowerWatts = page.instantaneousPowerWatts,
            strokeCount = strokes ?: snapshotValue.strokeCount,
        )
    }

    private fun applySpecificClimberData(page: FeCPage.SpecificClimberData): FeCMeasurementSnapshot {
        val cycles = if (page.cycleCountEnabled) {
            climberCycleAccumulator.accumulate(page.cycleCountRaw.toLong())
        } else null
        return snapshotValue.copy(
            equipmentState = page.equipmentState ?: snapshotValue.equipmentState,
            cadenceRpm = page.cadenceRpm,
            instantaneousPowerWatts = page.instantaneousPowerWatts,
            climberCycleCount = cycles ?: snapshotValue.climberCycleCount,
        )
    }

    private fun applySpecificNordicSkierData(page: FeCPage.SpecificNordicSkierData): FeCMeasurementSnapshot {
        val strides = if (page.strideCountEnabled) {
            nordicStrideAccumulator.accumulate(page.strideCountRaw.toLong())
        } else null
        return snapshotValue.copy(
            equipmentState = page.equipmentState ?: snapshotValue.equipmentState,
            cadenceRpm = page.cadenceRpm,
            instantaneousPowerWatts = page.instantaneousPowerWatts,
            strideCount = strides ?: snapshotValue.strideCount,
        )
    }

    private fun applyTrainerTorqueData(page: FeCPage.TrainerTorqueData): FeCMeasurementSnapshot {
        val previousEvents = if (torqueEventAccumulator.hasSample) torqueEventAccumulator.total else null
        val previousPeriod = if (torquePeriodAccumulator.hasSample) torquePeriodAccumulator.total else null
        val previousTorque = if (torqueAccumulator.hasSample) torqueAccumulator.total else null
        val events = torqueEventAccumulator.accumulate(page.eventCountRaw.toLong())
        torqueWheelTicksAccumulator.accumulate(page.wheelTicksRaw.toLong())
        val period = torquePeriodAccumulator.accumulate(page.accumulatedWheelPeriodRaw.toLong())
        val torque = torqueAccumulator.accumulate(page.accumulatedTorqueRaw.toLong())
        val power = if (previousEvents != null && previousPeriod != null && previousTorque != null && events > previousEvents) {
            val periodDelta = period - previousPeriod
            if (periodDelta > 0) 128.0 * Math.PI * (torque - previousTorque) / periodDelta else null
        } else {
            null
        }
        return snapshotValue.copy(
            equipmentState = page.equipmentState ?: snapshotValue.equipmentState,
            torquePowerWatts = power,
            torqueEventCount = events,
            torqueWheelTicks = torqueWheelTicksAccumulator.total,
            accumulatedWheelPeriodRaw = period,
            accumulatedTorqueRaw = torque,
        )
    }

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
            caloriesAccumulator.reset()
            treadmillPositiveVerticalAccumulator.reset()
            treadmillNegativeVerticalAccumulator.reset()
            ellipticalPositiveVerticalAccumulator.reset()
            ellipticalStrideAccumulator.reset()
            rowerStrokeAccumulator.reset()
            climberCycleAccumulator.reset()
            nordicStrideAccumulator.reset()
            torqueEventAccumulator.reset()
            torqueWheelTicksAccumulator.reset()
            torquePeriodAccumulator.reset()
            torqueAccumulator.reset()
        }
    }

    companion object {
        private const val TAG = "FeCProtocolSession"
    }
}
