package com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine

import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.capture.BleCapture
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.capture.CaptureTrigger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/** Phase 5b §11b: every bounded Chrono field crosses decode, emission and fusion. */
class PrecorPlausibilityBoundsTest {

    private val source = MetricSourceId(
        ConnectionId("C0:00:00:00:00:01", 1),
        MetricProtocol.VENDOR,
        BluetoothIds.UUID_PRECOR_MEASUREMENT,
    )

    @Before
    fun resetCaptureBeforeTest() = BleCapture.resetSession()

    @After
    fun resetCaptureAfterTest() = BleCapture.resetSession()

    @Test
    fun `decoded Chrono speed outside the ride bound is dropped and captured`() {
        assertDecodedMetricIsRejected(
            metric = MetricKind.SPEED,
            frame = chronoFrame(speedHundredthsKph = 0xFFFF),
        )
    }

    @Test
    fun `decoded Chrono cadence outside the ride bound is dropped and captured`() {
        assertDecodedMetricIsRejected(
            metric = MetricKind.CADENCE,
            frame = chronoFrame(cadenceTenthsRpm = 0xFFFF),
        )
    }

    @Test
    fun `decoded Chrono power outside the shared bound is dropped and captured`() {
        assertDecodedMetricIsRejected(
            metric = MetricKind.POWER,
            frame = chronoFrame(powerWatts = 0xFFFF),
        )
    }

    @Test
    fun `decoded Chrono reserved heart-rate value is dropped and captured`() {
        assertDecodedMetricIsRejected(
            metric = MetricKind.HEART_RATE,
            frame = chronoFrame(heartRate = 0xFF),
        )
    }

    @Test
    fun `decoded Chrono cumulative fields remain deliberately unbounded`() {
        val reading = requireNotNull(
            PrecorMeasurement.parse(
                chronoFrame(distanceMetres = 0xFF_FFFF, calories = 0xFFFF),
            ),
        )
        val byMetric = precorSamples(reading).associateBy { it.metric }
        val state = BtEngineState()
        val fusion = MetricFusion(state).also { it.setPersona(FusionPersona.RIDE, 0) }
        val emitter = MetricEmitter(fusion, source, MetricSourceClass.VENDOR_NATIVE, 1_000)

        for (metric in listOf(MetricKind.DISTANCE, MetricKind.ENERGY)) {
            assertNull(
                "$metric must remain unbounded because its ceiling is session-length dependent",
                PlausibilityBounds.rangeFor(metric, FusionPersona.RIDE),
            )
            val sample = byMetric.getValue(metric)
            assertEquals(
                FusionSubmission.ACCEPTED,
                emitter.emit(
                    sample.metric,
                    sample.value,
                    eventMs = sample.eventMs,
                    nativeCumulative = sample.nativeCumulative,
                ),
            )
            assertEquals(source, fusion.selectedSource(metric))
        }
        assertNull("accepted cumulative values must not arm anomaly capture", BleCapture.armedBy())
    }

    private fun assertDecodedMetricIsRejected(metric: MetricKind, frame: ByteArray) {
        val reading = requireNotNull(PrecorMeasurement.parse(frame))
        val sample = precorSamples(reading).single { it.metric == metric }
        val fusion = MetricFusion(BtEngineState()).also { it.setPersona(FusionPersona.RIDE, 0) }
        val emitter = MetricEmitter(fusion, source, MetricSourceClass.VENDOR_NATIVE, 1_000)

        val outcome = emitter.emit(
            sample.metric,
            sample.value,
            eventMs = sample.eventMs,
            nativeCumulative = sample.nativeCumulative,
        )

        assertEquals(FusionSubmission.REJECTED_IMPLAUSIBLE, outcome)
        assertNull("a rejected $metric value must not be published", fusion.selectedSource(metric))
        assertEquals(CaptureTrigger.PARSER_REJECTION, BleCapture.armedBy())
    }

    private fun chronoFrame(
        heartRate: Int = 142,
        speedHundredthsKph: Int = 2_283,
        cadenceTenthsRpm: Int = 940,
        distanceMetres: Int = 1_000,
        calories: Int = 50,
        powerWatts: Int = 180,
    ): ByteArray = ByteArray(PrecorMeasurement.FRAME_LENGTH).also { frame ->
        frame[0] = 83
        frame[1] = 89
        frame[2] = 22
        putU16(frame, 3, 1)
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
