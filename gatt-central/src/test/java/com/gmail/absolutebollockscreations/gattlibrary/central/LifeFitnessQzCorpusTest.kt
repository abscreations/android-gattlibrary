package com.gmail.absolutebollockscreations.gattlibrary.central

import com.gmail.absolutebollockscreations.gattlibrary.central.LifeFitnessFingerprint.CharacteristicInventory
import com.gmail.absolutebollockscreations.gattlibrary.central.LifeFitnessFingerprint.ServiceInventory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/**
 * Consumers and integrity checks for the four QZ-derived Life Fitness evidence corpora.
 *
 * The fixtures deliberately answer different questions:
 *
 * - issue #1018 is raw standard-FTMS replay evidence, not a circular decoded golden;
 * - issue #3374 supplies independently checkable Discover SE speed/incline/checksum vectors;
 * - the separate #3374 inventory proves that Discover is not the canonical B90/B91 setup path;
 * - issue #834 is a real same-service negative corpus for the Precor Chrono frame gate.
 *
 * Keeping the consumers together makes the variant boundary executable. A future contributor
 * cannot make a Discover or T5 packet pass by quietly broadening the canonical Life Fitness or
 * Precor matchers without breaking the evidence that says they are separate protocols.
 */
class LifeFitnessQzCorpusTest {

    private val fixtureNames = listOf(
        "life_fitness_issue_1018_ftms_treadmill_capture",
        "life_fitness_issue_3374_discover_se_measurements",
        "life_fitness_issue_3374_discover_se_inventory",
        "life_fitness_issue_834_t5_precor_collision",
    )

    private fun text(name: String): String {
        val stream = javaClass.classLoader?.getResourceAsStream("fixtures/qz/$name.json")
            ?: error("fixture $name.json is missing")
        return stream.bufferedReader().use { it.readText() }
    }

    private fun load(name: String): Map<String, Any?> = FixtureJson.obj(FixtureJson.parse(text(name)))

    private fun cases(fixture: Map<String, Any?>): List<Map<String, Any?>> =
        FixtureJson.array(fixture["cases"]).map(FixtureJson::obj)

    private fun packet(case: Map<String, Any?>): ByteArray =
        FixtureJson.array(case["packet"]).map { FixtureJson.int(it).toByte() }.toByteArray()

    private fun uuid(value: Any?): UUID = UUID.fromString(FixtureJson.string(value))

    @Test
    fun `all four fixtures parse and contain no address or email`() {
        val mac = Regex("(?i)\\b(?:[0-9a-f]{2}[:-]){5}[0-9a-f]{2}\\b")
        val email = Regex("(?i)\\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}\\b")

        for (name in fixtureNames) {
            val raw = text(name)
            assertTrue("$name must be a JSON object", load(name).isNotEmpty())
            assertFalse("$name contains a Bluetooth address", mac.containsMatchIn(raw))
            assertFalse("$name contains an email address", email.containsMatchIn(raw))
        }
    }

    @Test
    fun `issue 1018 corpus has the captured fingerprints and valid packet declarations`() {
        val fixture = load("life_fitness_issue_1018_ftms_treadmill_capture")
        val captured = cases(fixture)
        assertEquals(221, FixtureJson.int(fixture["caseCount"]))
        assertEquals(221, captured.size)
        assertEquals(setOf(1, 2), captured.map { FixtureJson.int(it["session"]) }.toSet())

        val fingerprints = captured.groupingBy { case ->
            FixtureJson.string(case["flags"]) to FixtureJson.int(case["length"])
        }.eachCount()
        assertEquals(
            mapOf(
                ("0x049E" to 24) to 198,
                ("0x059E" to 25) to 23,
            ),
            fingerprints,
        )

        for (case in captured) {
            val bytes = packet(case)
            assertEquals(FixtureJson.int(case["length"]), bytes.size)
            val flags = (bytes[0].toInt() and 0xFF) or ((bytes[1].toInt() and 0xFF) shl 8)
            assertEquals(FixtureJson.string(case["flags"]), "0x%04X".format(flags))
        }
    }

    @Test
    fun `all issue 1018 packets traverse the production treadmill parser`() {
        val captured = cases(load("life_fitness_issue_1018_ftms_treadmill_capture"))

        for (case in captured) {
            val state = BtEngineState()
            val fusion = MetricFusion(state).also { it.setPersona(FusionPersona.RUN) }
            val emitter = MetricEmitter(
                fusion = fusion,
                sourceId = MetricSourceId(
                    ConnectionId("qz:1018", 1L),
                    MetricProtocol.FTMS,
                    BluetoothIds.UUID_TREADMILL_DATA,
                ),
                sourceClass = MetricSourceClass.FTMS,
                arrivalMs = FixtureJson.int(case["relativeMs"]).toLong(),
            )

            try {
                BtEngineFtmsParsers.doTreadmill(emitter, packet(case))
            } catch (t: Throwable) {
                throw AssertionError(
                    "issue #1018 sequence ${FixtureJson.int(case["sequence"])} threw " +
                        "${t::class.java.simpleName}: ${t.message}",
                    t,
                )
            }

            assertTrue(
                "issue #1018 sequence ${FixtureJson.int(case["sequence"])} produced no metric",
                MetricKind.values().any { fusion.latestSample(it) != null },
            )
        }
    }

    @Test
    fun `discover corpus reproduces every declared wire fact`() {
        val fixture = load("life_fitness_issue_3374_discover_se_measurements")
        val captured = cases(fixture)
        assertEquals(256, FixtureJson.int(fixture["caseCount"]))
        assertEquals(256, captured.size)
        assertEquals(setOf(1, 2), captured.map { FixtureJson.int(it["session"]) }.toSet())

        for (case in captured) {
            val bytes = packet(case)
            val expect = FixtureJson.obj(case["expect"])
            assertEquals(40, bytes.size)
            assertEquals(40, FixtureJson.int(case["length"]))
            assertEquals(FixtureJson.double(expect["speedKmh"]), (bytes[32].toInt() and 0xFF) / 10.0, 1e-9)
            assertEquals(
                FixtureJson.double(expect["inclinePercent"]),
                (bytes[27].toInt() and 0xFF) / 10.0,
                1e-9,
            )
            assertEquals(FixtureJson.int(expect["sequenceByte"]), bytes[6].toInt() and 0xFF)
            val sum = bytes.sumOf { it.toInt() and 0xFF } and 0xFF
            assertEquals(0xF1, sum)
            assertEquals(FixtureJson.int(expect["sumMod256"]), sum)
            assertEquals(true, expect["checksumValid"])
        }
    }

    @Test
    fun `discover inventory is not admitted as the canonical Life Fitness setup surface`() {
        val fixture = load("life_fitness_issue_3374_discover_se_inventory")
        val services = FixtureJson.array(fixture["services"]).map { serviceValue ->
            val service = FixtureJson.obj(serviceValue)
            ServiceInventory(
                serviceUuid = uuid(service["uuid"]),
                characteristics = FixtureJson.array(service["characteristics"]).map { characteristicValue ->
                    val characteristic = FixtureJson.obj(characteristicValue)
                    val properties = FixtureJson.array(characteristic["properties"])
                        .map(FixtureJson::string)
                        .toSet()
                    CharacteristicInventory(
                        uuid = uuid(characteristic["uuid"]),
                        writable = "write" in properties || "writeWithoutResponse" in properties,
                        notifyOrIndicate = "notify" in properties || "indicate" in properties,
                        hasCccd = characteristic["hasCccd"] as Boolean,
                    )
                },
            )
        }

        val expected = FixtureJson.obj(fixture["expectedCanonicalFingerprint"])
        val result = LifeFitnessFingerprint.identify(services)
        assertTrue(result is LifeFitnessFingerprint.Result.NoMatch)
        result as LifeFitnessFingerprint.Result.NoMatch
        assertEquals(FixtureJson.string(expected["reason"]), result.reason.name)
        assertEquals(LifeFitnessFingerprint.Decline.NOT_LIFE_FITNESS, result.reason)
    }

    @Test
    fun `T5 corpus removes the QZ logger length token and retains both real wire families`() {
        val fixture = load("life_fitness_issue_834_t5_precor_collision")
        val captured = cases(fixture)
        assertEquals(1_874, FixtureJson.int(fixture["caseCount"]))
        assertEquals(1_874, captured.size)

        val families = captured.groupingBy { FixtureJson.string(it["kind"]) to FixtureJson.int(it["length"]) }
            .eachCount()
        assertEquals(
            mapOf(
                ("treadmill19" to 19) to 937,
                ("incline4" to 4) to 937,
            ),
            families,
        )

        for (case in captured) {
            val bytes = packet(case)
            val kind = FixtureJson.string(case["kind"])
            val expect = FixtureJson.obj(case["expect"])
            assertEquals(FixtureJson.int(case["length"]), bytes.size)

            when (kind) {
                "treadmill19" -> {
                    assertEquals(listOf(0x53, 0xCB, 0x1A), bytes.take(3).map { it.toInt() and 0xFF })
                    val speed = ((bytes[7].toInt() and 0xFF) or ((bytes[8].toInt() and 0xFF) shl 8)) / 100.0
                    assertEquals(FixtureJson.double(expect["speedKmh"]), speed, 1e-9)
                    assertEquals(null, expect["inclinePercent"])
                }
                "incline4" -> {
                    assertEquals(listOf(0x22, 0x80), bytes.take(2).map { it.toInt() and 0xFF })
                    assertEquals(FixtureJson.double(expect["inclinePercent"]), (bytes[2].toInt() and 0xFF) / 10.0, 1e-9)
                    assertEquals(null, expect["speedKmh"])
                }
                else -> error("unknown issue #834 frame family $kind")
            }
        }
    }

    @Test
    fun `every captured T5 packet is rejected by the Precor Chrono parser`() {
        val captured = cases(load("life_fitness_issue_834_t5_precor_collision"))
        for (case in captured) {
            assertEquals("reject", FixtureJson.string(case["expectedPrecorChronoParse"]))
            assertNull(
                "T5 sequence ${FixtureJson.int(case["sequence"])} must not become Chrono metrics",
                PrecorMeasurement.parse(packet(case)),
            )
        }
    }
}
