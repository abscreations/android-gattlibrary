package com.gmail.absolutebollockscreations.Fitbridge.bluetooth.peripheral

import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine.BluetoothIds
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.simulator.SimulatorPersona
import java.util.UUID

/** Legacy advertisement layout for a PM5 persona, kept separate from FTMS budgeting. */
data class Concept2Pm5AdvertisingPlan(
    val serviceUuid: UUID,
    val localName: String,
    val advertisingBytes: Int,
    val scanResponseBytes: Int,
) {
    init {
        require(advertisingBytes <= MAX_LEGACY_BYTES)
        require(scanResponseBytes <= MAX_LEGACY_BYTES)
    }

    companion object {
        const val MAX_LEGACY_BYTES = 31
        private const val FLAGS_STRUCTURE_BYTES = 3
        private const val UUID128_STRUCTURE_BYTES = 18
        private const val STRUCTURE_OVERHEAD_BYTES = 2

        fun forPersona(persona: SimulatorPersona.Concept2Pm5): Concept2Pm5AdvertisingPlan {
            val nameBytes = persona.advertisedName.toByteArray(Charsets.UTF_8).size
            return Concept2Pm5AdvertisingPlan(
                serviceUuid = BluetoothIds.UUID_C2_DISCOVERY_SERVICE,
                localName = persona.advertisedName,
                advertisingBytes = FLAGS_STRUCTURE_BYTES + UUID128_STRUCTURE_BYTES,
                scanResponseBytes = STRUCTURE_OVERHEAD_BYTES + nameBytes,
            )
        }
    }
}
