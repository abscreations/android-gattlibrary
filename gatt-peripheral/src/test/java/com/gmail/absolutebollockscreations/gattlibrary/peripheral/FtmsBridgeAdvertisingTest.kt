package com.gmail.absolutebollockscreations.gattlibrary.peripheral

import com.gmail.absolutebollockscreations.gattlibrary.central.DeviceCapabilities
import com.gmail.absolutebollockscreations.gattlibrary.core.ActivityType
import com.gmail.absolutebollockscreations.gattlibrary.core.ftms.FtmsMachineProfile
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Unit 4b step 4a-3: the bridge's persona, stop and reset rules, driven by the same flows a
 * `FitnessGattClient` publishes. The loop itself is covered by [ProviderAdvertisingCoordinatorTest].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FtmsBridgeAdvertisingTest {

    private class FakeTarget : AdvertisingTarget {
        val events = mutableListOf<String>()
        override fun startFtms(
            profile: FtmsMachineProfile,
            capabilities: DeviceCapabilities,
            nativeCyclingSensors: Boolean,
        ): Boolean {
            events += if (nativeCyclingSensors) "start $profile +sensors" else "start $profile"
            return true
        }
        override fun stopAdvertising() { events += "stop" }
    }

    private class Inputs {
        val target = FakeTarget()
        val inferred = MutableStateFlow<ActivityType?>(null)
        val connected = MutableStateFlow(true)
        val session = MutableStateFlow(false)
        val caps = MutableStateFlow(DeviceCapabilities(featuresRead = true))
    }

    private fun TestScope.bridge(inputs: Inputs, nativeCyclingSensors: Boolean = false) = FtmsBridgeAdvertising(
        scope = backgroundScope,
        target = { inputs.target },
        inferredActivity = inputs.inferred,
        machineConnected = inputs.connected,
        hasUpstreamMachine = { inputs.connected.value },
        sessionActive = { inputs.session.value },
        machineCapabilities = { inputs.caps.value },
        nowMs = { testScheduler.currentTime },
        nativeCyclingSensors = nativeCyclingSensors,
    )

    @Test
    fun `every activity maps to its persona and heart rate has none`() = runTest {
        val expected = mapOf(
            ActivityType.TREADMILL to FtmsMachineProfile.TREADMILL,
            ActivityType.ELLIPTICAL to FtmsMachineProfile.ELLIPTICAL,
            ActivityType.ROWING to FtmsMachineProfile.INDOOR_ROWER,
            ActivityType.BIKING to FtmsMachineProfile.INDOOR_BIKE,
            ActivityType.STAIRCLIMBER to FtmsMachineProfile.STAIR_CLIMBER,
            ActivityType.STAIRSTEPPER to FtmsMachineProfile.STAIR_STEPPER,
        )
        for ((activity, profile) in expected) assertEquals(activity.name, profile, activity.toFtmsProfile())
        assertNull(ActivityType.HEARTRATE.toFtmsProfile())
    }

    @Test
    fun `a classified machine starts advertising its inferred persona`() = runTest {
        val inputs = Inputs()
        bridge(inputs)
        runCurrent()
        assertEquals(emptyList<String>(), inputs.target.events)

        inputs.inferred.value = ActivityType.ROWING // a ski machine classifies as rowing
        runCurrent()
        assertEquals(listOf("start INDOOR_ROWER"), inputs.target.events)
    }

    @Test
    fun `an override made while the loop waits wins and survives a repeat classification`() = runTest {
        val inputs = Inputs().apply { caps.value = DeviceCapabilities() } // features not read yet
        val bridge = bridge(inputs)
        inputs.inferred.value = ActivityType.ROWING
        runCurrent()
        bridge.overrideActivity(ActivityType.BIKING)
        inputs.inferred.value = ActivityType.ROWING // equal value: no new request
        runCurrent()

        inputs.caps.value = DeviceCapabilities(featuresRead = true)
        advanceTimeBy(1_000); runCurrent()
        assertEquals(listOf("start INDOOR_BIKE"), inputs.target.events)
    }

    @Test
    fun `an override to a different persona restarts advertising`() = runTest {
        val inputs = Inputs()
        val bridge = bridge(inputs)
        inputs.inferred.value = ActivityType.TREADMILL
        runCurrent()

        bridge.overrideActivity(ActivityType.ELLIPTICAL)
        runCurrent()
        assertEquals(listOf("start TREADMILL", "stop", "start ELLIPTICAL"), inputs.target.events)
    }

    @Test
    fun `a heart rate override is ignored`() = runTest {
        val inputs = Inputs()
        val bridge = bridge(inputs)
        inputs.inferred.value = ActivityType.TREADMILL
        runCurrent()

        bridge.overrideActivity(ActivityType.HEARTRATE)
        runCurrent()
        assertEquals(listOf("start TREADMILL"), inputs.target.events)
    }

    @Test
    fun `a sensor-only session advertises the chosen persona at once`() = runTest {
        val inputs = Inputs().apply { connected.value = false; session.value = true }
        val bridge = bridge(inputs)
        runCurrent()
        bridge.overrideActivity(ActivityType.BIKING)
        runCurrent()
        assertEquals(listOf("start INDOOR_BIKE"), inputs.target.events)
    }

    @Test
    fun `an override with no machine and no session is only recorded`() = runTest {
        val inputs = Inputs().apply { connected.value = false }
        val bridge = bridge(inputs)
        runCurrent()
        bridge.overrideActivity(ActivityType.BIKING)
        runCurrent()
        assertEquals(emptyList<String>(), inputs.target.events)
    }

    @Test
    fun `the machine link dropping stops advertising and does not restart it by itself`() = runTest {
        val inputs = Inputs()
        bridge(inputs)
        inputs.inferred.value = ActivityType.TREADMILL
        runCurrent()

        inputs.connected.value = false
        runCurrent()
        inputs.connected.value = true // recovered link, same session
        inputs.inferred.value = ActivityType.TREADMILL
        runCurrent()
        advanceTimeBy(10_000); runCurrent()
        assertEquals(listOf("start TREADMILL", "stop"), inputs.target.events)
    }

    @Test
    fun `a machine that was never connected causes no stop`() = runTest {
        val inputs = Inputs().apply { connected.value = false }
        bridge(inputs)
        runCurrent()
        inputs.connected.value = false
        runCurrent()
        assertEquals(emptyList<String>(), inputs.target.events)
    }

    @Test
    fun `the inferred activity clearing resets so the next classification advertises again`() = runTest {
        val inputs = Inputs()
        bridge(inputs)
        inputs.inferred.value = ActivityType.TREADMILL
        runCurrent()

        inputs.connected.value = false
        inputs.inferred.value = null
        runCurrent()
        inputs.connected.value = true
        inputs.inferred.value = ActivityType.BIKING
        runCurrent()
        assertEquals(listOf("start TREADMILL", "stop", "start INDOOR_BIKE"), inputs.target.events)
    }

    @Test
    fun `close stops advertising and ignores later changes`() = runTest {
        val inputs = Inputs()
        val bridge = bridge(inputs)
        inputs.inferred.value = ActivityType.TREADMILL
        runCurrent()

        bridge.close()
        bridge.close()
        inputs.inferred.value = null
        inputs.inferred.value = ActivityType.BIKING
        inputs.connected.value = false
        runCurrent()
        assertEquals(listOf("start TREADMILL", "stop", "stop"), inputs.target.events)
    }

    @Test
    fun `native cycling sensors are off by default and on from the start when requested`() = runTest {
        val off = Inputs()
        val offBridge = bridge(off)
        off.inferred.value = ActivityType.BIKING
        runCurrent()
        assertEquals(false, offBridge.nativeCyclingSensors)
        assertEquals(listOf("start INDOOR_BIKE"), off.target.events)

        val on = Inputs()
        val onBridge = bridge(on, nativeCyclingSensors = true)
        on.inferred.value = ActivityType.BIKING
        runCurrent()
        assertEquals(true, onBridge.nativeCyclingSensors)
        assertEquals(listOf("start INDOOR_BIKE +sensors"), on.target.events)
    }

    @Test
    fun `enabling native cycling sensors while biking restarts advertising with them`() = runTest {
        val inputs = Inputs()
        val bridge = bridge(inputs)
        inputs.inferred.value = ActivityType.BIKING
        runCurrent()

        bridge.setNativeCyclingSensors(true)
        runCurrent()
        bridge.setNativeCyclingSensors(true) // unchanged: no restart
        runCurrent()
        assertEquals(listOf("start INDOOR_BIKE", "stop", "start INDOOR_BIKE +sensors"), inputs.target.events)

        bridge.setNativeCyclingSensors(false)
        runCurrent()
        assertEquals(
            listOf("start INDOOR_BIKE", "stop", "start INDOOR_BIKE +sensors", "stop", "start INDOOR_BIKE"),
            inputs.target.events,
        )
    }

    @Test
    fun `changing the setting for another persona does not restart advertising`() = runTest {
        val inputs = Inputs()
        val bridge = bridge(inputs)
        inputs.inferred.value = ActivityType.TREADMILL
        runCurrent()

        bridge.setNativeCyclingSensors(true)
        runCurrent()
        assertEquals(listOf("start TREADMILL"), inputs.target.events)
    }

    @Test
    fun `a bike persona chosen by override uses the setting too`() = runTest {
        val inputs = Inputs().apply { connected.value = false; session.value = true }
        val bridge = bridge(inputs)
        bridge.setNativeCyclingSensors(true)
        bridge.overrideActivity(ActivityType.BIKING)
        runCurrent()
        assertEquals(listOf("start INDOOR_BIKE +sensors"), inputs.target.events)
    }
}
