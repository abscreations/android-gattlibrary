package com.gmail.absolutebollockscreations.Fitbridge.bluetooth.simulator

import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine.BtServiceProfiles
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.peripheral.AdapterNameCoordinator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guard for decision L15 (LIB-5).
 *
 * `AdapterNameCoordinator` ships in release builds, which carry no simulator code, so its list
 * of simulator-owned adapter names is a literal. This test re-derives the list from the persona
 * definitions (the derivation the coordinator used before LIB-5) and fails on any difference. A
 * persona therefore still cannot be added, renamed or removed without the list following. If this
 * fails, update `AdapterNameCoordinator.UNPREFIXED_SESSION_NAMES`; do not weaken the test.
 */
class AdapterNameSessionNamesTest {

    private val derivedUnprefixedNames: Set<String> = buildSet {
        add(BtServiceProfiles.DEVICE_NAME_CSC_CPS)
        add(SimulatorPersona.StrydFootpod.advertisedName)
        add(SimulatorPersona.PrecorChrono.advertisedName)
        add(SimulatorPersona.FlywheelSports.advertisedName)
        add(SimulatorPersona.KayakFirstBlue.advertisedName)
        add(SimulatorPersona.KayakFirstOrange.advertisedName)
        add(SCHWINN_X70_ADVERTISED_NAME)
        LifeFitnessMachine.entries.forEach { add(it.syntheticName) }
        KeiserMSeriesSimulatorProfile.entries.forEach { add(it.advertisedName) }
        FeCEquipmentType.entries.forEach { add(feCBleAdvertisedName(it)) }
        FeCBleSimulatorIdentity.entries.forEach { identity -> identity.localName?.let { add(it) } }
        KickrSimulatorPersona.entries.forEach {
            add(KickrPersonaManifest.forPersona(it).advertising.localName)
        }
    }

    @Test
    fun `static unprefixed session names equal the names derived from the personas`() {
        val listed = AdapterNameCoordinator.UNPREFIXED_SESSION_NAMES
        assertEquals(
            "missing from the static list: ${derivedUnprefixedNames - listed}; " +
                "listed but no longer derived: ${listed - derivedUnprefixedNames}",
            derivedUnprefixedNames,
            listed,
        )
    }

    @Test
    fun `every selectable persona's advertised name is recognised as a session name`() {
        val unrecognised = simulatorPersonaOptions(includeKickr = true)
            .map { it.advertisedName }
            .filterNot { AdapterNameCoordinator.isKnownSessionName(it) }
        assertTrue("persona names the coordinator would save as original: $unrecognised", unrecognised.isEmpty())
    }
}
