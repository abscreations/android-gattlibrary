package com.gmail.absolutebollockscreations.gattlibrary.simulator

import com.gmail.absolutebollockscreations.gattlibrary.server.MovementCountKind
import com.gmail.absolutebollockscreations.gattlibrary.server.SimulatedUpstreamSample

/** Maps the generator's sample onto the host contract (LIB-19k); [kind] routes the movement count. */
internal fun FtmsMockSample.toUpstreamSample(kind: MovementCountKind?): SimulatedUpstreamSample =
    SimulatedUpstreamSample(
        elapsedSeconds = elapsedSeconds,
        heartRate = heartRate,
        caloriesTotal = caloriesTotal,
        caloriesPerHour = caloriesPerHour,
        speedKph = speedKph,
        averageSpeedKph = averageSpeedKph,
        distanceMeters = distanceMeters,
        cadence = cadence,
        averageCadence = averageCadence,
        resistance = resistance,
        inclinationPercent = inclinationPercent,
        powerWatts = powerWatts,
        averagePowerWatts = averagePowerWatts,
        forceOnBeltNewtons = forceOnBeltNewtons,
        instantPaceMinPerKm = instantPaceMinPerKm,
        averagePaceMinPerKm = averagePaceMinPerKm,
        movementCount = movementCount,
        movementCountKind = kind,
        positiveElevationMeters = positiveElevationMeters,
        floors = floors,
    )
