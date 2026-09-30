package com.gmail.absolutebollockscreations.gattlibrary.conformance

import com.gmail.absolutebollockscreations.gattlibrary.simulator.StrydFootpodEncoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * L25 golden check for the Stryd footpod persona against the sanitized capture corpus in
 * `gatt-fixtures/.../fixtures/hardware/stryd/` (qdomyos-zwift application debug logs from a physical Stryd; application
 * layer GATT notifications, not an over-the-air trace). Session B, the reference workout, is the
 * one checked in: 1 230 RSC and 1 230 CPS notifications. Each row's decoded fields are fed to the
 * encoder and the octets must come out identical to the captured hex. This replaces relying on
 * the four frames pasted into StrydFootpodEncoderTest, which are rows of this same corpus.
 */
class StrydGoldenTest {

    private fun rows(name: String): List<Map<String, String>> {
        val stream = requireNotNull(StrydGoldenTest::class.java.classLoader?.getResourceAsStream("fixtures/hardware/stryd/$name")) {
            "corpus file not on the test classpath: $name"
        }
        val lines = stream.use { it.readBytes().toString(Charsets.UTF_8) }.lines().filter { it.isNotBlank() }
        val header = lines.first().split(',')
        return lines.drop(1).map { line ->
            val cells = line.split(',')
            require(cells.size == header.size) { "malformed row in $name: $line" }
            header.zip(cells).toMap()
        }
    }

    private fun hex(text: String): ByteArray =
        text.trim().split(' ').map { it.toInt(16).toByte() }.toByteArray()

    @Test
    fun everyCapturedRscFrameIsReproducedOctetForOctet() {
        val rows = rows("session-B.rsc.csv")
        assertTrue("corpus row count", rows.size >= 1_000)
        rows.forEach { row ->
            val golden = hex(row.getValue("hex"))
            val encoded = StrydFootpodEncoder.rscMeasurement(
                speedMps = row.getValue("instantaneous_speed_mps").toDouble(),
                cadenceStridesPerMinute = row.getValue("instantaneous_cadence_spm").toInt(),
                strideLengthCm = row.getValue("instantaneous_stride_length_cm").toInt(),
                totalDistanceMetres = row.getValue("total_distance_m").toDouble(),
                running = row.getValue("running") == "True",
            )
            assertEquals("t=${row.getValue("t_ms")} ms", golden.toList(), encoded.toList())
        }
    }

    @Test
    fun everyCapturedCpsFrameIsReproducedOctetForOctet() {
        val rows = rows("session-B.cps.csv")
        assertTrue("corpus row count", rows.size >= 1_000)
        rows.forEach { row ->
            val golden = hex(row.getValue("hex"))
            val encoded = StrydFootpodEncoder.cyclingPowerMeasurement(
                instantaneousPowerWatts = row.getValue("instantaneous_power_w").toInt(),
                cumulativeStrides = row.getValue("cumulative_crank_revs").toInt(),
                lastStrideEventTime1024 = row.getValue("last_crank_event_time_1024s").toInt(),
            )
            assertEquals("t=${row.getValue("t_ms")} ms", golden.toList(), encoded.toList())
        }
    }

    @Test
    fun theFramesPastedIntoTheEncoderTestAreRowsOfTheCorpus() {
        val rsc = rows("session-B.rsc.csv").map { it.getValue("hex").lowercase() }.toSet()
        val cps = rows("session-B.cps.csv").map { it.getValue("hex").lowercase() }.toSet()
        // The literals in StrydFootpodEncoderTest (quoted in the comment above each assertion).
        assertTrue("running RSC frame", "07 e7 01 56 92 00 d6 06 00 00" in rsc)
        assertTrue("CPS frame 1", "20 00 9f 00 de 37 5b fc" in cps)
        assertTrue("CPS frame 2", "20 00 a1 00 df 37 25 ff" in cps)
        // The fourth literal, the walking RSC frame "03 e0 00 2f 6d 00 8c 0a 00 00", is from one of the
        // other three sessions, which are not checked in (see gatt-fixtures/.../fixtures/hardware/stryd/README.md).
        // It therefore cannot be tied here; \`assertFalse\` documents that so nobody assumes it is.
        assertFalse("walking literal is not in session B", "03 e0 00 2f 6d 00 8c 0a 00 00" in rsc)
    }
}
