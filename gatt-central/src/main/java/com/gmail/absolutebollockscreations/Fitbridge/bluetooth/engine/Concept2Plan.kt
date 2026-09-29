package com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine

import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.core.ActivityType
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.core.FitBridgeBleInternalApi
import java.util.UUID

/**
 * Subscription planning, persona selection and fallback policy for the Concept2 PM (plan §5a).
 *
 * These are pure decisions, kept out of the GATT handler so they can be tested without a live
 * connection — the lesson §11.7 taught in Phase 4, where correlation logic sealed inside a
 * handler stayed untested long enough for a defect to ship through it.
 */

@FitBridgeBleInternalApi
enum class Concept2GattOperationKind { READ, ENABLE_NOTIFICATIONS }

@FitBridgeBleInternalApi
data class Concept2GattOperation(
    val kind: Concept2GattOperationKind,
    val characteristicUuid: UUID,
)

/** Which delivery path a plan subscribes. */
@FitBridgeBleInternalApi
enum class Concept2DeliveryPath {
    /** One CCCD on `0x0080`; identifiers dispatch to the individual decoders. */
    MULTIPLEXED,

    /** One CCCD per consumed characteristic. */
    INDIVIDUAL,
}

@FitBridgeBleInternalApi
data class Concept2DiscoveryPlan(
    val activate: Boolean,
    val path: Concept2DeliveryPath?,
    val operations: List<Concept2GattOperation>,
)

/**
 * Plan the Device Information read that identifies the ergometer.
 *
 * Separate from [planConcept2Discovery] because `0x0016` lives in the Device Information
 * service (`0x0010`), not the Erg service (`0x0030`), so it is discovered and queued from a
 * different `BluetoothGattService`. Returns nothing when the firmware predates the
 * characteristic — persona then falls back to the name, and failing that to the user's choice.
 */
internal fun planConcept2Identification(
    deviceInfoCharacteristicUuids: Set<UUID>,
): List<Concept2GattOperation> =
    if (BluetoothIds.UUID_C2_MACHINE_TYPE in deviceInfoCharacteristicUuids) {
        listOf(
            Concept2GattOperation(
                Concept2GattOperationKind.READ,
                BluetoothIds.UUID_C2_MACHINE_TYPE,
            ),
        )
    } else {
        emptyList()
    }

/** The four characteristics FitBridge consumes, in identifier order. */
@FitBridgeBleInternalApi
val CONCEPT2_CONSUMED_CHARACTERISTICS: List<UUID> = listOf(
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
@FitBridgeBleInternalApi
fun planConcept2Discovery(characteristicUuids: Set<UUID>): Concept2DiscoveryPlan {
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
@FitBridgeBleInternalApi
fun planConcept2IndividualFallback(characteristicUuids: Set<UUID>): List<Concept2GattOperation> =
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
 * Ergometer family, decoded from the PM's Erg Machine Type octet (`0x0016`).
 *
 * The enum in the specification is sparse and **grouped by range**, not a dense list, which is
 * what makes range classification the correct reading rather than an exhaustive `when`:
 *
 * ```
 *   0..8    static rowers (models D, C, A, B, E, simulator = 7, dynamic = 8)
 *   16..20  slides A..E
 *   32      slides dynamic
 *   64      static dyno
 *   128     static ski        143  static ski simulator
 *   192     bike              193 bike arms, 194 bike no-arms, 207 bike simulator
 *   224     multi-erg row     225  multi-erg ski     226  multi-erg bike
 * ```
 *
 * Everything below 128 is a rowing machine of some description, 128–191 is ski, 192–223 is
 * bike, and the multi-erg values at 224+ name their own discipline. Treating unknown values in
 * the rowing range as rowing is deliberate: Concept2 has added members to that block over time
 * (the simulator and dynamic entries arrived later than the lettered models), and a future
 * rower is far more likely than a future machine of an entirely new kind.
 */
internal enum class Concept2ErgFamily { ROWER, SKI, BIKE }

internal fun concept2ErgFamilyFor(machineTypeOctet: Int?): Concept2ErgFamily? = when (machineTypeOctet) {
    null -> null
    in 0..127 -> Concept2ErgFamily.ROWER
    in 128..191 -> Concept2ErgFamily.SKI
    in 192..223 -> Concept2ErgFamily.BIKE
    224 -> Concept2ErgFamily.ROWER
    225 -> Concept2ErgFamily.SKI
    226 -> Concept2ErgFamily.BIKE
    else -> null
}

/** Decode the one-octet `0x0016` Erg Machine Type read. */
internal fun decodeConcept2MachineType(value: ByteArray?): Concept2ErgFamily? {
    if (value == null || value.isEmpty()) return null
    return concept2ErgFamilyFor(value[0].toInt() and 0xFF)
}

/**
 * The device classification a decoded erg family implies, for `DiscoveredDevice.machineFeature`.
 *
 * This is the seam FTMS already uses: `btEngine.GattFtms` infers an [ActivityType] from which
 * data characteristic the machine exposes and publishes its ordinal through
 * `updateDeviceMachineFeatureByAddress`. Concept2 arrives at the same answer from a vendor read
 * instead of a characteristic census, and publishes it the same way, so the device row, the
 * friendly label and `currentMachineActivity` all work for a PM without knowing it is one.
 *
 * **It classifies the device; it does not start or alter a session.** Session persona comes
 * from `markSessionStarted(activity)`, which carries the user's explicit selection. Writing
 * here cannot override that choice, and that separation is the reason this is the correct
 * consumer rather than `MetricFusion.setPersona`.
 *
 * ## SkiErg maps to ROWING, and that is lossy
 *
 * [ActivityType] has no ski member. A SkiErg's metric set *is* the rowing set — stroke rate,
 * pace per 500 m, power — and `concept2PersonaFor` already folds it into `ROW_PADDLE` for
 * exactly that reason, so ROWING is the entry whose expectations fit. The visible cost is that
 * a SkiErg's device row reads "Indoor Rower".
 *
 * The alternative was to publish no classification for a ski machine, leaving the row blank.
 * That is not more honest, only less useful: it would withhold a correct statement about the
 * metrics because the label attached to it is imprecise. Recorded as a known limitation rather
 * than hidden — adding a ski member to [ActivityType] reaches the FIT export, the session model
 * and the UI, which is a change well outside Phase 5a.
 */
internal fun concept2ActivityTypeFor(family: Concept2ErgFamily?): ActivityType? = when (family) {
    Concept2ErgFamily.ROWER, Concept2ErgFamily.SKI -> ActivityType.ROWING
    Concept2ErgFamily.BIKE -> ActivityType.BIKING
    null -> null
}

/** Preserve the product-family distinction that the session activity intentionally loses. */
internal fun concept2MachineDeviceTypeFor(
    family: Concept2ErgFamily?,
): BtEngineState.MachineDeviceType? = when (family) {
    Concept2ErgFamily.ROWER -> BtEngineState.MachineDeviceType.ROWER
    Concept2ErgFamily.SKI -> BtEngineState.MachineDeviceType.SKI_ERG
    Concept2ErgFamily.BIKE -> BtEngineState.MachineDeviceType.BIKE_ERG
    null -> null
}

/**
 * Session persona for a PM.
 *
 * ## The machine tells us; the name usually does not
 *
 * Plan §5a says "name postfix selects RowErg / BikeErg / SkiErg". That is how TMIW does it —
 * `scan_result_ex.dart` requires the advertised name to *start with* `PM5` and *end with*
 * `Row`, `Ski` or `Bike` — but it is not what a PM actually advertises. The specification gives
 * the GAP device name as `"PM5 430000000"`, serial number only, and its own pairing-record
 * example is `PM5 430343693`. So on a stock PM the name carries no discipline at all, which is
 * why TMIW prompts the user to choose on every real unit.
 *
 * The vendor's actual identification path is the **Erg Machine Type characteristic `0x0016`**
 * in the Device Information service, added for PM5 firmware V150–V199.99 and V204–V299.99, and
 * repeated as the last octet of the multiplexed `0x0032` and `0x0038` payloads. That is read
 * first here, and the name is consulted only when it is unavailable.
 *
 * ## A null return is an answer, not a failure
 *
 * When neither source identifies the machine — an older PM whose firmware predates `0x0016`,
 * advertising a bare `PM5 <serial>` — the correct behaviour is to defer to the user's
 * session-mode selection. Guessing here would silently override a choice the person made.
 *
 * @param machineType the decoded `0x0016` value, when it has been read.
 * @param deviceName fallback only, for a renamed unit or firmware without `0x0016`.
 */
internal fun concept2PersonaFor(
    machineType: Concept2ErgFamily? = null,
    deviceName: String? = null,
): FusionPersona? = when (machineType ?: concept2FamilyFromName(deviceName)) {
    // A SkiErg is not a rower, but its metrics are the rowing set — stroke rate, pace per
    // 500 m, power — and ROW_PADDLE is the persona whose plausibility bounds fit them.
    Concept2ErgFamily.ROWER, Concept2ErgFamily.SKI -> FusionPersona.ROW_PADDLE
    Concept2ErgFamily.BIKE -> FusionPersona.RIDE
    null -> null
}

/**
 * Fallback identification from the advertised name.
 *
 * Accepts both the postfix form TMIW matches (`PM5 430343693 Row`) and the fuller `RowErg` /
 * `SkiErg` / `BikeErg` spellings, because a renamed unit or a simulator may use either and
 * neither is wrong. Order matters: `bikeerg` must be tested before `erg` would match anything
 * else, and `row` before `rower` is irrelevant since both contain `row`.
 */
private fun concept2FamilyFromName(deviceName: String?): Concept2ErgFamily? {
    val name = deviceName?.lowercase() ?: return null
    if (!name.startsWith("pm")) return null
    return when {
        "bike" in name -> Concept2ErgFamily.BIKE
        "ski" in name -> Concept2ErgFamily.SKI
        "row" in name -> Concept2ErgFamily.ROWER
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
