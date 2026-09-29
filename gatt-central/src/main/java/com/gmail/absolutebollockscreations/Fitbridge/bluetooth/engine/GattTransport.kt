package com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine

import android.Manifest
import android.bluetooth.BluetoothGattCharacteristic
import androidx.annotation.RequiresPermission
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import java.util.UUID

/**
 * Write dispatch mode for [GattTransport.write]. Mirrors [StackEntry.WriteMode] at the
 * transport boundary so protocol code (FE-C over BLE's FEC3, in particular) does not need
 * to depend on the queue's internal entry type to express "this must go without response."
 */
internal enum class GattWriteMode { WITH_RESPONSE, WITHOUT_RESPONSE }

/**
 * Connection-scoped, protocol-neutral wrapper over the existing [GattQueue] machinery.
 *
 * §5.1 of the FE-C over BLE central plan calls for this rather than a second, parallel write
 * path: every write still goes through [BtEngine.pushWrite] and is therefore still serialized
 * behind the one [GattQueue] per physical link, still captured via [CaptureHooks], and still
 * subject to the same stale-generation guards as FTMS/Concept2/Precor/Schwinn x70 writes. What
 * this interface adds is (a) a stable seam a `ProtocolSession` can hold onto without reaching
 * into `BtEngine` internals, and (b) first-class support for write-without-response, which no
 * existing caller needed before FE-C's FEC3 control channel.
 *
 * A transport is only valid for the connection generation it was constructed for. It is not
 * itself responsible for detecting a stale generation - callers (in particular
 * `FeCControlScheduler`) must stop invoking it once their owning `ProtocolSession` is closed.
 */
internal interface GattTransport {
    val connectionId: ConnectionId

    /**
     * Arms notifications on [uuid] (finds the characteristic, verifies it advertises
     * NOTIFY/INDICATE, queues the CCCD write). Returns false only for structural failures
     * knowable before the write is dispatched - characteristic not present on this link, or
     * no GATT currently attached. A queued-but-not-yet-acknowledged CCCD write still returns
     * true; CCCD completion itself is observed the same way every other handler observes it,
     * through [ConnectionContext.cccd].
     */
    fun enableNotifications(uuid: UUID): Boolean

    /**
     * Writes [payload] to [uuid] and suspends until the queue reports completion or
     * [timeoutMs] elapses, whichever comes first.
     *
     * For [GattWriteMode.WITHOUT_RESPONSE], "completion" means the local stack accepted the
     * packet for transmission - there is no protocol acknowledgement to wait for (this is
     * explicitly true for FE-C's FEC3; a dispatched write is not a confirmed one). For
     * [GattWriteMode.WITH_RESPONSE], completion means the peripheral's ATT write response
     * arrived.
     *
     * A timeout returns false without cancelling the underlying queue entry - the entry may
     * still complete later and will still advance the queue and fire [CaptureHooks] normally;
     * the caller has simply stopped waiting on this particular call.
     */
    suspend fun write(
        uuid: UUID,
        payload: ByteArray,
        mode: GattWriteMode,
        timeoutMs: Long,
        operationName: String,
    ): Boolean
}

/** Default production [GattTransport], backed by the shared [BtEngine] GATT queue. */
internal class BtEngineGattTransport(
    private val engine: BtEngine,
    override val connectionId: ConnectionId,
) : GattTransport {

    private fun context(): ConnectionContext? = engine.state.connectionRegistry.contextFor(connectionId)

    private fun findCharacteristic(uuid: UUID): BluetoothGattCharacteristic? {
        val ctx = context() ?: return null
        val gatt = ctx.gatt ?: return null
        val services = try {
            gatt.services
        } catch (_: SecurityException) {
            null
        }.orEmpty()
        for (service in services) {
            service.getCharacteristic(uuid)?.let { return it }
        }
        return null
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    override fun enableNotifications(uuid: UUID): Boolean {
        val ctx = context() ?: run {
            Timber.tag(BtEngineState.TAG).w(
                "GattTransport.enableNotifications: no active context for %s",
                connectionId,
            )
            return false
        }
        val ch = findCharacteristic(uuid) ?: run {
            Timber.tag(BtEngineState.TAG).w(
                "GattTransport.enableNotifications: characteristic %s not found on %s",
                uuid,
                connectionId,
            )
            return false
        }
        engine.enableNotifications(ctx, ch)
        return true
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    override suspend fun write(
        uuid: UUID,
        payload: ByteArray,
        mode: GattWriteMode,
        timeoutMs: Long,
        operationName: String,
    ): Boolean {
        val ctx = context() ?: run {
            Timber.tag(BtEngineState.TAG).w(
                "GattTransport.write(%s): no active context for %s",
                operationName,
                connectionId,
            )
            return false
        }
        val queueMode = when (mode) {
            GattWriteMode.WITH_RESPONSE -> StackEntry.WriteMode.WITH_RESPONSE
            GattWriteMode.WITHOUT_RESPONSE -> StackEntry.WriteMode.WITHOUT_RESPONSE
        }
        val completion = CompletableDeferred<Boolean>()
        // Positional for (ctx, payload, uuid): pushWrite is overloaded with a legacy
        // (context, uuid, bytes) form sharing the same parameter names in swapped order,
        // so a fully-named call here would be genuinely ambiguous between the two. Position
        // + type (ByteArray then UUID) picks the (bytes, uuid) overload unambiguously.
        engine.pushWrite(
            ctx,
            payload,
            uuid,
            writeMode = queueMode,
            onComplete = { ok: Boolean -> completion.complete(ok) },
        )
        val result = withTimeoutOrNull(timeoutMs) { completion.await() }
        if (result == null) {
            Timber.tag(BtEngineState.TAG).w(
                "GattTransport.write(%s): timed out after %dms uuid=%s connection=%s",
                operationName,
                timeoutMs,
                uuid,
                connectionId,
            )
            return false
        }
        return result
    }
}
