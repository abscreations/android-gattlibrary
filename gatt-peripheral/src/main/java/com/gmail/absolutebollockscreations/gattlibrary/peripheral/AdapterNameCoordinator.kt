package com.gmail.absolutebollockscreations.gattlibrary.peripheral

import com.gmail.absolutebollockscreations.gattlibrary.core.GattLibraryInternalApi
import com.gmail.absolutebollockscreations.gattlibrary.central.BtServiceProfiles
import java.util.ArrayDeque
import timber.log.Timber

/**
 * Serialises every Bluetooth adapter-name change and waits for Android to
 * confirm it before reporting success.
 *
 * ## The bug this exists to prevent
 *
 * Android has no per-advertisement name API. `AdvertiseData.setIncludeDeviceName(true)`
 * splices in the **global adapter name as it stands when advertising starts** -
 * not the name the caller intended. `BluetoothAdapter.setName()` returns true as
 * soon as the request is *accepted*; the adapter's name is not updated until the
 * stack applies it a moment later.
 *
 * The previous code requested the rename and started advertising nine lines
 * later, on the assumption that "the global name propagates before it matters to
 * the advertisement". It does not. When the stack loses that race the machine
 * goes on the air under whatever the phone was previously called, and the status
 * panel still cheerfully reports the intended name because it echoed the profile
 * rather than the adapter.
 *
 * There is a second race in the same area: restoring a name stranded by a
 * previous session is equally asynchronous, and if it lands *after* a new
 * session's rename it silently overwrites it.
 *
 * ## The fix
 *
 * One queue, one operation in flight, and completion only on observed
 * confirmation - either an `ACTION_LOCAL_NAME_CHANGED` broadcast delivered to
 * [onNameObserved] or a poll that sees the adapter agree. A bounded timeout
 * fails the operation so the caller can abort startup rather than advertise
 * under the wrong identity.
 *
 * Deliberately free of Android types: the three seams below are injected, which
 * is what makes the ordering rules exhaustively unit-testable. Every method must
 * be called on one thread (the controller uses the main looper).
 */
@GattLibraryInternalApi
class AdapterNameCoordinator(
    private val gateway: Gateway,
    private val store: OriginalNameStore,
    private val scheduler: Scheduler,
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    private val pollIntervalMs: Long = DEFAULT_POLL_INTERVAL_MS,
) {

    /** The adapter. */
    interface Gateway {
        /** Null when the adapter is unavailable or has not published a name yet. */
        val currentName: String?

        /** Asks the stack to rename. True only means the request was accepted. */
        fun requestName(name: String): Boolean
    }

    /** Persistent storage for the user's original name, across process death. */
    interface OriginalNameStore {
        fun read(): String?
        fun write(name: String): Boolean
        fun clear(): Boolean
    }

    /** Delayed execution, so tests can drive time. */
    interface Scheduler {
        fun postDelayed(token: Any, delayMs: Long, action: () -> Unit)
        fun cancel(token: Any)
    }

    private sealed interface Operation {
        val onResult: (Result<Unit>) -> Unit

        /** Rename to a session identity, preserving the user's original once. */
        data class Apply(
            val target: String,
            override val onResult: (Result<Unit>) -> Unit,
        ) : Operation

        /** Put the user's original name back and forget it. */
        data class Restore(override val onResult: (Result<Unit>) -> Unit) : Operation
    }

    private val queue = ArrayDeque<Operation>()
    private var inFlight: Operation? = null

    /** The name the in-flight operation is waiting to observe. */
    private var awaitedName: String? = null

    private val timeoutToken = Any()
    private val pollToken = Any()

    /** True while any rename is queued or in flight. */
    val isBusy: Boolean get() = inFlight != null || queue.isNotEmpty()

    /**
     * Rename the adapter and confirm it.
     *
     * [onResult] fires only once the adapter genuinely reports [target], or with
     * a failure on timeout. Callers must not advertise until success.
     */
    fun apply(target: String, onResult: (Result<Unit>) -> Unit) {
        enqueue(Operation.Apply(target, onResult))
    }

    /**
     * Put the original name back, if one was saved.
     *
     * Succeeds immediately when nothing was stranded. The saved original is
     * cleared only after the adapter confirms, so a failed restore is retried on
     * the next launch rather than leaving the user's phone renamed forever.
     */
    fun restore(onResult: (Result<Unit>) -> Unit = {}) {
        enqueue(Operation.Restore(onResult))
    }

    /**
     * Feed in `ACTION_LOCAL_NAME_CHANGED`.
     *
     * @param name the value from `EXTRA_LOCAL_NAME`; null falls back to polling
     * the gateway, since some stacks broadcast without the extra.
     */
    fun onNameObserved(name: String?) {
        val awaited = awaitedName ?: return
        val observed = name ?: gateway.currentName
        if (observed == awaited) settle(Result.success(Unit))
    }

    /** Drop everything without touching the adapter. Used on teardown. */
    fun cancelAll(reason: String) {
        scheduler.cancel(timeoutToken)
        scheduler.cancel(pollToken)
        val pending = buildList {
            inFlight?.let { add(it) }
            while (queue.isNotEmpty()) add(queue.removeFirst())
        }
        inFlight = null
        awaitedName = null
        pending.forEach { it.onResult(Result.failure(IllegalStateException(reason))) }
    }

    // ── queue ─────────────────────────────────────────────────────────────────

    private fun enqueue(operation: Operation) {
        queue.addLast(operation)
        pump()
    }

    private fun pump() {
        if (inFlight != null) return
        val next = queue.pollFirst() ?: return
        inFlight = next
        when (next) {
            is Operation.Apply -> beginApply(next)
            is Operation.Restore -> beginRestore(next)
        }
    }

    private fun beginApply(operation: Operation.Apply) {
        val current = gateway.currentName
        if (current == operation.target) {
            // Already correct. Still record the original if we have never seen
            // one, so a later restore has something to go back to.
            settle(Result.success(Unit))
            return
        }
        // Preserve the user's own name exactly once per stranding, and only a
        // name that is not one of ours - otherwise a crash mid-session would
        // "restore" the phone to a previous persona name forever.
        if (store.read() == null && current != null && !isSessionName(current)) {
            if (!store.write(current)) {
                settle(
                    Result.failure(
                        IllegalStateException("Could not persist the original Bluetooth name"),
                    ),
                )
                return
            }
        }
        request(operation.target)
    }

    private fun beginRestore(operation: Operation.Restore) {
        val original = store.read()
        if (original == null) {
            settle(Result.success(Unit))
            return
        }
        if (gateway.currentName == original) {
            store.clear()
            settle(Result.success(Unit))
            return
        }
        request(original)
    }

    private fun request(target: String) {
        val accepted = runCatching { gateway.requestName(target) }.getOrElse { error ->
            Timber.tag(TAG).w(error, "adapter rename threw")
            false
        }
        if (!accepted) {
            settle(Result.failure(IllegalStateException("Android rejected the adapter name change")))
            return
        }
        awaitedName = target
        Timber.tag(TAG).i("awaiting adapter name confirmation for '%s'", target)

        // Confirmation normally arrives as a broadcast, but not every stack
        // emits one reliably, so poll as well. Whichever observes it first wins.
        scheduler.postDelayed(pollToken, pollIntervalMs) { poll() }
        scheduler.postDelayed(timeoutToken, timeoutMs) {
            Timber.tag(TAG).w(
                "adapter name did not become '%s' within %dms; refusing to advertise under the wrong name",
                target,
                timeoutMs,
            )
            settle(
                Result.failure(
                    IllegalStateException(
                        "Bluetooth adapter name did not change to \"$target\" within ${timeoutMs}ms",
                    ),
                ),
            )
        }
    }

    private fun poll() {
        val awaited = awaitedName ?: return
        if (gateway.currentName == awaited) {
            settle(Result.success(Unit))
        } else {
            scheduler.postDelayed(pollToken, pollIntervalMs) { poll() }
        }
    }

    private fun settle(result: Result<Unit>) {
        val operation = inFlight ?: return
        scheduler.cancel(timeoutToken)
        scheduler.cancel(pollToken)
        inFlight = null
        awaitedName = null

        if (result.isSuccess && operation is Operation.Restore) {
            // Only forget the saved original once the adapter has actually gone
            // back, so a failure leaves it to be retried next launch.
            store.clear()
        }
        if (result.isSuccess) {
            Timber.tag(TAG).i("adapter name settled to '%s'", gateway.currentName)
        }

        operation.onResult(result)
        pump()
    }

    /** True for names this app assigns, so they are never saved as "original". */
    private fun isSessionName(name: String): Boolean = isKnownSessionName(name)

    companion object {
        private const val TAG = "AdapterName"

        /**
         * Long enough for a slow stack to apply a rename, short enough that a
         * user tapping start does not sit staring at nothing. Startup fails
         * past this rather than advertising under the wrong name.
         */
        const val DEFAULT_TIMEOUT_MS = 3_000L
        const val DEFAULT_POLL_INTERVAL_MS = 100L

        /**
         * Referenced rather than duplicated: if this drifted from the advertised
         * name prefix, the coordinator would start saving our own persona names
         * as the user's "original" and strand their phone under one permanently.
         * `const val` is inlined, so this costs no class loading in a JVM test.
         */
        const val SESSION_NAME_PREFIX = BtServiceProfiles.DEVICE_NAME_PREFIX
        /**
         * Persona names that carry no [SESSION_NAME_PREFIX].
         *
         * Every name here exists for one reason: if the process dies mid-session the adapter is
         * still called this, and on the next start an unrecognised name is saved as the user's
         * "original" and their phone is stranded under it permanently.
         *
         * Only the FTMS personas were ever safe by construction, because only they carry the
         * prefix. PM5, Keiser and KICKR were then covered by the patterns below, and everything
         * added after that was not: as of 2026-09-02 the four Life Fitness consoles, all seven
         * FE-C equipment types, both Tacx identities and the Keiser `M3o` profile (whose name the
         * `M3(i|m)?` pattern does not match) were all unrecognised.
         *
         * **Static since LIB-5 (decision L15).** This set used to be derived from the simulator's
         * persona definitions. The per-family simulator peripherals now live in the simulator
         * package, which release builds do not contain (L7), yet the protection must still hold
         * there: a debug session that crashed mid-persona leaves the adapter renamed for the
         * release app to find. The literal list below is therefore the source, and
         * `AdapterNameSessionNamesTest` derives the same set from the persona definitions and
         * fails on any difference, so a persona still cannot be added without being covered.
         *
         * The KICKR names stay listed even though three of the four match [KICKR_SESSION_NAME]:
         * the Zwift Hub's does not. The pattern stays as well, because it still catches a name
         * stranded by an older build whose manifest this one no longer lists.
         */
        @GattLibraryInternalApi
        val UNPREFIXED_SESSION_NAMES: Set<String> = setOf(
            BtServiceProfiles.DEVICE_NAME_CSC_CPS,
            "Stryd",
            "CHRONO 0",
            "Flywheel 1",
            "25100001",
            "17100001",
            "SCHWINN 170/270",
            "LF1F17B1D000001094",
            "LF1F17B1D000002089",
            "LF0F17B1D000003095",
            "LF0F17B1D000004090",
            "M3i",
            "M3m",
            "M3o",
            "FitBridge FE-C Trainer",
            "FitBridge FE-C Bike",
            "FitBridge FE-C Treadmill",
            "FitBridge FE-C Elliptical",
            "FitBridge FE-C Rower",
            "FitBridge FE-C Climber",
            "FitBridge FE-C Skier",
            "Tacx Neo 90002",
            "Tacx Flux-2 90001",
            "Wahoo KICKR AEE4",
            "KICKR CORE C3FB",
            "KICKR CORE 2 1A2B",
            "Zwift Hub",
        )

        /** True for names this app assigns, so they are never saved as "original". */
        @GattLibraryInternalApi
        fun isKnownSessionName(name: String): Boolean =
            name.startsWith(SESSION_NAME_PREFIX) ||
                name in UNPREFIXED_SESSION_NAMES ||
                PM5_SESSION_NAME.matches(name) ||
                KEISER_SESSION_NAME.matches(name) ||
                KICKR_SESSION_NAME.matches(name)

        private val PM5_SESSION_NAME = Regex("PM5 [0-9]{9}(?: Row| Ski| Bike)?")
        private val KEISER_SESSION_NAME = Regex("M3(?:i|m)?(?: .+)?")
        private val KICKR_SESSION_NAME = Regex("(?:Wahoo KICKR|KICKR CORE)(?: .+)?")
    }
}
