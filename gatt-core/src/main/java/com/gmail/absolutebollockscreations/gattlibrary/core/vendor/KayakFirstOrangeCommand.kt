package com.gmail.absolutebollockscreations.gattlibrary.core.vendor

import com.gmail.absolutebollockscreations.gattlibrary.core.GattLibraryInternalApi

/**
 * Orange console serial commands: one ASCII digit each. Provenance: the values below were inherited from external projects named in the member comments and are used as published there; those projects report working hardware behavior, and FitBridge treats them as correct on that feedback. FitBridge has not independently verified a value unless its comment says so.
 */
@GattLibraryInternalApi
public enum class KayakFirstOrangeCommand(public val wire: Byte) {
    T_MIN('1'.code.toByte()),
    T_H('2'.code.toByte()),
    T_MAX('3'.code.toByte()),
    T_V('4'.code.toByte()),
    AUXILIARY('5'.code.toByte()),
    RESET('9'.code.toByte());

    public fun bytes(): ByteArray = byteArrayOf(wire)

    public companion object {
        public fun fromLine(line: String): KayakFirstOrangeCommand? =
            entries.firstOrNull { line.length == 1 && it.wire == line[0].code.toByte() }
    }
}
