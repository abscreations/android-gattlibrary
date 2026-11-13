package com.gmail.absolutebollockscreations.gattlibrary.central

import com.gmail.absolutebollockscreations.gattlibrary.core.ActivityType
import com.gmail.absolutebollockscreations.gattlibrary.core.GattLibraryInternalApi
import com.gmail.absolutebollockscreations.gattlibrary.core.ftms.FtmsMachineType

/**
 * Reads the optimistic activity hint carried in an advertised FTMS Service Data payload.
 *
 * Android returns only the three bytes following the 0x1826 UUID: Flags + little-endian Fitness
 * Machine Type (FTMS v1.0 section 3.1). If a device sets several known type bits, the priority
 * matches connected GATT discovery. Central-owned reader (LIB-19h); the server builds the same
 * payload independently in `peripheral.BtServiceProfiles.ftmsServiceDataFor`.
 */
@GattLibraryInternalApi
internal object FtmsServiceDataParser {
    fun activityType(payload: ByteArray?): ActivityType? {
        if (payload == null || payload.size < 3) return null
        val machineTypes = (payload[1].toInt() and 0xFF) or
            ((payload[2].toInt() and 0xFF) shl 8)
        return when {
            machineTypes and FtmsMachineType.FM_TYPE_TREADMILL != 0 -> ActivityType.TREADMILL
            machineTypes and FtmsMachineType.FM_TYPE_ROWER != 0 -> ActivityType.ROWING
            machineTypes and FtmsMachineType.FM_TYPE_INDOOR_BIKE != 0 -> ActivityType.BIKING
            machineTypes and FtmsMachineType.FM_TYPE_CROSS_TRAINER != 0 -> ActivityType.ELLIPTICAL
            machineTypes and FtmsMachineType.FM_TYPE_STEP_CLIMBER != 0 -> ActivityType.STAIRSTEPPER
            machineTypes and FtmsMachineType.FM_TYPE_STAIR_CLIMBER != 0 -> ActivityType.STAIRCLIMBER
            else -> null
        }
    }
}
