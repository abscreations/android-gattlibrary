/*  ┏━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━┓
 *  ┃ btEngine.GattQueue.kt — Serialized GATT I/O + CCCD Retry & Recovery Layer  ┃
 *  ┗━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━┛
 *
 *  PURPOSE
 *  - Centralized, serialized queue for all GATT operations issued by BtEngine:
 *      • Characteristic READs
 *      • Characteristic WRITE (write-with-response)
 *      • Descriptor WRITE (primarily CCCDs)
 *  - Normalizes behaviour across API levels (pre-33 vs 33+) and handles:
 *      • Immediate return codes (BluetoothStatusCodes on API 33+)
 *      • Legacy boolean-return APIs (< 33)
 *      • SecurityException safety around all GATT calls
 *  - Adds robust CCCD handling: retries, bonding, and merged-plan re-arming
 *    flows when notification/indication enablement fails, including **re-bonding
 *    even when the OS reports the device as already bonded** (to recover from
 *    stale/invalid bonds).
 *
 *  KEY FEATURES
 *  - Queue model:
 *      • [StackEntry] defines a small command object (Op + UUID/payload/descriptor).
 *      • BtEngine exposes helpers:
 *          - pushRead(context, uuid)
 *          - pushWrite(context, bytes, uuid)
 *          - pushDescriptorWrite(context, descriptor, payload)
 *      • Each ConnectionContext owns an independent GattQueue.
 *      • handleStack(context) pulls that link's head, dispatches it, and
 *        leaves completion to the corresponding BluetoothGattCallback, which must
 *        present matching GATT, operation, and UUID identity before the head is popped.
 *
 *  - Descriptor / CCCD writes:
 *      • API 33+:
 *          - Uses writeDescriptor(desc, payload) and inspects the returned
 *            BluetoothStatusCodes value.
 *          - On non-SUCCESS, immediately falls back once to the legacy path
 *            (desc.value + writeDescriptor(desc)).
 *      • Pre-33:
 *          - Uses legacy desc.value + writeDescriptor(desc) and, on failure,
 *            retries once after a short delay using a background IO coroutine.
 *      • CCCD plans from all handlers are merged by context and queued via
 *        enableNotifications(), which:
 *          - Validates notify/indicate capability.
 *          - Calls setCharacteristicNotification(..., true).
 *          - Locates the CCCD descriptor (UUID_CCCD).
 *          - Increments context.cccd.pendingWrites and enqueues a DESC_WRITE entry.
 *
 *  - CCCD recovery / bonding workflow:
 *      • On CCCD write failure, handleCccdWriteFailureRecovery(...) is invoked:
 *          - Consumes the failed queue head and clears writeBusy (pauses queue).
 *          - Uses the owning context's recovery counter and caps retries at
 *            MAX_CCCD_RECOVERY_ATTEMPTS.
 *          - On **unbonded** devices:
 *              → attempts a bond via ensureBondOrPrompt(...).
 *          - On **bonded** devices:
 *              → first failure: re-run enable plan without re-bonding.
 *              → second failure: treat as stale bond and call ensureBondOrPrompt(...)
 *                to (re)bond even though the OS reports BOND_BONDED.
 *          - In a background IO coroutine:
 *              1. Perform (re)bonding if needed.
 *              2. Brief delay to allow pairing/encryption to settle.
 *              3. Re-arms the context's complete merged CCCD plan.
 *              4. Waits (with timeout) for context.cccd.ready==true. Protocol-specific
 *                 follow-up is delivered by the callback's plan-ready lifecycle hook.
 *              5. Finally resumes that context's queue.
 *      • On any matching successful descriptor write, callback completion clears
 *        the owning context's recovery counter.
 *
 *  - GATT routing helpers:
 *      • BluetoothGatt.getServiceFor(charUuid) performs a best-effort lookup of
 *        the owning service for a given characteristic UUID.
 *      • gattLabel(...) is used purely for logging, distinguishing FTMS vs HRM
 *        vs generic GATT instances.
 *
 *  INTEGRATION
 *  - BtEngineGattCore:
 *      • Calls queueSucceed()/queueFail() from characteristic/descriptor callbacks
 *        to advance the queue.
 *  - ConnectionContext:
 *      • Owns queue, busy, pending CCCD, readiness, and recovery state.
 *  - BtEngine:
 *      • Provides context and bonding helper (ensureBondOrPrompt) used by recovery.
 */

package com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothStatusCodes
import android.os.Build
import androidx.annotation.RequiresPermission
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.core.FitBridgeBleInternalApi
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine.BluetoothIds.UUID_CCCD
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import java.util.UUID

/* ────────────────────────────────────────────────────────────────────────────── */
/* Internal state for recovery attempts                                          */
/* ────────────────────────────────────────────────────────────────────────────── */

internal const val MAX_CCCD_RECOVERY_ATTEMPTS = 2

// Simple IO scope for background retries in the queue
private val queueIoScope = CoroutineScope(Dispatchers.IO)

/* ────────────────────────────────────────────────────────────────────────────── */
/* Queue entry + push helpers                                                    */
/* ────────────────────────────────────────────────────────────────────────────── */

@FitBridgeBleInternalApi
data class StackEntry(
    val op: Op,
    val charUuid: UUID? = null,
    val data: ByteArray? = null,
    val descriptor: BluetoothGattDescriptor? = null,
    val descriptorUuid: UUID? = descriptor?.uuid,
    /** Testable identity token; production descriptor entries use the descriptor instance. */
    val descriptorIdentity: Any? = descriptor,
    /** WRITE only; ignored for READ/DESC_WRITE, which are always with-response by protocol. */
    val writeMode: WriteMode = WriteMode.WITH_RESPONSE,
    /**
     * Optional per-entry completion signal, invoked exactly once with true (success) or
     * false (failed) when this entry leaves the queue head - by GATT callback, immediate
     * local completion (write-without-response has no protocol ACK to wait for), or
     * failure. This is the single completion path the queue produces; GattTransport
     * builds its bounded await on top of it rather than adding a second one.
     */
    val onComplete: ((Boolean) -> Unit)? = null,
) {
    enum class Op { READ, WRITE, DESC_WRITE }

    /** Dispatch mode for a WRITE entry. FE-C's FEC3 control channel requires WITHOUT_RESPONSE. */
    enum class WriteMode { WITH_RESPONSE, WITHOUT_RESPONSE }
}

@FitBridgeBleInternalApi
fun <T> queueCompletionMatches(
    contextGatt: T?,
    callbackGatt: T,
    head: StackEntry?,
    callbackOp: StackEntry.Op,
    callbackCharUuid: UUID?,
    callbackDescriptorUuid: UUID? = null,
    callbackDescriptorIdentity: Any? = null,
): Boolean {
    if (contextGatt !== callbackGatt || head == null || head.op != callbackOp) return false
    if (head.charUuid != callbackCharUuid) return false
    if (callbackOp != StackEntry.Op.DESC_WRITE) return true
    if (head.descriptorUuid != callbackDescriptorUuid) return false
    return callbackDescriptorIdentity == null ||
        head.descriptorIdentity === callbackDescriptorIdentity
}

/** Typed origin for diagnostics; every form enters the same recovery coordinator. */
internal sealed interface CccdWriteFailureCause {
    val detail: String

    data class Api33Dispatch(val status: Int) : CccdWriteFailureCause {
        override val detail: String = "dispatch-api33-status=$status"
    }

    data object LegacyDispatchRejected : CccdWriteFailureCause {
        override val detail: String = "dispatch-legacy-rejected"
    }

    data class Callback(val status: Int) : CccdWriteFailureCause {
        override val detail: String = "callback-status=$status"
    }
}

/** Identity and generation ownership check used before and after every delayed recovery step. */
internal fun <T> cccdRecoveryStillOwned(
    contextGatt: T?,
    recoveryGatt: T,
    contextActive: Boolean,
): Boolean = contextActive && contextGatt === recoveryGatt

/** Match and consume a failed descriptor head exactly once without draining later work. */
internal fun <T> consumeMatchedDescriptorFailure(
    contextGatt: T?,
    callbackGatt: T,
    queue: GattQueue,
    callbackCharUuid: UUID?,
    callbackDescriptorUuid: UUID?,
    callbackDescriptorIdentity: Any?,
): StackEntry? {
    val head = queue.head()
    if (!queue.isBusy || !queueCompletionMatches(
            contextGatt,
            callbackGatt,
            head,
            StackEntry.Op.DESC_WRITE,
            callbackCharUuid,
            callbackDescriptorUuid,
            callbackDescriptorIdentity,
        )
    ) return null
    return queue.finishIfHead(head!!)
}

/** `BluetoothGatt.readCharacteristic` reports request acceptance as Boolean on every API level. */
internal fun characteristicReadAccepted(accepted: Boolean): Boolean = accepted

@RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
internal fun BtEngine.pushRead(context: ConnectionContext, uuid: UUID) {
    val size = context.queue.enqueue(
        StackEntry(
            StackEntry.Op.READ,
            charUuid = uuid,
        )
    )
    Timber.tag(BtEngineState.TAG).d(
        "Queue READ %s size=%d context=%s",
        uuid,
        size,
        context.connectionId,
    )
    handleStack(context)
}

@RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
internal fun BtEngine.pushWrite(
    context: ConnectionContext,
    bytes: ByteArray,
    uuid: UUID,
    writeMode: StackEntry.WriteMode = StackEntry.WriteMode.WITH_RESPONSE,
    onComplete: ((Boolean) -> Unit)? = null,
) {
    val size = context.queue.enqueue(
        StackEntry(
            StackEntry.Op.WRITE,
            charUuid = uuid,
            data = bytes,
            writeMode = writeMode,
            onComplete = onComplete,
        )
    )
    Timber.tag(BtEngineState.TAG).d(
        "Queue WRITE %s len=%d size=%d mode=%s context=%s",
        uuid,
        bytes.size,
        size,
        writeMode,
        context.connectionId,
    )
    handleStack(context)
}

// Pre-API 33 overload
@RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
internal fun BtEngine.pushWrite(
    context: ConnectionContext,
    uuid: UUID,
    bytes: ByteArray,
    writeMode: StackEntry.WriteMode = StackEntry.WriteMode.WITH_RESPONSE,
    onComplete: ((Boolean) -> Unit)? = null,
) {
    Timber.tag(BtEngineState.TAG).d(
        "Queue WRITE (overload) %s len=%d mode=%s context=%s",
        uuid,
        bytes.size,
        writeMode,
        context.connectionId,
    )
    pushWrite(context, bytes, uuid, writeMode, onComplete)
}

@RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
internal fun BtEngine.pushDescriptorWrite(
    context: ConnectionContext,
    descriptor: BluetoothGattDescriptor,
    payload: ByteArray
) {
    val size = context.queue.enqueue(
        StackEntry(
            op = StackEntry.Op.DESC_WRITE,
            charUuid = descriptor.characteristic?.uuid,
            descriptor = descriptor,
            descriptorUuid = descriptor.uuid,
            descriptorIdentity = descriptor,
            data = payload,
        )
    )
    Timber.tag(BtEngineState.TAG).d(
        "Queue DESC_WRITE %s len=%d size=%d context=%s",
        descriptor.uuid,
        payload.size,
        size,
        context.connectionId,
    )
    handleStack(context)
}

/* ────────────────────────────────────────────────────────────────────────────── */
/* Public hook for GATT core to clear attempts on success                        */
/* ────────────────────────────────────────────────────────────────────────────── */

@RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
internal fun BtEngine.resetCccdRecoveryAttemptsFor(context: ConnectionContext) {
    context.cccd.resetRecoveryAttempts()
}

/* ────────────────────────────────────────────────────────────────────────────── */
/* Enable Notifications (queues CCCD write)                                      */
/* ────────────────────────────────────────────────────────────────────────────── */

@RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
internal fun BtEngine.enableNotifications(
    context: ConnectionContext,
    ch: BluetoothGattCharacteristic,
) {
    val serviceUuid = ch.service?.uuid ?: run {
        Timber.tag(BtEngineState.TAG).e(
            "Cannot plan CCCD for characteristic without service uuid=%s context=%s",
            ch.uuid,
            context.connectionId,
        )
        return
    }
    val target = CccdTarget(serviceUuid, ch.uuid)
    val newlyPlanned = context.cccd.mergeEnablePlan(listOf(target))
    if (newlyPlanned.isEmpty()) {
        Timber.tag(BtEngineState.TAG).d(
            "CCCD target already merged service=%s characteristic=%s context=%s",
            serviceUuid,
            ch.uuid,
            context.connectionId,
        )
        return
    }
    if (!queueNotificationEnable(context, ch)) {
        context.cccd.removeEnableTarget(target)
    }
}

@RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
private fun BtEngine.queueNotificationEnable(
    context: ConnectionContext,
    ch: BluetoothGattCharacteristic,
): Boolean {
    val gatt = context.gatt ?: run {
        Timber.tag(BtEngineState.TAG).w(
            "enableNotifications: context %s has no GATT",
            context.connectionId,
        )
        return false
    }
    val props = ch.properties
    val wantsIndicate =
        (props and BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0
    val wantsNotify =
        (props and BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0
    if (!wantsIndicate && !wantsNotify) {
        state.setGattErrorFor(gatt, "Char ${ch.uuid} has no notify/indicate")
        Timber.tag(BtEngineState.TAG)
            .w("enableNotifications: %s has no notify/indicate", ch.uuid)
        return false
    }

    val ok = try {
        gatt.setCharacteristicNotification(ch, true)
    } catch (_: SecurityException) {
        false
    }
    if (!ok) {
        state.setGattErrorFor(gatt, "setCharacteristicNotification failed for ${ch.uuid}")
        Timber.tag(BtEngineState.TAG)
            .e("setCharacteristicNotification failed for %s", ch.uuid)
        return false
    }

    val cccd = ch.getDescriptor(UUID_CCCD)
    if (cccd == null) {
        state.setGattErrorFor(gatt, "Missing CCCD for ${ch.uuid}")
        Timber.tag(BtEngineState.TAG).e("Missing CCCD for %s", ch.uuid)
        return false
    }

    val payload =
        if (wantsIndicate) BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
        else BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE

    Timber.tag(BtEngineState.TAG).d(
        "Queue CCCD %s → %s (indicate=%s notify=%s) on %s",
        cccd.uuid, ch.uuid, wantsIndicate, wantsNotify, gattLabel(gatt)
    )
    val pending = context.cccd.incrementPending()
    Timber.tag(BtEngineState.TAG).d(
        "CCCD pending +=1 → %d for %s context=%s",
        pending,
        ch.uuid,
        context.connectionId,
    )
    pushDescriptorWrite(context, cccd, payload)
    return true
}

/** Re-arm every handler's merged CCCD request after a bounded write-recovery attempt. */
@RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
private fun BtEngine.rearmMergedCccdEnablePlan(context: ConnectionContext) {
    val gatt = context.gatt ?: return
    val plan = context.cccd.enablePlanSnapshot()
    state.connectionRegistry.resetCccd(context, clearPlan = false)

    var allTargetsQueued = true
    plan.forEach { target ->
        val characteristic = runCatching {
            gatt.getService(target.serviceUuid)?.getCharacteristic(target.characteristicUuid)
        }.getOrNull()
        if (characteristic == null) {
            allTargetsQueued = false
            context.cccd.markEnablePlanFailed()
            Timber.tag(BtEngineState.TAG).e(
                "Merged CCCD target missing during recovery service=%s characteristic=%s context=%s",
                target.serviceUuid,
                target.characteristicUuid,
                context.connectionId,
            )
        } else {
            if (!queueNotificationEnable(context, characteristic)) {
                allTargetsQueued = false
                context.cccd.markEnablePlanFailed()
            }
        }
    }

    if (allTargetsQueued && context.cccd.pendingWrites == 0) {
        state.setCccdReady(context, true)
    }
}

/* ────────────────────────────────────────────────────────────────────────────── */
/* Queue driver                                                                  */
/* ────────────────────────────────────────────────────────────────────────────── */

@RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
internal fun BtEngine.handleStack(context: ConnectionContext) {
    val entry = context.queue.beginDispatch() ?: return
    val contextGatt = context.gatt
    Timber.tag(BtEngineState.TAG).d(
        "Deque head → op=%s uuid=%s on=%s size=%d context=%s",
        entry.op,
        entry.charUuid,
        gattLabel(contextGatt),
        context.queue.size,
        context.connectionId,
    )

    try {
        when (entry.op) {
            StackEntry.Op.DESC_WRITE -> {
                val tgtGatt = contextGatt
                val desc = entry.descriptor
                val payload = entry.data
                if (tgtGatt == null || desc == null || payload == null) {
                    failAndContinue(
                        context,
                        "Descriptor entry incomplete (gatt=${tgtGatt != null} desc=${desc != null} data=${payload != null})"
                    )
                    return
                }

                // ── Path A: API 33+ first try ────────────────────────────────────────
                if (Build.VERSION.SDK_INT >= 33) {
                    val rc = try {
                        Timber.tag(BtEngineState.TAG).d(
                            "Dispatch DESC_WRITE %s len=%d on %s",
                            desc.uuid,
                            payload.size,
                            gattLabel(tgtGatt)
                        )
                        tgtGatt.writeDescriptor(desc, payload)
                    } catch (_: SecurityException) {
                        BluetoothStatusCodes.ERROR_MISSING_BLUETOOTH_CONNECT_PERMISSION
                    }
                    Timber.tag(BtEngineState.TAG).d(
                        "writeDescriptor issued rc=%d %s on %s",
                        rc,
                        desc.uuid,
                        gattLabel(tgtGatt)
                    )

                    if (rc == BluetoothStatusCodes.SUCCESS) {
                        // accepted; wait for onDescriptorWrite()
                        return
                    }

                    // Busy → retry once with small backoff without popping the queue
                    if (rc == BluetoothStatusCodes.ERROR_GATT_WRITE_REQUEST_BUSY) {
                        queueIoScope.launch {
                            delay(200)
                            if (context.gatt !== tgtGatt || !context.queue.isInFlight(entry)) {
                                Timber.tag(BtEngineState.TAG).d(
                                    "DESC_WRITE retry cancelled for stale context %s",
                                    context.connectionId,
                                )
                                return@launch
                            }
                            val rcRetry = try {
                                Timber.tag(BtEngineState.TAG).d(
                                    "DESC_WRITE retry (API33) %s on %s",
                                    desc.uuid,
                                    gattLabel(tgtGatt)
                                )
                                tgtGatt.writeDescriptor(desc, payload)
                            } catch (_: SecurityException) {
                                BluetoothStatusCodes.ERROR_MISSING_BLUETOOTH_CONNECT_PERMISSION
                            }
                            if (rcRetry == BluetoothStatusCodes.SUCCESS) {
                                // will complete via callback
                            } else {
                                // Fallback to legacy once
                                val legacyOk = try {
                                    @Suppress("DEPRECATION")
                                    run {
                                        desc.value = payload
                                        Timber.tag(BtEngineState.TAG).d(
                                            "DESC_WRITE legacy fallback (after busy) %s on %s",
                                            desc.uuid,
                                            gattLabel(tgtGatt)
                                        )
                                        tgtGatt.writeDescriptor(desc)
                                    }
                                } catch (_: SecurityException) {
                                    false
                                }
                                if (!legacyOk) {
                                    handleDescriptorDispatchFailure(
                                        context,
                                        tgtGatt,
                                        desc,
                                        CccdWriteFailureCause.Api33Dispatch(rcRetry),
                                    )
                                }
                            }
                        }
                        // keep head in place; keep writeBusy=true until callback/recovery
                        return
                    }

                    // Immediate legacy fallback once for other non-success codes
                    val legacyOk = try {
                        @Suppress("DEPRECATION")
                        run {
                            desc.value = payload
                            Timber.tag(BtEngineState.TAG).d(
                                "DESC_WRITE legacy fallback %s on %s",
                                desc.uuid,
                                gattLabel(tgtGatt)
                            )
                            tgtGatt.writeDescriptor(desc)
                        }
                    } catch (_: SecurityException) {
                        false
                    }

                    if (!legacyOk) {
                        handleDescriptorDispatchFailure(
                            context,
                            tgtGatt,
                            desc,
                            CccdWriteFailureCause.Api33Dispatch(rc),
                        )
                        return
                    } else {
                        // Legacy call will deliver onDescriptorWrite; keep queue in-flight
                        return
                    }
                } else {
                    // ── Path B: pre-33 legacy, with single retry ─────────────────────
                    @Suppress("DEPRECATION")
                    run {
                        desc.value = payload
                        val okInitial = try {
                            Timber.tag(BtEngineState.TAG).d(
                                "Dispatch DESC_WRITE %s len=%d on %s",
                                desc.uuid,
                                payload.size,
                                gattLabel(tgtGatt)
                            )
                            tgtGatt.writeDescriptor(desc)
                        } catch (_: SecurityException) {
                            false
                        }

                        if (!okInitial) {
                            // one short backoff then retry once — keep head pending
                            queueIoScope.launch {
                                delay(150)
                                if (context.gatt !== tgtGatt || !context.queue.isInFlight(entry)) {
                                    Timber.tag(BtEngineState.TAG).d(
                                        "DESC_WRITE retry cancelled for stale context %s",
                                        context.connectionId,
                                    )
                                    return@launch
                                }
                                val okRetry = try {
                                    Timber.tag(BtEngineState.TAG).d(
                                        "DESC_WRITE retry (legacy) %s on %s",
                                        desc.uuid,
                                        gattLabel(tgtGatt)
                                    )
                                    @Suppress("DEPRECATION")
                                    tgtGatt.writeDescriptor(desc)
                                } catch (_: SecurityException) {
                                    false
                                }

                                if (!okRetry) {
                                    handleDescriptorDispatchFailure(
                                        context,
                                        tgtGatt,
                                        desc,
                                        CccdWriteFailureCause.LegacyDispatchRejected,
                                    )
                                } // else: wait for onDescriptorWrite
                            }
                            // keep queue head and writeBusy until callback/recovery
                            return
                        }
                        // accepted; wait for onDescriptorWrite
                        return
                    }
                }
            }

            StackEntry.Op.READ, StackEntry.Op.WRITE -> {
                val uuid = entry.charUuid
                val gatt = contextGatt
                if (uuid == null) {
                    failAndContinue(context, "Missing charUuid"); return
                }
                if (gatt == null) {
                    failAndContinue(context, "No GATT available for $uuid"); return
                }

                val svc: BluetoothGattService? = gatt.getServiceFor(uuid)
                if (svc == null) {
                    val msg =
                        "Service for char $uuid not found on ${gattLabel(gatt)}"
                    state.setGattErrorFor(gatt, msg)
                    failAndContinue(context, msg); return
                }
                val ch = svc.getCharacteristic(uuid)
                if (ch == null) {
                    val msg =
                        "Characteristic $uuid not found on ${gattLabel(gatt)}"
                    state.setGattErrorFor(gatt, msg)
                    failAndContinue(context, msg); return
                }

                if (entry.op == StackEntry.Op.READ) {
                    Timber.tag(BtEngineState.TAG).d(
                        "Dispatch READ %s on %s",
                        uuid,
                        gattLabel(gatt)
                    )
                    @Suppress("DEPRECATION")
                    val accepted = try {
                        gatt.readCharacteristic(ch)
                    } catch (_: SecurityException) {
                        false
                    }
                    Timber.tag(BtEngineState.TAG).d(
                        "readCharacteristic accepted=%s %s on %s",
                        accepted,
                        uuid,
                        gattLabel(gatt),
                    )
                    if (!characteristicReadAccepted(accepted)) {
                        val msg = "readCharacteristic=false $uuid on ${gattLabel(gatt)}"
                        state.setGattErrorFor(gatt, msg)
                        failAndContinue(context, msg)
                        return
                    }
                    // Accepted; retain the READ head until onCharacteristicRead.
                } else {
                    val value = entry.data ?: ByteArray(0)
                    if (entry.writeMode == StackEntry.WriteMode.WITHOUT_RESPONSE) {
                        dispatchWriteWithoutResponse(context, gatt, ch, uuid, value, entry)
                        return
                    }
                    if (Build.VERSION.SDK_INT >= 33) {
                        ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                        val rc = try {
                            Timber.tag(BtEngineState.TAG).d(
                                "Dispatch WRITE %s len=%d on %s",
                                uuid,
                                value.size,
                                gattLabel(gatt)
                            )
                            gatt.writeCharacteristic(
                                ch,
                                value,
                                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                            )
                        } catch (_: SecurityException) {
                            BluetoothStatusCodes.ERROR_MISSING_BLUETOOTH_CONNECT_PERMISSION
                        }
                        Timber.tag(BtEngineState.TAG).d(
                            "writeCharacteristic issued rc=%d %s on %s",
                            rc,
                            uuid,
                            gattLabel(gatt)
                        )

                        if (rc == BluetoothStatusCodes.SUCCESS) {
                            // accepted; wait for onCharacteristicWrite
                            return
                        }

                        if (rc == BluetoothStatusCodes.ERROR_GATT_WRITE_REQUEST_BUSY) {
                            // Backoff + single retry without popping the queue
                            queueIoScope.launch {
                                delay(200)
                                if (context.gatt !== gatt || !context.queue.isInFlight(entry)) {
                                    Timber.tag(BtEngineState.TAG).d(
                                        "WRITE retry cancelled for stale context %s",
                                        context.connectionId,
                                    )
                                    return@launch
                                }
                                val rcRetry = try {
                                    Timber.tag(BtEngineState.TAG).d(
                                        "WRITE retry (API33) %s on %s",
                                        uuid,
                                        gattLabel(gatt)
                                    )
                                    gatt.writeCharacteristic(
                                        ch,
                                        value,
                                        BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                                    )
                                } catch (_: SecurityException) {
                                    BluetoothStatusCodes.ERROR_MISSING_BLUETOOTH_CONNECT_PERMISSION
                                }
                                if (rcRetry != BluetoothStatusCodes.SUCCESS) {
                                    val msg = "writeCharacteristic retry rc=$rcRetry $uuid on ${gattLabel(gatt)}"
                                    state.setGattErrorFor(gatt, msg)
                                    failAndContinue(context, msg)
                                } // else: wait for onCharacteristicWrite
                            }
                            return
                        }

                        val msg =
                            "writeCharacteristic rc=$rc $uuid on ${gattLabel(gatt)}"
                        state.setGattErrorFor(gatt, msg)
                        failAndContinue(context, msg); return
                    } else {
                        @Suppress("DEPRECATION")
                        run {
                            ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                            ch.value = value
                            val ok = try {
                                Timber.tag(BtEngineState.TAG).d(
                                    "Dispatch WRITE %s len=%d on %s",
                                    uuid,
                                    value.size,
                                    gattLabel(gatt)
                                )
                                gatt.writeCharacteristic(ch)
                            } catch (_: SecurityException) {
                                false
                            }
                            if (!ok) {
                                // backoff + one retry on legacy path
                                queueIoScope.launch {
                                    delay(150)
                                    if (context.gatt !== gatt || !context.queue.isInFlight(entry)) {
                                        Timber.tag(BtEngineState.TAG).d(
                                            "WRITE retry cancelled for stale context %s",
                                            context.connectionId,
                                        )
                                        return@launch
                                    }
                                    val okRetry = try {
                                        Timber.tag(BtEngineState.TAG).d(
                                            "WRITE retry (legacy) %s on %s",
                                            uuid,
                                            gattLabel(gatt)
                                        )
                                        @Suppress("DEPRECATION")
                                        gatt.writeCharacteristic(ch)
                                    } catch (_: SecurityException) {
                                        false
                                    }
                                    if (!okRetry) {
                                        val msg =
                                            "writeCharacteristic=false $uuid on ${gattLabel(gatt)}"
                                        state.setGattErrorFor(gatt, msg)
                                        failAndContinue(context, msg)
                                    } // else: wait for onCharacteristicWrite
                                }
                                return
                            }
                            // accepted; wait for onCharacteristicWrite
                        }
                    }
                }
            }
        }
    } catch (t: Throwable) {
        Timber.tag(BtEngineState.TAG).e(t, "Exception in handleStack")
        failAndContinue(context, "Exception: ${t.message ?: "unknown"}")
    }
}

/**
 * Dispatches a WRITE entry as write-without-response and completes the queue head
 * immediately on acceptance, rather than waiting for [BluetoothGatt.onCharacteristicWrite].
 *
 * FE-C over BLE's FEC3 control channel has no protocol acknowledgement - "the stack
 * accepted the packet for transmission" is the only local completion signal that exists.
 * Waiting on the with-response callback path here would either hang (many stacks never
 * call it back for WRITE_TYPE_NO_RESPONSE) or race a spurious later callback against the
 * next queued entry. If a callback does arrive late, [queueCompletionMatches] against the
 * (already-advanced) head causes it to be logged and ignored rather than mis-attributed.
 */
@RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
private fun BtEngine.dispatchWriteWithoutResponse(
    context: ConnectionContext,
    gatt: BluetoothGatt,
    ch: BluetoothGattCharacteristic,
    uuid: UUID,
    value: ByteArray,
    entry: StackEntry,
) {
    fun completeLocally() {
        CaptureHooks.wrote(
            connectionId = context.connectionId,
            characteristic = uuid,
            payload = value,
            withoutResponse = true,
        )
        val finished = context.queue.finishHead()
        finished?.onComplete?.invoke(true)
        Timber.tag(BtEngineState.TAG).d(
            "Queue WRITE-NO-RESPONSE OK %s -> next (size=%d context=%s)",
            uuid,
            context.queue.size,
            context.connectionId,
        )
        handleStack(context)
    }

    fun failLocally(msg: String) {
        state.setGattErrorFor(gatt, msg)
        failAndContinue(context, msg)
    }

    if (Build.VERSION.SDK_INT >= 33) {
        ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        val rc = try {
            Timber.tag(BtEngineState.TAG).d(
                "Dispatch WRITE-NO-RESPONSE %s len=%d on %s",
                uuid,
                value.size,
                gattLabel(gatt),
            )
            gatt.writeCharacteristic(ch, value, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
        } catch (_: SecurityException) {
            BluetoothStatusCodes.ERROR_MISSING_BLUETOOTH_CONNECT_PERMISSION
        }
        when (rc) {
            BluetoothStatusCodes.SUCCESS -> completeLocally()
            BluetoothStatusCodes.ERROR_GATT_WRITE_REQUEST_BUSY -> {
                queueIoScope.launch {
                    delay(200)
                    if (context.gatt !== gatt || !context.queue.isInFlight(entry)) {
                        Timber.tag(BtEngineState.TAG).d(
                            "WRITE-NO-RESPONSE retry cancelled for stale context %s",
                            context.connectionId,
                        )
                        return@launch
                    }
                    val rcRetry = try {
                        Timber.tag(BtEngineState.TAG).d(
                            "WRITE-NO-RESPONSE retry (API33) %s on %s",
                            uuid,
                            gattLabel(gatt),
                        )
                        gatt.writeCharacteristic(
                            ch,
                            value,
                            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE,
                        )
                    } catch (_: SecurityException) {
                        BluetoothStatusCodes.ERROR_MISSING_BLUETOOTH_CONNECT_PERMISSION
                    }
                    if (rcRetry == BluetoothStatusCodes.SUCCESS) {
                        completeLocally()
                    } else {
                        failLocally(
                            "writeCharacteristic (no-response) retry rc=$rcRetry $uuid on ${gattLabel(gatt)}",
                        )
                    }
                }
            }
            else -> failLocally("writeCharacteristic (no-response) rc=$rc $uuid on ${gattLabel(gatt)}")
        }
    } else {
        @Suppress("DEPRECATION")
        run {
            ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            ch.value = value
            val ok = try {
                Timber.tag(BtEngineState.TAG).d(
                    "Dispatch WRITE-NO-RESPONSE %s len=%d on %s",
                    uuid,
                    value.size,
                    gattLabel(gatt),
                )
                gatt.writeCharacteristic(ch)
            } catch (_: SecurityException) {
                false
            }
            if (ok) {
                completeLocally()
            } else {
                queueIoScope.launch {
                    delay(150)
                    if (context.gatt !== gatt || !context.queue.isInFlight(entry)) {
                        Timber.tag(BtEngineState.TAG).d(
                            "WRITE-NO-RESPONSE retry cancelled for stale context %s",
                            context.connectionId,
                        )
                        return@launch
                    }
                    val okRetry = try {
                        Timber.tag(BtEngineState.TAG).d(
                            "WRITE-NO-RESPONSE retry (legacy) %s on %s",
                            uuid,
                            gattLabel(gatt),
                        )
                        @Suppress("DEPRECATION")
                        gatt.writeCharacteristic(ch)
                    } catch (_: SecurityException) {
                        false
                    }
                    if (okRetry) {
                        completeLocally()
                    } else {
                        failLocally("writeCharacteristic (no-response)=false $uuid on ${gattLabel(gatt)}")
                    }
                }
            }
        }
    }
}

@RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
internal fun BtEngine.failAndContinue(context: ConnectionContext, reason: String) {
    val justFinished = context.queue.finishHead()
    justFinished?.onComplete?.invoke(false)
    Timber.tag(BtEngineState.TAG).w(
        "Queue FAIL (%s %s): %s → next (size=%d context=%s)",
        justFinished?.op,
        justFinished?.charUuid,
        reason,
        context.queue.size,
        context.connectionId,
    )
    handleStack(context)
}

@RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
internal fun BtEngine.queueSucceed(
    context: ConnectionContext,
    callbackGatt: BluetoothGatt,
    callbackOp: StackEntry.Op,
    callbackCharUuid: UUID?,
    callbackDescriptorUuid: UUID? = null,
    callbackDescriptorIdentity: Any? = null,
): Boolean = completeQueueCallback(
    context,
    callbackGatt,
    callbackOp,
    callbackCharUuid,
    callbackDescriptorUuid,
    callbackDescriptorIdentity,
    failureReason = null,
)

@RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
internal fun BtEngine.queueFail(
    context: ConnectionContext,
    callbackGatt: BluetoothGatt,
    callbackOp: StackEntry.Op,
    callbackCharUuid: UUID?,
    callbackDescriptorUuid: UUID? = null,
    reason: String,
    callbackDescriptorIdentity: Any? = null,
): Boolean = completeQueueCallback(
    context,
    callbackGatt,
    callbackOp,
    callbackCharUuid,
    callbackDescriptorUuid,
    callbackDescriptorIdentity,
    failureReason = reason,
)

@RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
private fun BtEngine.completeQueueCallback(
    context: ConnectionContext,
    callbackGatt: BluetoothGatt,
    callbackOp: StackEntry.Op,
    callbackCharUuid: UUID?,
    callbackDescriptorUuid: UUID?,
    callbackDescriptorIdentity: Any?,
    failureReason: String?,
): Boolean {
    val head = context.queue.head()
    val idle = !context.queue.isBusy
    if (idle || !queueCompletionMatches(
            context.gatt,
            callbackGatt,
            head,
            callbackOp,
            callbackCharUuid,
            callbackDescriptorUuid,
            callbackDescriptorIdentity,
        )
    ) {
        // Two different situations reach this branch and only one of them is a fault.
        //
        // An idle queue means the stack delivered a completion for work this queue is not
        // tracking. Write Without Response is the ordinary source: it is acknowledged at the
        // controller, so the queue never holds a slot for it, yet Android still raises
        // onCharacteristicWrite. A Kayak First Orange session polls at about 45 ms and would
        // otherwise emit roughly 1,300 warnings a minute — enough noise to bury a real diagnostic
        // in exactly the captures used to find one. That case is expected and logs at debug.
        //
        // A busy queue whose head does not match is a genuine correlation failure: some other
        // operation completed while this one was in flight, and the queue is about to be blocked
        // until its own timeout. That stays a warning.
        if (idle) {
            Timber.tag(BtEngineState.TAG).d(
                "Untracked GATT completion on idle queue context=%s callbackOp=%s callbackChar=%s " +
                    "callbackDesc=%s",
                context.connectionId,
                callbackOp,
                callbackCharUuid,
                callbackDescriptorUuid,
            )
        } else {
            Timber.tag(BtEngineState.TAG).w(
                "Ignoring mismatched GATT completion context=%s callbackOp=%s callbackChar=%s " +
                    "callbackDesc=%s headOp=%s headChar=%s",
                context.connectionId,
                callbackOp,
                callbackCharUuid,
                callbackDescriptorUuid,
                head?.op,
                head?.charUuid,
            )
        }
        return false
    }

    val finished = context.queue.finishIfHead(head!!) ?: return false
    finished.onComplete?.invoke(failureReason == null)
    Timber.tag(BtEngineState.TAG).d(
        "Queue %s (%s %s) → next (size=%d context=%s)",
        if (failureReason == null) "OK" else "FAIL: $failureReason",
        finished.op,
        finished.charUuid,
        context.queue.size,
        context.connectionId,
    )
    handleStack(context)
    return true
}

/* ────────────────────────────────────────────────────────────────────────────── */
/* CCCD failure → recovery (bond if needed, re-enable, request control)          */
/* ────────────────────────────────────────────────────────────────────────────── */

@RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
private fun BtEngine.handleDescriptorDispatchFailure(
    connection: ConnectionContext,
    gatt: BluetoothGatt,
    desc: BluetoothGattDescriptor,
    cause: CccdWriteFailureCause,
) {
    if (desc.uuid == UUID_CCCD) {
        handleCccdWriteFailureRecovery(connection, gatt, desc, cause)
    } else {
        failAndContinue(connection, "Descriptor dispatch failed ${desc.uuid} (${cause.detail})")
    }
}

@RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
internal fun BtEngine.handleCccdWriteFailureRecovery(
    connection: ConnectionContext,
    gatt: BluetoothGatt,
    desc: BluetoothGattDescriptor,
    cause: CccdWriteFailureCause,
): Boolean {
    val failed = consumeMatchedDescriptorFailure(
        contextGatt = connection.gatt,
        callbackGatt = gatt,
        queue = connection.queue,
        callbackCharUuid = desc.characteristic?.uuid,
        callbackDescriptorUuid = desc.uuid,
        callbackDescriptorIdentity = desc,
    ) ?: run {
        Timber.tag(BtEngineState.TAG).w(
            "Ignoring mismatched CCCD failure context=%s characteristic=%s cause=%s",
            connection.connectionId,
            desc.characteristic?.uuid,
            cause.detail,
        )
        return false
    }

    Timber.tag(BtEngineState.TAG)
        .w(
            "CCCD write failed %s for %s on %s (%s)",
            desc.uuid,
            failed.charUuid,
            gattLabel(gatt),
            cause.detail,
        )
    connection.cccd.markEnablePlanFailed()
    state.connectionRegistry.setCccdReady(connection, false)

    val dev = gatt.device
    val mac = dev.address ?: "?"

    val previousAttempts = connection.cccd.recoveryAttempts
    val decision = connection.cccd.nextRecoveryDecision(MAX_CCCD_RECOVERY_ATTEMPTS)
    if (decision == CccdRecoveryDecision.Terminate) {
        val reason = "CCCD setup failed after $previousAttempts recovery attempts"
        Timber.tag(BtEngineState.TAG).e("%s for %s context=%s", reason, mac, connection.connectionId)
        state.setGattErrorFor(gatt, reason)
        connection.queue.clear()
        connection.terminationHook?.invoke(reason)
        return true
    }
    val attemptsNow = (decision as CccdRecoveryDecision.Retry).attempt

    queueIoScope.launch {
        try {
            if (!cccdRecoveryStillOwned(connection.gatt, gatt, connection.isActive)) return@launch
            val wasBonded = (dev.bondState == BluetoothDevice.BOND_BONDED)

            // Bonding policy (see connectFtms in btEngine.kt): never bond
            // speculatively. A CCCD write can fail for many reasons - a busy
            // queue, a slow peripheral, a descriptor the peer does not actually
            // implement - and only one of them is fixed by pairing.
            //
            // This used to bond whenever the device was unbonded, so a machine
            // that simply needed a retry got a pairing request instead, and an
            // unencrypted peripheral (which has no reason to accept one) could
            // lose the link entirely.
            //
            // Now the only case that warrants pairing is a *stale bond*: the
            // device says it is bonded, yet the write still fails after a retry,
            // which means the stored keys no longer match. Everything else just
            // re-runs the enable plan, and if the peer genuinely requires
            // encryption Android escalates on its own.
            val staleBondSuspected = wasBonded && previousAttempts > 0

            var bondedOk = wasBonded
            if (staleBondSuspected) {
                Timber.tag(BtEngineState.TAG).i(
                    "CCCD recovery: bonded but still failing after %d attempt(s) → suspected stale bond for %s, re-bonding",
                    previousAttempts,
                    mac
                )
                bondedOk = ensureBondOrPrompt(context, dev)
                Timber.tag(BtEngineState.TAG)
                    .i("Bond result for %s → %s", mac, bondedOk)
            } else {
                Timber.tag(BtEngineState.TAG).i(
                    "CCCD recovery: retrying enable plan without bonding (%s, bondState=%d, attempt=%d)",
                    mac,
                    dev.bondState,
                    attemptsNow
                )
            }

            // Let the stack settle briefly (pairing/encryption propagation)
            delay(300)

            // Re-arm the complete context plan contributed by every active handler.
            if (!cccdRecoveryStillOwned(connection.gatt, gatt, connection.isActive)) {
                Timber.tag(BtEngineState.TAG).d(
                    "CCCD recovery cancelled for stale context %s",
                    connection.connectionId,
                )
                return@launch
            }

            runCatching { rearmMergedCccdEnablePlan(connection) }
                .onFailure {
                    Timber.tag(BtEngineState.TAG)
                        .w(it, "Re-enable notifications failed")
                }

            // Wait for CCCD orchestration to complete
            val ready: Boolean = withTimeoutOrNull(6_000) {
                connection.cccd.ready.first { it }
            } != null

            if (ready) {
                Timber.tag(BtEngineState.TAG)
                    .i("CCCD recovery: merged plan ready for context=%s", connection.connectionId)
            } else {
                Timber.tag(BtEngineState.TAG)
                    .w("CCCD recovery: cccdReady not observed within timeout")
            }
        } finally {
            // A disconnected generation owns no more queue work.
            if (cccdRecoveryStillOwned(connection.gatt, gatt, connection.isActive)) {
                handleStack(connection)
            }
        }
    }
    return true
}

/* ────────────────────────────────────────────────────────────────────────────── */
/* Helpers                                                                       */
/* ────────────────────────────────────────────────────────────────────────────── */

/** Resolve the owning service for a characteristic UUID. */
@RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
private fun BluetoothGatt.getServiceFor(charUuid: UUID): BluetoothGattService? {
    val list = try {
        services
    } catch (_: SecurityException) {
        emptyList<BluetoothGattService>()
    }
    for (svc in list) if (svc.getCharacteristic(charUuid) != null) return svc
    return null
}

private fun BtEngine.gattLabel(g: BluetoothGatt?): String = when (g) {
    null -> "null"
    state.gattHrm -> "HRM"
    state.gatt -> "FTMS"
    else -> "GATT@${System.identityHashCode(g)}"
}
