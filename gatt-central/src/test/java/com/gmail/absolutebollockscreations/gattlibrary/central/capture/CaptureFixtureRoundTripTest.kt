package com.gmail.absolutebollockscreations.gattlibrary.central.capture

import com.gmail.absolutebollockscreations.gattlibrary.central.capture.CaptureFixtures.toJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Plan §0.9.9 exit criterion 3: capture a synthetic session, parse the NDJSON,
 * and generate a §5 fixture from it.
 *
 * This is the claim §0.9.4 was built on - that recording decoded samples
 * alongside raw payloads makes a capture *convertible* rather than merely
 * readable. Until now that claim was untested, so the capture channel's whole
 * justification rested on an assertion in a design document.
 *
 * The session below is the real Cross Trainer frame from the elliptical
 * captured on 2026-08-06, with the samples FitBridge decoded from it.
 */
class CaptureFixtureRoundTripTest {

    private val crossTrainer = "00002ace-0000-1000-8000-00805f9b34fb"

    /** One real 38-octet Cross Trainer Data notification and its decoded samples. */
    private val session = listOf(
        """{"v":1,"app":"FitBridge","session":"test"}""",
        """{"t":1,"seq":1,"conn":"c1","ev":"connect","generation":1,"mtu":517}""",
        """{"t":2,"seq":2,"conn":"c1","ev":"notify","char":"$crossTrainer",""" +
            """"hex":"fe2f006c0384038201007f0082008606170000003700200007009d00a5001900310209849b00","len":38}""",
        """{"t":3,"seq":3,"conn":"c1","ev":"sample","sourceId":"FTMS:$crossTrainer",""" +
            """"metric":"SPEED","value":8.76,"unit":"KILOMETERS_PER_HOUR","quality":"NATIVE"}""",
        """{"t":4,"seq":4,"conn":"c1","ev":"sample","sourceId":"FTMS:$crossTrainer",""" +
            """"metric":"INCLINATION","value":5.5,"unit":"PERCENT","quality":"NATIVE"}""",
        """{"t":5,"seq":5,"conn":"c1","ev":"sample","sourceId":"FTMS:$crossTrainer",""" +
            """"metric":"RESISTANCE","value":7,"unit":"LEVEL","quality":"NATIVE"}""",
        """{"t":6,"seq":6,"conn":"c1","ev":"sample","sourceId":"FTMS:$crossTrainer",""" +
            """"metric":"POWER","value":157,"unit":"WATTS","quality":"NATIVE"}""",
    )

    private fun build(lines: List<String> = session) = CaptureFixtures.build(
        lines = lines.asSequence(),
        characteristic = crossTrainer,
        family = "ftms.crosstrainer",
        source = "hardware capture 2026-08-06",
        captured = "2026-08-06",
    )

    @Test
    fun `a captured session becomes a fixture case`() {
        val fixture = build()

        assertEquals(1, fixture.cases.size)
        val case = fixture.cases.single()

        // The packet survives as bytes, not as a hex string: a fixture has to be
        // feedable straight into a parser.
        assertEquals(38, case.packet.size)
        assertEquals(0xFE, case.packet[0])
        assertEquals(0x2F, case.packet[1])
        assertEquals(0x00, case.packet[2])
        assertTrue("every byte must be in range", case.packet.all { it in 0..255 })
    }

    @Test
    fun `each payload is paired with the samples it produced`() {
        val expect = build().cases.single().expect

        assertEquals(8.76, expect["SPEED"]!!, 0.0001)
        assertEquals(5.5, expect["INCLINATION"]!!, 0.0001)
        assertEquals(7.0, expect["RESISTANCE"]!!, 0.0001)
        assertEquals(157.0, expect["POWER"]!!, 0.0001)
        assertEquals(4, expect.size)
    }

    @Test
    fun `samples are attributed to the notification that preceded them`() {
        val two = session + listOf(
            """{"t":7,"seq":7,"conn":"c1","ev":"notify","char":"$crossTrainer","hex":"fe2f0000","len":4}""",
            """{"t":8,"seq":8,"conn":"c1","ev":"sample","sourceId":"x","metric":"SPEED","value":0,"unit":"K"}""",
        )
        val cases = build(two).cases

        assertEquals(2, cases.size)
        assertEquals(8.76, cases[0].expect["SPEED"]!!, 0.0001)
        assertEquals("the second packet gets only its own sample", 0.0, cases[1].expect["SPEED"]!!, 0.0001)
        assertEquals(1, cases[1].expect.size)
    }

    @Test
    fun `another characteristic's notification does not steal samples`() {
        val hrm = "00002a37-0000-1000-8000-00805f9b34fb"
        val mixed = session.take(3) + listOf(
            """{"t":4,"seq":4,"conn":"c1","ev":"notify","char":"$hrm","hex":"0056","len":2}""",
            """{"t":5,"seq":5,"conn":"c1","ev":"sample","sourceId":"HRM","metric":"HEART_RATE","value":86,"unit":"BPM"}""",
        )
        val case = build(mixed).cases.single()

        assertTrue(
            "a heart-rate sample must not be attributed to a cross trainer packet",
            case.expect.isEmpty(),
        )
    }

    @Test
    fun `a sample before any notification is discarded rather than guessed at`() {
        val orphaned = listOf(
            """{"t":1,"seq":1,"conn":"c1","ev":"sample","sourceId":"x","metric":"SPEED","value":9.9,"unit":"K"}""",
        ) + session
        val case = build(orphaned).cases.single()

        assertEquals("only the four samples that followed the packet", 4, case.expect.size)
        assertEquals(8.76, case.expect["SPEED"]!!, 0.0001)
    }

    @Test
    fun `the generated fixture matches the section 5 2 layout`() {
        val json = build().toJson()

        for (key in listOf("family", "source", "captured", "characteristic", "cases", "name", "packet", "expect")) {
            assertTrue("fixture must carry \"$key\"", json.contains("\"$key\""))
        }
        assertTrue(json.contains("\"family\": \"ftms.crosstrainer\""))
        assertTrue(json.contains("\"characteristic\": \"$crossTrainer\""))
        assertTrue("packet is a byte array", json.contains("\"packet\": [254, 47, 0,"))
    }

    @Test
    fun `the generated fixture re-parses to the same cases`() {
        // The round trip has to close: a fixture we cannot read back is a file,
        // not a fixture.
        val json = build().toJson()
        val packetLine = json.lines().first { it.contains("\"packet\"") }
        val bytes = packetLine.substringAfter("[").substringBefore("]")
            .split(",").map { it.trim().toInt() }

        assertEquals(build().cases.single().packet, bytes)
    }

    @Test
    fun `a header line and non-sample events are ignored`() {
        // The header carries no ev, and connect/cccd/disconnect are not fixture
        // material. None may produce a case or corrupt one.
        val noisy = listOf(
            """{"v":1,"app":"FitBridge","session":"test"}""",
            """{"t":1,"seq":1,"conn":"c1","ev":"cccd","char":"$crossTrainer","enabled":true,"result":"NOTIFY"}""",
        ) + session + listOf(
            """{"t":9,"seq":9,"conn":"c1","ev":"disconnect","reason":"user"}""",
            "",
        )

        assertEquals(1, build(noisy).cases.size)
        assertEquals(4, build(noisy).cases.single().expect.size)
    }

    @Test
    fun `the reader survives escapes and a nested record`() {
        val awkward = listOf(
            """{"t":1,"seq":1,"ev":"scan","name":"a \"quoted\" \\ name ","conn":null}""",
            """{"t":2,"seq":2,"ev":"gatt_inventory","services":[{"uuid":"1826","chars":[{"uuid":"2ace"}]}]}""",
        ) + session

        // Neither line is fixture material; what matters is that neither derails
        // the scan of the ones that are.
        assertEquals(1, build(awkward).cases.size)
        assertEquals(38, build(awkward).cases.single().packet.size)
    }

    @Test
    fun `a capture with no matching notification yields an empty fixture, not a wrong one`() {
        val fixture = CaptureFixtures.build(
            lines = session.asSequence(),
            characteristic = "00002ad2-0000-1000-8000-00805f9b34fb",
            family = "ftms.indoorbike",
            source = "x",
            captured = "2026-08-06",
        )
        assertTrue(fixture.cases.isEmpty())
    }
}
