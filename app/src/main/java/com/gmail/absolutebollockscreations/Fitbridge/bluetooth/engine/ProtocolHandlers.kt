package com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine

import android.bluetooth.BluetoothGatt
import com.gmail.absolutebollockscreations.Fitbridge.BuildConfig
import timber.log.Timber
import java.util.UUID

/** Bind one received packet to its exact physical-link generation; never infer a fallback link. */
internal fun BtEngine.metricEmitterFor(
    gatt: BluetoothGatt,
    characteristicUuid: UUID,
    protocol: MetricProtocol,
    sourceClass: MetricSourceClass,
    arrivalMs: Long = MonotonicMetricClock.SYSTEM.nowMs(),
): MetricEmitter? {
    val connection = state.connectionRegistry.contextForGatt(gatt)
    if (connection == null) {
        Timber.tag(BtEngineState.TAG).w(
            "Metric emission rejected for unregistered GATT protocol=%s characteristic=%s",
            protocol,
            characteristicUuid,
        )
        return null
    }
    return MetricEmitter(
        fusion = metricFusion,
        sourceId = MetricSourceId(connection.connectionId, protocol, characteristicUuid),
        sourceClass = sourceClass,
        arrivalMs = arrivalMs,
    )
}

/** Stable factory key for one BLE protocol handler implementation. */
internal data class ProtocolKey(val value: String) {
    init {
        require(value.isNotBlank()) { "Protocol key must not be blank" }
    }

    companion object {
        val FTMS = ProtocolKey("ftms")
        val HRM = ProtocolKey("hrm")
        val RSC = ProtocolKey("rsc")
        val TEST_ECHO = ProtocolKey("test.echo.rsc")
    }
}

/**
 * The single place that says which BLE service a **sensor** handler exists for.
 *
 * ### Why this is a function rather than three `when` blocks
 *
 * Until 2026-08-07 the answer was encoded in three places: the handler list built
 * at connect time, the protocol lookup used for same-link activation, and a guard
 * in `connectSensor` that refused any new sensor link whose services were not all
 * `HeartRate`. The first two gained RSC in Phase 1. The third did not, because it
 * named a service instead of asking a question.
 *
 * The result was a split that no test caught and that reads as absurd once stated:
 * adding RSC to an **already connected** strap worked, while connecting a dedicated
 * footpod - the better sensor, and the entire point of §4a - returned false with no
 * log line. Observed with a Zwift RunPod.
 *
 * Phases 2 and 3 open CSC and CPS by returning a key here and registering the
 * handler in [HandlerFactory.standard]. There is no third place to remember.
 *
 * `null` means no sensor handler exists yet. `FTMS` is null on purpose: it is the
 * machine role, admitted through the machine path, not the secondary-sensor path.
 */
internal fun sensorProtocolFor(serviceType: BtEngineState.BtServiceType): ProtocolKey? =
    when (serviceType) {
        BtEngineState.BtServiceType.HeartRate -> ProtocolKey.HRM
        BtEngineState.BtServiceType.RSC -> ProtocolKey.RSC
        // Phase 2 and Phase 3 respectively; no handler yet.
        BtEngineState.BtServiceType.CSC,
        BtEngineState.BtServiceType.Power,
        // Not secondary sensors.
        BtEngineState.BtServiceType.FTMS,
        BtEngineState.BtServiceType.Glucose,
        -> null
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
                ProtocolKey.RSC to ::RscProtocolHandler,
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
