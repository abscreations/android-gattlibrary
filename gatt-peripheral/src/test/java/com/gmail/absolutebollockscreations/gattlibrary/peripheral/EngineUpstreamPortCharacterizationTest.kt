package com.gmail.absolutebollockscreations.gattlibrary.peripheral

import com.gmail.absolutebollockscreations.gattlibrary.central.DeviceCapabilities
import com.gmail.absolutebollockscreations.gattlibrary.central.MeasurementSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pins the two live reads the server controller makes through [EngineUpstreamPort] (unit-1 migration, L24). */
class EngineUpstreamPortCharacterizationTest {

    @Test
    fun `no central connections means no addresses`() {
        assertTrue(EngineUpstreamPort({ MeasurementSnapshot.EMPTY }, { DeviceCapabilities.NONE }).connectedCentralAddresses().isEmpty())
    }

    @Test
    fun `connected central addresses come from the machine link, read live`() {
        val link = FakeUpstreamMachineLink()
        val port = EngineUpstreamPort({ MeasurementSnapshot.EMPTY }, { DeviceCapabilities.NONE }, link)
        assertTrue(port.connectedCentralAddresses().isEmpty())

        link.addresses = setOf("aa:bb:cc:dd:ee:ff")
        assertEquals(setOf("aa:bb:cc:dd:ee:ff"), port.connectedCentralAddresses())
    }

    @Test
    fun `pace support is read from the published capabilities each call`() {
        var published = DeviceCapabilities.NONE
        val port = EngineUpstreamPort({ MeasurementSnapshot.EMPTY }, { published })
        assertFalse(port.supportsPaceMeasurement())
        published = DeviceCapabilities(supportsPaceMeasurement = true)
        assertTrue(port.supportsPaceMeasurement())
    }

    @Test
    fun `the control sink is bound to the engine provider and is inert without one`() {
        val port = EngineUpstreamPort({ MeasurementSnapshot.EMPTY }, { DeviceCapabilities.NONE })
        assertFalse(port.controlSink.canControl)
        assertTrue(port.controlSink.requestControl())
        assertFalse(port.controlSink.setTargetSpeedKph(10.0))
    }
}
