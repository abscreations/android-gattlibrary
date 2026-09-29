package com.gmail.absolutebollockscreations.gattlibrary.core

/**
 * CPU keep-awake hook supplied by the host app (LIB-7/S7).
 *
 * The BLE libraries do not own a wake lock. An app usually has several features that need the
 * CPU awake at once (FitBridge: RunMode and the data simulator), and their ownership must share one
 * registry so that one finishing cannot release the lock under the other. The app therefore
 * implements this, typically by delegating to its own process-wide wake-lock owner.
 *
 * [owner] is an identity token: acquiring twice with the same owner is one hold, and only that
 * owner's release drops it.
 */
interface KeepAwake {
    fun acquireCpu(owner: Any)

    fun releaseCpu(owner: Any)
}
