package com.gmail.absolutebollockscreations.gattlibrary.central.capture

import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Exact-line assertions for the NDJSON emitter (plan §0.9.4).
 *
 * Deliberately exact rather than "parse and compare fields": field order is part
 * of the contract, and a capture taken today has to stay readable by whatever
 * reads captures tomorrow. An accidental reordering should fail here rather than
 * silently change the format of every capture ever taken.
 */
class CaptureJsonTest {

    private val ftmsData: UUID = UUID.fromString("00002ad2-0000-1000-8000-00805f9b34fb")

    private fun record(event: CaptureEvent, seq: Long = 1L) =
        CaptureRecord(atMs = 1_712_345_678_901L, seq = seq, event = event)

    @Test
    fun `the header names the build and the session`() {
        val line = CaptureJson.header(
            CaptureHeader(
                appVersion = "1.4.2",
                androidSdk = 34,
                deviceModel = "Pixel 8",
                session = "a3f9",
                t0Ms = 1_712_345_678_000L,
            ),
        )

        assertEquals(
            """{"capture_version":1,"app":"1.4.2","android":34,"model":"Pixel 8",""" +
                """"session":"a3f9","t0":1712345678000}""",
            line,
        )
    }

    @Test
    fun `every event leads with t seq conn and ev`() {
        val line = CaptureJson.encode(record(ConnectEvent(conn = "c1", generation = 2), seq = 7))

        assertEquals("""{"t":1712345678901,"seq":7,"conn":"c1","ev":"connect","gen":2}""", line)
    }

    @Test
    fun `a connection-less event omits conn entirely`() {
        val line = CaptureJson.encode(record(DroppedEvent(count = 12)))

        assertEquals("""{"t":1712345678901,"seq":1,"ev":"dropped","n":12}""", line)
    }

    @Test
    fun `notify payloads are lowercase hex with a length`() {
        val line = CaptureJson.encode(
            record(
                NotifyEvent(
                    conn = "c1",
                    characteristic = ftmsData.toString(),
                    payload = byteArrayOf(0x00, 0x0F, 0x7F.toByte(), 0x80.toByte(), 0xFF.toByte()),
                ),
            ),
        )

        assertTrue("high-bit bytes must not sign-extend, got $line", line.contains("\"hex\":\"000f7f80ff\""))
        assertTrue(line.contains("\"len\":5"))
    }

    @Test
    fun `an empty payload is an empty hex string not null`() {
        val line = CaptureJson.encode(
            record(ReadEvent(conn = "c1", characteristic = ftmsData.toString(), payload = ByteArray(0))),
        )

        assertTrue(line, line.contains("\"hex\":\"\",\"len\":0"))
    }

    @Test
    fun `a device name from the air cannot corrupt the line`() {
        // Names are attacker-controlled: a quote or a newline would otherwise
        // split one record into two unparseable ones. The trailing U+0001 covers
        // the generic control-character branch, which must emit a backslash-u
        // escape rather than the raw byte: JSON forbids an unescaped control
        // character inside a string.
        val line = CaptureJson.encode(
            record(
                ScanEvent(
                    name = "Bad\"Name\\With\nControl\u0001",
                    rssi = -61,
                    serviceUuids = listOf("1826"),
                    manufacturerData = mapOf(0x0157 to byteArrayOf(0x02, 0x01)),
                    serviceData = emptyMap(),
                ),
            ),
        )

        assertTrue(line, line.contains("""\"Name\\With\nControl\u0001"""))
        assertEquals("exactly one line", 1, line.lines().size)
    }

    @Test
    fun `a null name and a null selection are written as JSON null`() {
        val scan = CaptureJson.encode(
            record(
                ScanEvent(
                    name = null,
                    rssi = -40,
                    serviceUuids = emptyList(),
                    manufacturerData = emptyMap(),
                    serviceData = emptyMap(),
                ),
            ),
        )
        assertTrue(scan, scan.contains("\"name\":null"))
        assertTrue(scan, scan.contains("\"services\":[]"))

        val match = CaptureJson.encode(
            record(MatchEvent(conn = "c1", candidates = emptyList(), selected = null)),
        )
        assertTrue(match, match.contains("\"selected\":null"))
    }

    @Test
    fun `raw advertisement payloads are preserved unparsed`() {
        // The Keiser M3i case: the entire metric stream lives in manufacturer
        // data this app does not decode, so a parsed-only record is useless.
        val line = CaptureJson.encode(
            record(
                ScanEvent(
                    name = "M3",
                    rssi = -55,
                    serviceUuids = emptyList(),
                    manufacturerData = mapOf(0x0102 to byteArrayOf(0x02, 0x01, 0x33)),
                    serviceData = mapOf("1826" to byteArrayOf(0x01, 0x02, 0x00)),
                ),
            ),
        )

        assertTrue(line, line.contains("\"mfg\":{\"258\":\"020133\"}"))
        assertTrue(line, line.contains("\"svcdata\":{\"1826\":\"010200\"}"))
    }

    @Test
    fun `advertisement source state is structural and contains no address or payload`() {
        val line = CaptureJson.encode(
            record(
                AdvertisementSourceEvent(
                    conn = "c3",
                    family = "keiser_m_series",
                    candidate = "vendor.keiser.m",
                    systemId = 56,
                    firmware = "6.40",
                    generation = 2L,
                    state = "stale",
                    decision = "freshness_expired",
                    freshnessMs = 1_000L,
                ),
            ),
        )

        assertTrue(line.contains("\"ev\":\"advertisement_source\""))
        assertTrue(line.contains("\"candidate\":\"vendor.keiser.m\""))
        assertTrue(line.contains("\"decision\":\"freshness_expired\""))
        assertFalse(line.contains("AA:BB:CC:DD:EE:FF"))
        assertFalse(line.contains("\"hex\""))
        assertFalse(line.contains("\"payload\""))
    }

    @Test
    fun `the demote event carries the evidence needed to fix a fingerprint`() {
        val line = CaptureJson.encode(
            record(
                DemoteEvent(
                    conn = "c2",
                    from = "ftms.generic",
                    to = null,
                    reason = "No MetricSample after subscription completion",
                    timeoutMs = 10_000L,
                    evidence = "[SERVICE:1826:w3:low:advertised]",
                ),
                seq = 88,
            ),
        )

        assertEquals(
            """{"t":1712345678901,"seq":88,"conn":"c2","ev":"demote",""" +
                """"from":"ftms.generic","to":null,""" +
                """"reason":"No MetricSample after subscription completion",""" +
                """"timeoutMs":10000,"evidence":"[SERVICE:1826:w3:low:advertised]"}""",
            line,
        )
    }

    @Test
    fun `a non-finite sample value is null rather than an unparseable token`() {
        val line = CaptureJson.encode(
            record(
                SampleEvent(
                    conn = "c1",
                    sourceId = "ftms#1",
                    metric = "speed",
                    value = Double.NaN,
                    unit = "kph",
                    quality = "NATIVE",
                ),
            ),
        )

        assertTrue(line, line.contains("\"value\":null"))
        assertFalse("NaN would make the line unparseable", line.contains("NaN"))
    }

    @Test
    fun `the gatt inventory nests services characteristics and descriptors`() {
        val line = CaptureJson.encode(
            record(
                GattInventoryEvent(
                    conn = "c1",
                    services = listOf(
                        CapturedService(
                            uuid = "1826",
                            characteristics = listOf(
                                CapturedCharacteristic(
                                    uuid = "2ad2",
                                    properties = 0x10,
                                    descriptors = listOf("2902"),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )

        assertEquals(
            """{"t":1712345678901,"seq":1,"conn":"c1","ev":"gatt_inventory","services":[""" +
                """{"uuid":"1826","chars":[{"uuid":"2ad2","props":16,"descriptors":["2902"]}]}]}""",
            line,
        )
    }

    @Test
    fun `booleans are written unquoted`() {
        val line = CaptureJson.encode(
            record(CccdEvent(conn = "c1", characteristic = "2ad2", enabled = true, result = "SUCCESS")),
        )

        assertTrue(line, line.contains("\"enabled\":true"))
    }
}
