package com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine

import android.Manifest
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattService
import androidx.annotation.RequiresPermission
import timber.log.Timber
import java.util.TimeZone
import java.util.UUID

internal data class KayakFirstCharacteristicInventory(
    val properties: Int,
    val hasCccd: Boolean,
)

internal data class KayakFirstGattInventory(
    val services: Map<UUID, Map<UUID, KayakFirstCharacteristicInventory>>,
)

internal enum class KayakFirstDiscoveryFailure {
    ADVERTISEMENT_CANDIDATE_MISSING,
    MISSING_SERVICE,
    MISSING_IO,
    IO_NOT_NOTIFY,
    IO_NOT_WRITE_WITHOUT_RESPONSE,
    IO_MISSING_CCCD,
}

internal data class KayakFirstDiscoveryPlan(
    val activate: Boolean,
    val failure: KayakFirstDiscoveryFailure?,
    val notificationTarget: CccdTarget?,
)

/** FFE0 alone is generic; activation also requires the exact same-parent FFE1 surface. */
internal fun planKayakFirstDiscovery(
    advertisementCandidateId: String?,
    inventory: KayakFirstGattInventory,
): KayakFirstDiscoveryPlan {
    fun reject(reason: KayakFirstDiscoveryFailure) =
        KayakFirstDiscoveryPlan(false, reason, null)

    if (advertisementCandidateId != KAYAK_FIRST_CANDIDATE_ID) {
        return reject(KayakFirstDiscoveryFailure.ADVERTISEMENT_CANDIDATE_MISSING)
    }
    val service = inventory.services[BluetoothIds.UUID_KAYAK_FIRST_SERVICE]
        ?: return reject(KayakFirstDiscoveryFailure.MISSING_SERVICE)
    val io = service[BluetoothIds.UUID_KAYAK_FIRST_IO]
        ?: return reject(KayakFirstDiscoveryFailure.MISSING_IO)
    if (io.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY == 0) {
        return reject(KayakFirstDiscoveryFailure.IO_NOT_NOTIFY)
    }
    if (io.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE == 0) {
        return reject(KayakFirstDiscoveryFailure.IO_NOT_WRITE_WITHOUT_RESPONSE)
    }
    if (!io.hasCccd) return reject(KayakFirstDiscoveryFailure.IO_MISSING_CCCD)

    return KayakFirstDiscoveryPlan(
        activate = true,
        failure = null,
        notificationTarget = CccdTarget(
            BluetoothIds.UUID_KAYAK_FIRST_SERVICE,
            BluetoothIds.UUID_KAYAK_FIRST_IO,
        ),
    )
}

internal fun List<BluetoothGattService>.toKayakFirstInventory(): KayakFirstGattInventory =
    KayakFirstGattInventory(
        associate { service ->
            service.uuid to service.characteristics.orEmpty().associate { characteristic ->
                characteristic.uuid to KayakFirstCharacteristicInventory(
                    properties = characteristic.properties,
                    hasCccd = characteristic.getDescriptor(BluetoothIds.UUID_CCCD) != null,
                )
            }
        },
    )

/**
 * Thin Android binding for the pure Kayak First setup coordinator.
 *
 * This phase intentionally stops at readiness: it does not poll, emit metrics, signal sessions,
 * start a downstream provider, or expose a control route. Every byte written comes from the typed
 * D98/D99 codec and traverses the owning connection's shared GATT queue.
 */
internal class KayakFirstProtocolHandler(
    private val displayConfiguration: KayakFirstDisplayConfiguration =
        KayakFirstDisplayConfiguration.DEFAULT,
) : BtEngineGattCore.ProtocolHandler {
    override val primaryServices: Set<UUID> = setOf(BluetoothIds.UUID_KAYAK_FIRST_SERVICE)
    override val extraCharacteristics: Set<UUID> = setOf(BluetoothIds.UUID_KAYAK_FIRST_IO)

    private var assembler = KayakFirstFrameAssembler()
    @Volatile
    private var coordinator: KayakFirstSetupCoordinator? = null
    @Volatile
    private var structuralMatch = false
    @Volatile
    private var protocolConfirmed = false

    internal fun hasStructuralMatch(): Boolean = structuralMatch
    internal fun isProtocolConfirmed(): Boolean = protocolConfirmed
    internal fun setupState(): KayakFirstSetupState =
        coordinator?.state ?: KayakFirstSetupState.DISCOVERING
    internal fun setupCoordinator(): KayakFirstSetupCoordinator? = coordinator

    internal fun recordStructuralMatch(
        connectionId: ConnectionId,
        nowMs: Long = 0L,
    ) {
        structuralMatch = true
        coordinator = KayakFirstSetupCoordinator(connectionId, displayConfiguration).also {
            it.beginSubscription(nowMs)
        }
    }

    override fun onConnected(engine: BtEngine, gatt: BluetoothGatt) {
        resetLinkState(disconnected = false)
    }

    override fun onDisconnected(engine: BtEngine) {
        resetLinkState(disconnected = true)
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    override fun onServicesDiscovered(
        engine: BtEngine,
        gatt: BluetoothGatt,
        services: List<BluetoothGattService>,
    ): Boolean {
        val context = engine.state.connectionRegistry.contextForGatt(gatt) ?: return false
        val candidateId = engine.state.discoveredDevices.value.values.firstOrNull { device ->
            canonicalScanAddress(device.address) == canonicalScanAddress(context.address)
        }?.matchCandidateId
        val plan = planKayakFirstDiscovery(candidateId, services.toKayakFirstInventory())
        if (!plan.activate) {
            Timber.tag(BtEngineState.TAG).w(
                "Kayak First discovery declined generation=%d reason=%s",
                context.connectionId.linkGeneration,
                plan.failure,
            )
            resetLinkState(disconnected = false)
            return false
        }

        val service = services.first { it.uuid == BluetoothIds.UUID_KAYAK_FIRST_SERVICE }
        val io = requireNotNull(service.getCharacteristic(BluetoothIds.UUID_KAYAK_FIRST_IO))
        recordStructuralMatch(context.connectionId, MonotonicMetricClock.SYSTEM.nowMs())
        engine.enableNotifications(context, io)
        return true
    }

    override fun onCccdPlanReady(engine: BtEngine, gatt: BluetoothGatt) {
        val context = engine.state.connectionRegistry.contextForGatt(gatt) ?: return
        if (!context.cccd.ready.value) return
        if (
            engine.state.adapterLiveness.selectedAdapterId(context.connectionId) !=
            AdapterLivenessCoordinator.KAYAK_FIRST_ADAPTER_ID
        ) {
            // Native FTMS (or another stronger adapter) owns this link. Never probe a generic
            // FFE0 surface behind the selected machine adapter.
            coordinator?.cancel()
            return
        }
        val wallClockMs = System.currentTimeMillis()
        val actions = coordinator?.onSubscribed(
            context.connectionId,
            unixEpochSeconds = wallClockMs / 1_000L,
            timezoneOffsetMinutes = TimeZone.getDefault().getOffset(wallClockMs) / 60_000,
            nowMs = MonotonicMetricClock.SYSTEM.nowMs(),
        ).orEmpty()
        applyActions(engine, context, actions)
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    override fun onCharacteristicChanged(
        engine: BtEngine,
        gatt: BluetoothGatt,
        ch: BluetoothGattCharacteristic,
        value: ByteArray,
    ) {
        if (ch.uuid != BluetoothIds.UUID_KAYAK_FIRST_IO) return
        val context = engine.state.connectionRegistry.contextForGatt(gatt) ?: return
        val setup = coordinator ?: return
        if (setup.connectionId != context.connectionId) return
        val nowMs = MonotonicMetricClock.SYSTEM.nowMs()

        assembler.accept(value).forEach { event ->
            val actions = when (event) {
                KayakFirstFrameEvent.BinaryResetAcknowledgement -> {
                    val before = setup.completedTransactions.size
                    setup.onBinaryResetAcknowledgement(context.connectionId, nowMs).also {
                        if (setup.completedTransactions.size > before) protocolConfirmed = true
                    }
                }
                is KayakFirstFrameEvent.TextLine -> onTextLine(setup, context.connectionId, event.value, nowMs)
                is KayakFirstFrameEvent.Rejected -> {
                    Timber.tag(BtEngineState.TAG).w(
                        "Kayak First frame rejected generation=%d reason=%s len=%d",
                        context.connectionId.linkGeneration,
                        event.reason,
                        value.size,
                    )
                    emptyList()
                }
            }
            applyActions(engine, context, actions)
        }
    }

    override fun onLivenessTick(engine: BtEngine, gatt: BluetoothGatt) {
        val context = engine.state.connectionRegistry.contextForGatt(gatt) ?: return
        val setup = coordinator ?: return
        if (setup.connectionId != context.connectionId) return
        applyActions(engine, context, setup.tick(MonotonicMetricClock.SYSTEM.nowMs()))
    }

    private fun onTextLine(
        setup: KayakFirstSetupCoordinator,
        connectionId: ConnectionId,
        line: String,
        nowMs: Long,
    ): List<KayakFirstSetupAction> {
        val decoded = KayakFirstResponseDecoder.decode(line)
        if (decoded !is KayakFirstLineDecodeResult.Decoded) {
            Timber.tag(BtEngineState.TAG).w(
                "Kayak First response rejected generation=%d reason=%s",
                connectionId.linkGeneration,
                (decoded as KayakFirstLineDecodeResult.Rejected).reason,
            )
            return emptyList()
        }
        if (
            decoded.response.kind == KayakFirstResponseKind.POLL &&
            KayakFirstMeasurement.decode(line) is KayakFirstMeasurementDecodeResult.Decoded
        ) {
            protocolConfirmed = true
        }
        val before = setup.completedTransactions.size
        return setup.onTextResponse(connectionId, decoded.response, nowMs).also {
            if (setup.completedTransactions.size > before &&
                setup.completedTransactions.last().terminalResult == KayakFirstTransactionResult.SUCCESS
            ) {
                protocolConfirmed = true
            }
        }
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun applyActions(
        engine: BtEngine,
        context: ConnectionContext,
        actions: List<KayakFirstSetupAction>,
    ) {
        actions.forEach { action ->
            when (action) {
                is KayakFirstSetupAction.Send -> dispatch(engine, context, action.transaction)
                KayakFirstSetupAction.Ready -> {
                    protocolConfirmed = true
                    engine.state.adapterLiveness.setupSucceeded(
                        context,
                        MonotonicMetricClock.SYSTEM.nowMs(),
                    )
                }
                is KayakFirstSetupAction.Failed -> {
                    engine.state.adapterLiveness.setupFailed(
                        context,
                        "Kayak First setup failed: ${action.reason.name.lowercase()}",
                    )
                    Timber.tag(BtEngineState.TAG).w(
                        "Kayak First setup failed generation=%d reason=%s",
                        context.connectionId.linkGeneration,
                        action.reason,
                    )
                }
            }
        }
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun dispatch(
        engine: BtEngine,
        context: ConnectionContext,
        transaction: KayakFirstTransaction,
    ) {
        if (context.connectionId != transaction.connectionId) return
        if (context.gatt == null) {
            applyActions(
                engine,
                context,
                coordinator?.onWriteCompleted(
                    transaction.connectionId,
                    transaction.kind,
                    transaction.attempt,
                    success = false,
                    nowMs = MonotonicMetricClock.SYSTEM.nowMs(),
                ).orEmpty(),
            )
            return
        }
        lateinit var issueChunk: (Int) -> Unit
        issueChunk = { index ->
            engine.pushWrite(
                context,
                transaction.chunks[index],
                BluetoothIds.UUID_KAYAK_FIRST_IO,
                writeMode = StackEntry.WriteMode.WITHOUT_RESPONSE,
                onComplete = completion@{ success ->
                    val current = engine.state.connectionRegistry.contextFor(transaction.connectionId)
                    if (current !== context) return@completion
                    if (success && index + 1 < transaction.chunks.size) {
                        issueChunk(index + 1)
                    } else {
                        applyActions(
                            engine,
                            context,
                            coordinator?.onWriteCompleted(
                                transaction.connectionId,
                                transaction.kind,
                                transaction.attempt,
                                success,
                                MonotonicMetricClock.SYSTEM.nowMs(),
                            ).orEmpty(),
                        )
                    }
                },
            )
        }
        issueChunk(0)
    }

    private fun resetLinkState(disconnected: Boolean) {
        coordinator?.cancel(disconnected)
        coordinator = null
        assembler.reset()
        assembler = KayakFirstFrameAssembler()
        structuralMatch = false
        protocolConfirmed = false
    }
}
