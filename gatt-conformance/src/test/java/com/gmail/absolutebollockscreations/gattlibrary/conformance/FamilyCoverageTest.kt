package com.gmail.absolutebollockscreations.gattlibrary.conformance

import com.gmail.absolutebollockscreations.gattlibrary.core.catalog.MachineFamilyIds
import com.gmail.absolutebollockscreations.gattlibrary.core.ids.WahooUuids
import com.gmail.absolutebollockscreations.gattlibrary.simulator.KickrPersonaManifest
import com.gmail.absolutebollockscreations.gattlibrary.simulator.SimulatorPersona
import com.gmail.absolutebollockscreations.gattlibrary.simulator.simulatorPersonaOptions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * L25 family coverage, public API only. Every family id in the core catalog is either served by at
 * least one simulator persona or listed in [KNOWN_GAPS] with a reason. Adding a family without a
 * persona, or a persona for a listed gap, fails here so the list cannot rot.
 *
 * The persona-to-family mapping is stated here by hand; it is deliberate double entry (L23).
 */
class FamilyCoverageTest {

    private fun families(p: SimulatorPersona): Set<String> = when (p) {
        SimulatorPersona.IndoorBikeSpeedAndPower -> setOf(MachineFamilyIds.SIG_CSC, MachineFamilyIds.SIG_CPS)
        SimulatorPersona.StrydFootpod -> setOf(MachineFamilyIds.SIG_RSC, MachineFamilyIds.SIG_CPS)
        is SimulatorPersona.Ftms -> setOf(MachineFamilyIds.SIG_FTMS)
        is SimulatorPersona.Concept2Pm5 -> setOf(MachineFamilyIds.CONCEPT2_PM)
        is SimulatorPersona.KeiserMSeries -> setOf(MachineFamilyIds.KEISER_M_SERIES)
        SimulatorPersona.PrecorChrono -> setOf(MachineFamilyIds.PRECOR_CHRONO)
        SimulatorPersona.FlywheelSports -> setOf(MachineFamilyIds.FLYWHEEL_SPORTS)
        SimulatorPersona.KayakFirstBlue, SimulatorPersona.KayakFirstOrange -> setOf(MachineFamilyIds.KAYAK_FIRST)
        is SimulatorPersona.LifeFitness -> setOf(MachineFamilyIds.LIFE_FITNESS)
        SimulatorPersona.SchwinnX70 -> setOf(MachineFamilyIds.SCHWINN_X70)
        is SimulatorPersona.Sole -> emptySet() // The core catalog has no Sole family ID yet.
        is SimulatorPersona.FeCBle -> setOf(MachineFamilyIds.FEC_BLE)
        is SimulatorPersona.Kickr -> kickrFamilies(p)
    }

    /**
     * Derived from what the persona actually exposes, not from its name: the original RideOn service
     * puts a persona in the original family, FC82 in the Core 2 family, and the Wahoo control
     * characteristic in the legacy KICKR family. (Central's matcher also wants the RideOn
     * characteristics; service presence is the coarser check this test can make without central.)
     */
    private fun kickrFamilies(p: SimulatorPersona.Kickr): Set<String> {
        val services = KickrPersonaManifest.forPersona(p.device).services
        val families = mutableSetOf<String>()
        if (services.any { it.uuid == WahooUuids.UUID_WAHOO_RIDEON_SERVICE }) families += MachineFamilyIds.WAHOO_RIDEON_ORIGINAL
        if (services.any { it.uuid == WahooUuids.UUID_WAHOO_RIDEON_CORE2_SERVICE }) families += MachineFamilyIds.WAHOO_RIDEON_CORE2
        if (services.any { s -> s.characteristics.any { it.uuid == WahooUuids.UUID_WAHOO_KICKR_CONTROL } }) families += MachineFamilyIds.WAHOO_KICKR_LEGACY
        return families
    }

    private val allFamilies: Set<String> =
        MachineFamilyIds::class.java.declaredFields
            .filter { java.lang.reflect.Modifier.isStatic(it.modifiers) && it.type == String::class.java }
            .mapNotNull { it.also { f -> f.isAccessible = true }.get(null) as? String }
            .toSet()

    private val covered: Set<String> =
        simulatorPersonaOptions(includeKickr = true).flatMap { families(it) }.toSet()

    @Test
    fun catalogReflectionFindsTheFamilies() {
        assertTrue("expected the 17 catalog ids, got $allFamilies", allFamilies.size >= 17)
    }

    @Test
    fun everyFamilyHasAPersonaOrIsAKnownGap() {
        val unexplained = allFamilies - covered - KNOWN_GAPS.keys
        assertEquals("families with neither persona nor KNOWN_GAPS entry: $unexplained", emptySet<String>(), unexplained)
    }

    @Test
    fun knownGapsAreRealFamiliesWithNoPersona() {
        assertEquals("gap ids not in catalog", emptySet<String>(), KNOWN_GAPS.keys - allFamilies)
        assertEquals("gap has a persona now; remove it from KNOWN_GAPS", emptySet<String>(), KNOWN_GAPS.keys intersect covered)
    }

    private companion object {
        val KNOWN_GAPS: Map<String, String> = mapOf(
            MachineFamilyIds.SIG_HRM to "no standalone HRM persona; heart rate is published by machine personas",
            MachineFamilyIds.SIG_GLUCOSE to "no glucose persona; out of scope for the simulator",
            MachineFamilyIds.MAXPRO to "no MAXPRO persona; MAXPRO-1 scope (2026-10-21) deferred the simulator",
        )
    }
}
