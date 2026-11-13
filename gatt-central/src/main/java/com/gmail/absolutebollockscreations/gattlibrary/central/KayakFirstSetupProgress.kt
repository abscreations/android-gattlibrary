package com.gmail.absolutebollockscreations.gattlibrary.central

/** Engine record of one Kayak First setup generation, keyed by its physical-link generation. */
internal data class KayakFirstSetupProgressState(
    val connectionId: ConnectionId,
    val state: KayakFirstSetupState,
    val attempt: Int? = null,
    val maximumAttempts: Int = KayakFirstSetupCoordinator.MAX_ATTEMPTS,
) {
    companion object {
        fun from(coordinator: KayakFirstSetupCoordinator): KayakFirstSetupProgressState =
            KayakFirstSetupProgressState(
                connectionId = coordinator.connectionId,
                state = coordinator.state,
                attempt = coordinator.activeTransaction?.attempt,
            )
    }
}

/**
 * Progress of the Kayak First setup handshake, for display. [link] is the connection the setup runs on: the
 * same handle as that link's [ConnectedLink.handle], or null if the link is not (or no longer) in
 * [FitnessGattClient.connections]. LIB-80 replaced the engine-internal connection id with this handle.
 */
public class KayakFirstSetupProgress internal constructor(
    public val link: LinkHandle?,
    public val state: KayakFirstSetupState,
    public val attempt: Int?,
    public val maximumAttempts: Int,
) {
    /** True until the setup reaches READY, FAILED or CANCELLED. */
    public val active: Boolean
        get() = state !in setOf(
            KayakFirstSetupState.READY,
            KayakFirstSetupState.FAILED,
            KayakFirstSetupState.CANCELLED,
        )

    override fun equals(other: Any?): Boolean =
        other is KayakFirstSetupProgress && other.link === link && other.state == state &&
            other.attempt == attempt && other.maximumAttempts == maximumAttempts

    override fun hashCode(): Int =
        31 * (31 * (31 * System.identityHashCode(link) + state.hashCode()) + (attempt ?: -1)) + maximumAttempts

    override fun toString(): String =
        "KayakFirstSetupProgress(link=${link != null}, state=$state, attempt=$attempt, maximumAttempts=$maximumAttempts)"
}
