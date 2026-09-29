package com.gmail.absolutebollockscreations.gattlibrary.central

import com.gmail.absolutebollockscreations.gattlibrary.central.capture.BleCapture
import com.gmail.absolutebollockscreations.gattlibrary.central.capture.CaptureTrigger
import com.gmail.absolutebollockscreations.gattlibrary.peripheral.FtmsSpec
import com.gmail.absolutebollockscreations.gattlibrary.peripheral.TargetCapabilities
import com.gmail.absolutebollockscreations.gattlibrary.peripheral.buildFtmsFeaturePayload
import com.gmail.absolutebollockscreations.gattlibrary.peripheral.providerMeasurementCapabilities
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import timber.log.Timber

/** Phase 9 §11.7, §11.8 and capture evidence at the production policy seams. */
class FeCDownstreamControlCaptureTest {

    private class RecordingTree : Timber.Tree() {
        val lines = mutableListOf<String>()
        override fun isLoggable(tag: String?, priority: Int): Boolean = tag == BleCapture.TAG
        override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
            if (tag == BleCapture.TAG) lines += message
        }
    }

    private lateinit var tree: RecordingTree

    @Before
    fun setUp() {
        tree = RecordingTree()
        Timber.plant(tree)
        BleCapture.resetSession()
    }

    @After
    fun tearDown() {
        Timber.uproot(tree)
        BleCapture.resetSession()
    }

    @Test
    fun `selected adapter routes control to FE-C only when its handler is active`() {
        assertEquals(MachineControlRoute.FTMS, machineControlRoute(null, false))
        assertEquals(
            MachineControlRoute.FTMS,
            machineControlRoute(AdapterLivenessCoordinator.FTMS_ADAPTER_ID, true),
        )
        assertEquals(
            MachineControlRoute.FTMS,
            machineControlRoute(AdapterLivenessCoordinator.FEC_ADAPTER_ID, false),
        )
        assertEquals(
            MachineControlRoute.FEC,
            machineControlRoute(AdapterLivenessCoordinator.FEC_ADAPTER_ID, true),
        )
        assertEquals(
            "a selected Wahoo adapter remains closed until its endpoint unit lands",
            MachineControlRoute.UNAVAILABLE,
            machineControlRoute(AdapterLivenessCoordinator.WAHOO_KICKR_ADAPTER_ID, false),
        )
    }

    @Test
    fun `page 54 mode bits become exactly the downstream FE-C target bits and never speed`() {
        val state = BtEngineState()
        FeCCapabilities.publish(
            state,
            controlAvailable = true,
            page = FeCPage.FeCapabilities(800, true, true, true),
        )
        val targets = TargetCapabilities.fromEngineCapabilities(state.ftmsCapabilities)
        val expected =
            (1 shl FtmsSpec.TargetFeature.INCLINATION) or
                (1 shl FtmsSpec.TargetFeature.RESISTANCE) or
                (1 shl FtmsSpec.TargetFeature.POWER) or
                (1 shl FtmsSpec.TargetFeature.INDOOR_BIKE_SIMULATION)

        assertEquals(expected, targets.toTargetFeatureBits())
        assertFalse(targets.supportsSpeed)
        assertTrue(targets.supportsInclination)
        assertTrue(targets.supportsResistance)
        assertTrue(targets.supportsPower)
        assertTrue(targets.indoorBikeSimulation)
    }

    @Test
    fun `measurement-only and timed-out capability discovery publish zero target bits`() {
        for (controlAvailable in listOf(false, true)) {
            val state = BtEngineState()
            FeCCapabilities.publish(
                state,
                controlAvailable,
                if (controlAvailable) FeCPage.FeCapabilities(null, false, false, false) else null,
            )
            assertEquals(
                0,
                TargetCapabilities.fromEngineCapabilities(state.ftmsCapabilities).toTargetFeatureBits(),
            )
        }
    }

    @Test
    fun `FE-C treadmill downstream Feature word does not claim force-on-belt power`() {
        val profile = BtServiceProfiles.FtmsMachineProfile.TREADMILL
        val capabilities = providerMeasurementCapabilities(profile, setOf(BluetoothIds.UUID_FEC_SERVICE))
        val payload = buildFtmsFeaturePayload(profile, TargetCapabilities.NONE, measurementCapabilities = capabilities)
        val measurementBits = u32(payload, 0)

        assertEquals(0L, measurementBits and (1L shl FtmsSpec.Feature.FORCE_ON_BELT_AND_POWER))
        for (bit in listOf(
            FtmsSpec.Feature.AVERAGE_SPEED,
            FtmsSpec.Feature.TOTAL_DISTANCE,
            FtmsSpec.Feature.INCLINATION,
            FtmsSpec.Feature.ELEVATION_GAIN,
            FtmsSpec.Feature.EXPENDED_ENERGY,
            FtmsSpec.Feature.HEART_RATE,
            FtmsSpec.Feature.ELAPSED_TIME,
        )) {
            assertTrue("FE-C treadmill measurement bit $bit", measurementBits and (1L shl bit) != 0L)
        }
    }

    @Test
    fun `an FTMS plus FE-C treadmill keeps the standard FTMS measurement contract`() {
        val profile = BtServiceProfiles.FtmsMachineProfile.TREADMILL
        assertTrue(
            providerMeasurementCapabilities(
                profile,
                setOf(BluetoothIds.UUID_FEC_SERVICE, BluetoothIds.UUID_FTMS_SERVICE),
            ).forceOnBeltAndPowerOutput,
        )
    }

    @Test
    fun `FE-C notify sample no-response write and parser rejection are capture-visible`() {
        val connection = ConnectionId("FE:C0:00:00:00:33", 7)
        val validPage = byteArrayOf(0x10, 25, 1, 2, 0x88.toByte(), 0x13, 140.toByte(), 0x34)
        val validFrame = FeCFrameCodec.encode(0x4E, 5, validPage)
        BleCapture.arm(CaptureTrigger.MANUAL)
        CaptureHooks.notified(connection, BluetoothIds.UUID_FEC_MEASUREMENT, validFrame)
        MetricEmitter(
            MetricFusion(BtEngineState()).also { it.setPersona(FusionPersona.RIDE, 0) },
            MetricSourceId(connection, MetricProtocol.VENDOR, BluetoothIds.UUID_FEC_MEASUREMENT),
            MetricSourceClass.VENDOR_NATIVE,
            1_000,
        ).emit(MetricKind.POWER, 200)
        CaptureHooks.wrote(connection, BluetoothIds.UUID_FEC_CONTROL, byteArrayOf(0x31), true)

        assertTrue(tree.lines.any { it.contains("\"ev\":\"notify\"") && it.contains(BluetoothIds.UUID_FEC_MEASUREMENT.toString()) })
        assertTrue(tree.lines.any { it.contains("\"ev\":\"sample\"") && it.contains("POWER") })
        assertTrue(tree.lines.any { it.contains("\"ev\":\"write\"") && it.contains("\"withoutResponse\":true") })

        BleCapture.resetSession()
        val session = FeCProtocolSession(connection)
        val corrupt = validFrame.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        session.onPacket(BluetoothIds.UUID_FEC_MEASUREMENT, corrupt, 2_000)
        assertEquals(CaptureTrigger.PARSER_REJECTION, BleCapture.armedBy())
    }

    private fun u32(value: ByteArray, offset: Int): Long =
        (value[offset].toLong() and 0xFF) or
            ((value[offset + 1].toLong() and 0xFF) shl 8) or
            ((value[offset + 2].toLong() and 0xFF) shl 16) or
            ((value[offset + 3].toLong() and 0xFF) shl 24)
}
