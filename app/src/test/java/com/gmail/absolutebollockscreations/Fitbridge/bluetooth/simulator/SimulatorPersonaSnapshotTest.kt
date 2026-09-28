package com.gmail.absolutebollockscreations.Fitbridge.bluetooth.simulator

import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.peripheral.FtmsAdvertisingPlan
import com.gmail.absolutebollockscreations.Fitbridge.simulatorPersonaOptions
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * Golden snapshots of every selectable simulator persona (LIB-5a, Stage 0.5 substitute).
 *
 * Recorded on the refactor branch **before** S5 breaks the peripheral ↔ simulator cycle, so S5
 * is judged against pre-S5 output. Each persona's snapshot holds what a unit test can see without
 * a radio: the persona value, advertised name, `describe()` descriptor and `standardSurface()`,
 * plus the family's pure-Kotlin advertising plan, GATT manifest and static read values.
 *
 * **Not covered** (verified by nRF Connect spot checks instead): GATT databases assembled
 * directly as `BluetoothGattService` objects (standard FTMS, Stryd, FE-C generic), which are
 * inert stubs under `isReturnDefaultValues`, and `btServiceManager`'s persona → controller wiring.
 *
 * **Protocol:** a missing golden file is written and the test fails, asking for review and a
 * commit. The next run compares byte for byte. To re-record deliberately, delete the golden file.
 */
class SimulatorPersonaSnapshotTest {

    private val goldenDir: File = sequenceOf(File("app/src/test"), File("src/test"))
        .first(File::isDirectory)
        .resolve("resources/snapshots/simulator-personas")

    @Test
    fun `every selectable persona matches its recorded snapshot`() {
        val personas = simulatorPersonaOptions(includeKickr = true)
        val keyed = keyedPersonas(personas)

        val recorded = mutableListOf<String>()
        val mismatches = mutableListOf<String>()

        fun check(fileName: String, actual: String) {
            val file = File(goldenDir, fileName)
            if (!file.isFile) {
                goldenDir.mkdirs()
                file.writeText(actual)
                recorded += fileName
                return
            }
            val expected = file.readText().replace("\r\n", "\n")
            if (expected != actual) mismatches += fileName + ":\n" + firstDifference(expected, actual)
        }

        check(INDEX_FILE, HEADER + keyed.joinToString("\n", postfix = "\n") { it.first })
        keyed.forEach { (key, persona) -> check("$key.txt", HEADER + snapshot(persona)) }

        val expectedFiles = keyed.map { "${it.first}.txt" }.toSet() + INDEX_FILE
        val stale = goldenDir.listFiles().orEmpty()
            .filter { it.isFile && it.name !in expectedFiles }
            .map { it.name }
            .sorted()

        val problems = buildList {
            if (mismatches.isNotEmpty()) {
                add("Snapshot mismatch in ${mismatches.size} file(s):\n" + mismatches.joinToString("\n\n"))
            }
            if (stale.isNotEmpty()) {
                add("Golden files for personas that no longer exist: $stale")
            }
            if (recorded.isNotEmpty()) {
                add(
                    "Recorded ${recorded.size} new snapshot file(s) in $goldenDir. Review them, " +
                        "commit them, and re-run: $recorded",
                )
            }
        }
        if (problems.isNotEmpty()) fail(problems.joinToString("\n\n"))
    }

    @Test
    fun `snapshot rendering is deterministic`() {
        val personas = simulatorPersonaOptions(includeKickr = true)
        personas.forEach { persona ->
            assertEquals("snapshot of $persona", snapshot(persona), snapshot(persona))
        }
    }

    private fun keyedPersonas(personas: List<SimulatorPersona>): List<Pair<String, SimulatorPersona>> {
        val used = mutableMapOf<String, Int>()
        return personas.map { persona ->
            val base = persona.toString()
                .replace(Regex("[^A-Za-z0-9]+"), "_")
                .trim('_')
            val count = (used[base] ?: 0) + 1
            used[base] = count
            (if (count == 1) base else "${base}_$count") to persona
        }
    }

    private fun snapshot(persona: SimulatorPersona): String =
        sections(persona).joinToString("\n") { (title, value) ->
            "## $title\n${SnapshotRenderer.render(value)}\n"
        }

    private fun sections(persona: SimulatorPersona): List<Pair<String, Any?>> = buildList {
        add("persona" to persona)
        add("advertisedName" to persona.advertisedName)
        add("descriptor" to persona.describe())
        val surface = persona.standardSurface()
        add("standardSurface" to surface)

        when (persona) {
            SimulatorPersona.IndoorBikeSpeedAndPower, is SimulatorPersona.Ftms -> {
                val s = requireNotNull(surface) { "standard persona without a surface: $persona" }
                add(
                    "ftmsAdvertisingPlan" to FtmsAdvertisingPlan.forProfile(
                        s.profile,
                        s.nativeBikeSensors,
                        s.advertisedMachineType,
                    ),
                )
            }

            SimulatorPersona.StrydFootpod -> {
                add("advertisingPlan" to StrydFootpodAdvertisingPlan.create())
                add("staticReadValues" to StrydFootpodGattProfile.staticReadValues())
                add("notifyingCharacteristics" to StrydFootpodGattProfile.notifyingCharacteristics)
            }

            is SimulatorPersona.Concept2Pm5 -> {
                val profile = Concept2Pm5GattProfile(persona)
                val uuids = profile.services.values.flatten().map { it.uuid }.distinct()
                add("advertisingPlan" to Concept2Pm5AdvertisingPlan.forPersona(persona))
                add("gattServices" to profile.services)
                add(
                    "deviceInformationReads" to uuids.mapNotNull { uuid ->
                        profile.readDeviceInformation(uuid, 0)?.let { uuid to it }
                    }.toMap(),
                )
                add("ftmsReads" to uuids.mapNotNull { uuid -> profile.readFtms(uuid)?.let { uuid to it } }.toMap())
            }

            is SimulatorPersona.KeiserMSeries -> {
                // The manufacturer payload is live ride data; a zero placeholder of the real
                // length pins everything else about the plan.
                val placeholder = ByteArray(KeiserMSeriesSimulatorEncoder.MANUFACTURER_PAYLOAD_LENGTH)
                add("advertisingPlan (zero payload)" to KeiserMSeriesAdvertisingPlan.create(persona, placeholder))
            }

            SimulatorPersona.PrecorChrono -> {
                add("advertisingPlan" to PrecorChronoAdvertisingPlan.create())
                add("gattCharacteristics" to PrecorChronoGattProfile.characteristics)
            }

            SimulatorPersona.FlywheelSports -> {
                add("advertisingPlan" to FlywheelSportsAdvertisingPlan.create())
                add("gattCharacteristics" to FlywheelSportsGattProfile.characteristics)
            }

            SimulatorPersona.KayakFirstBlue, SimulatorPersona.KayakFirstOrange -> {
                // The controller uses the adapter's address when it can read it; this is its
                // documented fallback, which keeps the snapshot machine-independent.
                add(
                    "advertisingPlan (fallback address)" to
                        KayakFirstAdvertisingPlan.create(localName = persona.advertisedName),
                )
                add("gattCharacteristics" to KayakFirstGattProfile.characteristics)
            }

            is SimulatorPersona.LifeFitness -> {
                add("advertisingPlan" to LifeFitnessAdvertisingPlan.create(persona.machine))
                add("gattManifest" to LifeFitnessGattProfile.manifest(persona.machine))
            }

            SimulatorPersona.SchwinnX70 -> {
                add("advertisingPlan" to SchwinnX70AdvertisingPlan.create())
                add("gattManifest" to SchwinnX70GattProfile.manifest())
                add("readValues" to SchwinnX70GattProfile.readValues())
            }

            is SimulatorPersona.FeCBle -> {
                val includeCps = persona.overlay == FeCBleSimulatorOverlay.CPS_INCYCLIST_COMPAT
                val manifest = FeCBleGattProfile.capturedManifest(persona.identity, persona.controlEnabled)
                add("advertisingPlan" to tacxAdvertisingPlan(persona.identity, includeCps))
                add("capturedGattManifest" to manifest)
                add(
                    "readValues" to manifest.flatMap { it.characteristics }
                        .map { it.uuid }
                        .distinct()
                        .mapNotNull { uuid -> tacxReadValue(persona.identity, uuid, includeCps)?.let { uuid to it } }
                        .toMap(),
                )
            }

            is SimulatorPersona.Kickr -> {
                add("manifest" to KickrPersonaManifest.forPersona(persona.device))
            }
        }
    }

    private fun firstDifference(expected: String, actual: String): String {
        val e = expected.lines()
        val a = actual.lines()
        val index = (0 until maxOf(e.size, a.size)).first { e.getOrNull(it) != a.getOrNull(it) }
        return "  line ${index + 1}\n  expected: ${e.getOrNull(index)}\n  actual:   ${a.getOrNull(index)}"
    }

    private companion object {
        const val INDEX_FILE = "_index.txt"
        const val HEADER =
            "# FitBridge simulator persona snapshot - generated by SimulatorPersonaSnapshotTest (LIB-5a).\n" +
                "# Delete this file and re-run the test to re-record after an intentional change.\n"
    }
}
