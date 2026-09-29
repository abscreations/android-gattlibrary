package com.gmail.absolutebollockscreations.gattlibrary.simulator

internal fun simulatorGenerationReady(
    isKeiser: Boolean,
    isKayakFirst: Boolean,
    subscribed: Boolean,
    kayakFirstSetupReady: Boolean,
): Boolean = when {
    isKeiser -> true
    isKayakFirst -> subscribed && kayakFirstSetupReady
    else -> subscribed
}

internal data class KayakFirstGenerationState(
    val ready: Boolean,
    val advancing: Boolean,
)

internal fun kayakFirstGenerationState(
    subscribed: Boolean,
    setupStage: String,
    running: Boolean,
): KayakFirstGenerationState {
    val ready = subscribed && setupStage == "ready"
    return KayakFirstGenerationState(ready = ready, advancing = ready && running)
}

/** Latches the first persona-specific readiness signal for one simulator run. */
internal class SimulatorGenerationGate {
    private var armed = false

    @Synchronized
    fun observe(subscribed: Boolean): Boolean {
        if (subscribed) armed = true
        return armed
    }

    @Synchronized
    fun reset() {
        armed = false
    }
}
