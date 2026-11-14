package com.gmail.absolutebollockscreations.gattlibrary.central

/**
 * F65 proprietary UART startup handshake and keep-alive polling.
 *
 * The F65's FTMS stream carries no incline, so incline is only available from the UART's
 * 18-byte workout frames. A captured F65 session shows those frames only after the client runs
 * a setup exchange and keeps acknowledging. The wire sequence follows the QDomyos-Zwift Sole
 * treadmill driver with its F65 and incline options enabled (revision 89f1213e). That sequence
 * matches the bytes in the separately captured F65 HCI sessions. This is an independent
 * implementation of that wire behavior. No QZ code is copied.
 *
 * Each step either waits for the next UART notification (up to [responseTimeoutMs]), waits for
 * a packet without writing, or writes and moves on. After the handshake, two acknowledgement
 * frames are sent every [pollIntervalMs], each waiting for a notification.
 *
 * Safety: this sends no start, stop, speed or incline command (`03 04`, `03 06`, `03 07`,
 * `F1 xx`). It does write the F65 setup records (`07`, `08`, `04`, `22`, `02`) and idle state
 * `03 01`, exactly as QZ does. The console still has to be started by the user.
 *
 * Restart follows QZ exactly. A notification that is exactly a 5-byte state frame
 * (`5B 02 03 xx 5D`, any state) requests a restart, which QZ treats as a stop or pause event.
 * Requests that arrive during the handshake are cleared when it completes, so the console's
 * echoes of the handshake's own state writes do not loop. After the handshake, the next
 * [pollIntervalMs] update runs the full handshake again in place of the poll. A poll already
 * waiting for a reply finishes first, as QZ's blocking writes do. There is no speed or rate
 * guard: QZ re-initializes even while the belt is moving.
 */
internal class SoleF65Session(
    private val responseTimeoutMs: Long = 2_000L,
    private val packetWaitMs: Long = 500L,
    private val pollIntervalMs: Long = 300L,
) {
    enum class Phase { IDLE, HANDSHAKE, POLLING }

    private sealed interface Step {
        /** Write [frame]; if [awaitPacket], wait for the next notification or the timeout. */
        class Write(val frame: ByteArray, val awaitPacket: Boolean) : Step

        /** Write nothing; wait for the next notification or [timeoutMs]. */
        class WaitForPacket(val timeoutMs: Long) : Step
    }

    var phase: Phase = Phase.IDLE
        private set

    /** Number of completed handshakes on this link, including restarts. */
    var handshakesCompleted: Int = 0
        private set

    private var steps: List<Step> = emptyList()
    private var index = 0
    private var waitingSinceMs: Long? = null
    private var waitTimeoutMs = 0L
    private var lastPollStartMs: Long? = null
    private var restartRequested = false

    fun reset() {
        phase = Phase.IDLE
        steps = emptyList()
        index = 0
        waitingSinceMs = null
        lastPollStartMs = null
        restartRequested = false
    }

    /** Begin the handshake after the UART notification CCCD write succeeds. */
    fun start(nowMs: Long): List<ByteArray> {
        phase = Phase.HANDSHAKE
        return run(handshakeSteps, nowMs)
    }

    /**
     * Any UART notification satisfies a pending wait, as QZ does. [value] is the raw notification;
     * QZ matches the state frame against the whole notification, not assembled frames.
     */
    fun onNotification(nowMs: Long, value: ByteArray): List<ByteArray> {
        if (phase == Phase.IDLE) return emptyList()
        if (value.size == 5 && value.octet(0) == 0x5b && value.octet(1) == 0x02 && value.octet(2) == 0x03) {
            restartRequested = true
        }
        if (waitingSinceMs == null) return emptyList()
        waitingSinceMs = null
        index++
        return advance(nowMs)
    }

    fun tick(nowMs: Long): List<ByteArray> {
        val since = waitingSinceMs
        if (since != null) {
            if (nowMs - since < waitTimeoutMs) return emptyList()
            waitingSinceMs = null
            index++
            return advance(nowMs)
        }
        val lastPoll = lastPollStartMs
        if (phase == Phase.POLLING && index >= steps.size && (lastPoll == null || nowMs - lastPoll >= pollIntervalMs)) {
            lastPollStartMs = nowMs
            if (restartRequested) {
                restartRequested = false
                return start(nowMs)
            }
            return run(pollSteps, nowMs)
        }
        return emptyList()
    }

    private fun run(sequence: List<Step>, nowMs: Long): List<ByteArray> {
        steps = sequence
        index = 0
        waitingSinceMs = null
        return advance(nowMs)
    }

    private fun advance(nowMs: Long): List<ByteArray> = buildList {
        while (index < steps.size) {
            when (val step = steps[index]) {
                is Step.Write -> {
                    add(step.frame.copyOf())
                    if (step.awaitPacket) {
                        waitingSinceMs = nowMs
                        waitTimeoutMs = responseTimeoutMs
                        return@buildList
                    }
                    index++
                }
                is Step.WaitForPacket -> {
                    waitingSinceMs = nowMs
                    waitTimeoutMs = step.timeoutMs
                    return@buildList
                }
            }
        }
        if (phase == Phase.HANDSHAKE) {
            phase = Phase.POLLING
            handshakesCompleted++
            lastPollStartMs = nowMs
            // QZ clears its restart request when initialization returns.
            restartRequested = false
        }
    }

    private val handshakeSteps: List<Step> = listOf(
        Step.Write(IDENTIFY, awaitPacket = true),
        Step.WaitForPacket(packetWaitMs),
        Step.WaitForPacket(packetWaitMs),
        Step.Write(ACK_10, true),
        Step.Write(STATE_IDLE, true),
        Step.Write(STATE_IDLE, true),
        Step.Write(STATE_IDLE, true),
        Step.Write(STATE_IDLE, true),
        Step.Write(ACK_10, false),
        Step.Write(STATE_IDLE, true),
        Step.Write(ACK_09, true),
        Step.Write(ACK_10, true),
        Step.Write(USER_PROFILE, true),
        Step.Write(SETUP_08, true),
        Step.Write(SETUP_08, true),
        Step.Write(SETUP_04, true),
        Step.Write(SETUP_22, true),
        Step.Write(SETUP_02, true),
        Step.Write(SETUP_02, true),
        Step.Write(SETUP_02, true),
        Step.Write(SETUP_02, true),
        Step.Write(ACK_10, true),
        Step.Write(ACK_40, true),
    )

    private val pollSteps: List<Step> = listOf(
        Step.Write(ACK_10, awaitPacket = true),
        Step.Write(ACK_06, awaitPacket = true),
    )

    internal companion object {
        private fun frame(hex: String): ByteArray = hex.hexToByteArray()

        val IDENTIFY = frame("5b01f05d")
        val ACK_06 = frame("5b0400064f4b5d")
        val ACK_09 = frame("5b0400094f4b5d")
        val ACK_10 = frame("5b0400104f4b5d")
        val ACK_40 = frame("5b0400404f4b5d")
        val STATE_IDLE = frame("5b0203015d")
        val USER_PROFILE = frame("5b06070123009baa5d")
        val SETUP_08 = frame("5b030810015d")
        val SETUP_04 = frame("5b0504000000005d")
        val SETUP_22 = frame("5b0222095d")
        val SETUP_02 = frame("5b0202025d")

        private fun ByteArray.octet(index: Int): Int = this[index].toInt() and 0xff
    }
}
