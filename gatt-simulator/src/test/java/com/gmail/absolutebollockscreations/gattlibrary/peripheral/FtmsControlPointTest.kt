package com.gmail.absolutebollockscreations.gattlibrary.peripheral

import com.gmail.absolutebollockscreations.gattlibrary.core.ftms.FtmsMachineProfile
import com.gmail.absolutebollockscreations.gattlibrary.simulator.FtmsMockControlProfile

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.gmail.absolutebollockscreations.gattlibrary.core.bytes.LittleEndian.u16Wrapping
import com.gmail.absolutebollockscreations.gattlibrary.core.bytes.LittleEndian.s16Wrapping
import com.gmail.absolutebollockscreations.gattlibrary.core.ftms.FtmsSpec
import com.gmail.absolutebollockscreations.gattlibrary.server.FtmsControlPointHandler
import com.gmail.absolutebollockscreations.gattlibrary.server.FtmsControlSink
import com.gmail.absolutebollockscreations.gattlibrary.server.FtmsProcedureSession
import com.gmail.absolutebollockscreations.gattlibrary.server.FtmsTargetRange
import com.gmail.absolutebollockscreations.gattlibrary.server.InertFtmsControlSink
import com.gmail.absolutebollockscreations.gattlibrary.server.SwitchableFtmsControlSink
import com.gmail.absolutebollockscreations.gattlibrary.server.TargetCapabilities

/**
 * Control Point tests.
 *
 * The v1 sink is inert, so none of this is reachable at runtime yet. Testing it
 * anyway is the point: FitBridge's Set Target Resistance parser was wrong for
 * as long as it existed precisely because nothing exercised it. When two-way
 * control lands, these tests are what stop the same bug shipping.
 */
class FtmsControlPointTest {

    /** Records what the Control Point asked for, and answers yes. */
    private class RecordingSink(
        override val canControl: Boolean = true,
        override val targetCapabilities: TargetCapabilities = TargetCapabilities(
            speed = true,
            inclination = true,
            resistance = true,
            power = true,
            heartRate = true,
            indoorBikeSimulation = true,
            speedRange = FtmsTargetRange(0, 10_000, 1),
            inclinationRange = FtmsTargetRange(-1_000, 1_000, 1),
            resistanceRange = FtmsTargetRange(-1_000, 1_000, 1),
            powerRange = FtmsTargetRange(-2_000, 2_000, 1),
            heartRateRange = FtmsTargetRange(1, 254, 1),
        ),
        private val succeed: Boolean = true,
        override val reportsTargetChangesAsynchronously: Boolean = false,
        override val clampsTargetsToAdvertisedRange: Boolean = false,
    ) : FtmsControlSink {
        var speed: Double? = null
        var inclination: Double? = null
        var resistance: Double? = null
        var power: Int? = null
        var heartRate: Int? = null
        var sim: List<Double>? = null
        var stopped: Boolean? = null
        var started = false
        var reset = false
        var wheelCircumferenceMm: Double? = null

        override fun requestControl() = true
        override fun reset(): Boolean { reset = true; return succeed }
        override fun startOrResume(): Boolean { started = true; return succeed }
        override fun stopOrPause(stop: Boolean): Boolean { stopped = stop; return succeed }
        override fun setTargetSpeedKph(kph: Double): Boolean { speed = kph; return succeed }
        override fun setTargetInclinationPercent(percent: Double): Boolean { inclination = percent; return succeed }
        override fun setTargetResistance(level: Double): Boolean { resistance = level; return succeed }
        override fun setTargetPowerWatts(watts: Int): Boolean { power = watts; return succeed }
        override fun setTargetHeartRate(bpm: Int): Boolean { heartRate = bpm; return succeed }
        override fun setWheelCircumferenceMm(millimetres: Double): Boolean {
            wheelCircumferenceMm = millimetres
            return succeed
        }
        override fun setIndoorBikeSimulation(
            windMps: Double, gradePercent: Double, crr: Double, cw: Double,
        ): Boolean { sim = listOf(windMps, gradePercent, crr, cw); return succeed }
    }

    private val R = FtmsSpec.Result
    private val O = FtmsSpec.Opcode

    private fun result(r: FtmsControlPointHandler.Outcome) = r.response[2]

    /** Compatibility helper for parsing-focused cases that do not exercise transitions. */
    private fun FtmsControlPointHandler.handle(value: ByteArray, hasControl: Boolean): FtmsControlPointHandler.Outcome {
        val session = FtmsProcedureSession()
        session.hasControl = hasControl
        return handle(value, session)
    }

    // ── framing ──────────────────────────────────────────────────────────────

    @Test
    fun `response is always 0x80, request opcode, result`() {
        val cp = FtmsControlPointHandler(InertFtmsControlSink)
        val out = cp.handle(byteArrayOf(O.REQUEST_CONTROL), hasControl = false)
        assertArrayEquals(byteArrayOf(0x80.toByte(), O.REQUEST_CONTROL, R.SUCCESS), out.response)
    }

    @Test
    fun `empty write is rejected rather than crashing`() {
        val cp = FtmsControlPointHandler(InertFtmsControlSink)
        val out = cp.handle(ByteArray(0), hasControl = true)
        assertEquals(R.OPCODE_NOT_SUPPORTED, result(out))
    }

    // ── control ownership ────────────────────────────────────────────────────

    @Test
    fun `request control grants the slot`() {
        val cp = FtmsControlPointHandler(RecordingSink())
        val session = FtmsProcedureSession()
        val out = cp.handle(byteArrayOf(O.REQUEST_CONTROL), session)
        assertEquals(R.SUCCESS, result(out))
        assertTrue(session.hasControl)
    }

    @Test
    fun `every other opcode is refused without control`() {
        val cp = FtmsControlPointHandler(RecordingSink())
        val opcodes = listOf(
            O.RESET, O.START_RESUME, O.SET_TARGET_SPEED,
            O.SET_TARGET_INCLINATION, O.SET_TARGET_RESISTANCE, O.SET_TARGET_POWER,
        )
        for (op in opcodes) {
            val out = cp.handle(byteArrayOf(op, 0, 0), hasControl = false)
            assertEquals("opcode 0x%02X".format(op), R.CONTROL_NOT_PERMITTED, result(out))
        }
    }

    @Test
    fun `reset clears operation state and retains the control slot`() {
        val sink = RecordingSink()
        val cp = FtmsControlPointHandler(sink)
        val session = FtmsProcedureSession().apply {
            hasControl = true
            operationalState = FtmsProcedureSession.OperationalState.RUNNING
            acceptedTargets.powerWatts = 200
        }
        val out = cp.handle(byteArrayOf(O.RESET), session)
        assertEquals(R.SUCCESS, result(out))
        assertTrue(session.hasControl)
        assertEquals(FtmsProcedureSession.OperationalState.IDLE, session.operationalState)
        assertNull(session.acceptedTargets.powerWatts)
        assertTrue(sink.reset)
    }

    // ── parameter layouts ────────────────────────────────────────────────────

    @Test
    fun `treadmill speed opt in hides capability and refuses live speed writes`() {
        val upstream = RecordingSink()
        var speedAllowed = false
        val guarded = SwitchableFtmsControlSink(upstream) { speedAllowed }
        val cp = FtmsControlPointHandler(guarded)
        val session = FtmsProcedureSession().apply { hasControl = true }
        val speedRequest = byteArrayOf(O.SET_TARGET_SPEED, 0xE8.toByte(), 0x03)

        assertFalse(guarded.targetCapabilities.supportsSpeed)
        assertEquals(0, guarded.targetCapabilities.toTargetFeatureBits() and 1)
        assertTrue(guarded.targetCapabilities.supportsInclination)
        assertEquals(R.OPCODE_NOT_SUPPORTED, result(cp.handle(speedRequest, session)))
        assertNull(upstream.speed)

        speedAllowed = true
        assertTrue(guarded.targetCapabilities.supportsSpeed)
        assertEquals(R.SUCCESS, result(cp.handle(speedRequest, session)))
        assertEquals(10.0, upstream.speed!!, 0.001)

        speedAllowed = false
        upstream.speed = null
        assertEquals(R.OPCODE_NOT_SUPPORTED, result(cp.handle(speedRequest, session)))
        assertFalse(guarded.setTargetSpeedKph(10.0))
        assertNull(upstream.speed)
    }

    @Test
    fun `treadmill speed opt in does not gate a local override such as the simulator`() {
        // LIB-9d: the opt-in guards pass-through to a real treadmill. The simulator's override
        // moves nothing, so with its own speed capability it keeps speed even when the opt-in
        // is off; removing the override restores the gate for the real machine.
        val upstream = RecordingSink()
        val simulator = RecordingSink()
        val guarded = SwitchableFtmsControlSink(upstream) { false }
        val cp = FtmsControlPointHandler(guarded)
        val session = FtmsProcedureSession().apply { hasControl = true }
        val speedRequest = byteArrayOf(O.SET_TARGET_SPEED, 0xE8.toByte(), 0x03)

        guarded.override = simulator
        assertTrue(guarded.targetCapabilities.supportsSpeed)
        assertEquals(R.SUCCESS, result(cp.handle(speedRequest, session)))
        assertEquals(10.0, simulator.speed!!, 0.001)
        assertNull(upstream.speed)

        guarded.override = null
        assertFalse(guarded.targetCapabilities.supportsSpeed)
        assertEquals(R.OPCODE_NOT_SUPPORTED, result(cp.handle(speedRequest, session)))
        assertNull(upstream.speed)
    }

    @Test
    fun `set target resistance takes a uint8, not a sint16`() {
        // FTMS 4.16: the Set Target Resistance Level parameter is a single
        // unsigned byte in 0.1 units, so a conformant write is exactly 2 octets.
        // FitBridge required 3 and decoded sint16, rejecting every conformant
        // client with INVALID_PARAMETER.
        val sink = RecordingSink()
        val cp = FtmsControlPointHandler(sink)
        val out = cp.handle(byteArrayOf(O.SET_TARGET_RESISTANCE, 75), hasControl = true)
        assertEquals(R.SUCCESS, result(out))
        assertEquals(7.5, sink.resistance!!, 0.001)
    }

    @Test
    fun `local target sink publishes accepted resistance status immediately`() {
        val cp = FtmsControlPointHandler(RecordingSink())
        val out = cp.handle(byteArrayOf(O.SET_TARGET_RESISTANCE, 75), hasControl = true)

        assertArrayEquals(
            byteArrayOf(FtmsSpec.Status.TARGET_RESISTANCE_CHANGED, 75),
            out.status,
        )
    }

    @Test
    fun `machine backed target sink waits for authoritative resistance status`() {
        val cp = FtmsControlPointHandler(
            RecordingSink(reportsTargetChangesAsynchronously = true),
        )
        val out = cp.handle(byteArrayOf(O.SET_TARGET_RESISTANCE, 75), hasControl = true)

        assertEquals(R.SUCCESS, result(out))
        assertNull(out.status)
    }

    @Test
    fun `engine pass through declares asynchronous status and strict range validation`() {
        val passThrough = EngineFtmsControlSink { null }
        assertTrue(passThrough.reportsTargetChangesAsynchronously)
        assertTrue(!passThrough.clampsTargetsToAdvertisedRange)

        val sink = RecordingSink(
            reportsTargetChangesAsynchronously = true,
            targetCapabilities = TargetCapabilities(
                power = true,
                powerRange = FtmsTargetRange(1, 1_000, 1),
            ),
        )
        val out = FtmsControlPointHandler(sink).handle(
            byteArrayOf(O.SET_TARGET_POWER) + s16Wrapping(1_001),
            hasControl = true,
        )
        assertEquals(R.INVALID_PARAMETER, result(out))
        assertNull(out.status)
        assertNull(sink.power)
    }

    @Test
    fun `the legacy resistance byte is read against the range the machine advertises`() {
        // The Wahoo app writes whole levels in the legacy UINT8 form (04 05 / 04 0F / 04 19 /
        // 04 23 for 5/15/25/35, wahoobtsnoop_hci1.log 2026-08-31); spec-conformant clients write
        // tenths. Neither convention can be assumed globally: assuming whole levels corrupts what
        // the bridge forwards to a real machine by 10x, and assuming tenths rejects Wahoo. The
        // advertised range decides, by the same rule FtmsResistanceScale applies upstream.
        val kickr = RecordingSink(
            targetCapabilities = TargetCapabilities(
                resistance = true,
                // The KICKR/Zwift simulator personas: 1.0 - 100.0 in whole-level steps.
                resistanceRange = FtmsTargetRange(10, 1_000, 10),
            ),
        )
        val kickrCp = FtmsControlPointHandler(kickr)
        listOf(5 to 5.0, 15 to 15.0, 25 to 25.0, 35 to 35.0).forEach { (wire, level) ->
            val out = kickrCp.handle(byteArrayOf(O.SET_TARGET_RESISTANCE, wire.toByte()), hasControl = true)
            assertEquals("wire $wire", R.SUCCESS, result(out))
            assertEquals("wire $wire", level, kickr.resistance!!, 0.001)
            // Echoed in the client's own framing and convention, not re-encoded as SINT16.
            assertArrayEquals(
                byteArrayOf(FtmsSpec.Status.TARGET_RESISTANCE_CHANGED, wire.toByte()),
                out.status,
            )
        }

        // A machine whose declared ceiling is 20.0 cannot mean level 35, so the same byte is
        // read as tenths and forwarded as 3.5 - the bridge behaviour, unchanged.
        val spec = RecordingSink(
            targetCapabilities = TargetCapabilities(
                resistance = true,
                resistanceRange = FtmsTargetRange(0, 200, 5),
            ),
        )
        val out = FtmsControlPointHandler(spec)
            .handle(byteArrayOf(O.SET_TARGET_RESISTANCE, 35), hasControl = true)
        assertEquals(R.SUCCESS, result(out))
        assertEquals(3.5, spec.resistance!!, 0.001)
        assertArrayEquals(
            byteArrayOf(FtmsSpec.Status.TARGET_RESISTANCE_CHANGED, 35),
            out.status,
        )
    }

    @Test
    fun `set target resistance rejects a one octet write`() {
        val cp = FtmsControlPointHandler(RecordingSink())
        val out = cp.handle(byteArrayOf(O.SET_TARGET_RESISTANCE), hasControl = true)
        assertEquals(R.INVALID_PARAMETER, result(out))
    }

    @Test
    fun `resistance control point accepts sint16 framing through level 30`() {
        val sink = RecordingSink(
            targetCapabilities = FtmsMockControlProfile.forMachine(FtmsMachineProfile.INDOOR_BIKE),
        )
        val cp = FtmsControlPointHandler(sink)
        val session = FtmsProcedureSession().apply { hasControl = true }
        val out = cp.handle(
            byteArrayOf(O.SET_TARGET_RESISTANCE) + s16Wrapping(300),
            session,
        )
        assertEquals(R.SUCCESS, result(out))
        assertEquals(30.0, sink.resistance!!, 0.001)
        assertArrayEquals(
            byteArrayOf(FtmsSpec.Status.TARGET_RESISTANCE_CHANGED) + s16Wrapping(300),
            out.status,
        )
    }

    @Test
    fun `set target speed is uint16 in hundredths of a kmh`() {
        val sink = RecordingSink()
        val cp = FtmsControlPointHandler(sink)
        // 0x0BB8 = 3000 -> 30.00 km/h, little-endian
        val out = cp.handle(byteArrayOf(O.SET_TARGET_SPEED, 0xB8.toByte(), 0x0B), hasControl = true)
        assertEquals(R.SUCCESS, result(out))
        assertEquals(30.0, sink.speed!!, 0.001)
    }

    @Test
    fun `set target inclination is a signed sint16 in tenths of a percent`() {
        val sink = RecordingSink()
        val cp = FtmsControlPointHandler(sink)
        // -25 -> 0xFFE7 little-endian -> -2.5 %
        val out = cp.handle(
            byteArrayOf(O.SET_TARGET_INCLINATION, 0xE7.toByte(), 0xFF.toByte()),
            hasControl = true,
        )
        assertEquals(R.SUCCESS, result(out))
        assertEquals(-2.5, sink.inclination!!, 0.001)
    }

    @Test
    fun `set target power is a signed sint16 in watts`() {
        val sink = RecordingSink()
        val cp = FtmsControlPointHandler(sink)
        val out = cp.handle(
            byteArrayOf(O.SET_TARGET_POWER, 0xCE.toByte(), 0xFF.toByte()),
            hasControl = true,
        )
        assertEquals(R.SUCCESS, result(out))
        assertEquals(-50, sink.power)
    }

    @Test
    fun `indoor bike simulation decodes wind grade crr and cw`() {
        val sink = RecordingSink()
        val cp = FtmsControlPointHandler(sink)
        // wind 0x0000 = 0 m/s, grade 0x01F4 = 500 -> 5.00 %, crr 0x50 = 80 ->
        // 0.008, cw 0x33 = 51 -> 0.51
        val out = cp.handle(
            byteArrayOf(
                O.SET_INDOOR_BIKE_SIMULATION,
                0x00, 0x00,
                0xF4.toByte(), 0x01,
                0x50, 0x33,
            ),
            hasControl = true,
        )
        assertEquals(R.SUCCESS, result(out))
        val sim = sink.sim!!
        assertEquals(0.0, sim[0], 0.0001)
        assertEquals(5.0, sim[1], 0.0001)
        assertEquals(0.008, sim[2], 0.00001)
        assertEquals(0.51, sim[3], 0.0001)
    }

    @Test
    fun `stop pause honours its mandatory parameter byte`() {
        val sink = RecordingSink()
        val cp = FtmsControlPointHandler(sink)

        val stopSession = FtmsProcedureSession().apply {
            hasControl = true
            operationalState = FtmsProcedureSession.OperationalState.RUNNING
        }
        assertEquals(R.SUCCESS, result(cp.handle(byteArrayOf(O.STOP_PAUSE, 0x01), stopSession)))
        assertEquals(true, sink.stopped)

        val pauseSession = FtmsProcedureSession().apply {
            hasControl = true
            operationalState = FtmsProcedureSession.OperationalState.RUNNING
        }
        assertEquals(R.SUCCESS, result(cp.handle(byteArrayOf(O.STOP_PAUSE, 0x02), pauseSession)))
        assertEquals(false, sink.stopped)

        // Missing or out-of-range parameter is a parameter error, not silence.
        assertEquals(R.INVALID_PARAMETER, result(cp.handle(byteArrayOf(O.STOP_PAUSE), hasControl = true)))
        assertEquals(R.INVALID_PARAMETER, result(cp.handle(byteArrayOf(O.STOP_PAUSE, 0x09), hasControl = true)))
    }

    @Test
    fun `stop and start emit the matching machine status`() {
        val cp = FtmsControlPointHandler(RecordingSink())
        val session = FtmsProcedureSession().apply { hasControl = true }
        val started = cp.handle(byteArrayOf(O.START_RESUME), session)
        assertArrayEquals(byteArrayOf(FtmsSpec.Status.STARTED_OR_RESUMED_BY_USER), started.status)

        val stopped = cp.handle(byteArrayOf(O.STOP_PAUSE, 0x01), session)
        assertArrayEquals(
            byteArrayOf(FtmsSpec.Status.STOPPED_OR_PAUSED_BY_USER, 0x01),
            stopped.status,
        )
    }

    // ── v1 inert behaviour ───────────────────────────────────────────────────

    @Test
    fun `inert sink reports target setting as unsupported`() {
        val cp = FtmsControlPointHandler(InertFtmsControlSink)
        val targets = listOf(
            byteArrayOf(O.SET_TARGET_SPEED, 0, 0),
            byteArrayOf(O.SET_TARGET_INCLINATION, 0, 0),
            byteArrayOf(O.SET_TARGET_RESISTANCE, 0),
            byteArrayOf(O.SET_TARGET_POWER, 0, 0),
            byteArrayOf(O.SET_TARGET_HEART_RATE, 0),
            byteArrayOf(O.SET_INDOOR_BIKE_SIMULATION, 0, 0, 0, 0, 0, 0),
        )
        for (write in targets) {
            val out = cp.handle(write, hasControl = true)
            assertEquals(
                "opcode 0x%02X".format(write[0]),
                R.OPCODE_NOT_SUPPORTED,
                result(out),
            )
            assertNull(out.status)
        }
    }

    @Test
    fun `target setting features are zero while the sink is inert`() {
        // With TSF zero the Supported-* Range characteristics must not be
        // registered at all (FTMS 4.11-4.15).
        assertEquals(0, InertFtmsControlSink.targetCapabilities.toTargetFeatureBits())
        assertTrue(!InertFtmsControlSink.targetCapabilities.any)
    }

    @Test
    fun `capability off yields not supported even when the sink can control`() {
        val sink = RecordingSink(
            targetCapabilities = TargetCapabilities(
                power = true,
                powerRange = FtmsTargetRange(-2_000, 2_000, 1),
            ),
        )
        val cp = FtmsControlPointHandler(sink)
        assertEquals(
            R.SUCCESS,
            result(cp.handle(byteArrayOf(O.SET_TARGET_POWER, 0x64, 0x00), hasControl = true)),
        )
        assertEquals(
            R.OPCODE_NOT_SUPPORTED,
            result(cp.handle(byteArrayOf(O.SET_TARGET_SPEED, 0x64, 0x00), hasControl = true)),
        )
    }

    @Test
    fun `downstream failure is reported as operation failed`() {
        val sink = RecordingSink(succeed = false)
        val cp = FtmsControlPointHandler(sink)
        val out = cp.handle(byteArrayOf(O.SET_TARGET_POWER, 0x64, 0x00), hasControl = true)
        assertEquals(R.OPERATION_FAILED, result(out))
    }

    @Test
    fun `duplicate and invalid operational transitions fail without changing state`() {
        val cp = FtmsControlPointHandler(RecordingSink())
        val session = FtmsProcedureSession().apply { hasControl = true }

        assertEquals(R.OPERATION_FAILED, result(cp.handle(byteArrayOf(O.STOP_PAUSE, 0x01), session)))
        assertEquals(FtmsProcedureSession.OperationalState.IDLE, session.operationalState)

        assertEquals(R.SUCCESS, result(cp.handle(byteArrayOf(O.START_RESUME), session)))
        assertEquals(R.OPERATION_FAILED, result(cp.handle(byteArrayOf(O.START_RESUME), session)))
        assertEquals(FtmsProcedureSession.OperationalState.RUNNING, session.operationalState)

        assertEquals(R.SUCCESS, result(cp.handle(byteArrayOf(O.STOP_PAUSE, 0x02), session)))
        assertEquals(R.OPERATION_FAILED, result(cp.handle(byteArrayOf(O.STOP_PAUSE, 0x02), session)))
        assertEquals(FtmsProcedureSession.OperationalState.PAUSED, session.operationalState)
    }

    @Test
    fun `failed sink action does not change operation state or emit status`() {
        val cp = FtmsControlPointHandler(RecordingSink(succeed = false))
        val session = FtmsProcedureSession().apply { hasControl = true }
        val out = cp.handle(byteArrayOf(O.START_RESUME), session)

        assertEquals(R.OPERATION_FAILED, result(out))
        assertEquals(FtmsProcedureSession.OperationalState.IDLE, session.operationalState)
        assertNull(out.status)
        assertTrue(!out.trainingStatusChanged)
    }

    @Test
    fun `inert sink accepts start and stop as local session transitions`() {
        // A data-only persona still owns a valid local procedure state machine.
        // Start/Resume and Stop/Pause must succeed (only target-setting stays
        // unsupported), so strict centrals do not treat them as fatal.
        val cp = FtmsControlPointHandler(InertFtmsControlSink)
        val session = FtmsProcedureSession().apply { hasControl = true }

        val started = cp.handle(byteArrayOf(O.START_RESUME), session)
        assertEquals(R.SUCCESS, result(started))
        assertEquals(FtmsProcedureSession.OperationalState.RUNNING, session.operationalState)
        assertTrue(started.trainingStatusChanged)

        val stopped = cp.handle(byteArrayOf(O.STOP_PAUSE, 0x01), session)
        assertEquals(R.SUCCESS, result(stopped))
        assertEquals(FtmsProcedureSession.OperationalState.STOPPED, session.operationalState)
    }

    @Test
    fun `procedure operands require exact lengths`() {
        val cp = FtmsControlPointHandler(RecordingSink())
        val session = FtmsProcedureSession().apply { hasControl = true }
        val oversized = listOf(
            byteArrayOf(O.RESET, 0),
            byteArrayOf(O.START_RESUME, 0),
            byteArrayOf(O.STOP_PAUSE, 1, 0),
            byteArrayOf(O.SET_TARGET_SPEED, 0, 0, 0),
            byteArrayOf(O.SET_TARGET_RESISTANCE, 0, 0, 0),
        )

        for (write in oversized) {
            assertEquals(R.INVALID_PARAMETER, result(cp.handle(write, session)))
        }
    }

    @Test
    fun `procedure session permits a bounded Garmin pipeline`() {
        val session = FtmsProcedureSession()
        repeat(FtmsProcedureSession.MAX_PIPELINED_PROCEDURES) {
            assertTrue(session.tryBeginProcedure())
        }
        assertTrue(!session.tryBeginProcedure())
        session.finishProcedure()
        assertTrue(session.tryBeginProcedure())
    }

    @Test
    fun `two target procedures can be accepted before the first indication completes`() {
        val sink = RecordingSink()
        val cp = FtmsControlPointHandler(sink)
        val session = FtmsProcedureSession().apply { hasControl = true }

        assertTrue(session.tryBeginProcedure())
        val resistance = cp.handle(byteArrayOf(O.SET_TARGET_RESISTANCE, 80), session)
        assertTrue(session.tryBeginProcedure())
        val inclination = cp.handle(byteArrayOf(O.SET_TARGET_INCLINATION, 75, 0), session)

        assertEquals(R.SUCCESS, result(resistance))
        assertEquals(R.SUCCESS, result(inclination))
        assertEquals(8.0, sink.resistance!!, 0.001)
        assertEquals(7.5, sink.inclination!!, 0.001)
        session.finishProcedure()
        session.finishProcedure()
        assertTrue(!session.procedureInFlight)
    }

    @Test
    fun `target range clamps boundaries and snaps increments before sink call`() {
        val sink = RecordingSink(
            targetCapabilities = TargetCapabilities(
                speed = true,
                speedRange = FtmsTargetRange(100, 200, 10),
            ),
            clampsTargetsToAdvertisedRange = true,
        )
        val cp = FtmsControlPointHandler(sink)
        val session = FtmsProcedureSession().apply { hasControl = true }

        assertEquals(R.SUCCESS, result(cp.handle(byteArrayOf(O.SET_TARGET_SPEED, 100, 0), session)))
        assertEquals(R.SUCCESS, result(cp.handle(byteArrayOf(O.SET_TARGET_SPEED, 200.toByte(), 0), session)))
        assertEquals(R.SUCCESS, result(cp.handle(byteArrayOf(O.SET_TARGET_SPEED, 99, 0), session)))
        assertEquals(1.0, sink.speed!!, 0.001)
        assertEquals(R.SUCCESS, result(cp.handle(byteArrayOf(O.SET_TARGET_SPEED, 201.toByte(), 0), session)))
        assertEquals(2.0, sink.speed!!, 0.001)
        val snapped = cp.handle(byteArrayOf(O.SET_TARGET_SPEED, 105, 0), session)
        assertEquals(R.SUCCESS, result(snapped))
        assertEquals(1.1, sink.speed!!, 0.001)
        assertArrayEquals(
            byteArrayOf(FtmsSpec.Status.TARGET_SPEED_CHANGED, 110, 0),
            snapped.status,
        )
    }

    @Test
    fun `standardized target boundaries and clamping flow through control point`() {
        val sink = RecordingSink(
            targetCapabilities = FtmsMockControlProfile.forMachine(FtmsMachineProfile.ELLIPTICAL),
            clampsTargetsToAdvertisedRange = true,
        )
        val cp = FtmsControlPointHandler(sink)
        val session = FtmsProcedureSession().apply { hasControl = true }

        fun apply(opcode: Byte, operand: ByteArray) =
            cp.handle(byteArrayOf(opcode) + operand, session).also {
                assertEquals(R.SUCCESS, result(it))
                assertArrayEquals(byteArrayOf(0x80.toByte(), opcode, R.SUCCESS), it.response)
            }

        apply(O.SET_TARGET_SPEED, u16Wrapping(0))
        assertEquals(0.0, sink.speed!!, 0.001)
        apply(O.SET_TARGET_SPEED, u16Wrapping(3_000))
        assertEquals(30.0, sink.speed!!, 0.001)

        apply(O.SET_TARGET_INCLINATION, s16Wrapping(-100))
        assertEquals(-10.0, sink.inclination!!, 0.001)
        apply(O.SET_TARGET_INCLINATION, s16Wrapping(300))
        assertEquals(30.0, sink.inclination!!, 0.001)

        apply(O.SET_TARGET_RESISTANCE, s16Wrapping(10))
        assertEquals(1.0, sink.resistance!!, 0.001)
        apply(O.SET_TARGET_RESISTANCE, s16Wrapping(300))
        assertEquals(30.0, sink.resistance!!, 0.001)

        apply(O.SET_TARGET_POWER, s16Wrapping(1))
        assertEquals(1, sink.power)
        apply(O.SET_TARGET_POWER, s16Wrapping(1_000))
        assertEquals(1_000, sink.power)
        val exactPower = apply(O.SET_TARGET_POWER, s16Wrapping(237))
        assertEquals(237, sink.power)
        assertArrayEquals(
            byteArrayOf(FtmsSpec.Status.TARGET_POWER_CHANGED) + s16Wrapping(237),
            exactPower.status,
        )

        apply(O.SET_TARGET_SPEED, u16Wrapping(65_535))
        assertEquals(30.0, sink.speed!!, 0.001)
        apply(O.SET_TARGET_INCLINATION, s16Wrapping(Short.MIN_VALUE.toInt()))
        assertEquals(-10.0, sink.inclination!!, 0.001)
        apply(O.SET_TARGET_RESISTANCE, s16Wrapping(2_000))
        assertEquals(30.0, sink.resistance!!, 0.001)
        apply(O.SET_TARGET_POWER, s16Wrapping(Short.MAX_VALUE.toInt()))
        assertEquals(1_000, sink.power)
    }

    @Test
    fun `unknown opcode is reported as not supported`() {
        val cp = FtmsControlPointHandler(RecordingSink())
        val out = cp.handle(byteArrayOf(0x7E, 0x00), hasControl = true)
        assertEquals(R.OPCODE_NOT_SUPPORTED, result(out))
        assertEquals(0x7E.toByte(), out.response[1])
    }

    @Test
    fun `wheel circumference 0x12 is setup available without acquiring control`() {
        val sink = RecordingSink(
            canControl = false,
            targetCapabilities = TargetCapabilities(wheelCircumference = true),
            reportsTargetChangesAsynchronously = true,
        )
        val cp = FtmsControlPointHandler(sink)
        val session = FtmsProcedureSession()

        assertEquals(R.INVALID_PARAMETER, result(cp.handle(byteArrayOf(O.SET_WHEEL_CIRCUMFERENCE, 1), session)))
        assertEquals(R.INVALID_PARAMETER, result(cp.handle(byteArrayOf(O.SET_WHEEL_CIRCUMFERENCE, 0, 0), session)))

        val accepted = cp.handle(byteArrayOf(O.SET_WHEEL_CIRCUMFERENCE, 0x52, 0x52), session)
        assertEquals(R.SUCCESS, result(accepted))
        assertEquals(2107.4, sink.wheelCircumferenceMm!!, 0.0001)
        assertEquals(2107.4, session.acceptedTargets.wheelCircumferenceMm!!, 0.0001)
        assertNull(accepted.status)
        assertFalse(session.hasControl)

        val deniedPower = cp.handle(byteArrayOf(O.SET_TARGET_POWER, 0xA0.toByte(), 0x00), session)
        assertEquals(R.CONTROL_NOT_PERMITTED, result(deniedPower))
    }

    @Test
    fun `wheel circumference without control rejects an unsupported sink honestly`() {
        val sink = RecordingSink(targetCapabilities = TargetCapabilities())
        val session = FtmsProcedureSession()

        val out = FtmsControlPointHandler(sink).handle(
            byteArrayOf(O.SET_WHEEL_CIRCUMFERENCE, 0xDC.toByte(), 0x50),
            session,
        )

        assertEquals(R.OPCODE_NOT_SUPPORTED, result(out))
        assertFalse(session.hasControl)
        assertNull(sink.wheelCircumferenceMm)
    }
}
