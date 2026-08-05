package com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine

import com.gmail.absolutebollockscreations.Fitbridge.BuildConfig
import java.util.UUID

/** Stable factory key for one BLE protocol handler implementation. */
internal data class ProtocolKey(val value: String) {
    init {
        require(value.isNotBlank()) { "Protocol key must not be blank" }
    }

    companion object {
        val FTMS = ProtocolKey("ftms")
        val HRM = ProtocolKey("hrm")
        val TEST_ECHO = ProtocolKey("test.echo.rsc")
    }
}

/** Creates a fresh handler instance for each protocol on each physical-link context. */
internal class HandlerFactory(
    private val builders: Map<ProtocolKey, () -> BtEngineGattCore.ProtocolHandler>,
    private val companionProtocols: Map<ProtocolKey, List<ProtocolKey>> = emptyMap(),
) {
    fun create(protocol: ProtocolKey): BtEngineGattCore.ProtocolHandler =
        requireNotNull(builders[protocol]) { "No handler factory registered for ${protocol.value}" }
            .invoke()

    fun createAll(protocols: Iterable<ProtocolKey>): List<BtEngineGattCore.ProtocolHandler> =
        protocols.map(::create)

    /** Primary protocol plus explicitly configured same-link validation/companion handlers. */
    fun createForConnection(primaryProtocol: ProtocolKey): List<BtEngineGattCore.ProtocolHandler> =
        createAll((listOf(primaryProtocol) + companionProtocols[primaryProtocol].orEmpty()).distinct())

    companion object {
        fun standard(
            enablePhase05HardwareTest: Boolean = BuildConfig.PHASE_05_HARDWARE_TEST,
        ): HandlerFactory {
            val builders = mutableMapOf<ProtocolKey, () -> BtEngineGattCore.ProtocolHandler>(
                ProtocolKey.FTMS to ::FtmsProtocolHandler,
                ProtocolKey.HRM to ::HrmProtocolHandler,
            ).apply {
                if (enablePhase05HardwareTest) {
                    put(ProtocolKey.TEST_ECHO, ::TestEchoHandler)
                }
            }
            val companions = if (enablePhase05HardwareTest) {
                mapOf(ProtocolKey.HRM to listOf(ProtocolKey.TEST_ECHO))
            } else {
                emptyMap()
            }
            return HandlerFactory(builders, companions)
        }
    }
}

/** Offer discovery to every matching candidate and retain every successful activation. */
internal fun activateMatchingProtocolHandlers(
    candidates: List<BtEngineGattCore.ProtocolHandler>,
    discoveredServiceUuids: Set<UUID>,
    offer: (BtEngineGattCore.ProtocolHandler) -> Boolean,
): List<BtEngineGattCore.ProtocolHandler> =
    candidates.filter { candidate ->
        candidate.primaryServices.any(discoveredServiceUuids::contains) && offer(candidate)
    }

internal enum class HandlerOwnership {
    EXPLICIT_CHARACTERISTIC,
    PRIMARY_SERVICE,
}

internal data class ProtocolHandlerRoute(
    val owners: List<BtEngineGattCore.ProtocolHandler>,
    val ownership: HandlerOwnership?,
)

/** Resolve ownership without a fan-out fallback, which would be ambiguous with several handlers. */
internal fun resolveProtocolHandlerRoute(
    activeHandlers: Collection<BtEngineGattCore.ProtocolHandler>,
    characteristicUuid: UUID,
    serviceUuid: UUID?,
): ProtocolHandlerRoute {
    val explicitOwners = activeHandlers.filter { characteristicUuid in it.extraCharacteristics }
    if (explicitOwners.isNotEmpty()) {
        return ProtocolHandlerRoute(explicitOwners, HandlerOwnership.EXPLICIT_CHARACTERISTIC)
    }

    val serviceOwners = if (serviceUuid == null) {
        emptyList()
    } else {
        activeHandlers.filter { serviceUuid in it.primaryServices }
    }
    if (serviceOwners.isNotEmpty()) {
        return ProtocolHandlerRoute(serviceOwners, HandlerOwnership.PRIMARY_SERVICE)
    }

    return ProtocolHandlerRoute(emptyList(), ownership = null)
}
