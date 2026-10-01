package com.gmail.absolutebollockscreations.gattlibrary.central

import android.bluetooth.BluetoothProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class FitnessGattClientTest {
    @Test
    fun `bounded discovery refreshes its lease and leaves telemetry running`() = runTest {
        val backend = Backend()
        backend.telemetry = true
        val client = client(backend)
        assertTrue(client.startScan(ScanOptions(100)).accepted)
        advanceTimeBy(80)
        assertTrue(client.startScan(ScanOptions(100)).accepted)
        advanceTimeBy(80)
        runCurrent()
        assertTrue(client.discovery.value.discoveryRequested)
        advanceTimeBy(20)
        runCurrent()
        assertFalse(client.discovery.value.discoveryRequested)
        assertTrue(client.discovery.value.radioScanning)
        assertEquals(1, backend.stopCalls)
        client.close()
    }

    @Test
    fun `consumer snapshots isolate collections and retain unresolved identity`() = runTest {
        val services = linkedSetOf(BtEngineState.BtServiceType.HeartRate, BtEngineState.BtServiceType.RSC)
        val backend = Backend()
        backend.state.putDiscoveredDevices(mapOf("AA:01" to row(services = services)))
        val client = client(backend)
        val device = client.discovery.value.devices.single()
        services.clear()
        assertEquals(setOf(DeviceService.HeartRate, DeviceService.RSC), device.services)
        assertNull(device.machineFeature)
        assertNull(device.matchCandidateId)
        try {
            (device.services as MutableSet<DeviceService>).clear()
            fail("consumer collections must not be writable")
        } catch (_: UnsupportedOperationException) { }
        client.close()
    }

    @Test
    fun `same-link multi-service activation passes the original generation and roles`() = runTest {
        val backend = Backend()
        val context = backend.state.connectionRegistry.openAdditional("AA:01", null, BluetoothProfile.STATE_CONNECTED)
        val client = client(backend)
        val link = client.connections.value.links.single()
        val assignment = SensorAssignment(DeviceService.CSC, setOf(SensorRole.CADENCE))
        assertTrue(client.activateSensorService(link.handle, assignment).accepted)
        assertEquals(context.connectionId, backend.activatedConnection)
        assertEquals(setOf(SensorRole.CADENCE), backend.assignment?.roles)
        assertEquals(1, client.connections.value.links.size)
        client.close()
    }

    @Test
    fun `stale link disconnect and activation never touch its replacement even before collection`() = runTest {
        val backend = Backend()
        val old = backend.state.connectionRegistry.openAdditional("AA:01", null, BluetoothProfile.STATE_CONNECTED)
        val client = client(backend)
        val oldHandle = client.connections.value.links.single().handle
        backend.state.connectionRegistry.clear(old)
        val replacement = backend.state.connectionRegistry.openAdditional("AA:01", null, BluetoothProfile.STATE_CONNECTED)
        assertEquals(OperationFailure.STALE_HANDLE, client.disconnect(oldHandle).failure)
        assertEquals(OperationFailure.STALE_HANDLE, client.activateSensorService(oldHandle, SensorAssignment(DeviceService.RSC)).failure)
        assertEquals(0, backend.disconnectCalls)
        assertNull(backend.activatedConnection)
        assertTrue(backend.state.connectionRegistry.isCurrent(replacement))
        client.close()
    }

    @Test
    fun `cross-client device and link handles cannot cause operations`() = runTest {
        val first = Backend().apply {
            state.putDiscoveredDevices(mapOf("AA:01" to row()))
            state.connectionRegistry.openAdditional("AA:01", null, BluetoothProfile.STATE_CONNECTED)
        }
        val second = Backend().apply {
            state.putDiscoveredDevices(mapOf("AA:01" to row()))
            state.connectionRegistry.openAdditional("AA:01", null, BluetoothProfile.STATE_CONNECTED)
        }
        val one = client(first)
        val two = client(second)
        assertEquals(OperationFailure.STALE_HANDLE, two.selectMachine(one.discovery.value.devices.single().handle).failure)
        assertEquals(OperationFailure.STALE_HANDLE, two.disconnect(one.connections.value.links.single().handle).failure)
        assertEquals(0, second.selectCalls)
        assertEquals(0, second.disconnectCalls)
        one.close()
        two.close()
    }

    @Test
    fun `cleared discovery token cannot select a vanished device`() = runTest {
        val backend = Backend().apply { state.putDiscoveredDevices(mapOf("AA:01" to row())) }
        val client = client(backend)
        val handle = client.discovery.value.devices.single().handle
        assertTrue(client.clearDiscoveredDevices().accepted)
        assertEquals(OperationFailure.STALE_HANDLE, client.selectMachine(handle).failure)
        assertEquals(0, backend.selectCalls)
        client.close()
    }

    @Test
    fun `machine selection and sensor assignment dispatch only their explicit requested operation`() = runTest {
        val backend = Backend().apply { state.putDiscoveredDevices(mapOf("AA:01" to row())) }
        val client = client(backend)
        val handle = client.discovery.value.devices.single().handle
        assertTrue(client.selectMachine(handle).accepted)
        assertEquals(1, backend.selectCalls)
        assertEquals(0, backend.connectCalls)
        assertTrue(client.connectSensor(handle, SensorAssignment(DeviceService.HeartRate)).accepted)
        assertEquals(1, backend.connectCalls)
        assertEquals(DeviceService.HeartRate, backend.assignment?.service)
        assertEquals(1, backend.selectCalls)
        backend.acceptSelection = false
        assertEquals(OperationFailure.REJECTED, client.selectMachine(handle).failure)
        client.close()
    }

    @Test
    fun `advertisement-only machine cannot be connected as a sensor`() = runTest {
        val backend = Backend().apply {
            state.putDiscoveredDevices(mapOf("AA:01" to row().copy(
                transport = BtEngineState.DeviceTransport.ADVERTISEMENT_ONLY,
                advertisementSystemId = 7,
            )))
        }
        val client = client(backend)
        assertEquals(OperationFailure.UNSUPPORTED,
            client.connectSensor(client.discovery.value.devices.single().handle, SensorAssignment(DeviceService.HeartRate)).failure)
        assertEquals(0, backend.connectCalls)
        client.close()
    }

    @Test
    fun `disconnect removes only the specified physical link`() = runTest {
        val backend = Backend()
        backend.state.connectionRegistry.openAdditional("AA:01", null, BluetoothProfile.STATE_CONNECTED)
        val remaining = backend.state.connectionRegistry.openAdditional("AA:02", null, BluetoothProfile.STATE_CONNECTED)
        val client = client(backend)
        val handle = client.connections.value.links.first { it.address == "AA:01" }.handle
        assertTrue(client.disconnect(handle).accepted)
        assertEquals(remaining.address, client.connections.value.links.single().address)
        assertEquals(OperationFailure.STALE_HANDLE, client.disconnect(handle).failure)
        assertEquals(1, backend.disconnectCalls)
        client.close()
    }

    @Test
    fun `platform rejection is explicit and close cancels the scan timeout exactly once`() = runTest {
        val backend = Backend()
        val client = client(backend)
        backend.failure = OperationFailure.PERMISSION_REQUIRED
        assertEquals(OperationFailure.PERMISSION_REQUIRED, client.startScan().failure)
        assertEquals(0, backend.startCalls)
        backend.failure = OperationFailure.BLUETOOTH_UNAVAILABLE
        assertEquals(OperationFailure.BLUETOOTH_UNAVAILABLE, client.startScan().failure)
        backend.failure = null
        client.startScan(ScanOptions(100))
        client.close()
        client.close()
        advanceTimeBy(200)
        runCurrent()
        assertEquals(1, backend.closeCalls)
        assertEquals(0, backend.stopCalls)
        assertEquals(OperationFailure.CLOSED, client.startScan().failure)
        assertEquals(OperationFailure.CLOSED, client.stopScan().failure)
        assertFalse(client.discovery.value.radioScanning)
    }

    @Test
    fun `advertisement source retains identity as its address rotates`() = runTest {
        val backend = Backend()
        backend.state.setActiveAdvertisementMachine(BtEngineState.ActiveAdvertisementMachine(
            "AA:01", "vendor.keiser.m", 7, "6.40", 12,
            BtEngineState.AdvertisementSourceState.RECEIVING, "M3i",
        ))
        val client = client(backend)
        val before = client.connections.value.advertisementMachine!!
        backend.state.setActiveAdvertisementMachine(backend.state.activeAdvertisementMachine.value!!.copy(address = "AA:02"))
        runCurrent()
        val after = client.connections.value.advertisementMachine!!
        assertEquals(before.systemId, after.systemId)
        assertEquals(before.generation, after.generation)
        assertEquals("AA:02", after.address)
        assertTrue(client.connections.value.links.isEmpty())
        client.close()
    }

    @Test
    fun `scan failure is observable and a failed local start is not accepted`() = runTest {
        val backend = Backend()
        val client = client(backend)
        backend.scanStarts = false
        assertEquals(OperationFailure.REJECTED, client.startScan().failure)
        assertFalse(client.discovery.value.discoveryRequested)
        backend.scanFailure.value = 3
        runCurrent()
        assertEquals(3, client.discovery.value.scanFailureCode)
        client.close()
    }

    @Test
    fun `unresolved-address rows stay distinct and can never be selected or connected`() = runTest {
        val backend = Backend().apply {
            state.putDiscoveredDevices(mapOf(
                "tmp:1" to row().copy(name = "One", address = ""),
                "tmp:2" to row().copy(name = "Two", address = ""),
            ))
        }
        val client = client(backend)
        val devices = client.discovery.value.devices
        assertEquals(listOf("One", "Two"), devices.map { it.name })
        assertNotSame(devices[0].handle, devices[1].handle)
        assertEquals(OperationFailure.REJECTED, client.selectMachine(devices[0].handle).failure)
        assertEquals(OperationFailure.REJECTED,
            client.connectSensor(devices[1].handle, SensorAssignment(DeviceService.HeartRate)).failure)
        assertEquals(0, backend.selectCalls)
        assertEquals(0, backend.connectCalls)
        client.close()
    }

    @Test
    fun `discovery handle survives a packet refresh of the same row`() = runTest {
        val backend = Backend().apply { state.putDiscoveredDevices(mapOf("AA:01" to row())) }
        val client = client(backend)
        val handle = client.discovery.value.devices.single().handle
        backend.state.putDiscoveredDevices(mapOf("AA:01" to row().copy(rssi = -70)))
        runCurrent()
        assertSame(handle, client.discovery.value.devices.single().handle)
        assertTrue(client.selectMachine(handle).accepted)
        client.close()
    }

    @Test
    fun `scan packets do not re-emit an unchanged connection snapshot`() = runTest {
        val backend = Backend()
        backend.state.connectionRegistry.openAdditional("AA:01", null, BluetoothProfile.STATE_CONNECTED)
        val client = client(backend)
        runCurrent()
        val connections = client.connections.value
        backend.state.putDiscoveredDevices(mapOf("AA:02" to row().copy(address = "AA:02")))
        runCurrent()
        assertEquals(1, client.discovery.value.devices.size)
        assertSame(connections, client.connections.value)
        backend.state.connectionRegistry.openAdditional("AA:03", null, BluetoothProfile.STATE_CONNECTED)
        runCurrent()
        assertEquals(2, client.connections.value.links.size)
        client.close()
    }

    @Test
    fun `measurements follow engine state and equal re-writes do not re-emit`() = runTest {
        val backend = Backend()
        val client = client(backend)
        runCurrent()
        assertEquals(MeasurementSnapshot.EMPTY, client.measurements.value)

        backend.state.setInstantPower(180)
        backend.state.setHeartRate(140)
        runCurrent()
        assertEquals(180, client.measurements.value.powerWatts)
        assertEquals(140, client.measurements.value.heartRate)

        val emitted = mutableListOf<MeasurementSnapshot>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) { client.measurements.collect { emitted += it } }
        backend.state.setInstantPower(180) // equal write
        runCurrent()
        assertEquals(1, emitted.size)
        backend.state.setInstantPower(null)
        runCurrent()
        assertEquals(2, emitted.size)
        assertEquals(null, emitted.last().powerWatts)
        job.cancel()
        client.close()
    }

    @Test
    fun `native event stores are not flows but still drive a new measurement snapshot`() = runTest {
        val backend = Backend()
        val client = client(backend)
        runCurrent()
        backend.state.treadmillNativePace.publish(ConnectionId("AA:01", 1L), 12, 10)
        runCurrent()
        assertEquals(TreadmillRawPace(12, 10), client.measurements.value.treadmillRawPace)
        backend.state.treadmillNativePace.clear()
        runCurrent()
        assertEquals(null, client.measurements.value.treadmillRawPace)
        client.close()
    }

    @Test
    fun `closing the client empties its measurements and stops following the engine`() = runTest {
        val backend = Backend()
        val client = client(backend)
        backend.state.setInstantPower(150)
        runCurrent()
        assertEquals(150, client.measurements.value.powerWatts)
        client.close()
        assertEquals(MeasurementSnapshot.EMPTY, client.measurements.value)
        backend.state.setInstantPower(160)
        runCurrent()
        assertEquals(MeasurementSnapshot.EMPTY, client.measurements.value)
    }

    @Test
    fun `measurements do not wait for the client's own scope dispatcher`() = runTest {
        // The client scope stands in for the main thread and is never run; only the measurement
        // dispatcher advances. The snapshot must still follow the engine.
        val backend = Backend()
        val stalledMain = StandardTestDispatcher()
        val client = FitnessGattClient(
            backend, CoroutineScope(stalledMain + SupervisorJob()),
            measurementDispatcher = StandardTestDispatcher(testScheduler),
        )
        runCurrent()
        backend.state.setInstantPower(222)
        runCurrent()
        assertEquals(222, client.measurements.value.powerWatts)
        client.close()
    }

    @Test
    fun `history follows engine appends one value per change and shares unchanged series`() = runTest {
        val backend = Backend()
        val client = client(backend)
        runCurrent()
        assertTrue(client.history.value.series(MetricSeriesKind.POWER_WATTS).isEmpty())

        backend.state.appendPowerSample(180, tsMillis = 1_000L)
        runCurrent()
        val afterPower = client.history.value
        assertEquals(listOf(1_000L to 180.0), afterPower.series(MetricSeriesKind.POWER_WATTS).pairsOf())

        backend.state.appendHeartRateSample(140, tsMillis = 2_000L)
        runCurrent()
        val afterHr = client.history.value
        assertEquals(listOf(2_000L to 140.0), afterHr.series(MetricSeriesKind.HEART_RATE).pairsOf())
        // Appending heart rate leaves the power series equal, so a power chart does not redraw.
        assertEquals(afterPower.series(MetricSeriesKind.POWER_WATTS), afterHr.series(MetricSeriesKind.POWER_WATTS))
        // A view taken earlier is unchanged by later appends.
        assertEquals(1, afterPower.series(MetricSeriesKind.POWER_WATTS).size)
        assertTrue(afterPower.series(MetricSeriesKind.HEART_RATE).isEmpty())
        client.close()
    }

    @Test
    fun `clearing session data empties the client history and keeps the old view readable`() = runTest {
        val backend = Backend()
        val client = client(backend)
        backend.state.appendPowerSample(180, tsMillis = 1_000L)
        runCurrent()
        val before = client.history.value.series(MetricSeriesKind.POWER_WATTS)

        backend.state.clearSessionData()
        runCurrent()

        assertTrue(client.history.value.series(MetricSeriesKind.POWER_WATTS).isEmpty())
        assertEquals(1, before.size)
        client.close()
    }

    @Test
    fun `capabilities and session state follow the engine and reset on close`() = runTest {
        val backend = Backend()
        val client = client(backend)
        runCurrent()
        assertEquals(DeviceCapabilities.NONE, client.capabilities.value)
        assertFalse(client.sessionActive.value)

        backend.state.connectionRegistry.open(LegacyConnectionRole.FTMS, "AA:01", null, BluetoothProfile.STATE_CONNECTED)
        BtEngineCapabilities.applyFitnessMachineFeatures(
            backend.state,
            byteArrayOf(0x20, 0, 0, 0, 0x08, 0, 0, 0), // pace; power target
        )
        BtEngineCapabilities.applySupportedPowerRange(backend.state, byteArrayOf(0, 0, 0xD0.toByte(), 0x07, 1, 0))
        backend.state.setActiveSession(true)
        runCurrent()
        val caps = client.capabilities.value
        assertTrue(caps.featuresRead && caps.supportsPowerTarget && caps.supportsPaceMeasurement)
        assertEquals(ControlRange(0f, 2_000f, 1f), caps.powerRangeWatts)
        assertTrue(client.sessionActive.value)

        client.close()
        assertEquals(DeviceCapabilities.NONE, client.capabilities.value)
        assertFalse(client.sessionActive.value)
    }

    @Test
    fun `control events reach a collector in order including equal repeats, and end after close`() = runTest {
        val backend = Backend()
        val client = client(backend)
        val seen = mutableListOf<ControlEvent>()
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            client.controlEvents.collect { seen += it }
        }

        backend.state.emitControlEvent(ControlEvent.TargetPowerWatts(100))
        backend.state.emitControlEvent(ControlEvent.TargetPowerWatts(100))
        backend.state.emitControlEvent(ControlEvent.SafetyKeyPulled)
        assertEquals(
            listOf(ControlEvent.TargetPowerWatts(100), ControlEvent.TargetPowerWatts(100), ControlEvent.SafetyKeyPulled),
            seen,
        )

        client.close()
        backend.state.emitControlEvent(ControlEvent.ControlPermissionLost)
        assertEquals(3, seen.size)
        assertTrue(job.isCompleted)
    }

    @Test
    fun `a collector that starts late is not replayed earlier control events`() = runTest {
        val backend = Backend()
        val client = client(backend)
        backend.state.emitControlEvent(ControlEvent.SafetyKeyPulled)
        val seen = mutableListOf<ControlEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { client.controlEvents.collect { seen += it } }
        assertTrue(seen.isEmpty())
        client.close()
    }

    @Test
    fun `closing the client empties its history and stops following the engine`() = runTest {
        val backend = Backend()
        val client = client(backend)
        backend.state.appendPowerSample(150, tsMillis = 1_000L)
        runCurrent()
        assertEquals(1, client.history.value.series(MetricSeriesKind.POWER_WATTS).size)
        client.close()
        assertTrue(client.history.value.series(MetricSeriesKind.POWER_WATTS).isEmpty())
        backend.state.appendPowerSample(160, tsMillis = 2_000L)
        runCurrent()
        assertTrue(client.history.value.series(MetricSeriesKind.POWER_WATTS).isEmpty())
    }

    @Test
    fun `history does not wait for the client's own scope dispatcher`() = runTest {
        val backend = Backend()
        val stalledMain = StandardTestDispatcher()
        val client = FitnessGattClient(
            backend, CoroutineScope(stalledMain + SupervisorJob()),
            measurementDispatcher = StandardTestDispatcher(testScheduler),
        )
        runCurrent()
        backend.state.appendPowerSample(222, tsMillis = 1_000L)
        runCurrent()
        assertEquals(1, client.history.value.series(MetricSeriesKind.POWER_WATTS).size)
        client.close()
    }

    private fun HistorySeries.pairsOf() = List(size) { timeAt(it) to valueAt(it) }

    private fun TestScope.client(backend: Backend) = FitnessGattClient(
        backend, CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob()),
        measurementDispatcher = StandardTestDispatcher(testScheduler),
    )

    private fun row(services: Set<BtEngineState.BtServiceType> = setOf(BtEngineState.BtServiceType.FTMS)) =
        BtEngineState.DiscoveredDevice("Device", "AA:01", -40, 1, null, services)

    private class Backend : CentralConnectionBackend {
        override val state = BtEngineState()
        override val discoveryRequested = MutableStateFlow(false)
        override val scanFailure = MutableStateFlow<Int?>(null)
        var failure: OperationFailure? = null
        var telemetry = false
        var scanStarts = true
        var startCalls = 0
        var stopCalls = 0
        var closeCalls = 0
        var selectCalls = 0
        var acceptSelection = true
        var connectCalls = 0
        var disconnectCalls = 0
        var activatedConnection: ConnectionId? = null
        var assignment: SensorAssignment? = null
        override fun platformFailure(scan: Boolean) = failure
        override fun startScan(hardwareFilters: Boolean) {
            startCalls++
            discoveryRequested.value = true
            state.setScanning(scanStarts)
        }
        override fun stopScan() {
            stopCalls++
            discoveryRequested.value = false
            state.setScanning(telemetry)
        }
        override fun selectMachine(address: String): Boolean { selectCalls++; return acceptSelection }
        override fun connectSensor(address: String, assignment: SensorAssignment): Boolean {
            connectCalls++
            this.assignment = assignment
            return true
        }
        override fun activateService(key: String, assignment: SensorAssignment): Boolean {
            activatedConnection = state.connectionRegistry.contextForKey(key)?.connectionId
            this.assignment = assignment
            return activatedConnection != null
        }
        override fun disconnect(key: String): Boolean {
            disconnectCalls++
            return state.connectionRegistry.contextForKey(key)?.let(state.connectionRegistry::clear) == true
        }
        override fun deselectMachine() = true
        override fun close() { closeCalls++ }
    }
}
