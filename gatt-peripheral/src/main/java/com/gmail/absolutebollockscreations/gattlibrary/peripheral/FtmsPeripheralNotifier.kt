package com.gmail.absolutebollockscreations.gattlibrary.peripheral

import android.os.SystemClock
import com.gmail.absolutebollockscreations.gattlibrary.central.BtServiceProfiles.FtmsMachineProfile
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * How long a client pump waits before its next emission.
 *
 * [periodMs] is a **floor, not a period**. When a payload took at least that long
 * to complete — the normal case on a 990 ms connection interval — the next
 * emission starts immediately, so the snapshot is encoded at the moment the link
 * is ready rather than at a fixed tick that bears no relation to the link's
 * capacity. Extracted and tested because collapsing this back into a fixed
 * `delay(periodMs)` would silently restore the behavior this refactor removed.
 */
internal fun emissionDelayMs(periodMs: Long, elapsedMs: Long): Long =
    (periodMs - elapsedMs).coerceAtLeast(0L)

/**
 * What an `onNotificationSent` callback actually told us.
 *
 * Android fires that callback when the stack **accepts** a notification into its
 * controller buffer, not when the radio transmits it. Nothing in the Android API
 * reports transmission. So every latency figure derived from it is an acceptance
 * latency, and on a busy link acceptance and transmission are minutes of meaning
 * apart.
 */
internal enum class DeliveryConfidence {
    /** Acceptance took at least one connection interval: the previous packet had to go out first. */
    TRANSMITTED,

    /**
     * Acceptance was faster than one connection interval, so the packet cannot
     * have reached the air yet — it is sitting in the controller buffer.
     */
    BUFFERED,

    /** No negotiated interval known yet, so acceptance cannot be interpreted at all. */
    UNKNOWN,
}

/**
 * How much of a reported latency can be believed.
 *
 * The inference is a hard physical bound rather than a heuristic: a peripheral
 * transmits only during a connection event, so a notification cannot be on the
 * air sooner than the next one. If the stack accepted a payload in less time
 * than a single interval, that payload is provably still queued.
 *
 * Where it is buffered, the true age at transmission is therefore somewhere in
 * `[measured, measured + interval]`. Reporting the upper bound alongside the
 * measurement is the honest form: the measurement alone reads healthy precisely
 * when the link is most congested.
 *
 * This is the instrument that was missing. Three sessions reported acceptance
 * under 30 ms and nothing superseded while the on-air rate was a third of the
 * production rate, because acceptance latency is exactly the quantity that
 * *improves* as the buffer fills.
 */
internal data class DeliveryEstimate(
    val confidence: DeliveryConfidence,
    /** Snapshot age at the moment the stack accepted the payload. */
    val measuredStalenessMs: Long,
    /** Worst-case snapshot age when the payload actually reaches the air. */
    val stalenessUpperBoundMs: Long,
)

internal fun estimateDelivery(
    measuredStalenessMs: Long,
    stackAcceptMs: Long,
    connectionIntervalMs: Int,
): DeliveryEstimate = when {
    connectionIntervalMs <= 0 -> DeliveryEstimate(
        DeliveryConfidence.UNKNOWN,
        measuredStalenessMs,
        measuredStalenessMs,
    )

    stackAcceptMs >= connectionIntervalMs -> DeliveryEstimate(
        DeliveryConfidence.TRANSMITTED,
        measuredStalenessMs,
        measuredStalenessMs,
    )

    else -> DeliveryEstimate(
        DeliveryConfidence.BUFFERED,
        measuredStalenessMs,
        measuredStalenessMs + connectionIntervalMs,
    )
}

/**
 * Encodes the latest metric snapshot and notifies each connected client.
 *
 * Runs on its own schedule rather than reacting to every upstream packet. That
 * decoupling is the whole point: a machine may deliver notifications irregularly,
 * but FTMS clients expect a steady stream, and a burst of upstream packets must
 * not turn into a burst of downstream notifications.
 *
 * ### Why each client has its own pump
 *
 * The previous version ran one tick loop that awaited each client's send in turn,
 * inside a fixed `delay(periodMs)`. Two consequences, both observed as a Garmin
 * watch falling progressively further behind the tablet:
 *
 * 1. A slow client stalled every other client's notifications and stretched the
 *    tick period itself, because the loop awaited each send serially.
 * 2. The fixed period had no relationship to what the link could carry. At a
 *    990 ms connection interval against a 700 ms tick, records were produced
 *    faster than they could be delivered and the old FIFO queue banked the
 *    backlog — see [GattTxQueue].
 *
 * Now each connection gets its own pump. A pump encodes a snapshot, sends it as
 * one atomic payload, and *waits for that payload to finish* before encoding the
 * next. Data is therefore encoded at the moment the link is ready for it rather
 * than up to a period earlier, which is the freshest a snapshot can be. The
 * period becomes a floor: if a send completes quickly the pump waits out the
 * remainder, and if it takes longer than the period the next send starts
 * immediately.
 *
 * The conflation slots in [GattTxQueue] remain the enforced ceiling — at most one
 * payload queued per client per characteristic — but with pumps pacing themselves
 * off completion, the queued slot is normally empty. An empty queued slot is the
 * ideal state: it means nothing stale is waiting to be sent.
 */
class FtmsPeripheralNotifier(
    private val advertiser: FtmsPeripheralAdvertiser,
    private val store: FtmsMetricStore,
    private val onCountersChanged: () -> Unit = {},
) {

    @Volatile
    var periodMs: Long = DEFAULT_PERIOD_MS
        set(value) {
            field = value.coerceAtLeast(MIN_PERIOD_MS)
        }

    @Volatile
    var garminCompatibilityFraming: Boolean = false

    @Volatile
    private var cyclingConfig: CyclingSensorConfig = CyclingSensorConfig.DISABLED
    private var cyclingAccumulator: CyclingEventAccumulator? = null

    /**
     * Latest revolution counts, advanced by the supervisor exactly once per
     * period. The accumulator integrates speed and cadence over elapsed time, so
     * it must not be advanced once per client — that would multiply the
     * revolution counts by the number of connected clients.
     */
    @Volatile
    private var latestRevolutions: CyclingRevolutionSnapshot? = null

    private var job: Job? = null

    // Counters are atomic because several client pumps now update them
    // concurrently. The previous single tick loop made plain fields safe; per-
    // connection pumps do not.
    private val packets = AtomicLong(0L)
    private val complete = AtomicLong(0L)
    private val compacted = AtomicLong(0L)
    private val fragmented = AtomicLong(0L)
    private val ftmsPackets = AtomicLong(0L)
    private val cscPackets = AtomicLong(0L)
    private val cpsPackets = AtomicLong(0L)
    private val superseded = AtomicLong(0L)
    private val stalenessLast = AtomicLong(0L)
    private val stalenessMax = AtomicLong(0L)
    private val stalenessSamples = AtomicLong(0L)
    private val stalenessTotal = AtomicLong(0L)

    // Upper bound on age at transmission, not at stack acceptance. See
    // [estimateDelivery] for why the measured figure alone cannot be trusted.
    private val stalenessUpperLast = AtomicLong(0L)
    private val stalenessUpperMax = AtomicLong(0L)

    // How many acknowledgements could be believed, and how many were provably
    // just the controller taking the packet off our hands.
    private val transmittedAcks = AtomicLong(0L)
    private val bufferedAcks = AtomicLong(0L)
    private val unknownAcks = AtomicLong(0L)

    val packetsSent: Long get() = packets.get()
    val completeRecords: Long get() = complete.get()
    val compactedRecords: Long get() = compacted.get()
    val fragmentedRecords: Long get() = fragmented.get()
    val ftmsPacketsSent: Long get() = ftmsPackets.get()
    val cscPacketsSent: Long get() = cscPackets.get()
    val cpsPacketsSent: Long get() = cpsPackets.get()

    /**
     * Records superseded because a newer one was ready before the link was free.
     * Expected to be non-zero on a slow client; it is the mechanism working, not
     * a fault. A large and growing value alongside a large [maxStalenessMs] is
     * what a genuinely overloaded link looks like.
     */
    val supersededRecords: Long get() = superseded.get()

    /**
     * Age of the snapshot when the **stack accepted** it — not when the client
     * received it. Android reports no transmission event, so this is the most
     * that can be measured directly. Read it alongside [maxStalenessUpperMs].
     */
    val lastStalenessMs: Long get() = stalenessLast.get()

    val maxStalenessMs: Long get() = stalenessMax.get()

    /**
     * Worst-case age when the record actually reaches the air.
     *
     * Equal to the measured figure when acceptance took at least one connection
     * interval — that packet had to wait for a real transmission opportunity, so
     * the measurement is sound. Otherwise the measured figure plus one interval,
     * because the packet is provably still queued and the next connection event
     * is the earliest it can leave.
     */
    val lastStalenessUpperMs: Long get() = stalenessUpperLast.get()

    val maxStalenessUpperMs: Long get() = stalenessUpperMax.get()

    /** Acknowledgements slow enough to have involved a real transmission. */
    val transmittedAckCount: Long get() = transmittedAcks.get()

    /** Acknowledgements provably returned before the packet could reach the air. */
    val bufferedAckCount: Long get() = bufferedAcks.get()

    /** Acknowledgements taken before any connection interval was known. */
    val unknownAckCount: Long get() = unknownAcks.get()

    val meanStalenessMs: Long
        get() {
            val samples = stalenessSamples.get()
            return if (samples == 0L) 0L else stalenessTotal.get() / samples
        }

    fun configureCycling(config: CyclingSensorConfig) {
        cyclingConfig = config
        cyclingAccumulator = if (config.enabled) {
            CyclingEventAccumulator(config.wheelCircumferenceMm)
        } else {
            null
        }
        latestRevolutions = null
    }

    fun setCumulativeWheelRevolutions(value: Long): Boolean {
        if (!store.snapshot.allowsCscCumulativeWheelReset()) return false
        val accumulator = cyclingAccumulator ?: return false
        accumulator.setCumulativeWheelRevolutions(value)
        return true
    }

    fun cyclingRevolutions(): CyclingRevolutionSnapshot =
        cyclingAccumulator?.snapshot() ?: CyclingRevolutionSnapshot()

    fun start(scope: CoroutineScope) {
        if (job != null) return
        listOf(
            packets, complete, compacted, fragmented,
            ftmsPackets, cscPackets, cpsPackets, superseded,
            stalenessLast, stalenessMax, stalenessSamples, stalenessTotal,
            stalenessUpperLast, stalenessUpperMax,
            transmittedAcks, bufferedAcks, unknownAcks,
        ).forEach { it.set(0L) }
        latestRevolutions = null
        job = scope.launch {
            Timber.tag(TAG).i("notifier started, floor=%dms", periodMs)
            supervise()
            Timber.tag(TAG).i("notifier stopped")
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    /**
     * Fire one extra emission immediately when measurement notifications become
     * enabled, so a client that has just subscribed does not wait a full period.
     *
     * Safe to overlap a pump: every record still enters the conflating queue, so
     * the worst case is that one of the two is superseded by the other.
     */
    fun kick(scope: CoroutineScope) {
        if (job?.isActive != true) return
        scope.launch {
            try {
                coroutineScope {
                    // Concurrently, so a slow client cannot delay the kick to a
                    // fast one - the same reason each client has its own pump.
                    advertiser.activeConnections().forEach { conn ->
                        launch { emitOnce(conn) }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                Timber.tag(TAG).w(failure, "kick failed")
            }
        }
    }

    /**
     * Owns the shared per-period work — advancing the cycling accumulator and
     * logging diagnostics — and keeps one pump alive per connected client.
     */
    private suspend fun supervise() = coroutineScope {
        val pumps = mutableMapOf<String, Job>()
        var lastSummaryAtMs = SystemClock.elapsedRealtime()

        try {
            while (isActive) {
                val nowMs = SystemClock.elapsedRealtime()
                advanceCyclingAccumulator(nowMs)

                val connections = advertiser.activeConnections()
                val live = connections.associateBy { pumpKey(it) }
                for ((key, conn) in live) {
                    if (pumps[key]?.isActive != true) {
                        pumps[key] = launch { pump(conn) }
                    }
                }
                // A reconnect produces a new sessionId and therefore a new key,
                // so the stale pump is cancelled rather than left writing to a
                // dead session.
                val gone = pumps.keys - live.keys
                for (key in gone) {
                    pumps.remove(key)?.cancel()
                }

                if (nowMs - lastSummaryAtMs >= SUMMARY_INTERVAL_MS) {
                    lastSummaryAtMs = nowMs
                    logSummary(pumps.size)
                }

                delay(periodMs)
            }
        } finally {
            pumps.values.forEach { it.cancel() }
        }
    }

    /**
     * One client's send loop.
     *
     * [periodMs] is a floor, not a period: the next emission starts as soon as
     * the previous payload finishes, unless less than a period has elapsed.
     */
    private suspend fun pump(conn: PeripheralConnection) = coroutineScope {
        while (isActive) {
            val startedAtMs = SystemClock.elapsedRealtime()
            try {
                emitOnce(conn)
            } catch (cancelled: CancellationException) {
                // The pump is being torn down; never swallow this, or the loop
                // keeps running against a dead session.
                throw cancelled
            } catch (failure: Throwable) {
                Timber.tag(TAG).w(failure, "emit failed for %s", conn.device.address)
            }
            val elapsed = SystemClock.elapsedRealtime() - startedAtMs
            // Paced to the link, not to a constant. The configured period is a
            // lower bound on how often we *want* to emit; the negotiated
            // connection interval is a hard bound on how often the client can
            // actually be reached, and exceeding it only builds a queue in front
            // of that client's own ATT requests.
            val floor = maxOf(periodMs, conn.emissionFloorMs)
            val remaining = emissionDelayMs(floor, elapsed)
            if (remaining > 0) delay(remaining)
        }
    }

    /**
     * Encodes and sends every subscribed characteristic for one client.
     *
     * The three characteristics are sent concurrently so they occupy independent
     * conflation slots. Sending them serially would make an FTMS record wait
     * behind CSC and CPS on a slow link, tripling its effective period.
     */
    private suspend fun emitOnce(conn: PeripheralConnection) {
        // Re-read per emission: the machine profile can change mid-session.
        val profile = advertiser.currentProfile()
        val nowMs = SystemClock.elapsedRealtime()
        // Keep the stream flowing once a workout has started: stale instantaneous
        // metrics come back zeroed, cumulative totals held. Null only before the
        // first sample ever arrives.
        val snapshot = store.snapshotForNotification(nowMs) ?: return
        val revolutions = latestRevolutions

        val speed = if (conn.dataSubscribed && requiresSpeed(profile)) {
            store.effectiveSpeedKph(nowMs) ?: return
        } else {
            null
        }

        coroutineScope {
            val pending = mutableListOf<Deferred<Unit>>()
            if (conn.dataSubscribed) {
                pending += async { notifyOne(conn, profile, snapshot, speed) }
            }
            if (revolutions != null && conn.cscMeasurementSubscribed) {
                pending += async {
                    val payload = CyclingSensorEncoders.cscMeasurement(revolutions)
                    countCyclingOutcome(
                        outcome = advertiser.notifyCscMeasurement(conn.device, payload),
                        counter = cscPackets,
                        conn = conn,
                    )
                }
            }
            if (revolutions != null && conn.cyclingPowerMeasurementSubscribed) {
                pending += async {
                    val payload = CyclingSensorEncoders.cyclingPowerMeasurement(
                        snapshot.powerWatts ?: 0,
                        revolutions,
                        snapshot.cpsNativeEvents,
                    )
                    countCyclingOutcome(
                        outcome = advertiser.notifyCyclingPowerMeasurement(conn.device, payload),
                        counter = cpsPackets,
                        conn = conn,
                    )
                }
            }
            pending.awaitAll()
            if (pending.isNotEmpty()) reportCountersChanged()
        }
    }

    private fun reportCountersChanged() {
        runCatching(onCountersChanged)
            .onFailure { Timber.tag(TAG).w(it, "downstream counter status callback failed") }
    }

    private fun countCyclingOutcome(
        outcome: GattTxQueue.Outcome,
        counter: AtomicLong,
        conn: PeripheralConnection,
    ) {
        when (outcome) {
            GattTxQueue.Outcome.DELIVERED -> {
                packets.incrementAndGet()
                counter.incrementAndGet()
                conn.countPacket()
            }

            // Cumulative counters, so a skipped update self-heals on the next one.
            GattTxQueue.Outcome.SUPERSEDED -> superseded.incrementAndGet()
            GattTxQueue.Outcome.FAILED -> Unit
        }
    }

    private fun advanceCyclingAccumulator(nowMs: Long) {
        val accumulator = cyclingAccumulator ?: return
        val snapshot = store.snapshotForNotification(nowMs) ?: return
        latestRevolutions = accumulator
            .update(snapshot.speedKph, snapshot.cadence, nowMs)
            .withNativeEvents(snapshot.cscNativeEvents)
    }

    private suspend fun notifyOne(
        conn: PeripheralConnection,
        profile: FtmsMachineProfile,
        snapshot: FtmsMetricSnapshot,
        speed: Double?,
    ) {
        // Preserve every populated metric. When the negotiated ATT payload is too
        // small, FTMS More Data continuation frames carry the overflow.
        var packOutcome: FtmsPackOutcome? = null
        val frames = FtmsDataEncoders.encode(
            profile,
            snapshot,
            speed,
            conn.maxNotifyPayload,
            payloadMode = FtmsPayloadMode.STRICT_FRAGMENTATION,
            onPackOutcome = { packOutcome = it },
            // Automatic per link, because the constraint is that link's payload
            // budget rather than anything about the vendor. The manual flag stays
            // as a force-on override for a client that needs it despite a roomy
            // MTU; it can add compact framing, never remove it.
            garminCompatibilityFraming = garminCompatibilityFraming || conn.prefersCompactFraming,
        )
        if (frames.isEmpty()) return

        when (packOutcome) {
            FtmsPackOutcome.COMPLETE -> complete.incrementAndGet()
            FtmsPackOutcome.COMPACTED -> compacted.incrementAndGet()
            FtmsPackOutcome.FRAGMENTED -> fragmented.incrementAndGet()
            null -> Unit
        }

        if (FTMS_VERBOSE_LOGGING) {
            Timber.tag(TAG).d(
                "emit %s client=%s snapshot=%s -> %d frame(s) payloadCap=%dB",
                profile, conn.device.address, describe(snapshot), frames.size,
                conn.maxNotifyPayload,
            )
        }

        // The whole record goes as one atomic payload. Fragmented records can no
        // longer be half-sent: the queue either delivers every frame or reports
        // the record failed, and supersession happens only at record boundaries.
        val handedOverAtMs = SystemClock.elapsedRealtime()
        when (advertiser.notifyDataRecord(conn.device, frames)) {
            GattTxQueue.Outcome.DELIVERED -> {
                packets.addAndGet(frames.size.toLong())
                ftmsPackets.addAndGet(frames.size.toLong())
                repeat(frames.size) { conn.countPacket() }
                recordStaleness(
                    snapshot = snapshot,
                    stackAcceptMs = SystemClock.elapsedRealtime() - handedOverAtMs,
                    connectionIntervalMs = conn.connectionIntervalMs,
                )
            }

            GattTxQueue.Outcome.SUPERSEDED -> {
                // Normal on a slow link: a newer record was ready before this one
                // reached the wire, so the client gets the newer one instead.
                // Deliberately not a warning - this is the mechanism working.
                superseded.incrementAndGet()
            }

            GattTxQueue.Outcome.FAILED -> {
                Timber.tag(TAG).w(
                    "record of %d frame(s) not delivered to %s",
                    frames.size, conn.device.address,
                )
            }
        }
    }

    /**
     * Age of the data at the instant the client acknowledged it. This is the
     * number that describes the user-visible symptom — how far behind the watch
     * is — so it is measured on acknowledgement rather than on encode.
     */
    /**
     * Records how stale a record was when the stack accepted it, **and how stale
     * it could still be when it actually reaches the air**.
     *
     * Only the first of those is measurable. The second is a bound derived from
     * the connection interval, and it exists because the measurement alone is
     * systematically optimistic: acceptance latency falls as the controller
     * buffer fills, so the number looks best exactly when the link is worst.
     */
    private fun recordStaleness(
        snapshot: FtmsMetricSnapshot,
        stackAcceptMs: Long,
        connectionIntervalMs: Int,
    ) {
        if (snapshot.updatedAtMs <= 0L) return
        val age = SystemClock.elapsedRealtime() - snapshot.updatedAtMs
        if (age < 0L) return

        val estimate = estimateDelivery(age, stackAcceptMs, connectionIntervalMs)
        when (estimate.confidence) {
            DeliveryConfidence.TRANSMITTED -> transmittedAcks.incrementAndGet()
            DeliveryConfidence.BUFFERED -> bufferedAcks.incrementAndGet()
            DeliveryConfidence.UNKNOWN -> unknownAcks.incrementAndGet()
        }

        stalenessLast.set(age)
        stalenessMax.updateAndGet { previous -> maxOf(previous, age) }
        stalenessSamples.incrementAndGet()
        stalenessTotal.addAndGet(age)
        stalenessUpperLast.set(estimate.stalenessUpperBoundMs)
        stalenessUpperMax.updateAndGet { previous ->
            maxOf(previous, estimate.stalenessUpperBoundMs)
        }
    }

    private fun logSummary(pumpCount: Int) {
        val tx = advertiser.txStats()
        val pacing = advertiser.activeConnections().joinToString(",") { conn ->
            "${conn.attMtu}/~${conn.assumedConnectionIntervalMs}ms->${maxOf(periodMs, conn.emissionFloorMs)}ms"
        }.ifBlank { "none" }
        val acks = transmittedAckCount + bufferedAckCount + unknownAckCount
        val bufferedPct = if (acks == 0L) 0L else bufferedAckCount * 100 / acks
        Timber.tag(TAG).i(
            "downstream pumps=%d pacing[mtu/~interval->floor]=%s | " +
                "staleness measured last=%dms mean=%dms max=%dms" +
                " / at-air-worst-case last=%dms max=%dms | " +
                "acks transmitted=%d buffered=%d(%d%%) unknown=%d | " +
                "records delivered=%d superseded=%d failed=%d | " +
                "stackAccept last=%dms mean=%dms max=%dms | queued data=%d control=%d",
            pumpCount,
            pacing,
            lastStalenessMs, meanStalenessMs, maxStalenessMs,
            lastStalenessUpperMs, maxStalenessUpperMs,
            transmittedAckCount, bufferedAckCount, bufferedPct, unknownAckCount,
            tx.delivered, tx.superseded, tx.failed,
            tx.lastAckLatencyMs, tx.meanAckLatencyMs, tx.maxAckLatencyMs,
            tx.queuedDataPayloads, tx.queuedControlPayloads,
        )
    }

    private fun pumpKey(conn: PeripheralConnection): String =
        "${conn.device.address.lowercase()}#${conn.sessionId}"

    /** Compact snapshot dump: only the fields that are actually populated. */
    private fun describe(s: FtmsMetricSnapshot): String = buildList {
        s.speedKph?.let { add("spd=$it") }
        s.paceSecondsPer500m?.let { add("pace500=$it") }
        s.averagePaceSecondsPer500m?.let { add("avgPace500=$it") }
        s.cadence?.let { add("cad=$it") }
        s.powerWatts?.let { add("pwr=$it") }
        s.forceOnBeltNewtons?.let { add("force=$it") }
        s.heartRate?.let { add("hr=$it") }
        s.distanceMeters?.let { add("dist=$it") }
        s.elapsedSeconds?.let { add("t=$it") }
        s.caloriesTotal?.let { add("kcal=$it") }
        s.resistance?.let { add("res=$it") }
        s.inclinationPercent?.let { add("inc=$it") }
        s.positiveElevationGainMeters?.let { add("up=$it") }
        s.negativeElevationGainMeters?.let { add("down=$it") }
        s.floors?.let { add("floors=$it") }
        s.strokeCount?.let { add("stride=$it") }
    }.joinToString(",", "[", "]")

    companion object {
        private const val TAG = "FtmsNotifier"

        /**
         * Emission floor, not a fixed period.
         *
         * Two constraints bound it. Below: a 1,000 ms cadence sits directly on
         * Android's one-second no-holder GATT idle timeout, and normal scheduling
         * jitter then lets the idle timer fire before the next notification, so
         * affected Samsung/vendor stacks kill an otherwise active connection.
         * 250 ms is comfortably clear of that — more so than the previous 700 ms.
         *
         * Above: there is no point encoding faster than a client can consume,
         * but because pumps pace themselves off payload completion, a shorter
         * floor costs nothing on a slow link. It only takes effect when the link
         * is fast enough to finish a payload in under 250 ms, and there it caps
         * the radio and encode cost.
         *
         * The old 700 ms value was chosen against a FIFO queue that banked the
         * overflow. With conflation the trade-off inverts: a shorter floor means
         * fresher data, and surplus records are dropped rather than queued.
         */
        const val DEFAULT_PERIOD_MS = 250L
        const val MIN_PERIOD_MS = 100L

        /** How often the downstream-lag summary is written to the log. */
        private const val SUMMARY_INTERVAL_MS = 30_000L
    }
}
