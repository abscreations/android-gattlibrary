package com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Replays privacy-sanitized physical Tacx FEC2 notifications through the production decoders. */
class FeCTacxHardwareCorpusTest {
    private data class Expected(
        val resource: String,
        val frames: Int,
        val sessions: Int,
        val pages: Map<Int, Int>,
        val maximumPower: Int,
        val targetStatuses: Set<Int>,
        val modelNumber: Int,
    )

    private val corpora = listOf(
        Expected("fec_tacx_flux_2.json", 2589, 2, mapOf(0x10 to 1235, 0x19 to 1218, 0x50 to 40, 0x51 to 39, 0xf0 to 39, 0xf9 to 18), 133, setOf(0, 3), 2980),
        Expected("fec_tacx_neo.json", 1533, 2, mapOf(0x10 to 290, 0x19 to 1146, 0x50 to 21, 0x51 to 21, 0xf0 to 17, 0xfa to 22, 0xfb to 16), 243, setOf(0), 2800),
        Expected("fec_tacx_neo_2.json", 351, 2, mapOf(0x10 to 90, 0x19 to 209, 0x47 to 35, 0x50 to 4, 0x51 to 4, 0xf0 to 3, 0xf9 to 2, 0xfa to 2, 0xfc to 2), 19, setOf(0), 2850),
        // A second, physically different Neo 2 (2026-08-25). Same nine-service GATT, same handle
        // layout, and Page 80 reporting the same hardware revision, manufacturer and model as the
        // unit above - which is the point of keeping it separate rather than appending it as a
        // third session: the agreement is only evidence while the units stay distinguishable.
        Expected("fec_tacx_neo_2_unit2.json", 7969, 1, mapOf(0x10 to 1571, 0x19 to 5225, 0x47 to 899, 0x50 to 122, 0x51 to 116, 0xf0 to 8, 0xf9 to 8, 0xfa to 10, 0xfc to 10), 317, setOf(0), 2850),
    )

    @Test fun `all captured frames pass the production envelope and page decoders`() {
        corpora.forEach { expected ->
            val root = load(expected.resource)
            val sessions = FixtureJson.array(root["sessions"])
            assertEquals(expected.sessions, sessions.size)
            val actualPages = linkedMapOf<Int, Int>()
            var count = 0
            var maxPower = Int.MIN_VALUE
            val targetStatuses = linkedSetOf<Int>()

            sessions.forEach { sessionValue ->
                val session = FixtureJson.obj(sessionValue)
                assertTrue(FixtureJson.string(session["sourceSha256"]).matches(Regex("[0-9a-f]{64}")))
                val frames = FixtureJson.array(session["frames"])
                assertEquals(0, FixtureJson.int(FixtureJson.obj(frames.first())["relativeMs"]))
                var prior = -1
                frames.forEach { frameValue ->
                    val record = FixtureJson.obj(frameValue)
                    val relative = FixtureJson.int(record["relativeMs"])
                    assertTrue(relative >= prior)
                    prior = relative
                    val bytes = FixtureJson.string(record["packet"]).hexBytes()
                    val decoded = FeCFrameCodec.decode(bytes)
                    assertTrue("${expected.resource}: ${record["packet"]}", decoded is FeCFrameDecodeResult.Decoded)
                    val ant = (decoded as FeCFrameDecodeResult.Decoded).frame
                    assertEquals(0x4e, ant.messageType)
                    assertEquals(5, ant.channel)
                    val pageNumber = ant.data[0].toInt() and 0xff
                    assertEquals("0x%02X".format(pageNumber), FixtureJson.string(record["page"]))
                    actualPages[pageNumber] = (actualPages[pageNumber] ?: 0) + 1
                    when (val page = FeCPageParsers.decodePage(ant.data)) {
                        is FeCPage.GeneralFe -> assertEquals(25, page.equipmentType)
                        is FeCPage.TrainerData -> {
                            assertTrue("${expected.resource}: physical Page 25 power is available", page.instantaneousPowerWatts != null)
                            page.instantaneousPowerWatts?.let { maxPower = maxOf(maxPower, it) }
                            targetStatuses += page.targetPowerStatus
                        }
                        is FeCPage.ProductInformation -> assertEquals(0L, page.serialNumber)
                        is FeCPage.ManufacturerInformation -> {
                            assertEquals(1, page.hardwareRevision)
                            assertEquals(89, page.manufacturerId)
                            assertEquals(expected.modelNumber, page.modelNumber)
                        }
                        else -> Unit // common/vendor/page-71 frames are intentionally opaque
                    }
                    count++
                }
            }
            assertEquals(expected.frames, count)
            assertEquals(expected.pages, actualPages)
            assertEquals(expected.maximumPower, maxPower)
            assertEquals(expected.targetStatuses, targetStatuses)
        }
    }

    @Test fun `corpus remains sanitized and does not claim pages absent from physical sessions`() {
        corpora.forEach { expected ->
            val text = resourceText(expected.resource)
            assertFalse(text.contains(Regex("(?i)([0-9a-f]{2}:){5}[0-9a-f]{2}")))

            // Serial redaction is asserted STRUCTURALLY, through the production parser, rather
            // than by searching the document for known serial digits.
            //
            // This used to be `assertFalse(text.contains("33326"))` and `("17867")`. That check
            // was sound while the corpora were small and became a false-positive generator as
            // soon as they were not: a fixture is mostly hex payloads and millisecond integers,
            // so any decimal string eventually appears inside one by chance. The unit-2 corpus
            // contains "33326" inside the relativeMs value 333266 and "2768" - its own physical
            // serial - inside 322768, while carrying a zeroed serial in all 116 of its Page 81
            // frames. A substring probe cannot tell a leak from a coincidence; decoding can.
            var productInformationFrames = 0
            FixtureJson.array(load(expected.resource)["sessions"]).forEach { sessionValue ->
                FixtureJson.array(FixtureJson.obj(sessionValue)["frames"]).forEach { frameValue ->
                    val record = FixtureJson.obj(frameValue)
                    val decoded = FeCFrameCodec.decode(FixtureJson.string(record["packet"]).hexBytes())
                    val page = FeCPageParsers.decodePage((decoded as FeCFrameDecodeResult.Decoded).frame.data)
                    if (page is FeCPage.ProductInformation) {
                        productInformationFrames++
                        assertEquals(
                            "${expected.resource} leaks a physical serial",
                            0L,
                            page.serialNumber,
                        )
                    }
                }
            }
            assertEquals(
                "${expected.resource} declares Page 81 frames it does not contain",
                FixtureJson.int(FixtureJson.obj(load(expected.resource)["pageCounts"])["0x51"] ?: 0),
                productInformationFrames,
            )

            val pages = FixtureJson.obj(load(expected.resource)["pageCounts"]).keys
            assertTrue(pages.intersect(setOf("0x11", "0x12", "0x1A", "0x36")).isEmpty())
        }
    }

    private fun load(name: String) = FixtureJson.obj(FixtureJson.parse(resourceText(name)))

    private fun resourceText(name: String): String = checkNotNull(
        javaClass.classLoader?.getResourceAsStream("fixtures/hardware/$name"),
    ) { "missing Tacx corpus $name" }.bufferedReader().use { it.readText() }

    private fun String.hexBytes(): ByteArray {
        require(length % 2 == 0)
        return chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }
}
