package com.gmail.absolutebollockscreations.gattlibrary.central

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.floor

public enum class MaxProLinkStatus { DISCONNECTED, SUBSCRIBING, LISTENING, STALE, FAILED }
public enum class MaxProDecodeStatus { AWAITING_DATA, V1_CAPTURE_LAYOUT, UNSUPPORTED, MALFORMED }

/** Unmodified unsigned hexadecimal fields. Motion units remain explicitly raw here. */
public data class MaxProRawSide(
    val knobTenths: Int,
    val distanceMillimeters: Int,
    val timeTicks: Int,
    val velocityRaw: Int,
    val accelerationRaw: Int,
    val deviceRepCount: Int,
)

public data class MaxProRawSnapshot(
    val event: Int, val model: Int, val error: Int, val batteryRaw: Int,
    val left: MaxProRawSide, val right: MaxProRawSide,
    val payloadHex: String,
)

/** Derived values use only the captured legacy layout/model; no V2 calibration is assumed. */
public data class MaxProSide(
    val resistanceLevel: Double?,
    val nominalForceNewtons: Double?,
    val deviceRepCount: Int,
    val rawDistanceMeters: Double,
    val calibratedDistanceMeters: Double?,
    val deviceTimeMillis: Int?,
)

public data class MaxProSnapshot(
    val left: MaxProSide, val right: MaxProSide, val arrivalMillis: Long,
)

public data class MaxProState(
    val linkStatus: MaxProLinkStatus = MaxProLinkStatus.DISCONNECTED,
    val decodeStatus: MaxProDecodeStatus = MaxProDecodeStatus.AWAITING_DATA,
    val firmware: String? = null,
    val firmwareQuerySent: Boolean = false,
    val firmwareQueryFailed: Boolean = false,
    val raw: MaxProRawSnapshot? = null,
    val snapshot: MaxProSnapshot? = null,
    val leftForce: MaxProForceSummary = MaxProForceSummary(),
    val rightForce: MaxProForceSummary = MaxProForceSummary(),
    val lastFrame: String? = null,
    val lastNotificationHex: String? = null,
    val lastNotificationMillis: Long? = null,
    val lastTelemetryMillis: Long? = null,
    val notificationsReceived: Long = 0,
    val lastNotificationBytes: Int = 0,
    val maximumNotificationBytes: Int = 0,
    val completedFrames: Long = 0,
    val rejectedFragments: Long = 0,
)

/** Byte-oriented, bounded brace framing; an ATT packet is never assumed to be a frame. */
internal class MaxProFramer(private val maxFrameBytes: Int = 256) {
    private val buffer = StringBuilder()
    var rejectedFragments: Long = 0
        private set

    fun feed(bytes: ByteArray): List<String> = buildList {
        bytes.forEach { byte ->
            val value = byte.toInt() and 0xff
            when {
                value == 0x7b -> {
                    if (buffer.isNotEmpty()) rejectedFragments++
                    buffer.clear()
                    buffer.append('{')
                }
                buffer.isEmpty() -> Unit
                value !in 0x20..0x7e || buffer.length >= maxFrameBytes -> {
                    rejectedFragments++
                    buffer.clear()
                }
                value == 0x7d -> {
                    buffer.append('}')
                    add(buffer.toString())
                    buffer.clear()
                }
                else -> buffer.append(value.toChar())
            }
        }
    }

    fun reset() { buffer.clear(); rejectedFragments = 0 }
}

/** Wire facts traced in Hermes function 27156, offsets 0x00ab..0x0340; see evidence record. */
internal object MaxProV1Decoder {
    fun raw(payload: String): MaxProRawSnapshot? {
        if (payload.length != 46 || payload.any { it !in "0123456789abcdefABCDEF" }) return null
        fun number(start: Int, end: Int) = payload.substring(start, end).toInt(16)
        return MaxProRawSnapshot(
            number(0, 2), number(2, 4), number(4, 6), number(6, 8),
            MaxProRawSide(number(8, 10), number(10, 14), number(14, 17),
                number(17, 20), number(20, 23), number(23, 27)),
            MaxProRawSide(number(27, 29), number(29, 33), number(33, 36),
                number(36, 39), number(39, 42), number(42, 46)),
            payload,
        )
    }

    private val legacyLbf = doubleArrayOf(
        6.5, 8.0, 9.0, 10.0, 11.0, 12.0, 14.0, 17.0, 20.0, 24.0,
        28.0, 33.0, 38.0, 44.0, 52.0, 60.0, 68.0, 78.0, 88.0, 100.0,
        112.0, 124.0, 137.0, 148.0, 158.0,
    )

    fun nominalForceNewtons(level: Double): Double? {
        if (!level.isFinite() || level !in 1.0..25.0) return null
        val low = floor(level).toInt()
        val lbf = if (low == 25) legacyLbf.last() else
            legacyLbf[low - 1] + (level - low) * (legacyLbf[low] - legacyLbf[low - 1])
        return lbf * 4.4482216152605
    }

    fun side(raw: MaxProRawSide): MaxProSide {
        val level = (raw.knobTenths / 10.0).takeIf { it in 1.0..25.0 }
        val distance = raw.distanceMillimeters / 1000.0
        val calibrated = if (distance <= 0) 0.0 else 0.7333 * distance + 0.0234
        val time = raw.timeTicks * 10
        // App constants MAX_DISTANCE_VALUE=5 m and MAX_TIME_VALUE=5000 ms (see evidence).
        // Keep invalid source values in raw, but do not present them as usable motion metrics.
        return MaxProSide(level, level?.let(::nominalForceNewtons), raw.deviceRepCount,
            distance, calibrated.takeIf { it <= 5.0 }, time.takeIf { it <= 5000 })
    }
}

/** Owns one link generation. Stale callbacks cannot clear or publish into its replacement. */
internal class MaxProStore {
    private val mutable = MutableStateFlow(MaxProState())
    val state: StateFlow<MaxProState> = mutable.asStateFlow()
    private var owner: ConnectionId? = null
    private val framer = MaxProFramer()
    private val leftForce = MaxProForceTracker()
    private val rightForce = MaxProForceTracker()

    @Synchronized fun resetActivity() {
        leftForce.reset(mutable.value.snapshot?.left?.deviceRepCount)
        rightForce.reset(mutable.value.snapshot?.right?.deviceRepCount)
        mutable.value = mutable.value.copy(leftForce = leftForce.summary, rightForce = rightForce.summary)
    }

    @Synchronized fun begin(id: ConnectionId) {
        owner = id
        framer.reset()
        leftForce.reset()
        rightForce.reset()
        mutable.value = MaxProState(linkStatus = MaxProLinkStatus.SUBSCRIBING)
    }

    @Synchronized fun status(id: ConnectionId, status: MaxProLinkStatus) {
        if (owner == id) mutable.value = mutable.value.copy(linkStatus = status)
    }

    @Synchronized fun querySent(id: ConnectionId, failed: Boolean = false) {
        if (owner == id) mutable.value = mutable.value.copy(
            firmwareQuerySent = true, firmwareQueryFailed = failed)
    }

    @Synchronized fun end(id: ConnectionId) {
        if (owner != id) return
        owner = null
        framer.reset()
        leftForce.reset()
        rightForce.reset()
        mutable.value = MaxProState()
    }

    @Synchronized fun feed(id: ConnectionId, bytes: ByteArray, nowMs: Long,
        onInterpreted: (MaxProState) -> Unit = {}) {
        if (owner != id) return
        var next = mutable.value.copy(
            notificationsReceived = mutable.value.notificationsReceived + 1,
            lastNotificationBytes = bytes.size,
            maximumNotificationBytes = maxOf(mutable.value.maximumNotificationBytes, bytes.size),
            lastNotificationHex = bytes.take(256).joinToString("") { "%02X".format(it.toInt() and 0xff) },
            lastNotificationMillis = nowMs,
        )
        framer.feed(bytes).forEach { frame ->
            next = next.copy(lastFrame = frame, completedFrames = next.completedFrames + 1)
            if (frame.startsWith("{EVT,")) {
                val payload = frame.substring(5, frame.length - 1)
                val raw = MaxProV1Decoder.raw(payload)
                val status = when {
                    payload.any { it !in "0123456789abcdefABCDEF" } -> MaxProDecodeStatus.MALFORMED
                    raw == null || raw.model != 1 -> MaxProDecodeStatus.UNSUPPORTED
                    else -> MaxProDecodeStatus.V1_CAPTURE_LAYOUT
                }
                next = next.copy(
                    decodeStatus = status,
                    raw = raw.takeIf { status == MaxProDecodeStatus.V1_CAPTURE_LAYOUT },
                    snapshot = if (status == MaxProDecodeStatus.V1_CAPTURE_LAYOUT && raw != null)
                        MaxProSnapshot(MaxProV1Decoder.side(raw.left), MaxProV1Decoder.side(raw.right), nowMs)
                        else null,
                    lastTelemetryMillis = nowMs,
                )
                next.snapshot?.let { snapshot ->
                    next = next.copy(leftForce = leftForce.observe(snapshot.left),
                        rightForce = rightForce.observe(snapshot.right))
                }
                // A diagnostic sink must never interrupt publication of device telemetry.
                runCatching { onInterpreted(next.copy(rejectedFragments = framer.rejectedFragments)) }
            }
            // Firmware replies have no verified grammar yet. Preserve the frame, never guess a version.
        }
        mutable.value = next.copy(rejectedFragments = framer.rejectedFragments)
    }
}
