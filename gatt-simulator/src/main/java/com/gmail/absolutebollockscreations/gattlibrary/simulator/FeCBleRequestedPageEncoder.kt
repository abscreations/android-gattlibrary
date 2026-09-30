package com.gmail.absolutebollockscreations.gattlibrary.simulator

import com.gmail.absolutebollockscreations.gattlibrary.core.ids.FecBleUuids

/** Pure selection/encoding seam for Common Page 70 responses sent on FEC2. */
internal object FeCBleRequestedPageEncoder {
    fun encode(
        requestedPage: Int,
        messageType: Int,
        identity: FeCBleSimulatorIdentity,
        state: FeCBleMachineState,
    ): ByteArray? = when (requestedPage) {
        FecBleUuids.FEC_PAGE_FE_CAPABILITIES ->
            FeCBleTelemetryEncoder.encodePage54Frame(state, messageType)

        FecBleUuids.FEC_PAGE_MANUFACTURER_INFORMATION -> {
            val hardware = identity.antHardwareRevision ?: return null
            val manufacturer = identity.antManufacturerId ?: return null
            val model = identity.antModelNumber ?: return null
            FeCBleTelemetryEncoder.encodePage80Frame(
                hardwareRevision = hardware,
                manufacturerId = manufacturer,
                modelNumber = model,
                messageType = messageType,
            )
        }

        else -> null
    }
}
