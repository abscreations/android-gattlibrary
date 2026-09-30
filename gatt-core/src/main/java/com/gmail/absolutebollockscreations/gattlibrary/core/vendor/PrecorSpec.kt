package com.gmail.absolutebollockscreations.gattlibrary.core.vendor

import com.gmail.absolutebollockscreations.gattlibrary.core.GattLibraryInternalApi

/**
 * Precor Spinner Chrono Power notification framing: exact frame length, invariant prefix and the companion frame observed after each measurement.
 *
 * Provenance: the values below were inherited from external projects named in the member comments and are used as published there; those projects report working hardware behavior, and FitBridge treats them as correct on that feedback. FitBridge has not independently verified a value unless its comment says so.
 */
@GattLibraryInternalApi
public object PrecorSpec {
    /** Exact frame length. Not a minimum: a 20-octet frame is not a Chrono frame. */
    public const val FRAME_LENGTH: Int = 19

    /** The invariant first three octets, `0x53 0x59 0x16`. */
    public val MAGIC_PREFIX: IntArray = intArrayOf(83, 89, 22)

    /** Known non-metric companion notification observed after each physical Chrono frame. */
    private val AUXILIARY_FRAME: ByteArray = byteArrayOf(0x24, 0x08, 0x00)

    public fun auxiliaryFrame(): ByteArray = AUXILIARY_FRAME.copyOf()

    public fun isAuxiliaryFrame(data: ByteArray): Boolean = data.contentEquals(AUXILIARY_FRAME)
}
