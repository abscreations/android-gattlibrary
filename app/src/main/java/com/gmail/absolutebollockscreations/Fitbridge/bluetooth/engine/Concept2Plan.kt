package com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine

import java.util.UUID

/**
 * Subscription planning, persona selection and fallback policy for the Concept2 PM (plan §5a).
 *
 * These are pure decisions, kept out of the GATT handler so they can be tested without a live
 * connection — the lesson §11.7 taught in Phase 4, where correlation logic sealed inside a
 * handler stayed untested long enough for a defect to ship through it.
 */

internal enum class Concept2GattOperationKind { ENABLE_NOTIFICATIONS }

internal data class Concept2GattOperation(
    val kind: Concept2GattOperationKind,
    val characteristicUuid: UUID,
)

/** Which delivery path a plan subscribes. */
internal enum class Concept2DeliveryPath {
    /** One CCCD on `0x0080`; identifiers dispatch to the individual decoders. */
    MULTIPLEXED,

    /** One CCCD per consumed characteristic. */
    INDIVIDUAL,
}

internal data class Concept2DiscoveryPlan(
    val activate: Boolean,
    val path: Concept2DeliveryPath?,
    val operations: List<Concept2GattOperation>,
)

/** The four characteristics FitBridge consumes, in identifier order. */
internal val CONCEPT2_CONSUMED_CHARACTERISTICS: List<UUID> = listOf(
    BluetoothIds.UUID_C2_GENERAL_STATUS,
    BluetoothIds.UUID_C2_ADDITIONAL_STATUS_1,
    BluetoothIds.UUID_C2_ADDITIONAL_STATUS_2,
    BluetoothIds.UUID_C2_ADDITIONAL_STROKE_DATA,
)

/**
 * Plan the initial subscription: multiplexed when the PM offers it, individual otherwise (D41).
 *
 * ## Why multiplexed is preferred
 *
 * Concept2's specification tells Android applications to use `0x0080` "in lieu of" the
 * individual characteristics. The stated reason is a concurrent-notification limit on **Android
 * 4.3 and 4.4** — four and seven subscriptions respectively — which is far below anything
 * FitBridge targets, so the original motivation is obsolete. Multiplexed is still preferred
 * because it is one subscription rather than four on a link that may already carry other
 * handlers, not because the platform forces it.
 *
 * ## Why the individual path must remain
 *
 * `0x0080` appears in the document's revision history around V1.26, so a PM5 on older firmware
 * may not expose it at all. With the Android rationale obsolete there is no correctness reason
 * to avoid the individual path, which is also what TMIW does successfully. A PM that offers no
 * multiplexed characteristic is planned individually from the start; one that offers it but
 * never speaks is handled by [concept2FallbackDecision].
 */
internal fun planConcept2Discovery(characteristicUuids: Set<UUID>): Concept2DiscoveryPlan {
    if (BluetoothIds.UUID_C2_MULTIPLEXED_INFORMATION in characteristicUuids) {
        return Concept2DiscoveryPlan(
            activate = true,
            path = Concept2DeliveryPath.MULTIPLEXED,
            operations = listOf(
                Concept2GattOperation(
                    Concept2GattOperationKind.ENABLE_NOTIFICATIONS,
                    BluetoothIds.UUID_C2_MULTIPLEXED_INFORMATION,
                ),
            ),
        )
    }

    val present = CONCEPT2_CONSUMED_CHARACTERISTICS.filter { it in characteristicUuids }
    if (present.isEmpty()) {
        return Concept2DiscoveryPlan(activate = false, path = null, operations = emptyList())
    }
    return Concept2DiscoveryPlan(
        activate = true,
        path = Concept2DeliveryPath.INDIVIDUAL,
        operations = present.map {
            Concept2GattOperation(Concept2GattOperationKind.ENABLE_NOTIFICATIONS, it)
        },
    )
}

/**
 * Plan the fallback subscription after the multiplexed path has proved silent.
 *
 * Safe to issue without unsubscribing `0x0080` first. The specification states that an
 * identifier is multiplexed **only** while the corresponding individual notification is not
 * enabled, so arming these CCCDs withdraws those identifiers from the multiplex automatically.
 * There is no window in which both paths deliver the same frame.
 */
internal fun planConcept2IndividualFallback(characteristicUuids: Set<UUID>): List<Concept2GattOperation> =
    CONCEPT2_CONSUMED_CHARACTERISTICS
        .filter { it in characteristicUuids }
        .map { Concept2GattOperation(Concept2GattOperationKind.ENABLE_NOTIFICATIONS, it) }

/**
 * Whether to abandon the multiplexed path for the individual characteristics.
 *
 * §5a asks for a generous timeout and warns that "a PM5 sitting idle with no one on it
 * legitimately sends nothing", suggesting the timer start on first user motion. The
 * specification points the other way: characteristic `0x0034` sets how often status is sent as
 * a matter of **cadence rather than activity** — 500 ms by default — and `0x0031` reports
 * `WORKOUTSTATE_WAITTOBEGIN`, a state no client could observe if idle frames were withheld.
 *
 * So a plain timeout from CCCD completion is used, with no motion gate. At the default cadence
 * a live PM produces roughly twenty frames inside [CONCEPT2_LIVENESS_TIMEOUT_MS]. That reading
 * is strong inference rather than a quoted sentence, which is why the timeout stays at the
 * plan's generous suggestion rather than being tightened to match the cadence: if the inference
 * is wrong, the cost is one unnecessary fallback, which the mutual-exclusion rule makes
 * harmless.
 */
internal fun concept2FallbackDecision(
    path: Concept2DeliveryPath,
    framesReceived: Int,
    elapsedSinceCccdReadyMs: Long,
    timeoutMs: Long = CONCEPT2_LIVENESS_TIMEOUT_MS,
): Boolean =
    path == Concept2DeliveryPath.MULTIPLEXED &&
        framesReceived == 0 &&
        elapsedSinceCccdReadyMs >= timeoutMs

/** §5a's suggested generous window, measured from CCCD completion. */
internal const val CONCEPT2_LIVENESS_TIMEOUT_MS: Long = 10_000L

/**
 * Session persona for a PM, from its advertised name.
 *
 * §5a: "Name postfix selects RowErg / BikeErg / SkiErg; a bare `PM5` stays multi-sport and
 * takes the user's session-mode selection." A `null` return is therefore not a failure — it
 * means the machine has not told us, and the user's choice stands.
 *
 * Matching is on the postfix rather than the whole name because the PM advertises as
 * `PM5 123456` style with the ergometer type appended, and the serial portion varies.
 */
internal fun concept2PersonaFor(deviceName: String?): FusionPersona? {
    val name = deviceName?.lowercase() ?: return null
    return when {
        "rowerg" in name -> FusionPersona.ROW_PADDLE
        "skierg" in name -> FusionPersona.ROW_PADDLE
        "bikeerg" in name -> FusionPersona.RIDE
        else -> null
    }
}

/**
 * Whether a device name looks like a Concept2 PM at all.
 *
 * Deliberately narrow. Plan §0.7 ranks name evidence below service and characteristic evidence,
 * and this is only used to choose a persona and to log; the handler activates on the presence
 * of the Erg service UUID, which is real evidence.
 */
internal fun isConcept2Name(deviceName: String?): Boolean =
    deviceName?.lowercase()?.startsWith("pm") == true
