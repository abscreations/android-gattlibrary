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
 *  - Adds robust CCCD handling: retries, bonding, and “re-arm + request control”
 *    flows when notification/indication enablement fails, including **re-bonding
 *    even when the OS reports the device as already bonded** (to recover from
 *    stale/invalid bonds).
 *
 *  KEY FEATURES
 *  - Queue model:
 *      • [StackEntry] defines a small command object (Op + UUID/payload/descriptor/GATT).
 *      • BtEngine exposes helpers:
 *          - pushRead(uuid, targetGatt?)
 *          - pushWrite(bytes, uuid, targetGatt?)
 *          - pushDescriptorWrite(gatt, descriptor, payload)
 *      • All operations are serialized via:
 *          - state.queue (ArrayDeque<StackEntry>)
 *          - state.lock (synchronization)
 *          - state.writeBusy flag
 *      • handleStack() pulls the head, dispatches the correct GATT call, and
 *        leaves completion to the corresponding BluetoothGattCallback, which must
 *        call queueSucceed()/queueFail() (via BtEngineGattCore).
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
 *      • CCCD writes are queued via enableNotifications(), which:
 *          - Validates notify/indicate capability.
 *          - Calls setCharacteristicNotification(..., true).
 *          - Locates the CCCD descriptor (UUID_CCCD).
 *          - Increments state.pendingCccdWrites and enqueues a DESC_WRITE entry.
 *
 *  - CCCD recovery / bonding workflow:
 *      • On CCCD write failure, handleCccdWriteFailureRecovery(...) is invoked:
 *          - Consumes the failed queue head and clears writeBusy (pauses queue).
 *          - Looks up a per-device attempt counter (keyed by MAC) and caps
 *            retries at MAX_CCCD_RECOVERY_ATTEMPTS.
 *          - On **unbonded** devices:
 *              → attempts a bond via ensureBondOrPrompt(...).
 *          - On **bonded** devices:
 *              → first failure: re-run enable plan without re-bonding.
 *              → second failure: treat as stale bond and call ensureBondOrPrompt(...)
 *                to (re)bond even though the OS reports BOND_BONDED.
 *          - In a background IO coroutine:
 *              1. Perform (re)bonding if needed.
 *              2. Brief delay to allow pairing/encryption to settle.
 *              3. Calls BtEngine.enableFtmsNotificationsAndIndications(), which is
 *                 expected to be idempotent and to arm only the correct FTMS data
 *                 characteristics + control point.
 *              4. Waits (with timeout) for state.cccdReady==true, then calls
 *                 requestControl() so the app regains FTMS control on the now
 *                 encrypted link.
 *              5. Finally resumes the queue via handleStack().
 *      • On any successful descriptor write, succeedAndContinue() clears the
 *        per-MAC recovery counters via resetCccdRecoveryAttemptsFor(...).
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
 *  - BtEngineState:
 *      • Owns the queue, lock, writeBusy flag, pendingCccdWrites, and cccdReady
 *        flow used by the recovery logic.
 *  - BtEngine:
 *      • Provides context, bonding helper (ensureBondOrPrompt), and FTMS helpers
 *        (enableFtmsNotificationsAndIndications, requestControl) referenced by
 *        the CCCD recovery path.
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
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine.BluetoothIds.UUID_CCCD
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/* ────────────────────────────────────────────────────────────────────────────── */
/* Internal state for recovery attempts                                          */
/* ────────────────────────────────────────────────────────────────────────────── */

private const val MAX_CCCD_RECOVERY_ATTEMPTS = 2
private val cccdRecoveryAttemptsByMac = ConcurrentHashMap<String, Int>()

// Simple IO scope for background retries in the queue
private val queueIoScope = CoroutineScope(Dispatchers.IO)

/* ────────────────────────────────────────────────────────────────────────────── */
/* Queue entry + push helpers                                                    */
/* ────────────────────────────────────────────────────────────────────────────── */

internal data class StackEntry(
    val op: Op,
    val charUuid: UUID? = null,
    val data: ByteArray? = null,
    val descriptor: BluetoothGattDescriptor? = null,
    val targetGatt: BluetoothGatt? = null
) {
    enum class Op { READ, WRITE, DESC_WRITE }
}

@RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
internal fun BtEngine.pushRead(uuid: UUID, targetGatt: BluetoothGatt? = null) {
    synchronized(state.lock) {
        state.queue.addLast(
            StackEntry(
                StackEntry.Op.READ,
                charUuid = uuid,
                targetGatt = targetGatt
            )
        )
        Timber.tag(BtEngineState.TAG).d("Queue READ %s  size=%d", uuid, state.queue.size)
    }
    handleStack()
}

@RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
internal fun BtEngine.pushWrite(
    bytes: ByteArray,
    uuid: UUID,
    targetGatt: BluetoothGatt? = null
) {
    synchronized(state.lock) {
        state.queue.addLast(
            StackEntry(
                StackEntry.Op.WRITE,
                charUuid = uuid,
                data = bytes,
                targetGatt = targetGatt
            )
        )
        Timber.tag(BtEngineState.TAG)
            .d("Queue WRITE %s  len=%d  size=%d", uuid, bytes.size, state.queue.size)
    }
    handleStack()
}

// Pre-API 33 overload
@RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
internal fun BtEngine.pushWrite(
    uuid: UUID,
    bytes: ByteArray,
    targetGatt: BluetoothGatt? = null
) {
    Timber.tag(BtEngineState.TAG).d(
        "Queue WRITE (overload) %s  len=%d  target=%s",
        uuid, bytes.size, gattLabel(targetGatt)
    )
    pushWrite(bytes, uuid, targetGatt)
}

@RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
internal fun BtEngine.pushDescriptorWrite(
    targetGatt: BluetoothGatt,
    descriptor: BluetoothGattDescriptor,
    payload: ByteArray
) {
    synchronized(state.lock) {
        state.queue.addLast(
            StackEntry(
                op = StackEntry.Op.DESC_WRITE,
                descriptor = descriptor,
                data = payload,
                targetGatt = targetGatt
            )
        )
        Timber.tag(BtEngineState.TAG).d(
            "Queue DESC_WRITE %s  len=%d  size=%d  on=%s",
            descriptor.uuid, payload.size, state.queue.size, gattLabel(targetGatt)
        )
    }
    handleStack()
}

/* ────────────────────────────────────────────────────────────────────────────── */
/* Public hook for GATT core to clear attempts on success                        */
/* ────────────────────────────────────────────────────────────────────────────── */

@RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
internal fun BtEngine.resetCccdRecoveryAttemptsFor(gatt: BluetoothGatt?) {
    val mac = gatt?.device?.address ?: return
    cccdRecoveryAttemptsByMac.remove(mac)
}

/* ────────────────────────────────────────────────────────────────────────────── */
/* Enable Notifications (queues CCCD write)                                      */
/* ────────────────────────────────────────────────────────────────────────────── */

@RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
internal fun BtEngine.enableNotifications(gatt: BluetoothGatt, ch: BluetoothGattCharacteristic) {
    val props = ch.properties
    val wantsIndicate =
        (props and BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0
    val wantsNotify =
        (props and BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0
    if (!wantsIndicate && !wantsNotify) {
        state.setGattErrorFor(gatt, "Char ${ch.uuid} has no notify/indicate")
        Timber.tag(BtEngineState.TAG)
            .w("enableNotifications: %s has no notify/indicate", ch.uuid)
        return
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
        return
    }

    val cccd = ch.getDescriptor(UUID_CCCD)
    if (cccd == null) {
        state.setGattErrorFor(gatt, "Missing CCCD for ${ch.uuid}")
        Timber.tag(BtEngineState.TAG).e("Missing CCCD for %s", ch.uuid)
        return
    }

    val payload =
        if (wantsIndicate) BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
        else BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE

    Timber.tag(BtEngineState.TAG).d(
        "Queue CCCD %s → %s (indicate=%s notify=%s) on %s",
        cccd.uuid, ch.uuid, wantsIndicate, wantsNotify, gattLabel(gatt)
    )
    synchronized(state.lock) {
        state.pendingCccdWrites += 1
        Timber.tag(BtEngineState.TAG).d(
            "CCCD pending +=1 → %d for %s",
            state.pendingCccdWrites,
            ch.uuid
        )
    }
    pushDescriptorWrite(gatt, cccd, payload)
}

/* ────────────────────────────────────────────────────────────────────────────── */
/* Queue driver                                                                  */
/* ────────────────────────────────────────────────────────────────────────────── */

@RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
internal fun BtEngine.handleStack() {
    val entry: StackEntry
    val defaultGatt = state.gatt
    synchronized(state.lock) {
        if (state.queue.isEmpty() || state.writeBusy) return
        state.writeBusy = true
        entry = state.queue.first()

        Timber.tag(BtEngineState.TAG).d(
            "Deque head → op=%s uuid=%s on=%s size=%d",
            entry.op,
            entry.charUuid,
            gattLabel(entry.targetGatt ?: defaultGatt),
            state.queue.size
        )
    }

    try {
        when (entry.op) {
            StackEntry.Op.DESC_WRITE -> {
                val tgtGatt = entry.targetGatt ?: defaultGatt
                val desc = entry.descriptor
                val payload = entry.data
                if (tgtGatt == null || desc == null || payload == null) {
                    failAndContinue(
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
                                    handleCccdWriteFailureRecovery(
                                        tgtGatt,
                                        desc,
                                        rcApi33 = rcRetry,
                                        rcApi32 = null
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
                        handleCccdWriteFailureRecovery(
                            tgtGatt,
                            desc,
                            rcApi33 = rc,
                            rcApi32 = null
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
                                    handleCccdWriteFailureRecovery(
                                        tgtGatt,
                                        desc,
                                        rcApi33 = null,
                                        rcApi32 = false
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
                val gatt = entry.targetGatt ?: defaultGatt
                if (uuid == null) {
                    failAndContinue("Missing charUuid"); return
                }
                if (gatt == null) {
                    failAndContinue("No GATT available for $uuid"); return
                }

                val svc: BluetoothGattService? = gatt.getServiceFor(uuid)
                if (svc == null) {
                    val msg =
                        "Service for char $uuid not found on ${gattLabel(gatt)}"
                    state.setGattErrorFor(gatt, msg)
                    failAndContinue(msg); return
                }
                val ch = svc.getCharacteristic(uuid)
                if (ch == null) {
                    val msg =
                        "Characteristic $uuid not found on ${gattLabel(gatt)}"
                    state.setGattErrorFor(gatt, msg)
                    failAndContinue(msg); return
                }

                if (entry.op == StackEntry.Op.READ) {
                    if (Build.VERSION.SDK_INT >= 33) {
                        val rc = try {
                            Timber.tag(BtEngineState.TAG).d(
                                "Dispatch READ %s on %s",
                                uuid,
                                gattLabel(gatt)
                            )
                            gatt.readCharacteristic(ch)
                        } catch (_: SecurityException) {
                            BluetoothStatusCodes.ERROR_MISSING_BLUETOOTH_CONNECT_PERMISSION
                        }
                        Timber.tag(BtEngineState.TAG).d(
                            "readCharacteristic issued rc=$rc $uuid on ${gattLabel(gatt)}"
                        )
                        if (rc != BluetoothStatusCodes.SUCCESS) {
                            val msg =
                                "readCharacteristic rc=$rc $uuid on ${gattLabel(gatt)}"
                            state.setGattErrorFor(gatt, msg)
                            failAndContinue(msg); return
                        }
                        // accepted; wait for onCharacteristicRead
                    } else {
                        @Suppress("DEPRECATION")
                        run {
                            Timber.tag(BtEngineState.TAG).d(
                                "Dispatch READ %s on %s",
                                uuid,
                                gattLabel(gatt)
                            )
                            val ok = try {
                                gatt.readCharacteristic(ch)
                            } catch (_: SecurityException) {
                                false
                            }
                            if (!ok) {
                                val msg =
                                    "readCharacteristic=false $uuid on ${gattLabel(gatt)}"
                                state.setGattErrorFor(gatt, msg)
                                failAndContinue(msg); return
                            }
                            // accepted; wait for onCharacteristicRead
                        }
                    }
                } else {
                    val value = entry.data ?: ByteArray(0)
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
                                    failAndContinue(msg)
                                } // else: wait for onCharacteristicWrite
                            }
                            return
                        }

                        val msg =
                            "writeCharacteristic rc=$rc $uuid on ${gattLabel(gatt)}"
                        state.setGattErrorFor(gatt, msg)
                        failAndContinue(msg); return
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
                                        failAndContinue(msg)
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
        failAndContinue("Exception: ${t.message ?: "unknown"}")
    }
}

@RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
internal fun BtEngine.succeedAndContinue() {
    val justFinished: StackEntry?
    synchronized(state.lock) {
        justFinished = state.queue.firstOrNull()
        if (state.queue.isNotEmpty()) state.queue.removeFirst()
        state.writeBusy = false
    }
    // If success was a DESC_WRITE, clear recovery attempt counter for this connection
    resetCccdRecoveryAttemptsFor(state.gatt)
    resetCccdRecoveryAttemptsFor(state.gattHrm)

    Timber.tag(BtEngineState.TAG).d(
        "Queue OK (%s %s) → next (size=%d)",
        justFinished?.op,
        justFinished?.charUuid,
        state.queue.size
    )
    handleStack()
}

@RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
internal fun BtEngine.failAndContinue(reason: String) {
    Timber.tag(BtEngineState.TAG).w("Queue FAIL: %s", reason)

    val justFinished: StackEntry?
    val remainingSize: Int
    synchronized(state.lock) {
        justFinished = state.queue.firstOrNull()
        if (state.queue.isNotEmpty()) state.queue.removeFirst()
        state.writeBusy = false
        remainingSize = state.queue.size
    }

    Timber.tag(BtEngineState.TAG).d(
        "Queue FAIL (%s %s) → next (size=%d)",
        justFinished?.op,
        justFinished?.charUuid,
        remainingSize
    )

    handleStack()
}

/* For callers still using old names (e.g., BtEngineGattCore) */
@RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
internal fun BtEngine.queueSucceed() = succeedAndContinue()

@RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
internal fun BtEngine.queueFail(reason: String) = failAndContinue(reason)

/* ────────────────────────────────────────────────────────────────────────────── */
/* CCCD failure → recovery (bond if needed, re-enable, request control)          */
/* ────────────────────────────────────────────────────────────────────────────── */

@RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
private fun BtEngine.handleCccdWriteFailureRecovery(
    gatt: BluetoothGatt,
    desc: BluetoothGattDescriptor,
    rcApi33: Int? = null,
    rcApi32: Boolean? = null
) {
    val rcText = when {
        rcApi33 != null -> "rc=$rcApi33"
        rcApi32 != null -> "ok=$rcApi32"
        else -> "rc=?"
    }
    Timber.tag(BtEngineState.TAG)
        .w("CCCD write failed %s on %s (%s)", desc.uuid, gattLabel(gatt), rcText)

    // Pause queue by consuming the failed head
    synchronized(state.lock) {
        if (state.queue.isNotEmpty()) state.queue.removeFirst()
        state.writeBusy = false
    }

    val dev = gatt.device
    val mac = dev.address ?: "?"

    val previousAttempts = cccdRecoveryAttemptsByMac.getOrDefault(mac, 0)
    if (previousAttempts >= MAX_CCCD_RECOVERY_ATTEMPTS) {
        Timber.tag(BtEngineState.TAG).w(
            "CCCD recovery: reached max attempts (%d) for %s → giving up",
            previousAttempts,
            mac
        )
        // Continue the queue without further intervention
        handleStack()
        return
    }
    val attemptsNow = previousAttempts + 1
    cccdRecoveryAttemptsByMac[mac] = attemptsNow

    queueIoScope.launch {
        try {
            val wasBonded = (dev.bondState == BluetoothDevice.BOND_BONDED)

            // Strategy:
            //  - If NOT bonded → always attempt bond.
            //  - If bonded:
            //      • first CCCD failure (previousAttempts == 0) → retry enable plan only
            //      • second CCCD failure (previousAttempts > 0) → treat as stale bond and
            //        force a (re)bond via ensureBondOrPrompt(...).
            val shouldRebond =
                !wasBonded || (wasBonded && previousAttempts > 0)

            var bondedOk = wasBonded
            if (shouldRebond) {
                Timber.tag(BtEngineState.TAG).i(
                    "CCCD recovery: attempting (re)bond for %s (bondState=%d, attempt=%d, wasBonded=%s)",
                    mac,
                    dev.bondState,
                    attemptsNow,
                    wasBonded
                )
                bondedOk = ensureBondOrPrompt(context, dev)
                Timber.tag(BtEngineState.TAG)
                    .i("Bond result for %s → %s", mac, bondedOk)
            } else {
                Timber.tag(BtEngineState.TAG).i(
                    "CCCD recovery: already bonded (%s) — first failure, will retry enable plan without re-bond (attempt=%d)",
                    mac,
                    attemptsNow
                )
            }

            // Let the stack settle briefly (pairing/encryption propagation)
            delay(300)

            // Re-run idempotent enable plan (MUST only arm the single correct FTMS data char)
            runCatching { enableFtmsNotificationsAndIndications() }
                .onFailure {
                    Timber.tag(BtEngineState.TAG)
                        .w(it, "Re-enable notifications failed")
                }

            // Wait for CCCD orchestration to complete
            val ready: Boolean = withTimeoutOrNull(6_000) {
                state.cccdReady.first { it }
            } != null

            if (ready) {
                Timber.tag(BtEngineState.TAG)
                    .i("CCCD recovery: cccdReady=true → requestControl()")
                runCatching { requestControl() }
            } else {
                Timber.tag(BtEngineState.TAG)
                    .w("CCCD recovery: cccdReady not observed within timeout")
            }
        } finally {
            // Resume any remaining queue work
            handleStack()
        }
    }
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
