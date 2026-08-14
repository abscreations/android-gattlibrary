package com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine

import timber.log.Timber

/**
 * Drives [MachineAdapterPolicy] for live connections (plan §0.7a).
 *
 * ### Why this exists
 *
 * Phase 0.7a built and tested the policy but never connected it to the engine, so
 * a liveness demotion could not occur in the running app and the §0.9.5 capture
 * trigger that depends on it was unreachable. This is the missing integration.
 *
 * ### What it does today, and what it does not
 *
 * FitBridge currently implements exactly one machine adapter — standard FTMS —
 * so there is nothing to demote *to*. A timeout therefore records the demotion
 * (which arms a capture, because that record is the whole point) and then lands
 * in the terminal `CONNECTED_NO_DATA` state rather than switching handlers.
 *
 * That is detection, not fallback, and it is deliberately the whole scope here.
 * The moment a second machine adapter exists — Concept2 or Precor in Phase 5 —
 * candidate construction below grows a second entry and the demotion path starts
 * doing what it was written for, with no change to this class's shape.
 *
 * ### The idle-machine problem
 *
 * An FTMS machine with nobody on it legitimately sends no notifications. The plan
 * calls this out for the Concept2 PM5 and it is true of every machine: the user
 * connects, then mounts the machine, then starts moving, and only then do
 * notifications begin. A short liveness timeout would declare a perfectly healthy
 * machine dead during that gap.
 *
 * [DEFAULT_LIVENESS_TIMEOUT_MS] is therefore generous. It is not tuned to catch a
 * fault quickly; it is tuned so that catching a fault at all does not come at the
 * cost of false accusations against working hardware.
 */
internal class AdapterLivenessCoordinator(
    private val livenessTimeoutMs: Long = DEFAULT_LIVENESS_TIMEOUT_MS,
) {

    private val lock = Any()

    /** One policy per live machine link, keyed by connection id. */
    private val policies = LinkedHashMap<ConnectionId, MachineAdapterPolicy>()

    /** Connections already reported terminal, so the log records it once. */
    private val reportedTerminal = mutableSetOf<ConnectionId>()

    /**
     * Called when every CCCD on a machine link has been armed.
     *
     * Candidate construction happens here rather than at service discovery
     * because this is the first moment both facts are known: which services the
     * device actually exposed, and that it accepted every subscription. A device
     * that never completes its CCCD plan has a different problem, and the
     * liveness timer would be measuring the wrong thing.
     */
    fun onSubscriptionsComplete(context: ConnectionContext, nowMs: Long) {
        if (!context.machineConnection) return
        val candidates = candidatesFor(context)
        if (candidates.isEmpty()) return

        synchronized(lock) {
            val policy = policies.getOrPut(context.connectionId) {
                MachineAdapterPolicy(context.connectionId, candidates).also { it.start() }
            }
            if (policy.subscriptionsCompleted(context.connectionId, nowMs)) {
                Timber.tag(TAG).i(
                    "liveness armed for link generation %d, timeout %dms",
                    context.connectionId.linkGeneration,
                    livenessTimeoutMs,
                )
            }
        }
    }

    /**
     * Called for every sample reaching fusion. Only a sample from the *selected*
     * adapter on the owning link clears the timer — a heart-rate sample arriving
     * from a different handler on the same physical connection says nothing about
     * whether the machine adapter is working.
     */
    fun onSample(sample: MetricSample) {
        val policy = synchronized(lock) { policies[sample.connectionId] } ?: return
        policy.metricSampleArrived(sample.connectionId, FTMS_ADAPTER_ID, sample.arrivalMs)
    }

    /** Periodic evaluation. Cheap and allocation-free when nothing has expired. */
    fun tick(nowMs: Long) {
        val snapshots = synchronized(lock) {
            policies.map { (id, policy) -> id to policy.checkLiveness(nowMs) }
        }
        for ((id, snapshot) in snapshots) {
            if (snapshot.state != AdapterPolicyState.CONNECTED_NO_DATA) continue
            val firstReport = synchronized(lock) { reportedTerminal.add(id) }
            if (!firstReport) continue
            Timber.tag(TAG).w(
                "link generation %d connected but produced no data: %s",
                id.linkGeneration,
                snapshot.detail ?: "no detail",
            )
        }
    }

    /** Drops per-link state when a connection generation ends. */
    /**
     * A setup handshake completed. The link leaves `SETUP_REQUIRED` for the ordinary
     * subscription-pending path, which is what lets measurements count as live.
     *
     * Returns whether the policy accepted the transition — false when the link has no policy,
     * or is not in `SETUP_REQUIRED`, which happens naturally if a late callback arrives after
     * the connection has moved on. Silence rather than a throw: a stale callback is a fact of
     * BLE, not an error.
     */
    fun setupSucceeded(connectionId: ConnectionId): Boolean = synchronized(lock) {
        val accepted = policies[connectionId]?.setupSucceeded(connectionId) ?: false
        if (accepted) {
            Timber.tag(TAG).i("setup succeeded for link generation %d", connectionId.linkGeneration)
        }
        accepted
    }

    /**
     * Link-aware setup completion. It creates the policy when setup finishes before the final
     * CCCD and replays subscription completion when descriptors finished first.
     */
    fun setupSucceeded(context: ConnectionContext, nowMs: Long): Boolean {
        if (!context.machineConnection) return false
        val candidates = candidatesFor(context)
        if (candidates.isEmpty()) return false
        return synchronized(lock) {
            val policy = policies.getOrPut(context.connectionId) {
                MachineAdapterPolicy(context.connectionId, candidates).also { it.start() }
            }
            val accepted = policy.setupSucceeded(context.connectionId)
            if (accepted && context.cccd.ready.value) {
                policy.subscriptionsCompleted(context.connectionId, nowMs)
            }
            if (accepted) {
                Timber.tag(TAG).i(
                    "setup succeeded for link generation %d",
                    context.connectionId.linkGeneration,
                )
            }
            accepted
        }
    }

    /**
     * A setup handshake failed. The link becomes `SETUP_FAILED` with [reason] as its detail.
     *
     * [reason] reaches logs and the connection UI, so it must name the step and never the value
     * — see `LifeFitnessSetup.Op`, whose labels exist for this.
     */
    fun setupFailed(connectionId: ConnectionId, reason: String): Boolean = synchronized(lock) {
        val accepted = policies[connectionId]?.setupFailed(connectionId, reason) ?: false
        if (accepted) {
            Timber.tag(TAG).w(
                "setup failed for link generation %d: %s",
                connectionId.linkGeneration,
                reason,
            )
        }
        accepted
    }

    /** Ensure a setup failure is retained even when it precedes subscription completion. */
    fun setupFailed(context: ConnectionContext, reason: String): Boolean {
        if (!context.machineConnection) return false
        val candidates = candidatesFor(context)
        if (candidates.isEmpty()) return false
        return synchronized(lock) {
            val policy = policies.getOrPut(context.connectionId) {
                MachineAdapterPolicy(context.connectionId, candidates).also { it.start() }
            }
            val accepted = policy.setupFailed(context.connectionId, reason)
            if (accepted) {
                Timber.tag(TAG).w(
                    "setup failed for link generation %d: %s",
                    context.connectionId.linkGeneration,
                    reason,
                )
            }
            accepted
        }
    }

    fun forget(connectionId: ConnectionId) {
        synchronized(lock) {
            policies.remove(connectionId)
            reportedTerminal.remove(connectionId)
        }
    }

    fun clear() {
        synchronized(lock) {
            policies.clear()
            reportedTerminal.clear()
        }
    }

    /** Visible for tests and diagnostics. */
    fun stateOf(connectionId: ConnectionId): AdapterPolicyState? =
        synchronized(lock) { policies[connectionId]?.snapshot()?.state }

    /**
     * Which adapter is currently driving this link, or null when no candidate matched.
     *
     * Visible for tests and diagnostics. Needed from Phase 5a onward because a link can now
     * have more than one candidate: on a PM5 advertising both FTMS and the Concept2 Erg
     * service, the state alone no longer says which path the data is coming from (D6).
     */
    fun selectedAdapterId(connectionId: ConnectionId): String? =
        synchronized(lock) { policies[connectionId]?.snapshot()?.selected?.adapterId }

    /**
     * The adapters plausibly able to drive this link, best first.
     *
     * The evidence is structural in every case — the device exposed a particular GATT service —
     * which is the kind of fact §0.7 says to prefer over a device-name guess.
     *
     * ## Why a PM5 can produce two candidates (D6, plan §5a)
     *
     * A Concept2 PM5 may advertise both the standard Fitness Machine Service and its own Erg
     * service. TMIW resolves this by hard-skipping its proprietary Concept2 descriptor whenever
     * FTMS is advertised. D6 rejects that: **both** candidates are registered, FTMS ranked
     * higher because older PM5 firmware genuinely serves usable FTMS Rower data, and the
     * Concept2 candidate left live behind the §0.7a liveness timeout.
     *
     * The difference matters for a firmware revision that advertises FTMS and then sends
     * nothing. Under a hard skip that is a dead session with no recovery. Here the FTMS
     * candidate times out, §0.7a demotes once, and the proprietary path takes over
     * automatically — which is the whole point of ranking candidates rather than choosing one.
     */
    private fun candidatesFor(context: ConnectionContext): List<AdapterCandidate> {
        val services = context.discoveredServiceUuids
        // **The one candidate property that is discovered rather than declared.** Life Fitness
        // machines advertise and serve ordinary FTMS and then send nothing until a proprietary
        // handshake completes, so `requiresSetup` cannot be a constant on the FTMS candidate the
        // way rank and evidence are — it depends on what this particular device turned out to
        // have. §0.7a and D6 were written for exactly this case; the machinery has been waiting
        // for it since Phase 0.
        //
        // Decided from characteristics, not service UUIDs: D52 keeps the runtime pair rule so a
        // firmware variant with different parents still completes setup, and a service-level test
        // would miss precisely that device.
        val requiresLifeFitnessSetup =
            context.lifeFitnessSetupState != LifeFitnessLinkSetupState.NOT_REQUIRED ||
                LifeFitnessFingerprint.isSetupSurface(
                    context.discoveredServices.toLifeFitnessInventory(),
                )
        return buildList {
            if (BluetoothIds.UUID_FTMS_SERVICE in services) {
                add(
                    MachineAdapterCandidate(
                        adapterId = FTMS_ADAPTER_ID,
                        evidence = MatchEvidence(
                            listOf(
                                MatchEvidenceFact(
                                    kind = MatchEvidenceKind.GATT_SERVICE,
                                    structuralValue = BluetoothIds.UUID_FTMS_SERVICE.toString(),
                                    weight = 40,
                                    collisionRisk = CollisionRisk.LOW,
                                    note = "FTMS service present in the discovered GATT database",
                                ),
                            ),
                        ),
                        rank = RANK_FTMS,
                        requiresSetup = requiresLifeFitnessSetup,
                        livenessTimeoutMs = livenessTimeoutMs,
                    ),
                )
            }
            if (BluetoothIds.UUID_C2_ERG_SERVICE in services) {
                add(
                    MachineAdapterCandidate(
                        adapterId = CONCEPT2_ADAPTER_ID,
                        evidence = MatchEvidence(
                            listOf(
                                MatchEvidenceFact(
                                    kind = MatchEvidenceKind.GATT_SERVICE,
                                    // A full 128-bit vendor UUID, resolved from Concept2's
                                    // published specification rather than a four-character
                                    // alias — see the Phase 5a evidence record.
                                    structuralValue = BluetoothIds.UUID_C2_ERG_SERVICE.toString(),
                                    // Deliberately equal to the FTMS fact's weight, not higher.
                                    //
                                    // `MachineAdapterPolicy` orders candidates by evidence
                                    // weight *before* rank, so a heavier fact wins outright and
                                    // `rank` never gets a say. A 128-bit vendor UUID is arguably
                                    // the stronger fingerprint, but both facts are certain
                                    // structural identifications of a device that is present —
                                    // there is no doubt to discriminate. Preference between two
                                    // certain matches belongs in `rank`, which is where D6 puts
                                    // it. Inflating this to 50 would silently invert D6 and put
                                    // the proprietary path first.
                                    weight = 40,
                                    collisionRisk = CollisionRisk.LOW,
                                    note = "Concept2 Erg service present in the discovered GATT database",
                                ),
                            ),
                        ),
                        rank = RANK_CONCEPT2,
                        requiresSetup = false,
                        livenessTimeoutMs = livenessTimeoutMs,
                    ),
                )
            }
            if (BluetoothIds.UUID_PRECOR_SERVICE in services) {
                add(
                    MachineAdapterCandidate(
                        adapterId = PRECOR_ADAPTER_ID,
                        evidence = MatchEvidence(
                            listOf(
                                MatchEvidenceFact(
                                    kind = MatchEvidenceKind.GATT_SERVICE,
                                    // Full 128-bit vendor UUID, read from QZ and recorded in
                                    // plan §10 — not derived from TMIW's `ee07` alias.
                                    structuralValue = BluetoothIds.UUID_PRECOR_SERVICE.toString(),
                                    // Same weight as the other two structural facts, for the
                                    // reason spelled out on the Concept2 fact above: weight
                                    // expresses how certain the identification is, and all
                                    // three are certain. Preference between certain matches is
                                    // `rank`'s job.
                                    weight = 40,
                                    collisionRisk = CollisionRisk.LOW,
                                    note = "Precor Chrono service present in the discovered GATT database",
                                ),
                            ),
                        ),
                        rank = RANK_PRECOR,
                        requiresSetup = false,
                        livenessTimeoutMs = livenessTimeoutMs,
                    ),
                )
            }
        }
    }

    companion object {
        private const val TAG = "AdapterLiveness"

        const val FTMS_ADAPTER_ID = "ftms.generic"
        const val CONCEPT2_ADAPTER_ID = "concept2.pm"
        const val PRECOR_ADAPTER_ID = "precor.chrono"

        /**
         * Standard FTMS outranks the Concept2 proprietary path on a PM5 that advertises both.
         *
         * Not because FTMS is better evidence — the Concept2 Erg service is a full 128-bit
         * vendor UUID and carries *more* weight as a fingerprint — but because a PM5 serving
         * FTMS Rower data is serving a standard both ends already agree on, and older PM5
         * firmware does so usably. The proprietary path is the richer one and stays available
         * behind the liveness timeout; it is second choice, not a worse one.
         */
        const val RANK_FTMS = 100
        const val RANK_CONCEPT2 = 80

        /**
         * Equal to [RANK_CONCEPT2], because the two can never compete.
         *
         * A device advertising both the Concept2 Erg service and the Precor Chrono service does
         * not exist and would not be a coherent machine. Inventing an ordering between them
         * would state a preference nothing can ever act on, and a reader would then have to
         * work out what it meant. Equality says the true thing: these are peers, each the only
         * proprietary candidate on its own hardware, both ranked below standard FTMS for the
         * reason given above.
         */
        const val RANK_PRECOR = 80

        /**
         * 30 s from subscription completion.
         *
         * Chosen against the idle-machine problem rather than for fast fault
         * detection: it has to cover connecting, mounting the machine and
         * starting to move. A machine still silent after half a minute of an
         * active session is genuinely not talking to us.
         */
        const val DEFAULT_LIVENESS_TIMEOUT_MS = 30_000L
    }
}
