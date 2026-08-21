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
        val CSC = ProtocolKey("csc")
        val CPS = ProtocolKey("cps")

        /**
         * Concept2 PM, proprietary (plan §5a). A **machine** protocol like FTMS, not a
         * secondary sensor, so it is absent from [sensorProtocolFor] by the same reasoning.
         *
         * Offered on every machine connection via [MACHINE_PROTOCOLS] since 2026-08-12, and
         * activated only when the Erg service is actually present. Before that it was
         * registered and buildable but never constructed — see [MACHINE_PROTOCOLS].
         *
         * Which adapter drives a PM5 advertising both this and FTMS remains the D6 conflict
         * rule, answered by `MachineAdapterPolicy` rather than by the handler registry.
         */
        val CONCEPT2 = ProtocolKey("concept2")

        /**
         * Precor Spinner Chrono Power, proprietary (plan §5b). A machine protocol, so it is
         * absent from [sensorProtocolFor] for the same reason as FTMS and Concept2.
         *
         * Unlike Concept2 there is no standard-versus-proprietary contest to resolve: a Chrono
         * advertises its vendor service and no FTMS, so `MachineAdapterPolicy` sees one
         * candidate and D6 never arises.
         */
        val PRECOR = ProtocolKey("precor")

        /**
         * Schwinn x70 — 170 / 270 / 570, proprietary (plan §7). A machine protocol, so it is
         * absent from [sensorProtocolFor] for the same reason as FTMS, Concept2 and Precor.
         *
         * One key for two consoles. A 170 and a 270 share a service and an initialization
         * command and share no measurement stream at all, but nothing at discovery time
         * distinguishes them — both report the identity `SCHWINN 170/270` — so the split lives
         * inside the handler where the notifications reveal it, not in the registry.
         *
         * Like Precor and unlike Concept2 there is no standard-versus-proprietary contest: an
         * x70 advertises its vendor service and no FTMS, so D6 never arises.
         */
        val SCHWINN_X70 = ProtocolKey("schwinn.x70")

        /**
         * FE-C over BLE, generic (plan §7 of `docs/fe-c-ble_central_implementation.md`). A
         * machine protocol, absent from [sensorProtocolFor] for the same reason as FTMS,
         * Concept2, Precor and Schwinn x70.
         *
         * One key regardless of persona (Trainer/Bike/Treadmill/Rower/Elliptical/Climber/
         * Nordic Skier): the ANT+ FE-C page 16 equipment-type byte, not the GATT surface,
         * is what tells personas apart, and that only becomes available after the first
         * General FE Data notification.
         */
        val FEC = ProtocolKey("fec.ble")
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
        BtEngineState.BtServiceType.CSC -> ProtocolKey.CSC
        BtEngineState.BtServiceType.Power -> ProtocolKey.CPS
        // Not secondary sensors.
        BtEngineState.BtServiceType.FTMS,
        BtEngineState.BtServiceType.Glucose,
        -> null
    }

/**
 * The machine-role protocols, offered together on every machine connection.
 *
 * The counterpart to [sensorProtocolFor], and it exists for the same reason: the answer to
 * "which handlers does a machine link get?" was previously written inline as `ProtocolKey.FTMS`
 * at the single call site, so Phase 5a could add a fully working Concept2 adapter — decoders,
 * matcher, adapter candidate, ranking — that no connection ever constructed.
 *
 * Offering all of them is safe because activation is service-gated: a handler whose
 * `primaryServices` are absent from the discovered GATT database is discarded by
 * [activateMatchingProtocolHandlers] before it sees a packet. The cost of an unused candidate
 * is one short-lived object per connection; the cost of a forgotten one is a family that
 * silently produces nothing.
 *
 * Order is FTMS first for readability only. Which adapter *drives* a link that matches more
 * than one is D6's question and `MachineAdapterPolicy`'s answer, not this list's.
 */
internal val MACHINE_PROTOCOLS: List<ProtocolKey> = listOf(
    ProtocolKey.FTMS,
    ProtocolKey.CONCEPT2,
    ProtocolKey.PRECOR,
    ProtocolKey.SCHWINN_X70,
    ProtocolKey.FEC,
)

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
                ProtocolKey.CSC to { CscProtocolHandler() },
                ProtocolKey.CPS to { CpsProtocolHandler() },
                ProtocolKey.CONCEPT2 to { Concept2ProtocolHandler() },
                ProtocolKey.PRECOR to { PrecorProtocolHandler() },
                ProtocolKey.SCHWINN_X70 to { SchwinnX70ProtocolHandler() },
                ProtocolKey.FEC to { FeCOverBleProtocolHandler() },
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
