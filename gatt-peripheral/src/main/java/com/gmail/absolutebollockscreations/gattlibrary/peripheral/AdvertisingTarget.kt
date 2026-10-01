package com.gmail.absolutebollockscreations.gattlibrary.peripheral

import com.gmail.absolutebollockscreations.gattlibrary.central.DeviceCapabilities
import com.gmail.absolutebollockscreations.gattlibrary.core.ftms.FtmsMachineProfile

/**
 * What the advertising coordinator drives: the thing that actually puts the downstream FTMS bridge
 * on the air. It replaces the central `BtServiceControlSink`, which the engine used to call.
 */
public interface AdvertisingTarget {
    /**
     * Start (or reconfigure) the bridge as [profile] with the controls [capabilities] describes.
     * Returns true only once the platform has confirmed the advertisement is on the air; false means
     * "not ready yet" and the caller retries after a backoff. May block briefly; never call it from
     * the main thread.
     */
    public fun startFtms(profile: FtmsMachineProfile, capabilities: DeviceCapabilities): Boolean

    /** Stop advertising. Safe to call when nothing is advertising. */
    public fun stopAdvertising()
}
