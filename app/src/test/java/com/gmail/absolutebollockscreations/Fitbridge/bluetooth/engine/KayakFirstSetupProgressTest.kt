package com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class KayakFirstSetupProgressTest {
    @Test
    fun stale_generation_cannot_replace_or_clear_current_progress() {
        val engineState = BtEngineState()
        val old = ConnectionId("AA:BB:CC:DD:EE:01", 4)
        val current = ConnectionId("AA:BB:CC:DD:EE:02", 5)

        engineState.setKayakFirstSetupProgress(
            KayakFirstSetupProgress(current, KayakFirstSetupState.HANDSHAKE, attempt = 2),
        )
        engineState.setKayakFirstSetupProgress(
            KayakFirstSetupProgress(old, KayakFirstSetupState.RESET_A, attempt = 1),
        )
        engineState.clearKayakFirstSetupProgress(old)

        assertEquals(current, engineState.kayakFirstSetupProgress.value?.connectionId)
        assertEquals(KayakFirstSetupState.HANDSHAKE, engineState.kayakFirstSetupProgress.value?.state)
        engineState.clearKayakFirstSetupProgress(current)
        assertNull(engineState.kayakFirstSetupProgress.value)
    }
}
