package com.gmail.absolutebollockscreations.Fitbridge.bluetooth.simulator

import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine.BluetoothIds
import java.io.ByteArrayOutputStream
import java.util.UUID

/** Bounds-safe PM5 1.30 measurement encoders. */
object Concept2Pm5Encoder {
    fun individual(uuid: UUID, s: Concept2Pm5Snapshot): ByteArray? = when (uuid) {
        BluetoothIds.UUID_C2_GENERAL_STATUS -> generalStatus(s)
        BluetoothIds.UUID_C2_ADDITIONAL_STATUS_1 -> additionalStatus1(s, multiplexed = false)
        BluetoothIds.UUID_C2_ADDITIONAL_STATUS_2 -> additionalStatus2(s, multiplexed = false)
        BluetoothIds.UUID_C2_STROKE_DATA -> strokeData(s, multiplexed = false)
        BluetoothIds.UUID_C2_ADDITIONAL_STROKE_DATA -> additionalStrokeData(s, multiplexed = false)
        BluetoothIds.UUID_C2_SPLIT_INTERVAL_DATA -> splitIntervalData(s)
        BluetoothIds.UUID_C2_ADDITIONAL_SPLIT_INTERVAL -> additionalSplitInterval(s)
        BluetoothIds.UUID_C2_WORKOUT_SUMMARY -> workoutSummary(s, multiplexed = false)
        BluetoothIds.UUID_C2_ADDITIONAL_WORKOUT_SUMMARY -> additionalWorkoutSummary(s, multiplexed = false)
        BluetoothIds.UUID_C2_HEART_RATE_BELT_INFO -> heartRateBeltInformation()
        BluetoothIds.UUID_C2_ADDITIONAL_WORKOUT_SUMMARY_2 -> additionalWorkoutSummary2(s)
        BluetoothIds.UUID_C2_ADDITIONAL_STATUS_3 -> additionalStatus3(s)
        else -> null
    }

    fun multiplexed(id: Int, s: Concept2Pm5Snapshot): ByteArray? {
        val payload = when (id) {
            0x31 -> generalStatus(s)
            0x32 -> additionalStatus1(s, multiplexed = true)
            0x33 -> additionalStatus2(s, multiplexed = true)
            0x35 -> strokeData(s, multiplexed = true)
            0x36 -> additionalStrokeData(s, multiplexed = true)
            0x37 -> splitIntervalData(s)
            0x38 -> additionalSplitInterval(s)
            0x39 -> workoutSummary(s, multiplexed = true)
            0x3A -> additionalWorkoutSummary(s, multiplexed = true)
            0x3B -> heartRateBeltInformation()
            0x3C -> additionalWorkoutSummary2(s)
            0x3E -> additionalStatus3(s)
            else -> return null
        }
        return byteArrayOf(id.toByte()) + payload
    }

    fun generalStatus(s: Concept2Pm5Snapshot) = packet {
        u24(s.elapsedCentiseconds)
        u24(s.distanceDecimetres)
        u8(s.workoutType); u8(s.intervalType); u8(s.workoutState); u8(s.rowingState); u8(s.strokeState)
        u24(s.distanceDecimetres / 10)
        u24(s.projectedWorkTimeSeconds * 100)
        u8(0); u8(s.dragFactor)
    }

    private fun additionalStatus1(s: Concept2Pm5Snapshot, multiplexed: Boolean) = packet {
        u24(s.elapsedCentiseconds); u16(s.speedMillimetresPerSecond); u8(s.strokeRate); u8(s.heartRate)
        u16(s.currentPaceCentiseconds); u16(s.averagePaceCentiseconds)
        u16(s.restDistanceMetres); u24(s.restTimeCentiseconds)
        if (multiplexed) u16(s.averagePowerWatts)
        u8(s.machineType)
    }

    private fun additionalStatus2(s: Concept2Pm5Snapshot, multiplexed: Boolean) = packet {
        u24(s.elapsedCentiseconds); u8(s.intervalCount)
        if (!multiplexed) u16(s.averagePowerWatts)
        u16(s.totalCalories); u16(s.averagePaceCentiseconds); u16(s.averagePowerWatts)
        u16(s.caloriesPerHour); u24(s.elapsedCentiseconds / 10); u24(s.distanceDecimetres / 10)
    }

    private fun additionalStrokeData(s: Concept2Pm5Snapshot, multiplexed: Boolean) = packet {
        u24(s.elapsedCentiseconds); u16(s.currentPowerWatts); u16(s.caloriesPerHour); u16(s.strokeCount)
        u24(s.projectedWorkTimeSeconds); u24(s.projectedWorkDistanceMetres)
        if (multiplexed) u16(s.workPerStrokeTenthsJoule)
    }

    private fun strokeData(s: Concept2Pm5Snapshot, multiplexed: Boolean) = packet {
        u24(s.elapsedCentiseconds); u24(s.distanceDecimetres)
        u8(s.driveLengthCentimetres); u8(s.driveTimeCentiseconds); u16(s.recoveryTimeCentiseconds)
        u16(s.strokeDistanceCentimetres); u16(s.peakForceTenthsPound); u16(s.averageForceTenthsPound)
        if (!multiplexed) u16(s.workPerStrokeTenthsJoule)
        u16(s.strokeCount)
    }

    private fun splitIntervalData(s: Concept2Pm5Snapshot) = packet {
        u24(s.elapsedCentiseconds); u24(s.distanceDecimetres); u24(s.elapsedCentiseconds / 10)
        u24(s.distanceDecimetres / 10); u16(s.restTimeCentiseconds / 100); u16(s.restDistanceMetres)
        u8(s.intervalType); u8(s.intervalCount)
    }

    private fun additionalSplitInterval(s: Concept2Pm5Snapshot) = packet {
        u24(s.elapsedCentiseconds); u8(s.strokeRate); u8(s.heartRate); u8(s.heartRate)
        u16(s.averagePaceCentiseconds / 10); u16(s.totalCalories); u16(s.caloriesPerHour)
        u16(s.speedMillimetresPerSecond); u16(s.averagePowerWatts); u8(s.dragFactor)
        u8(s.intervalCount); u8(s.machineType)
    }

    private fun workoutSummary(s: Concept2Pm5Snapshot, multiplexed: Boolean) = packet {
        u16(0); u16(0); u24(s.elapsedCentiseconds); u24(s.distanceDecimetres)
        u8(s.strokeRate); u8(s.heartRate); u8(s.heartRate); u8(s.heartRate); u8(s.heartRate)
        u8(s.dragFactor); u8(0); u8(s.workoutType)
        if (!multiplexed) u16(s.averagePaceCentiseconds / 10)
    }

    private fun additionalWorkoutSummary(s: Concept2Pm5Snapshot, multiplexed: Boolean) = packet {
        u16(0); u16(0)
        if (!multiplexed) u8(s.intervalType)
        u16(s.projectedWorkDistanceMetres); u8(s.intervalCount); u16(s.totalCalories)
        u16(s.averagePowerWatts); u24(s.restDistanceMetres); u16(s.restTimeCentiseconds / 100)
        u16(s.caloriesPerHour)
    }

    private fun heartRateBeltInformation() = packet {
        u8(0); u8(0); u16(1); u16(0)
    }

    private fun additionalWorkoutSummary2(s: Concept2Pm5Snapshot) = packet {
        u16(0); u16(0); u16(s.averagePaceCentiseconds / 10); u8(0); u16(0); u8(s.machineType)
    }

    private fun additionalStatus3(s: Concept2Pm5Snapshot) = packet {
        u8(s.operationalState); u8(s.workoutVerificationState)
        u16(s.screenNumber); u16(s.lastError)
        u8(s.calibrationMode); u8(s.calibrationState); u8(s.calibrationStatus)
        u8(s.gameId); u16(s.gameScore)
    }

    /** One complete force curve, split into PM5 frames that always fit ATT MTU 23. */
    fun forceCurve(s: Concept2Pm5Snapshot): List<ByteArray> {
        val peak = (s.peakForceTenthsPound / 10).coerceAtLeast(1)
        val points = (0 until 24).map { index ->
            val x = index / 23.0
            (peak * kotlin.math.sin(kotlin.math.PI * x)).toInt().coerceAtLeast(0)
        }
        val chunks = points.chunked(9)
        return chunks.mapIndexed { sequence, values -> packet {
            u8((chunks.size.coerceAtMost(15) shl 4) or values.size.coerceAtMost(15))
            u8(sequence)
            values.forEach(::u16)
        } }
    }

    private inline fun packet(block: Writer.() -> Unit): ByteArray = Writer().apply(block).bytes()

    private class Writer {
        private val out = ByteArrayOutputStream()
        fun u8(value: Int) = out.write(value.coerceIn(0, 0xFF))
        fun u16(value: Int) {
            val v = value.coerceIn(0, 0xFFFF); out.write(v); out.write(v ushr 8)
        }
        fun u24(value: Int) {
            val v = value.coerceIn(0, 0xFFFFFF); out.write(v); out.write(v ushr 8); out.write(v ushr 16)
        }
        fun bytes(): ByteArray = out.toByteArray()
    }
}
