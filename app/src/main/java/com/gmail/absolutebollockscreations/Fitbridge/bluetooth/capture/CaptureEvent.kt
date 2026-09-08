package com.gmail.absolutebollockscreations.Fitbridge.bluetooth.capture

import java.util.UUID

/**
 * The diagnostic capture event model (plan §0.9.4) with structural redaction
 * (§0.9.6).
 *
 * Four consumers drive the schema: unknown-device triage, fingerprint discovery
 * after a §0.7a liveness demotion, fixture generation for §5, and protocol
 * specification for the QZ phases. Each is served by recording **raw bytes
 * alongside the decoded samples they produced**, so a capture can be replayed
 * and checked rather than merely read.
 *
 * ### Redaction is a property of the type
 *
 * §0.9.6 requires that personal data be *impossible* to log rather than scrubbed
 * on the way out. That is enforced here in two ways:
 *
 * 1. [RedactedWriteEvent] has **no field capable of holding a payload**. No later
 *    edit can accidentally reintroduce the bytes, because there is nowhere to put
 *    them.
 * 2. [WriteEvent] has a private constructor. The only way to obtain one is
 *    [WriteEvent.of], which consults [SensitiveCharacteristics] and the caller's
 *    declared intent and returns the redacted type instead when either says so.
 *
 * A regex over hex dumps would eventually miss a field. A constructor that cannot
 * be reached will not.
 *
 * ### Identifiers
 *
 * Per D14 and §0.9.6, [CaptureEvent.conn] is a **session-scoped label** — `c1`,
 * `c2` — assigned by [ConnectionLabels]. No MAC address appears in any event
 * body. Addresses may appear in the file header only when the user opts in while
 * sharing.
 *
 * Byte-carrying events are data classes whose generated `equals` compares arrays
 * by identity. That is deliberate and harmless: nothing compares events, and the
 * tests assert on encoded lines rather than on instances.
 */
internal sealed interface CaptureEvent {

    /** The `ev` discriminator written to the wire. */
    val ev: String

    /** Session-scoped connection label, or null for connection-less events. */
    val conn: String?
}

/**
 * Which side of the bridge an event belongs to.
 *
 * FitBridge is simultaneously a GATT **client** to fitness machines and a GATT
 * **server** to watches and apps. Both directions produce the same event shapes,
 * and mixing them in one file makes a capture actively misleading: a `notify`
 * would mean "received from the machine" on one line and "sent to the watch" on
 * the next.
 *
 * Each role therefore gets its own reserved tag and its own file. The role is
 * **not** written into the JSON — the filename carries it, and keeping the line
 * format identical means one parser reads both and the §5 fixture pipeline needs
 * no special case.
 *
 * Sequence numbers are shared across roles on purpose. Interleaving the two files
 * by `seq` reconstructs cause and effect across the bridge: the upstream
 * notification that produced a sample, and the downstream notification that
 * carried it onward.
 */
internal enum class CaptureRole(val tag: String, val fileSuffix: String) {
    /** FitBridge as GATT client. `notify` and `read` mean *received*. */
    CENTRAL("BLECAP", "central"),

    /** FitBridge as GATT server. `notify` means *sent*; `write` and `read` are client-initiated. */
    PERIPHERAL("BLECAP-P", "peripheral"),
    ;

    companion object {
        /** True for any reserved capture tag. Human-log trees reject all of them. */
        fun isCaptureTag(tag: String?): Boolean = entries.any { it.tag == tag }
    }
}

/** Why a payload was withheld. Written verbatim as the `redacted` field. */
internal enum class RedactionReason(val wireName: String) {
    /** Name, email, age, gender, height, weight — Life Fitness (Phase 6). */
    USER_PROFILE("user_profile"),

    /** Athlete weight in a session handshake — Kayak First (Phase 8). */
    ATHLETE_WEIGHT("athlete_weight"),

    /** Vendor authentication tokens. Not personal data, but not ours to publish. */
    VENDOR_TOKEN("vendor_token"),
}

/**
 * Characteristics whose payloads must never reach a capture file.
 *
 * Seeded with the Life Fitness vendor tokens resolved from QZ and recorded in
 * plan §10. The Life Fitness *user* service characteristics carry the actual
 * profile fields but their full UUIDs are unresolved (§10 lists them as needing
 * runtime discovery), so Phase 6 declares those writes through
 * [WriteEvent.of]'s `redactAs` parameter instead. Both routes produce the same
 * payload-free type.
 *
 * Deliberately immutable: a registry that could be mutated at runtime is a
 * registry that can be mutated too late.
 */
internal object SensitiveCharacteristics {

    private val byUuid: Map<UUID, RedactionReason> = mapOf(
        // Life Fitness vendor token 1 — 32-byte ASCII hex string.
        UUID.fromString("5da54cf2-9cb2-11e5-8994-feff819cdc9f") to RedactionReason.VENDOR_TOKEN,
        // Life Fitness vendor token 2.
        UUID.fromString("ce78e85e-9cb6-11e5-8994-feff819cdc9f") to RedactionReason.VENDOR_TOKEN,

        // ── SIG User Data Service (Phase 6) ──────────────────────────────────────
        //
        // **The personal data is here, not on the proprietary UUIDs.** Until 2026-08-12 this
        // registry held only the two vendor tokens, which protected Life Fitness's secrets and
        // would have published the user's name and email address the moment the setup transcript
        // ran. Plan §6 is explicit that registration is the failure mode: the mechanism cannot
        // leak, but a characteristic nobody registered is not protected by it.
        //
        // Registered by UUID rather than by family, so they are redacted wherever they appear.
        // A name written to any device is personal data; nothing about that depends on which
        // machine is receiving it.
        UUID.fromString("00002a8a-0000-1000-8000-00805f9b34fb") to RedactionReason.USER_PROFILE,
        UUID.fromString("00002a90-0000-1000-8000-00805f9b34fb") to RedactionReason.USER_PROFILE,
        UUID.fromString("00002a87-0000-1000-8000-00805f9b34fb") to RedactionReason.USER_PROFILE,
        UUID.fromString("00002a80-0000-1000-8000-00805f9b34fb") to RedactionReason.USER_PROFILE,
        UUID.fromString("00002a8c-0000-1000-8000-00805f9b34fb") to RedactionReason.USER_PROFILE,
        UUID.fromString("00002aa2-0000-1000-8000-00805f9b34fb") to RedactionReason.USER_PROFILE,

        // Weight (2A98) and height (2A8E) are deliberately absent under D102. They are model
        // inputs rather than identifiers, a capture that hides them cannot diagnose a wrong power
        // figure, and captures are armed and shared by the user rather than collected. See
        // BluetoothIds.DIAGNOSTICALLY_RETAINED_CHARACTERISTICS for the reasoning and the test that
        // pins it. Everything above identifies a person and stays registered.
    )

    fun reasonFor(characteristic: UUID): RedactionReason? = byUuid[characteristic]

    /** Every registered characteristic, for the redaction test to enumerate. */
    fun all(): Map<UUID, RedactionReason> = byUuid
}

// ── connection-less events ────────────────────────────────────────────────────

/**
 * One advertisement. **Raw payloads, not parsed** — the Keiser M3i case proves a
 * parsed-only record is insufficient, because the whole metric stream lives in
 * manufacturer data this app does not yet decode.
 */
internal data class ScanEvent(
    val name: String?,
    val rssi: Int,
    val serviceUuids: List<String>,
    /** Company identifier → raw manufacturer payload. */
    val manufacturerData: Map<Int, ByteArray>,
    /** Service UUID → raw service data. */
    val serviceData: Map<String, ByteArray>,
) : CaptureEvent {
    override val ev: String get() = "scan"
    override val conn: String? get() = null
}

/** Ring-buffer or writer-queue overflow marker. */
internal data class DroppedEvent(val count: Int) : CaptureEvent {
    override val ev: String get() = "dropped"
    override val conn: String? get() = null
}

// ── connection lifecycle ──────────────────────────────────────────────────────

internal data class ConnectEvent(
    override val conn: String,
    /** Link generation from §0.2, so a reconnect is distinguishable. */
    val generation: Int,
    /**
     * Negotiated ATT MTU, where known. Omitted from the line when null.
     *
     * Carried on the connect event because on the peripheral side it is the
     * single most consequential property of a link: it decides whether a record
     * fits in one notification or is fragmented, and a client that never raises
     * it cannot be sent the same bytes as one that does.
     */
    val mtu: Int? = null,
) : CaptureEvent {
    override val ev: String get() = "connect"
}

internal data class DisconnectEvent(
    override val conn: String,
    val reason: String,
    val generation: Int,
) : CaptureEvent {
    override val ev: String get() = "disconnect"
}

/**
 * Full GATT inventory, once per connection. §0.9.1 calls this the single most
 * useful event for triage: "it connects but shows nothing" is usually answered
 * by looking at what the device actually exposes.
 */
internal data class GattInventoryEvent(
    override val conn: String,
    val services: List<CapturedService>,
) : CaptureEvent {
    override val ev: String get() = "gatt_inventory"
}

internal data class CapturedService(
    val uuid: String,
    val characteristics: List<CapturedCharacteristic>,
)

internal data class CapturedCharacteristic(
    val uuid: String,
    /** Raw property bitmask, not a decoded list — decoding can be wrong later. */
    val properties: Int,
    val descriptors: List<String>,
)

internal data class CccdEvent(
    override val conn: String,
    val characteristic: String,
    val enabled: Boolean,
    val result: String,
) : CaptureEvent {
    override val ev: String get() = "cccd"
}

// ── traffic ───────────────────────────────────────────────────────────────────

internal data class NotifyEvent(
    override val conn: String,
    val characteristic: String,
    val payload: ByteArray,
) : CaptureEvent {
    override val ev: String get() = "notify"
}

internal data class ReadEvent(
    override val conn: String,
    val characteristic: String,
    val payload: ByteArray,
) : CaptureEvent {
    override val ev: String get() = "read"
}

/**
 * A write whose payload was captured.
 *
 * The constructor is private on purpose — see [of]. Reaching it is the only way
 * to put bytes in a capture file, and [of] is the only way to reach it.
 *
 * Deliberately **not** a data class: a generated `copy()` would be a second route
 * to a payload-carrying instance that bypasses [of] entirely, which is precisely
 * the hole this design exists to close.
 */
internal class WriteEvent private constructor(
    override val conn: String,
    val characteristic: String,
    val payload: ByteArray,
    val withoutResponse: Boolean,
) : CaptureEvent {

    override val ev: String get() = "write"

    companion object {
        /**
         * Builds a write event, redacting the payload when either the
         * characteristic is registered in [SensitiveCharacteristics] or the
         * caller declares [redactAs].
         *
         * Both checks apply; a caller cannot opt *out* of the registry. The
         * `redactAs` parameter exists for payloads whose characteristic UUID is
         * only known at runtime, which is the Life Fitness user service case.
         */
        fun of(
            conn: String,
            characteristic: UUID,
            payload: ByteArray,
            withoutResponse: Boolean,
            redactAs: RedactionReason? = null,
        ): CaptureEvent {
            val reason = SensitiveCharacteristics.reasonFor(characteristic) ?: redactAs
            return if (reason != null) {
                RedactedWriteEvent(
                    conn = conn,
                    characteristic = characteristic.toString(),
                    reason = reason,
                    length = payload.size,
                    withoutResponse = withoutResponse,
                )
            } else {
                WriteEvent(
                    conn = conn,
                    characteristic = characteristic.toString(),
                    payload = payload.copyOf(),
                    withoutResponse = withoutResponse,
                )
            }
        }
    }
}

/**
 * A write whose payload is structurally absent.
 *
 * Note what is missing: there is no `payload`, no `hex`, no `bytes`. The length
 * is retained because it is diagnostically useful and reveals nothing.
 */
internal data class RedactedWriteEvent(
    override val conn: String,
    val characteristic: String,
    val reason: RedactionReason,
    val length: Int,
    val withoutResponse: Boolean,
) : CaptureEvent {
    override val ev: String get() = "write"
}

// ── decisions and decoded output ──────────────────────────────────────────────

/** Matcher evidence per §0.7, so a wrong selection can be understood later. */
internal data class MatchEvent(
    override val conn: String,
    val candidates: List<MatchCandidate>,
    val selected: String?,
) : CaptureEvent {
    override val ev: String get() = "match"
}

internal data class MatchCandidate(
    val adapterId: String,
    val score: Int,
    val evidence: String,
)

/**
 * A §0.7a liveness demotion — the matcher was wrong and FitBridge now knows it.
 *
 * §0.9.1 calls this the highest-value event in the system: it is exactly the
 * data needed to add a correct fingerprint later, and it is also one of the
 * arming triggers in §0.9.5.
 */
internal data class DemoteEvent(
    override val conn: String,
    val from: String,
    val to: String?,
    val reason: String,
    val timeoutMs: Long,
    val evidence: String,
) : CaptureEvent {
    override val ev: String get() = "demote"
}

/**
 * A decoded metric, recorded after parsing so the bytes that produced it can be
 * correlated with the value. This pairing is what makes a capture convertible
 * into a §5 fixture.
 */
internal data class SampleEvent(
    override val conn: String,
    val sourceId: String,
    val metric: String,
    val value: Double,
    val unit: String,
    val quality: String,
) : CaptureEvent {
    override val ev: String get() = "sample"
}

/** Structural lifecycle/decision record for an advertisement-only metric source. */
internal data class AdvertisementSourceEvent(
    override val conn: String?,
    val family: String,
    val candidate: String,
    val systemId: Int?,
    val firmware: String?,
    val generation: Long,
    val state: String,
    val decision: String,
    val freshnessMs: Long?,
) : CaptureEvent {
    override val ev: String get() = "advertisement_source"
}

internal data class ErrorEvent(
    override val conn: String?,
    val where: String,
    val message: String,
) : CaptureEvent {
    override val ev: String get() = "error"
}

// ── envelope ──────────────────────────────────────────────────────────────────

/**
 * One event with its ordering metadata.
 *
 * [atMs] and [seq] are assigned when the event is *submitted*, not when it is
 * written. That matters for §0.9.5: the ring buffer holds events that may reach
 * disk seconds later, and a timestamp applied at flush time would misrepresent
 * when the thing happened.
 */
internal data class CaptureRecord(
    val atMs: Long,
    val seq: Long,
    val event: CaptureEvent,
    /**
     * Which file this record belongs in. Routing only — deliberately not encoded
     * into the JSON, since the filename already carries it and keeping the line
     * format identical lets one parser read both roles.
     */
    val role: CaptureRole = CaptureRole.CENTRAL,
)

/** First line of every capture file. */
internal data class CaptureHeader(
    val appVersion: String,
    val androidSdk: Int,
    val deviceModel: String,
    /** Session-scoped random identifier; not derived from any device address. */
    val session: String,
    val t0Ms: Long,
    val captureVersion: Int = CAPTURE_VERSION,
) {
    companion object {
        const val CAPTURE_VERSION = 1
    }
}

/**
 * Assigns session-scoped `c1`, `c2` labels so no MAC reaches an event body.
 *
 * Scoped to one capture session and never persisted, consistent with D14: there
 * is no cross-session device identity to leak because FitBridge does not keep
 * one.
 */
internal class ConnectionLabels {

    private val lock = Any()
    private val labels = LinkedHashMap<String, String>()
    private var next = 1

    /** Stable label for [address] within this session. */
    fun labelFor(address: String): String = synchronized(lock) {
        labels.getOrPut(address.lowercase()) { "c${next++}" }
    }

    /** Existing label, or null if this address has never been labelled. */
    fun existingLabel(address: String): String? = synchronized(lock) {
        labels[address.lowercase()]
    }

    fun clear() = synchronized(lock) {
        labels.clear()
        next = 1
    }
}
