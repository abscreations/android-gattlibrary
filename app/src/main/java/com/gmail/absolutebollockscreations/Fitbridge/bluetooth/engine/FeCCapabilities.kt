package com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine

/**
 * Translates FE-C over BLE link facts into [BtEngineState]'s FTMS-shaped capability flags, per
 * Unit 9.8 of `docs/fe-c-ble_central_implementation.md`, and (2026-08-20) the FE-C <-> FTMS
 * control handshake review that added real Supported-* ranges once FEC3 is present.
 *
 * ## Why FTMS-shaped flags for a non-FTMS link
 *
 * [BtEngineState.supportsResistance]/`supportsPower`/`supportsIndoorBikeSimulation` and their
 * `*RangeKnown` siblings exist because `btService` reads them through
 * [BtServiceEngineFacade]/[BtServiceSupportedRangesAdapter] (and, on the live downstream-peripheral
 * path, `TargetCapabilities.fromEngineCapabilities`) to answer its own downstream FTMS clients
 * (plan §10). Reusing the same fields for an FE-C link — rather than adding a parallel FE-C
 * capability model — is the same choice `FeCMetricMapper` and `FeCOverBleProtocolHandler` already
 * made for `MetricProtocol`/`MetricSourceClass`/`updateDeviceMachineFeatureByAddress`: one machine
 * link is active at a time, and the consumer only ever needs "can I set X", not "which protocol
 * is it".
 *
 * ## Ranges are now spec bounds, not device readings — and that distinction matters
 *
 * Earlier, [publish] set the boolean capability flags but never claimed a known range, on the
 * reasoning that FE-C over BLE has no wire characteristic analogous to FTMS's Supported
 * Resistance/Power Range (0x2AD6/0x2AD8) reporting a *specific device's* real limits, and
 * inventing one from the spec's ceiling would be a fabricated, device-specific claim (plan §21
 * FEC-Q1). That review is why downstream FTMS clients could never actually reach resistance,
 * power or inclination control on an FE-C link at all:
 * `TargetCapabilities.fromEngineCapabilities` only advertises a target-setting feature (and
 * registers its Supported-*-Range characteristic) when both the capability flag AND the matching
 * `*RangeKnown` flag are true, so every one of those three controls was permanently invisible to
 * a compliant downstream client, and `FtmsControlPointHandler` independently re-checks the same
 * flag before forwarding a write - so even a client that somehow sent one anyway would be
 * rejected with `OPCODE_NOT_SUPPORTED`.
 *
 * The 2026-08-20 review reframed what a "range" claims here: [publish] now advertises the ANT+
 * FE-C spec's own encodable bounds for pages 48 (Basic Resistance, 0-100%), 49 (Target Power,
 * 0-4000 W) and 51 (Track Resistance's Grade field, -200.00%..+200.00%) - not a guess at a
 * specific trainer's physical limit, but the range the wire format itself is defined to accept
 * (the same bound [FeCControlEncoder] already enforces client-side). ANT+ requires certified
 * fitness equipment to implement its data pages correctly, so treating spec compliance as the
 * reasonable default for a certified device - rather than continuing to withhold the range
 * entirely - is what actually makes the resistance/power/inclination control paths this project
 * already implements reachable from a downstream client. A specific real trainer silently
 * clamping or rejecting part of that range is a physical-device question no protocol document
 * resolves (plan §21 FEC-Q1's narrower remaining scope), not something advertising the spec's
 * bound gets wrong.
 *
 * ## Units: FTMS raw wire units, not FE-C's
 *
 * [BtEngineState.FtmsCapabilities] (and the flat fields on [BtEngineState] this also sets)
 * store every range in the *downstream* FTMS characteristic's own raw units, per
 * `TargetCapabilities`'s own doc: 0.1 resistance "level" per unit, whole watts, 0.1 % per unit
 * for inclination. Resistance's 0.5%-per-step FE-C resolution becomes a 5-unit FTMS increment
 * (5 * 0.1 = 0.5); inclination's FTMS characteristic cannot express FE-C's finer 0.01% steps at
 * all, so the increment advertised is FTMS's own finest unit (1 = 0.1%), not FE-C's.
 *
 * [supportsInclination] now follows [controlAvailable] rather than being hardcoded `false`:
 * [FeCBleMachineControlEndpoint.setTargetInclinationPercent] submits a real page 51 write (see
 * that class's KDoc for why grade *is* the incline control on FE-C), so a flag that still claimed
 * "not supported" would misrepresent an implemented control path as absent.
 *
 * [supportsSpeed] remains unconditionally `false`: FE-C truly has no speed-target page at all
 * (not even an indirect one like grade-for-incline), so there is nothing for [controlAvailable]
 * to unlock here.
 *
 * [featuresRead] is set to `true` unconditionally once FEC1/FEC2 discovery has completed, because
 * "FEC3 absent" is itself a fully determined fact at that point (unlike FTMS, where `featuresRead`
 * waits on a separate 0x2ACC read that can simply never arrive) - there is nothing more to learn
 * later that would change these booleans for this link generation.
 */
internal object FeCCapabilities {

    /** Page 48 (Basic Resistance): 0-100%, 0.5% resolution -> FTMS raw 0.1-level units. */
    private const val RESISTANCE_MIN_RAW = 0f
    private const val RESISTANCE_MAX_RAW = 1000f
    private const val RESISTANCE_INCR_RAW = 5f

    /** Page 49 (Target Power): 0-4000 W. FTMS's own Target Power characteristic is 1 W/unit -
     * finer than that does not exist on the downstream wire regardless of FE-C's own 0.25 W step. */
    private const val POWER_MIN_RAW = 0f
    private const val POWER_MAX_RAW = 4000f
    private const val POWER_INCR_RAW = 1f

    /** Page 51 Grade: -200.00%..+200.00% -> FTMS raw 0.1%-per-unit units. */
    private const val INCLINE_MIN_RAW = -2000f
    private const val INCLINE_MAX_RAW = 2000f
    private const val INCLINE_INCR_RAW = 1f

    fun publish(state: BtEngineState, controlAvailable: Boolean) {
        state.supportsResistance = controlAvailable
        state.supportsPower = controlAvailable
        state.supportsIndoorBikeSimulation = controlAvailable
        state.supportsSpeed = false
        state.supportsInclination = controlAvailable
        state.featuresRead = true

        state.resistanceRangeKnown = controlAvailable
        state.resistanceMin = if (controlAvailable) RESISTANCE_MIN_RAW else 0f
        state.resistanceMax = if (controlAvailable) RESISTANCE_MAX_RAW else 0f
        state.resistanceIncr = if (controlAvailable) RESISTANCE_INCR_RAW else 0f

        state.powerRangeKnown = controlAvailable
        state.powerMin = if (controlAvailable) POWER_MIN_RAW else 0f
        state.powerMax = if (controlAvailable) POWER_MAX_RAW else 0f
        state.powerIncr = if (controlAvailable) POWER_INCR_RAW else 0f

        state.inclineRangeKnown = controlAvailable
        state.inclineMin = if (controlAvailable) INCLINE_MIN_RAW else 0f
        state.inclineMax = if (controlAvailable) INCLINE_MAX_RAW else 0f
        state.inclineIncr = if (controlAvailable) INCLINE_INCR_RAW else 0f

        state.mergeFtmsCapabilities(
            BtEngineState.FtmsCapabilities(
                featuresRead = true,
                supportsResistance = controlAvailable,
                supportsPower = controlAvailable,
                supportsIndoorBikeSimulation = controlAvailable,
                supportsSpeed = false,
                supportsInclination = controlAvailable,

                resistanceMin = state.resistanceMin,
                resistanceMax = state.resistanceMax,
                resistanceIncr = state.resistanceIncr,
                resistanceRangeKnown = controlAvailable,

                powerMin = state.powerMin,
                powerMax = state.powerMax,
                powerIncr = state.powerIncr,
                powerRangeKnown = controlAvailable,

                inclineMin = state.inclineMin,
                inclineMax = state.inclineMax,
                inclineIncr = state.inclineIncr,
                inclineRangeKnown = controlAvailable,
            )
        )
    }
}
