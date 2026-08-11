package com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine

import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.peripheral.TargetCapabilities
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Plan §11.7, §11.8 and amended D11 for the Concept2 PM.
 *
 * §11.7 asks for control request/response tests **where control exists**. For a family that
 * cannot be tested on hardware, §13 inverts the obligation: prove the control path is
 * *unreachable* and that §9.2's capability row sets no target bits. That is the shape of test
 * this file contains, because for Concept2 the absence is the requirement rather than a gap.
 *
 * ## Why the peripheral CSAFE surface does not authorize upstream control
 *
 * D11 now permits `0x0020`-`0x0022` only in the downstream PM5 peripheral simulator. The
 * central-role Concept2 handler remains measurement-only, and no FTMS target capability follows
 * from simulator-local workout programming.
 */
class Concept2ControlHonestyTest {

    // ── The central adapter still cannot reach the peripheral control transport ──

    @Test
    fun `central Concept2 handler does not claim the CSAFE service or characteristics`() {
        val handler = Concept2ProtocolHandler()
        val claimed = handler.primaryServices + handler.extraCharacteristics
        val peripheralOnly = setOf(
            BluetoothIds.UUID_C2_PM_CONTROL_SERVICE,
            BluetoothIds.UUID_C2_CSAFE_REQUEST,
            BluetoothIds.UUID_C2_CSAFE_RESPONSE,
        )

        assertTrue(claimed.intersect(peripheralOnly).isEmpty())
    }

    @Test
    fun `the handler owns no writable characteristic`() {
        // Every characteristic the handler claims is a notification source or, in the case of
        // the sample rate, deliberately not claimed at all. None is a control endpoint.
        val handler = Concept2ProtocolHandler()

        assertTrue(
            "the sample-rate characteristic is configuration and is not owned",
            BluetoothIds.UUID_C2_STATUS_SAMPLE_RATE !in handler.extraCharacteristics,
        )
        assertEquals(
            setOf(
                BluetoothIds.UUID_C2_MULTIPLEXED_INFORMATION,
                BluetoothIds.UUID_C2_GENERAL_STATUS,
                BluetoothIds.UUID_C2_ADDITIONAL_STATUS_1,
                BluetoothIds.UUID_C2_ADDITIONAL_STATUS_2,
                BluetoothIds.UUID_C2_ADDITIONAL_STROKE_DATA,
            ),
            handler.extraCharacteristics,
        )
    }

    @Test
    fun `the subscription plan issues notification enables and nothing else`() {
        // A plan that ever emitted a write would be the first step of a control path.
        val everything = setOf(
            BluetoothIds.UUID_C2_MULTIPLEXED_INFORMATION,
            BluetoothIds.UUID_C2_GENERAL_STATUS,
            BluetoothIds.UUID_C2_ADDITIONAL_STATUS_1,
            BluetoothIds.UUID_C2_ADDITIONAL_STATUS_2,
            BluetoothIds.UUID_C2_ADDITIONAL_STROKE_DATA,
            BluetoothIds.UUID_C2_STATUS_SAMPLE_RATE,
        )

        for (available in listOf(everything, everything - BluetoothIds.UUID_C2_MULTIPLEXED_INFORMATION)) {
            val plan = planConcept2Discovery(available)
            assertTrue(
                plan.operations.all { it.kind == Concept2GattOperationKind.ENABLE_NOTIFICATIONS },
            )
        }
        assertTrue(
            planConcept2IndividualFallback(everything)
                .all { it.kind == Concept2GattOperationKind.ENABLE_NOTIFICATIONS },
        )
    }

    // ── §11.8 / §9.2: the downstream capability row ──────────────────────────────

    @Test
    fun `a Concept2-driven session advertises no target-setting capability`() {
        // A PM has no upstream FTMS control features to mirror, so there is nothing honest to
        // advertise. §9.2's row for this family is empty by construction rather than by choice,
        // and this asserts the construction actually produces that.
        val capabilities = TargetCapabilities.fromEngineCapabilities(
            BtEngineState.FtmsCapabilities(featuresRead = true),
        )

        assertEquals(
            "not one target bit may be set for a machine FitBridge cannot control",
            0,
            capabilities.toTargetFeatureBits(),
        )
        assertFalse("no control surface may be offered", capabilities.any)
        assertFalse(capabilities.supportsSpeed)
        assertFalse(capabilities.supportsInclination)
        assertFalse(capabilities.supportsResistance)
        assertFalse(capabilities.supportsPower)
        assertFalse(capabilities.supportsHeartRate)
        assertFalse(capabilities.indoorBikeSimulation)
    }

    @Test
    fun `reading a PM's data never grants control capability`() {
        // The engine's control flags come from an FTMS 0x2ACC read. A Concept2 link performs no
        // such read, so the flags stay false and the downstream row stays empty. Pinned because
        // a future change that defaulted any of these to true would silently advertise control
        // of a rowing machine.
        val state = BtEngineState()

        assertFalse(state.featuresRead)
        assertFalse(state.supportsSpeed)
        assertFalse(state.supportsInclination)
        assertFalse(state.supportsResistance)
        assertFalse(state.supportsPower)
        assertFalse(state.supportsIndoorBikeSimulation)
        assertFalse(state.supportsSpinDown)
    }

    // ── §11.3 remainder: trailing octets and counter rollover ────────────────────

    @Test
    fun `trailing vendor octets past a known layout are ignored`() {
        // A longer frame than the specification documents is not corruption — a later firmware
        // may append fields, exactly as the multiplexed forms of 0x32 and 0x36 already do
        // relative to their individual counterparts.
        val stroke = ByteArray(15) { 0 }.also {
            it[0] = 0x8B.toByte(); it[1] = 0x2F; it[3] = 0xCB.toByte()
        }
        val padded = stroke + ByteArray(8) { 0xEE.toByte() }

        assertEquals(
            Concept2Measurement.additionalStrokeData(stroke),
            Concept2Measurement.additionalStrokeData(padded),
        )
    }

    @Test
    fun `a wrapped distance counter decodes as the value on the wire`() {
        // uint24 in 0.1 m reaches 1,677,721.5 m before wrapping — about 1,677 km, which no
        // session approaches. Pinned anyway, and for the same reason as FTMS: the parser
        // reports what arrived and leaves continuity to fusion, which has nativeCumulative.
        fun general(d0: Int, d1: Int, d2: Int) = Concept2Measurement.generalStatus(
            ByteArray(19) { 0 }.also {
                it[0] = 0x8B.toByte(); it[1] = 0x2F
                it[3] = d0.toByte(); it[4] = d1.toByte(); it[5] = d2.toByte()
            },
        )!!

        assertEquals(1_677_721.5, general(0xFF, 0xFF, 0xFF).distanceMetres!!, 1e-9)
        assertEquals("one decimetre later the counter is zero", 0.0, general(0, 0, 0).distanceMetres!!, 1e-9)
    }
}
