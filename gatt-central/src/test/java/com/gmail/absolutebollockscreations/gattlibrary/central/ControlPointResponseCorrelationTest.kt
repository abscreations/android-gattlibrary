package com.gmail.absolutebollockscreations.gattlibrary.central

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Plan §11.7 — Control Point request/response correlation, upstream.
 *
 * Correlation had **no coverage at all** before this: the logic lived inside a private handler
 * that needs a live `BluetoothGatt`, so nothing could reach it. That is not an incidental gap —
 * it is how `0x11` came to be sent without a response branch. The write was added, its response
 * fell through an inexhaustive `when`, and no test could have noticed.
 *
 * `correlateControlPointResponse` was extracted for that reason, following the
 * `applyFitnessMachineStatus` precedent: it decides, the handler applies GATT-shaped
 * consequences.
 *
 * ## Why correlation is per frame rather than per queue slot
 *
 * FTMS responses name the opcode they answer. A machine that replies out of order, or replies
 * to something FitBridge never sent, is interpreted against the opcode in the frame — never
 * against whatever happens to be at the head of the write queue.
 */
class ControlPointResponseCorrelationTest {

    private fun response(requestOpcode: Byte, result: Byte): ByteArray =
        byteArrayOf(BluetoothIds.OPCODE_RESPONSE_CODE, requestOpcode, result)

    // ── The gap that let 0x11 ship unhandled ─────────────────────────────────────

    @Test
    fun `a simulation response is correlated rather than ignored`() {
        val accepted = correlateControlPointResponse(
            response(BluetoothIds.OPCODE_SET_INDOOR_BIKE_SIMULATION, BluetoothIds.RESULT_SUCCESS),
        )
        assertEquals(
            CpResponseOutcome.Accepted(BluetoothIds.OPCODE_SET_INDOOR_BIKE_SIMULATION),
            accepted,
        )
    }

    @Test
    fun `a rejected simulation write surfaces an error and keeps control`() {
        val outcome = correlateControlPointResponse(
            response(
                BluetoothIds.OPCODE_SET_INDOOR_BIKE_SIMULATION,
                BluetoothIds.RESULT_INVALID_PARAMETER,
            ),
        )

        assertTrue("a rejected gradient must not be reported as accepted", outcome is CpResponseOutcome.Rejected)
        outcome as CpResponseOutcome.Rejected
        assertEquals(BluetoothIds.OPCODE_SET_INDOOR_BIKE_SIMULATION, outcome.requestOpcode)
        assertTrue(outcome.detail.contains("INVALID_PARAMETER"))
    }

    @Test
    fun `a simulation write refused for permission loses control`() {
        // This is the case that was silently dropped: with no branch for 0x11, a machine
        // revoking control during a ride left FitBridge believing it still held it.
        val outcome = correlateControlPointResponse(
            response(
                BluetoothIds.OPCODE_SET_INDOOR_BIKE_SIMULATION,
                BluetoothIds.RESULT_CONTROL_NOT_PERMITTED,
            ),
        )

        assertTrue(outcome is CpResponseOutcome.ControlLost)
    }

    // ── Correlation across opcodes ───────────────────────────────────────────────

    @Test
    fun `every opcode FitBridge writes has a response branch`() {
        // The registry of opcodes FitBridge can send. Any addition here without a matching
        // correlation branch reproduces exactly the 0x11 defect, so the list is asserted
        // rather than assumed.
        val written = listOf(
            BluetoothIds.OPCODE_REQUEST_CONTROL,
            BluetoothIds.OPCODE_RESET,
            BluetoothIds.OPCODE_START_RESUME,
            BluetoothIds.OPCODE_STOP_PAUSE,
            BluetoothIds.OPCODE_SET_TARGET_SPEED,
            BluetoothIds.OPCODE_SET_TARGET_INCLINATION,
            BluetoothIds.OPCODE_SET_TARGET_RESISTANCE,
            BluetoothIds.OPCODE_SET_TARGET_POWER,
            BluetoothIds.OPCODE_SET_INDOOR_BIKE_SIMULATION,
        )

        for (opcode in written) {
            val outcome = correlateControlPointResponse(
                response(opcode, BluetoothIds.RESULT_SUCCESS),
            )
            assertTrue(
                "0x%02X has no response branch - a write without one fails silently"
                    .format(opcode),
                outcome !is CpResponseOutcome.Unhandled,
            )
        }
    }

    @Test
    fun `a response for an opcode FitBridge never sends is reported as unhandled`() {
        // Spin-down is defined and deliberately not implemented. If a machine answers one, the
        // right behaviour is a loud "unhandled", not silent success.
        val outcome = correlateControlPointResponse(
            response(BluetoothIds.OPCODE_SPIN_DOWN_CONTROL, BluetoothIds.RESULT_SUCCESS),
        )

        assertTrue(outcome is CpResponseOutcome.Unhandled)
    }

    @Test
    fun `a response is matched to the opcode it names, not to the last write`() {
        // Out-of-order delivery: the frame names 0x05, so it is a power response regardless of
        // what was written most recently.
        val outcome = correlateControlPointResponse(
            response(BluetoothIds.OPCODE_SET_TARGET_POWER, BluetoothIds.RESULT_SUCCESS),
        )

        assertEquals(CpResponseOutcome.Accepted(BluetoothIds.OPCODE_SET_TARGET_POWER), outcome)
    }

    // ── Request Control is special ───────────────────────────────────────────────

    @Test
    fun `any failure to request control is a loss of control, not a rejection`() {
        for (result in listOf(
            BluetoothIds.RESULT_CONTROL_NOT_PERMITTED,
            BluetoothIds.RESULT_OPCODE_NOT_SUPPORTED,
            BluetoothIds.RESULT_OPERATION_FAILED,
            BluetoothIds.RESULT_INVALID_PARAMETER,
        )) {
            val outcome = correlateControlPointResponse(
                response(BluetoothIds.OPCODE_REQUEST_CONTROL, result),
            )
            assertTrue(
                "treating a failed control request as a mere rejection would leave the " +
                    "session believing it controls a machine that never granted it",
                outcome is CpResponseOutcome.ControlLost,
            )
        }
    }

    @Test
    fun `granted control is accepted`() {
        assertEquals(
            CpResponseOutcome.Accepted(BluetoothIds.OPCODE_REQUEST_CONTROL),
            correlateControlPointResponse(
                response(BluetoothIds.OPCODE_REQUEST_CONTROL, BluetoothIds.RESULT_SUCCESS),
            ),
        )
    }

    // ── Malformed frames ─────────────────────────────────────────────────────────

    @Test
    fun `short and mis-headed frames are rejected as malformed`() {
        for (frame in listOf(
            byteArrayOf(),
            byteArrayOf(BluetoothIds.OPCODE_RESPONSE_CODE),
            byteArrayOf(BluetoothIds.OPCODE_RESPONSE_CODE, BluetoothIds.OPCODE_SET_TARGET_POWER),
        )) {
            assertTrue(
                "a frame too short to name an opcode cannot be correlated",
                correlateControlPointResponse(frame) is CpResponseOutcome.Malformed,
            )
        }

        // A data frame arriving on the control point: the header is not 0x80.
        assertTrue(
            correlateControlPointResponse(byteArrayOf(0x01, 0x02, 0x03))
                is CpResponseOutcome.Malformed,
        )
    }

    @Test
    fun `an unknown result code is a rejection rather than an acceptance`() {
        val outcome = correlateControlPointResponse(
            response(BluetoothIds.OPCODE_SET_TARGET_POWER, 0x7F),
        )

        assertTrue(
            "an unrecognised result must never be read as success",
            outcome is CpResponseOutcome.Rejected,
        )
        assertTrue((outcome as CpResponseOutcome.Rejected).detail.contains("UNKNOWN"))
    }
}
