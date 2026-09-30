package com.gmail.absolutebollockscreations.gattlibrary.central

import com.gmail.absolutebollockscreations.gattlibrary.core.ActivityType
import com.gmail.absolutebollockscreations.gattlibrary.core.ids.FecBleUuids

/**
 * One canonical metric derived from a decoded FE-C page, in FitBridge units. Mirrors
 * `SchwinnX70Sample` - a plain data holder the handler turns into `MetricEmitter.emit` calls,
 * kept separate from the emission call site so the mapping itself stays unit-testable without
 * an Android `BluetoothGatt`.
 */
internal data class FeCSample(
    val metric: MetricKind,
    val value: Double,
    val nativeCumulative: Double? = null,
    val eventMs: Long? = null,
)

/**
 * Maps decoded FE-C pages onto canonical metrics, per plan §9.
 *
 * Deliberately per-page rather than per-snapshot: a page 25 notification should not re-emit
 * page 16's speed just because it is sitting in the merged snapshot, and vice versa. Only
 * [FeCPage.GeneralFe] and [FeCPage.TrainerData] carry metrics - product info (page 81) and
 * unrecognized pages produce nothing here.
 */
internal object FeCMetricMapper {

    private const val METERS_PER_SECOND_TO_KMH = 3.6

    /**
     * Page 16 - General FE Data. [snapshot] supplies the rollover-corrected elapsed time and
     * distance; every other field comes straight from [page] since neither needs accumulation.
     */
    fun samplesForGeneralFe(page: FeCPage.GeneralFe, snapshot: FeCMeasurementSnapshot): List<FeCSample> =
        buildList {
            add(FeCSample(MetricKind.SPEED, page.speedMetersPerSecond * METERS_PER_SECOND_TO_KMH))
            snapshot.elapsedTimeSeconds?.let { add(FeCSample(MetricKind.ELAPSED, it)) }
            if (page.distanceTraveledEnabled) {
                snapshot.distanceMeters?.let {
                    add(FeCSample(MetricKind.DISTANCE, it, nativeCumulative = it))
                }
            }
            page.heartRateBpm?.let { add(FeCSample(MetricKind.HEART_RATE, it.toDouble())) }
        }

    fun samplesForGeneralSettings(page: FeCPage.GeneralSettings): List<FeCSample> = buildList {
        page.inclinePercent?.let { add(FeCSample(MetricKind.INCLINATION, it)) }
        page.resistancePercent?.let { add(FeCSample(MetricKind.RESISTANCE, it)) }
    }

    fun samplesForMetabolicData(
        page: FeCPage.GeneralMetabolicData,
        snapshot: FeCMeasurementSnapshot,
    ): List<FeCSample> = buildList {
        page.metabolicEquivalent?.let { add(FeCSample(MetricKind.METABOLIC_EQUIVALENT, it)) }
        page.caloricBurnRateKcalPerHour?.let { add(FeCSample(MetricKind.ENERGY_RATE, it)) }
        if (page.accumulatedCaloriesEnabled) {
            snapshot.accumulatedCalories?.let {
                add(FeCSample(MetricKind.ENERGY, it.toDouble(), nativeCumulative = it.toDouble()))
            }
        }
    }

    /**
     * Page 25 - Specific Trainer Data.
     *
     * [AVERAGE_POWER][MetricKind.AVERAGE_POWER] is only emitted when the rollover-corrected
     * event count actually advanced since the previous frame ([previousEventCountTotal] to
     * [eventCountTotal]) - the accumulated-power counter is a running total *per event*, so a
     * delta computed against an unchanged event count would either divide by zero or restate a
     * stale average as if it were fresh. Both "total" parameters are the session's
     * rollover-corrected totals, not the raw wire bytes, so a byte-256 wraparound between two
     * notifications does not appear as a huge (or negative) average power.
     */
    fun samplesForTrainerData(
        page: FeCPage.TrainerData,
        eventCountTotal: Long,
        accumulatedPowerTotal: Long,
        previousEventCountTotal: Long?,
        previousAccumulatedPowerTotal: Long?,
    ): List<FeCSample> = buildList {
        page.cadenceRpm?.let { add(FeCSample(MetricKind.CADENCE, it.toDouble())) }
        page.instantaneousPowerWatts?.let { add(FeCSample(MetricKind.POWER, it.toDouble())) }

        if (previousEventCountTotal != null && previousAccumulatedPowerTotal != null) {
            val eventDelta = eventCountTotal - previousEventCountTotal
            if (eventDelta > 0) {
                val powerDelta = accumulatedPowerTotal - previousAccumulatedPowerTotal
                add(FeCSample(MetricKind.AVERAGE_POWER, powerDelta.toDouble() / eventDelta.toDouble()))
            }
        }
    }

    /**
     * Page 19 - Specific Treadmill Data. Speed/distance/HR already come from page 16
     * ([samplesForGeneralFe]) per the spec's own minimum-requirements table (§10.1.2.1); this
     * page only ever contributes cadence - it has no power field at all, unlike page 22's rower
     * data, so there is nothing to guess or invent here for a value the wire format never
     * carries.
     */
    fun samplesForTreadmillData(
        page: FeCPage.SpecificTreadmillData,
        snapshot: FeCMeasurementSnapshot = FeCMeasurementSnapshot(),
    ): List<FeCSample> = buildList {
        page.cadenceRpm?.let { add(FeCSample(MetricKind.CADENCE, it.toDouble())) }
        if (page.positiveVerticalDistanceEnabled) {
            snapshot.positiveVerticalDistanceMeters?.let {
                add(FeCSample(MetricKind.ELEVATION_GAIN_POSITIVE, it, nativeCumulative = it))
            }
        }
        if (page.negativeVerticalDistanceEnabled) {
            snapshot.negativeVerticalDistanceMeters?.let {
                add(FeCSample(MetricKind.ELEVATION_GAIN_NEGATIVE, it, nativeCumulative = it))
            }
        }
    }

    fun samplesForEllipticalData(
        page: FeCPage.SpecificEllipticalData,
        snapshot: FeCMeasurementSnapshot,
    ): List<FeCSample> = buildList {
        page.cadenceRpm?.let { add(FeCSample(MetricKind.CADENCE, it.toDouble())) }
        page.instantaneousPowerWatts?.let { add(FeCSample(MetricKind.POWER, it.toDouble())) }
        if (page.positiveVerticalDistanceEnabled) {
            snapshot.positiveVerticalDistanceMeters?.let {
                add(FeCSample(MetricKind.ELEVATION_GAIN_POSITIVE, it, nativeCumulative = it))
            }
        }
        if (page.strideCountEnabled) {
            snapshot.strideCount?.let {
                add(FeCSample(MetricKind.TOTAL_STRIDES, it.toDouble(), nativeCumulative = it.toDouble()))
            }
        }
    }

    /**
     * Page 22 - Specific Rower Data. Speed/distance/HR come from page 16 per the spec's minimum
     * requirements (§10.1.2.3); this page contributes cadence and instantaneous power. Unlike
     * page 25's trainer data, page 22 has no accumulated-power counter, so there is no
     * [MetricKind.AVERAGE_POWER] to derive here - only the instantaneous reading the wire
     * actually carries.
     */
    fun samplesForRowerData(
        page: FeCPage.SpecificRowerData,
        snapshot: FeCMeasurementSnapshot = FeCMeasurementSnapshot(),
    ): List<FeCSample> = buildList {
        page.cadenceRpm?.let { add(FeCSample(MetricKind.CADENCE, it.toDouble())) }
        page.instantaneousPowerWatts?.let { add(FeCSample(MetricKind.POWER, it.toDouble())) }
        if (page.strokeCountEnabled) {
            snapshot.strokeCount?.let {
                add(FeCSample(MetricKind.STROKE_COUNT, it.toDouble(), nativeCumulative = it.toDouble()))
            }
        }
    }

    fun samplesForClimberData(
        page: FeCPage.SpecificClimberData,
        snapshot: FeCMeasurementSnapshot,
    ): List<FeCSample> = buildList {
        page.cadenceRpm?.let { add(FeCSample(MetricKind.CADENCE, it.toDouble())) }
        page.instantaneousPowerWatts?.let { add(FeCSample(MetricKind.POWER, it.toDouble())) }
        if (page.cycleCountEnabled) {
            snapshot.climberCycleCount?.let {
                add(FeCSample(MetricKind.TOTAL_STRIDES, it.toDouble(), nativeCumulative = it.toDouble()))
                add(FeCSample(MetricKind.TOTAL_STEPS, it.toDouble() * 2.0))
            }
        }
    }

    fun samplesForNordicSkierData(
        page: FeCPage.SpecificNordicSkierData,
        snapshot: FeCMeasurementSnapshot,
    ): List<FeCSample> = buildList {
        // Page 24 calls this cadence but defines it as instantaneous strides/minute. Nordic
        // Skier follows the PM5 SkiErg rowing presentation, whose canonical rate is STROKE_RATE;
        // emitting CADENCE would populate only the rower's derived average and leave its
        // instantaneous stroke-rate field empty.
        page.cadenceRpm?.let { add(FeCSample(MetricKind.STROKE_RATE, it.toDouble())) }
        page.instantaneousPowerWatts?.let { add(FeCSample(MetricKind.POWER, it.toDouble())) }
        if (page.strideCountEnabled) {
            snapshot.strideCount?.let {
                add(FeCSample(MetricKind.TOTAL_STRIDES, it.toDouble(), nativeCumulative = it.toDouble()))
            }
        }
    }

    fun samplesForTrainerTorqueData(snapshot: FeCMeasurementSnapshot): List<FeCSample> =
        snapshot.torquePowerWatts?.let { listOf(FeCSample(MetricKind.POWER, it)) }.orEmpty()

    /**
     * Best-effort device classification from the page 16 equipment-type byte, for the existing
     * `updateDeviceMachineFeatureByAddress` UI path (the same mechanism Concept2/Precor/Schwinn
     * x70 use - none of them call `MetricFusion.setPersona` either, so this does not either).
     *
     * Nordic Skier follows the established PM5 SkiErg policy: rowing session/fusion/provider
     * behavior, with ski equipment identity retained separately on the connection context.
     */
    fun activityTypeFor(equipmentType: Int): ActivityType? = when (equipmentType) {
        FecBleUuids.FEC_EQUIPMENT_TREADMILL -> ActivityType.TREADMILL
        FecBleUuids.FEC_EQUIPMENT_ELLIPTICAL -> ActivityType.ELLIPTICAL
        FecBleUuids.FEC_EQUIPMENT_ROWER -> ActivityType.ROWING
        FecBleUuids.FEC_EQUIPMENT_CLIMBER -> ActivityType.STAIRCLIMBER
        FecBleUuids.FEC_EQUIPMENT_NORDIC_SKIER -> ActivityType.ROWING
        // FEC_EQUIPMENT_STATIONARY_BIKE == FEC_EQUIPMENT_TRAINER == 25 on the wire (the ANT+
        // spec gives Trainer and Stationary Bike the same equipment-type value - see
        // BluetoothIds' doc on FEC_EQUIPMENT_STATIONARY_BIKE); one `when` branch covers both,
        // since a duplicate-valued branch would be unreachable dead code, not a second case.
        FecBleUuids.FEC_EQUIPMENT_TRAINER -> ActivityType.BIKING
        else -> null
    }

    /** Presentation identity kept separate from the session/provider activity mapping above. */
    fun machineDeviceTypeFor(equipmentType: Int): BtEngineState.MachineDeviceType? = when (equipmentType) {
        FecBleUuids.FEC_EQUIPMENT_TREADMILL -> BtEngineState.MachineDeviceType.TREADMILL
        FecBleUuids.FEC_EQUIPMENT_ELLIPTICAL -> BtEngineState.MachineDeviceType.ELLIPTICAL
        FecBleUuids.FEC_EQUIPMENT_ROWER -> BtEngineState.MachineDeviceType.ROWER
        FecBleUuids.FEC_EQUIPMENT_CLIMBER -> BtEngineState.MachineDeviceType.STAIR_CLIMBER
        FecBleUuids.FEC_EQUIPMENT_NORDIC_SKIER -> BtEngineState.MachineDeviceType.SKI_ERG
        FecBleUuids.FEC_EQUIPMENT_TRAINER -> BtEngineState.MachineDeviceType.BIKE
        else -> null
    }
}
