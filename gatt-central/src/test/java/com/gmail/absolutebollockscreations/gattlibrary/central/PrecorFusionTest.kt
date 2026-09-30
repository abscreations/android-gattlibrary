package com.gmail.absolutebollockscreations.gattlibrary.central

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import com.gmail.absolutebollockscreations.gattlibrary.core.ids.PrecorUuids
import com.gmail.absolutebollockscreations.gattlibrary.core.ids.SigUuids
import com.gmail.absolutebollockscreations.gattlibrary.core.vendor.PrecorSpec

/** Phase 5b §11.6 coverage through the real Chrono decoder and emission mapping. */
class PrecorFusionTest {

    private val chronoAddress = "C0:00:00:00:00:01"

    private fun chronoSource(generation: Long) = MetricSourceId(
        ConnectionId(chronoAddress, generation),
        MetricProtocol.VENDOR,
        PrecorUuids.UUID_PRECOR_MEASUREMENT,
    )

    private fun emitChrono(
        fusion: MetricFusion,
        generation: Long,
        arrivalMs: Long,
        distanceMetres: Int = 1_000,
        calories: Int = 50,
        powerWatts: Int = 180,
    ): MetricSourceId {
        val source = chronoSource(generation)
        val reading = requireNotNull(
            PrecorMeasurement.parse(
                chronoFrame(
                    elapsedSeconds = (arrivalMs / 1_000L).toInt(),
                    heartRate = 142,
                    speedHundredthsKph = 2_283,
                    cadenceTenthsRpm = 940,
                    distanceMetres = distanceMetres,
                    calories = calories,
                    powerWatts = powerWatts,
                ),
            ),
        )
        val emitter = MetricEmitter(fusion, source, MetricSourceClass.VENDOR_NATIVE, arrivalMs)
        precorSamples(reading).forEach { sample ->
            emitter.emit(
                sample.metric,
                sample.value,
                eventMs = sample.eventMs,
                nativeCumulative = sample.nativeCumulative,
            )
        }
        return source
    }

    @Test
    fun `Chrono supplies ride metrics until a higher-priority cycling source arrives`() {
        val state = BtEngineState()
        val fusion = MetricFusion(state).also { it.setPersona(FusionPersona.RIDE, 0) }
        val chrono = emitChrono(fusion, generation = 1, arrivalMs = 1_000)

        assertEquals(chrono, fusion.selectedSource(MetricKind.POWER))
        assertEquals(180, state.instantPower.value)
        assertEquals(22.83, state.speedKph.value!!, 1e-9)

        val cps = MetricSourceId(
            ConnectionId("C0:00:00:00:00:04", 4),
            MetricProtocol.CPS,
            SigUuids.UUID_CYCLING_POWER_MEASUREMENT,
        )
        MetricEmitter(fusion, cps, MetricSourceClass.CPS, 1_100)
            .emit(MetricKind.POWER, 225)

        assertEquals(cps, fusion.selectedSource(MetricKind.POWER))
        assertEquals(225, state.instantPower.value)

        fusion.assign(MetricKind.POWER, chrono, nowMs = 1_100)
        assertEquals(chrono, fusion.selectedSource(MetricKind.POWER))
        assertEquals(180, state.instantPower.value)

        fusion.clearAssignment(MetricKind.POWER, nowMs = 1_100)
        assertEquals(cps, fusion.selectedSource(MetricKind.POWER))
    }

    @Test
    fun `Chrono metrics expire on arrival-time freshness`() {
        val state = BtEngineState()
        val fusion = MetricFusion(state).also { it.setPersona(FusionPersona.RIDE, 0) }
        emitChrono(fusion, generation = 1, arrivalMs = 1_000)

        fusion.refresh(4_001)

        assertNull(fusion.selectedSource(MetricKind.POWER))
        assertNull(fusion.selectedSource(MetricKind.SPEED))
        assertNull(state.instantPower.value)
        assertNull(state.speedKph.value)
    }

    @Test
    fun `distance and energy remain continuous across a Chrono reconnect`() {
        val state = BtEngineState()
        val fusion = MetricFusion(state).also { it.setPersona(FusionPersona.RIDE, 0) }

        emitChrono(fusion, 1, 1_000, distanceMetres = 100, calories = 10)
        emitChrono(fusion, 1, 2_000, distanceMetres = 120, calories = 12)
        assertEquals(120, state.totalDistanceMeters.value)
        assertEquals(12, state.expEnergyTotal.value)

        fusion.onDisconnected(ConnectionId(chronoAddress, 1), nowMs = 2_100)
        emitChrono(fusion, 2, 2_200, distanceMetres = 2, calories = 1)
        assertEquals("a new native counter establishes an origin", 120, state.totalDistanceMeters.value)
        assertEquals(12, state.expEnergyTotal.value)

        emitChrono(fusion, 2, 2_900, distanceMetres = 5, calories = 3)
        assertEquals(123, state.totalDistanceMeters.value)
        assertEquals(14, state.expEnergyTotal.value)
    }

    @Test
    fun `same-link Chrono counter wrap never moves cumulative totals backwards`() {
        val state = BtEngineState()
        val fusion = MetricFusion(state).also { it.setPersona(FusionPersona.RIDE, 0) }

        emitChrono(
            fusion,
            generation = 1,
            arrivalMs = 1_000,
            distanceMetres = 0xFF_FFFD,
            calories = 0xFFFD,
        )
        assertEquals(0xFF_FFFD, state.totalDistanceMeters.value)
        assertEquals(0xFFFD, state.expEnergyTotal.value)

        // Both native counters wrap on the same live connection. Fusion treats the decrease as
        // a counter boundary, holds the canonical totals, and establishes the new native origin.
        emitChrono(fusion, 1, 2_000, distanceMetres = 2, calories = 1)
        assertEquals(0xFF_FFFD, state.totalDistanceMeters.value)
        assertEquals(0xFFFD, state.expEnergyTotal.value)

        // Post-wrap deltas resume from the new origin rather than freezing or jumping backward.
        emitChrono(fusion, 1, 2_500, distanceMetres = 5, calories = 4)
        assertEquals(0x10_00000, state.totalDistanceMeters.value)
        assertEquals(0x1_0000, state.expEnergyTotal.value)
    }

    private fun chronoFrame(
        elapsedSeconds: Int,
        heartRate: Int,
        speedHundredthsKph: Int,
        cadenceTenthsRpm: Int,
        distanceMetres: Int,
        calories: Int,
        powerWatts: Int,
    ): ByteArray = ByteArray(PrecorSpec.FRAME_LENGTH).also { frame ->
        frame[0] = 83.toByte()
        frame[1] = 89.toByte()
        frame[2] = 22.toByte()
        putU16(frame, 3, elapsedSeconds)
        frame[5] = heartRate.toByte()
        putU16(frame, 6, speedHundredthsKph)
        putU16(frame, 8, cadenceTenthsRpm)
        putU24(frame, 10, distanceMetres)
        putU16(frame, 13, calories)
        putU16(frame, 15, 0)
        putU16(frame, 17, powerWatts)
    }

    private fun putU16(target: ByteArray, offset: Int, value: Int) {
        target[offset] = value.toByte()
        target[offset + 1] = (value ushr 8).toByte()
    }

    private fun putU24(target: ByteArray, offset: Int, value: Int) {
        putU16(target, offset, value)
        target[offset + 2] = (value ushr 16).toByte()
    }
}
