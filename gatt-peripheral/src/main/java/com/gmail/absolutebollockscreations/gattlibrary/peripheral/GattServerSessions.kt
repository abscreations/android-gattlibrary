package com.gmail.absolutebollockscreations.gattlibrary.peripheral

import android.annotation.SuppressLint
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattService
import android.os.Handler
import android.os.Looper

/**
 * The part of a GATT server that [GattServerSessions] drives. Abstract so the close logic can
 * be tested without Android.
 */
interface GattServerOps<S> {
    fun addService(service: S): Boolean
    fun removeService(service: S): Boolean
    fun clearServices()
    fun close()
}

/** Delayed work for [GattServerSessions]; the default posts to the main looper. */
fun interface GattTimer {
    fun schedule(delayMs: Long, action: () -> Unit)
}

/** Main-looper [GattTimer]; the handler is created on first use. */
internal object MainLooperGattTimer : GattTimer {
    private val handler by lazy { Handler(Looper.getMainLooper()) }
    override fun schedule(delayMs: Long, action: () -> Unit) {
        handler.postDelayed({ action() }, delayMs)
    }
}

/** Adapts a platform server to [GattServerOps]. */
@SuppressLint("MissingPermission")
fun BluetoothGattServer.gattOps(): GattServerOps<BluetoothGattService> {
    val server = this
    return object : GattServerOps<BluetoothGattService> {
        override fun addService(service: BluetoothGattService): Boolean = server.addService(service)
        override fun removeService(service: BluetoothGattService): Boolean = server.removeService(service)
        override fun clearServices() = server.clearServices()
        override fun close() = server.close()
    }
}

/**
 * Service publication and shutdown for one owner's GATT servers (LIB-9g).
 *
 * `addService` is asynchronous: Android confirms each service later, in `onServiceAdded`. If a
 * server is cleared and closed while an add is still unconfirmed, the add can complete inside the
 * Bluetooth stack after the close. Nothing owns that service any more, so nothing can remove it,
 * and it stays in the device's GATT database until the app process dies. That is how a stopped
 * PM5 persona left its Concept2 rowing service beside a later FTMS treadmill.
 *
 * So a close never runs while an add is unconfirmed:
 *  - With no add in flight, [closeCurrent] clears and closes at once, as before.
 *  - With one in flight, it clears what is already published and waits. When the confirmation
 *    arrives ([onServiceAdded]), it removes that service, clears again and closes.
 *  - If no confirmation arrives within [closeWaitMs], it closes anyway.
 *
 * One session per opened server, keyed by the owner's start epoch, so a new server can open
 * while an old one is still waiting to close. Every `addService` must go through [addService],
 * and every `onServiceAdded` callback must call [onServiceAdded] first.
 */
class GattServerSessions<S>(
    private val timer: GattTimer = MainLooperGattTimer,
    private val closeWaitMs: Long = CLOSE_WAIT_MS,
) {
    private class Session<S>(val epoch: Long, val ops: GattServerOps<S>) {
        var inFlight: S? = null
        var closed = false
    }

    private val lock = Any()
    private var current: Session<S>? = null
    private val closing = mutableMapOf<Long, Session<S>>()

    /** Registers a newly opened server; any previous current server is closed first. */
    fun open(epoch: Long, ops: GattServerOps<S>) {
        val previous = synchronized(lock) {
            current.also { current = Session(epoch, ops) }
        }
        previous?.let(::beginClose)
    }

    /** Adds [service] to the current server, recording it as unconfirmed until [onServiceAdded]. */
    fun addService(service: S): Boolean {
        val session = synchronized(lock) {
            current?.also { it.inFlight = service }
        } ?: return false
        val accepted = runCatching { session.ops.addService(service) }.getOrDefault(false)
        if (!accepted) synchronized(lock) { if (session.inFlight === service) session.inFlight = null }
        return accepted
    }

    /**
     * Call first in `onServiceAdded`.
     *
     * @return true when the callback belongs to a server that is closing or already closed, so the
     * owner must ignore it; false for the current server, whose own handling should continue.
     */
    fun onServiceAdded(epoch: Long, service: S): Boolean {
        val finishing = synchronized(lock) {
            val live = current
            if (live != null && live.epoch == epoch) {
                live.inFlight = null
                return false
            }
            closing.remove(epoch)?.also { it.inFlight = null } ?: return true
        }
        finishClose(finishing, lateService = service)
        return true
    }

    /** Closes the current server, deferring while an add is unconfirmed. */
    fun closeCurrent() {
        val session = synchronized(lock) { current.also { current = null } } ?: return
        beginClose(session)
    }

    /** True while some closed server still waits for an add confirmation (tests, diagnostics). */
    val hasPendingClose: Boolean get() = synchronized(lock) { closing.isNotEmpty() }

    private fun beginClose(session: Session<S>) {
        val wait = synchronized(lock) {
            if (session.inFlight != null) {
                closing[session.epoch] = session
                true
            } else {
                false
            }
        }
        if (!wait) {
            finishClose(session, lateService = null)
            return
        }
        // Withdraw everything already published now; only the unconfirmed add is left to catch.
        runCatching { session.ops.clearServices() }
        timer.schedule(closeWaitMs) {
            val stillWaiting = synchronized(lock) { closing.remove(session.epoch) != null }
            if (stillWaiting) finishClose(session, lateService = null)
        }
    }

    private fun finishClose(session: Session<S>, lateService: S?) {
        synchronized(lock) {
            if (session.closed) return
            session.closed = true
        }
        lateService?.let { late -> runCatching { session.ops.removeService(late) } }
        runCatching { session.ops.clearServices() }
        runCatching { session.ops.close() }
    }

    companion object {
        /** How long a close waits for an unconfirmed add before closing anyway. */
        const val CLOSE_WAIT_MS = 2_000L
    }
}
