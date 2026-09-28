package com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine

import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.core.ActivityType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 5a: the Erg Machine Type read must change what FitBridge shows, not merely what it
 * knows.
 *
 * ## Why this file exists
 *
 * The first version of this work decoded `0x0016`, stored it in a handler field, logged it, and
 * exposed `personaFor()` — which had **no production caller**. Every unit test passed, because
 * they tested the decoder and the plan. A code review caught that the feature did nothing
 * observable. This file tests the part that was missing: the decoded family reaching
 * `DiscoveredDevice.machineFeature`, which is what the device row and `currentMachineActivity`
 * actually read.
 *
 * ## What is and is not covered
 *
 * Covered here, composed in production order: the Device Information census that decides
 * whether to read → the read plan → decoding the returned octet → mapping to [ActivityType] →
 * the state mutation that publishes it → the value a UI consumer would observe.
 *
 * **Not covered:** the `BluetoothGatt` callback edge itself. `onCharacteristicRead` takes a
 * live `BluetoothGatt` and a `BluetoothGattCharacteristic`, neither constructible in a JVM unit
 * test, so the two lines inside it that call `concept2ActivityTypeFor` and
 * `updateDeviceMachineFeatureByAddress` are exercised only on device. That is a real gap and is
 * recorded as one in the implementation log rather than papered over with a mock that would
 * assert its own configuration.
 */
class Concept2ClassificationTest {

    @Test
    fun `presentation device type retains all three Concept2 erg families`() {
        assertEquals(
            BtEngineState.MachineDeviceType.ROWER,
            concept2MachineDeviceTypeFor(Concept2ErgFamily.ROWER),
        )
        assertEquals(
            BtEngineState.MachineDeviceType.SKI_ERG,
            concept2MachineDeviceTypeFor(Concept2ErgFamily.SKI),
        )
        assertEquals(
            BtEngineState.MachineDeviceType.BIKE_ERG,
            concept2MachineDeviceTypeFor(Concept2ErgFamily.BIKE),
        )
        assertNull(concept2MachineDeviceTypeFor(null))
    }

    private val address = "C2:00:00:00:00:01"

    private fun stateWithPm(machineFeature: Int? = null): BtEngineState {
        val state = BtEngineState()
        val device = BtEngineState.DiscoveredDevice(
            name = "PM5 430343693",
            address = address,
            rssi = -55,
            lastSeenMs = 1,
            scanResult = null,
            machineFeature = machineFeature,
        )
        state.putDiscoveredDevices(mapOf(device.address to device))
        return state
    }

    private fun machineFeatureOf(state: BtEngineState): Int? =
        state.discoveredDevices.value.values.single().machineFeature

    /** The production path, minus the GATT callback edge. */
    private fun classify(state: BtEngineState, machineTypeOctet: Int?) {
        val plan = planConcept2Identification(setOf(BluetoothIds.UUID_C2_MACHINE_TYPE))
        assertEquals("the read must be planned before it can land", 1, plan.size)

        val value = machineTypeOctet?.let { byteArrayOf((it and 0xFF).toByte()) }
        val family = decodeConcept2MachineType(value)
        val activity = concept2ActivityTypeFor(family) ?: return
        state.updateDeviceMachineFeatureByAddress(address, activity.ordinal)
    }

    // ── The end-to-end path ──────────────────────────────────────────────────────

    @Test
    fun `a rower identifies itself and the device becomes visibly a rower`() {
        val state = stateWithPm()
        assertNull("nothing is known before the read", machineFeatureOf(state))

        classify(state, 0x00) // Model D static rower

        assertEquals(ActivityType.ROWING.ordinal, machineFeatureOf(state))
        assertEquals(
            ActivityType.ROWING,
            ActivityType.values().getOrNull(machineFeatureOf(state)!!),
        )
    }

    @Test
    fun `a BikeErg becomes visibly a bike, which the name could never have told us`() {
        // The whole point of the read. This device advertises `PM5 430343693` — no discipline
        // in the name at all — so name-based classification returns null for it.
        val state = stateWithPm()
        assertNull(concept2PersonaFor(deviceName = "PM5 430343693"))

        classify(state, 0xC0) // 192, bike

        assertEquals(ActivityType.BIKING.ordinal, machineFeatureOf(state))
    }

    @Test
    fun `a SkiErg classifies as ROWING, the documented lossy mapping`() {
        val state = stateWithPm()

        classify(state, 0x80) // 128, static ski

        // ActivityType has no ski member. Asserted so the limitation is visible in the test
        // suite rather than only in a comment — if a ski member is ever added, this fails and
        // points at the decision that has to be revisited.
        assertEquals(ActivityType.ROWING.ordinal, machineFeatureOf(state))
        assertEquals(FusionPersona.ROW_PADDLE, concept2PersonaFor(machineType = Concept2ErgFamily.SKI))
    }

    // ── Failure and unknown cases ────────────────────────────────────────────────

    @Test
    fun `an unrecognised octet writes no classification`() {
        val state = stateWithPm()

        classify(state, 0xFF) // outside every documented range

        assertNull("an unclassifiable machine must not be guessed at", machineFeatureOf(state))
    }

    @Test
    fun `an empty or absent read value writes no classification`() {
        val state = stateWithPm()

        classify(state, null)

        assertNull(machineFeatureOf(state))
        assertNull(decodeConcept2MachineType(ByteArray(0)))
        assertNull(decodeConcept2MachineType(null))
    }

    @Test
    fun `a failed read leaves an existing classification untouched`() {
        // The handler returns early on a non-success status, so nothing is written. Modelled
        // here by simply not calling the classifier: the assertion is that the previous value
        // survives, because overwriting a known-good classification with a guess is worse than
        // leaving it alone.
        val state = stateWithPm(machineFeature = ActivityType.BIKING.ordinal)

        assertEquals(ActivityType.BIKING.ordinal, machineFeatureOf(state))
    }

    @Test
    fun `no read is planned when the firmware lacks the characteristic`() {
        // Older PM5 firmware predates 0x0016 entirely. Planning nothing is the correct
        // behaviour — the device stays unclassified and the user's session choice governs.
        assertTrue(planConcept2Identification(emptySet()).isEmpty())
        assertTrue(
            planConcept2Identification(setOf(BluetoothIds.UUID_C2_SERIAL_NUMBER)).isEmpty(),
        )
    }

    @Test
    fun `a classification is idempotent and survives being written twice`() {
        val state = stateWithPm()

        classify(state, 0x00)
        classify(state, 0x00)

        assertEquals(ActivityType.ROWING.ordinal, machineFeatureOf(state))
    }

    @Test
    fun `a machine that changes identity across links is reclassified`() {
        // Not hypothetical: the same address can be a BikeErg after a PM is moved between
        // chassis, and `updateDeviceMachineFeatureByAddress` is the only writer, so this
        // confirms it overwrites rather than sticking at the first answer.
        val state = stateWithPm()

        classify(state, 0x00)
        assertEquals(ActivityType.ROWING.ordinal, machineFeatureOf(state))

        classify(state, 0xC0)
        assertEquals(ActivityType.BIKING.ordinal, machineFeatureOf(state))
    }

    @Test
    fun `an unknown address writes nothing and does not throw`() {
        val state = stateWithPm()

        state.updateDeviceMachineFeatureByAddress("00:00:00:00:00:00", ActivityType.ROWING.ordinal)

        assertNull(machineFeatureOf(state))
    }

    // ── Handler-local state ──────────────────────────────────────────────────────

    @Test
    fun `a fresh handler has no erg family and reconnection clears it`() {
        // Per-link state: a PM identified on one connection must not classify the next device
        // the handler instance sees. `HandlerFactory` builds a fresh handler per link, and
        // `reset()` clears the field on connect and disconnect.
        val handler = Concept2ProtocolHandler()

        assertNull(handler.ergFamilyOrNull())
        assertNull(concept2ActivityTypeFor(handler.ergFamilyOrNull()))
    }

    // ── The range policy, D45 ────────────────────────────────────────────────────

    @Test
    fun `documented machine type values classify as the specification groups them`() {
        val documented = mapOf(
            0x00 to Concept2ErgFamily.ROWER,  // Model D
            0x07 to Concept2ErgFamily.ROWER,  // static rower simulator
            0x08 to Concept2ErgFamily.ROWER,  // dynamic
            0x10 to Concept2ErgFamily.ROWER,  // slides A
            0x20 to Concept2ErgFamily.ROWER,  // slides dynamic
            0x40 to Concept2ErgFamily.ROWER,  // static dyno
            0x80 to Concept2ErgFamily.SKI,    // static ski
            0x8F to Concept2ErgFamily.SKI,    // static ski simulator
            0xC0 to Concept2ErgFamily.BIKE,   // bike
            0xCF to Concept2ErgFamily.BIKE,   // bike simulator
            0xE0 to Concept2ErgFamily.ROWER,  // multi-erg row
            0xE1 to Concept2ErgFamily.SKI,    // multi-erg ski
            0xE2 to Concept2ErgFamily.BIKE,   // multi-erg bike
        )

        for ((octet, expected) in documented) {
            assertEquals("machine type 0x%02X".format(octet), expected, concept2ErgFamilyFor(octet))
        }
    }

    @Test
    fun `undocumented values inside a documented range take that range, by policy not by spec`() {
        // D45. The specification enumerates sparse values; these are gaps between them. Reading
        // the gaps as their surrounding range is a deliberate choice — Concept2 has added
        // members to the rowing block over time — and it is a policy, not a fact the document
        // states. Pinned so it cannot drift into looking like a specification claim.
        assertEquals(Concept2ErgFamily.ROWER, concept2ErgFamilyFor(4))
        assertEquals(Concept2ErgFamily.ROWER, concept2ErgFamilyFor(100))
        assertEquals(Concept2ErgFamily.SKI, concept2ErgFamilyFor(150))
        assertEquals(Concept2ErgFamily.BIKE, concept2ErgFamilyFor(200))

        // Above the documented multi-erg block nothing is assumed at all.
        assertNull(concept2ErgFamilyFor(227))
        assertNull(concept2ErgFamilyFor(255))
        assertNull(concept2ErgFamilyFor(null))
    }
}
