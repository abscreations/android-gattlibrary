package com.gmail.absolutebollockscreations.Fitbridge.bluetooth.peripheral

import android.os.SystemClock
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine.BtServiceProfiles.FtmsMachineProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Periodically encodes the latest metric snapshot and notifies the connected
 * clients.
 *
 * Runs on its own timer rather than reacting to every upstream packet. That
 * decoupling is the whole point: a machine may deliver notifications irregularly,
 * but FTMS clients expect a steady stream, and a burst of upstream packets must
 * not turn into a burst of downstream notifications.
 *
 * Differences from the old `BtServiceNotifier`:
 *  - Encodes once *per client*, because each link has its own negotiated MTU. The
 *    old notifier built one payload and pushed the same bytes to everyone.
 *  - Emits CSC and Cycling Power measurements, which the old notifier never did.
 *  - Default period is 700 ms rather than 333 ms - see [DEFAULT_PERIOD_MS].
 */
class FtmsPeripheralNotifier(
    private val advertiser: FtmsPeripheralAdvertiser,
    private val store: FtmsMetricStore,
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

    private var job: Job? = null

    @Volatile
    var packetsSent: Long = 0
        private set

    @Volatile
    var completeRecords: Long = 0
        private set

    @Volatile
    var compactedRecords: Long = 0
        private set

    @Volatile
    var fragmentedRecords: Long = 0
        private set

    @Volatile
    var ftmsPacketsSent: Long = 0
        private set

    @Volatile
    var cscPacketsSent: Long = 0
        private set

    @Volatile
    var cpsPacketsSent: Long = 0
        private set

    fun configureCycling(config: CyclingSensorConfig) {
        cyclingConfig = config
        cyclingAccumulator = if (config.enabled) {
            CyclingEventAccumulator(config.wheelCircumferenceMm)
        } else {
            null
        }
    }

    fun setCumulativeWheelRevolutions(value: Long): Boolean {
        val accumulator = cyclingAccumulator ?: return false
        accumulator.setCumulativeWheelRevolutions(value)
        return true
    }

    fun cyclingRevolutions(): CyclingRevolutionSnapshot =
        cyclingAccumulator?.snapshot() ?: CyclingRevolutionSnapshot()

    fun start(scope: CoroutineScope) {
        if (job != null) return
        packetsSent = 0
        completeRecords = 0
        compactedRecords = 0
        fragmentedRecords = 0
        ftmsPacketsSent = 0
        cscPacketsSent = 0
        cpsPacketsSent = 0
        job = scope.launch {
            Timber.tag(TAG).i("notifier started at %dms", periodMs)
            while (isActive) {
                runCatching { tick() }.onFailure { Timber.tag(TAG).w(it, "tick failed") }
                delay(periodMs)
            }
            Timber.tag(TAG).i("notifier stopped")
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    /** Fire one tick immediately when measurement notifications become enabled. */
    fun kick(scope: CoroutineScope) {
        if (job?.isActive != true) return
        // A kick may overlap the periodic loop, but every packet still enters the
        // advertiser's single GATT transaction queue. It cannot collide with
        // another notification or a Control Point indication.
        scope.launch { runCatching { tick() }.onFailure { Timber.tag(TAG).w(it, "kick failed") } }
    }

    private suspend fun tick() {
        val connections = advertiser.activeConnections()
        val profile = advertiser.currentProfile()
        val nowMs = SystemClock.elapsedRealtime()
        // Keep the stream flowing once a workout has started: stale instantaneous
        // metrics come back zeroed, cumulative totals held. Null only before the
        // first sample ever arrives.
        val snapshot = store.snapshotForNotification(nowMs) ?: return
        val revolutions = cyclingAccumulator?.update(snapshot.speedKph, snapshot.cadence, nowMs)
        if (connections.isEmpty()) return
        val needsFtmsMeasurement = connections.any { it.dataSubscribed }
        val speed = if (needsFtmsMeasurement && requiresSpeed(profile)) {
            store.effectiveSpeedKph(nowMs) ?: return
        } else {
            null
        }

        // Encode once per client because each link has its own negotiated MTU (a
        // phone may take one large record while a Connect IQ watch is capped at
        // 20 octets). Notifications for every client funnel through the shared,
        // globally-serialised GattTxQueue, so they never collide on the wire.
        for (conn in connections) {
            if (conn.dataSubscribed) notifyOne(conn, profile, snapshot, speed)
            if (revolutions != null && conn.cscMeasurementSubscribed) {
                val payload = CyclingSensorEncoders.cscMeasurement(revolutions)
                if (advertiser.notifyCscMeasurement(conn.device, payload)) {
                    packetsSent++
                    cscPacketsSent++
                    conn.countPacket()
                }
            }
            if (revolutions != null && conn.cyclingPowerMeasurementSubscribed) {
                val payload = CyclingSensorEncoders.cyclingPowerMeasurement(
                    snapshot.powerWatts ?: 0,
                    revolutions,
                )
                if (advertiser.notifyCyclingPowerMeasurement(conn.device, payload)) {
                    packetsSent++
                    cpsPacketsSent++
                    conn.countPacket()
                }
            }
        }
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
        val packets = FtmsDataEncoders.encode(
            profile,
            snapshot,
            speed,
            conn.maxNotifyPayload,
            payloadMode = FtmsPayloadMode.STRICT_FRAGMENTATION,
            onPackOutcome = { packOutcome = it },
            garminCompatibilityFraming = garminCompatibilityFraming,
        )
        if (packets.isEmpty()) return

        when (packOutcome) {
            FtmsPackOutcome.COMPLETE -> completeRecords++
            FtmsPackOutcome.COMPACTED -> compactedRecords++
            FtmsPackOutcome.FRAGMENTED -> fragmentedRecords++
            null -> Unit
        }

        if (FTMS_VERBOSE_LOGGING) {
            Timber.tag(TAG).d(
                "tick %s client=%s snapshot=%s -> %d packet(s) payloadCap=%dB",
                profile, conn.device.address, describe(snapshot), packets.size,
                conn.maxNotifyPayload,
            )
        }

        for ((i, payload) in packets.withIndex()) {
            val ok = advertiser.notifyData(conn.device, payload)
            if (FTMS_VERBOSE_LOGGING) {
                Timber.tag(TAG).d(
                    "  notify frag %d/%d %dB moreData=%s hex=%s -> %s",
                    i + 1, packets.size, payload.size,
                    (payload[0].toInt() and 0x01) == 1,
                    payload.toHexString(),
                    if (ok) "OK" else "FAIL",
                )
            }
            if (ok) {
                packetsSent++
                ftmsPacketsSent++
                conn.countPacket()
            } else {
                // A failed notify usually means the client went away or the
                // stack's buffer is full. Drop the rest of this record rather
                // than sending a partial fragment set.
                Timber.tag(TAG).w(
                    "notify failed on frag %d/%d; dropping remainder of this record",
                    i + 1, packets.size,
                )
                break
            }
        }
    }

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
         * Confirmed disconnect root cause and resiliency requirement:
         *
         * A 1,000 ms notification period sits directly on Android's one-second
         * no-holder GATT idle timeout. When the app is backgrounded, normal
         * scheduling jitter lets the idle timer fire before the next notification,
         * so affected Samsung/vendor stacks kill an otherwise active connection
         * after only a few packets.
         *
         * Keep this at 700 ms, comfortably below that timeout. The GATT server
         * also explicitly retains inbound links, but the shorter cadence is a
         * second, confirmed defense against background idle disconnects while
         * remaining gentle on the globally serialized notification queue.
         *
         * The previous FitBridge default of 333 ms was safe from that timeout but
         * wasteful: at MTU 23 it put three fragmented records per second on a link
         * whose connection interval may be 990 ms.
         */
        const val DEFAULT_PERIOD_MS = 700L
        const val MIN_PERIOD_MS = 200L
    }
}
