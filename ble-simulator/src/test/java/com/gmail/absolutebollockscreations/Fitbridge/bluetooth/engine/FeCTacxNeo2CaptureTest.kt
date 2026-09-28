package com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine

import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.simulator.FeCBleControlCommand
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.simulator.FeCBleControlDecoder
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.simulator.RejectReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Consumers for the four Tacx Neo 2 HCI corpora extracted on 2026-08-25.
 *
 * ## What these add that `fec_tacx_neo_2.json` does not
 *
 * That corpus is a QZ debug-text extraction of FE-C *notifications*. These are packet captures of
 * the same model from the other direction: the advertisement, the GATT, the FE-C *control* writes,
 * and the standard CPS/CSC streams. The two are complements — this capture's client never armed the
 * FE-C notify characteristic, so it contains no telemetry frames at all, and the debug-text corpus
 * contains no writes.
 *
 * ## The load-bearing tests here are negative
 *
 * Three facts are asserted as *absences*, and each one blocks something:
 *
 * - no `669AA501-…`, so the Neo 2 is a different BLE identity from the legacy Neo that
 *   `FeCBleSimulatorIdentity.TACX_NEO` reproduces, not a parameter change to it;
 * - zero ATT Read Requests, so every DIS and feature value is unobserved and a Neo 2 simulator
 *   identity cannot be built from this evidence without inventing firmware strings;
 * - not one control frame carries a valid ANT checksum, and the trainer honoured them all.
 */
class FeCTacxNeo2CaptureTest {

    private val fixtures = listOf(
        "fec_tacx_neo_2_ble_identity",
        "fec_tacx_neo_2_control_writes",
        "fec_tacx_neo_2_cycling_sensors",
        "fec_tacx_neo_2_link_timeline",
    )

    private fun text(name: String): String {
        val stream = javaClass.classLoader?.getResourceAsStream("fixtures/hardware/$name.json")
            ?: error("fixture $name.json is missing")
        return stream.bufferedReader().use { it.readText() }
    }

    private fun load(name: String) = FixtureJson.obj(FixtureJson.parse(text(name)))

    private fun arr(value: Any?) = FixtureJson.array(value).map(FixtureJson::obj)

    private fun bool(value: Any?): Boolean =
        value as? Boolean ?: error("expected a JSON boolean, got $value")

    private fun hex(value: String): ByteArray =
        ByteArray(value.length / 2) { ((value[it * 2].digitToInt(16) shl 4) or value[it * 2 + 1].digitToInt(16)).toByte() }

    // ── Privacy ──────────────────────────────────────────────────────────────────

    @Test
    fun `no Neo 2 fixture carries an address or the physical name suffix`() {
        val mac = Regex("(?i)\\b(?:[0-9a-f]{2}[:-]){5}[0-9a-f]{2}\\b")

        for (name in fixtures) {
            val raw = text(name)
            assertTrue("$name must parse", load(name).isNotEmpty())
            assertFalse("$name contains a Bluetooth address", mac.containsMatchIn(raw))
            // The advertised name carries the unit's serial. It is substituted at extraction; this
            // guard is on re-extraction forgetting to.
            assertFalse("$name leaks the physical device-name suffix", raw.contains("07049"))
        }
        for (session in arr(load("fec_tacx_neo_2_ble_identity")["sources"])) {
            assertTrue(FixtureJson.string(session["sourceSha256"]).matches(Regex("[0-9a-f]{64}")))
        }
    }

    // ── Advertising ──────────────────────────────────────────────────────────────

    private fun advertising() = FixtureJson.obj(load("fec_tacx_neo_2_ble_identity")["advertising"])

    @Test
    fun `the advertisement is the exact captured octets`() {
        // Byte-identical across every observation in two sessions two days apart, so it is pinned
        // as raw octets rather than as a description that could drift from them.
        val advInd = FixtureJson.obj(advertising()["advInd"])
        val scanResponse = FixtureJson.obj(advertising()["scanResponse"])

        assertEquals(
            "020106110954616378204e656f2032203930303033",
            FixtureJson.string(advInd["rawHex"]),
        )
        assertEquals(
            "031980040703181816180a1811079ecadc240ee5a9e093f3a3b5c1fe406e",
            FixtureJson.string(scanResponse["rawHex"]),
        )
        assertTrue(bool(advertising()["identicalAcrossAllObservations"]))

        // Re-derive the structures from the octets rather than trusting the decoded fields beside
        // them: a fixture that describes itself incorrectly is worse than one that only has bytes.
        val advBytes = hex(FixtureJson.string(advInd["rawHex"]))
        assertEquals(0x02, advBytes[0].toInt())
        assertEquals(0x01, advBytes[1].toInt())
        assertEquals(0x06, advBytes[2].toInt())
        assertEquals(0x09, advBytes[4].toInt())
        assertEquals("Tacx Neo 2 90003", String(advBytes, 5, advBytes.size - 5, Charsets.UTF_8))
    }

    @Test
    fun `the scan response carries Generic Cycling, CPS, CSC, DIS and FEC1 and no FTMS`() {
        val structures = arr(FixtureJson.obj(advertising()["scanResponse"])["structures"])
        val appearance = structures.single { FixtureJson.string(it["type"]) == "0x19" }
        val uuid16 = structures.single { FixtureJson.string(it["type"]) == "0x03" }
        val uuid128 = structures.single { FixtureJson.string(it["type"]) == "0x07" }

        assertEquals("0x0480", FixtureJson.string(appearance["value"]))
        assertEquals(
            listOf(
                "00001818-0000-1000-8000-00805f9b34fb",
                "00001816-0000-1000-8000-00805f9b34fb",
                "0000180a-0000-1000-8000-00805f9b34fb",
            ),
            FixtureJson.array(uuid16["uuids"]).map(FixtureJson::string),
        )
        assertEquals(
            listOf("6e40fec1-b5a3-f393-e0a9-e50e24dcca9e"),
            FixtureJson.array(uuid128["uuids"]).map(FixtureJson::string),
        )
        assertTrue(bool(advertising()["noFitnessMachineService"]))
        assertTrue(bool(advertising()["noManufacturerSpecificData"]))
    }

    // ── GATT ─────────────────────────────────────────────────────────────────────

    private fun services() = arr(FixtureJson.obj(load("fec_tacx_neo_2_ble_identity")["gatt"])["services"])

    private fun characteristicsOf(uuid: String): Set<String> =
        arr(services().single { FixtureJson.string(it["uuid"]) == uuid }["characteristics"])
            .map { FixtureJson.string(it["uuid"]) }.toSet()

    @Test
    fun `the Neo 2 is a different BLE identity from the legacy Neo`() {
        // The whole reason a Neo 2 simulator entry is a new identity rather than a tweak to
        // TACX_NEO. If this ever passes trivially because the fixture was regenerated from a
        // legacy Neo capture, that is worth failing over.
        val uuids = services().map { FixtureJson.string(it["uuid"]) }.toSet()

        assertFalse(
            "the legacy Neo's proprietary service must be absent",
            uuids.contains("669aa501-0c08-969e-e211-86ad5062675f"),
        )
        for (uuid in listOf(
            "0000fe59-0000-1000-8000-00805f9b34fb",
            "cf285001-00db-15a1-0e3f-ffa5794c3719",
            "fa1c030e-52d1-2062-0777-bc61f0b8e27b",
        )) {
            assertTrue("$uuid is part of the Neo 2 surface", uuids.contains(uuid))
        }
        // The legacy Neo's CSC has no control point; this one does.
        assertTrue(
            characteristicsOf("00001816-0000-1000-8000-00805f9b34fb")
                .contains("00002a55-0000-1000-8000-00805f9b34fb"),
        )
    }

    @Test
    fun `the FE-C service carries the notify and control characteristics FitBridge expects`() {
        val fec = characteristicsOf("6e40fec1-b5a3-f393-e0a9-e50e24dcca9e")

        assertTrue(fec.contains(BluetoothIds.UUID_FEC_MEASUREMENT.toString()))
        assertTrue(fec.contains(BluetoothIds.UUID_FEC_CONTROL.toString()))
    }

    @Test
    fun `every readable value on the device is unobserved, which blocks a simulator identity`() {
        // A persona has to serve DIS and feature reads. This capture supplies none of them, and
        // carrying the legacy Neo's "Tacx"/"Neo"/"0.7.4" across would be inventing a wire fact
        // about different hardware.
        val assertions = FixtureJson.obj(load("fec_tacx_neo_2_ble_identity")["assertions"])
        val reads = FixtureJson.obj(load("fec_tacx_neo_2_link_timeline")["readRequests"])

        assertEquals(0, FixtureJson.int(reads["count"]))
        assertFalse(bool(assertions["deviceInformationValuesObserved"]))
        assertFalse(bool(assertions["featureValuesObserved"]))

        // Device Information is present and readable - the gap is the values, not the surface.
        val dis = characteristicsOf("0000180a-0000-1000-8000-00805f9b34fb")
        assertEquals(5, dis.size)
    }

    // ── FE-C control writes: the checksum finding, made executable ───────────────

    private fun controlFrames() = arr(load("fec_tacx_neo_2_control_writes")["frames"])

    @Test
    fun `no captured control frame carries a valid ANT checksum`() {
        val frames = controlFrames()
        assertEquals(363, frames.size)

        for (frame in frames) {
            val bytes = hex(FixtureJson.string(frame["frame"]))
            var xor = 0
            for (i in 0 until bytes.size - 1) xor = xor xor (bytes[i].toInt() and 0xFF)
            assertNotEquals(
                "ANT XOR unexpectedly matches for ${frame["frame"]}",
                bytes.last().toInt() and 0xFF,
                xor,
            )
            assertFalse(bool(frame["antXorValid"]))
        }
    }

    @Test
    fun `every captured control frame fits the additive rule the client actually used`() {
        // sum(octets[1..10]) mod 256: excludes the SYNC octet and, tellingly, the final data
        // octet. For page 0x31 the excluded octet is the target-power high byte, so two commands
        // 256 quarter-watts apart checksum identically. That is what makes it a defect rather
        // than an alternative convention.
        for (frame in controlFrames()) {
            val bytes = hex(FixtureJson.string(frame["frame"]))
            var sum = 0
            for (i in 1 until 11) sum += bytes[i].toInt() and 0xFF
            assertEquals(
                "additive rule for ${frame["frame"]}",
                bytes.last().toInt() and 0xFF,
                sum % 256,
            )
            assertTrue(bool(frame["observedRuleValid"]))
        }
    }

    @Test
    fun `the FitBridge simulator rejects every frame this trainer accepted`() {
        // Not an assertion that the decoder is wrong. It is an assertion that the gap exists and
        // is known: real hardware honoured all 363 of these and FeCBleControlDecoder honours none.
        // If the decoder is ever loosened, this test is where that decision becomes visible.
        for (frame in controlFrames()) {
            val decoded = FeCBleControlDecoder.decode(hex(FixtureJson.string(frame["frame"])))
            assertEquals(
                "simulator verdict for ${frame["frame"]}",
                FeCBleControlCommand.Rejected(RejectReason.BAD_CHECKSUM),
                decoded,
            )
        }
    }

    @Test
    fun `the trainer honoured the ERG ladder despite the checksums`() {
        // The evidence that the frames were acted on rather than silently dropped, without which
        // the checksum finding would say nothing about the hardware.
        val steps = arr(FixtureJson.obj(load("fec_tacx_neo_2_control_writes")["ergTargetCorrelation"])["steps"])
        assertEquals(13, steps.size)

        var priorTarget = 0.0
        for (step in steps) {
            val target = FixtureJson.double(step["targetWatts"])
            assertTrue("the ladder must ascend", target > priorTarget)
            priorTarget = target
        }
        // Skip the first step: the rider is spinning up and has not reached target yet.
        for (step in steps.drop(1)) {
            val target = FixtureJson.double(step["targetWatts"])
            val measured = FixtureJson.double(step["measuredMeanWatts"])
            assertTrue(
                "measured ${measured}W must track target ${target}W",
                kotlin.math.abs(measured - target) <= 10.0,
            )
        }
    }

    @Test
    fun `the control corpus covers both control modes and keeps 0xFC opaque`() {
        val root = load("fec_tacx_neo_2_control_writes")
        val pages = FixtureJson.obj(root["pageCounts"])

        assertEquals(243, FixtureJson.int(pages["0x31"]))
        assertEquals(58, FixtureJson.int(pages["0x33"]))
        assertEquals(62, FixtureJson.int(pages["0xFC"]))

        // 0xFC is not decoded anywhere in FitBridge and is deliberately not guessed here.
        val unresolved = FixtureJson.obj(root["unresolvedPage"])
        assertEquals("0xFC", FixtureJson.string(unresolved["page"]))
        for (frame in controlFrames().filter { FixtureJson.string(it["page"]) == "0xFC" }) {
            assertEquals("unresolved", FixtureJson.string(FixtureJson.obj(frame["decoded"])["field"]))
        }
    }

    // ── CPS / CSC hazards ────────────────────────────────────────────────────────

    @Test
    fun `the sensor corpus declares lengths that match its flag words`() {
        val cases = arr(load("fec_tacx_neo_2_cycling_sensors")["cases"])
        assertEquals(2523, cases.size)

        for (case in cases) {
            val packet = hex(FixtureJson.string(case["packet"]))
            assertEquals(FixtureJson.int(case["length"]), packet.size)
            val cps = FixtureJson.string(case["characteristic"]).startsWith("00002a63")
            val expected = when {
                cps && FixtureJson.string(case["flags"]) == "0x0010" -> 10
                cps && FixtureJson.string(case["flags"]) == "0x0030" -> 14
                !cps && FixtureJson.string(case["flags"]) == "0x01" -> 7
                !cps && FixtureJson.string(case["flags"]) == "0x03" -> 11
                else -> error("unexpected flag word ${case["flags"]}")
            }
            assertEquals("length for flags ${case["flags"]}", expected, packet.size)
        }
    }

    @Test
    fun `the sensor corpus contains the three hazards it claims`() {
        // Offsets shift mid-stream, uint16 event times wrap, and unchanged packets are re-sent
        // byte for byte. Each is asserted to be non-zero rather than pinned to an exact count, so
        // a re-extraction that adds sessions does not fail spuriously.
        val hazards = FixtureJson.obj(load("fec_tacx_neo_2_cycling_sensors")["hazards"])
        val flags = FixtureJson.obj(hazards["flagWordChangesMidStream"])
        val rollover = FixtureJson.obj(hazards["eventTimeRollover"])
        val repeats = FixtureJson.obj(hazards["byteIdenticalConsecutiveRepeats"])

        assertTrue(FixtureJson.int(flags["cyclingPowerTransitions"]) > 0)
        assertTrue(FixtureJson.int(flags["cscTransitions"]) > 0)
        assertTrue(FixtureJson.int(rollover["cyclingPowerWheelEventTimeWraps"]) > 0)
        assertTrue(FixtureJson.int(rollover["cscWheelEventTimeWraps"]) > 0)
        assertTrue(FixtureJson.int(repeats["cyclingPower"]) > 0)
        assertTrue(FixtureJson.int(repeats["csc"]) > 0)
    }

    // ── Link ─────────────────────────────────────────────────────────────────────

    @Test
    fun `the client never armed FE-C notifications, so this corpus has no telemetry`() {
        // The structural fact that makes these captures the complement of fec_tacx_neo_2.json
        // rather than a duplicate of it.
        val subscriptions = FixtureJson.obj(load("fec_tacx_neo_2_link_timeline")["subscriptions"])
        val armed = FixtureJson.array(subscriptions["armed"]).map(FixtureJson::string)
        val notArmed = FixtureJson.array(subscriptions["notArmed"]).map(FixtureJson::string)

        assertTrue(armed.any { it.startsWith("00002a63") })
        assertTrue(armed.any { it.startsWith("00002a5b") })
        assertTrue(notArmed.single().startsWith("6e40fec2"))
    }

    @Test
    fun `both sessions discovered services and negotiated the same link parameters`() {
        val sessions = arr(load("fec_tacx_neo_2_link_timeline")["sessions"])
        assertEquals(2, sessions.size)

        for (session in sessions) {
            assertTrue(bool(session["serviceDiscoveryPerformed"]))
            val intervals = FixtureJson.array(session["connectionIntervalsMs"]).map(FixtureJson::double)
            assertEquals(listOf(7.5, 48.75, 60.0), intervals)
            assertEquals(0, FixtureJson.int(session["peripheralLatency"]))
            assertEquals(4000, FixtureJson.int(session["supervisionTimeoutMs"]))
        }
        assertEquals("track resistance / SIM", FixtureJson.string(sessions[0]["mode"]))
        assertEquals("target power / ERG", FixtureJson.string(sessions[1]["mode"]))
    }
}
