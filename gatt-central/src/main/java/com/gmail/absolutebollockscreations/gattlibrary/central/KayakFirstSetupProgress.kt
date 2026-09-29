package com.gmail.absolutebollockscreations.gattlibrary.central

/** Immutable UI projection of one Kayak First setup generation. */
data class KayakFirstSetupProgress(
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
        internal fun from(coordinator: KayakFirstSetupCoordinator): KayakFirstSetupProgress =
            KayakFirstSetupProgress(
                connectionId = coordinator.connectionId,
                state = coordinator.state,
                attempt = coordinator.activeTransaction?.attempt,
            )
    }
}
