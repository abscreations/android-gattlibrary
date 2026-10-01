package com.gmail.absolutebollockscreations.gattlibrary.central

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test
import java.util.UUID

class MetricFusionTest {
    private val connection = ConnectionId("AA:BB:CC:DD:EE:01", 7L)
    private val characteristic = UUID.randomUUID()

    private fun sample(
        metric: MetricKind,
        value: Double,
        arrivalMs: Long,
        protocol: MetricProtocol = MetricProtocol.FTMS,
        sourceClass: MetricSourceClass = MetricSourceClass.FTMS,
        eventMs: Long? = null,
        nativeCumulative: Double? = if (metric.cumulative) value else null,
    ): MetricSample = MetricSample(
        connectionId = connection,
        sourceId = MetricSourceId(connection, protocol, characteristic),
        protocol = protocol,
        sourceClass = sourceClass,
        metric = metric,
        arrivalMs = arrivalMs,
        eventMs = eventMs,
        value = value,
        nativeCumulative = nativeCumulative,
    )

    @Test
    fun `sample preserves separate monotonic arrival and protocol event time`() {
        val sample = sample(MetricKind.CADENCE, 82.0, arrivalMs = 10_000, eventMs = 512)

        assertEquals(10_000, sample.arrivalMs)
        assertEquals(512L, sample.eventMs)
        assertEquals(connection, sample.sourceId.connectionId)
        assertEquals(characteristic, sample.sourceId.characteristicUuid)
    }

    @Test
    fun `sample rejects inconsistent identity units and non-finite values`() {
        val otherConnection = ConnectionId("AA:BB:CC:DD:EE:02", 8L)
        assertThrows(IllegalArgumentException::class.java) {
            sample(MetricKind.SPEED, Double.NaN, arrivalMs = 1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            sample(MetricKind.SPEED, 12.0, arrivalMs = -1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            MetricSample(
                connectionId = connection,
                sourceId = MetricSourceId(otherConnection, MetricProtocol.FTMS, characteristic),
                protocol = MetricProtocol.FTMS,
                sourceClass = MetricSourceClass.FTMS,
                metric = MetricKind.POWER,
                arrivalMs = 1,
                value = 100.0,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            MetricSample(
                connectionId = connection,
                sourceId = MetricSourceId(connection, MetricProtocol.HRM, characteristic),
                protocol = MetricProtocol.FTMS,
                sourceClass = MetricSourceClass.FTMS,
                metric = MetricKind.POWER,
                arrivalMs = 1,
                value = 100.0,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            MetricSample(
                connectionId = connection,
                sourceId = MetricSourceId(connection, MetricProtocol.FTMS, characteristic),
                protocol = MetricProtocol.FTMS,
                sourceClass = MetricSourceClass.FTMS,
                metric = MetricKind.POWER,
                arrivalMs = 1,
                value = 100.0,
                unit = MetricUnit.BPM,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            sample(MetricKind.POWER, 100.0, arrivalMs = 1).copy(confidence = 1.1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            sample(MetricKind.DISTANCE, 100.0, arrivalMs = 1, nativeCumulative = null)
        }
        assertThrows(IllegalArgumentException::class.java) {
            sample(MetricKind.DISTANCE, 100.0, arrivalMs = 1, nativeCumulative = -1.0)
        }
    }

    @Test
    fun `pass through uses wall clock for graph series and monotonic time for freshness`() {
        val state = BtEngineState()
        val seriesClock = MetricSeriesClock { 1_785_000_000_000L }
        val fusion = MetricFusion(state, seriesClock = seriesClock)

        assertEquals(FusionSubmission.ACCEPTED, fusion.submit(sample(MetricKind.SPEED, 12.5, 100)))
        assertEquals(FusionSubmission.ACCEPTED, fusion.submit(sample(MetricKind.CADENCE, 84.0, 101)))
        assertEquals(FusionSubmission.ACCEPTED, fusion.submit(sample(MetricKind.POWER, 211.0, 102)))
        assertEquals(FusionSubmission.ACCEPTED, fusion.submit(sample(MetricKind.DISTANCE, 432.0, 103)))

        assertEquals(12.5, state.speedKph.value!!, 0.0)
        assertEquals(84.0, state.instantCadence.value!!, 0.0)
        assertEquals(211, state.instantPower.value)
        assertEquals(432, state.totalDistanceMeters.value)
        assertEquals(1_785_000_000_000L, state.series(MetricSeriesKind.SPEED_KPH).single().tMillis)
        assertEquals(1_785_000_000_000L, state.series(MetricSeriesKind.CADENCE_PER_MIN).single().tMillis)
        assertEquals(1_785_000_000_000L, state.series(MetricSeriesKind.POWER_WATTS).single().tMillis)
        assertEquals(1_785_000_000_000L, state.series(MetricSeriesKind.DISTANCE_METERS).single().tMillis)
        assertEquals(103L, fusion.latestSample(MetricKind.DISTANCE)?.arrivalMs)
    }

    @Test
    fun `rejection warning gate suppresses repeats per metric and source then reopens`() {
        val gate = RejectionWarningGate(windowMs = 10_000)
        val pace = sample(MetricKind.AVERAGE_PACE, 0.5, arrivalMs = 1_000)

        assertEquals(true, gate.shouldLog(pace))
        assertEquals(false, gate.shouldLog(pace.copy(arrivalMs = 2_000)))
        assertEquals(true, gate.shouldLog(pace.copy(metric = MetricKind.INSTANT_PACE, arrivalMs = 2_000)))
        assertEquals(true, gate.shouldLog(pace.copy(arrivalMs = 11_000)))
    }

    @Test
    fun `instant CSC speed samples publish a simple average in km per hour`() {
        val state = BtEngineState()
        val fusion = MetricFusion(state)

        fusion.submit(
            sample(
                MetricKind.SPEED,
                12.0,
                arrivalMs = 100,
                protocol = MetricProtocol.CSC,
                sourceClass = MetricSourceClass.CSC_WHEEL,
            ),
        )
        fusion.submit(
            sample(
                MetricKind.SPEED,
                18.0,
                arrivalMs = 101,
                protocol = MetricProtocol.CSC,
                sourceClass = MetricSourceClass.CSC_WHEEL,
            ),
        )

        assertEquals(15.0, state.avgSpeedKph.value!!, 0.0)

        state.setActiveSession(true)

        assertEquals(null, state.avgSpeedKph.value)
    }

    @Test
    fun `instant CSC speed average is the mean of the samples whatever their magnitude`() {
        val state = BtEngineState()
        val fusion = MetricFusion(state)

        fusion.submit(
            sample(
                MetricKind.SPEED,
                16.09344,
                arrivalMs = 100,
                protocol = MetricProtocol.CSC,
                sourceClass = MetricSourceClass.CSC_WHEEL,
            ),
        )
        fusion.submit(
            sample(
                MetricKind.SPEED,
                32.18688,
                arrivalMs = 101,
                protocol = MetricProtocol.CSC,
                sourceClass = MetricSourceClass.CSC_WHEEL,
            ),
        )

        assertEquals(24.14016, state.avgSpeedKph.value!!, 0.000001)
    }

    @Test
    fun `pass through projects HR energy pace and resistance`() {
        val state = BtEngineState()
        val fusion = MetricFusion(state, seriesClock = MetricSeriesClock { 1_785_000_000_000L })
        val hr = sample(
            MetricKind.HEART_RATE,
            147.0,
            200,
            protocol = MetricProtocol.HRM,
            sourceClass = MetricSourceClass.HRM,
        )

        fusion.submit(hr)
        fusion.submit(sample(MetricKind.ENERGY, 55.4, 201))
        fusion.submit(sample(MetricKind.ENERGY_RATE, 700.0, 202))
        fusion.submit(sample(MetricKind.INSTANT_PACE, 4.25, 203))
        fusion.submit(sample(MetricKind.AVERAGE_PACE, 4.5, 204))
        fusion.submit(sample(MetricKind.RESISTANCE, 8.5, 205))
        fusion.submit(sample(MetricKind.AVERAGE_SPEED, 10.5, 206))
        fusion.submit(sample(MetricKind.AVERAGE_CADENCE, 80.0, 207))
        fusion.submit(sample(MetricKind.AVERAGE_POWER, 190.0, 208))
        fusion.submit(sample(MetricKind.ELAPSED, 999.0, 209))
        fusion.updateSessionElapsed(600, arrivalMs = 210)

        assertEquals(147, state.heartRate.value)
        assertEquals(147, state.externalHeartRate.value)
        assertEquals(BtEngineState.HrSource.EXTERNAL_HRM, state.heartRateSource.value)
        assertEquals(200L, state.lastExternalAt)
        assertEquals(1_785_000_000_000L, state.series(MetricSeriesKind.HEART_RATE).single().tMillis)
        assertEquals(55, state.expEnergyTotal.value)
        assertEquals(700, state.expEnergyRateKcalPerHour.value)
        assertEquals(4.25, state.instantPaceMinPerKm.value!!, 0.0)
        assertEquals(4.5, state.averagePaceMinPerKm.value!!, 0.0)
        assertEquals(8.5, state.resistance.value!!, 0.0)
        assertEquals(10.5, state.avgSpeedKph.value!!, 0.0)
        assertEquals(80.0, state.averageCadence.value!!, 0.0)
        assertEquals(190, state.averagePower.value)
        // The machine's own elapsed time outranks the session clock while it stays fresh.
        assertEquals(999, state.elapsedTimeSec.value)
        assertEquals(MetricProtocol.FTMS, fusion.latestSample(MetricKind.ELAPSED)?.protocol)
        assertSame(hr, fusion.latestSample(MetricKind.HEART_RATE))
    }

    @Test
    fun `older arrival cannot overwrite newer pass through sample`() {
        val state = BtEngineState()
        val fusion = MetricFusion(state)

        fusion.submit(sample(MetricKind.POWER, 250.0, 500))
        val result = fusion.submit(sample(MetricKind.POWER, 100.0, 499))

        assertEquals(FusionSubmission.REJECTED_STALE, result)
        assertEquals(250, state.instantPower.value)
        assertEquals(1, state.series(MetricSeriesKind.POWER_WATTS).size)
    }
}
