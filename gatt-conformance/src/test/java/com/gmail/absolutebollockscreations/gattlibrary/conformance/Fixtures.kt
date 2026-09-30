package com.gmail.absolutebollockscreations.gattlibrary.conformance

import com.gmail.absolutebollockscreations.gattlibrary.central.FixtureJson

/** Reads gatt-fixtures resources for conformance tests (classpath paths stay `fixtures/...`). */
internal object Fixtures {
    fun json(path: String): Map<String, Any?> {
        val stream = requireNotNull(Fixtures::class.java.classLoader?.getResourceAsStream("fixtures/$path")) {
            "fixture not found: fixtures/$path"
        }
        val text = stream.use { it.readBytes().toString(Charsets.UTF_8) }
        return FixtureJson.obj(FixtureJson.parse(text))
    }

    fun objects(value: Any?): List<Map<String, Any?>> = FixtureJson.array(value).map(FixtureJson::obj)

    fun bytes(array: Any?): ByteArray = FixtureJson.array(array).map { FixtureJson.int(it).toByte() }.toByteArray()

    fun hex(text: String): ByteArray {
        require(text.length % 2 == 0) { "odd-length hex" }
        return ByteArray(text.length / 2) { text.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }
}
