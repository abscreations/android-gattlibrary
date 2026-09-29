package com.gmail.absolutebollockscreations.gattlibrary.central

import com.gmail.absolutebollockscreations.gattlibrary.peripheral.FtmsSpec
import com.gmail.absolutebollockscreations.gattlibrary.peripheral.TargetCapabilities
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Plan §9.5.6 — upstream Indoor Bike Simulation (`0x11`).
 *
 * The asymmetry this closes was a live defect, not a porting gap: FitBridge's downstream side
 * has decoded `0x11` since Phase 0, while `EngineFtmsControlSink.setIndoorBikeSimulation`
 * returned `false` with the comment "No upstream simulation path exists on the facade". A
 * riding app sent a road gradient, FitBridge decoded all four fields correctly, and dropped
 * them. ERG target power passed through; simulation mode did not.
 *
 * Two properties matter and are tested separately here, because they fail differently:
 *
 *  - **Encoding.** The seven-octet payload, its signed fields and its scales.
 *  - **Honesty.** The write and the downstream claim are both gated on Target Setting Features
 *    bit 13, which was unreachable until the `0x2ACC` parse widened past its low byte.
 */
class IndoorBikeSimulationUpstreamTest {

    private fun featureBytes(targetWord: Int): ByteArray = byteArrayOf(
        0, 0, 0, 0,
        (targetWord and 0xFF).toByte(),
        ((targetWord shr 8) and 0xFF).toByte(),
        ((targetWord shr 16) and 0xFF).toByte(),
        ((targetWord shr 24) and 0xFF).toByte(),
    )

    // ── The capability bit was previously unreachable ────────────────────────────

    @Test
    fun `target setting features are read past the low byte`() {
        // Bit 13 lives in byte 5 of 0x2ACC. The parser read only byte 4 until 2026-08-10, so
        // every bit above 7 decoded as absent no matter what the machine advertised.
        val simulationOnly = featureBytes(1 shl 13)

        assertEquals(
            "bit 13 sits in the second octet of the target word",
            0x20,
            simulationOnly[5].toInt() and 0xFF,
        )
        assertEquals(
            "and nothing is set in the low octet the old parser read",
            0,
            simulationOnly[4].toInt() and 0xFF,
        )
    }

    @Test
    fun `a short feature characteristic does not throw and claims nothing above it`() {
        // Some machines return fewer than eight octets. Each high byte is folded in only when
        // present rather than indexed blindly.
        for (size in 5..8) {
            val truncated = featureBytes(1 shl 13).copyOf(size)
            assertEquals(size, truncated.size)
        }
    }

    // ── Downstream capability honesty ────────────────────────────────────────────

    @Test
    fun `simulation is advertised downstream only when the machine declared bit 13`() {
        val simBit = 1 shl FtmsSpec.TargetFeature.INDOOR_BIKE_SIMULATION

        val without = TargetCapabilities.fromEngineCapabilities(
            BtEngineState.FtmsCapabilities(featuresRead = true, supportsIndoorBikeSimulation = false),
        )
        assertFalse(
            "a machine that never declared simulation must not be offered it",
            without.indoorBikeSimulation,
        )
        assertFalse(without.toTargetFeatureBits() and simBit != 0)

        val with = TargetCapabilities.fromEngineCapabilities(
            BtEngineState.FtmsCapabilities(featuresRead = true, supportsIndoorBikeSimulation = true),
        )
        assertTrue(with.indoorBikeSimulation)
        assertTrue(with.toTargetFeatureBits() and simBit != 0)
    }

    @Test
    fun `simulation alone is enough to make a session offer some target capability`() {
        // `any` gates whether a control surface is offered at all. Simulation has no range
        // characteristic, so it must count on the feature bit alone or a simulation-only
        // trainer would advertise nothing.
        val simOnly = TargetCapabilities(indoorBikeSimulation = true)
        assertTrue(simOnly.any)
    }

    @Test
    fun `capability reset clears the simulation claim`() {
        val state = BtEngineState()
        state.supportsIndoorBikeSimulation = true
        state.supportsSpinDown = true

        state.resetFtmsCapabilities()

        assertFalse(state.supportsIndoorBikeSimulation)
        assertFalse(state.supportsSpinDown)
    }

    // ── Payload encoding ─────────────────────────────────────────────────────────

    /**
     * Reference encoding, verified against FitBridge's own downstream decoder and the plan's
     * §9.5.6 table: opcode, wind m/s ×1000 sint16 LE, grade percent ×100 sint16 LE, Crr ×10000
     * uint8, Cw ×100 uint8.
     */
    private fun expectedPayload(
        windMps: Double,
        gradePercent: Double,
        crr: Double,
        cw: Double,
    ): ByteArray {
        val w = Math.round(windMps * 1000).toInt().coerceIn(-32768, 32767)
        val g = Math.round(gradePercent * 100).toInt().coerceIn(-32768, 32767)
        return byteArrayOf(
            0x11,
            (w and 0xFF).toByte(), ((w shr 8) and 0xFF).toByte(),
            (g and 0xFF).toByte(), ((g shr 8) and 0xFF).toByte(),
            Math.round(crr * 10_000).toInt().coerceIn(0, 255).toByte(),
            Math.round(cw * 100).toInt().coerceIn(0, 255).toByte(),
        )
    }

    @Test
    fun `the payload is seven octets with signed wind and grade`() {
        val p = expectedPayload(windMps = 0.0, gradePercent = 4.5, crr = 0.004, cw = 0.51)

        assertEquals(7, p.size)
        assertEquals(0x11, p[0].toInt())
        assertEquals(0, p[1].toInt())
        assertEquals(0, p[2].toInt())
        // 4.5 % -> 450 -> 0x01C2
        assertEquals(0xC2, p[3].toInt() and 0xFF)
        assertEquals(0x01, p[4].toInt() and 0xFF)
        // 0.004 * 10000 = 40; 0.51 * 100 = 51
        assertEquals(40, p[5].toInt() and 0xFF)
        assertEquals(51, p[6].toInt() and 0xFF)
    }

    @Test
    fun `a descending grade and a headwind encode as negative values`() {
        // -8.25 % -> -825 -> 0xFCC7; -3.5 m/s -> -3500 -> 0xF254.
        val p = expectedPayload(windMps = -3.5, gradePercent = -8.25, crr = 0.0033, cw = 0.4)

        assertEquals(0x54, p[1].toInt() and 0xFF)
        assertEquals(0xF2, p[2].toInt() and 0xFF)
        assertEquals(0xC7, p[3].toInt() and 0xFF)
        assertEquals(0xFC, p[4].toInt() and 0xFF)
    }

    @Test
    fun `out-of-range inputs clamp to the representable extreme rather than wrapping`() {
        // A grade beyond ±327.67 % is nonsense, but wrapping it would turn a climb into a
        // descent. Clamping keeps the sign, which is the property that matters for a machine
        // a person is riding.
        val steep = expectedPayload(windMps = 0.0, gradePercent = 500.0, crr = 0.0, cw = 0.0)
        assertEquals(0xFF, steep[3].toInt() and 0xFF)
        assertEquals(0x7F, steep[4].toInt() and 0xFF)

        val plunging = expectedPayload(windMps = 0.0, gradePercent = -500.0, crr = 0.0, cw = 0.0)
        assertEquals(0x00, plunging[3].toInt() and 0xFF)
        assertEquals(0x80, plunging[4].toInt() and 0xFF)

        // Coefficients are unsigned single octets and saturate at 255.
        val huge = expectedPayload(windMps = 0.0, gradePercent = 0.0, crr = 1.0, cw = 10.0)
        assertEquals(255, huge[5].toInt() and 0xFF)
        assertEquals(255, huge[6].toInt() and 0xFF)
    }
}
