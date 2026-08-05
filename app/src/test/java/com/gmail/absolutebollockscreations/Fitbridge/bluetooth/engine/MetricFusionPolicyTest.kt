package com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine

import com.gmail.absolutebollockscreations.Fitbridge.HrZoneMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.UUID

class MetricFusionPolicyTest {
    private val ftmsConnection = ConnectionId("AA:00:00:00:00:01", 1)
    private val sensorConnection = ConnectionId("AA:00:00:00:00:02", 2)
    private val otherConnection = ConnectionId("AA:00:00:00:00:03", 3)

    private fun source(
        connection: ConnectionId,
        protocol: MetricProtocol,
        discriminator: Long,
    ) = MetricSourceId(connection, protocol, UUID(0, discriminator))

    private fun sample(
        metric: MetricKind,
        value: Double,
        arrivalMs: Long,
        source: MetricSourceId,
        sourceClass: MetricSourceClass,
        quality: MetricQuality = MetricQuality.NATIVE,
        confidence: Double = 1.0,
        eventMs: Long? = null,
    ) = MetricSample(
        connectionId = source.connectionId,
        sourceId = source,
        protocol = source.protocol,
        sourceClass = sourceClass,
        metric = metric,
        arrivalMs = arrivalMs,
        eventMs = eventMs,
        value = value,
        nativeCumulative = if (metric.cumulative) value else null,
        quality = quality,
        confidence = confidence,
    )

    @Test
    fun `persona priority selects ride run and row defaults`() {
        val fusion = MetricFusion(BtEngineState())
        val ftms = source(ftmsConnection, MetricProtocol.FTMS, 1)
        val wheel = source(sensorConnection, MetricProtocol.CSC, 2)
        val rsc = source(sensorConnection, MetricProtocol.RSC, 3)
        val vendor = source(otherConnection, MetricProtocol.VENDOR, 4)

        fusion.submit(sample(MetricKind.SPEED, 20.0, 1_000, wheel, MetricSourceClass.CSC_WHEEL))
        fusion.submit(sample(MetricKind.SPEED, 18.0, 1_001, ftms, MetricSourceClass.FTMS))
        assertEquals(ftms, fusion.selectedSource(MetricKind.SPEED))

        fusion.setPersona(FusionPersona.RUN, 1_001)
        fusion.submit(sample(MetricKind.SPEED, 12.0, 1_002, rsc, MetricSourceClass.RSC))
        assertEquals(rsc, fusion.selectedSource(MetricKind.SPEED))

        fusion.setPersona(FusionPersona.ROW_PADDLE, 1_002)
        fusion.submit(sample(MetricKind.SPEED, 9.0, 1_003, vendor, MetricSourceClass.VENDOR_NATIVE))
        assertEquals(ftms, fusion.selectedSource(MetricKind.SPEED))
    }

    @Test
    fun `native quality precedes source priority then confidence and recency break ties`() {
        val fusion = MetricFusion(BtEngineState())
        val cps = source(sensorConnection, MetricProtocol.CPS, 10)
        val ftmsA = source(ftmsConnection, MetricProtocol.FTMS, 11)
        val ftmsB = source(otherConnection, MetricProtocol.FTMS, 12)

        fusion.submit(sample(MetricKind.POWER, 300.0, 100, cps, MetricSourceClass.CPS, MetricQuality.DERIVED))
        fusion.submit(sample(MetricKind.POWER, 200.0, 101, ftmsA, MetricSourceClass.FTMS))
        assertEquals(ftmsA, fusion.selectedSource(MetricKind.POWER))

        fusion.submit(sample(MetricKind.POWER, 201.0, 102, ftmsB, MetricSourceClass.FTMS, confidence = 0.8))
        assertEquals(ftmsA, fusion.selectedSource(MetricKind.POWER))
        fusion.submit(sample(MetricKind.POWER, 202.0, 103, ftmsB, MetricSourceClass.FTMS))
        assertEquals(ftmsB, fusion.selectedSource(MetricKind.POWER))

        val nonNativeFusion = MetricFusion(BtEngineState())
        nonNativeFusion.submit(sample(MetricKind.POWER, 180.0, 200, ftmsA, MetricSourceClass.FTMS, MetricQuality.DERIVED))
        nonNativeFusion.submit(sample(MetricKind.POWER, 220.0, 201, cps, MetricSourceClass.CPS, MetricQuality.ESTIMATED))
        assertEquals(ftmsA, nonNativeFusion.selectedSource(MetricKind.POWER))
    }

    @Test
    fun `freshness uses arrival time with four second HR and three second default windows`() {
        val state = BtEngineState()
        val fusion = MetricFusion(state)
        val hrm = source(sensorConnection, MetricProtocol.HRM, 20)
        val ftms = source(ftmsConnection, MetricProtocol.FTMS, 21)

        fusion.submit(sample(MetricKind.HEART_RATE, 150.0, 1_000, hrm, MetricSourceClass.HRM, eventMs = 99_999))
        fusion.submit(sample(MetricKind.HEART_RATE, 130.0, 4_999, ftms, MetricSourceClass.FTMS, eventMs = 1))
        assertEquals(hrm, fusion.selectedSource(MetricKind.HEART_RATE))
        fusion.refresh(5_001)
        assertEquals(ftms, fusion.selectedSource(MetricKind.HEART_RATE))

        fusion.submit(sample(MetricKind.POWER, 210.0, 10_000, ftms, MetricSourceClass.FTMS, eventMs = Long.MAX_VALUE))
        fusion.refresh(13_001)
        assertNull(fusion.selectedSource(MetricKind.POWER))
        assertNull(state.instantPower.value)
    }

    @Test
    fun `manual pin survives staleness and clear resumes automatic selection`() {
        val fusion = MetricFusion(BtEngineState())
        val hrm = source(sensorConnection, MetricProtocol.HRM, 30)
        val ftms = source(ftmsConnection, MetricProtocol.FTMS, 31)
        fusion.submit(sample(MetricKind.HEART_RATE, 145.0, 100, hrm, MetricSourceClass.HRM))
        fusion.assign(MetricKind.HEART_RATE, hrm, nowMs = 10_000)
        fusion.submit(sample(MetricKind.HEART_RATE, 125.0, 9_999, ftms, MetricSourceClass.FTMS))
        fusion.refresh(10_000)
        assertEquals(hrm, fusion.selectedSource(MetricKind.HEART_RATE))

        fusion.clearAssignment(MetricKind.HEART_RATE, nowMs = 10_000)
        assertEquals(ftms, fusion.selectedSource(MetricKind.HEART_RATE))
    }

    @Test
    fun `disconnect and session end clear pins and selected machine`() {
        val fusion = MetricFusion(BtEngineState())
        val hrm = source(sensorConnection, MetricProtocol.HRM, 40)
        val ftms = source(ftmsConnection, MetricProtocol.FTMS, 41)
        fusion.submit(sample(MetricKind.HEART_RATE, 145.0, 1_000, hrm, MetricSourceClass.HRM))
        fusion.submit(sample(MetricKind.HEART_RATE, 125.0, 1_001, ftms, MetricSourceClass.FTMS))
        fusion.assign(MetricKind.HEART_RATE, hrm, 1_001)
        fusion.selectMachine(ftmsConnection)

        fusion.onDisconnected(sensorConnection, 1_002)
        assertNull(fusion.manualAssignment(MetricKind.HEART_RATE))
        assertEquals(ftms, fusion.selectedSource(MetricKind.HEART_RATE))
        assertEquals(ftmsConnection, fusion.selectedMachine())

        fusion.assign(MetricKind.HEART_RATE, ftms, 1_002)
        fusion.endSession()
        assertNull(fusion.manualAssignment(MetricKind.HEART_RATE))
        assertNull(fusion.selectedMachine())
    }

    @Test
    fun `machine selection is independent from metric winner`() {
        val fusion = MetricFusion(BtEngineState())
        val machine = source(ftmsConnection, MetricProtocol.FTMS, 50)
        val cps = source(sensorConnection, MetricProtocol.CPS, 51)
        fusion.selectMachine(ftmsConnection)
        fusion.submit(sample(MetricKind.POWER, 180.0, 100, machine, MetricSourceClass.FTMS))
        fusion.submit(sample(MetricKind.POWER, 220.0, 101, cps, MetricSourceClass.CPS))

        assertEquals(ftmsConnection, fusion.selectedMachine())
        assertEquals(cps, fusion.selectedSource(MetricKind.POWER))
    }

    @Test
    fun `cumulative distance and energy rebase across switches and resets`() {
        val state = BtEngineState()
        val fusion = MetricFusion(state)
        val first = source(ftmsConnection, MetricProtocol.FTMS, 60)
        val second = source(sensorConnection, MetricProtocol.RSC, 61)

        fusion.submit(sample(MetricKind.DISTANCE, 100.0, 100, first, MetricSourceClass.FTMS))
        fusion.submit(sample(MetricKind.DISTANCE, 120.0, 101, first, MetricSourceClass.FTMS))
        assertEquals(120, state.totalDistanceMeters.value)
        fusion.assign(MetricKind.DISTANCE, second, 102)
        fusion.submit(sample(MetricKind.DISTANCE, 1_000.0, 102, second, MetricSourceClass.RSC))
        assertEquals(120, state.totalDistanceMeters.value)
        fusion.submit(sample(MetricKind.DISTANCE, 1_010.0, 103, second, MetricSourceClass.RSC))
        assertEquals(130, state.totalDistanceMeters.value)
        fusion.submit(sample(MetricKind.DISTANCE, 5.0, 104, second, MetricSourceClass.RSC))
        fusion.submit(sample(MetricKind.DISTANCE, 8.0, 105, second, MetricSourceClass.RSC))
        assertEquals(133, state.totalDistanceMeters.value)

        fusion.submit(sample(MetricKind.ENERGY, 50.0, 200, first, MetricSourceClass.FTMS))
        fusion.submit(sample(MetricKind.ENERGY, 55.0, 201, first, MetricSourceClass.FTMS))
        fusion.refresh(3_202)
        assertNull(fusion.selectedSource(MetricKind.ENERGY))
        assertEquals(55, state.expEnergyTotal.value)
        fusion.startSession(FusionPersona.RIDE, 3_203)
        assertEquals(0, state.expEnergyTotal.value)
        fusion.submit(sample(MetricKind.ENERGY, 80.0, 3_204, first, MetricSourceClass.FTMS))
        assertEquals(80, state.expEnergyTotal.value)
    }

    @Test
    fun `cumulative distance remains continuous across a recovered link generation`() {
        val state = BtEngineState()
        val fusion = MetricFusion(state)
        val beforeDrop = source(ftmsConnection, MetricProtocol.FTMS, 80)
        val recoveredConnection = ConnectionId(ftmsConnection.address, 99)
        val afterRecovery = source(recoveredConnection, MetricProtocol.FTMS, 80)

        fusion.submit(
            sample(MetricKind.DISTANCE, 100.0, 100, beforeDrop, MetricSourceClass.FTMS)
        )
        fusion.submit(
            sample(MetricKind.DISTANCE, 120.0, 101, beforeDrop, MetricSourceClass.FTMS)
        )
        fusion.onDisconnected(ftmsConnection, nowMs = 102)
        fusion.submit(
            sample(MetricKind.DISTANCE, 2.0, 103, afterRecovery, MetricSourceClass.FTMS)
        )
        assertEquals(120, state.totalDistanceMeters.value)

        fusion.submit(
            sample(MetricKind.DISTANCE, 5.0, 104, afterRecovery, MetricSourceClass.FTMS)
        )
        assertEquals(123, state.totalDistanceMeters.value)
    }

    @Test
    fun `machine elapsed is ignored and FitBridge session clock is authoritative`() {
        val state = BtEngineState()
        val fusion = MetricFusion(state)
        val ftms = source(ftmsConnection, MetricProtocol.FTMS, 70)
        fusion.submit(sample(MetricKind.ELAPSED, 900.0, 100, ftms, MetricSourceClass.FTMS))
        assertNull(state.elapsedTimeSec.value)

        fusion.updateSessionElapsed(12, 101)
        assertEquals(12, state.elapsedTimeSec.value)
        assertEquals(MetricProtocol.SESSION, fusion.latestSample(MetricKind.ELAPSED)?.protocol)
    }

    @Test
    fun `valid zero is accepted while invalid domain samples cannot replace selection`() {
        val state = BtEngineState()
        val fusion = MetricFusion(state)
        val ftms = source(ftmsConnection, MetricProtocol.FTMS, 80)
        assertEquals(FusionSubmission.ACCEPTED, fusion.submit(sample(MetricKind.SPEED, 0.0, 100, ftms, MetricSourceClass.FTMS)))
        assertEquals(0.0, state.speedKph.value!!, 0.0)
        assertEquals(FusionSubmission.REJECTED_INVALID, fusion.submit(sample(MetricKind.HEART_RATE, 0.0, 101, ftms, MetricSourceClass.FTMS)))
        assertEquals(FusionSubmission.REJECTED_INVALID, fusion.submit(sample(MetricKind.INSTANT_PACE, 0.0, 102, ftms, MetricSourceClass.FTMS)))
        assertNull(fusion.selectedSource(MetricKind.HEART_RATE))
    }

    @Test
    fun `HR jump filter is independent per source and zones follow canonical HR`() {
        val state = BtEngineState().apply {
            setHrMaxFromApp(200)
            setHrZoneModeFromApp(HrZoneMode.MaxPercent)
        }
        val fusion = MetricFusion(state)
        val first = source(sensorConnection, MetricProtocol.HRM, 90)
        val second = source(otherConnection, MetricProtocol.HRM, 91)
        fusion.submit(sample(MetricKind.HEART_RATE, 100.0, 0, first, MetricSourceClass.HRM))
        fusion.submit(sample(MetricKind.HEART_RATE, 200.0, 1_000, first, MetricSourceClass.HRM))
        assertEquals(106, state.heartRate.value)
        fusion.submit(sample(MetricKind.HEART_RATE, 150.0, 1_001, second, MetricSourceClass.HRM))
        assertEquals(150, state.heartRate.value)
        assertEquals(3, state.heartRateZone.value)

        state.setHrMaxFromApp(150)
        fusion.recalculateHeartRateZones()
        assertEquals(5, state.heartRateZone.value)
    }

    @Test
    fun `native pace wins then speed-derived pace takes over and expires`() {
        val state = BtEngineState()
        val fusion = MetricFusion(state)
        val native = source(ftmsConnection, MetricProtocol.FTMS, 100)
        val speed = source(sensorConnection, MetricProtocol.RSC, 101)
        fusion.setPersona(FusionPersona.RUN, 100)
        fusion.submit(sample(MetricKind.INSTANT_PACE, 5.0, 100, native, MetricSourceClass.FTMS))
        fusion.submit(sample(MetricKind.SPEED, 10.0, 101, speed, MetricSourceClass.RSC))
        assertEquals(5.0, state.instantPaceMinPerKm.value!!, 0.0)

        fusion.refresh(3_101)
        assertEquals(6.0, state.instantPaceMinPerKm.value!!, 0.0)
        assertEquals(MetricQuality.DERIVED, fusion.latestSample(MetricKind.INSTANT_PACE)?.quality)
        fusion.refresh(3_102)
        assertNull(state.instantPaceMinPerKm.value)
    }

    @Test
    fun `distance-derived pace is session scoped`() {
        val state = BtEngineState()
        val fusion = MetricFusion(state)
        val ftms = source(ftmsConnection, MetricProtocol.FTMS, 110)
        fusion.startSession(FusionPersona.RUN, 0)
        fusion.submit(sample(MetricKind.DISTANCE, 0.0, 100, ftms, MetricSourceClass.FTMS))
        fusion.submit(sample(MetricKind.DISTANCE, 100.0, 6_100, ftms, MetricSourceClass.FTMS))
        assertEquals(1.0, state.instantPaceMinPerKm.value!!, 0.0)

        fusion.startSession(FusionPersona.RUN, 6_101)
        assertNull(state.instantPaceMinPerKm.value)
        fusion.submit(sample(MetricKind.DISTANCE, 10.0, 6_102, ftms, MetricSourceClass.FTMS))
        assertNull(state.instantPaceMinPerKm.value)
    }
}
