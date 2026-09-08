package com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the Phase 8.2 language-neutral oracle without invoking future production Kayak First code.
 * Expected outcomes belong to the specification and fixture, never to the implementation under test.
 */
class KayakFirstAdversarialCorpusTest {
    private val resource = "fixtures/simulated/kayak_first_adversarial_v1.json"

    @Test
    fun `corpus is specification-authored and gives TMIW command precedence`() {
        val fixture = load()

        assertEquals(1, FixtureJson.int(fixture["schema_version"]))
        assertEquals(5, FixtureJson.int(fixture["corpus_revision"]))
        assertEquals("kayak-first-paddle", FixtureJson.string(fixture["family"]))
        assertEquals(
            "fitbridge-authored-language-neutral-adversarial-oracle",
            FixtureJson.string(fixture["corpus_kind"]),
        )
        assertEquals(
            "docs/kayak-first-wire-protocol-spec.md",
            FixtureJson.string(fixture["normative_source"]),
        )
        assertTrue(FixtureJson.string(fixture["source_precedence"]).contains("TMIW is normative"))
        assertTrue(FixtureJson.string(fixture["independence"]).contains("before a production"))
        val history = strings(fixture["revision_history"])
        assertEquals(4, history.size)
        assertTrue(history.any { it.contains("zero-field empty line") })
        assertTrue(history.any { it.contains("force_newtons") })
        assertTrue(history.any { it.contains("seventeen are consumed") })
        assertTrue(history.any { it.contains("retained on the wire and in captures") })

        val transport = FixtureJson.obj(fixture["transport_contract"])
        assertEquals(20, FixtureJson.int(transport["maximum_outbound_chunk_bytes"]))
        assertEquals(4_096, FixtureJson.int(transport["maximum_pending_line_bytes"]))
        assertEquals(1000, FixtureJson.int(transport["ack_deadline_ms"]))
        assertEquals(1000, FixtureJson.int(transport["retry_delay_ms"]))
        assertEquals(3, FixtureJson.int(transport["maximum_attempts"]))
        assertEquals(1_000, FixtureJson.int(transport["poll_interval_ms"]))
        assertEquals(3000, FixtureJson.int(transport["machine_freshness_ms"]))
    }

    @Test
    fun `every baseline byte split and required stream shape is pinned`() {
        val fixture = load()
        val baseline = FixtureJson.obj(fixture["baseline_measurement"])
        val frame = FixtureJson.string(baseline["frame_ascii"])
        val bytes = frame.toByteArray(Charsets.UTF_8)
        assertEquals(90, bytes.size)
        assertEquals(90, FixtureJson.int(baseline["frame_utf8_bytes"]))
        assertEquals(24, frame.removeSuffix("\r\n").split(';').size)

        val matrices = FixtureJson.array(fixture["fragmentation_matrices"]).map(FixtureJson::obj)
        assertEquals(
            setOf(
                "baseline-every-two-chunk-split",
                "baseline-byte-at-a-time",
                "baseline-twenty-byte-notifications",
                "baseline-cr-lf-split",
            ),
            matrices.mapTo(linkedSetOf()) { FixtureJson.string(it["id"]) },
        )
        val everySplit = matrices.single {
            FixtureJson.string(it["id"]) == "baseline-every-two-chunk-split"
        }
        val splitOffsets = ints(everySplit["split_offsets_bytes"])
        assertEquals((1 until bytes.size).toList(), splitOffsets)
        splitOffsets.forEach { split ->
            assertTrue(
                (bytes.copyOfRange(0, split) + bytes.copyOfRange(split, bytes.size)) contentEquals bytes,
            )
        }

        val twenty = matrices.single {
            FixtureJson.string(it["id"]) == "baseline-twenty-byte-notifications"
        }
        val chunkSizes = ints(twenty["chunk_sizes"])
        assertEquals(bytes.size, chunkSizes.sum())
        assertTrue(chunkSizes.all { it in 1..20 })

        val streamCases = FixtureJson.array(fixture["stream_cases"]).map(FixtureJson::obj)
        assertEquals(7, streamCases.size)
        val required = setOf(
            "two-complete-lines-coalesced",
            "complete-line-plus-partial-suffix",
            "partial-prefix-completed-before-coalesced-line-and-suffix",
            "cr-without-lf-remains-pending",
            "lf-without-cr-is-not-a-delimiter",
            "empty-crlf-line",
            "valid-line-after-unknown-line",
        )
        assertEquals(required, streamCases.mapTo(linkedSetOf()) { FixtureJson.string(it["id"]) })
        streamCases.forEach { case ->
            val actual = assembleAscii(strings(case["chunks_ascii"]))
            assertEquals(FixtureJson.string(case["id"]), strings(case["expect_frames_ascii"]), actual.frames)
            assertEquals(
                FixtureJson.string(case["id"]),
                FixtureJson.string(case["expect_pending_ascii"]),
                actual.pending,
            )
        }
    }

    @Test
    fun `binary encoding and bounded-buffer adversaries are complete`() {
        val fixture = load()
        val binary = FixtureJson.array(fixture["binary_and_encoding_cases"]).map(FixtureJson::obj)
        assertEquals(8, binary.size)
        assertEquals(
            setOf(
                "reset-success",
                "reject-binary-frame",
                "ignore-transport-noise",
                "reject-embedded-nul",
                "reject-invalid-utf8",
            ),
            binary.mapTo(linkedSetOf()) { FixtureJson.string(it["expect"]) },
        )
        binary.flatMap { strings(it["chunks_hex"]) }.forEach { hex ->
            assertTrue(hex.length % 2 == 0)
            assertTrue(hex.all { it.isDigit() || it.uppercaseChar() in 'A'..'F' })
        }

        val buffers = FixtureJson.array(fixture["buffer_limit_cases"]).map(FixtureJson::obj)
        assertEquals(
            setOf(
                "exactly-512-bytes-remains-bounded",
                "delimiter-after-exactly-512-bytes",
                "byte-513-overflows-and-clears",
                "valid-frame-after-overflow",
            ),
            buffers.mapTo(linkedSetOf()) { FixtureJson.string(it["id"]) },
        )
        val exact = buffers.single { FixtureJson.string(it["id"]) == "exactly-512-bytes-remains-bounded" }
        val overflow = buffers.single { FixtureJson.string(it["id"]) == "byte-513-overflows-and-clears" }
        assertEquals(512, generatedByteCount(exact))
        assertEquals(513, generatedByteCount(overflow))
        assertEquals(
            listOf("line-too-long"),
            strings(FixtureJson.obj(overflow["expect"])["rejections"]),
        )
    }

    @Test
    fun `measurement mutations cover field shape grammar overflow bounds and continuity`() {
        val fixture = load()
        val contract = FixtureJson.obj(fixture["measurement_contract"])
        val consumed = FixtureJson.obj(contract["consumed_fields"])
        val consumedIndexes = consumed.values.mapTo(linkedSetOf(), FixtureJson::int)
        // Seventeen consumed fields, not the five this corpus claimed until revision 4. The
        // decoder reads everything the KayakFit convergence resolved; only 2-6 and 8 stay unread.
        assertEquals(
            setOf(1, 7, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23),
            consumedIndexes,
        )
        assertEquals(setOf(9, 10, 11, 20, 21), ints(contract["decimal_field_indexes"]).toSet())
        assertEquals(
            setOf(1, 7, 12, 13, 14, 15, 16, 17, 18, 19, 22),
            ints(contract["integer_field_indexes"]).toSet(),
        )
        // Field 23 is consumed but can never reject a frame, so it belongs to neither grammar set.
        assertEquals(setOf(23), ints(contract["lenient_field_indexes"]).toSet())
        assertEquals(setOf(2, 3, 4, 5, 6, 8), ints(contract["ignored_field_indexes_within_first_24"]).toSet())
        val semantics = FixtureJson.obj(contract["field_semantics"])
        (0..23).forEach { index ->
            assertTrue("field_semantics missing $index", semantics.containsKey(index.toString()))
        }

        val truncation = FixtureJson.obj(fixture["truncation_matrix"])
        assertEquals((0..23).toList(), ints(truncation["prefix_field_counts"]))
        assertEquals("reject-empty-line", FixtureJson.string(truncation["expect_count_0"]))
        assertEquals(
            "reject-too-few-fields",
            FixtureJson.string(truncation["expect_counts_1_through_23"]),
        )

        val mutations = FixtureJson.array(fixture["measurement_mutation_cases"]).map(FixtureJson::obj)
        assertEquals(92, mutations.size)
        assertEquals(mutations.size, mutations.map { FixtureJson.string(it["id"]) }.toSet().size)
        val baselineFields = FixtureJson.string(
            FixtureJson.obj(fixture["baseline_measurement"])["frame_ascii"],
        ).removeSuffix("\r\n").split(';')
        mutations.forEach { case ->
            val fields = baselineFields.toMutableList()
            case["mutations"]?.let { rawMutations ->
                FixtureJson.array(rawMutations).map(FixtureJson::obj).forEach { mutation ->
                    fields[FixtureJson.int(mutation["index"])] = FixtureJson.string(mutation["value"])
                }
            }
            case["append_fields"]?.let { fields += strings(it) }
            assertTrue(FixtureJson.string(case["id"]), fields.size >= 24)
        }

        fun indexesFor(prefix: String): Set<Int> = mutations
            .filter { FixtureJson.string(it["id"]).startsWith(prefix) }
            .flatMap { FixtureJson.array(it["mutations"]).map(FixtureJson::obj) }
            .mapTo(linkedSetOf()) { FixtureJson.int(it["index"]) }

        assertEquals(consumedIndexes, indexesFor("empty-"))
        assertEquals(consumedIndexes, indexesFor("negative-"))
        val results = mutations.mapTo(linkedSetOf()) { FixtureJson.string(it["expect"]) }
        assertTrue("reject-numeric-grammar" in results)
        assertTrue("reject-numeric-overflow" in results)
        assertTrue("reject-plausibility-speed" in results)
        assertTrue("reject-plausibility-cadence" in results)
        assertTrue("accept-measurement-ignore-extra-fields" in results)

        val continuity = FixtureJson.array(fixture["continuity_cases"]).map(FixtureJson::obj)
        assertEquals(5, continuity.size)
        assertEquals(
            setOf("accept", "reject-counter-decrease", "accept-new-baseline"),
            continuity.mapTo(linkedSetOf()) { FixtureJson.string(it["expect"]) },
        )
        assertTrue(continuity.any { it["boundary"] == "accepted-local-start" })
        assertTrue(continuity.any { it["boundary"] == "new-link-generation" })
    }

    @Test
    fun `normative command chunks follow TMIW while captured dialects stay simulator-only`() {
        val commands = FixtureJson.array(load()["outbound_command_cases"]).map(FixtureJson::obj)
        assertEquals(12, commands.size)
        val normative = commands.filter { FixtureJson.string(it["authority"]) == "central-normative" }
        assertEquals(
            setOf(
                "tmiw-complete-legacy-handshake",
                "tmiw-reset",
                "tmiw-display-default",
                "tmiw-poll",
                "tmiw-start-short",
                "tmiw-stop-short",
            ),
            normative.mapTo(linkedSetOf()) { FixtureJson.string(it["id"]) },
        )
        normative.forEach { case ->
            val chunks = strings(case["expected_chunks_ascii"])
            assertEquals(
                FixtureJson.string(case["id"]),
                FixtureJson.string(case["command_ascii"]),
                chunks.joinToString(separator = ""),
            )
            assertTrue(chunks.all { it.toByteArray(Charsets.UTF_8).size <= 20 })
        }
        val handshake = commands.single {
            FixtureJson.string(it["id"]) == "tmiw-complete-legacy-handshake"
        }
        assertEquals("2;1700000000;120;80;1\r\n", FixtureJson.string(handshake["command_ascii"]))
        assertEquals(
            listOf("2;1700000000;120;80;", "1\r\n"),
            strings(handshake["expected_chunks_ascii"]),
        )

        val simulatorOnly = commands.filter {
            FixtureJson.string(it["authority"]) == "simulator-compatibility-only"
        }
        assertEquals(
            setOf("captured-handshake-truncated", "captured-start-extended", "captured-stop-extended"),
            simulatorOnly.mapTo(linkedSetOf()) { FixtureJson.string(it["id"]) },
        )
        assertEquals(
            setOf("unsupported-new-handshake", "unsupported-configuration", "unsupported-parameters"),
            commands.filter { FixtureJson.string(it["authority"]) == "known-token-out-of-profile" }
                .mapTo(linkedSetOf()) { FixtureJson.string(it["id"]) },
        )
    }

    @Test
    fun `transaction timelines pin wrong late missing cancelled and reconnect behavior`() {
        val fixture = load()
        val responses = FixtureJson.array(fixture["response_cases"]).map(FixtureJson::obj)
        assertEquals(9, responses.size)
        assertEquals(
            setOf("reset", "handshake", "display", "poll", "start", "stop", "unknown"),
            responses.mapTo(linkedSetOf()) {
                FixtureJson.string(it["id"]).substringBefore('-')
            },
        )

        val timelines = FixtureJson.array(fixture["setup_timeline_cases"]).map(FixtureJson::obj)
        assertEquals(10, timelines.size)
        val ids = timelines.mapTo(linkedSetOf()) { FixtureJson.string(it["id"]) }
        assertEquals(
            setOf(
                "wrong-response-does-not-complete-reset",
                "late-reset-response-between-attempts-is-ignored",
                "reset-exhaustion-fails-closed",
                "handshake-exhaustion-proceeds-unconfirmed",
                "out-of-order-display-response-does-not-complete-handshake",
                "display-negative-response-fails-attempt-and-retries",
                "display-exhaustion-fails-closed",
                "disconnect-cancels-attempt-and-retry",
                "reconnect-owns-fresh-state",
                "unconfirmed-handshake-plus-display-ack-reaches-ready-after-settle",
            ),
            ids,
        )
        timelines.forEach { timeline ->
            val events = FixtureJson.array(timeline["events"]).map(FixtureJson::obj)
            val times = events.map { FixtureJson.int(it["t_ms"]) }
            assertEquals(FixtureJson.string(timeline["id"]), times.sorted(), times)
            events.mapNotNull { it["attempt"] }.forEach {
                assertTrue(FixtureJson.int(it) in 1..3)
            }
        }

        val resetExhaustion = timelines.single {
            FixtureJson.string(it["id"]) == "reset-exhaustion-fails-closed"
        }
        val resetEvents = FixtureJson.array(resetExhaustion["events"]).map(FixtureJson::obj)
        assertEquals(
            listOf(0, 2000, 4000),
            resetEvents.filter { it["event"] == "send" }.map { FixtureJson.int(it["t_ms"]) },
        )
        assertEquals(
            listOf(1000, 3000, 5000),
            resetEvents.filter { it["event"] == "deadline" }.map { FixtureJson.int(it["t_ms"]) },
        )
        assertTrue(timelines.any { FixtureJson.string(it["expect_terminal"]).contains("no-pending-work") })
        assertTrue(timelines.any { FixtureJson.string(it["expect_terminal"]).contains("generation-2") })
        assertTrue(timelines.any { FixtureJson.string(it["expect_terminal"]).contains("handshake-unconfirmed") })
        assertTrue(timelines.any { FixtureJson.string(it["expect_terminal"]).contains("no-capabilities") })

        val runtime = FixtureJson.array(fixture["runtime_timeline_cases"]).map(FixtureJson::obj)
        assertEquals(
            setOf(
                "start-silence-never-auto-retries",
                "stop-wrong-response-never-auto-retries",
                "missing-polls-advance-schedule-without-retry-then-go-stale",
            ),
            runtime.mapTo(linkedSetOf()) { FixtureJson.string(it["id"]) },
        )
        runtime.forEach { timeline ->
            val events = FixtureJson.array(timeline["events"]).map(FixtureJson::obj)
            val times = events.map { FixtureJson.int(it["t_ms"]) }
            assertEquals(FixtureJson.string(timeline["id"]), times.sorted(), times)
        }
        val pollTimeline = runtime.single {
            FixtureJson.string(it["id"]) ==
                "missing-polls-advance-schedule-without-retry-then-go-stale"
        }
        val pollEvents = FixtureJson.array(pollTimeline["events"]).map(FixtureJson::obj)
        assertEquals(
            listOf(0, 500, 1000, 1500, 2000, 2500),
            pollEvents.filter { it["event"] == "send" }.map { FixtureJson.int(it["t_ms"]) },
        )
        assertTrue(runtime.take(2).all { timeline ->
            FixtureJson.array(timeline["events"]).map(FixtureJson::obj)
                .count { it["event"] == "send" } == 1
        })

        val generationStreams = FixtureJson.array(fixture["generation_stream_cases"])
            .map(FixtureJson::obj)
        assertEquals(
            setOf("disconnect-discards-partial-line", "stale-notification-cannot-enter-new-generation"),
            generationStreams.mapTo(linkedSetOf()) { FixtureJson.string(it["id"]) },
        )
        assertTrue(generationStreams.any {
            FixtureJson.string(it["expect"]) == "generation-2-has-fresh-empty-assembler"
        })
        assertTrue(generationStreams.any {
            FixtureJson.string(it["expect"]) == "only-generation-2-frame-emitted"
        })
    }

    @Test
    fun `privacy and coverage manifests stay explicit`() {
        val text = loadText()
        val fixture = FixtureJson.obj(FixtureJson.parse(text))
        val privacy = FixtureJson.obj(fixture["privacy_expectations"])

        assertEquals(8, strings(privacy["forbidden_data_classes"]).size)
        assertTrue(
            strings(privacy["allowed_protocol_constants"])
                .contains("neutral weight 80 from TMIW, as the unconfigured fallback only"),
        )
        assertTrue(FixtureJson.string(privacy["ordinary_log_policy"]).contains("BLECAP"))

        // D102 rescinded the athlete-weight restriction: weight and height are retained on the
        // wire and in captures because vendor power and energy models take mass as an input. The
        // identifying values are the ones that must stay forbidden, so assert both directions —
        // a future edit that quietly re-adds weight, or quietly drops a name, fails here.
        val forbidden = strings(privacy["forbidden_data_classes"])
        assertFalse(
            "athlete weight is retained by D102 and must not be listed as forbidden",
            forbidden.any { it.contains("weight", ignoreCase = true) },
        )
        assertTrue(
            "identifying profile values must stay forbidden",
            forbidden.any { it.contains("identifying profile value", ignoreCase = true) },
        )
        assertEquals(2, strings(privacy["retained_by_decision"]).size)
        assertEquals(20, strings(fixture["coverage_manifest"]).size)
        assertFalse("fixture must not contain a Bluetooth address", MAC_ADDRESS.containsMatchIn(text))
        assertFalse("fixture must not contain known private keys", PRIVATE_KEY.containsMatchIn(text))
        assertFalse("fixture must not name raw capture paths", text.contains("CaptureLogs", ignoreCase = true))
    }

    private fun generatedByteCount(case: Map<String, Any?>): Int =
        FixtureJson.array(case["generated_chunks"]).map(FixtureJson::obj).sumOf { chunk ->
            chunk["count"]?.let(FixtureJson::int)
                ?: (FixtureJson.string(chunk["hex"]).length / 2)
        }

    private fun assembleAscii(chunks: List<String>): AssembledAscii {
        var pending = ""
        val frames = mutableListOf<String>()
        chunks.forEach { chunk ->
            pending += chunk
            while (true) {
                val delimiter = pending.indexOf("\r\n")
                if (delimiter < 0) break
                frames += pending.substring(0, delimiter)
                pending = pending.substring(delimiter + 2)
            }
        }
        return AssembledAscii(frames, pending)
    }

    private fun loadText(): String = javaClass.classLoader?.getResourceAsStream(resource)
        ?.bufferedReader()
        ?.use { it.readText() }
        ?: error("missing $resource")

    private fun load(): Map<String, Any?> = FixtureJson.obj(FixtureJson.parse(loadText()))

    private fun strings(value: Any?): List<String> = FixtureJson.array(value).map(FixtureJson::string)

    private fun ints(value: Any?): List<Int> = FixtureJson.array(value).map(FixtureJson::int)

    private data class AssembledAscii(val frames: List<String>, val pending: String)

    private companion object {
        val MAC_ADDRESS = Regex("(?i)(?:[0-9a-f]{2}:){5}[0-9a-f]{2}")
        val PRIVATE_KEY = Regex("(?i)(fitshow_user_id|skuDetailsToken|offerIdToken|productDetailsToken)")
    }
}
