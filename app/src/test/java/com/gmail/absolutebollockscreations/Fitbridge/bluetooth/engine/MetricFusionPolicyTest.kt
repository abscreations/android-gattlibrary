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

        // This used to assert that the machine took over once the strap aged out
        // of the 4 s window. That was the reported 2026-08-06 defect rather than
        // the intended policy: a strap missing one or two notifications handed
        // heart rate to a machine reading 120-140 against a strap's 54-58, and
        // the graph alternated between them. A connected strap now owns the
        // metric, and its going quiet reads as no pulse rather than as the
        // machine's number.
        fusion.refresh(5_001)
        assertNull(
            "a stale strap must not hand heart rate to the machine",
            fusion.selectedSource(MetricKind.HEART_RATE),
        )

        fusion.submit(sample(MetricKind.POWER, 210.0, 10_000, ftms, MetricSourceClass.FTMS, eventMs = Long.MAX_VALUE))
        fusion.refresh(13_001)
        assertNull(fusion.selectedSource(MetricKind.POWER))
        assertNull(state.instantPower.value)
    }

    @Test
    fun `a sample that arrives mid-parse of another packet is not stale`() {
        // Regression, hardware session 948b of 2026-08-07.
        //
        // An FTMS packet carries fifteen fields under one arrival stamp taken when
        // the packet landed, and parsing it takes tens of milliseconds. A strap
        // notification arriving in that window is stamped LATER than the packet
        // being parsed. `submit` reselects with the submitting sample's own
        // arrivalMs, so the strap's sample was in the future relative to "now",
        // `isFresh` computed a negative age, and the `age >= 0` guard threw out a
        // sample forty milliseconds old.
        val state = BtEngineState()
        val fusion = MetricFusion(state)
        val hrm = source(sensorConnection, MetricProtocol.HRM, 40)
        val ftms = source(ftmsConnection, MetricProtocol.FTMS, 41)

        // The machine's packet lands at 10_000 and the strap's at 10_014 - inside
        // the parse. The machine's heart-rate field is submitted last, still
        // carrying the packet's own 10_000 stamp.
        fusion.submit(sample(MetricKind.HEART_RATE, 58.0, 10_014, hrm, MetricSourceClass.HRM))
        val seriesBefore = state.hrSeries.value.size
        fusion.submit(sample(MetricKind.HEART_RATE, 148.0, 10_000, ftms, MetricSourceClass.FTMS))

        assertEquals(
            "the strap is 14 ms old, not stale",
            hrm,
            fusion.selectedSource(MetricKind.HEART_RATE),
        )
        assertEquals(58, state.heartRate.value)
        assertEquals(BtEngineState.HrSource.EXTERNAL_HRM, state.heartRateSource.value)
        assertEquals(
            "no dropout may be projected into the graph",
            seriesBefore,
            state.hrSeries.value.size,
        )
    }

    @Test
    fun `a genuinely silent strap still reads as a dropout`() {
        // The counterpart to the test above: widening the freshness reference must
        // not disable staleness. Nothing may arrive after the strap, or the newest
        // evidence would carry the window along with it.
        val state = BtEngineState()
        val fusion = MetricFusion(state)
        val hrm = source(sensorConnection, MetricProtocol.HRM, 42)

        fusion.submit(sample(MetricKind.HEART_RATE, 58.0, 1_000, hrm, MetricSourceClass.HRM))
        assertEquals(58, state.heartRate.value)

        fusion.refresh(5_001)

        assertNull(fusion.selectedSource(MetricKind.HEART_RATE))
        assertNull(state.heartRate.value)
        assertEquals(BtEngineState.HrSource.UNKNOWN, state.heartRateSource.value)
        assertEquals(
            "a real dropout is still marked with an explicit zero",
            0.0,
            state.hrSeries.value.last().v,
            1e-9,
        )
    }

    @Test
    fun `machine packets do not drag a live strap out of the freshness window`() {
        // The session-shaped case: the machine notifies at over 1 Hz for a minute
        // while the strap notifies about every 0.75 s, and every machine packet
        // re-runs selection. Five spurious zeros reached the graph in ninety
        // seconds of real hardware; none may reach it here.
        val state = BtEngineState()
        val fusion = MetricFusion(state)
        val hrm = source(sensorConnection, MetricProtocol.HRM, 43)
        val ftms = source(ftmsConnection, MetricProtocol.FTMS, 44)

        var t = 1_000L
        repeat(40) {
            // Machine packet lands, then the strap lands 14 ms into its parse, then
            // the machine's heart-rate field is submitted with the older stamp.
            val machineArrival = t
            fusion.submit(sample(MetricKind.HEART_RATE, 58.0, machineArrival + 14, hrm, MetricSourceClass.HRM))
            fusion.submit(sample(MetricKind.HEART_RATE, 148.0, machineArrival, ftms, MetricSourceClass.FTMS))
            t += 750
        }

        assertEquals(hrm, fusion.selectedSource(MetricKind.HEART_RATE))
        assertEquals(58, state.heartRate.value)
        assertNull(
            "the machine's heart rate must never reach the graph",
            state.hrSeries.value.firstOrNull { it.v > 100.0 },
        )
        assertNull(
            "and neither may a spurious dropout",
            state.hrSeries.value.firstOrNull { it.v == 0.0 },
        )
    }

    @Test
    fun `a foot pod keeps priority over a machine, as section 4-1 requires`() {
        // The half of §4.1 that must survive: RSC > FTMS for running speed. A pod
        // with no heart rate on its link is a foot pod, and it still wins.
        val fusion = MetricFusion(BtEngineState())
        val pod = source(sensorConnection, MetricProtocol.RSC, 50)
        val ftms = source(ftmsConnection, MetricProtocol.FTMS, 51)
        fusion.setPersona(FusionPersona.RUN, 0)

        fusion.submit(sample(MetricKind.SPEED, 3.0, 1_000, ftms, MetricSourceClass.FTMS))
        fusion.submit(sample(MetricKind.SPEED, 11.5, 1_010, pod, MetricSourceClass.RSC))

        assertEquals(pod, fusion.selectedSource(MetricKind.SPEED))
    }

    @Test
    fun `a torso strap stands aside from speed while a machine reports`() {
        // Session 948b: one link carrying both HRM and RSC - a chest strap by
        // construction, since a foot pod does not measure heart rate - reported
        // 19.11 km/h at cadence 49 beside a treadmill steady at 3.00.
        val fusion = MetricFusion(BtEngineState())
        val strapLink = ConnectionId("AA:00:00:00:00:09", 4)
        val strapHrm = MetricSourceId(strapLink, MetricProtocol.HRM, UUID(0, 52))
        val strapRsc = MetricSourceId(strapLink, MetricProtocol.RSC, UUID(0, 53))
        val ftms = source(ftmsConnection, MetricProtocol.FTMS, 54)
        fusion.setPersona(FusionPersona.RUN, 0)

        fusion.submit(sample(MetricKind.HEART_RATE, 58.0, 1_000, strapHrm, MetricSourceClass.HRM))
        fusion.submit(sample(MetricKind.SPEED, 3.0, 1_005, ftms, MetricSourceClass.FTMS))
        fusion.submit(sample(MetricKind.SPEED, 19.11, 1_010, strapRsc, MetricSourceClass.RSC))

        assertEquals(ftms, fusion.selectedSource(MetricKind.SPEED))

        // Cadence is untouched: the treadmill does not report it at all, and a
        // torso strap counts steps perfectly well even when it cannot measure pace.
        fusion.submit(sample(MetricKind.CADENCE, 49.0, 1_011, strapRsc, MetricSourceClass.RSC))
        assertEquals(strapRsc, fusion.selectedSource(MetricKind.CADENCE))
    }

    @Test
    fun `a torso strap supplies speed again once the machine is gone`() {
        // Setting aside must not strand the metric. With no machine candidate the
        // strap's estimate is better than nothing.
        val fusion = MetricFusion(BtEngineState())
        val strapLink = ConnectionId("AA:00:00:00:00:0A", 5)
        val strapHrm = MetricSourceId(strapLink, MetricProtocol.HRM, UUID(0, 55))
        val strapRsc = MetricSourceId(strapLink, MetricProtocol.RSC, UUID(0, 56))
        fusion.setPersona(FusionPersona.RUN, 0)

        fusion.submit(sample(MetricKind.HEART_RATE, 58.0, 1_000, strapHrm, MetricSourceClass.HRM))
        fusion.submit(sample(MetricKind.SPEED, 5.2, 1_010, strapRsc, MetricSourceClass.RSC))

        assertEquals(strapRsc, fusion.selectedSource(MetricKind.SPEED))
    }

    @Test
    fun `a manual pin overrides the torso-strap exclusion`() {
        val fusion = MetricFusion(BtEngineState())
        val strapLink = ConnectionId("AA:00:00:00:00:0B", 6)
        val strapHrm = MetricSourceId(strapLink, MetricProtocol.HRM, UUID(0, 57))
        val strapRsc = MetricSourceId(strapLink, MetricProtocol.RSC, UUID(0, 58))
        val ftms = source(ftmsConnection, MetricProtocol.FTMS, 59)
        fusion.setPersona(FusionPersona.RUN, 0)

        fusion.submit(sample(MetricKind.HEART_RATE, 58.0, 1_000, strapHrm, MetricSourceClass.HRM))
        fusion.submit(sample(MetricKind.SPEED, 3.0, 1_005, ftms, MetricSourceClass.FTMS))
        fusion.submit(sample(MetricKind.SPEED, 19.11, 1_010, strapRsc, MetricSourceClass.RSC))
        assertEquals(ftms, fusion.selectedSource(MetricKind.SPEED))

        fusion.assign(MetricKind.SPEED, strapRsc, 1_011)
        assertEquals(
            "an explicit choice is not second-guessed by a heuristic",
            strapRsc,
            fusion.selectedSource(MetricKind.SPEED),
        )
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

        // Clearing the pin resumes automatic selection. That no longer means the
        // machine wins: with a strap connected, automatic selection considers
        // only the strap, and this one is stale.
        fusion.clearAssignment(MetricKind.HEART_RATE, nowMs = 10_000)
        assertNull(
            "automatic selection must not fall back to the machine",
            fusion.selectedSource(MetricKind.HEART_RATE),
        )

        // Proving selection genuinely resumed rather than merely staying empty:
        // a fresh strap reading is picked up without another assign().
        fusion.submit(sample(MetricKind.HEART_RATE, 146.0, 10_100, hrm, MetricSourceClass.HRM))
        assertEquals(hrm, fusion.selectedSource(MetricKind.HEART_RATE))
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
    fun `machine elapsed wins while fresh and the session clock is the fallback`() {
        val state = BtEngineState()
        val fusion = MetricFusion(state)
        val ftms = source(ftmsConnection, MetricProtocol.FTMS, 70)

        // A machine that reports its own elapsed time owns the value.
        fusion.submit(sample(MetricKind.ELAPSED, 900.0, 100, ftms, MetricSourceClass.FTMS))
        assertEquals(900, state.elapsedTimeSec.value)
        assertEquals(MetricProtocol.FTMS, fusion.latestSample(MetricKind.ELAPSED)?.protocol)

        // The session clock cannot displace a machine that is still reporting.
        fusion.updateSessionElapsed(12, 101)
        assertEquals(900, state.elapsedTimeSec.value)
        assertEquals(MetricProtocol.FTMS, fusion.latestSample(MetricKind.ELAPSED)?.protocol)

        // Once the machine's sample goes stale the session clock takes over.
        // Fusion's default freshness window is 3 s; the constant itself is private.
        val afterStale = 100L + 3_000L + 1L
        fusion.updateSessionElapsed(310, afterStale)
        assertEquals(310, state.elapsedTimeSec.value)
        assertEquals(MetricProtocol.SESSION, fusion.latestSample(MetricKind.ELAPSED)?.protocol)
    }

    @Test
    fun `a machine that never reports elapsed still gets a running session clock`() {
        val state = BtEngineState()
        val fusion = MetricFusion(state)
        assertNull(state.elapsedTimeSec.value)

        fusion.updateSessionElapsed(0, 100)
        assertEquals(0, state.elapsedTimeSec.value)

        fusion.updateSessionElapsed(1, 1_100)
        assertEquals(1, state.elapsedTimeSec.value)
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
