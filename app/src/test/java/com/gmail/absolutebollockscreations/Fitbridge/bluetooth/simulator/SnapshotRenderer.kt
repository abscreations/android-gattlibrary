package com.gmail.absolutebollockscreations.Fitbridge.bluetooth.simulator

import java.lang.reflect.Modifier
import java.util.IdentityHashMap
import java.util.UUID

/**
 * Deterministic text rendering for snapshot tests (LIB-5a).
 *
 * Data classes and plain objects are printed field by field, sorted by field name, so the output
 * does not depend on `toString()` implementations (a `ByteArray` field makes a data class's own
 * `toString()` print an identity hash). Sets and maps are sorted by their rendered form because
 * enum hash codes are identity-based and differ between JVM runs. Android framework classes are
 * inert stubs in unit tests, so they are named but never inspected.
 */
internal object SnapshotRenderer {

    private const val MAX_DEPTH = 16
    private const val APP_PACKAGE_PREFIX = "com.gmail.absolutebollockscreations.Fitbridge."

    fun render(value: Any?): String = render(value, 0, 0, IdentityHashMap())

    private fun render(value: Any?, indent: Int, depth: Int, seen: IdentityHashMap<Any, Boolean>): String {
        if (value == null) return "null"
        return when (value) {
            is String -> quote(value)
            is Char -> quote(value.toString())
            is Number, is Boolean -> value.toString()
            is UUID -> value.toString()
            is Enum<*> -> renderEnum(value, indent, depth, seen)
            is ByteArray -> hex(value)
            is IntArray -> value.joinToString(prefix = "IntArray[", postfix = "]")
            is LongArray -> value.joinToString(prefix = "LongArray[", postfix = "]")
            is ShortArray -> value.joinToString(prefix = "ShortArray[", postfix = "]")
            is DoubleArray -> value.joinToString(prefix = "DoubleArray[", postfix = "]")
            is FloatArray -> value.joinToString(prefix = "FloatArray[", postfix = "]")
            is BooleanArray -> value.joinToString(prefix = "BooleanArray[", postfix = "]")
            is CharArray -> value.joinToString(prefix = "CharArray[", postfix = "]")
            is Regex -> "Regex(${quote(value.pattern)})"
            is Function<*> -> "<function>"
            is Lazy<*> -> render(value.value, indent, depth + 1, seen)
            is Pair<*, *> -> block(
                "Pair(",
                ")",
                listOf(
                    "first = ${render(value.first, indent + 1, depth + 1, seen)}",
                    "second = ${render(value.second, indent + 1, depth + 1, seen)}",
                ),
                indent,
            )
            is IntRange -> "${value.first}..${value.last}"
            is LongRange -> "${value.first}..${value.last}"
            is Array<*> -> block("[", "]", value.map { render(it, indent + 1, depth + 1, seen) }, indent)
            is Set<*> -> block("[", "]", value.map { render(it, indent + 1, depth + 1, seen) }.sorted(), indent)
            is Collection<*> -> block("[", "]", value.map { render(it, indent + 1, depth + 1, seen) }, indent)
            is Map<*, *> -> block(
                "{",
                "}",
                value.entries
                    .map { (k, v) -> render(k, indent + 1, depth + 1, seen) to v }
                    .sortedBy { it.first }
                    .map { (k, v) -> "$k: ${render(v, indent + 1, depth + 1, seen)}" },
                indent,
            )
            else -> renderObject(value, indent, depth, seen)
        }
    }

    /**
     * Enum constants are printed with their properties: wire-relevant identity (a serial number,
     * an advertised name) often lives there rather than in the object that references the enum.
     */
    private fun renderEnum(value: Enum<*>, indent: Int, depth: Int, seen: IdentityHashMap<Any, Boolean>): String {
        val label = "${enumClassName(value)}.${value.name}"
        if (!value.javaClass.name.startsWith(APP_PACKAGE_PREFIX)) return label
        return renderFields(value, label, indent, depth, seen)
    }

    private fun renderObject(value: Any, indent: Int, depth: Int, seen: IdentityHashMap<Any, Boolean>): String {
        val cls = value.javaClass
        val name = cls.name
        if (name.startsWith("android.")) return "<android ${cls.simpleName}>"
        if (!name.startsWith(APP_PACKAGE_PREFIX)) {
            // JDK or Kotlin value types: use toString() only when it is really overridden,
            // otherwise the output would contain an identity hash.
            val overridden = runCatching { cls.getMethod("toString").declaringClass != Any::class.java }
                .getOrDefault(false)
            return if (overridden) quote(value.toString()) else "<${cls.simpleName}>"
        }
        return renderFields(value, name.removePrefix(APP_PACKAGE_PREFIX), indent, depth, seen)
    }

    private fun renderFields(
        value: Any,
        display: String,
        indent: Int,
        depth: Int,
        seen: IdentityHashMap<Any, Boolean>,
    ): String {
        if (depth > MAX_DEPTH) return "<$display: max depth>"
        if (seen.containsKey(value)) return "<$display: cycle>"
        val fields = generateSequence(value.javaClass as Class<*>) { it.superclass }
            .takeWhile { it != Any::class.java && it != Enum::class.java }
            .flatMap { it.declaredFields.asSequence() }
            .filter { !Modifier.isStatic(it.modifiers) && !it.isSynthetic }
            .sortedBy { it.name }
            .toList()
        if (fields.isEmpty()) return display
        seen[value] = true
        try {
            val lines = fields.map { field ->
                field.isAccessible = true
                "${field.name} = ${render(field.get(value), indent + 1, depth + 1, seen)}"
            }
            return block("$display(", ")", lines, indent)
        } finally {
            seen.remove(value)
        }
    }

    /** An enum constant with a body is an anonymous subclass; name the declaring enum instead. */
    private fun enumClassName(value: Enum<*>): String {
        val cls = value.javaClass
        return (if (cls.isEnum) cls else cls.superclass).simpleName
    }

    private fun block(open: String, close: String, items: List<String>, indent: Int): String {
        if (items.isEmpty()) return open + close
        val pad = "  ".repeat(indent)
        return items.joinToString(separator = ",\n", prefix = "$open\n", postfix = "\n$pad$close") { "$pad  $it" }
    }

    private fun hex(bytes: ByteArray): String =
        "hex(${bytes.size})[" + bytes.joinToString(" ") { "%02X".format(it.toInt() and 0xFF) } + "]"

    private fun quote(text: String): String = buildString {
        append('"')
        for (c in text) {
            when {
                c == '"' -> append("\\\"")
                c == '\\' -> append("\\\\")
                c == '\n' -> append("\\n")
                c == '\r' -> append("\\r")
                c == '\t' -> append("\\t")
                c.code < 0x20 || c.code > 0x7E -> append("\\u%04X".format(c.code))
                else -> append(c)
            }
        }
        append('"')
    }
}
