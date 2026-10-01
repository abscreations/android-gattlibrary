package com.gmail.absolutebollockscreations.gattlibrary.peripheral

import com.gmail.absolutebollockscreations.gattlibrary.central.ControlRange
import com.gmail.absolutebollockscreations.gattlibrary.central.DeviceCapabilities
import com.gmail.absolutebollockscreations.gattlibrary.core.ftms.FtmsMachineProfile
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit 4b step 4a-2: the provider-advertising retry loop in the peripheral over public inputs. These
 * are the central loop's cases (LIB-48), re-expressed over [DeviceCapabilities] and
 * [AdvertisingTarget]; the kill-switch and CSC/CPS cases are gone with the code they covered.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ProviderAdvertisingCoordinatorTest {

    private class FakeSink : AdvertisingTarget {
        data class Attempt(val atMs: Long, val profile: FtmsMachineProfile, val caps: DeviceCapabilities)

        val attempts = mutableListOf<Attempt>()
        val events = mutableListOf<String>()
        var now: () -> Long = { 0L }

        /** Result per attempt, in order; the last entry repeats. `null` entries throw. */
        var results: List<Boolean?> = listOf(true)
        var stopThrows = false

        override fun startFtms(profile: FtmsMachineProfile, capabilities: DeviceCapabilities): Boolean {
            attempts += Attempt(now(), profile, capabilities)
            events += "start $profile"
            val r = results[minOf(attempts.size - 1, results.size - 1)]
            return r ?: throw IllegalStateException("sink threw")
        }

        override fun stopAdvertising() {
            events += "stop"
            if (stopThrows) throw IllegalStateException("stop threw")
        }
    }

    /** Mutable inputs the coordinator reads, standing in for the engine. */
    private class Inputs {
        var sink: FakeSink? = FakeSink()
        var machine = true
        var session = false
        var caps = DeviceCapabilities.NONE
    }

    private val treadmillFeaturesOnly = DeviceCapabilities(
        featuresRead = true,
        supportsSpeedTarget = true,
        supportsInclineTarget = true,
        supportsPowerTarget = true,
    )
    private val treadmillWithRanges = treadmillFeaturesOnly.copy(
        speedRangeKph = ControlRange(1f, 20f, 0.1f),
        inclineRangePercent = ControlRange(-3f, 15f, 0.5f),
        powerRangeWatts = ControlRange(0f, 1_000f, 1f),
    )

    private fun TestScope.coordinator(inputs: Inputs): ProviderAdvertisingCoordinator {
        inputs.sink?.now = { testScheduler.currentTime }
        return ProviderAdvertisingCoordinator(
            scope = backgroundScope,
            sink = { inputs.sink },
            hasUpstreamMachine = { inputs.machine },
            sessionActive = { inputs.session },
            machineCapabilities = { inputs.caps },
            nowMs = { testScheduler.currentTime },
        )
    }

    @Test
    fun `null profile and missing sink start no loop`() = runTest {
        val inputs = Inputs().apply { caps = treadmillWithRanges }
        val c = coordinator(inputs)
        c.request(null)
        runCurrent()
        assertFalse(c.loopRunning)

        inputs.sink = null
        c.request(FtmsMachineProfile.TREADMILL)
        runCurrent()
        assertFalse(c.loopRunning)
    }

    @Test
    fun `waits for FEATURES at a one second poll`() = runTest {
        val inputs = Inputs()
        val c = coordinator(inputs)
        c.request(FtmsMachineProfile.TREADMILL)
        runCurrent()
        advanceTimeBy(2_500); runCurrent()
        assertTrue(inputs.sink!!.attempts.isEmpty())
        assertTrue(c.loopRunning)

        inputs.caps = treadmillWithRanges
        advanceTimeBy(1_000); runCurrent()
        val attempt = inputs.sink!!.attempts.single()
        assertEquals(3_000L, attempt.atMs)
        assertTrue(c.advertisingStarted)
        assertEquals(FtmsMachineProfile.TREADMILL, c.advertisedProfile)
        assertFalse(c.loopRunning)
    }

    @Test
    fun `waits for claimed ranges at a 250 ms poll and passes the settled snapshot`() = runTest {
        val inputs = Inputs().apply { caps = treadmillFeaturesOnly }
        val c = coordinator(inputs)
        c.request(FtmsMachineProfile.TREADMILL)
        runCurrent()
        advanceTimeBy(600); runCurrent()
        assertTrue(inputs.sink!!.attempts.isEmpty())

        inputs.caps = treadmillWithRanges
        advanceTimeBy(250); runCurrent()
        val attempt = inputs.sink!!.attempts.single()
        assertEquals(750L, attempt.atMs)
        assertEquals(treadmillWithRanges, attempt.caps)
    }

    @Test
    fun `bridges without ranges once the five second wait has elapsed`() = runTest {
        val inputs = Inputs().apply { caps = treadmillFeaturesOnly }
        val c = coordinator(inputs)
        c.request(FtmsMachineProfile.TREADMILL)
        runCurrent()
        advanceTimeBy(4_999); runCurrent()
        assertTrue(inputs.sink!!.attempts.isEmpty())

        advanceTimeBy(1); runCurrent()
        val attempt = inputs.sink!!.attempts.single()
        assertEquals(5_000L, attempt.atMs)
        assertEquals(treadmillFeaturesOnly, attempt.caps)
    }

    @Test
    fun `a refusing sink is retried every two seconds until it accepts`() = runTest {
        val inputs = Inputs().apply {
            caps = treadmillWithRanges
            sink!!.results = listOf(false, false, true)
        }
        val c = coordinator(inputs)
        c.request(FtmsMachineProfile.TREADMILL)
        runCurrent()
        advanceTimeBy(4_000); runCurrent()
        assertEquals(listOf(0L, 2_000L, 4_000L), inputs.sink!!.attempts.map { it.atMs })
        assertTrue(c.advertisingStarted)
        assertFalse(c.loopRunning)
    }

    @Test
    fun `a throwing sink is retried like a refusal`() = runTest {
        val inputs = Inputs().apply {
            caps = treadmillWithRanges
            sink!!.results = listOf(null, true)
        }
        val c = coordinator(inputs)
        c.request(FtmsMachineProfile.TREADMILL)
        runCurrent()
        advanceTimeBy(2_000); runCurrent()
        assertEquals(listOf(0L, 2_000L), inputs.sink!!.attempts.map { it.atMs })
        assertTrue(c.advertisingStarted)
    }

    @Test
    fun `no machine and no session ends the loop without an attempt`() = runTest {
        val inputs = Inputs().apply { machine = false; session = false; caps = treadmillWithRanges }
        val c = coordinator(inputs)
        c.request(FtmsMachineProfile.TREADMILL)
        runCurrent()
        assertTrue(inputs.sink!!.attempts.isEmpty())
        assertFalse(c.loopRunning)
        assertFalse(c.advertisingStarted)
    }

    @Test
    fun `losing the machine while waiting ends the loop`() = runTest {
        val inputs = Inputs()
        val c = coordinator(inputs)
        c.request(FtmsMachineProfile.TREADMILL)
        runCurrent()
        assertTrue(c.loopRunning)

        inputs.machine = false
        advanceTimeBy(1_000); runCurrent()
        assertFalse(c.loopRunning)
        assertTrue(inputs.sink!!.attempts.isEmpty())
    }

    @Test
    fun `a sensor-only session advertises the settled sensor-only capabilities at once`() = runTest {
        val inputs = Inputs().apply {
            machine = false
            session = true
            caps = treadmillFeaturesOnly // must be ignored
        }
        val c = coordinator(inputs)
        c.request(FtmsMachineProfile.INDOOR_BIKE)
        runCurrent()
        val attempt = inputs.sink!!.attempts.single()
        assertEquals(0L, attempt.atMs)
        assertEquals(SENSOR_ONLY_CAPABILITIES, attempt.caps)
        assertEquals(FtmsMachineProfile.INDOOR_BIKE, attempt.profile)
    }

    @Test
    fun `a second request while the loop runs only updates the persona`() = runTest {
        val inputs = Inputs()
        val c = coordinator(inputs)
        c.request(FtmsMachineProfile.TREADMILL)
        runCurrent()
        c.request(FtmsMachineProfile.INDOOR_BIKE)
        runCurrent()
        assertTrue(inputs.sink!!.attempts.isEmpty())

        inputs.caps = treadmillWithRanges
        advanceTimeBy(1_000); runCurrent()
        assertEquals(FtmsMachineProfile.INDOOR_BIKE, inputs.sink!!.attempts.single().profile)
    }

    @Test
    fun `a request after advertising started does nothing`() = runTest {
        val inputs = Inputs().apply { caps = treadmillWithRanges }
        val c = coordinator(inputs)
        c.request(FtmsMachineProfile.TREADMILL)
        runCurrent()
        c.request(FtmsMachineProfile.INDOOR_BIKE)
        runCurrent()
        assertEquals(1, inputs.sink!!.attempts.size)
        assertEquals(FtmsMachineProfile.TREADMILL, c.advertisedProfile)
    }

    @Test
    fun `losing the sink aborts the loop`() = runTest {
        val inputs = Inputs()
        val c = coordinator(inputs)
        val sink = inputs.sink!!
        c.request(FtmsMachineProfile.TREADMILL)
        runCurrent()
        assertTrue(c.loopRunning)

        inputs.sink = null
        advanceTimeBy(1_000); runCurrent()
        assertFalse(c.loopRunning)
        assertTrue(sink.attempts.isEmpty())
    }

    @Test
    fun `reset clears advertising state`() = runTest {
        val inputs = Inputs().apply { caps = treadmillWithRanges }
        val c = coordinator(inputs)
        c.request(FtmsMachineProfile.TREADMILL)
        runCurrent()
        assertTrue(c.advertisingStarted)

        c.reset()
        assertFalse(c.advertisingStarted)
        assertNull(c.advertisedProfile)
        assertFalse(c.loopRunning)

        c.request(FtmsMachineProfile.TREADMILL)
        runCurrent()
        assertEquals(2, inputs.sink!!.attempts.size)
    }

    @Test
    fun `stop calls the sink, swallows a failure, and is a no-op without a sink`() = runTest {
        val inputs = Inputs().apply { sink!!.stopThrows = true }
        val c = coordinator(inputs)
        c.stop("test")
        assertEquals(listOf("stop"), inputs.sink!!.events)

        inputs.sink = null
        c.stop("no sink") // must not throw
    }

    @Test
    fun `persona override with no machine and no session is only recorded`() = runTest {
        val inputs = Inputs().apply { machine = false; session = false; caps = treadmillWithRanges }
        val c = coordinator(inputs)
        c.overridePersona(FtmsMachineProfile.INDOOR_ROWER)
        runCurrent()
        assertTrue(inputs.sink!!.attempts.isEmpty())
        assertFalse(c.loopRunning)
    }

    @Test
    fun `persona override before advertising starts a loop for that persona`() = runTest {
        val inputs = Inputs().apply { caps = treadmillWithRanges }
        val c = coordinator(inputs)
        c.overridePersona(FtmsMachineProfile.INDOOR_ROWER)
        runCurrent()
        assertEquals(FtmsMachineProfile.INDOOR_ROWER, inputs.sink!!.attempts.single().profile)
    }

    @Test
    fun `persona override to the advertised persona does nothing`() = runTest {
        val inputs = Inputs().apply { caps = treadmillWithRanges }
        val c = coordinator(inputs)
        c.request(FtmsMachineProfile.TREADMILL)
        runCurrent()
        c.overridePersona(FtmsMachineProfile.TREADMILL)
        runCurrent()
        assertEquals(listOf("start TREADMILL"), inputs.sink!!.events)
    }

    @Test
    fun `persona override to a different persona stops and restarts`() = runTest {
        val inputs = Inputs().apply { caps = treadmillWithRanges }
        val c = coordinator(inputs)
        c.request(FtmsMachineProfile.TREADMILL)
        runCurrent()

        c.overridePersona(FtmsMachineProfile.INDOOR_BIKE)
        runCurrent()
        assertEquals(
            listOf("start TREADMILL", "stop", "start INDOOR_BIKE"),
            inputs.sink!!.events,
        )
        assertEquals(FtmsMachineProfile.INDOOR_BIKE, c.advertisedProfile)
    }
}
