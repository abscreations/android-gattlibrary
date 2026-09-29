package com.gmail.absolutebollockscreations.gattlibrary.central

import org.junit.Assert.assertEquals
import org.junit.Test

class LifeFitnessSetupGateTest {
    private fun profile(first: String = "A", last: String = "B", email: String = "c@d") =
        LifeFitnessProfile(first, last, email)

    @Test
    fun `a complete profile on a fingerprinted machine is ready`() {
        assertEquals(
            LifeFitnessSetupGate.Ready,
            lifeFitnessSetupGate(fingerprinted = true, profile = profile()),
        )
    }

    @Test
    fun `an incomplete profile reports exactly which fields to fill`() {
        val gate = lifeFitnessSetupGate(
            fingerprinted = true,
            profile = LifeFitnessProfile("Given", "", ""),
        )

        assertEquals(
            LifeFitnessSetupGate.NeedsProfile(
                listOf(LifeFitnessProfileField.LAST_NAME, LifeFitnessProfileField.EMAIL),
            ),
            gate,
        )
    }

    @Test
    fun `a machine that is not fingerprinted never triggers the prompt`() {
        // A machine that merely resembles Life Fitness must not trigger a profile prompt.
        assertEquals(
            LifeFitnessSetupGate.NotApplicable,
            lifeFitnessSetupGate(fingerprinted = false, profile = LifeFitnessProfile.EMPTY),
        )
        assertEquals(
            LifeFitnessSetupGate.NotApplicable,
            lifeFitnessSetupGate(fingerprinted = false, profile = profile()),
        )
    }
}
