package com.gmail.absolutebollockscreations.gattlibrary.server

/**
 * Pure ATT/CCCD validation helpers, kept free of Android classes for JVM tests.
 *
 * Android hands offsets and raw CCCD bytes to the server callback but does not
 * enforce application-level semantics. Centralizing them here keeps reads and
 * subscription writes consistent across characteristics and returns ATT status
 * codes instead of throwing from a Bluetooth binder callback.
 *
 * The previous FitBridge advertiser ignored the read offset entirely (so a long
 * read was answered with the whole value again) and accepted any CCCD byte
 * pattern, including combined notify+indicate on a notify-only characteristic.
 */
internal object GattProtocol {
    const val SUCCESS = 0x00
    const val REQUEST_NOT_SUPPORTED = 0x06
    const val INVALID_OFFSET = 0x07
    const val INVALID_ATTRIBUTE_LENGTH = 0x0D
    const val CCCD_IMPROPERLY_CONFIGURED = 0xFD

    data class ReadResult(val status: Int, val value: ByteArray?)

    data class ControlPointWriteValidation(
        val status: Int,
        /** True only for an ATT Write Request; commands have no ATT response. */
        val sendAttResponse: Boolean,
    )

    /**
     * Validate either ATT transport accepted by the FTMS Control Point.
     *
     * [responseNeeded] distinguishes a Write Request from Garmin Connect IQ's
     * Write Command. It deliberately does not affect validity: a valid command
     * skips only the ATT Write Response and still receives the FTMS procedure
     * result through the subscribed Control Point indication.
     */
    fun validateControlPointWrite(
        responseNeeded: Boolean,
        preparedWrite: Boolean,
        offset: Int,
        controlPointSubscribed: Boolean,
    ): ControlPointWriteValidation {
        val status = when {
            preparedWrite -> REQUEST_NOT_SUPPORTED
            offset != 0 -> INVALID_OFFSET
            !controlPointSubscribed -> CCCD_IMPROPERLY_CONFIGURED
            else -> SUCCESS
        }
        return ControlPointWriteValidation(status, sendAttResponse = responseNeeded)
    }

    fun readSlice(payload: ByteArray, offset: Int): ReadResult = when {
        offset < 0 -> ReadResult(INVALID_OFFSET, null)
        offset > payload.size -> ReadResult(INVALID_OFFSET, null)
        offset == payload.size -> ReadResult(SUCCESS, ByteArray(0))
        else -> ReadResult(SUCCESS, payload.copyOfRange(offset, payload.size))
    }

    fun decodeCccd(
        value: ByteArray,
        supportsNotify: Boolean,
        supportsIndicate: Boolean,
    ): PeripheralConnection.CccdMode? {
        // CCCDs are exactly one little-endian 16-bit value. Reject combined or
        // reserved bits rather than silently subscribing a client to a mode the
        // characteristic cannot deliver.
        if (value.size != 2) return null
        return when ((value[0].toInt() and 0xFF) or ((value[1].toInt() and 0xFF) shl 8)) {
            0x0000 -> PeripheralConnection.CccdMode.DISABLED
            0x0001 -> PeripheralConnection.CccdMode.NOTIFY.takeIf { supportsNotify }
            0x0002 -> PeripheralConnection.CccdMode.INDICATE.takeIf { supportsIndicate }
            else -> null
        }
    }
}
