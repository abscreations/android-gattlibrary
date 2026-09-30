package com.gmail.absolutebollockscreations.gattlibrary.conformance

import com.gmail.absolutebollockscreations.gattlibrary.simulator.SchwinnX70Identity
import com.gmail.absolutebollockscreations.gattlibrary.simulator.SchwinnX70Template
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * L25 guard tests for the Schwinn X70 persona against the physical captures in gatt-fixtures.
 *
 * `SchwinnX70Template` is a hand transcription of captured frames and captured octet sequences
 * (simulator main sources cannot read fixtures), so it is double entry. These tests make the two
 * copies unable to drift: every template frame must be an exact captured frame on its
 * characteristic, and every replayed sequence must be the captured sequence from the first frame
 * of its stream, in arrival order.
 *
 * - `PAIRED_170` is QZ issue #1365 (file 11097202) -> `schwinn_x70_170_qz_interleaved.json`
 * - `MAIN_270` is QZ issue #2366 (file 15522764) -> `schwinn_x70_270_qz_interleaved.json`
 *
 * The Device Information strings are checked against `schwinn_x70_270_main.json` (nRF Connect log
 * of a physical 270).
 *
 * Not tied, because the corpus does not contain them: the advertisement and the thirteen
 * characteristic surface (evidence documents only), both gate command literals (written by
 * third-party client code, not captured from a console), and two of the four stream-marker
 * variants (see [streamMarkersPresentInTheCorpusAreExactlyTheTwoSeen]).
 */
class SchwinnGoldenTest {
    private class Stream(val frames: List<ByteArray>) {
        fun octet(index: Int): List<Int> = frames.map { it[index].toInt() and 0xFF }
    }

    private fun stream(file: String, channel: String, length: Int): Stream {
        val cases = Fixtures.objects(Fixtures.json("hardware/$file")["cases"])
        val frames = cases.filter { it["ch"] == channel }.map { Fixtures.bytes(it["packet"]) }
            .filter { it.size == length }
        check(frames.isNotEmpty()) { "no $length-octet $channel frames in $file" }
        return Stream(frames)
    }

    private fun assertTemplateIsCaptured(template: SchwinnX70Template, file: String) {
        val supplemental = stream(file, "supplemental", 20)
        val sibling = stream(file, "sibling", 14)
        val main = stream(file, "main", 17)

        fun assertFrame(name: String, expected: ByteArray, stream: Stream) {
            assertTrue(
                "$template.$name is not a frame captured in $file",
                stream.frames.any { it.contentEquals(expected) },
            )
        }
        assertFrame("supplemental", template.supplemental, supplemental)
        assertFrame("sibling", template.sibling, sibling)
        assertFrame("main", template.main, main)

        assertEquals("$template.supplementalByte3", template.supplementalByte3.toList(), supplemental.octet(3).take(template.supplementalByte3.size))
        assertEquals("$template.siblingByte1", template.siblingByte1.toList(), sibling.octet(1).take(template.siblingByte1.size))
        assertEquals("$template.mainByte3", template.mainByte3.toList(), main.octet(3).take(template.mainByte3.size))
    }

    @Test
    fun `the 170 template is the captured frames and sequences of QZ issue 1365`() =
        assertTemplateIsCaptured(SchwinnX70Template.PAIRED_170, "schwinn_x70_170_qz_interleaved.json")

    @Test
    fun `the 270 template is the captured frames and sequences of QZ issue 2366`() =
        assertTemplateIsCaptured(SchwinnX70Template.MAIN_270, "schwinn_x70_270_qz_interleaved.json")

    @Test
    fun `the Device Information strings are those read from a physical 270`() {
        val capture = Fixtures.json("hardware/schwinn_x70_270_main.json")
        assertEquals(capture["firmware"], SchwinnX70Identity.FIRMWARE_REVISION)
        assertEquals(capture["software"], SchwinnX70Identity.SOFTWARE_REVISION)
        assertEquals(capture["console"], SchwinnX70Identity.MODEL_NUMBER)
        assertEquals(capture["manufacturer"], SchwinnX70Identity.MANUFACTURER.trimEnd('\u0000'))
        assertEquals("NUL padding to the nineteen-octet wire length", 19, SchwinnX70Identity.MANUFACTURER.length)
    }

    @Test
    fun `stream markers present in the corpus are exactly the two seen`() {
        fun markersIn(path: String): Set<List<Int>> =
            Fixtures.objects(Fixtures.json(path)["cases"]).map { Fixtures.bytes(it["packet"]) }
                .filter { it.size == 5 && it[0] == 0x05.toByte() && it[1] == 0x09.toByte() }
                .map { frame -> frame.map { it.toInt() and 0xFF } }.toSet()

        val seen = listOf(
            "schwinn_x70_170_qz_interleaved.json", "schwinn_x70_170_qz_link_open.json",
            "schwinn_x70_270_qz_interleaved.json", "schwinn_x70_170_qz_resistance_sweep.json",
            "schwinn_x70_270_qz_heart_rate.json", "schwinn_x70_570u_main.json", "schwinn_x70_270_main.json",
        ).flatMap { markersIn("hardware/$it") }.toSet()

        assertEquals(
            "captured: 05 09 01 01 00 and 05 09 01 03 00",
            setOf(listOf(0x05, 0x09, 0x01, 0x01, 0x00), listOf(0x05, 0x09, 0x01, 0x03, 0x00)),
            seen,
        )
        // 05 09 01 00 00 and 05 09 02 01 00 are in the simulator test and the evidence documents but
        // not in any fixture. If a fixture gains them this assertion fails: tighten it to four and
        // update docs/conformance-audit.md.
        assertFalse(listOf(0x05, 0x09, 0x02, 0x01, 0x00) in seen)
        assertFalse(listOf(0x05, 0x09, 0x01, 0x00, 0x00) in seen)
    }
}
