package com.gmail.absolutebollockscreations.gattlibrary.central.capture

import com.gmail.absolutebollockscreations.gattlibrary.core.capture.CaptureJson

/**
 * Converts a capture NDJSON file into a §5 fixture.
 *
 * Plan §5.3 defines the conversion exactly: *"An NDJSON capture converts to a
 * fixture by filtering `notify` events for a characteristic and pairing each
 * `hex` payload with the `sample` events it produced — which is why §0.9.4
 * records both."* This is that, executable.
 *
 * ### Why it lives in main rather than in a test
 *
 * A user-submitted capture is simultaneously the bug report, the fixture, and
 * the only hardware evidence most families will ever have. Turning one into a
 * fixture must therefore be a real, exercised code path, not a script someone
 * reconstructs from the plan each time.
 *
 * ### Why there is a JSON reader in here
 *
 * The project has no JSON dependency, and `org.json` returns stubs under unit
 * test. [CaptureJson] hand-writes the records; this hand-reads them. The two are
 * deliberately symmetric and deliberately small: the capture format is flat by
 * design, and a record that needs more than this to parse is a record that has
 * outgrown the format.
 */
internal object CaptureFixtures {

    /** One packet and everything the parser produced from it. */
    data class Case(
        val name: String,
        val packet: List<Int>,
        val expect: Map<String, Double?>,
    )

    data class Fixture(
        val family: String,
        val source: String,
        val captured: String,
        val characteristic: String,
        val cases: List<Case>,
    )

    /**
     * Build a fixture from [lines], keeping only notifications on
     * [characteristic].
     *
     * Samples are attributed to the most recent matching notification. A sample
     * arriving before any notification is discarded rather than guessed at: it
     * cannot be attributed to a packet, and a fixture case whose expectations
     * came from an unknown input is worse than no case at all.
     */
    fun build(
        lines: Sequence<String>,
        characteristic: String,
        family: String,
        source: String,
        captured: String,
    ): Fixture {
        val cases = mutableListOf<Case>()
        var current: MutableMap<String, Double?>? = null

        for (line in lines) {
            if (line.isBlank()) continue
            val record = FlatJson.parse(line) ?: continue
            when (record["ev"] as? String) {
                "notify" -> {
                    val uuid = record["char"] as? String ?: continue
                    if (!uuid.equals(characteristic, ignoreCase = true)) {
                        // A notification on another characteristic ends the
                        // current case: samples after it are not ours.
                        current = null
                        continue
                    }
                    val hex = record["hex"] as? String ?: continue
                    val expect = mutableMapOf<String, Double?>()
                    cases += Case(
                        name = "seq-${(record["seq"] as? Double)?.toInt() ?: cases.size}",
                        packet = hexToBytes(hex),
                        expect = expect,
                    )
                    current = expect
                }

                "sample" -> {
                    val slot = current ?: continue
                    val metric = record["metric"] as? String ?: continue
                    slot[metric] = record["value"] as? Double
                }
            }
        }
        return Fixture(family, source, captured, characteristic, cases)
    }

    /** Render in the §5.2 layout, so a generated fixture is diffable against a transcribed one. */
    fun Fixture.toJson(): String = buildString {
        append("{\n")
        append("  \"family\": ").append(str(family)).append(",\n")
        append("  \"source\": ").append(str(source)).append(",\n")
        append("  \"captured\": ").append(str(captured)).append(",\n")
        append("  \"characteristic\": ").append(str(characteristic)).append(",\n")
        append("  \"cases\": [\n")
        cases.forEachIndexed { i, case ->
            append("    {\n")
            append("      \"name\": ").append(str(case.name)).append(",\n")
            append("      \"packet\": [").append(case.packet.joinToString(", ")).append("],\n")
            append("      \"expect\": {")
            case.expect.entries.forEachIndexed { j, (k, v) ->
                if (j > 0) append(",")
                append(" ").append(str(k)).append(": ").append(v?.let { num(it) } ?: "null")
            }
            append(if (case.expect.isEmpty()) "}\n" else " }\n")
            append(if (i == cases.lastIndex) "    }\n" else "    },\n")
        }
        append("  ]\n}")
    }

    private fun num(v: Double): String =
        if (v == v.toLong().toDouble()) v.toLong().toString() else v.toString()

    private fun str(s: String): String = buildString {
        append('"')
        s.forEach {
            when (it) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                else -> append(it)
            }
        }
        append('"')
    }

    internal fun hexToBytes(hex: String): List<Int> =
        hex.chunked(2).mapNotNull { it.toIntOrNull(16) }

    /**
     * A reader for one flat JSON object: string, number, boolean and null
     * values only. Nested objects and arrays are skipped rather than parsed -
     * `gatt_inventory` is the only record that nests, and it is not a fixture
     * source.
     */
    internal object FlatJson {
        fun parse(line: String): Map<String, Any?>? {
            val s = line.trim()
            if (!s.startsWith("{")) return null
            val out = mutableMapOf<String, Any?>()
            var i = 1
            while (i < s.length) {
                while (i < s.length && s[i].isWhitespace()) i++
                if (i < s.length && (s[i] == ',' )) { i++; continue }
                if (i >= s.length || s[i] == '}') break
                if (s[i] != '"') return out
                val key = StringBuilder()
                i = readString(s, i, key)
                while (i < s.length && (s[i].isWhitespace() || s[i] == ':')) i++
                when {
                    i >= s.length -> return out
                    s[i] == '"' -> {
                        val v = StringBuilder()
                        i = readString(s, i, v)
                        out[key.toString()] = v.toString()
                    }
                    s[i] == '{' || s[i] == '[' -> i = skipNested(s, i)
                    else -> {
                        val start = i
                        while (i < s.length && s[i] != ',' && s[i] != '}') i++
                        val raw = s.substring(start, i).trim()
                        out[key.toString()] = when (raw) {
                            "true" -> true
                            "false" -> false
                            "null" -> null
                            else -> raw.toDoubleOrNull()
                        }
                    }
                }
            }
            return out
        }

        /** @param at index of the opening quote; returns the index just past the closing quote. */
        private fun readString(s: String, at: Int, into: StringBuilder): Int {
            var i = at + 1
            while (i < s.length) {
                when (val c = s[i]) {
                    '\\' -> {
                        i++
                        if (i >= s.length) break
                        when (val e = s[i]) {
                            'n' -> into.append('\n')
                            't' -> into.append('\t')
                            'r' -> into.append('\r')
                            'b' -> into.append('\b')
                            'f' -> into.append('\u000C')
                            'u' -> {
                                val hex = s.substring(i + 1, minOf(i + 5, s.length))
                                hex.toIntOrNull(16)?.let { into.append(it.toChar()) }
                                i += 4
                            }
                            else -> into.append(e)
                        }
                        i++
                    }
                    '"' -> return i + 1
                    else -> { into.append(c); i++ }
                }
            }
            return i
        }

        private fun skipNested(s: String, at: Int): Int {
            var depth = 0
            var i = at
            var inString = false
            while (i < s.length) {
                val c = s[i]
                when {
                    inString && c == '\\' -> i++
                    c == '"' -> inString = !inString
                    !inString && (c == '{' || c == '[') -> depth++
                    !inString && (c == '}' || c == ']') -> {
                        depth--
                        if (depth == 0) return i + 1
                    }
                }
                i++
            }
            return i
        }
    }
}
