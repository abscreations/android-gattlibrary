package com.gmail.absolutebollockscreations.Fitbridge.bluetooth.peripheral

/**
 * Pure FTMS Control Point decoder and per-connection procedure state machine.
 * Transport and indication ordering live in [FtmsPeripheralAdvertiser].
 *
 * This class deliberately returns a description of the side effects instead of
 * sending anything itself. The advertiser must first complete the ATT Write
 * Response, then indicate [Outcome.response], wait for confirmation, and only
 * then publish optional machine/training status. Keeping that sequencing at the
 * transport boundary prevents a fast client from observing state before its
 * procedure result has been confirmed.
 *
 * Behavioural corrections against the old `BtServiceControlPoint`:
 *  - Operand lengths are validated exactly, not with `size >= n`.
 *  - Target values are range-checked against the machine's own Supported-* range
 *    before the write is forwarded.
 *  - Reset no longer revokes control (FTMS Reset resets the *machine*, not the
 *    control ownership) and no longer reports success unconditionally.
 *  - Request Control is idempotent, so a client repeating it does not
 *    re-acquire the physical machine.
 *  - Request Control no longer emits a STARTED_OR_RESUMED machine status.
 *  - Start/Resume on an already-running session, and Stop/Pause on a session
 *    that is not running, are rejected instead of silently re-issued upstream.
 *  - Set Target Heart Rate and Set Indoor Bike Simulation are decoded rather
 *    than blanket-refused, so they work the moment a downstream path exists.
 */
class FtmsControlPointHandler(
    private val sink: FtmsControlSink,
) {

    /**
     * Result of one decoded procedure.
     *
     * [status] and [trainingStatusChanged] are follow-up publications, not part
     * of the Control Point response. [resetMetrics] tells the controller to clear
     * its snapshot only after the reset command has been accepted downstream.
     */
    class Outcome(
        val response: ByteArray,
        val status: ByteArray? = null,
        val trainingStatusChanged: Boolean = false,
        val resetMetrics: Boolean = false,
    )

    fun operationFailed(value: ByteArray): Outcome = Outcome(
        response(value.firstOrNull() ?: 0xFF.toByte(), FtmsSpec.Result.OPERATION_FAILED),
    )

    fun handle(value: ByteArray, session: FtmsProcedureSession): Outcome {
        if (value.isEmpty()) {
            return Outcome(response(0xFF.toByte(), FtmsSpec.Result.OPCODE_NOT_SUPPORTED))
        }

        val op = value[0]
        val o = FtmsSpec.Opcode
        val r = FtmsSpec.Result

        if (op == o.REQUEST_CONTROL) {
            if (!hasExactLength(op, value)) return Outcome(response(op, r.INVALID_PARAMETER))
            // Request Control is idempotent for this connection. Repeating it
            // must not reacquire the physical machine or disturb accepted state.
            if (session.hasControl) return Outcome(response(op, r.SUCCESS))
            return if (sink.requestControl()) {
                session.hasControl = true
                Outcome(response(op, r.SUCCESS))
            } else {
                Outcome(response(op, r.CONTROL_NOT_PERMITTED))
            }
        }

        if (!session.hasControl) {
            return Outcome(response(op, r.CONTROL_NOT_PERMITTED))
        }

        return when (op) {
            o.RESET -> {
                if (!hasExactLength(op, value)) return Outcome(response(op, r.INVALID_PARAMETER))
                if (!sink.reset()) return Outcome(response(op, r.OPERATION_FAILED))
                val changed =
                    session.operationalState != FtmsProcedureSession.OperationalState.IDLE
                session.resetByProcedure()
                Outcome(
                    response(op, r.SUCCESS),
                    status = byteArrayOf(FtmsSpec.Status.RESET),
                    trainingStatusChanged = changed,
                    resetMetrics = true,
                )
            }

            o.START_RESUME -> {
                if (!hasExactLength(op, value)) return Outcome(response(op, r.INVALID_PARAMETER))
                if (session.operationalState == FtmsProcedureSession.OperationalState.RUNNING) {
                    return Outcome(response(op, r.OPERATION_FAILED))
                }
                if (!sink.startOrResume()) return Outcome(response(op, r.OPERATION_FAILED))
                session.operationalState = FtmsProcedureSession.OperationalState.RUNNING
                Outcome(
                    response(op, r.SUCCESS),
                    status = byteArrayOf(FtmsSpec.Status.STARTED_OR_RESUMED_BY_USER),
                    trainingStatusChanged = true,
                )
            }

            o.STOP_PAUSE -> {
                if (!hasExactLength(op, value)) return Outcome(response(op, r.INVALID_PARAMETER))
                val param = value[1].toInt() and 0xFF
                if (param != 1 && param != 2) return Outcome(response(op, r.INVALID_PARAMETER))
                if (session.operationalState != FtmsProcedureSession.OperationalState.RUNNING) {
                    return Outcome(response(op, r.OPERATION_FAILED))
                }
                val stop = param == 1
                if (!sink.stopOrPause(stop)) return Outcome(response(op, r.OPERATION_FAILED))
                session.operationalState = if (stop) {
                    FtmsProcedureSession.OperationalState.STOPPED
                } else {
                    FtmsProcedureSession.OperationalState.PAUSED
                }
                Outcome(
                    response(op, r.SUCCESS),
                    status = byteArrayOf(
                        FtmsSpec.Status.STOPPED_OR_PAUSED_BY_USER,
                        param.toByte(),
                    ),
                    trainingStatusChanged = true,
                )
            }

            o.SET_TARGET_SPEED -> target(
                op, value, sink.targetCapabilities.supportsSpeed,
                sink.targetCapabilities.speedRange,
                FtmsSpec.Status.TARGET_SPEED_CHANGED,
                rawValue = { u16(value, 1) },
                statusOperand = { u16le(it) },
            ) { raw ->
                val t = raw!! / 100.0
                if (!sink.setTargetSpeedKph(t)) return@target false
                session.acceptedTargets.speedKph = t
                true
            }

            o.SET_TARGET_INCLINATION -> target(
                op, value, sink.targetCapabilities.supportsInclination,
                sink.targetCapabilities.inclinationRange,
                FtmsSpec.Status.TARGET_INCLINE_CHANGED,
                rawValue = { s16(value, 1) },
                statusOperand = { s16le(it) },
            ) { raw ->
                val t = raw!! / 10.0
                if (!sink.setTargetInclinationPercent(t)) return@target false
                session.acceptedTargets.inclinationPercent = t
                true
            }

            o.SET_TARGET_RESISTANCE -> target(
                op, value, sink.targetCapabilities.supportsResistance,
                sink.targetCapabilities.resistanceRange,
                FtmsSpec.Status.TARGET_RESISTANCE_CHANGED,
                rawValue = {
                    if (value.size == LEGACY_RESISTANCE_PROCEDURE_LENGTH) {
                        value[1].toInt() and 0xFF
                    } else {
                        s16(value, 1)
                    }
                },
                // FTMS permits the legacy UINT8 form and the newer SINT16 form.
                // Preserve legacy status framing when the normalized value fits;
                // SINT16 is required for the simulator's advertised 30.0 maximum.
                statusOperand = {
                    if (value.size == LEGACY_RESISTANCE_PROCEDURE_LENGTH && it <= 0xFF) {
                        u8(it)
                    } else {
                        s16le(it)
                    }
                },
            ) { raw ->
                val t = raw!! / 10.0
                if (!sink.setTargetResistance(t)) return@target false
                session.acceptedTargets.resistance = t
                true
            }

            o.SET_TARGET_POWER -> target(
                op, value, sink.targetCapabilities.supportsPower,
                sink.targetCapabilities.powerRange,
                FtmsSpec.Status.TARGET_POWER_CHANGED,
                rawValue = { s16(value, 1) },
                statusOperand = { s16le(it) },
            ) { raw ->
                val t = raw!!
                if (!sink.setTargetPowerWatts(t)) return@target false
                session.acceptedTargets.powerWatts = t
                true
            }

            o.SET_TARGET_HEART_RATE -> target(
                op, value, sink.targetCapabilities.supportsHeartRate,
                sink.targetCapabilities.heartRateRange,
                FtmsSpec.Status.TARGET_HEART_RATE_CHANGED,
                rawValue = { value[1].toInt() and 0xFF },
                statusOperand = { u8(it) },
            ) { raw ->
                val t = raw!!
                if (!sink.setTargetHeartRate(t)) return@target false
                session.acceptedTargets.heartRateBpm = t
                true
            }

            o.SET_INDOOR_BIKE_SIMULATION -> target(
                op, value, sink.targetCapabilities.indoorBikeSimulation,
                null,
                FtmsSpec.Status.INDOOR_BIKE_SIMULATION_PARAMETERS_CHANGED,
                rawValue = null,
                statusOperand = null,
            ) {
                val t = FtmsProcedureSession.IndoorBikeSimulation(
                    windMps = s16(value, 1) / 1000.0,
                    gradePercent = s16(value, 3) / 100.0,
                    crr = (value[5].toInt() and 0xFF) / 10000.0,
                    cw = (value[6].toInt() and 0xFF) / 100.0,
                )
                if (!sink.setIndoorBikeSimulation(t.windMps, t.gradePercent, t.crr, t.cw)) {
                    return@target false
                }
                session.acceptedTargets.indoorBikeSimulation = t
                true
            }

            else -> Outcome(response(op, r.OPCODE_NOT_SUPPORTED))
        }
    }

    private fun target(
        op: Byte,
        value: ByteArray,
        supported: Boolean,
        range: FtmsTargetRange?,
        statusOpcode: Byte,
        rawValue: (() -> Int)?,
        statusOperand: ((Int) -> ByteArray)?,
        apply: (Int?) -> Boolean,
    ): Outcome {
        // Validate capability and wire shape before touching the sink. Values
        // are normalized in wire units from the same advertised range used by
        // the GATT characteristic, then converted to public units by the caller.
        if (!sink.canControl || !supported) {
            return Outcome(response(op, FtmsSpec.Result.OPCODE_NOT_SUPPORTED))
        }
        if (!hasExactLength(op, value)) {
            return Outcome(response(op, FtmsSpec.Result.INVALID_PARAMETER))
        }
        val normalizedRaw = if (rawValue == null) {
            null
        } else {
            val targetRange = range
                ?: return Outcome(response(op, FtmsSpec.Result.OPCODE_NOT_SUPPORTED))
            val requested = rawValue()
            if (sink.clampsTargetsToAdvertisedRange) {
                targetRange.clampAndSnap(requested)
            } else if (targetRange.contains(requested)) {
                requested
            } else {
                return Outcome(response(op, FtmsSpec.Result.INVALID_PARAMETER))
            }
        }
        return if (apply(normalizedRaw)) {
            val status = if (sink.reportsTargetChangesAsynchronously) {
                null
            } else {
                val operand = if (normalizedRaw == null) {
                    value.copyOfRange(1, value.size)
                } else {
                    statusOperand?.invoke(normalizedRaw)
                        ?: return Outcome(response(op, FtmsSpec.Result.OPERATION_FAILED))
                }
                byteArrayOf(statusOpcode) + operand
            }
            Outcome(response(op, FtmsSpec.Result.SUCCESS), status = status)
        } else {
            Outcome(response(op, FtmsSpec.Result.OPERATION_FAILED))
        }
    }

    private fun hasExactLength(op: Byte, value: ByteArray): Boolean =
        if (op == FtmsSpec.Opcode.SET_TARGET_RESISTANCE) {
            value.size == LEGACY_RESISTANCE_PROCEDURE_LENGTH ||
                value.size == SINT16_RESISTANCE_PROCEDURE_LENGTH
        } else {
            FtmsControlPointSchema.expectedLength(op) == value.size
        }

    private fun response(requestOp: Byte, result: Byte): ByteArray =
        byteArrayOf(FtmsSpec.Opcode.RESPONSE_CODE, requestOp, result)

    private fun u16(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)

    private fun s16(b: ByteArray, off: Int): Int = u16(b, off).toShort().toInt()

    companion object {
        private const val LEGACY_RESISTANCE_PROCEDURE_LENGTH = 2
        private const val SINT16_RESISTANCE_PROCEDURE_LENGTH = 3

        fun opcodeName(op: Int): String = when (op.toByte()) {
            FtmsSpec.Opcode.REQUEST_CONTROL -> "REQUEST_CONTROL"
            FtmsSpec.Opcode.RESET -> "RESET"
            FtmsSpec.Opcode.SET_TARGET_SPEED -> "SET_TARGET_SPEED"
            FtmsSpec.Opcode.SET_TARGET_INCLINATION -> "SET_TARGET_INCLINATION"
            FtmsSpec.Opcode.SET_TARGET_RESISTANCE -> "SET_TARGET_RESISTANCE"
            FtmsSpec.Opcode.SET_TARGET_POWER -> "SET_TARGET_POWER"
            FtmsSpec.Opcode.SET_TARGET_HEART_RATE -> "SET_TARGET_HEART_RATE"
            FtmsSpec.Opcode.START_RESUME -> "START_RESUME"
            FtmsSpec.Opcode.STOP_PAUSE -> "STOP_PAUSE"
            FtmsSpec.Opcode.SET_INDOOR_BIKE_SIMULATION -> "SET_INDOOR_BIKE_SIMULATION"
            FtmsSpec.Opcode.SET_WHEEL_CIRCUMFERENCE -> "SET_WHEEL_CIRCUMFERENCE"
            FtmsSpec.Opcode.SPIN_DOWN_CONTROL -> "SPIN_DOWN_CONTROL"
            FtmsSpec.Opcode.SET_TARGETED_CADENCE -> "SET_TARGETED_CADENCE"
            else -> "UNKNOWN(0x%02X)".format(op)
        }

        fun resultName(res: Int): String = when (res.toByte()) {
            FtmsSpec.Result.SUCCESS -> "SUCCESS"
            FtmsSpec.Result.OPCODE_NOT_SUPPORTED -> "OPCODE_NOT_SUPPORTED"
            FtmsSpec.Result.INVALID_PARAMETER -> "INVALID_PARAMETER"
            FtmsSpec.Result.OPERATION_FAILED -> "OPERATION_FAILED"
            FtmsSpec.Result.CONTROL_NOT_PERMITTED -> "CONTROL_NOT_PERMITTED"
            else -> "UNKNOWN(0x%02X)".format(res)
        }
    }
}
