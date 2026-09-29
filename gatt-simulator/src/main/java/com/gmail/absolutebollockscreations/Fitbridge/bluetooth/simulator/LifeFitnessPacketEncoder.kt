package com.gmail.absolutebollockscreations.Fitbridge.bluetooth.simulator

import kotlin.math.roundToInt

/**
 * Generated Life Fitness records. The four captured personas retain their physical field order and
 * unavailable sentinels. The encoders deliberately stay inside the four official-client-accepted
 * persona layouts; unobserved machine types and optional fields are not added speculatively.
 */
internal object LifeFitnessPacketEncoder {
    fun encode(machine: LifeFitnessMachine, sample: FtmsMockSample): ByteArray = when (machine) {
        LifeFitnessMachine.TREADMILL -> treadmill(sample)
        LifeFitnessMachine.CROSS_TRAINER -> crossTrainer(sample)
        LifeFitnessMachine.STAIR_CLIMBER -> stairClimber(sample)
        LifeFitnessMachine.INDOOR_BIKE -> indoorBike(sample)
    }

    private fun treadmill(s: FtmsMockSample): ByteArray = bytes {
        u16(0x059E)
        speed(s.speedKph)
        speed(s.averageSpeedKph ?: s.speedKph)
        u24(s.distanceMeters ?: 0)
        s16(((s.inclinationPercent ?: 0.0) * 10).roundToInt())
        // Every retained physical treadmill frame uses Life Fitness's 0x7FFF unavailable value.
        s16(0x7FFF)
        u16(((s.positiveElevationMeters ?: 0.0) * 10).roundToInt())
        u16(0x7FFF)
        capturedEnergy(s)
        u8(s.heartRate)
        u16(s.elapsedSeconds)
    }

    private fun crossTrainer(s: FtmsMockSample): ByteArray = bytes {
        u24(0x2FBE)
        speed(s.speedKph)
        speed(s.averageSpeedKph ?: s.speedKph)
        u24(s.distanceMeters ?: 0)
        u16((s.cadence ?: 0.0).roundToInt())
        u16((s.averageCadence ?: s.cadence ?: 0.0).roundToInt())
        // 0x2ACE Total Stride Count is encoded in 0.1-stride units.
        u16((s.movementCount ?: 0) * 10)
        u16((s.positiveElevationMeters ?: 0.0).roundToInt())
        // All retained Life Fitness cross-trainer frames mark negative gain unavailable.
        u16(0xFFFF)
        s16((s.resistance ?: 0) * 10)
        s16(s.powerWatts ?: 0)
        s16(s.averagePowerWatts ?: s.powerWatts ?: 0)
        capturedEnergy(s)
        u8(s.heartRate)
        u16(s.elapsedSeconds)
    }

    private fun stairClimber(s: FtmsMockSample): ByteArray = bytes {
        u16(0x017E)
        u16(s.floors ?: 0)
        u16((s.cadence ?: 0.0).roundToInt())
        u16((s.averageCadence ?: s.cadence ?: 0.0).roundToInt())
        u16((s.positiveElevationMeters ?: 0.0).roundToInt())
        u16(s.movementCount ?: 0)
        capturedEnergy(s)
        u8(s.heartRate)
        u16(s.elapsedSeconds)
    }

    private fun indoorBike(s: FtmsMockSample): ByteArray = bytes {
        u16(0x0BFA)
        speed(s.speedKph)
        speed(s.averageSpeedKph ?: s.speedKph)
        // Physical Life Fitness frames place both cadence fields here while bit 2 is clear.
        u16(((s.cadence ?: 0.0) * 2).roundToInt())
        u16(((s.averageCadence ?: s.cadence ?: 0.0) * 2).roundToInt())
        u24(s.distanceMeters ?: 0)
        s16(s.resistance ?: 0)
        s16(s.powerWatts ?: 0)
        s16(s.averagePowerWatts ?: s.powerWatts ?: 0)
        capturedEnergy(s)
        u8(s.heartRate)
        u16(s.elapsedSeconds)
    }

    /**
     * The captured consoles compute Total Energy but put the FTMS unavailable sentinels in the
     * two rate members that share its presence flag. Preserve that exact mixed-validity shape.
     */
    private fun Writer.capturedEnergy(s: FtmsMockSample) {
        u16(s.caloriesTotal.coerceIn(0, 0xFFFE))
        u16(0xFFFF)
        u8(0xFF)
    }

    private fun Writer.speed(value: Double?) =
        u16(((value ?: 0.0) * 100).roundToInt())

    private fun bytes(block: Writer.() -> Unit): ByteArray = Writer().apply(block).toByteArray()

    private class Writer {
        private val out = ArrayList<Byte>(34)

        fun u8(value: Int) { out += value.coerceIn(0, 0xFF).toByte() }
        fun u16(value: Int) {
            val v = value.coerceIn(0, 0xFFFF)
            out += (v and 0xFF).toByte(); out += ((v ushr 8) and 0xFF).toByte()
        }
        fun s16(value: Int) {
            val v = value.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()) and 0xFFFF
            out += (v and 0xFF).toByte(); out += ((v ushr 8) and 0xFF).toByte()
        }
        fun u24(value: Int) {
            val v = value.coerceIn(0, 0xFFFFFF)
            out += (v and 0xFF).toByte(); out += ((v ushr 8) and 0xFF).toByte()
            out += ((v ushr 16) and 0xFF).toByte()
        }
        fun toByteArray(): ByteArray = out.toByteArray()
    }

}
