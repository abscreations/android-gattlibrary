package com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine

/**
 * A minimal JSON reader for the §5 fixture corpus.
 *
 * ### Why this exists rather than a dependency
 *
 * The project has no JSON library, and `org.json` returns stubbed defaults under
 * unit test, so a fixture file cannot be read with it. This reader lives in the
 * **test-only `:ble-fixtures` module** (never shipped; LIB-10c) and handles the full fixture shape - nested objects and
 * arrays, strings, numbers, booleans, null.
 *
 * `CaptureFixtures` carries a separate flat-record reader in main sources for a
 * different job. That is now two hand-written JSON readers in this repository.
 * **If a third is wanted, add a dependency instead** - the argument for hand-rolling
 * was that each was small and single-purpose, and it stops being true at three.
 */
object FixtureJson {

    fun parse(text: String): Any? = Reader(text).run {
        skipWhitespace()
        readValue().also {
            skipWhitespace()
            require(atEnd()) { "trailing content at offset $position" }
        }
    }

    @Suppress("UNCHECKED_CAST")
    fun obj(value: Any?): Map<String, Any?> =
        value as? Map<String, Any?> ?: error("expected an object, got ${value?.javaClass}")

    fun array(value: Any?): List<Any?> =
        value as? List<Any?> ?: error("expected an array, got ${value?.javaClass}")

    fun string(value: Any?): String =
        value as? String ?: error("expected a string, got ${value?.javaClass}")

    fun double(value: Any?): Double =
        (value as? Number)?.toDouble() ?: error("expected a number, got ${value?.javaClass}")

    fun int(value: Any?): Int =
        (value as? Number)?.toInt() ?: error("expected a number, got ${value?.javaClass}")

    private class Reader(private val s: String) {
        var position = 0
            private set

        fun atEnd(): Boolean = position >= s.length

        fun skipWhitespace() {
            while (position < s.length && s[position].isWhitespace()) position++
        }

        fun readValue(): Any? {
            skipWhitespace()
            require(position < s.length) { "unexpected end of input" }
            return when (val c = s[position]) {
                '{' -> readObject()
                '[' -> readArray()
                '"' -> readString()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> if (c == '-' || c.isDigit()) readNumber() else error("unexpected '$c' at $position")
            }
        }

        private fun readObject(): Map<String, Any?> {
            val out = LinkedHashMap<String, Any?>()
            position++ // {
            skipWhitespace()
            if (position < s.length && s[position] == '}') { position++; return out }
            while (true) {
                skipWhitespace()
                val key = readString()
                skipWhitespace()
                require(s[position] == ':') { "expected ':' at $position" }
                position++
                out[key] = readValue()
                skipWhitespace()
                when (s[position]) {
                    ',' -> position++
                    '}' -> { position++; return out }
                    else -> error("expected ',' or '}' at $position")
                }
            }
        }

        private fun readArray(): List<Any?> {
            val out = mutableListOf<Any?>()
            position++ // [
            skipWhitespace()
            if (position < s.length && s[position] == ']') { position++; return out }
            while (true) {
                out += readValue()
                skipWhitespace()
                when (s[position]) {
                    ',' -> position++
                    ']' -> { position++; return out }
                    else -> error("expected ',' or ']' at $position")
                }
            }
        }

        private fun readString(): String {
            require(s[position] == '"') { "expected a string at $position" }
            position++
            val sb = StringBuilder()
            while (s[position] != '"') {
                if (s[position] == '\\') {
                    position++
                    when (val e = s[position]) {
                        'n' -> sb.append('\n')
                        't' -> sb.append('\t')
                        'r' -> sb.append('\r')
                        'b' -> sb.append('\b')
                        'f' -> sb.append('\u000C')
                        '/' -> sb.append('/')
                        '"' -> sb.append('"')
                        '\\' -> sb.append('\\')
                        'u' -> {
                            sb.append(s.substring(position + 1, position + 5).toInt(16).toChar())
                            position += 4
                        }
                        else -> error("bad escape '\\$e' at $position")
                    }
                } else {
                    sb.append(s[position])
                }
                position++
            }
            position++
            return sb.toString()
        }

        private fun readNumber(): Number {
            val start = position
            if (s[position] == '-') position++
            while (position < s.length && (s[position].isDigit() || s[position] in ".eE+-")) position++
            val raw = s.substring(start, position)
            return if (raw.any { it in ".eE" }) raw.toDouble() else raw.toLong()
        }

        private fun literal(word: String, value: Any?): Any? {
            require(s.startsWith(word, position)) { "expected '$word' at $position" }
            position += word.length
            return value
        }
    }
}
