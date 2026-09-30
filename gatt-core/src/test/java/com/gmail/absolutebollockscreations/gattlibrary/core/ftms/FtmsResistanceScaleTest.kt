package com.gmail.absolutebollockscreations.gattlibrary.core.ftms

import com.gmail.absolutebollockscreations.gattlibrary.core.ftms.FtmsResistanceScale.Encoding
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Resistance Level encoding is decided from the machine's declared range.
 *
 * The captured elliptical's numbers are used verbatim rather than tidied, so a
 * future reader can check them against the NDJSON and the sniffer trace.
 */
class FtmsResistanceScaleTest {

    // Supported Resistance Level Range 0x2AD6 = 0a00 2c01 0a00
    // -> raw min 10, max 300, step 10 -> levels 1.0 .. 30.0 step 1.0
    private fun observed(raw: Int) =
        FtmsResistanceScale.decide(raw, min = 1.0f, max = 30.0f, incr = 1.0f, known = true)

    @Test
    fun `the captured elliptical is read as raw levels`() {
        // Its data field carries 7 and 8. As tenths those are 0.7 and 0.8, below
        // its own declared minimum of 1.0 - so the machine cannot mean tenths.
        assertEquals(Encoding.RAW_LEVELS, observed(7))
        assertEquals(Encoding.RAW_LEVELS, observed(8))
        assertEquals(8.0f, Encoding.RAW_LEVELS.toLevel(8), 0.0001f)
    }

    @Test
    fun `a spec-compliant machine is read as tenths`() {
        // Level 8 sent correctly as 80. Raw would be 80, far outside 1.0..30.0.
        assertEquals(Encoding.TENTHS, observed(80))
        assertEquals(8.0f, Encoding.TENTHS.toLevel(80), 0.0001f)
    }

    @Test
    fun `the step boundary breaks a tie when both readings are in range`() {
        // 15: as tenths 1.5, as raw 15. Both inside 1.0..30.0, but the declared
        // step is 1.0, so only the raw reading lands on a boundary.
        assertEquals(Encoding.RAW_LEVELS, observed(15))
    }

    @Test
    fun `a genuinely ambiguous reading defers to the spec`() {
        // Half-level steps make 1.5 legal too, so nothing distinguishes them.
        assertEquals(
            Encoding.TENTHS,
            FtmsResistanceScale.decide(15, min = 1.0f, max = 30.0f, incr = 0.5f, known = true),
        )
    }

    @Test
    fun `an unread range forces the spec`() {
        // Never guess from a range we have not actually seen.
        assertEquals(
            Encoding.TENTHS,
            FtmsResistanceScale.decide(7, min = 0f, max = 0f, incr = 0f, known = false),
        )
        assertEquals(
            Encoding.TENTHS,
            FtmsResistanceScale.decide(7, min = 1.0f, max = 30.0f, incr = 1.0f, known = false),
        )
    }

    @Test
    fun `a degenerate range is ignored rather than trusted`() {
        assertEquals(
            Encoding.TENTHS,
            FtmsResistanceScale.decide(7, min = 5f, max = 5f, incr = 1f, known = true),
        )
    }

    @Test
    fun `a value outside the range under either reading defers to the spec`() {
        // 5000 is neither 500.0 nor 5000 within 1.0..30.0. The machine has told
        // us nothing usable, so the standard wins.
        assertEquals(Encoding.TENTHS, observed(5000))
    }

    @Test
    fun `zero is not forced into a false positive`() {
        // A machine at rest reporting 0 is below a minimum of 1.0 either way.
        assertEquals(Encoding.TENTHS, observed(0))
    }

    @Test
    fun `a range starting at zero still discriminates`() {
        // min 0 admits 0.7; max 30 excludes 70. Tenths wins on range alone.
        assertEquals(
            Encoding.TENTHS,
            FtmsResistanceScale.decide(7, min = 0f, max = 3.0f, incr = 0.1f, known = true),
        )
    }
}
