package com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine

import timber.log.Timber

/**
 * The bike the user picked, for this session only (plan §6.6).
 *
 * Both halves are required. The address selects one broadcaster; the system ID is the number
 * printed on the bike's console, and it is what the rider actually used to choose between the
 * twenty machines in a studio. Matching on address alone would be enough in a room with one bike
 * and silently wrong in the room this feature exists for.
 *
 * **Never persisted.** §11 forbids writing the address or system ID to `AppSettings` or
 * `SharedPreferences`: the bike a person rode on Tuesday is not a preference, and a remembered
 * address would silently re-attach a returning user to a machine somebody else is now riding.
 */
internal data class KeiserSelection(
    val address: String,
    val systemId: Int,
) {
    init {
        require(address.isNotBlank()) { "A Keiser selection needs the broadcaster's address" }
        require(systemId in 0..0xff) { "System ID is a uint8" }
    }

    /**
     * Stable in-session identity for [MetricOriginId.Advertisement].
     *
     * Carries the address because two bikes in one room must not share a fusion origin. It never
     * reaches a capture file in this form: `CaptureHooks.sample` runs advertisement origins through
     * the same redaction table that maps GATT addresses to `c1`, `c2`, so a trace records which
     * source produced a sample without recording which machine it was.
     */
    val sourceKey: String get() = "keiser:$address#$systemId"
}

/**
 * What a Keiser advertisement says about the bike, for the scanner row.
 *
 * Identity, not telemetry, which is why it is read from any decodable field including a review-mode
 * one. A bike parked on its summary screen still is bike number seven running firmware 6.54, and a
 * row that went blank while the rider caught their breath would be worse than useless.
 */
internal data class KeiserRowMetadata(val systemId: Int, val firmware: String)

/**
 * Read the row metadata from an advertisement's manufacturer payloads, or null when none is a
 * decodable Keiser field.
 *
 * Kept beside the source rather than in the scan layer so the parse and the identity claim it
 * supports stay in one file, and so this is testable without a `ScanRecord`.
 */
internal fun keiserRowMetadata(
    payloads: Map<Int, ManufacturerPayload>,
): KeiserRowMetadata? {
    val payload = payloads[KeiserMSeriesMeasurement.COMPANY_ID] ?: return null
    val reading = KeiserMSeriesMeasurement.parse(payload.copyBytes()) ?: return null
    return KeiserRowMetadata(systemId = reading.systemId, firmware = reading.firmware)
}

/** What the source did with one advertisement. Every value is a decision, not a failure. */
internal enum class KeiserAdvertisementOutcome {
    /** Realtime telemetry from the selected bike. Freshness refreshed, metrics emitted. */
    ACCEPTED,

    /** Nothing is selected, or this generation has already ended. A late callback lands here. */
    IGNORED_NOT_ACTIVE,

    /** Another broadcaster. The room is full of them. */
    IGNORED_OTHER_ADDRESS,

    /** The selected address, but not the selected bike's system ID. */
    IGNORED_OTHER_SYSTEM_ID,

    /** Not a decodable Keiser field. */
    IGNORED_MALFORMED,

    /** A decodable field replaying a finished ride. */
    IGNORED_REVIEW,
}

/**
 * Whether the selected bike is currently telling us anything.
 *
 * Deliberately not called "connected" at any point, because nothing ever connects.
 */
internal enum class KeiserSourceState {
    /** No bike selected. */
    IDLE,

    /** An accepted advertisement arrived within this firmware's statistics window. */
    LIVE,

    /** The window has passed. Values have left fusion; the bike may simply be between packets. */
    STALE,

    /** Sixty seconds of silence. The origin has ended and the UI returns to machine selection. */
    GONE,
}

/** Why a generation ended. Recorded so a diagnostic can tell a user action from a disappearance. */
internal enum class KeiserEndReason { DESELECTED, BIKE_GONE, SESSION_ENDED, BLUETOOTH_LOST }

/** One advertisement as the source sees it: who sent it, and the canonical field it carried. */
internal data class KeiserAdvertisement(
    val address: String,
    val payload: ManufacturerPayload,
)

internal data class KeiserSourceSnapshot(
    val selection: KeiserSelection,
    val generation: Long,
    val firmware: String?,
    val state: KeiserSourceState,
)

/**
 * The selected Keiser bike, as a metric source (plan §6.5).
 *
 * ## This is not a connection, and the shape of the class says so
 *
 * There is no `ConnectionContext`, no `BluetoothGatt`, no operation queue, no CCCD, no handler
 * list, no recovery target and no control endpoint here, and the §11.5 gate is precisely that none
 * of them appear. What exists instead is a selection, a generation number, a last-seen time, one
 * dropout filter, and a lease on the platform scan. A bike that stops advertising has not
 * "disconnected"; it has stopped talking, and the difference is the whole point of the phase.
 *
 * ## Driven entirely by its caller's clock
 *
 * [onAdvertisement] takes the arrival time and [tick] takes the current time; the source reads no
 * clock of its own. That makes the entire state machine — statistics expiry, the sixty-second
 * disappearance, late-callback rejection — testable with plain numbers, which matters because the
 * behavior being asserted is *timing* and a test that waits a real second to prove a one-second
 * window is both slow and flaky.
 *
 * ## Generations
 *
 * Every [select] mints a new generation. Fusion keys its candidates, manual assignment, cumulative
 * rebasing and machine pin on `MetricOriginId.Advertisement(sourceKey, generation)`, so a bike that
 * goes away and comes back is a new origin whose cumulative counters rebase, exactly as a
 * reconnected GATT link does. It is also what makes a late callback harmless: an advertisement
 * arriving after a generation ended finds no active generation to attribute it to.
 *
 * ## What does not refresh liveness
 *
 * Only an accepted realtime packet from the selected bike updates last-seen. A malformed field, a
 * review-mode replay, another broadcaster, or the same address under a different system ID all
 * leave the statistics clock exactly where it was. Otherwise a bike sitting on its summary screen
 * — which the capture corpus shows sending seventy consecutive review frames — would hold the
 * source alive forever while publishing nothing.
 */
internal class KeiserAdvertisementSource(
    private val fusion: MetricFusion,
    private val leases: ScanLeaseRegistry,
    private val onScanLeaseAcquired: () -> Unit = {},
    private val onScanLeaseReleased: () -> Unit = {},
    private val onSourceStateChanged: (KeiserSourceSnapshot?) -> Unit = {},
) {

    private val lock = Any()
    private val dropoutFilter = KeiserDropoutFilter()

    private var selection: KeiserSelection? = null
    private var generation: Long = 0L
    private var confidence: Double = 1.0
    private var lastAcceptedArrivalMs: Long = 0L
    private var statsTimeoutMs: Long = KeiserMSeriesMeasurement.SLOW_STATS_TIMEOUT_MS
    private var state: KeiserSourceState = KeiserSourceState.IDLE
    private var firmware: String? = null

    /** Current state. The UI reads this; it never reads a connection state, because there is none. */
    fun state(): KeiserSourceState = synchronized(lock) { state }

    fun selection(): KeiserSelection? = synchronized(lock) { selection }

    /** Firmware of the last accepted advertisement, for the row and for diagnostics. */
    fun firmware(): String? = synchronized(lock) { firmware }

    /** The origin fusion knows this generation by, or null when nothing is selected. */
    fun originId(): MetricOriginId.Advertisement? = synchronized(lock) {
        selection?.let { MetricOriginId.Advertisement(it.sourceKey, generation) }
    }

    /**
     * Select a bike, ending any previous selection first.
     *
     * Takes the matcher's confidence rather than inventing one: how much this family's evidence is
     * worth was decided when the advertisement was admitted, and fusion should arbitrate on that
     * number rather than on a constant chosen here.
     *
     * @param nowMs seeds the statistics clock, so a freshly selected bike is [KeiserSourceState.LIVE]
     * until its first window elapses rather than starting stale and flickering.
     */
    fun select(
        selection: KeiserSelection,
        nowMs: Long,
        confidence: Double = 1.0,
    ): MetricOriginId.Advertisement = synchronized(lock) {
        require(confidence.isFinite() && confidence in 0.0..1.0) { "Confidence must be a probability" }
        // Ends the previous generation without giving up the lease: the scan must not stop and
        // restart between two selections, which is what releasing and re-acquiring would do to the
        // radio for no reason at all.
        if (this.selection != null) endLocked(KeiserEndReason.DESELECTED, nowMs, releaseLease = false)

        this.selection = selection
        this.confidence = confidence
        generation += 1L
        lastAcceptedArrivalMs = nowMs
        statsTimeoutMs = KeiserMSeriesMeasurement.SLOW_STATS_TIMEOUT_MS
        firmware = null
        state = KeiserSourceState.LIVE
        dropoutFilter.reset()

        val origin = MetricOriginId.Advertisement(selection.sourceKey, generation)
        // Exactly one machine origin at a time. Selecting a bike displaces a GATT machine here
        // rather than in the UI, so the two selection paths cannot both believe they won.
        fusion.selectMachineOrigin(origin)
        CaptureHooks.advertisementSource(
            sourceKey = selection.sourceKey,
            systemId = selection.systemId,
            firmware = null,
            generation = generation,
            state = state,
            decision = "selected",
            freshnessMs = statsTimeoutMs,
        )
        if (leases.acquire(ScanLeaseOwner.ADVERTISEMENT_MACHINE)) {
            Timber.tag(BtEngineState.TAG).i("Keiser selection started the platform scan")
            onScanLeaseAcquired()
        }
        publishStateLocked()
        origin
    }

    /**
     * Offer one advertisement to the selected source.
     *
     * @return what was done with it. Callers do not need the answer; it exists so the state machine
     * can be asserted directly instead of through its side effects.
     */
    fun onAdvertisement(
        advertisement: KeiserAdvertisement,
        arrivalMs: Long,
    ): KeiserAdvertisementOutcome = synchronized(lock) {
        val selected = selection ?: return@synchronized recordDecisionLocked(
            null,
            KeiserAdvertisementOutcome.IGNORED_NOT_ACTIVE,
        )
        if (state == KeiserSourceState.GONE) {
            return@synchronized recordDecisionLocked(
                selected,
                KeiserAdvertisementOutcome.IGNORED_NOT_ACTIVE,
            )
        }
        if (advertisement.address != selected.address) {
            return@synchronized recordDecisionLocked(
                selected,
                KeiserAdvertisementOutcome.IGNORED_OTHER_ADDRESS,
            )
        }

        val reading = KeiserMSeriesMeasurement.parse(advertisement.payload.copyBytes())
            ?: return@synchronized recordDecisionLocked(
                selected,
                KeiserAdvertisementOutcome.IGNORED_MALFORMED,
            )
        if (reading.systemId != selected.systemId) {
            return@synchronized recordDecisionLocked(
                selected,
                KeiserAdvertisementOutcome.IGNORED_OTHER_SYSTEM_ID,
            )
        }
        if (!reading.isRealtime) {
            return@synchronized recordDecisionLocked(
                selected,
                KeiserAdvertisementOutcome.IGNORED_REVIEW,
            )
        }

        // Out-of-order delivery is possible and is not evidence of anything going wrong, but it
        // must not drag the statistics clock backwards into an accidental stale state.
        lastAcceptedArrivalMs = maxOf(lastAcceptedArrivalMs, arrivalMs)
        statsTimeoutMs = reading.statsTimeoutMs
        firmware = reading.firmware
        state = KeiserSourceState.LIVE
        publishStateLocked()

        val filtered = dropoutFilter.filter(reading)
        val emitter = MetricEmitter(
            fusion = fusion,
            sourceId = MetricSourceId(
                originId = MetricOriginId.Advertisement(selected.sourceKey, generation),
                protocol = MetricProtocol.VENDOR,
                endpointId = MetricEndpointId.ManufacturerData(KeiserMSeriesMeasurement.COMPANY_ID),
            ),
            sourceClass = MetricSourceClass.VENDOR_NATIVE,
            arrivalMs = arrivalMs,
            freshnessMs = reading.statsTimeoutMs,
        )
        for (sample in keiserSamples(filtered)) {
            emitter.emit(
                metric = sample.metric,
                value = sample.value,
                nativeCumulative = sample.nativeCumulative,
                confidence = confidence,
            )
        }
        recordDecisionLocked(selected, KeiserAdvertisementOutcome.ACCEPTED)
    }

    /**
     * Advance the clock.
     *
     * Two thresholds, and they mean different things. Past the firmware's statistics window the
     * values are no longer current, so fusion is refreshed and they fall out of selection — but the
     * bike is probably still there, between packets or briefly out of range, and the source keeps
     * its generation. Past sixty seconds it is gone: the origin ends, its fusion candidates and any
     * manual assignment are cleared, and the scan lease is released.
     */
    fun tick(nowMs: Long) = synchronized(lock) {
        val selected = selection ?: return@synchronized
        if (state == KeiserSourceState.GONE) return@synchronized
        val silenceMs = nowMs - lastAcceptedArrivalMs
        if (silenceMs >= KeiserMSeriesMeasurement.BIKE_GONE_TIMEOUT_MS) {
            endLocked(KeiserEndReason.BIKE_GONE, nowMs, releaseLease = true)
            return@synchronized
        }
        if (silenceMs > statsTimeoutMs) {
            // Only on the transition. Fusion decides staleness from each sample's own window, so
            // one refresh drops them and they stay dropped until a new advertisement arrives;
            // refreshing on every tick would re-walk every candidate for no further effect.
            if (state != KeiserSourceState.STALE) {
                Timber.tag(BtEngineState.TAG).i(
                    "Keiser statistics stale after %d ms of silence (window %d ms)",
                    silenceMs,
                    statsTimeoutMs,
                )
                state = KeiserSourceState.STALE
                fusion.refresh(nowMs)
                publishStateLocked()
                CaptureHooks.advertisementSource(
                    selected.sourceKey,
                    selected.systemId,
                    firmware,
                    generation,
                    state,
                    "freshness_expired",
                    statsTimeoutMs,
                )
            }
        } else {
            state = KeiserSourceState.LIVE
        }
    }

    /** End the current generation. Idempotent: ending an already-ended source does nothing. */
    fun end(reason: KeiserEndReason, nowMs: Long) = synchronized(lock) {
        if (selection == null) return@synchronized
        endLocked(reason, nowMs, releaseLease = true)
    }

    private fun endLocked(reason: KeiserEndReason, nowMs: Long, releaseLease: Boolean) {
        val selected = selection ?: return
        val endedFirmware = firmware
        val origin = MetricOriginId.Advertisement(selected.sourceKey, generation)
        CaptureHooks.advertisementSource(
            selected.sourceKey,
            selected.systemId,
            endedFirmware,
            generation,
            if (reason == KeiserEndReason.BIKE_GONE) KeiserSourceState.GONE else state,
            reason.name,
            statsTimeoutMs,
        )
        // Clears this origin's candidates, its manual assignment, and the machine pin if it held it.
        fusion.onOriginEnded(origin, nowMs)
        dropoutFilter.reset()
        selection = null
        firmware = null
        state = if (reason == KeiserEndReason.BIKE_GONE) {
            KeiserSourceState.GONE
        } else {
            KeiserSourceState.IDLE
        }
        if (reason == KeiserEndReason.BIKE_GONE) {
            onSourceStateChanged(
                KeiserSourceSnapshot(selected, generation, endedFirmware, KeiserSourceState.GONE),
            )
        }
        if (releaseLease && leases.release(ScanLeaseOwner.ADVERTISEMENT_MACHINE)) {
            onScanLeaseReleased()
        }
        onSourceStateChanged(null)
        Timber.tag(BtEngineState.TAG).i("Keiser source generation %d ended: %s", generation, reason)
    }

    private fun publishStateLocked() {
        val selected = selection ?: return
        onSourceStateChanged(KeiserSourceSnapshot(selected, generation, firmware, state))
    }

    private fun recordDecisionLocked(
        selected: KeiserSelection?,
        outcome: KeiserAdvertisementOutcome,
    ): KeiserAdvertisementOutcome {
        CaptureHooks.advertisementSource(
            sourceKey = selected?.sourceKey,
            systemId = selected?.systemId,
            firmware = firmware,
            generation = generation,
            state = state,
            decision = outcome.name,
            freshnessMs = statsTimeoutMs.takeIf { outcome == KeiserAdvertisementOutcome.ACCEPTED },
        )
        return outcome
    }
}
