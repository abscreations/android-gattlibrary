package com.gmail.absolutebollockscreations.gattlibrary.peripheral

import com.gmail.absolutebollockscreations.gattlibrary.core.FitBridgeBleInternalApi
import com.gmail.absolutebollockscreations.gattlibrary.central.capture.BleCapture
import com.gmail.absolutebollockscreations.gattlibrary.central.capture.CaptureRole
import com.gmail.absolutebollockscreations.gattlibrary.central.capture.CccdEvent
import com.gmail.absolutebollockscreations.gattlibrary.central.capture.ConnectEvent
import com.gmail.absolutebollockscreations.gattlibrary.central.capture.DisconnectEvent
import com.gmail.absolutebollockscreations.gattlibrary.central.capture.NotifyEvent
import com.gmail.absolutebollockscreations.gattlibrary.central.capture.ReadEvent
import com.gmail.absolutebollockscreations.gattlibrary.central.capture.WriteEvent
import java.util.UUID

/**
 * Capture hooks for the **downstream** link — FitBridge as GATT server.
 *
 * ### Why this is a separate file from the central hooks
 *
 * The event shapes are identical but the direction is inverted, and that
 * inversion is the whole reason the roles are split:
 *
 * | Event | Central meaning | Peripheral meaning |
 * |---|---|---|
 * | `notify` | received from the machine | **sent to the client** |
 * | `read` | we read the machine | **the client read us** |
 * | `write` | we wrote to the machine | **the client wrote to us** |
 * | `cccd` | our subscription request | **the client's subscription** |
 *
 * A single file mixing both would be worse than no capture at all: it reads
 * plausibly and means the opposite of what it says on half its lines.
 *
 * ### The gap this closes
 *
 * Until now the capture channel recorded only the central side. A downstream
 * problem — a watch that subscribes, receives data and silently drops — produced
 * a capture containing nothing about the watch. The 2026-08-06 Connect IQ
 * investigation had to infer the downstream framing from an MTU value and a
 * frame-count ratio, because the bytes FitBridge actually sent were recorded
 * nowhere.
 */
@FitBridgeBleInternalApi
object PeripheralCaptureHooks {

    /**
     * A client connected.
     *
     * [mtu] is carried here because on this side it is the single most
     * consequential property of the link: it decides whether a record fits in one
     * notification or is fragmented across several, and a client that never
     * raises it cannot be sent the same bytes as one that does.
     */
    fun clientConnected(address: String, sessionId: Long, mtu: Int) {
        BleCapture.emit(
            ConnectEvent(
                conn = BleCapture.labelFor(address),
                generation = sessionId.toInt(),
                mtu = mtu,
            ),
            CaptureRole.PERIPHERAL,
        )
    }

    fun clientDisconnected(address: String, sessionId: Long, reason: String) {
        BleCapture.emit(
            DisconnectEvent(
                conn = BleCapture.labelFor(address),
                reason = reason,
                generation = sessionId.toInt(),
            ),
            CaptureRole.PERIPHERAL,
        )
    }

    /** A client subscribed or unsubscribed. */
    fun clientCccd(address: String, characteristic: UUID, enabled: Boolean, mode: String) {
        BleCapture.emit(
            CccdEvent(
                conn = BleCapture.labelFor(address),
                characteristic = characteristic.toString(),
                enabled = enabled,
                result = mode,
            ),
            CaptureRole.PERIPHERAL,
        )
    }

    /**
     * One notification or indication **as handed to the stack**.
     *
     * Recorded at the point of posting rather than at encode time, so the capture
     * holds the exact octets that went on the air — including each frame of a
     * fragmented record separately. That is what makes it possible to see whether
     * a client was sent a record it could reassemble.
     */
    fun notified(address: String, characteristic: UUID, payload: ByteArray) {
        BleCapture.emit(
            NotifyEvent(
                conn = BleCapture.labelFor(address),
                characteristic = characteristic.toString(),
                payload = payload.copyOf(),
            ),
            CaptureRole.PERIPHERAL,
        )
    }

    /** A client wrote to us — Control Point requests land here. */
    fun clientWrote(
        address: String,
        characteristic: UUID,
        payload: ByteArray,
        withoutResponse: Boolean,
    ) {
        BleCapture.emit(
            WriteEvent.of(
                conn = BleCapture.labelFor(address),
                characteristic = characteristic,
                payload = payload,
                withoutResponse = withoutResponse,
            ),
            CaptureRole.PERIPHERAL,
        )
    }

    /** A client read one of our characteristics. */
    /**
     * Whether a read at [offset] should produce a capture event.
     *
     * A client fetching a value larger than the ATT payload issues a Read
     * Request followed by Read Blob Requests at increasing offsets. Recording
     * every one would put the same value in the file several times, in pieces,
     * with no marker saying they belong together - so only the first fragment is
     * recorded, and it carries the whole value because the server holds it
     * complete before slicing.
     *
     * Extracted rather than inlined in the GATT callback so the rule is
     * reachable from a test. The callback itself is not.
     */
    fun shouldCaptureRead(offset: Int): Boolean = offset <= 0

    fun clientRead(address: String, characteristic: UUID, payload: ByteArray) {
        BleCapture.emit(
            ReadEvent(
                conn = BleCapture.labelFor(address),
                characteristic = characteristic.toString(),
                payload = payload.copyOf(),
            ),
            CaptureRole.PERIPHERAL,
        )
    }
}
