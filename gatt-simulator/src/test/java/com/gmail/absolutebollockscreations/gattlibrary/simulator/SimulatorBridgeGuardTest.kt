package com.gmail.absolutebollockscreations.gattlibrary.simulator

import android.bluetooth.BluetoothProfile
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** L17 (LIB-9c): a machine link starting stops the simulator; idle or closing links do not. */
class SimulatorBridgeGuardTest {

    @Test
    fun `connecting and connected machine links count as a bridge`() {
        assertTrue(SimulatorBridgeGuard.upstreamLinkActive(BluetoothProfile.STATE_CONNECTING))
        assertTrue(SimulatorBridgeGuard.upstreamLinkActive(BluetoothProfile.STATE_CONNECTED))
    }

    @Test
    fun `disconnected and disconnecting links do not`() {
        assertFalse(SimulatorBridgeGuard.upstreamLinkActive(BluetoothProfile.STATE_DISCONNECTED))
        assertFalse(SimulatorBridgeGuard.upstreamLinkActive(BluetoothProfile.STATE_DISCONNECTING))
    }

    @Test
    fun `simulator watches the machine link and refuses to start during one`() {
        // FtmsSimulator needs Android peripherals to construct, so its wiring is checked at source.
        val source = sequenceOf(
            File("gatt-simulator/src/main/java/com/gmail/absolutebollockscreations/gattlibrary/simulator/FtmsSimulator.kt"),
            File("src/main/java/com/gmail/absolutebollockscreations/gattlibrary/simulator/FtmsSimulator.kt"),
        ).first(File::isFile).readText().replace("\r\n", "\n")
        assertTrue(source.contains("engineState.connectionState.collect"))
        assertTrue(source.contains("SimulatorBridgeGuard.upstreamLinkActive(linkState)) stopForBridge()"))
        // The refusal is the first thing start(persona, ...) does, before any persona branch.
        val startBody = source.substringAfter("concept2Scenario: Concept2Scenario = Concept2Scenario.STEADY,\n    ) {")
        assertTrue(startBody.trimStart().startsWith("if (bridgeLinkActive())"))
    }
}
