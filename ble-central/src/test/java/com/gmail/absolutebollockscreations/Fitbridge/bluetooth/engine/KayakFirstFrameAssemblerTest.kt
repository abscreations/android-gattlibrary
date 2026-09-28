package com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KayakFirstFrameAssemblerTest {
    private val resource = "fixtures/simulated/kayak_first_adversarial_v1.json"
    private val hardwareResource = "fixtures/hardware/kayak_first_qz_202604.json"

    @Test
    fun `every two-chunk split emits the same complete measurement`() {
        val fixture = load()
        val baseline = FixtureJson.obj(fixture["baseline_measurement"])
        val frame = FixtureJson.string(baseline["frame_ascii"])
        val bytes = frame.toByteArray(Charsets.UTF_8)
        val matrix = FixtureJson.array(fixture["fragmentation_matrices"])
            .map(FixtureJson::obj)
            .single { FixtureJson.string(it["id"]) == "baseline-every-two-chunk-split" }

        ints(matrix["split_offsets_bytes"]).forEach { split ->
            val assembler = KayakFirstFrameAssembler()
            assertTrue(assembler.accept(bytes.copyOfRange(0, split)).isEmpty())
            assertEquals(
                listOf(KayakFirstFrameEvent.TextLine(frame.removeSuffix("\r\n"))),
                assembler.accept(bytes.copyOfRange(split, bytes.size)),
            )
            assertEquals(0, assembler.pendingByteCount())
        }
    }

    @Test
    fun `byte-wise and twenty-byte fragmentation emit once`() {
        val fixture = load()
        val frame = FixtureJson.string(
            FixtureJson.obj(fixture["baseline_measurement"])["frame_ascii"],
        )
        val bytes = frame.toByteArray(Charsets.UTF_8)

        val byteWise = KayakFirstFrameAssembler()
        val byteEvents = bytes.flatMap { byteWise.accept(byteArrayOf(it)) }
        assertEquals(listOf(KayakFirstFrameEvent.TextLine(frame.removeSuffix("\r\n"))), byteEvents)

        val twentyByte = KayakFirstFrameAssembler()
        val chunkEvents = bytes.asList().chunked(20).flatMap { twentyByte.accept(it.toByteArray()) }
        assertEquals(listOf(KayakFirstFrameEvent.TextLine(frame.removeSuffix("\r\n"))), chunkEvents)
    }

    @Test
    fun `coalesced lines and partial suffixes match the oracle`() {
        val cases = FixtureJson.array(load()["stream_cases"]).map(FixtureJson::obj)
        cases.forEach { case ->
            val assembler = KayakFirstFrameAssembler()
            val events = strings(case["chunks_ascii"]).flatMap {
                assembler.accept(it.toByteArray(Charsets.UTF_8))
            }
            val lines = events.filterIsInstance<KayakFirstFrameEvent.TextLine>().map { it.value }
            assertEquals(FixtureJson.string(case["id"]), strings(case["expect_frames_ascii"]), lines)
            assertEquals(
                FixtureJson.string(case["id"]),
                FixtureJson.string(case["expect_pending_ascii"]).toByteArray(Charsets.UTF_8).size,
                assembler.pendingByteCount(),
            )
        }
    }

    @Test
    fun `binary reset noise NUL and invalid UTF8 follow typed outcomes`() {
        val cases = FixtureJson.array(load()["binary_and_encoding_cases"]).map(FixtureJson::obj)
        cases.forEach { case ->
            val assembler = KayakFirstFrameAssembler()
            val events = strings(case["chunks_hex"]).flatMap { assembler.accept(hex(it)) }
            when (FixtureJson.string(case["expect"])) {
                "reset-success" -> assertEquals(
                    listOf(KayakFirstFrameEvent.BinaryResetAcknowledgement),
                    events,
                )
                "ignore-transport-noise" -> assertTrue(events.isEmpty())
                "reject-binary-frame" -> assertRejected(
                    KayakFirstFrameRejection.INVALID_BINARY_FRAME,
                    events,
                )
                "reject-embedded-nul" -> assertRejected(
                    KayakFirstFrameRejection.EMBEDDED_NUL,
                    events,
                )
                "reject-invalid-utf8" -> assertRejected(
                    KayakFirstFrameRejection.INVALID_UTF8,
                    events,
                )
                else -> error("unknown fixture result ${case["expect"]}")
            }
        }
    }

    @Test
    fun `line limit accepts its cap rejects the next byte and recovers`() {
        val cases = FixtureJson.array(load()["buffer_limit_cases"])
            .map(FixtureJson::obj)
            .associateBy { FixtureJson.string(it["id"]) }

        val exact = KayakFirstFrameAssembler(CORPUS_CAP_BYTES)
        assertTrue(feedGenerated(exact, requireNotNull(cases["exactly-512-bytes-remains-bounded"])).isEmpty())
        assertEquals(512, exact.pendingByteCount())

        val delimited = KayakFirstFrameAssembler(CORPUS_CAP_BYTES)
        val delimitedEvents = feedGenerated(
            delimited,
            requireNotNull(cases["delimiter-after-exactly-512-bytes"]),
        )
        assertEquals(1, delimitedEvents.filterIsInstance<KayakFirstFrameEvent.TextLine>().size)
        assertEquals(512, (delimitedEvents.single() as KayakFirstFrameEvent.TextLine).value.length)

        val overflow = KayakFirstFrameAssembler(CORPUS_CAP_BYTES)
        assertRejected(
            KayakFirstFrameRejection.FRAME_TOO_LONG,
            feedGenerated(overflow, requireNotNull(cases["byte-513-overflows-and-clears"])),
        )
        assertEquals(0, overflow.pendingByteCount())

        val recovered = KayakFirstFrameAssembler(CORPUS_CAP_BYTES)
        assertEquals(
            listOf(
                KayakFirstFrameEvent.Rejected(KayakFirstFrameRejection.FRAME_TOO_LONG),
                KayakFirstFrameEvent.TextLine("5;1"),
            ),
            feedGenerated(recovered, requireNotNull(cases["valid-frame-after-overflow"])),
        )

        val hardware = load(hardwareResource)
        val observedLengths = FixtureJson.array(hardware["measurement_cases"])
            .map(FixtureJson::obj)
            .map { FixtureJson.string(it["frame_ascii"]).toByteArray(Charsets.UTF_8).size }
        assertTrue(observedLengths.isNotEmpty())
        assertTrue(requireNotNull(observedLengths.maxOrNull()) < CORPUS_CAP_BYTES)
    }

    @Test
    fun `production cap is the converged 4096 bytes and admits a multi-frame burst`() {
        val assembler = KayakFirstFrameAssembler()
        // A Bull console can deliver several complete frames plus a partial one in one burst; the
        // former 512-byte cap discarded the in-flight line as overlong.
        val frame = "6;900000010449;0;0;0;0.00;0;1;0;11;0.94;2.75;98;76;229;72;573;180;1147;360;96;82;10;1\r\n"
        val burst = frame.repeat(8) + "6;900000020101;0;0;0;0.00;0;1;0;38"
        val events = assembler.accept(burst.toByteArray())
        assertEquals(8, events.filterIsInstance<KayakFirstFrameEvent.TextLine>().size)
        assertTrue(events.none { it is KayakFirstFrameEvent.Rejected })
        assertTrue(assembler.pendingByteCount() > 0)
    }

    @Test
    fun `reset clears partial bytes and stale fragments require their own assembler`() {
        val assembler = KayakFirstFrameAssembler()
        assertTrue(assembler.accept("6;stale-partial".toByteArray()).isEmpty())
        assertTrue(assembler.pendingByteCount() > 0)

        assembler.reset()

        assertEquals(0, assembler.pendingByteCount())
        assertEquals(
            listOf(KayakFirstFrameEvent.TextLine("5;1")),
            assembler.accept("5;1\r\n".toByteArray()),
        )
    }

    private fun assertRejected(
        expected: KayakFirstFrameRejection,
        events: List<KayakFirstFrameEvent>,
    ) {
        assertEquals(listOf(KayakFirstFrameEvent.Rejected(expected)), events)
    }

    private fun feedGenerated(
        assembler: KayakFirstFrameAssembler,
        case: Map<String, Any?>,
    ): List<KayakFirstFrameEvent> = FixtureJson.array(case["generated_chunks"])
        .map(FixtureJson::obj)
        .flatMap { chunk ->
            val bytes = chunk["repeat_hex"]?.let { raw ->
                val repeated = hex(FixtureJson.string(raw))
                require(repeated.size == 1)
                ByteArray(FixtureJson.int(chunk["count"])) { repeated.single() }
            } ?: hex(FixtureJson.string(chunk["hex"]))
            assembler.accept(bytes)
        }

    private fun hex(value: String): ByteArray {
        require(value.length % 2 == 0)
        return ByteArray(value.length / 2) { index ->
            value.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
    }

    private fun load(resourceName: String = resource): Map<String, Any?> = javaClass.classLoader
        ?.getResourceAsStream(resourceName)
        ?.bufferedReader()
        ?.use { FixtureJson.obj(FixtureJson.parse(it.readText())) }
        ?: error("missing $resourceName")

    private fun strings(value: Any?): List<String> = FixtureJson.array(value).map(FixtureJson::string)

    private fun ints(value: Any?): List<Int> = FixtureJson.array(value).map(FixtureJson::int)

    private companion object {
        /**
         * Cap the pinned buffer-limit corpus cases were authored against.
         *
         * Those cases pin cap *behaviour* — accept exactly the cap, reject the next byte, clear and
         * recover — which is independent of the cap's value. The production default moved to 4,096
         * bytes with the KayakFit convergence and is covered separately.
         */
        const val CORPUS_CAP_BYTES = 512
    }
}
