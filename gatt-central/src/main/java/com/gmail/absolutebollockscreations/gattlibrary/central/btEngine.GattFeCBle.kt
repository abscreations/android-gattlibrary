package com.gmail.absolutebollockscreations.gattlibrary.central

import android.Manifest
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattService
import androidx.annotation.RequiresPermission
import timber.log.Timber
import java.util.UUID
import com.gmail.absolutebollockscreations.gattlibrary.core.ids.FecBleUuids

internal enum class FeCGattOperationKind { ENABLE_NOTIFICATIONS }

internal data class FeCGattOperation(
    val kind: FeCGattOperationKind,
    val characteristicUuid: UUID,
)

internal data class FeCDiscoveryPlan(
    val activate: Boolean,
    val controlAvailable: Boolean,
    val operations: List<FeCGattOperation>,
)

/** Pure FE-C inventory decision used by production discovery and §11.2 tests. */
internal fun planFeCDiscovery(
    servicePresent: Boolean,
    characteristicUuids: Set<UUID>,
): FeCDiscoveryPlan {
    if (!servicePresent || FecBleUuids.UUID_FEC_MEASUREMENT !in characteristicUuids) {
        return FeCDiscoveryPlan(
            activate = false,
            controlAvailable = false,
            operations = emptyList(),
        )
    }
    return FeCDiscoveryPlan(
        activate = true,
        controlAvailable = FecBleUuids.UUID_FEC_CONTROL in characteristicUuids,
        operations = listOf(
            FeCGattOperation(
                FeCGattOperationKind.ENABLE_NOTIFICATIONS,
                FecBleUuids.UUID_FEC_MEASUREMENT,
            ),
        ),
    )
}

internal fun feCCapabilityResponseTimedOut(
    deadlineMs: Long?,
    nowMs: Long,
    alreadyResolved: Boolean,
): Boolean = !alreadyResolved && deadlineMs != null && nowMs >= deadlineMs

/**
 * FE-C over BLE protocol handler, generic across every FE-C persona (Trainer, Bike, Treadmill,
 * Rower, Elliptical, Climber, Nordic Skier) — plan §7/§9 of
 * `docs/fe-c-ble_central_implementation.md`.
 *
 * ## Minimum compatibility, not a Tacx driver
 *
 * `primaryServices` is `{FEC1}` alone. A device serving FEC1 + FEC2 (notify) is a valid
 * measurement-only match — this handler does not require CPS/FTMS/CSC/HRM to activate, unlike
 * Incyclist's `getRequiredCharacteristics()`, which the plan names as a defect to not repeat
 * (plan §2.1, §3.2). FEC3 (control, write-without-response) is optional: its absence means a
 * measurement-only device, not a failed match.
 *
 * ## Discovery does not write
 *
 * Per plan §7: FEC1 → find FEC2 (fail activation if absent) → find FEC3 (optional) → enable
 * notifications on FEC2 only → bind a [GattTransport] and [FeCProtocolSession] for later use.
 * No control page is ever sent from this function.
 *
 * ## Measurement path (§9.4)
 *
 * Every FEC2 notification is routed through [FeCProtocolSession] for rollover-corrected
 * decoding, then [FeCMetricMapper] maps the page that was just applied onto canonical metrics
 * and this handler emits them through [BtEngine.metricEmitterFor] under
 * [MetricProtocol.VENDOR]/[MetricSourceClass.VENDOR_NATIVE] — the same reuse Schwinn x70 and
 * Precor make, since neither `MetricProtocol` nor `MetricSourceClass` has a dedicated FE-C
 * value (plan §9 explicitly calls for reuse rather than adding one).
 *
 * ## Capability and control path
 *
 * A measurement-only FE-C device (no FEC3) remains fully supported. When FEC3 exists, the same
 * bounded scheduler sends the page-54 capability request and all enabled target pages. FEC3 alone
 * does not unlock a target mode; the corresponding page-54 bit must be present first.
 */
internal class FeCOverBleProtocolHandler : BtEngineGattCore.ProtocolHandler {

    override val primaryServices: Set<UUID> = setOf(FecBleUuids.UUID_FEC_SERVICE)

    override val extraCharacteristics: Set<UUID> = setOf(
        FecBleUuids.UUID_FEC_MEASUREMENT,
        FecBleUuids.UUID_FEC_CONTROL,
    )

    /** Link-scoped; dies with the link, same discipline as every other machine handler here. */
    private var session: FeCProtocolSession? = null
    private var transport: GattTransport? = null

    /** Only constructed when FEC3 is present - a measurement-only link never gets one (§9.9). */
    private var scheduler: FeCControlScheduler? = null

    @Volatile
    private var controlAvailable: Boolean = false

    private var deviceClassificationPublished = false

    /** Rollover-corrected totals as of the previous page 25, for §9.4's average-power delta. */
    private var previousEventCountTotal: Long? = null
    private var previousAccumulatedPowerTotal: Long? = null
    private var capabilityRequestDeadlineMs: Long? = null
    private var capabilitiesResolved = false

    /** Whether the last activated link exposed FEC3. */
    internal fun controlChannelAvailable(): Boolean = controlAvailable

    /** Exposed for tests and future control wiring; `null` until this link activates. */
    internal fun currentSession(): FeCProtocolSession? = session

    /** Exposed for [FeCBleMachineControlEndpoint]; `null` on a measurement-only link. */
    internal fun controlScheduler(): FeCControlScheduler? = scheduler

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    override fun onConnected(engine: BtEngine, gatt: BluetoothGatt) {
        resetLinkState()
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    override fun onDisconnected(engine: BtEngine) {
        resetLinkState()
    }

    private fun resetLinkState() {
        session?.close()
        session = null
        transport = null
        scheduler?.stop()
        scheduler = null
        controlAvailable = false
        deviceClassificationPublished = false
        previousEventCountTotal = null
        previousAccumulatedPowerTotal = null
        capabilityRequestDeadlineMs = null
        capabilitiesResolved = false
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    override fun onServicesDiscovered(
        engine: BtEngine,
        gatt: BluetoothGatt,
        services: List<BluetoothGattService>,
    ): Boolean {
        val fecService = services.firstOrNull { it.uuid == FecBleUuids.UUID_FEC_SERVICE }
        val characteristicUuids = fecService?.characteristics.orEmpty().mapTo(linkedSetOf()) { it.uuid }
        val plan = planFeCDiscovery(fecService != null, characteristicUuids)
        if (!plan.activate) {
            Timber.tag(BtEngineState.TAG).w(
                "FE-C over BLE: FEC1 present but FEC2 (measurement) is missing; declining activation",
            )
            return false
        }
        val measurementChar = requireNotNull(
            fecService?.getCharacteristic(FecBleUuids.UUID_FEC_MEASUREMENT),
        )

        val context = engine.state.connectionRegistry.contextForGatt(gatt) ?: run {
            Timber.tag(BtEngineState.TAG).w("FE-C over BLE discovery ignored for unregistered GATT")
            return false
        }

        controlAvailable = plan.controlAvailable
        if (!controlAvailable) {
            Timber.tag(BtEngineState.TAG).i(
                "FE-C over BLE: FEC3 (control) absent on %s; measurement-only device",
                context.connectionId,
            )
        }

        // FEC3 is only the transport. Page 54 determines which control modes the equipment
        // actually claims; keep controls withheld until that response arrives.
        FeCCapabilities.publish(engine.state, controlAvailable)
        capabilitiesResolved = !controlAvailable

        session = FeCProtocolSession(context.connectionId)
        val linkTransport = BtEngineGattTransport(engine, context.connectionId)
        transport = linkTransport
        scheduler = if (controlAvailable) FeCControlScheduler(linkTransport) else null

        // Only FEC2 is armed here. FEC3, when present, is a write-without-response target for
        // the control scheduler (§9.9) - it is never subscribed to, and nothing is written to
        // it during discovery.
        plan.operations.forEach { operation ->
            when (operation.kind) {
                FeCGattOperationKind.ENABLE_NOTIFICATIONS ->
                    engine.enableNotifications(context, measurementChar)
            }
        }

        Timber.tag(BtEngineState.TAG).i(
            "FE-C over BLE activating connection=%s control=%s",
            context.connectionId,
            controlAvailable,
        )
        return true
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    override fun onCharacteristicChanged(
        engine: BtEngine,
        gatt: BluetoothGatt,
        ch: BluetoothGattCharacteristic,
        value: ByteArray,
    ) {
        if (ch.uuid != FecBleUuids.UUID_FEC_MEASUREMENT) return
        val activeSession = session ?: return

        val arrivalMs = MonotonicMetricClock.SYSTEM.nowMs()
        activeSession.onPacket(ch.uuid, value, arrivalMs)

        val page = activeSession.lastAppliedPage ?: return
        val snap = activeSession.snapshot

        if (!deviceClassificationPublished) {
            snap.equipmentType?.let { classifyDevice(engine, gatt, it) }
        }

        if (page is FeCPage.FeCapabilities && !capabilitiesResolved) {
            capabilityRequestDeadlineMs = null
            capabilitiesResolved = true
            FeCCapabilities.publish(engine.state, controlAvailable, page)
            snap.equipmentType?.let { equipmentType ->
                FeCMetricMapper.activityTypeFor(equipmentType)?.let {
                    engine.state.setInferredActivity(it)
                }
            }
        }

        val samples: List<FeCSample> = when (page) {
            is FeCPage.GeneralFe -> FeCMetricMapper.samplesForGeneralFe(page, snap)
            is FeCPage.GeneralSettings -> FeCMetricMapper.samplesForGeneralSettings(page)
            is FeCPage.GeneralMetabolicData -> FeCMetricMapper.samplesForMetabolicData(page, snap)
            is FeCPage.TrainerData -> {
                val eventCountTotal = snap.eventCountRaw
                val accumulatedPowerTotal = snap.accumulatedPowerRaw
                val mapped = if (eventCountTotal != null && accumulatedPowerTotal != null) {
                    FeCMetricMapper.samplesForTrainerData(
                        page = page,
                        eventCountTotal = eventCountTotal,
                        accumulatedPowerTotal = accumulatedPowerTotal,
                        previousEventCountTotal = previousEventCountTotal,
                        previousAccumulatedPowerTotal = previousAccumulatedPowerTotal,
                    )
                } else {
                    emptyList()
                }
                previousEventCountTotal = eventCountTotal
                previousAccumulatedPowerTotal = accumulatedPowerTotal
                mapped
            }
            is FeCPage.SpecificTreadmillData -> FeCMetricMapper.samplesForTreadmillData(page, snap)
            is FeCPage.SpecificEllipticalData -> FeCMetricMapper.samplesForEllipticalData(page, snap)
            is FeCPage.SpecificRowerData -> FeCMetricMapper.samplesForRowerData(page, snap)
            is FeCPage.SpecificClimberData -> FeCMetricMapper.samplesForClimberData(page, snap)
            is FeCPage.SpecificNordicSkierData -> FeCMetricMapper.samplesForNordicSkierData(page, snap)
            is FeCPage.TrainerTorqueData -> FeCMetricMapper.samplesForTrainerTorqueData(snap)
            else -> emptyList()
        }
        if (samples.isEmpty()) return

        val emitter = engine.metricEmitterFor(
            gatt,
            ch.uuid,
            MetricProtocol.VENDOR,
            MetricSourceClass.VENDOR_NATIVE,
            arrivalMs,
        ) ?: return

        for (sample in samples) {
            emitter.emit(
                sample.metric,
                sample.value,
                eventMs = sample.eventMs,
                nativeCumulative = sample.nativeCumulative,
            )
        }
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun classifyDevice(engine: BtEngine, gatt: BluetoothGatt, equipmentType: Int) {
        val activityType = FeCMetricMapper.activityTypeFor(equipmentType) ?: return
        val address = gatt.device?.address ?: return
        engine.state.updateDeviceMachineFeatureByAddress(address, activityType.ordinal)
        engine.state.connectionRegistry.contextForGatt(gatt)?.let { context ->
            engine.state.connectionRegistry.setMachineDeviceType(
                context,
                FeCMetricMapper.machineDeviceTypeFor(equipmentType),
            )
        }
        deviceClassificationPublished = true
        engine.state.setInferredActivity(activityType)
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    override fun onCccdPlanReady(engine: BtEngine, gatt: BluetoothGatt) {
        session?.onSubscriptionsReady(MonotonicMetricClock.SYSTEM.nowMs())
        // §9.9: FEC2 subscribed + FEC3 present (scheduler only exists when it is) is the
        // whole start gate - there is no proprietary handshake to wait for, unlike Life
        // Fitness's FTMS setup.
        scheduler?.let {
            it.submit(
                FeCControlTarget.RequestDataPage(
                    requestedPage = FecBleUuids.FEC_PAGE_FE_CAPABILITIES,
                ),
            )
            capabilityRequestDeadlineMs = MonotonicMetricClock.SYSTEM.nowMs() + CAPABILITY_RESPONSE_TIMEOUT_MS
            it.start()
        }
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    override fun onLivenessTick(engine: BtEngine, gatt: BluetoothGatt) {
        session?.onTick(MonotonicMetricClock.SYSTEM.nowMs())
        val nowMs = MonotonicMetricClock.SYSTEM.nowMs()
        val deadline = capabilityRequestDeadlineMs
        if (feCCapabilityResponseTimedOut(deadline, nowMs, capabilitiesResolved)) {
            capabilityRequestDeadlineMs = null
            capabilitiesResolved = true
            Timber.tag(BtEngineState.TAG).w("FE-C page 54 response timed out; withholding all control modes")
            FeCCapabilities.publish(
                engine.state,
                controlAvailable,
                FeCPage.FeCapabilities(
                    maximumResistanceNewtons = null,
                    supportsBasicResistance = false,
                    supportsTargetPower = false,
                    supportsSimulation = false,
                ),
            )
            session?.snapshot?.equipmentType?.let { equipmentType ->
                FeCMetricMapper.activityTypeFor(equipmentType)?.let {
                    engine.state.setInferredActivity(it)
                }
            }
        }
        // §9.9's "stops on demotion": there is no dedicated demotion callback into
        // ProtocolHandler, so this reuses the periodic liveness tick every other handler
        // already receives to notice when AdapterLivenessCoordinator has moved this link
        // off FE-C, rather than adding a second notification path for the same fact.
        val activeScheduler = scheduler ?: return
        val activeSession = session ?: return
        if (activeScheduler.isRunning &&
            engine.state.adapterLiveness.selectedAdapterId(activeSession.connectionId) !=
            AdapterLivenessCoordinator.FEC_ADAPTER_ID
        ) {
            activeScheduler.stop()
        }
    }

    companion object {
        private const val CAPABILITY_RESPONSE_TIMEOUT_MS = 3_000L
    }
}
