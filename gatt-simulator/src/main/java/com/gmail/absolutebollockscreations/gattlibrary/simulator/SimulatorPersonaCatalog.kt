package com.gmail.absolutebollockscreations.gattlibrary.simulator

import com.gmail.absolutebollockscreations.gattlibrary.core.FitBridgeBleInternalApi

import com.gmail.absolutebollockscreations.gattlibrary.central.BtServiceProfiles.FtmsMachineProfile

// Persona catalogue and name line, moved from the app's ComposableSimulator.kt (LIB-9b). They are
// pure persona logic, used by the simulator UI and by the persona, snapshot and adapter-name tests.

/**
 * Stable UI order: native bike sensors, the native running footpod, standard FTMS, then
 * protocol/evidence groups.
 *
 * The footpod sits immediately below the indoor-bike row because the two belong to the same group:
 * native sensor services rather than a fitness machine. Putting it with the capture-backed console
 * personas further down would file a standards-only persona among proprietary ones.
 *
 * [includeKickr] has no default (LIB-9b): the library cannot read the app's `BuildConfig`, so the
 * UI passes `BuildConfig.DEBUG` and keeps the KICKR selector gate on its side.
 */
@FitBridgeBleInternalApi
fun simulatorPersonaOptions(includeKickr: Boolean): List<SimulatorPersona> =
    listOf(SimulatorPersona.IndoorBikeSpeedAndPower, SimulatorPersona.StrydFootpod) +
        FtmsMockEngine.supportedMachines.flatMap { machine ->
            // The broken-advertisement rower sits directly below the honest one: same persona,
            // but its FTMS Service Data claims Cross Trainer (see SimulatorPersona.Ftms).
            if (machine == FtmsMachineProfile.INDOOR_ROWER) {
                listOf(SimulatorPersona.Ftms(machine), SimulatorPersona.Ftms.BROKEN_ROWER_ADVERTISEMENT)
            } else {
                listOf(SimulatorPersona.Ftms(machine))
            }
        } +
        // Six PM5 entries, not three with a toggle: the surface changes which characteristics
        // exist, so it is a different device to a client, and a tester picking a target should
        // see that in the list rather than have to remember a mode. Modern first (the
        // ErgData-proven set), then pre-FTMS (the C2DataField proprietary-protocol targets).
        Concept2Pm5CompatibilityProfile.entries.map {
            SimulatorPersona.Concept2Pm5(it.machine, it)
        } +
        LifeFitnessMachine.entries.map { SimulatorPersona.LifeFitness(it) } +
        listOf(
            SimulatorPersona.FlywheelSports,
            SimulatorPersona.KayakFirstBlue,
            SimulatorPersona.KayakFirstOrange,
        ) +
        KeiserMSeriesSimulatorProfile.entries.map { SimulatorPersona.KeiserMSeries(it) } +
        SimulatorPersona.PrecorChrono +
        SimulatorPersona.SchwinnX70 +
        // plan `docs/fe-c-ble_simulator_implementation.md` §3.3: Trainer first (the only
        // control-capable persona), then the three measurement-only equipment types. The
        // Incyclist CPS overlay is not a separate entry here — it is implicit to Trainer and
        // Stationary Bike (FeCEquipmentType.includesCpsOverlay) and never surfaced in the UI.
        FeCEquipmentType.entries.map { SimulatorPersona.FeCBle(it) } +
        listOf(
            SimulatorPersona.FeCBle(
                FeCEquipmentType.TRAINER,
                identity = FeCBleSimulatorIdentity.TACX_NEO,
            ),
            SimulatorPersona.FeCBle(
                FeCEquipmentType.TRAINER,
                identity = FeCBleSimulatorIdentity.TACX_FLUX_2,
            ),
        ) + if (includeKickr) {
            KickrSimulatorPersona.entries.map { SimulatorPersona.Kickr(it) }
        } else {
            emptyList()
        }

@FitBridgeBleInternalApi
fun simulatorAdvertisedNameLine(persona: SimulatorPersona): String? = when (persona) {
    SimulatorPersona.KayakFirstBlue,
    SimulatorPersona.KayakFirstOrange,
    -> "Name: ${persona.advertisedName}"
    else -> null
}
