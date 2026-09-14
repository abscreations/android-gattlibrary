package com.gmail.absolutebollockscreations.Fitbridge.bluetooth.peripheral

import org.junit.Assert.assertEquals
import org.junit.Test

class FtmsClientStatusTest {
    @Test
    fun `ready requires advertising and no prior client`() {
        val idle = FtmsPeripheralController.PeripheralStatus()
        assertEquals(FtmsClientConnectionStatus.DISCONNECTED, idle.clientConnectionStatus())
        assertEquals(FtmsClientConnectionStatus.READY, idle.copy(clientAdvertising = true).clientConnectionStatus())
        assertEquals(FtmsClientConnectionStatus.DISCONNECTED,
            idle.copy(clientAdvertising = true, clientEverConnected = true).clientConnectionStatus())
    }

    @Test
    fun `connected wins over advertising state and connection history`() {
        for (advertising in listOf(false, true)) {
            assertEquals(FtmsClientConnectionStatus.CONNECTED,
                FtmsPeripheralController.PeripheralStatus(clientLinkConnected = true, clientAdvertising = advertising)
                    .clientConnectionStatus())
        }
    }

    @Test
    fun `shared non FTMS peripheral connection is not an FTMS client`() {
        assertEquals(FtmsClientConnectionStatus.DISCONNECTED,
            FtmsPeripheralController.PeripheralStatus(connected = true, advertising = true).clientConnectionStatus())
    }

    @Test
    fun `control label follows published capabilities until command receipt`() {
        val idle = FtmsPeripheralController.PeripheralStatus()
        assertEquals(FtmsClientControlStatus.NOT_CONTROLLABLE, idle.clientControlStatus())
        assertEquals(FtmsClientControlStatus.CONTROLLABLE,
            idle.copy(clientControllable = true).clientControlStatus())
        for (controllable in listOf(false, true)) {
            assertEquals(FtmsClientControlStatus.CLIENT_IS_CONTROLLING,
                idle.copy(clientControllable = controllable, clientControlReceived = true).clientControlStatus())
        }
    }
}
