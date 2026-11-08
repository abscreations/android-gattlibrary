package com.gmail.absolutebollockscreations.gattlibrary.central

import android.bluetooth.BluetoothProfile
import com.gmail.absolutebollockscreations.gattlibrary.central.BtEngineState.FtmsCapabilities
import com.gmail.absolutebollockscreations.gattlibrary.core.catalog.MachineFamilyIds
import com.gmail.absolutebollockscreations.gattlibrary.core.ids.MaxProUuids
import com.gmail.absolutebollockscreations.gattlibrary.core.ftms.FtmsUuids
import org.junit.Assert.*
import org.junit.Test

class MaxProDiscoveryTest {
    private val uuid = CanonicalUuid.fromUuid(MaxProUuids.SERVICE)

    @Test fun `only the selected MAXPRO family uses the MTU 23 experiment`() {
        assertTrue(maxProMtuExperimentEnabled(MachineFamilyIds.MAXPRO))
        assertFalse(maxProMtuExperimentEnabled(null))
        assertFalse(maxProMtuExperimentEnabled(MachineFamilyIds.SIG_FTMS))
        assertFalse(maxProMtuExperimentEnabled(MachineFamilyIds.FLYWHEEL_SPORTS))
    }

    @Test fun `Android 11 through 13 request MTU 23 while Android 14 skips the request`() {
        assertEquals(23, maxProRequestedAttMtu(true, 30, 512))
        assertEquals(23, maxProRequestedAttMtu(true, 31, 512))
        assertEquals(23, maxProRequestedAttMtu(true, 33, 512))
        assertNull(maxProRequestedAttMtu(true, 34, 512))
        assertNull(maxProRequestedAttMtu(true, 37, 512))
        assertEquals(512, maxProRequestedAttMtu(false, 30, 512))
        assertEquals(512, maxProRequestedAttMtu(false, 34, 512))
    }

    @Test fun `product name and full service must occur together`() {
        val accepted = rankedServiceCandidates(MatchObservation(localName = "maxpro",
            advertisedServiceUuids = setOf(uuid)))
        val match = accepted.single { it.candidateId == MachineFamilyIds.MAXPRO }
        assertTrue(match.machineRole)
        assertNull(match.knownService)
        assertTrue(rankedServiceCandidates(MatchObservation(localName = "MAXPRO")).isEmpty())
        assertTrue(rankedServiceCandidates(MatchObservation(advertisedServiceUuids = setOf(uuid))).isEmpty())
        assertTrue(rankedServiceCandidates(MatchObservation(localName = "Other", advertisedServiceUuids = setOf(uuid))).isEmpty())
        assertTrue(HardwareScanFilterSpec.ServiceUuid(uuid) in hardwareScanFilterSpecs())
        assertTrue(HandlerFactory.standard().create(ProtocolKey.MAXPRO) is MaxProProtocolHandler)
        assertTrue(ProtocolKey.MAXPRO in MACHINE_PROTOCOLS)
        assertFalse(HandlerSet.ftmsAndHrm().includes(ProtocolKey.MAXPRO))
    }

    @Test fun `captured FFE1 service data makes MAXPRO visible with hardware filtering enabled`() {
        val dataKey = CanonicalUuid.fromUuid(MaxProUuids.ADVERTISEMENT_DATA)
        val matches = rankedServiceCandidates(MatchObservation(localName = "MAXPRO",
            serviceDataUuids = setOf(dataKey)))
        assertEquals(MachineFamilyIds.MAXPRO, matches.single().candidateId)
        assertTrue(HardwareScanFilterSpec.ServiceDataUuid(dataKey) in hardwareScanFilterSpecs())
        assertTrue(dataKey in DeviceMatcherRegistry.standard().scanFilterServiceUuids())
        assertTrue(rankedServiceCandidates(MatchObservation(localName = "OTHER",
            serviceDataUuids = setOf(dataKey))).isEmpty())
    }

    @Test fun `subscription gate requires identity notification descriptor and writable command`() {
        assertTrue(maxProSurfaceSupported(MachineFamilyIds.MAXPRO, true, true, true))
        assertFalse(maxProSurfaceSupported(null, true, true, true))
        assertFalse(maxProSurfaceSupported(MachineFamilyIds.MAXPRO, false, true, true))
        assertFalse(maxProSurfaceSupported(MachineFamilyIds.MAXPRO, true, false, true))
        assertFalse(maxProSurfaceSupported(MachineFamilyIds.MAXPRO, true, true, false))
    }

    @Test fun `MAXPRO excludes canonical handlers even on a mixed GATT surface`() {
        val services = setOf(MaxProUuids.SERVICE, FtmsUuids.UUID_FTMS_SERVICE)
        assertEquals(setOf(MaxProUuids.SERVICE), maxProHandlerServices(MachineFamilyIds.MAXPRO, services))
        assertEquals(services, maxProHandlerServices(null, services))
    }

    @Test fun `unactivated candidate handlers do not label another machine MAXPRO`() {
        val state = BtEngineState()
        val context = state.connectionRegistry.open(LegacyConnectionRole.FTMS,
            "AA:00", null, BluetoothProfile.STATE_CONNECTING)
        state.connectionRegistry.replaceHandlers(context, HandlerFactory.standard().createAll(MACHINE_PROTOCOLS))
        assertNull(state.connectedDevices.value.single().machineIdentityFamily)
    }

    @Test fun `replacing the one machine slot clears MaxPro while retaining sensor links`() {
        val state = BtEngineState()
        val old = state.openConnectionContext(LegacyConnectionRole.FTMS, "AA:00",
            listOf(MaxProProtocolHandler()))
        val sensor = state.openAdditionalSensorContext("AA:01", emptyList())
        state.maxProStore.begin(old.connectionId)
        state.maxProStore.feed(old.connectionId,
            "{EVT,0F0100640A08A00A852230E00060A08400945913C10006}".toByteArray(), 100)
        val replacement = state.openConnectionContext(LegacyConnectionRole.FTMS, "AA:02", emptyList())
        assertEquals(MaxProState(), state.maxPro.value)
        assertEquals(2, state.connectedDevices.value.size)
        assertEquals(replacement, state.connectionRegistry.current(LegacyConnectionRole.FTMS))
        assertTrue(state.connectedDevices.value.any { it.address == sensor.address })
    }

    @Test fun `MaxPro values and connection identity stay outside fitness metrics and capabilities`() {
        val state = BtEngineState()
        val context = state.connectionRegistry.open(LegacyConnectionRole.FTMS,
            "AA:00", null, BluetoothProfile.STATE_CONNECTED)
        state.connectionRegistry.replaceHandlers(context, listOf(MaxProProtocolHandler().apply { recordStructuralMatch() }))
        val before = state.toMeasurementSnapshot()
        state.maxProStore.begin(context.connectionId)
        state.maxProStore.feed(context.connectionId,
            "{EVT,0F0100640A08A00A852230E00060A08400945913C10006}".toByteArray(), 100)
        assertEquals(before, state.toMeasurementSnapshot())
        assertEquals(DeviceCapabilities.NONE, state.deviceCapabilities.value)
        assertEquals(FtmsCapabilities(), state.providerMachineCapabilities())
        assertNotNull(state.maxPro.value.snapshot)
        val link = state.connectedDevices.value.single()
        assertTrue(link.machineConnection)
        assertTrue(link.activeServices.isEmpty())
        assertEquals(BtEngineState.MachineIdentityFamily.MAXPRO, link.machineIdentityFamily)
    }
}
