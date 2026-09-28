package com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine

import timber.log.Timber

/**
 * The bike the user picked, for this session only (plan §6.6).
 *
 * ## The system ID is the identity; the address is only where it is currently speaking from
 *
 * The system ID is the number printed on the bike's console, it is what the rider actually used
 * to choose between the twenty machines in a studio, and — critically — it is the only half of
 * this pair the bike itself guarantees to keep. A Keiser broadcasts from a *random* BLE address
 * and rotates it whenever its advertiser restarts, which on the validation rig happens on every
 * scenario silence window and in a studio happens whenever a console power-cycles or the
 * platform's privacy timer fires. Binding identity to the address meant a rotation looked exactly
 * like the bike vanishing: telemetry froze on the last accepted frame while perfectly decodable
 * ones from the same console were discarded as another broadcaster, and sixty seconds later the
 * origin ended. [address] is therefore a *binding*, re-learned under the guard in
 * [KeiserAdvertisementSource.onAdvertisement], not a name.
 *
 * **Never persisted.** §11 forbids writing the address or system ID to `AppSettings` or
 * `SharedPreferences`: the bike a person rode on Tuesday is not a preference, and a remembered
 * address would silently re-attach a returning user to a machine somebody else is now riding.
 */
data class KeiserSelection(
    val address: String,
    val systemId: Int,
    /**
     * Advertised model variant, for display only — never a filter.
     *
     * The scanner row already knows whether it admitted an `M3i` or an `M3m`, and a tester running
     * an imperial and a metric bike side by side needs that letter to tell the two rows and the
     * two run screens apart. It rides on the selection rather than being re-derived because the
     * discovered row it came from is pruned the moment the address rotates.
     */
    val model: String = DEFAULT_MODEL,
) {
    init {
        require(address.isNotBlank()) { "A Keiser selection needs the broadcaster's address" }
        require(systemId in 0..0xff) { "System ID is a uint8" }
        require(model.isNotBlank()) { "A Keiser selection needs a model label" }
    }

    /**
     * Stable in-session identity for [MetricOriginId.Advertisement].
     *
     * Keyed on the system ID alone, because that is the half that survives an address rotation and
     * fusion must not see a re-bound bike as a new origin — a new origin rebases every cumulative
     * counter, which is the same frozen-then-reset ride the address binding used to produce. Two
     * bikes cannot collide here: the source tracks exactly one selection, and two consoles sharing
     * a system ID is the collision [KeiserAdvertisementOutcome.IGNORED_ADDRESS_COLLISION] refuses.
     *
     * Dropping the address from the key also removes it from one more structure: `CaptureHooks`
     * already redacted this string, and now there is nothing in it left to redact.
     */
    val sourceKey: String get() = "keiser:#$systemId"

    /** Row and run-screen label, e.g. `M3i #1`. */
    val displayName: String get() = "$model #$systemId"

    /** The same bike, now speaking from [address]. Identity, generation and origin are unchanged. */
    internal fun boundTo(address: String): KeiserSelection = copy(address = address)

    companion object {
        /** Used when a row was admitted without a decodable variant letter in its local name. */
        const val DEFAULT_MODEL = "M3"

        /**
         * `M3`, optionally one variant letter. Anything else is not a model label.
         *
         * Case-insensitive to match `DeviceMatcherRegistry`'s own `^M3` local-name pattern, which
         * is what admitted the row in the first place; a parser stricter than the matcher would
         * drop the letter off exactly the rows the matcher went out of its way to accept.
         */
        private val MODEL_PATTERN = Regex("^M3[A-Za-z]?", RegexOption.IGNORE_CASE)

        /**
         * The model label carried by an advertised local name, or [DEFAULT_MODEL].
         *
         * Normalised so `m3I` and `M3i` produce one label: the matcher admits the name
         * case-insensitively, and two rows that differ only in the case a console was flashed with
         * would otherwise read as two different machines.
         */
        fun modelFromAdvertisedName(name: String?): String {
            val match = name?.trim()?.let { MODEL_PATTERN.find(it) }?.value ?: return DEFAULT_MODEL
            return match.take(2).uppercase() + match.drop(2).lowercase()
        }
    }
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

    /**
     * Realtime telemetry from the selected bike, arriving from a new broadcaster address.
     *
     * Accepted exactly as [ACCEPTED] is — same generation, same origin, same cumulative counters —
     * and reported separately only so a trace and a test can tell a re-binding apart from an
     * uneventful packet.
     */
    ACCEPTED_REBOUND,

    /** Nothing is selected, or this generation has already ended. A late callback lands here. */
    IGNORED_NOT_ACTIVE,

    /** Another broadcaster. The room is full of them. */
    IGNORED_OTHER_ADDRESS,

    /**
     * A second address claiming the selected system ID while the bound one is still live.
     *
     * Two consoles configured to the same number, or a stale duplicate row. The incumbent keeps
     * the binding: it is the one the rider chose and the one currently producing telemetry, and
     * following the newer address would silently move a ride onto somebody else's bike.
     */
    IGNORED_ADDRESS_COLLISION,

    /**
     * A new address carrying the selected system ID, but not carrying on the same ride.
     *
     * The elapsed clock jumped backwards or forwards past what the silence can account for, or the
     * firmware changed. That is a different machine wearing the same console number, not the one
     * this generation belongs to.
     */
    IGNORED_DISCONTINUOUS,

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
 * behavior being asserted is *timing* and a test that waits in real time to prove a two-second
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
 * source alive forever while publishing nothing. The validation scenario's every-fifteen-second
 * truncated frame is the same rule seen from the other side: it is refused, it moves nothing, and
 * the next well-formed frame carries on as though it had never arrived.
 *
 * ## Address rotation is expected, and does not end anything
 *
 * A Keiser broadcasts from a random address and mints a new one whenever its advertiser restarts.
 * The selection therefore names the bike by system ID and merely *binds* an address, which
 * [onAdvertisement] re-learns when the bound one falls silent and a new one carries the same
 * system ID on a ride that demonstrably continues. Generation, origin and cumulative counters all
 * survive it, because nothing about the bike changed — only which random number it announces
 * itself with.
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
    private val speedEstimator = KeiserSpeedEstimator()

    private var selection: KeiserSelection? = null
    private var generation: Long = 0L
    private var confidence: Double = 1.0
    private var lastAcceptedArrivalMs: Long = 0L
    private var statsTimeoutMs: Long = KeiserMSeriesMeasurement.SLOW_STATS_TIMEOUT_MS
    private var state: KeiserSourceState = KeiserSourceState.IDLE
    private var firmware: String? = null

    /**
     * Elapsed clock of the last accepted reading, or null before the first one.
     *
     * The continuity evidence a re-binding is allowed on. It is the one field in the payload that
     * both advances predictably and cannot be guessed by a different bike that happens to share a
     * console number.
     */
    private var lastAcceptedElapsedSeconds: Int? = null

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
        lastAcceptedElapsedSeconds = null
        state = KeiserSourceState.LIVE
        dropoutFilter.reset()
        speedEstimator.reset()

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

        val fromBoundAddress = advertisement.address == selected.address
        val reading = KeiserMSeriesMeasurement.parse(advertisement.payload.copyBytes())
            ?: return@synchronized recordDecisionLocked(
                selected,
                // A short frame from a broadcaster we are not bound to is simply not ours. Only
                // the bound address's malformed frames are worth reporting as malformed, and the
                // simulator emits one every fifteen seconds precisely to prove they are survivable.
                if (fromBoundAddress) {
                    KeiserAdvertisementOutcome.IGNORED_MALFORMED
                } else {
                    KeiserAdvertisementOutcome.IGNORED_OTHER_ADDRESS
                },
            )
        if (reading.systemId != selected.systemId) {
            return@synchronized recordDecisionLocked(
                selected,
                if (fromBoundAddress) {
                    KeiserAdvertisementOutcome.IGNORED_OTHER_SYSTEM_ID
                } else {
                    KeiserAdvertisementOutcome.IGNORED_OTHER_ADDRESS
                },
            )
        }

        // A review-mode replay never moves the binding and never refreshes liveness, whichever
        // address it came from: a bike parked on its summary screen must not be able to steal a
        // ride from the one still transmitting under the number the rider chose.
        if (!reading.isRealtime) {
            return@synchronized recordDecisionLocked(
                selected,
                if (fromBoundAddress) {
                    KeiserAdvertisementOutcome.IGNORED_REVIEW
                } else {
                    KeiserAdvertisementOutcome.IGNORED_OTHER_ADDRESS
                },
            )
        }

        var rebound = false
        if (!fromBoundAddress) {
            val silenceMs = arrivalMs - lastAcceptedArrivalMs
            // The incumbent is still inside its own statistics window, so it has not gone
            // anywhere. Two live addresses under one system ID is a collision, not a rotation.
            if (silenceMs <= statsTimeoutMs) {
                return@synchronized recordDecisionLocked(
                    selected,
                    KeiserAdvertisementOutcome.IGNORED_ADDRESS_COLLISION,
                )
            }
            if (!continuesRideLocked(reading, silenceMs)) {
                return@synchronized recordDecisionLocked(
                    selected,
                    KeiserAdvertisementOutcome.IGNORED_DISCONTINUOUS,
                )
            }
            selection = selected.boundTo(advertisement.address)
            rebound = true
            // No address in an ordinary log (§11). The system ID and the generation are what a
            // reader needs, and they are what the capture already carries.
            Timber.tag(BtEngineState.TAG).i(
                "Keiser bike #%d re-bound to a new broadcaster address after %d ms of silence " +
                    "(generation %d, origin unchanged)",
                selected.systemId,
                silenceMs,
                generation,
            )
        }
        val bound = selection ?: selected

        // Out-of-order delivery is possible and is not evidence of anything going wrong, but it
        // must not drag the statistics clock backwards into an accidental stale state.
        lastAcceptedArrivalMs = maxOf(lastAcceptedArrivalMs, arrivalMs)
        statsTimeoutMs = reading.statsTimeoutMs
        firmware = reading.firmware
        lastAcceptedElapsedSeconds = reading.elapsedSeconds
        state = KeiserSourceState.LIVE
        publishStateLocked()

        val filtered = dropoutFilter.filter(reading)
        val estimatedSpeedKph = speedEstimator.estimateKph(filtered)
        val emitter = MetricEmitter(
            fusion = fusion,
            sourceId = MetricSourceId(
                // Unchanged across a re-binding. The bike did not become a different bike by
                // changing which random address it speaks from, and fusion must not treat it as one.
                originId = MetricOriginId.Advertisement(bound.sourceKey, generation),
                protocol = MetricProtocol.VENDOR,
                endpointId = MetricEndpointId.ManufacturerData(KeiserMSeriesMeasurement.COMPANY_ID),
            ),
            sourceClass = MetricSourceClass.VENDOR_NATIVE,
            arrivalMs = arrivalMs,
            freshnessMs = reading.statsTimeoutMs,
        )
        for (sample in keiserSamples(filtered, estimatedSpeedKph)) {
            emitter.emit(
                metric = sample.metric,
                value = sample.value,
                nativeCumulative = sample.nativeCumulative,
                quality = sample.quality,
                confidence = confidence,
            )
        }
        recordDecisionLocked(
            bound,
            if (rebound) {
                KeiserAdvertisementOutcome.ACCEPTED_REBOUND
            } else {
                KeiserAdvertisementOutcome.ACCEPTED
            },
        )
    }

    /**
     * Whether [reading] plausibly continues the ride this generation has been watching.
     *
     * The only claim being tested is that the console at the far end of a new address is the same
     * console, mid-ride, that went quiet a moment ago — not that it is a Keiser, which the payload
     * already proved, and not that its number matches, which the caller already checked. Two
     * cheap pieces of evidence carry it:
     *
     *  - **Firmware.** A bike does not reflash itself between two advertisements.
     *  - **The elapsed clock.** It advances with the silence and nothing else. A different console
     *    that happens to share a system ID is overwhelmingly likely to be at a different point in
     *    its own ride, and a console that was power-cycled restarts at zero — both of which land
     *    outside the window and are correctly refused.
     *
     * The window is deliberately loose on the forward side: advertisements are lost routinely, so
     * the silence measured here is a lower bound on the silence the bike actually experienced. It
     * is tight on the backward side, where the only legitimate movement is the rounding of a
     * whole-second field.
     *
     * Returns true before the first accepted reading of a generation, when there is nothing to
     * continue from — a bike selected from a row and then rotating before its first accepted
     * packet has no ride to contradict.
     */
    private fun continuesRideLocked(
        reading: KeiserMSeriesMeasurement.Reading,
        silenceMs: Long,
    ): Boolean {
        val previous = lastAcceptedElapsedSeconds ?: return true
        firmware?.let { if (reading.firmware != it) return false }
        val silenceSeconds = (silenceMs / 1_000L).coerceAtLeast(0L)
        val lower = previous - ELAPSED_BACKWARD_TOLERANCE_SECONDS
        val upper = previous + silenceSeconds + ELAPSED_FORWARD_TOLERANCE_SECONDS
        return reading.elapsedSeconds >= lower && reading.elapsedSeconds <= upper
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
        speedEstimator.reset()
        selection = null
        firmware = null
        lastAcceptedElapsedSeconds = null
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

    private companion object {
        /** The elapsed field is whole seconds; only rounding may legitimately move it backwards. */
        const val ELAPSED_BACKWARD_TOLERANCE_SECONDS = 2

        /**
         * Slack above the measured silence, because the measurement is a lower bound: the bike may
         * have stopped transmitting well before the last packet we happened to receive from it.
         */
        const val ELAPSED_FORWARD_TOLERANCE_SECONDS = 15
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
            // Both accepting outcomes carry the window; a re-binding is an acceptance that
            // happened to change an address, not a different kind of event.
            freshnessMs = statsTimeoutMs.takeIf {
                outcome == KeiserAdvertisementOutcome.ACCEPTED ||
                    outcome == KeiserAdvertisementOutcome.ACCEPTED_REBOUND
            },
        )
        return outcome
    }
}
