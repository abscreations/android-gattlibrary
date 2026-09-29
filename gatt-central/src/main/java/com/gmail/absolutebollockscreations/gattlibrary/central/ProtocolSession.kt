package com.gmail.absolutebollockscreations.gattlibrary.central

import com.gmail.absolutebollockscreations.gattlibrary.core.GattLibraryInternalApi
import java.util.UUID

/**
 * Link-generation-scoped protocol state owner, per plan §5.2.
 *
 * A `ProtocolSession` holds whatever a protocol needs to remember across notifications on
 * one physical connection generation - rollover accumulators, last-seen product info,
 * control-mode/target state, an optional periodic-write scheduler. It must NOT hold a
 * `BluetoothGatt`, Android characteristic objects, global `BtEngineState`, UI state, or
 * another protocol's data (CPS/CSC/HRM parsing stays in their own handlers). The
 * `BtEngineGattCore.ProtocolHandler` that owns a session is responsible for constructing it
 * fresh per connection generation and calling [close] when that generation ends, so state
 * never survives a reconnect by accident.
 */
@GattLibraryInternalApi
interface ProtocolSession {
    val connectionId: ConnectionId
    val protocolId: String

    /** One inbound characteristic payload, e.g. a FEC2 notification. */
    fun onPacket(characteristicUuid: UUID, payload: ByteArray, arrivalMs: Long)

    /** Fired once this session's required characteristics have been subscribed. */
    fun onSubscriptionsReady(nowMs: Long)

    /** Periodic tick, for sessions that own a write-cadence scheduler (e.g. FE-C control). */
    fun onTick(nowMs: Long)

    /** Ends this session's lifetime: cancels any cadence, discards accumulated state. */
    fun close()
}
