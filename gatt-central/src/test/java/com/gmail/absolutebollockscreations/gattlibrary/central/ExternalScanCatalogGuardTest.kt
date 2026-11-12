package com.gmail.absolutebollockscreations.gattlibrary.central

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * LIB-69 characterization of the engine half of [FitnessGattClient.catalogExternalScanResult].
 * JVM tests cannot build a populated ScanResult (stub android.jar, no mocking library), so the
 * admission rules and the silent publication are pinned at source level. If one of these fails,
 * the change altered what an app-owned diagnostic scanner can put in the catalog, or made it noisy.
 */
class ExternalScanCatalogGuardTest {
    private fun source(name: String): String {
        val path = "src/main/java/com/gmail/absolutebollockscreations/gattlibrary/central/$name"
        return listOf(File(path), File("gatt-central/$path")).first { it.isFile }.readText()
    }

    /** Text from [start] through the first top-level closing brace after it. */
    private fun body(text: String, start: String): String {
        val from = text.indexOf(start)
        assertTrue("missing: $start", from >= 0)
        val end = Regex("""\r?\n}\r?\n""").find(text, from) ?: error("unterminated: $start")
        return text.substring(from, end.range.last + 1)
    }

    private val noise = listOf("Timber", "CaptureHooks", "BleCapture", "Telemetry", "telemetry", "putDiscoveredDevices(")

    @Test
    fun `the engine admits only connectable GATT matches and publishes them silently`() {
        val catalog = body(source("btEngine.scan.kt"), "internal fun BtEngine.catalogDiagnosticAdvertisement(")
        listOf(
            "if (!result.isConnectable) return",
            "if (address.isBlank()) return",
            "observation.candidates.firstOrNull() ?: return",
            "if (primary.transport != MatchTransport.GATT) return",
            "state.putDiagnosticDiscoveredDevices(",
            "diagnostic = true",
        ).forEach { assertTrue("catalog admission lost: $it", catalog.contains(it)) }
        noise.forEach { assertFalse("catalog admission must not use $it", catalog.contains(it)) }
    }

    @Test
    fun `the shared upsert stays silent and keeps the diagnostic publication branch`() {
        val upsert = body(source("btEngine.scan.kt"), "private fun BtEngine.upsertDiscoveredDevice(")
        assertTrue(upsert.contains("if (diagnostic) state.putDiagnosticDiscoveredDevices(map) else state.putDiscoveredDevices(map)"))
        noise.dropLast(1).forEach { assertFalse("upsert must not use $it", upsert.contains(it)) }
        val setter = Regex("""internal fun putDiagnosticDiscoveredDevices\([^)]*\)\s*\{[^}]*}""")
            .find(source("btEngineState.kt"))?.value ?: error("diagnostic setter missing")
        assertFalse("the diagnostic setter must not log", setter.contains("Timber"))
    }

    @Test
    fun `the engine extension is not public API`() {
        val text = source("btEngine.scan.kt")
        assertTrue(text.contains("internal fun BtEngine.catalogDiagnosticAdvertisement("))
        assertFalse(Regex("""(?m)^fun BtEngine\.catalogDiagnosticAdvertisement\(""").containsMatchIn(text))
    }
}
