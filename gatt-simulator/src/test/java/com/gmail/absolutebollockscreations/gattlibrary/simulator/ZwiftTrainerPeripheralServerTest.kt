package com.gmail.absolutebollockscreations.gattlibrary.simulator

import com.gmail.absolutebollockscreations.gattlibrary.central.RIDEON_GEAR_RATIOS
import com.gmail.absolutebollockscreations.gattlibrary.central.RideOnControlTarget
import com.gmail.absolutebollockscreations.gattlibrary.central.RideOnDecodeResult
import com.gmail.absolutebollockscreations.gattlibrary.central.RideOnProtoCodec
import com.gmail.absolutebollockscreations.gattlibrary.central.RideOnProtoField
import com.gmail.absolutebollockscreations.gattlibrary.central.RideOnServiceForm
import com.gmail.absolutebollockscreations.gattlibrary.central.RideOnUuids
import com.gmail.absolutebollockscreations.gattlibrary.central.decodeRideOnTelemetry
import com.gmail.absolutebollockscreations.gattlibrary.central.encodeRideOnTarget
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ZwiftTrainerPeripheralServerTest {
    @Test
    fun `CORE2 form accepts both handshake form bytes and uses shared FreeWheel vectors`() {
        val model = KickrSyntheticModel()
        val server = ZwiftTrainerPeripheralServer(RideOnServiceForm.CORE2, model)
        val handshake = server.handle(RideOnUuids.HANDSHAKE_CORE2, true)
        assertTrue(handshake.accepted)
        // Handshake acceptance now also emits the two QZ-derived OPCODE_STATUS_MESSAGE (0x2A)
        // frames alongside the echoed acknowledgement - see ZwiftTrainerPeripheralServer.handle()'s
        // KDoc.
        assertEquals(3, handshake.frames.size)
        assertArrayEquals(RideOnUuids.HANDSHAKE_CORE2, handshake.frames[0].value)
        assertFalse(handshake.frames[0].async)
        assertTrue(handshake.frames[1].async)
        assertTrue(handshake.frames[2].async)
        assertTrue(server.handle(hex("41 08 05"), true).accepted)
        assertTrue(server.handle(hex("00 08 00"), true).accepted)
        assertTrue(server.handle(hex("04 18 FA 01"), true).accepted)
        assertEquals(250, model.snapshot().targetPowerWatts)
        val shift = server.handle(RideOnProtoCodec.virtualShift(1, 0, 24_000), true)
        assertTrue(shift.accepted)
        assertArrayEquals(hex("40 10 00"), shift.frames.single().value)
        assertEquals(12, model.snapshot().virtualGearIndex)
    }

    @Test
    fun `real Zwift always sends form byte 03, even to the ORIGINAL 128-bit service`() {
        // Regression test for the 2026-08-30 three-persona btsnoop capture
        // (zwiftkickrbtsnoop_hci2.log): real Zwift wrote "RideOn" 02 03 to every persona it
        // connected to in that session, including CORE_ORIGINAL_ZWIFT and ZWIFT_HUB_CAPTURED,
        // both configured with the 128-bit ORIGINAL RideOn service - never "RideOn" 02 02. A
        // server that only accepted the form-specific HANDSHAKE_ORIGINAL constant there could
        // never negotiate: zero ASYNC_TX telemetry notifications appeared for the rest of either
        // session. The form byte is not tied to which RideOn service UUID was discovered, so
        // acceptance no longer depends on the configured RideOnServiceForm at all.
        val server = subject()
        val accepted = server.handle(RideOnUuids.HANDSHAKE_CORE2, true)
        assertTrue(accepted.accepted && accepted.negotiated)
        assertArrayEquals(RideOnUuids.HANDSHAKE_CORE2, accepted.frames[0].value)
        assertEquals(ZwiftTrainerStage.NEGOTIATED, server.stage)
    }

    @Test
    fun `original form requires both CCCDs and still accepts the 02 02 marker too`() {
        val server = subject()
        assertFalse(server.handle(RideOnUuids.HANDSHAKE_ORIGINAL, false).accepted)
        val accepted = server.handle(RideOnUuids.HANDSHAKE_ORIGINAL, true)
        assertTrue(accepted.accepted && accepted.negotiated)
        assertArrayEquals(RideOnUuids.HANDSHAKE_ORIGINAL, accepted.frames[0].value)
        assertEquals(ZwiftTrainerStage.NEGOTIATED, server.stage)
    }

    @Test
    fun `Zwift Hub device-signature suffix after the handshake is tolerated and echoed back`() {
        // Regression test for the ZWIFT_HUB_CAPTURED session in the same 2026-08-30 capture:
        // real Zwift appended a fixed 12-byte suffix after the 8-byte "RideOn" 02 03 magic on
        // every retry (constant within the session - some device-recognition token specific to
        // how Zwift treats its own-brand trainer). An exact-length contentEquals match against
        // the bare 8-byte handshake rejected this every time, which is the wire-level cause of
        // that persona sticking on "connecting" for the entire session.
        val suffixed = RideOnUuids.HANDSHAKE_CORE2 +
            hex("49 43 A0 A2 54 BE 75 2A E6 54 70 AE")
        val server = subject()
        val accepted = server.handle(suffixed, true)
        assertTrue(accepted.accepted && accepted.negotiated)
        assertArrayEquals(suffixed, accepted.frames[0].value)
        assertEquals(ZwiftTrainerStage.NEGOTIATED, server.stage)
    }

    @Test
    fun `garbage that merely starts with RideOn is still rejected`() {
        val server = subject()
        assertFalse(server.handle("RideOn".toByteArray(Charsets.US_ASCII), true).accepted)
        assertFalse(server.handle(hex("52 69 64 65 4F 6E 02 04"), true).accepted)
        assertEquals(ZwiftTrainerStage.CONNECTED, server.stage)
    }

    @Test
    fun `captured initialization order gates commands`() {
        val server = subject()
        assertTrue(server.handle(RideOnUuids.HANDSHAKE_ORIGINAL, true).accepted)
        assertFalse(server.handle(hex("04 18 FA 01"), true).accepted)
        assertTrue(server.handle(hex("41 08 05"), true).accepted)
        assertEquals(ZwiftTrainerStage.INITIALIZED, server.stage)
        val ready = server.handle(hex("00 08 00"), true)
        assertTrue(ready.accepted)
        // The dataId=0 general-info reply is now the QZ-derived structured 0x3C frame, sent
        // over indicate rather than the old bare-telemetry notify frame - see
        // ZwiftTrainerPeripheralServer.generalInfoFrame()'s KDoc.
        assertFalse(ready.frames.single().async)
        assertEquals(ZwiftTrainerStage.READY, server.stage)
    }

    @Test
    fun `real Zwift skipping INITIALIZATION still reaches READY`() {
        // Regression test for the 2026-08-30 Zwift-vs-CORE2_FC82_PROVISIONAL btsnoop capture:
        // Zwift wrote the "RideOn" handshake and then went straight to the General Information
        // Request (00 08 00) without ever sending the INITIALIZATION frame (41 08 05) our own
        // client-side RideOnControlSession sends. Before this fix that request was rejected as
        // "out of order" every time (silently, since it arrives as a WriteCommand), the state
        // machine stayed stuck at NEGOTIATED forever, controlReady never became true, no RideOn
        // telemetry was ever sent, and every later HubCommand was rejected too - so the simulator
        // stayed parked at its AUTONOMOUS defaults for the whole session and Zwift showed no
        // bike movement (docs/evidence/phase-10, 2026-08-30 Zwift/CORE2 btsnoop capture).
        val model = KickrSyntheticModel()
        val server = ZwiftTrainerPeripheralServer(RideOnServiceForm.CORE2, model)
        assertTrue(server.handle(RideOnUuids.HANDSHAKE_CORE2, true).accepted)
        assertEquals(ZwiftTrainerStage.NEGOTIATED, server.stage)

        val ready = server.handle(hex("00 08 00"), true)
        assertTrue("general-information request must be accepted directly from NEGOTIATED: ${ready.reason}", ready.accepted)
        assertTrue(server.controlReady)
        assertEquals(ZwiftTrainerStage.READY, server.stage)
        // The dataId=0 general-info reply is now the QZ-derived structured 0x3C frame, sent over
        // indicate - see ZwiftTrainerPeripheralServer.generalInfoFrame()'s KDoc.
        assertFalse(ready.frames.single().async)

        val ergCommand = server.handle(hex("04 18 FA 01"), true)
        assertTrue("HubCommand should now reach the model: ${ergCommand.reason}", ergCommand.accepted)
        assertEquals(KickrControlMode.ERG, model.snapshot().mode)
        assertEquals(250, model.snapshot().targetPowerWatts)

        // A device-info request ("41 08 05") is no longer required, but real Zwift (and QZ's own
        // reference implementation) may resend it after READY too - it must not be rejected as
        // "out of order", and it must not regress the negotiated stage.
        val lateDeviceInfo = server.handle(hex("41 08 05"), true)
        assertTrue("a late device-info request after READY must not be rejected: ${lateDeviceInfo.reason}", lateDeviceInfo.accepted)
        assertEquals(ZwiftTrainerStage.READY, server.stage)
    }

    @Test
    fun `HubRequest gear-ratio query after READY reports the current gear, not a rejection`() {
        // Regression coverage for the makinolo.com "Zwift Trainer protocol" write-up: a real
        // Kickr Core answers HubRequest dataId 520 with the current gear ratio. Before this,
        // any HubRequest opcode reaching the post-READY dispatch (including a repeat of the
        // dataId-0 general info request, which real Zwift keeps re-sending after negotiation
        // completes) fell into "unsupported opcode 0x0" and was rejected.
        val model = KickrSyntheticModel()
        val server = ready(model)
        check(server.handle(RideOnProtoCodec.virtualShift(1, 0, 24_000), true).accepted)

        val gearQuery = server.handle(RideOnProtoCodec.hubRequest(RideOnUuids.HUB_REQUEST_DATA_ID_GEAR_RATIO), true)
        assertTrue("gear-ratio HubRequest should be accepted: ${gearQuery.reason}", gearQuery.accepted)
        assertArrayEquals(RideOnProtoCodec.hubRequestGearRatio(24_000), gearQuery.frames.single().value)

        val repeatGeneralInfo = server.handle(RideOnProtoCodec.hubRequest(0), true)
        assertTrue(
            "a repeated general-info request after READY must not be rejected as out of order: ${repeatGeneralInfo.reason}",
            repeatGeneralInfo.accepted,
        )
        // The dataId=0 reply is now the QZ-derived structured 0x3C frame (see
        // RideOnProtoCodec.hubGeneralInfoReply()'s KDoc), not a bare RidingData frame - a repeat
        // after READY must keep getting that same structured reply, not fall back to telemetry.
        assertArrayEquals(
            RideOnProtoCodec.hubGeneralInfoReply(serial = "FITBRIDGE-SIM01"),
            repeatGeneralInfo.frames.single().value,
        )
        assertFalse(repeatGeneralInfo.frames.single().async)
    }

    @Test
    fun `unmapped HubRequest dataIds get a structured 0x3C reply over indicate, not a bare notify`() {
        // Neither the community write-up's dataId 1-7 range nor any other unmapped dataId (16,
        // observed on the wire from real Zwift on 2026-08-30 - see hubGeneralInfoReply's KDoc)
        // has a confirmed field layout, so there's still no fabricated per-field mapping here.
        // But a bare RidingData notify (telemetryFrame(), the previous fallback) is
        // indistinguishable from FitBridge's own periodic telemetry ticks: real Zwift retried
        // dataId 16 three times, ~1s apart, then abandoned the RideOn negotiation for the rest
        // of that session even though CPS/FTMS kept streaming valid, moving telemetry the whole
        // time. Every unmapped dataId now gets the same structured 0x3C frame dataId=0 gets, sent
        // over indicate (async=false) with its own dataId echoed back in field 1 - the channel
        // and shape real Zwift is actually waiting on, even though the content is only confirmed
        // correct for dataId 0.
        val model = KickrSyntheticModel()
        val server = ready(model)
        for (dataId in listOf(1L, 2L, 3L, 4L, 5L, 6L, 7L, 16L)) {
            val outcome = server.handle(RideOnProtoCodec.hubRequest(dataId), true)
            assertTrue("dataId $dataId: ${outcome.reason}", outcome.accepted)
            val frame = outcome.frames.single()
            assertFalse("dataId $dataId should reply over indicate", frame.async)
            assertArrayEquals(
                RideOnProtoCodec.hubGeneralInfoReply(serial = "FITBRIDGE-SIM01", dataId = dataId),
                frame.value,
            )
        }
    }

    @Test
    fun `ERG and signed simulation use production vectors and one model`() {
        val model = KickrSyntheticModel()
        val server = ready(model)
        assertTrue(server.handle(hex("04 18 FA 01"), true).accepted)
        assertEquals(KickrControlMode.ERG, model.snapshot().mode)
        assertEquals(250, model.snapshot().targetPowerWatts)

        val simulation = RideOnControlTarget.Simulation(
            windMps = -1.25,
            gradePercent = -4.0,
            crr = 0.004,
            cw = 0.51,
            gearRatio = 2.4,
            bicycleMassKg = 10.0,
            riderMassKg = 75.0,
        )
        assertTrue(server.handle(encodeRideOnTarget(simulation)!!, true).accepted)
        val snapshot = model.snapshot()
        assertEquals(KickrControlMode.SIMULATION, snapshot.mode)
        assertEquals(-1.25, snapshot.simulation.windMps, 0.001)
        assertEquals(-4.0, snapshot.simulation.gradePercent, 0.001)
        assertEquals(0.004, snapshot.simulation.crr, 0.000001)
        assertEquals(0.51, snapshot.simulation.cw, 0.000001)
        assertEquals(85.0, snapshot.simulation.totalMassKg, 0.001)
        assertEquals(12, snapshot.virtualGearIndex)
        assertEquals(2.4, snapshot.gearRatio, 0.000001)
    }

    @Test
    fun `a bare Physical gear command from a real KICKR Core session is a gear shift, not a rejected simulation`() {
        // zwiftplay/zaplibrary's zwift-play-kickr-core.csv HCI-snoop export, packet 5820: a real
        // Wahoo KICKR Core session's actual gear-shift message - HubCommand{Physical{gearRatio}}
        // alone, no Simulation, no mass fields. The previous implementation required Simulation
        // and Physical together and would have rejected this as "incomplete simulation command".
        val model = KickrSyntheticModel()
        val server = ready(model)
        val before = model.snapshot()
        val outcome = server.handle(hex("04 2A 04 10 C0 BB 01"), true)
        assertTrue("bare gear-shift HubCommand must be accepted: ${outcome.reason}", outcome.accepted)
        val after = model.snapshot()
        assertEquals(12, after.virtualGearIndex)
        assertEquals(2.4, after.gearRatio, 0.000001)
        // A bare gear shift must not touch the simulation target or flip control mode/kind to
        // SIMULATION - it is a Direct write (see KickrSyntheticModel.markDirectControl's KDoc).
        assertEquals(before.simulation, after.simulation)
        assertEquals(before.mode, after.mode)
    }

    @Test
    fun `a bare partial Simulation update from a real KICKR Core session merges onto the last known target`() {
        // zwiftplay/zaplibrary's zwift-play-kickr-core.csv HCI-snoop export, packet 7004: a real
        // grade-only update - HubCommand{Simulation{gradePercent}} alone, with only that one
        // SimulationParam sub-field set (no wind/Cw/Crr, no Physical at all). The previous
        // implementation required all four SimulationParam sub-fields present together and would
        // have rejected this as "invalid SimulationParam fields".
        val model = KickrSyntheticModel()
        val server = ready(model)
        val baseline = RideOnControlTarget.Simulation(
            windMps = -1.25,
            gradePercent = -4.0,
            crr = 0.004,
            cw = 0.51,
            gearRatio = 2.4,
            bicycleMassKg = 10.0,
            riderMassKg = 75.0,
        )
        check(server.handle(encodeRideOnTarget(baseline)!!, true).accepted)

        val outcome = server.handle(hex("04 22 02 10 25"), true)
        assertTrue("bare grade-only HubCommand must be accepted: ${outcome.reason}", outcome.accepted)
        val snapshot = model.snapshot()
        assertEquals(KickrControlMode.SIMULATION, snapshot.mode)
        // Only gradePercent changed (zigzag-decode(0x25=37) == -19, /100 == -0.19); every other
        // sub-field, and total mass (no Physical was present at all this time), carried over
        // from the baseline write rather than resetting to defaults.
        assertEquals(-0.19, snapshot.simulation.gradePercent, 0.001)
        assertEquals(-1.25, snapshot.simulation.windMps, 0.001)
        assertEquals(0.004, snapshot.simulation.crr, 0.000001)
        assertEquals(0.51, snapshot.simulation.cw, 0.000001)
        assertEquals(85.0, snapshot.simulation.totalMassKg, 0.001)
        assertEquals(12, snapshot.virtualGearIndex)
    }

    @Test
    fun `a HubCommand with only one of bicycle or rider mass is rejected rather than guessed`() {
        // Not observed in the real capture (which never splits bike and rider mass across
        // separate writes), but our model only stores their combined totalMassKg - a message
        // that supplies just one side can't be merged correctly, so it fails closed instead of
        // silently computing a wrong total.
        val model = KickrSyntheticModel()
        val server = ready(model)
        val before = model.snapshot()
        // opcode 0x04, field4=Simulation{gradePercent=37}, field5=Physical{bicycleMass=1000}
        // (no riderMass, no gearRatio).
        val outcome = server.handle(hex("04 22 02 10 25 2A 03 20 E8 07"), true)
        assertFalse(outcome.accepted)
        assertEquals(before, model.snapshot())
    }

    @Test
    fun `RideOn rider and bicycle masses change physics power at the same speed`() {
        fun powerFor(riderMassKg: Double, bicycleMassKg: Double): Int {
            val model = KickrSyntheticModel().also { it.startOrResume() }
            val server = ready(model)
            val target = RideOnControlTarget.Simulation(
                windMps = 0.0,
                gradePercent = 5.0,
                crr = 0.004,
                cw = 0.51,
                gearRatio = 2.4,
                bicycleMassKg = bicycleMassKg,
                riderMassKg = riderMassKg,
            )
            check(server.handle(encodeRideOnTarget(target)!!, true).accepted)
            repeat(80) { model.advance(0.5) }
            assertEquals(riderMassKg + bicycleMassKg, model.snapshot().simulation.totalMassKg, 0.001)
            assertEquals(28.0, model.snapshot().speedKph, 0.02)
            return model.snapshot().powerWatts
        }

        assertTrue(powerFor(90.0, 12.0) > powerFor(60.0, 8.0))
    }

    @Test
    fun `RideOn signed descent lowers live telemetry power after flat simulation`() {
        val model = KickrSyntheticModel().also { it.startOrResume() }
        val server = ready(model)
        fun send(gradePercent: Double) = server.handle(
            encodeRideOnTarget(
                RideOnControlTarget.Simulation(
                    windMps = 0.0,
                    gradePercent = gradePercent,
                    crr = 0.004,
                    cw = 0.51,
                    gearRatio = 2.4,
                    bicycleMassKg = 10.0,
                    riderMassKg = 75.0,
                ),
            )!!,
            true,
        )

        assertTrue(send(0.0).accepted)
        repeat(80) { model.advance(0.5) }
        val flat = model.snapshot()
        assertTrue(send(-5.0).accepted)
        val firstDescentTick = model.advance(0.5)
        assertEquals(flat.speedKph, firstDescentTick.speedKph, 0.01)
        assertEquals(-5.0, firstDescentTick.simulation.gradePercent, 0.001)
        // Power is smoothed toward the new target rather than snapping instantly (see
        // KickrSyntheticModel.advance()'s shared approach() step), so it only starts dropping
        // below the flat steady state on this first tick - give it time to fully converge
        // before asserting the descent's steady-state power.
        assertTrue(firstDescentTick.powerWatts < flat.powerWatts)
        var descent = firstDescentTick
        repeat(80) { descent = model.advance(0.5) }
        val telemetry = decodeRideOnTelemetry(server.telemetryFrame().value)!!

        assertEquals(0, descent.powerWatts)
        assertEquals(descent.powerWatts, telemetry.powerWatts)
    }

    @Test
    fun `all 24 gears update state and echo exact uint32 sequence`() {
        val model = KickrSyntheticModel()
        val server = ready(model)
        RIDEON_GEAR_RATIOS.forEachIndexed { index, ratio ->
            val sequence = if (index == 23) 0xFFFF_FFFFL else index.toLong()
            val outcome = server.handle(RideOnProtoCodec.virtualShift(1, sequence, ratio.toLong()), true)
            assertTrue("gear ${index + 1}: ${outcome.reason}", outcome.accepted)
            assertArrayEquals(RideOnProtoCodec.virtualShiftResponse(sequence), outcome.frames.single().value)
            assertEquals(index + 1, model.snapshot().virtualGearIndex)
            assertEquals(ratio / 10_000.0, model.snapshot().gearRatio, 0.000001)
        }
    }

    @Test
    fun `riding data is encoded from the same immutable snapshot`() {
        val model = KickrSyntheticModel()
        val server = ready(model)
        server.handle(hex("04 18 FA 01"), true)
        model.startOrResume()
        val snapshot = model.advance(2.0)
        val frame = server.telemetryFrame()
        val decoded = decodeRideOnTelemetry(frame.value)!!
        assertTrue(frame.async)
        assertEquals(snapshot.powerWatts, decoded.powerWatts)
        assertEquals(snapshot.cadenceRpm.toInt(), decoded.cadenceRpm)
        assertEquals(snapshot.speedKph, decoded.speedKph!!, 0.01)
        assertEquals(snapshot.heartRateBpm, decoded.heartRateBpm)
    }

    @Test
    fun `unsupported direct resistance malformed duplicates and wrong command type fail without mutation`() {
        val model = KickrSyntheticModel()
        val server = ready(model)
        val before = model.snapshot()
        listOf(
            hex("04 08 01"),
            hex("04 18 64 18 65"),
            hex("04 1A 01 00"),
            RideOnProtoCodec.virtualShift(2, 0, 24_000),
            ByteArray(RideOnUuids.MAX_PACKET_BYTES + 1) { 0x04 },
        ).forEach { assertFalse(server.handle(it, true).accepted) }
        assertEquals(before, model.snapshot())
    }

    @Test
    fun `an out-of-table gear ratio is still acked but does not mutate the model`() {
        // Real Zwift is the authoritative client here (see handleVirtualShift's KDoc): every
        // 0x3F it sends gets an ack regardless of whether the payload turns out to encode a
        // gear FitBridge actually knows, since real Zwift multiplexes non-shift payloads under
        // the same opcode too. A shift request naming a ratio that isn't one of the 24 known
        // gears is the narrow case where the payload *does* look like a gear-ratio shift but
        // fails to resolve - it must still be acked, just without mutating virtual-gear state.
        val model = KickrSyntheticModel()
        val server = ready(model)
        val before = model.snapshot()
        val outcome = server.handle(RideOnProtoCodec.virtualShift(1, 7, 24_001), true)
        assertTrue(outcome.reason, outcome.accepted)
        assertArrayEquals(RideOnProtoCodec.virtualShiftResponse(7), outcome.frames.single().value)
        assertEquals(before, model.snapshot())
    }

    @Test
    fun `non gear-shift 0x3F payload shapes captured from real Zwift are acked`() {
        // These two payload shapes are pinned byte-for-byte from a 2026-08-30 btsnoop capture of
        // real Zwift against CORE_ORIGINAL_ZWIFT / ZWIFT_HUB_CAPTURED - see handleVirtualShift's
        // KDoc. Before that fix, both were silently rejected (no ack frame at all): the first has
        // no nested field 7 whatsoever (a SimulationParam-shaped push, nested fields 8/10/11/12 -
        // confirmed as CWa=5100/0.51 and Crr=400/0.004 against zwift_hub.proto's documented
        // constants; fields 10/11=831/9253 remain unmapped candidates for wind/grade and are not
        // applied), and the second has nested field 7 fixed at 0, which isn't a valid table
        // entry. Zwift retried each burst three times over the real connection and disconnected
        // roughly 22s after the last unacknowledged one.
        val model = KickrSyntheticModel()
        val server = ready(model)
        val before = model.snapshot()
        val simulationParamShaped = hex("3F 08 01 10 00 22 0C 40 EC 27 50 BF 06 58 A5 48 60 90 03")
        val fixedZeroGearField = hex("3F 08 01 10 01 22 04 28 65 38 00")
        listOf(simulationParamShaped to 0L, fixedZeroGearField to 1L).forEach { (packet, sequence) ->
            val outcome = server.handle(packet, true)
            assertTrue("${packet.toList()}: ${outcome.reason}", outcome.accepted)
            assertArrayEquals(RideOnProtoCodec.virtualShiftResponse(sequence), outcome.frames.single().value)
        }
        // The SimulationParam-shaped push applies the two confirmed fields (CWa, Crr) onto the
        // simulation target (which also - correctly - flips the model into SIMULATION control
        // mode, same as any other applied simulation write); the unconfirmed fields (wind,
        // grade) and everything the fixed-zero-gear-field packet touches are left untouched.
        val after = model.snapshot()
        assertEquals(0.51, after.simulation.cw, 1e-9)
        assertEquals(0.004, after.simulation.crr, 1e-9)
        assertEquals(before.simulation.windMps, after.simulation.windMps, 1e-9)
        assertEquals(before.simulation.gradePercent, after.simulation.gradePercent, 1e-9)
        assertEquals(before.simulation.totalMassKg, after.simulation.totalMassKg, 1e-9)
        assertEquals(KickrControlMode.SIMULATION, after.mode)
        assertEquals(before.virtualGearIndex, after.virtualGearIndex)
        assertEquals(before.gearRatio, after.gearRatio, 1e-9)
    }

    @Test
    fun `every target and shift truncation fails without throwing or mutation`() {
        val model = KickrSyntheticModel()
        val server = ready(model)
        val simulationAndPhysical = encodeRideOnTarget(
            RideOnControlTarget.Simulation(-1.25, -4.0, 0.004, 0.51, 2.4, 10.0, 75.0),
        )!!
        // Truncating this specific packet to exactly its first 15 bytes happens to land exactly
        // on the boundary between the Simulation field and the Physical field that follows it -
        // so that "truncation" is actually a complete, legitimate Simulation-only HubCommand
        // under the partial-update handling added for real KICKR Core sessions (see
        // handleHubCommand's KDoc), not corruption. Every other length remains a genuine
        // truncation and must still fail closed without mutating the model.
        val packets = listOf(
            hex("04 18 FA 01") to emptySet<Int>(),
            simulationAndPhysical to setOf(15),
            RideOnProtoCodec.virtualShift(1, 0, 24_000) to emptySet(),
        )
        packets.forEach { (packet, legitimatePartialLengths) ->
            for (length in 0 until packet.size) {
                val before = model.snapshot()
                val outcome = server.handle(packet.copyOf(length), true)
                if (length in legitimatePartialLengths) {
                    assertTrue("${packet[0]} at $length should be a legitimate partial update: ${outcome.reason}", outcome.accepted)
                } else {
                    assertFalse("${packet[0]} at $length", outcome.accepted)
                    assertEquals(before, model.snapshot())
                }
            }
        }
    }

    @Test
    fun `unknown supported protobuf fields are preserved structurally and do not block ERG`() {
        val model = KickrSyntheticModel()
        val server = ready(model)
        val packet = hex("04 18 FA 01 30 07")
        val decoded = (RideOnProtoCodec.decode(packet) as RideOnDecodeResult.Decoded).packet
        assertEquals(7L, (decoded.fields.single { it.number == 6 } as RideOnProtoField.Varint).value)
        assertTrue(server.handle(packet, true).accepted)
        assertEquals(250, model.snapshot().targetPowerWatts)
    }

    @Test
    fun `click button frame matches the pinned pressed-is-zero vectors and is always async`() {
        val server = ready(KickrSyntheticModel())
        val upPressed = server.clickButtonFrame(upPressed = true, downPressed = false)
        assertArrayEquals(RideOnProtoCodec.clickKeyPadStatus(upPressed = true, downPressed = false), upPressed.value)
        assertTrue(upPressed.async)

        val released = server.clickButtonFrame(upPressed = false, downPressed = false)
        assertArrayEquals(RideOnProtoCodec.clickKeyPadStatus(upPressed = false, downPressed = false), released.value)
        assertTrue(released.async)
    }

    @Test
    fun `reset clears negotiation and rejects late commands`() {
        val server = ready(KickrSyntheticModel())
        server.reset()
        assertEquals(ZwiftTrainerStage.CONNECTED, server.stage)
        assertFalse(server.handle(hex("04 18 FA 01"), true).accepted)
    }

    private fun subject(model: KickrSyntheticModel = KickrSyntheticModel()) =
        ZwiftTrainerPeripheralServer(RideOnServiceForm.ORIGINAL, model)

    private fun ready(model: KickrSyntheticModel): ZwiftTrainerPeripheralServer = subject(model).also {
        check(it.handle(RideOnUuids.HANDSHAKE_ORIGINAL, true).accepted)
        check(it.handle(hex("41 08 05"), true).accepted)
        check(it.handle(hex("00 08 00"), true).accepted)
    }

    private fun hex(value: String): ByteArray = value.split(' ').map { it.toInt(16).toByte() }.toByteArray()
}
