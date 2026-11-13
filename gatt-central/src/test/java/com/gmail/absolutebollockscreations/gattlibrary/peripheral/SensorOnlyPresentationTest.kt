package com.gmail.absolutebollockscreations.gattlibrary.peripheral

import com.gmail.absolutebollockscreations.gattlibrary.central.toMeasurementSnapshot
import com.gmail.absolutebollockscreations.gattlibrary.central.BtEngineState
import com.gmail.absolutebollockscreations.gattlibrary.central.ConnectionId
import com.gmail.absolutebollockscreations.gattlibrary.central.FusionPersona
import com.gmail.absolutebollockscreations.gattlibrary.central.MetricEmitter
import com.gmail.absolutebollockscreations.gattlibrary.central.MetricFusion
import com.gmail.absolutebollockscreations.gattlibrary.central.MetricProtocol
import com.gmail.absolutebollockscreations.gattlibrary.central.MetricSourceClass
import com.gmail.absolutebollockscreations.gattlibrary.central.MetricSourceId
import com.gmail.absolutebollockscreations.gattlibrary.central.RscMeasurement
import com.gmail.absolutebollockscreations.gattlibrary.central.emitRscMetrics
import com.gmail.absolutebollockscreations.gattlibrary.central.sensorOnlyProviderCapabilities
import com.gmail.absolutebollockscreations.gattlibrary.core.ftms.FtmsMachineProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.gmail.absolutebollockscreations.gattlibrary.core.ftms.FtmsSpec
import com.gmail.absolutebollockscreations.gattlibrary.core.ids.SigUuids
import com.gmail.absolutebollockscreations.gattlibrary.server.FtmsDataEncoders
import com.gmail.absolutebollockscreations.gattlibrary.server.FtmsMetricStore
import com.gmail.absolutebollockscreations.gattlibrary.server.TargetCapabilities
import com.gmail.absolutebollockscreations.gattlibrary.server.buildFtmsFeaturePayload

/**
 * Phase 1's downstream criterion: *"an RSC-only session presents as a downstream FTMS Treadmill
 * with speed and distance, and with **no** target-setting capability bits set."* RSC cadence is
 * internal-only because FTMS Treadmill Data has no cadence field (plan D27).
 *
 * ### Why the target bits are the part that matters
 *
 * Speed and distance are just data; getting them wrong shows up immediately. The
 * capability bits are a **promise**. A watch reading a speed-target bit is being told it can
 * ask FitBridge to change the belt speed — and in a sensor-only session there is no belt. The
 * user would set a target, see it accepted, and nothing would happen.
 *
 * §9.2 and §6c both come down to the same rule, from opposite directions: advertise a control
 * capability only where a machine exists that can honour it.
 */
class SensorOnlyPresentationTest {

    private val sensorOnly = sensorOnlyProviderCapabilities()

    @Test
    fun `RSC samples reach downstream Feature and Treadmill Data without cadence or controls`() {
        val state = BtEngineState()
        val fusion = MetricFusion(state)
        fusion.startSession(FusionPersona.RUN, nowMs = 100)
        val source = MetricSourceId(
            ConnectionId("RUNPOD", 1),
            MetricProtocol.RSC,
            SigUuids.UUID_RSC_MEASUREMENT,
        )

        emitRscMetrics(
            RscMeasurement.parse(hex("032601387b0024000000"))!!,
            MetricEmitter(fusion, source, MetricSourceClass.RSC, arrivalMs = 101),
        )
        emitRscMetrics(
            RscMeasurement.parse(hex("03260135830042000000"))!!,
            MetricEmitter(fusion, source, MetricSourceClass.RSC, arrivalMs = 102),
        )

        val store = FtmsMetricStore()
        EngineMetricPump({ state.toMeasurementSnapshot() }, store) { FtmsMachineProfile.TREADMILL }.sampleOnce()
        val snapshot = store.snapshot
        assertEquals(4.134375, snapshot.speedKph ?: 0.0, 1e-9)
        assertEquals(3, snapshot.distanceMeters)
        assertEquals("cadence remains available to fusion only", 53.0, snapshot.cadence ?: 0.0, 0.0)

        val treadmillData = FtmsDataEncoders.encode(
            FtmsMachineProfile.TREADMILL,
            snapshot,
            snapshot.speedKph,
        ).single()
        val dataFlags = u16(treadmillData, 0)
        assertTrue("Total Distance must be present", dataFlags and (1 shl 2) != 0)
        assertEquals(3, u24(treadmillData, optionalOffset(dataFlags, bit = 2)))

        val targets = TargetCapabilities.fromRawFixture(sensorOnly)
        val feature = buildFtmsFeaturePayload(FtmsMachineProfile.TREADMILL, targets)
        assertEquals(8, feature.size)
        assertEquals(0, u32(feature, 4))
        assertFalse(
            "FTMS Treadmill must not claim its internal RSC cadence",
            u32(feature, 0) and (1 shl FtmsSpec.Feature.CADENCE) != 0,
        )
    }

    @Test
    fun `a sensor-only session advertises no target-setting capability at all`() {
        val targets = TargetCapabilities.fromRawFixture(sensorOnly)

        assertEquals(
            "a watch must not be offered control of a machine that does not exist",
            0,
            targets.toTargetFeatureBits(),
        )
        assertFalse(targets.any)
        assertFalse(targets.speed)
        assertFalse(targets.inclination)
        assertFalse(targets.resistance)
        assertFalse(targets.power)
    }

    @Test
    fun `and no supported range is published either`() {
        // A range without a capability bit is not merely redundant - the Supported-* reads
        // would answer with numbers describing nothing.
        val targets = TargetCapabilities.fromRawFixture(sensorOnly)

        assertNull(targets.speedRange)
        assertNull(targets.inclinationRange)
        assertNull(targets.resistanceRange)
        assertNull(targets.powerRange)
    }

    @Test
    fun `a real machine still advertises its own targets`() {
        // The counterpart: this must not become "never advertise targets". These are the
        // ranges the TMIW treadmill actually reported on 2026-08-07 - 0x2AD4 speed 0..3000
        // step 10, 0x2AD5 incline -100..300 step 5 - in raw spec units.
        val machine = BtEngineState.FtmsCapabilities(
            featuresRead = true,
            supportsSpeed = true,
            supportsInclination = true,
            speedMin = 0f, speedMax = 3000f, speedIncr = 10f, speedRangeKnown = true,
            inclineMin = -100f, inclineMax = 300f, inclineIncr = 5f, inclineRangeKnown = true,
        )

        val targets = TargetCapabilities.fromRawFixture(machine)

        assertTrue(targets.speed)
        assertTrue(targets.inclination)
        assertTrue(targets.toTargetFeatureBits() != 0)
        assertEquals(0, targets.speedRange?.minimum)
        assertEquals(3000, targets.speedRange?.maximum)
        assertEquals(-100, targets.inclinationRange?.minimum)
        assertEquals(300, targets.inclinationRange?.maximum)
    }

    @Test
    fun `capabilities with no machine read are not mistaken for settled ones`() {
        // The default FtmsCapabilities - nothing read yet - must stay distinguishable from the
        // sensor-only value. They differ only in featuresRead, and that flag is what stops the
        // advertising loop waiting forever for a machine that is not coming.
        val nothingRead = BtEngineState.FtmsCapabilities()

        assertFalse(nothingRead.featuresRead)
        assertTrue(sensorOnly.featuresRead)
        assertEquals(
            "both advertise nothing; only their readiness differs",
            TargetCapabilities.fromRawFixture(nothingRead).toTargetFeatureBits(),
            TargetCapabilities.fromRawFixture(sensorOnly).toTargetFeatureBits(),
        )
    }

    private fun optionalOffset(flags: Int, bit: Int): Int {
        var offset = 4 // two flag octets + mandatory instantaneous speed
        if (bit > 1 && flags and (1 shl 1) != 0) offset += 2
        return offset
    }

    private fun u16(value: ByteArray, offset: Int): Int =
        (value[offset].toInt() and 0xFF) or ((value[offset + 1].toInt() and 0xFF) shl 8)

    private fun u24(value: ByteArray, offset: Int): Int =
        (value[offset].toInt() and 0xFF) or
            ((value[offset + 1].toInt() and 0xFF) shl 8) or
            ((value[offset + 2].toInt() and 0xFF) shl 16)

    private fun u32(value: ByteArray, offset: Int): Int =
        u16(value, offset) or (u16(value, offset + 2) shl 16)

    private fun hex(value: String): ByteArray =
        value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
