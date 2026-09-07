package com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine

/** Immutable UI projection of one Kayak First setup generation. */
internal data class KayakFirstSetupProgress(
    val connectionId: ConnectionId,
    val state: KayakFirstSetupState,
    val attempt: Int? = null,
    val maximumAttempts: Int = KayakFirstSetupCoordinator.MAX_ATTEMPTS,
) {
    val active: Boolean
        get() = state !in setOf(
            KayakFirstSetupState.READY,
            KayakFirstSetupState.FAILED,
            KayakFirstSetupState.CANCELLED,
        )

    companion object {
        fun from(coordinator: KayakFirstSetupCoordinator): KayakFirstSetupProgress =
            KayakFirstSetupProgress(
                connectionId = coordinator.connectionId,
                state = coordinator.state,
                attempt = coordinator.activeTransaction?.attempt,
            )
    }
}
