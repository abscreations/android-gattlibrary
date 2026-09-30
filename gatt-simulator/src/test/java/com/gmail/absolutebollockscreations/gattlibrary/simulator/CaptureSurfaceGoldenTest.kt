package com.gmail.absolutebollockscreations.gattlibrary.simulator

import com.gmail.absolutebollockscreations.gattlibrary.central.FixtureJson
import com.gmail.absolutebollockscreations.gattlibrary.core.ids.PrecorUuids
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/**
 * Ties the Life Fitness and Precor Chrono GATT surfaces to their captures. These types are
 * `internal` to :gatt-simulator, so the test lives here (it has :gatt-fixtures) rather than in
 * :gatt-conformance; it reads the same fixtures the conformance tests do.
 *
 * - **Life Fitness treadmill**: `qz/life_fitness_issue_1018_gatt_inventory.json` (the discovery the
 *   QZ client performed on a physical treadmill). Every service except GAP and GATT (owned by the
 *   platform) must be served in the captured order, and each characteristic's UUID and property
 *   octet must equal the capture's. The one known difference is asserted.
 * - **Precor Chrono**: `hardware/precor_chrono_qz_issue_261.json`. Service and measurement UUIDs and
 *   the notify-only property must match; the advertisement name must be the capture's label.
 *   The manufacturer block (`fc 01`, `02 06`) is NOT in the fixture (its privacy rule keeps
 *   advertisements out), so it stays a literal in `PrecorChronoGattProfileTest`.
 */
class CaptureSurfaceGoldenTest {
    private fun fixture(path: String): Map<String, Any?> {
        val stream = requireNotNull(javaClass.classLoader?.getResourceAsStream("fixtures/$path")) { "missing $path" }
        return FixtureJson.obj(FixtureJson.parse(stream.use { it.readBytes().toString(Charsets.UTF_8) }))
    }

    private fun objects(value: Any?) = FixtureJson.array(value).map(FixtureJson::obj)

    private fun octet(text: Any?): Int = (text as String).removePrefix("0x").toInt(16)

    @Test
    fun `Life Fitness treadmill surface matches the captured inventory`() {
        val inventory = fixture("qz/life_fitness_issue_1018_gatt_inventory.json")
        val captured = objects(inventory["services"])
            .filterNot { FixtureJson.string(it["uuid"]).startsWith("00001800") || FixtureJson.string(it["uuid"]).startsWith("00001801") }
        val served = LifeFitnessGattProfile.manifest(LifeFitnessMachine.TREADMILL)

        assertEquals(
            "service order",
            captured.map { UUID.fromString(FixtureJson.string(it["uuid"])) },
            served.map { it.uuid },
        )

        val fe13 = UUID.fromString("0000fe13-0000-1000-8000-00805f9b34fb")
        for (service in captured) {
            val uuid = UUID.fromString(FixtureJson.string(service["uuid"]))
            val expected = objects(service["characteristics"]).associate {
                UUID.fromString(FixtureJson.string(it["uuid"])) to octet(it["propertiesOctet"])
            }
            val actual = served.single { it.uuid == uuid }.characteristics.associate { it.uuid to it.properties }
            if (uuid == fe13) continue // declared below
            assertEquals("characteristics of $uuid", expected, actual)
        }
    }

    @Test
    fun `the FE13 service is served empty although the capture shows two characteristics`() {
        // Declared gap. The capture's 0xFE13 service holds 0x2ADB (write without response) and
        // 0x2ADD (read, notify). The persona serves FE13 with no characteristics. No client in the
        // repo uses them, and their semantics are unknown, so nothing is invented; this assertion
        // forces a decision if either side changes.
        val inventory = fixture("qz/life_fitness_issue_1018_gatt_inventory.json")
        val fe13 = objects(inventory["services"]).single { FixtureJson.string(it["uuid"]).startsWith("0000fe13") }
        assertEquals(
            mapOf("00002adb-0000-1000-8000-00805f9b34fb" to 0x04, "00002add-0000-1000-8000-00805f9b34fb" to 0x12),
            objects(fe13["characteristics"]).associate { FixtureJson.string(it["uuid"]) to octet(it["propertiesOctet"]) },
        )
        val served = LifeFitnessGattProfile.manifest(LifeFitnessMachine.TREADMILL)
            .single { it.uuid == UUID.fromString("0000fe13-0000-1000-8000-00805f9b34fb") }
        assertTrue(served.characteristics.isEmpty())
    }

    @Test
    fun `the captured inventory has no control point or ranges, as the persona`() {
        val inventory = fixture("qz/life_fitness_issue_1018_gatt_inventory.json")
        val assertions = FixtureJson.obj(inventory["assertions"])
        assertEquals(false, assertions["ftmsControlPointPresent"])
        assertEquals(false, assertions["ftmsSupportedRangeCharacteristicsPresent"])
    }

    @Test
    fun `Precor Chrono surface and name match the physical capture`() {
        val capture = fixture("hardware/precor_chrono_qz_issue_261.json")
        assertEquals(FixtureJson.string(capture["service"]), PrecorUuids.UUID_PRECOR_SERVICE.toString())
        assertEquals(FixtureJson.string(capture["characteristic"]), PrecorUuids.UUID_PRECOR_MEASUREMENT.toString())
        assertEquals(FixtureJson.string(capture["deviceLabel"]), PrecorChronoAdvertisingPlan.create().localName)

        val measurement = PrecorChronoGattProfile.characteristics.single()
        assertEquals(PrecorUuids.UUID_PRECOR_MEASUREMENT, measurement.uuid)
        assertTrue(measurement.notifiable)
        assertEquals(false, measurement.readable)
        assertEquals(false, measurement.writable)
        // The client enabled notifications with 01 00, so a notify (not indicate) CCCD.
        assertEquals(listOf(1, 0), FixtureJson.array(capture["cccdWrite"]).map { FixtureJson.int(it) })
    }
}
