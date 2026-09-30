package com.gmail.absolutebollockscreations.gattlibrary.simulator

import com.gmail.absolutebollockscreations.gattlibrary.core.vendor.RideOnSpec
import com.gmail.absolutebollockscreations.gattlibrary.core.vendor.RideOnServiceForm
import kotlin.math.roundToLong
import com.gmail.absolutebollockscreations.gattlibrary.core.bytes.ProtobufVarint

internal enum class ZwiftTrainerStage { CONNECTED, NEGOTIATED, INITIALIZED, READY }

internal data class ZwiftTrainerServerFrame(
    val async: Boolean,
    val value: ByteArray,
)

internal data class ZwiftTrainerServerOutcome(
    val accepted: Boolean,
    val negotiated: Boolean = false,
    val frames: List<ZwiftTrainerServerFrame> = emptyList(),
    val reason: String? = null,
)

/** Per-link peripheral inverse of the production Zwift trainer-control session. */
internal class ZwiftTrainerPeripheralServer(
    // Retained for callers/manifest wiring; no longer gates handshake acceptance (see isHandshake).
    @Suppress("UNUSED_PARAMETER") form: RideOnServiceForm,
    private val model: KickrSyntheticModel,
) {
    var stage = ZwiftTrainerStage.CONNECTED
        private set

    val controlReady: Boolean get() = stage == ZwiftTrainerStage.READY

    fun handle(value: ByteArray, bothTxSubscribed: Boolean): ZwiftTrainerServerOutcome {
        if (!bothTxSubscribed) return rejected("both TX CCCDs are required")
        // Handshake acceptance used to require an exact match against a form-specific 8-byte
        // array (HANDSHAKE_ORIGINAL = "RideOn" 02 02 for the 128-bit service, HANDSHAKE_CORE2 =
        // "RideOn" 02 03 for the FC82 service). A 2026-08-30 btsnoop capture of real Zwift
        // running all three device attempts in one session (CORE_ORIGINAL_ZWIFT, CORE2 and
        // ZWIFT_HUB_CAPTURED personas) showed that assumption is wrong on both axes:
        //  - Zwift wrote "RideOn" 02 03 to every persona, including the two configured with the
        //    128-bit ORIGINAL service, never "RideOn" 02 02. The form byte is not tied to which
        //    RideOn service UUID it discovered, so requiring HANDSHAKE_ORIGINAL there meant the
        //    handshake for those personas could never succeed - confirmed on the wire by zero
        //    ASYNC_TX telemetry notifications for the whole session.
        //  - Against the persona advertised as "Zwift Hub" specifically, Zwift appended a fixed
        //    12-byte suffix after the 8-byte magic+form on every retry (constant within the
        //    session, presumably a device-recognition token for Zwift's own-brand trainer), so
        //    even an exact-length match against the CORE2 form would have failed too. This is
        //    the wire-level cause of that persona sticking on "connecting" forever.
        // Since Zwift is the authoritative real-world client, acceptance is now a prefix match on
        // the "RideOn" magic + a form byte of 0x02 or 0x03 (either is accepted from either
        // persona, since real Zwift does not appear to select the byte by service UUID), with any
        // trailing bytes tolerated and echoed back verbatim rather than assumed away.
        if (isHandshake(value)) {
            // A 2026-08-30 capture of Zwift connecting to the ZWIFT_HUB_CAPTURED persona showed
            // it writing the plain 8-byte handshake and then, within ~1ms, a second write to the
            // same characteristic carrying the same 8-byte prefix plus the 12-byte device-
            // signature suffix described above. Rejecting that second write as a "duplicate
            // handshake" (stage was already NEGOTIATED from the first) meant it never got an
            // accepted reply - only the short write's echo went out - and Zwift retried the whole
            // handshake sequence forever, which is the wire-level cause of that persona sticking
            // on "Connecting...". acquire(ZWIFT_PROPRIETARY) in KickrPeripheralSession is
            // idempotent once granted, so re-accepting a later handshake-shaped write is safe: it
            // only advances the stage the first time, past that it is just an echo.
            if (stage == ZwiftTrainerStage.CONNECTED) {
                stage = ZwiftTrainerStage.NEGOTIATED
            }
            // qdomyos-zwift (QZ), a mature open-source RideOn peripheral real Zwift accepts,
            // sends two OPCODE_STATUS_MESSAGE (0x2A) diagnostic frames over notify immediately
            // after accepting the handshake, in addition to its own handshake acknowledgement
            // over indicate. FitBridge previously sent only the acknowledgement. See
            // RideOnWire.statusMessage()'s KDoc for the bytes these were verified against.
            return ZwiftTrainerServerOutcome(
                accepted = true,
                negotiated = true,
                frames = listOf(
                    ZwiftTrainerServerFrame(async = false, value.copyOf()),
                    ZwiftTrainerServerFrame(async = true, RideOnWire.statusMessage(3, "ATX 04, STX 04")),
                    ZwiftTrainerServerFrame(async = true, RideOnWire.statusMessage(3, "RIDE_ON(2)")),
                ),
            )
        }
        if (stage == ZwiftTrainerStage.CONNECTED) return rejected("handshake required")
        // The 3-message sequence (handshake -> INITIALIZATION -> General Information Request)
        // was modeled on RideOnControlSession, FitBridge's own *client*-side RideOn
        // implementation used when FitBridge connects to a real trainer. The real Zwift app,
        // confirmed against a 2026-08-30 btsnoop capture of Zwift against CORE2_FC82_PROVISIONAL,
        // does not send INITIALIZATION at all - it goes straight from the handshake to the
        // General Information Request. Requiring INITIALIZED before accepting that request left
        // the state machine stuck at NEGOTIATED forever: controlReady never became true, no
        // RideOn telemetry was ever sent, and every subsequent HubCommand (power/resistance/sim)
        // was silently rejected, so the simulator stayed parked at its AUTONOMOUS defaults for
        // the whole ride and Zwift showed no bike movement. Since Zwift is the authoritative
        // real-world client, INITIALIZATION is now treated as optional: it still advances the
        // state machine when a client does send it, but the General Information Request is
        // accepted directly from NEGOTIATED too.
        // The exact bytes real Zwift (and FitBridge's own client-side RideOnControlSession)
        // send here, "41 08 05", happen to be a HubRequest-shaped OPCODE_DEVICE_INFO_REQUEST
        // (0x41) asking for dataId 5 - previously treated as an opaque "INITIALIZATION" literal
        // that only advanced the state machine with no reply at all. qdomyos-zwift (QZ) replies
        // to this same request with a proper OPCODE_HUB_REQUEST_RESPONSE (0x3C) device-info
        // frame over indicate; see RideOnWire.hubDeviceInfoReply()'s KDoc for the bytes
        // this was verified against. Matched on the opcode byte alone (not the literal 3-byte
        // sequence) so any dataId is accepted, and - mirroring the dataId-0 general-information
        // fast path above - only enforced as "out of order" before READY, since real Zwift may
        // resend it later in the session the same way it resends the general-information
        // request.
        if (value.isNotEmpty() && value[0] == RideOnSpec.OPCODE_DEVICE_INFO_REQUEST.toByte()) {
            if (stage != ZwiftTrainerStage.READY &&
                stage != ZwiftTrainerStage.NEGOTIATED &&
                stage != ZwiftTrainerStage.INITIALIZED
            ) {
                return rejected("device-info request out of order")
            }
            val packet = (RideOnWire.decode(value) as? RideOnWireDecodeResult.Decoded)?.packet
                ?: return rejected("malformed protobuf packet")
            if (stage == ZwiftTrainerStage.NEGOTIATED) stage = ZwiftTrainerStage.INITIALIZED
            return ZwiftTrainerServerOutcome(true, frames = listOf(deviceInfoFrame(packet)))
        }
        // Only treat this as the negotiation fast path while it can still advance the state
        // machine. Once already READY, the encoding of a dataId-0 HubRequest (see hubRequest()
        // in RideOnWire) happens to collide byte-for-byte with this literal, and real
        // Zwift does keep re-sending it after negotiation completes (see handleHubRequest's
        // KDoc). Matching it here unconditionally meant that legitimate repeat fell into "out of
        // order" and was rejected instead of reaching the opcode dispatch below, which already
        // handles it correctly via handleHubRequest.
        if (stage != ZwiftTrainerStage.READY && value.contentEquals(GENERAL_INFORMATION_REQUEST)) {
            if (stage != ZwiftTrainerStage.NEGOTIATED && stage != ZwiftTrainerStage.INITIALIZED) {
                return rejected("general-information request out of order")
            }
            stage = ZwiftTrainerStage.READY
            return ZwiftTrainerServerOutcome(true, frames = listOf(generalInfoFrame()))
        }
        if (!controlReady) return rejected("initialization incomplete")
        val packet = (RideOnWire.decode(value) as? RideOnWireDecodeResult.Decoded)?.packet
            ?: return rejected("malformed protobuf packet")
        return when (packet.opcode) {
            RideOnSpec.OPCODE_HUB_REQUEST -> handleHubRequest(packet)
            RideOnSpec.OPCODE_HUB_COMMAND -> handleHubCommand(packet)
            RideOnSpec.OPCODE_VIRTUAL_SHIFT -> handleVirtualShift(packet)
            else -> rejected("unsupported opcode 0x${packet.opcode.toString(16)}")
        }
    }

    fun telemetryFrame(): ZwiftTrainerServerFrame {
        val snapshot = model.snapshot()
        return ZwiftTrainerServerFrame(
            async = true,
            value = RideOnWire.ridingData(
                snapshot.powerWatts.coerceIn(0, 3_000).toLong(),
                snapshot.cadenceRpm.roundToLong().coerceIn(0, 300),
                (snapshot.speedKph * 100.0).roundToLong().coerceIn(0, 20_000),
                snapshot.heartRateBpm.coerceIn(1, 254).toLong(),
            ),
        )
    }

    /**
     * A Zwift Click button-state notification - see RideOnWire.clickKeyPadStatus()'s KDoc.
     * Unlike every other frame this server builds, it is not a reply to any inbound write: a
     * real Click is spontaneous, peripheral-initiated on a physical button press, so here it is
     * triggered by a tap in the simulator's own UI. It is still gated on [controlReady] by the
     * caller (KickrPeripheralSession.sendZwiftClickShift()) rather than here, matching how
     * telemetryFrame() is gated by its own caller (enqueueZwiftTelemetry()) instead of by
     * anything in this class.
     */
    fun clickButtonFrame(upPressed: Boolean, downPressed: Boolean): ZwiftTrainerServerFrame =
        ZwiftTrainerServerFrame(
            async = true,
            value = RideOnWire.clickKeyPadStatus(upPressed, downPressed),
        )

    // Reply to the dataId=0 "general information" HubRequest - see RideOnWire.
    // hubGeneralInfoReply()'s KDoc for the QZ reference bytes this was verified against. Sent
    // over indicate (async = false), matching QZ's notifier0004 for this same reply.
    private fun generalInfoFrame(): ZwiftTrainerServerFrame = ZwiftTrainerServerFrame(
        async = false,
        value = RideOnWire.hubGeneralInfoReply(serial = DEVICE_SERIAL),
    )

    // Reply to an opcode 0x41 device-info request - see RideOnWire.hubDeviceInfoReply()'s
    // KDoc for the QZ reference bytes this was verified against. Sent over indicate
    // (async = false), matching QZ's notifier0004 for this same reply. The requested dataId
    // (packet field 1) is decoded but not otherwise used: QZ's own reply does not vary its
    // content by the requested dataId beyond the opaque field-2 bytes reproduced here.
    private fun deviceInfoFrame(@Suppress("UNUSED_PARAMETER") packet: RideOnWirePacket): ZwiftTrainerServerFrame =
        ZwiftTrainerServerFrame(
            async = false,
            value = RideOnWire.hubDeviceInfoReply(
                deviceName = DEVICE_NAME,
                serial = DEVICE_SERIAL,
                shortField = "1",
            ),
        )

    fun reset() {
        stage = ZwiftTrainerStage.CONNECTED
    }

    // The literal GENERAL_INFORMATION_REQUEST bytes above are the fast path that gates the
    // NEGOTIATED/INITIALIZED -> READY transition on the very first request. Real Zwift keeps
    // re-sending that same dataId-0 request (and, per community reverse engineering, other
    // HubRequest dataIds - see RideOnUuids's HUB_REQUEST_DATA_ID_* KDoc) after we're already
    // READY; rejecting those repeats as "out of order" left Zwift's own negotiation waiting on a
    // reply that would never come. This handles any HubRequest that arrives once control is
    // already granted.
    private fun handleHubRequest(packet: RideOnWirePacket): ZwiftTrainerServerOutcome {
        val dataId = (packet.fields.singleOrNull { it.number == 1 } as? RideOnWireField.Varint)?.value
            ?: RideOnSpec.HUB_REQUEST_DATA_ID_GENERAL_INFO
        val frame = when (dataId) {
            RideOnSpec.HUB_REQUEST_DATA_ID_GEAR_RATIO -> ZwiftTrainerServerFrame(
                async = true,
                value = RideOnWire.hubRequestGearRatio(
                    (model.snapshot().gearRatio * 10_000.0).roundToLong(),
                ),
            )
            // See the KDoc above handle()'s dataId-0 fast path and generalInfoFrame(): real
            // Zwift keeps re-sending the dataId-0 general-information request after negotiation
            // completes, and must keep getting the same structured 0x3C reply it got the first
            // time, not a bare RidingData frame.
            RideOnSpec.HUB_REQUEST_DATA_ID_GENERAL_INFO -> generalInfoFrame()
            // Every other dataId - including the undocumented 1-7 range and dataId 16, observed
            // on the wire from real Zwift on 2026-08-30 - previously fell back to a bare
            // RidingData notify (telemetryFrame()). That reply is indistinguishable from
            // FitBridge's own ~500ms periodic telemetry ticks, so real Zwift can't tell it was
            // ever answered: it retried dataId 16 three times, ~1s apart, then abandoned the
            // RideOn negotiation for the rest of the session even though CPS/FTMS kept streaming
            // valid, moving telemetry throughout - see hubGeneralInfoReply's KDoc. Every
            // unmapped dataId now gets that same structured 0x3C reply, over indicate, with its
            // own dataId echoed back - matching the channel and frame shape Zwift is waiting on,
            // even though the reply's content is only confirmed correct for dataId 0.
            else -> ZwiftTrainerServerFrame(
                async = false,
                value = RideOnWire.hubGeneralInfoReply(serial = DEVICE_SERIAL, dataId = dataId),
            )
        }
        return ZwiftTrainerServerOutcome(true, frames = listOf(frame))
    }

    private fun handleHubCommand(packet: RideOnWirePacket): ZwiftTrainerServerOutcome {
        val known = packet.fields.filter { it.number in 1..5 }
        if (known.groupBy { it.number }.any { it.value.size > 1 }) return rejected("duplicate HubCommand field")
        if (known.any { field ->
                when (field.number) {
                    3 -> field !is RideOnWireField.Varint
                    4, 5 -> field !is RideOnWireField.Bytes
                    else -> false
                }
            }
        ) return rejected("wrong HubCommand wire type")
        if (known.any { it.number == 1 }) return rejected("direct resistance is unsupported")
        val power = known.singleOrNull { it.number == 3 } as? RideOnWireField.Varint
        val simulation = known.singleOrNull { it.number == 4 } as? RideOnWireField.Bytes
        val physical = known.singleOrNull { it.number == 5 } as? RideOnWireField.Bytes
        if (power != null && simulation != null) return rejected("mixed ERG and simulation command")
        if (power != null) {
            if (power.value !in 0L..2_000L ||
                !model.setTargetPower(power.value.toInt(), KickrControlSource.RIDE_ON)
            ) {
                return rejected("target power out of range")
            }
            return ZwiftTrainerServerOutcome(true)
        }
        if (simulation == null && physical == null) return rejected("empty HubCommand")

        // A real Wahoo KICKR Core session (zwiftplay/zaplibrary's zwift-play-kickr-core.csv
        // HCI-snoop export of Zwift driving an actual trainer) never bundles Simulation and
        // Physical into one full write the way our own RideOnControlSession.submit() does.
        // Instead it sends two independent, partial HubCommands throughout the ride: a bare
        // `Physical{gearRatio}` (opcode 0x04, field 5 only - no Simulation, no mass fields) for
        // every gear shift, and a bare `Simulation{gradePercent}` (field 4 only, with only its
        // gradePercent sub-field set - no wind/Cw/Crr, no Physical) for every grade change as
        // the route's gradient varies. The previous implementation required Simulation and
        // Physical together with all four SimulationParam sub-fields present every time, which
        // would silently reject every one of those - i.e. every gear shift and every grade
        // change a real KICKR Core session actually sends. A bare Physical{gearRatio} is now
        // treated as a gear-shift command (same handling as handleVirtualShift's bare shift);
        // a Simulation with only some sub-fields set is merged onto the model's last known
        // simulation target rather than requiring a full resend of all four every time.
        if (simulation == null) {
            val phy = nested(physical!!.value) ?: return rejected("invalid PhysicalParam")
            val physicalValues = optionalVarints(phy, required = setOf(2), optional = emptySet())
                ?: return rejected("bare Physical command must carry a gear ratio")
            val rawRatio = physicalValues.getValue(2)
            val index = RideOnSpec.GEAR_RATIOS.indexOf(rawRatio.toInt()) + 1
            if (index <= 0 || !model.setVirtualGear(index, rawRatio / 10_000.0)) {
                return rejected("gear ratio is not in the 24-gear table")
            }
            model.markDirectControl(KickrControlSource.RIDE_ON)
            return ZwiftTrainerServerOutcome(true)
        }

        val sim = nested(simulation.value) ?: return rejected("invalid SimulationParam")
        val simValues = optionalVarints(sim, required = emptySet(), optional = setOf(1, 2, 3, 4))
            ?: return rejected("invalid SimulationParam fields")
        if (simValues.isEmpty()) return rejected("empty SimulationParam")

        val physicalValues = physical?.let { bytes ->
            val phy = nested(bytes.value) ?: return rejected("invalid PhysicalParam")
            optionalVarints(phy, required = emptySet(), optional = setOf(2, 4, 5))
                ?: return rejected("invalid PhysicalParam fields")
        }
        val bikeMassRaw = physicalValues?.get(4)
        val riderMassRaw = physicalValues?.get(5)
        if ((bikeMassRaw == null) != (riderMassRaw == null)) {
            return rejected("partial bicycle/rider mass update is not supported")
        }

        val current = model.snapshot().simulation
        val target = runCatching {
            KickrSimulationTarget(
                windMps = simValues[1]?.let { ProtobufVarint.zigZagDecode32(it) / 100.0 } ?: current.windMps,
                gradePercent = simValues[2]?.let { ProtobufVarint.zigZagDecode32(it) / 100.0 }
                    ?: current.gradePercent,
                cw = simValues[3]?.let { it / 10_000.0 } ?: current.cw,
                crr = simValues[4]?.let { it / 100_000.0 } ?: current.crr,
                totalMassKg = if (bikeMassRaw != null && riderMassRaw != null) {
                    (bikeMassRaw + riderMassRaw) / 100.0
                } else {
                    current.totalMassKg
                },
            )
        }.getOrNull() ?: return rejected("invalid signed simulation value")
        if (!model.setSimulation(target, KickrControlSource.RIDE_ON)) {
            return rejected("simulation target out of range")
        }
        // A gear ratio riding along with a simulation command stays part of that simulation
        // write (see KickrSyntheticModel.markDirectControl's KDoc) - it must not flip the
        // control-mode label to RideOn-Direct.
        val gear = physicalValues?.get(2)?.let { rawRatio ->
            val index = RideOnSpec.GEAR_RATIOS.indexOf(rawRatio.toInt()) + 1
            if (index <= 0) return rejected("gear ratio is not in the 24-gear table")
            index to (rawRatio / 10_000.0)
        }
        gear?.let { (index, ratio) -> check(model.setVirtualGear(index, ratio)) }
        return ZwiftTrainerServerOutcome(true)
    }

    /**
     * Real Zwift multiplexes more than one payload shape under opcode 0x3F, not just an actual
     * gear-shift request (nested field 7 = gear ratio). Confirmed against a 2026-08-30 btsnoop
     * capture (Zwift vs CORE_ORIGINAL_ZWIFT / ZWIFT_HUB_CAPTURED): it also periodically pushes a
     * SimulationParam-shaped payload (nested fields 8/10/11/12 - wind/grade/crr/wind-resistance,
     * constant across the whole session) and a third shape carrying nested fields 5/7 where
     * field 7 is fixed at 0 - not a valid entry in the 24-gear table. Requiring the payload to be
     * exactly field 7 alone meant every one of those non-gear-ratio pushes was silently rejected
     * with no reply at all: real Zwift retried each burst three times (~1s apart) and then, in
     * that capture, disconnected roughly 22s after the last unacknowledged burst - a near-exact
     * repeat across three separate connection attempts. Real Zwift clearly expects an ack for
     * every 0x3F regardless of payload shape, so this now always acks (echoing the sequence back)
     * and only applies the "shift gear" side effect when the payload actually decodes to a known
     * gear ratio, instead of rejecting the whole command when it doesn't.
     */
    private fun handleVirtualShift(packet: RideOnWirePacket): ZwiftTrainerServerOutcome {
        val values = optionalVarints(packet, required = setOf(1, 2), optional = emptySet(), bytes = setOf(4))
            ?: return rejected("invalid VirtualShiftCommand fields")
        if (values.getValue(1) != 1L) return rejected("unsupported virtual-shift command type")
        val payload = packet.fields.singleOrNull { it.number == 4 } as? RideOnWireField.Bytes
        val simFields = payload?.let { nested(it.value) }
        val rawRatio = simFields?.let { exactVarints(it, setOf(7))?.get(7) }
        val gearApplied = rawRatio?.let { ratio ->
            val index = RideOnSpec.GEAR_RATIOS.indexOf(ratio.toInt()) + 1
            index > 0 && model.setVirtualGear(index, ratio / 10_000.0)
        } ?: false
        if (gearApplied) {
            // A bare virtual-shift command - no power or simulation target alongside it - is the
            // "gear shift" half of RideOn-Direct (plan: simulator control-mode display).
            model.markDirectControl(KickrControlSource.RIDE_ON)
        }
        // Real Zwift also pushes a SimulationParam-shaped payload nested under this same opcode
        // (see zwift_hub.proto: Windx100=1, InclineX100=2, CWax10000=3, Crrx100000=4 - but the
        // field numbers observed on the wire here are 8/10/11/12, not 1-4, so only the two we've
        // confirmed byte-for-byte against Zwift's documented constants are applied). CWa (field 8,
        // constant 5100 = 0.51) and Crr (field 12, constant 400 = 0.004) are confirmed; fields 10
        // and 11 are unmapped candidates for wind/grade pending a capture with an actual gradient
        // change to disambiguate them, and are intentionally left untouched.
        val simValues = simFields?.let { optionalVarints(it, required = emptySet(), optional = setOf(8, 12)) }
        val cwaRaw = simValues?.get(8)
        val crrRaw = simValues?.get(12)
        if (cwaRaw != null || crrRaw != null) {
            val current = model.snapshot().simulation
            val target = current.copy(
                cw = cwaRaw?.let { it / 10_000.0 } ?: current.cw,
                crr = crrRaw?.let { it / 100_000.0 } ?: current.crr,
            )
            model.setSimulation(target, KickrControlSource.RIDE_ON)
        }
        return ZwiftTrainerServerOutcome(
            true,
            frames = listOf(
                ZwiftTrainerServerFrame(
                    async = false,
                    value = RideOnWire.virtualShiftResponse(values.getValue(2)),
                ),
            ),
        )
    }

    private fun nested(bytes: ByteArray): RideOnWirePacket? =
        ((RideOnWire.decode(byteArrayOf(0x00) + bytes) as? RideOnWireDecodeResult.Decoded)?.packet)

    private fun exactVarints(packet: RideOnWirePacket, fields: Set<Int>): Map<Int, Long>? =
        optionalVarints(packet, required = fields, optional = emptySet())

    private fun optionalVarints(
        packet: RideOnWirePacket,
        required: Set<Int>,
        optional: Set<Int>,
        bytes: Set<Int> = emptySet(),
    ): Map<Int, Long>? {
        val known = packet.fields.filter { it.number in required || it.number in optional || it.number in bytes }
        if (known.groupBy { it.number }.any { it.value.size != 1 }) return null
        if (!required.all { number -> known.any { it.number == number } }) return null
        if (!bytes.all { number -> known.singleOrNull { it.number == number } is RideOnWireField.Bytes }) return null
        if (known.any { it.number !in bytes && it !is RideOnWireField.Varint }) return null
        return known.filterIsInstance<RideOnWireField.Varint>().associate { it.number to it.value }
    }

    private fun rejected(reason: String) = ZwiftTrainerServerOutcome(false, reason = reason)

    private fun isHandshake(value: ByteArray): Boolean {
        if (value.size < HANDSHAKE_MAGIC.size + 1) return false
        if (!value.copyOfRange(0, HANDSHAKE_MAGIC.size).contentEquals(HANDSHAKE_MAGIC)) return false
        val formByte = value[HANDSHAKE_MAGIC.size]
        return formByte == 0x02.toByte() || formByte == 0x03.toByte()
    }

    private companion object {
        // "RideOn" + the fixed 0x02 marker byte that always precedes the form byte. The form
        // byte itself (0x02 or 0x03) and anything after it are validated in isHandshake(), not
        // required to match a specific persona - see the KDoc on handle() for why.
        val HANDSHAKE_MAGIC = "RideOn".toByteArray(Charsets.US_ASCII) + byteArrayOf(0x02)
        val GENERAL_INFORMATION_REQUEST = byteArrayOf(0x00, 0x08, 0x00)

        // FitBridge's own device identity for the general-info/device-info replies - not copied
        // from qdomyos-zwift's literal "KICKR CORE" / real-hardware serial, beyond the device
        // name (this simulator's personas are themselves Wahoo KICKR CORE emulations, so
        // reusing that model name is accurate, not borrowed identity).
        const val DEVICE_NAME = "KICKR CORE"
        const val DEVICE_SERIAL = "FITBRIDGE-SIM01"
    }
}
