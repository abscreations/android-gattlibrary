package com.gmail.absolutebollockscreations.gattlibrary.central

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
    allowMissingCccd: Boolean = false,
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
    if (!io.hasCccd && !allowMissingCccd) {
        return reject(KayakFirstDiscoveryFailure.IO_MISSING_CCCD)
    }

    return KayakFirstDiscoveryPlan(
        activate = true,
        failure = null,
        notificationTarget = if (io.hasCccd) {
            CccdTarget(
                BluetoothIds.UUID_KAYAK_FIRST_SERVICE,
                BluetoothIds.UUID_KAYAK_FIRST_IO,
            )
        } else {
            null
        },
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
 * Android binding for the pure Kayak First setup and runtime coordinators.
 *
 * Setup and polling share the owning connection's serialized GATT queue. Runtime measurements are
 * source-tagged before fusion; local session commands remain deliberately separate from the
 * unavailable downstream FTMS control route.
 */
internal class KayakFirstProtocolHandler(
    private var displayConfiguration: KayakFirstDisplayConfiguration =
        KayakFirstDisplayConfiguration.DEFAULT,
) : BtEngineGattCore.ProtocolHandler {
    override val primaryServices: Set<UUID> = setOf(BluetoothIds.UUID_KAYAK_FIRST_SERVICE)
    override val extraCharacteristics: Set<UUID> = setOf(BluetoothIds.UUID_KAYAK_FIRST_IO)

    private var assembler = KayakFirstFrameAssembler()
    @Volatile
    private var coordinator: KayakFirstSetupCoordinator? = null
    @Volatile
    private var runtime: KayakFirstRuntimeCoordinator? = null
    private var continuity = KayakFirstContinuityTracker()
    private var derivedStrokeCounter = KayakFirstDerivedStrokeCounter()
    private var averagePowerAccumulator = KayakFirstAveragePowerAccumulator()
    private var energyEstimator = KayakFirstEnergyEstimator()
    private var windowTracker = KayakFirstSampleWindowTracker()
    private var activityGate = KayakFirstActivityGate()
    private var generation = KayakFirstGeneration.BLUE
    private var orangeAssembler = KayakFirstOrangeLineAssembler()
    private var orangeParser = KayakFirstOrangeResponseParser()
    private var orangeSession: KayakFirstOrangeSession? = null
    private var orangeInFlight: KayakFirstOrangeCommand? = null
    private var orangeIssuedAtMs = 0L
    private var orangeRetryCount = 0
    private var orangeReady = false
    private var orangeStarted = false
    @Volatile
    private var structuralMatch = false
    @Volatile
    private var protocolConfirmed = false
    @Volatile
    private var setupProgressConnectionId: ConnectionId? = null
    private var selectedSportMode: KayakFirstSportMode = KayakFirstSportMode.CANOEING
    private var selectedHandshakeVariant: KayakFirstHandshakeVariant =
        KayakFirstHandshakeVariant.LEGACY
    private var selectedAthleteWeightKg: Int = KayakFirstCommandCodec.NEUTRAL_WEIGHT_KG
    private var selectedBoatWeightKg: Int = KayakFirstCommandCodec.DEFAULT_BOAT_WEIGHT_KG

    internal fun hasStructuralMatch(): Boolean = structuralMatch
    internal fun isProtocolConfirmed(): Boolean = protocolConfirmed
    internal fun setupState(): KayakFirstSetupState =
        coordinator?.state ?: KayakFirstSetupState.DISCOVERING
    internal fun setupCoordinator(): KayakFirstSetupCoordinator? = coordinator
    internal fun runtimeCoordinator(): KayakFirstRuntimeCoordinator? = runtime

    internal fun recordStructuralMatch(
        connectionId: ConnectionId,
        nowMs: Long = 0L,
    ) {
        structuralMatch = true
        coordinator = KayakFirstSetupCoordinator(
            connectionId,
            displayConfiguration,
            selectedSportMode,
            selectedHandshakeVariant,
            selectedAthleteWeightKg,
            selectedBoatWeightKg,
        ).also {
            it.beginSubscription(nowMs)
        }
    }

    override fun onConnected(engine: BtEngine, gatt: BluetoothGatt) {
        resetLinkState(disconnected = false)
    }

    override fun onDisconnected(engine: BtEngine) {
        setupProgressConnectionId?.let(engine.state::clearKayakFirstSetupProgress)
        setupProgressConnectionId = null
        resetLinkState(disconnected = true)
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    override fun onServicesDiscovered(
        engine: BtEngine,
        gatt: BluetoothGatt,
        services: List<BluetoothGattService>,
    ): Boolean {
        val context = engine.state.connectionRegistry.contextForGatt(gatt) ?: return false
        val discovered = engine.state.discoveredDevices.value.values.firstOrNull { device ->
            canonicalScanAddress(device.address) == canonicalScanAddress(context.address)
        }
        val candidateId = discovered?.matchCandidateId
        generation = kayakFirstGeneration(discovered?.name) ?: KayakFirstGeneration.BLUE
        val inventory = services.toKayakFirstInventory()
        val plan = planKayakFirstDiscovery(
            candidateId,
            inventory,
            allowMissingCccd = generation == KayakFirstGeneration.ORANGE,
        )
        if (!plan.activate) {
            Timber.tag(BtEngineState.TAG).w(
                "Kayak First discovery declined generation=%d reason=%s",
                context.connectionId.linkGeneration,
                plan.failure,
            )
            engine.state.clearKayakFirstSetupProgress(context.connectionId)
            setupProgressConnectionId = null
            resetLinkState(disconnected = false)
            return false
        }

        val service = services.first { it.uuid == BluetoothIds.UUID_KAYAK_FIRST_SERVICE }
        val io = requireNotNull(service.getCharacteristic(BluetoothIds.UUID_KAYAK_FIRST_IO))
        // Snapshot only when this generation begins setup. A later settings edit is deliberately
        // inert until reconnect, so command 5 is never invented as a mid-workout update.
        engine.kayakFirstDisplayConfiguration?.let { displayConfiguration = it }
        selectedSportMode = engine.kayakFirstSportMode
        selectedHandshakeVariant = engine.kayakFirstHandshakeVariant
        // The console runs athlete mass through its own speed/energy model, so the configured
        // FitBridge body weight is transmitted when one exists; otherwise the neutral 80 kg.
        selectedAthleteWeightKg =
            KayakFirstCommandCodec.athleteWeightKgOrNeutral(engine.state.lifeFitnessProfile.weightKg)
        selectedBoatWeightKg = engine.kayakFirstBoatWeightKg
        if (generation == KayakFirstGeneration.ORANGE) {
            structuralMatch = true
            orangeSession = KayakFirstOrangeSession {
                engine.state.lifeFitnessProfile.weightKg.toDouble()
            }
            setupProgressConnectionId = null
            if (inventory.services[BluetoothIds.UUID_KAYAK_FIRST_SERVICE]
                    ?.get(BluetoothIds.UUID_KAYAK_FIRST_IO)?.hasCccd == true
            ) {
                engine.enableNotifications(context, io)
            } else {
                val enabled = runCatching { gatt.setCharacteristicNotification(io, true) }
                    .getOrDefault(false)
                if (!enabled) return false
            }
            return true
        }
        recordStructuralMatch(context.connectionId, MonotonicMetricClock.SYSTEM.nowMs())
        setupProgressConnectionId = context.connectionId
        publishSetupProgress(engine, context)
        engine.enableNotifications(context, io)
        return true
    }

    override fun onCccdPlanReady(engine: BtEngine, gatt: BluetoothGatt) {
        val context = engine.state.connectionRegistry.contextForGatt(gatt) ?: return
        if (
            engine.state.adapterLiveness.selectedAdapterId(context.connectionId) !=
            AdapterLivenessCoordinator.KAYAK_FIRST_ADAPTER_ID
        ) {
            // Native FTMS (or another stronger adapter) owns this link. Never probe a generic
            // FFE0 surface behind the selected machine adapter.
            coordinator?.cancel()
            publishSetupProgress(engine, context)
            return
        }
        if (generation == KayakFirstGeneration.ORANGE) {
            startOrange(engine, context)
            return
        }
        if (!context.cccd.ready.value) return
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
        if (generation == KayakFirstGeneration.ORANGE) {
            onOrangeBytes(engine, gatt, context, value)
            return
        }
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
                is KayakFirstFrameEvent.TextLine -> {
                    onTextLine(engine, gatt, setup, context, event.value, nowMs)
                    emptyList()
                }
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
        if (generation == KayakFirstGeneration.ORANGE) {
            if (!orangeStarted &&
                engine.state.adapterLiveness.selectedAdapterId(context.connectionId) ==
                AdapterLivenessCoordinator.KAYAK_FIRST_ADAPTER_ID
            ) {
                startOrange(engine, context)
            }
            val command = orangeInFlight ?: return
            val nowMs = MonotonicMetricClock.SYSTEM.nowMs()
            if (nowMs - orangeIssuedAtMs < ORANGE_RESPONSE_TIMEOUT_MS) return
            if (command == KayakFirstOrangeCommand.RESET) {
                orangeInFlight = null
                orangeRetryCount = 0
                orangeSession?.assumeResetComplete()?.let { dispatchOrange(engine, context, it) }
                return
            }
            if (orangeRetryCount >= ORANGE_MAX_RETRIES) {
                engine.state.adapterLiveness.setupFailed(
                    context,
                    "Kayak First Orange response timeout: ${command.name.lowercase()}",
                )
                orangeInFlight = null
            } else {
                orangeRetryCount += 1
                dispatchOrange(engine, context, command, retry = true)
            }
            return
        }
        val setup = coordinator ?: return
        if (setup.connectionId != context.connectionId) return
        applyActions(engine, context, setup.tick(MonotonicMetricClock.SYSTEM.nowMs()))
        val selected = engine.state.adapterLiveness.selectedAdapterId(context.connectionId)
        if (selected != AdapterLivenessCoordinator.KAYAK_FIRST_ADAPTER_ID) {
            runtime?.cancel()
            return
        }
        applyRuntimeActions(
            engine,
            context,
            runtime?.tick(MonotonicMetricClock.SYSTEM.nowMs()).orEmpty(),
        )
    }

    private fun onTextLine(
        engine: BtEngine,
        gatt: BluetoothGatt,
        setup: KayakFirstSetupCoordinator,
        context: ConnectionContext,
        line: String,
        nowMs: Long,
    ) {
        val connectionId = context.connectionId
        val decoded = KayakFirstResponseDecoder.decode(line)
        if (decoded !is KayakFirstLineDecodeResult.Decoded) {
            Timber.tag(BtEngineState.TAG).w(
                "Kayak First response rejected generation=%d reason=%s",
                connectionId.linkGeneration,
                (decoded as KayakFirstLineDecodeResult.Rejected).reason,
            )
            return
        }
        when (decoded.response.kind) {
            KayakFirstResponseKind.POLL -> onMeasurementLine(engine, gatt, context, line, nowMs)
            KayakFirstResponseKind.SESSION -> applyRuntimeActions(
                engine,
                context,
                runtime?.onSessionResponse(connectionId, decoded.response.fields, nowMs).orEmpty(),
            )
            else -> Unit
        }
        val before = setup.completedTransactions.size
        val actions = setup.onTextResponse(connectionId, decoded.response, nowMs).also {
            if (setup.completedTransactions.size > before &&
                setup.completedTransactions.last().terminalResult == KayakFirstTransactionResult.SUCCESS
            ) {
                protocolConfirmed = true
            }
        }
        applyActions(engine, context, actions)
    }

    private fun onMeasurementLine(
        engine: BtEngine,
        gatt: BluetoothGatt,
        context: ConnectionContext,
        line: String,
        nowMs: Long,
    ) {
        val decoded = KayakFirstMeasurement.decode(line)
        if (decoded !is KayakFirstMeasurementDecodeResult.Decoded) return
        val accepted = continuity.accept(decoded.reading)
        if (accepted !is KayakFirstContinuityResult.Accepted) {
            Timber.tag(BtEngineState.TAG).w(
                "Kayak First continuity rejected generation=%d reason=%s",
                context.connectionId.linkGeneration,
                (accepted as KayakFirstContinuityResult.Rejected).reason,
            )
            return
        }
        protocolConfirmed = true
        // Field 23 intersected with the field 22 elapsed delta gives the non-overlapping seconds
        // this frame represents; field 7 then decides whether those seconds are active time.
        val representedSeconds = windowTracker.accept(accepted.reading)
        when (activityGate.accept(accepted.reading, representedSeconds)) {
            KayakFirstActivityTransition.STARTED,
            KayakFirstActivityTransition.RESUMED,
            -> Timber.tag(BtEngineState.TAG).i(
                "Kayak First session active generation=%d",
                context.connectionId.linkGeneration,
            )
            KayakFirstActivityTransition.PAUSED -> Timber.tag(BtEngineState.TAG).i(
                "Kayak First session paused generation=%d",
                context.connectionId.linkGeneration,
            )
            KayakFirstActivityTransition.UNCHANGED -> Unit
        }
        val sessionActive = activityGate.isActive
        accepted.reading.paceAnomalies().takeIf { it.isNotEmpty() }?.let { anomalies ->
            Timber.tag(BtEngineState.TAG).w(
                "Kayak First pace schema anomaly fields=%s",
                anomalies.joinToString(),
            )
        }
        applyRuntimeActions(
            engine,
            context,
            runtime?.onMeasurement(context.connectionId, nowMs).orEmpty(),
        )
        val emitter = engine.metricEmitterFor(
            gatt,
            BluetoothIds.UUID_KAYAK_FIRST_IO,
            MetricProtocol.VENDOR,
            MetricSourceClass.VENDOR_NATIVE,
            nowMs,
        ) ?: return
        kayakFirstSamples(accepted.reading).forEach { sample ->
            emitter.emit(
                metric = sample.metric,
                value = sample.value,
                eventMs = accepted.reading.elapsedSeconds * 1_000L,
                nativeCumulative = sample.nativeCumulative,
                quality = sample.quality,
            )
        }
        val derivedEmitter = engine.metricEmitterFor(
            gatt,
            BluetoothIds.UUID_KAYAK_FIRST_IO,
            MetricProtocol.VENDOR,
            MetricSourceClass.DERIVED,
            nowMs,
        ) ?: return
        listOf(derivedStrokeCounter.sample(accepted.reading, representedSeconds)).forEach { sample ->
            derivedEmitter.emit(
                metric = sample.metric,
                value = sample.value,
                eventMs = accepted.reading.elapsedSeconds * 1_000L,
                nativeCumulative = sample.nativeCumulative,
                quality = sample.quality,
            )
        }
        KayakFirstDerivedMetrics.estimatePower(
            accepted.reading,
            engine.state.lifeFitnessProfile.weightKg.toDouble(),
        )?.let { power ->
            val averagePower =
                averagePowerAccumulator.sample(representedSeconds, power, sessionActive)
            kayakFirstEstimatedPowerSamples(power, averagePower).forEach { sample ->
                derivedEmitter.emit(
                    metric = sample.metric,
                    value = sample.value,
                    eventMs = accepted.reading.elapsedSeconds * 1_000L,
                    quality = sample.quality,
                )
            }
            kayakFirstEstimatedEnergySamples(
                energyEstimator.sample(representedSeconds, power, sessionActive),
            ).forEach { sample ->
                derivedEmitter.emit(
                    metric = sample.metric,
                    value = sample.value,
                    eventMs = accepted.reading.elapsedSeconds * 1_000L,
                    nativeCumulative = sample.nativeCumulative,
                    quality = sample.quality,
                )
            }
        }
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun onOrangeBytes(
        engine: BtEngine,
        gatt: BluetoothGatt,
        context: ConnectionContext,
        value: ByteArray,
    ) {
        val session = orangeSession ?: return
        orangeAssembler.accept(value).forEach { event ->
            if (event !is KayakFirstOrangeFrameEvent.Line) {
                Timber.tag(BtEngineState.TAG).w("Kayak First Orange line rejected: %s", event)
                return@forEach
            }
            val response = orangeParser.accept(event.value) ?: return@forEach
            val (next, reading) = session.onResponse(response, MonotonicMetricClock.SYSTEM.nowMs())
            if (next != null) {
                orangeInFlight = null
                orangeRetryCount = 0
            }
            if (response is KayakFirstOrangeResponse.ResetOk) {
                protocolConfirmed = true
                markOrangeReady(engine, context)
            }
            if (reading != null) {
                protocolConfirmed = true
                markOrangeReady(engine, context)
                emitOrangeReading(engine, gatt, reading)
            }
            next?.let { dispatchOrange(engine, context, it) }
        }
    }

    private fun markOrangeReady(engine: BtEngine, context: ConnectionContext) {
        if (orangeReady) return
        orangeReady = true
        engine.state.adapterLiveness.setupSucceeded(context, MonotonicMetricClock.SYSTEM.nowMs())
        engine.state.connectionRegistry.setMachineDeviceType(context, BtEngineState.MachineDeviceType.ROWER)
        engine.state.updateDeviceMachineFeatureByAddress(
            context.address,
            com.gmail.absolutebollockscreations.gattlibrary.core.ActivityType.ROWING.ordinal,
        )
        engine.maybeStartProviderAdvertisingAfterFeatures(BtServiceProfiles.FtmsMachineProfile.INDOOR_ROWER)
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun startOrange(engine: BtEngine, context: ConnectionContext) {
        if (orangeStarted) return
        orangeStarted = true
        dispatchOrange(engine, context, orangeSession?.start() ?: return)
    }

    private fun emitOrangeReading(
        engine: BtEngine,
        gatt: BluetoothGatt,
        reading: KayakFirstOrangeReading,
    ) {
        val nowMs = MonotonicMetricClock.SYSTEM.nowMs()
        val emitter = engine.metricEmitterFor(
            gatt,
            BluetoothIds.UUID_KAYAK_FIRST_IO,
            MetricProtocol.VENDOR,
            MetricSourceClass.DERIVED,
            nowMs,
        ) ?: return
        val elapsed = reading.elapsedSeconds
        val physics = reading.physics
        val samples = listOf(
            KayakFirstSample(MetricKind.POWER, physics.powerWatts, quality = MetricQuality.DERIVED),
            KayakFirstSample(MetricKind.AVERAGE_POWER, reading.averagePowerWatts, quality = MetricQuality.DERIVED),
            KayakFirstSample(MetricKind.SPEED, physics.speedMetresPerSecond * 3.6, quality = MetricQuality.DERIVED),
            KayakFirstSample(MetricKind.AVERAGE_SPEED, reading.averageSpeedMetresPerSecond * 3.6, quality = MetricQuality.DERIVED),
            KayakFirstSample(MetricKind.CADENCE, physics.strokeRateSpm, quality = MetricQuality.DERIVED),
            KayakFirstSample(MetricKind.AVERAGE_CADENCE, reading.averageStrokeRateSpm, quality = MetricQuality.DERIVED),
            KayakFirstSample(MetricKind.DISTANCE, reading.distanceMetres, reading.distanceMetres, quality = MetricQuality.DERIVED),
            KayakFirstSample(MetricKind.STROKE_COUNT, reading.strokeCount, reading.strokeCount, quality = MetricQuality.DERIVED),
            KayakFirstSample(MetricKind.ELAPSED, elapsed, quality = MetricQuality.DERIVED),
        )
        samples.forEach { sample ->
            emitter.emit(
                metric = sample.metric,
                value = sample.value,
                eventMs = (elapsed * 1_000.0).toLong(),
                nativeCumulative = sample.nativeCumulative,
                quality = sample.quality,
            )
        }
        // The weight is what this tuple represents, not the cumulative session clock: passing
        // `elapsed` here integrated power against the whole session on every frame.
        energyEstimator.sample(reading.representedSeconds, physics.powerWatts).let { estimate ->
            kayakFirstEstimatedEnergySamples(estimate).forEach { sample ->
                emitter.emit(
                    metric = sample.metric,
                    value = sample.value,
                    eventMs = (elapsed * 1_000.0).toLong(),
                    nativeCumulative = sample.nativeCumulative,
                    quality = sample.quality,
                )
            }
        }
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun dispatchOrange(
        engine: BtEngine,
        context: ConnectionContext,
        command: KayakFirstOrangeCommand,
        retry: Boolean = false,
    ) {
        if (context.gatt == null) return
        if (!retry) orangeRetryCount = 0
        orangeInFlight = command
        orangeIssuedAtMs = MonotonicMetricClock.SYSTEM.nowMs()
        engine.pushWrite(
            context,
            command.bytes(),
            BluetoothIds.UUID_KAYAK_FIRST_IO,
            writeMode = StackEntry.WriteMode.WITHOUT_RESPONSE,
            onComplete = { success ->
                if (!success) {
                    engine.state.adapterLiveness.setupFailed(
                        context,
                        "Kayak First Orange write failed: ${command.name.lowercase()}",
                    )
                }
            },
        )
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
                    engine.state.connectionRegistry.setMachineDeviceType(
                        context,
                        BtEngineState.MachineDeviceType.ROWER,
                    )
                    engine.state.updateDeviceMachineFeatureByAddress(
                        context.address,
                        com.gmail.absolutebollockscreations.gattlibrary.core.ActivityType.ROWING.ordinal,
                    )
                    engine.maybeStartProviderAdvertisingAfterFeatures(
                        BtServiceProfiles.FtmsMachineProfile.INDOOR_ROWER,
                    )
                    val nowMs = MonotonicMetricClock.SYSTEM.nowMs()
                    runtime = KayakFirstRuntimeCoordinator(context.connectionId)
                    applyRuntimeActions(engine, context, runtime?.start(nowMs).orEmpty())
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
        publishSetupProgress(engine, context)
    }

    private fun publishSetupProgress(engine: BtEngine, context: ConnectionContext) {
        val setup = coordinator ?: return
        if (setup.connectionId != context.connectionId) return
        engine.state.setKayakFirstSetupProgress(KayakFirstSetupProgress.from(setup))
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

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    internal fun requestLocalStart(engine: BtEngine, context: ConnectionContext): Boolean {
        val active = runtime ?: return false
        if (active.connectionId != context.connectionId || !active.requestStart()) return false
        applyRuntimeActions(
            engine,
            context,
            active.tick(MonotonicMetricClock.SYSTEM.nowMs()),
        )
        continuity.reset()
        return true
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    internal fun requestLocalStop(engine: BtEngine, context: ConnectionContext): Boolean {
        val active = runtime ?: return false
        if (active.connectionId != context.connectionId || !active.requestStop()) return false
        applyRuntimeActions(
            engine,
            context,
            active.tick(MonotonicMetricClock.SYSTEM.nowMs()),
        )
        return true
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun applyRuntimeActions(
        engine: BtEngine,
        context: ConnectionContext,
        actions: List<KayakFirstRuntimeAction>,
    ) {
        actions.forEach { action ->
            when (action) {
                is KayakFirstRuntimeAction.Send -> dispatchRuntime(engine, context, action.transaction)
                is KayakFirstRuntimeAction.ConnectedNoData -> {
                    engine.state.adapterLiveness.pollingMissesExceeded(
                        context.connectionId,
                        action.consecutiveMisses,
                    )
                    runtime?.cancel()
                }
            }
        }
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun dispatchRuntime(
        engine: BtEngine,
        context: ConnectionContext,
        transaction: KayakFirstRuntimeTransaction,
    ) {
        if (context.connectionId != transaction.connectionId || context.gatt == null) return
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
                        applyRuntimeActions(
                            engine,
                            context,
                            runtime?.onWriteCompleted(
                                transaction.connectionId,
                                transaction.kind,
                                transaction.sequence,
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
        runtime?.cancel(disconnected)
        coordinator = null
        runtime = null
        assembler.reset()
        assembler = KayakFirstFrameAssembler()
        continuity.reset()
        continuity = KayakFirstContinuityTracker()
        derivedStrokeCounter.reset()
        derivedStrokeCounter = KayakFirstDerivedStrokeCounter()
        averagePowerAccumulator.reset()
        averagePowerAccumulator = KayakFirstAveragePowerAccumulator()
        windowTracker = KayakFirstSampleWindowTracker()
        activityGate = KayakFirstActivityGate()
        energyEstimator.reset()
        energyEstimator = KayakFirstEnergyEstimator()
        orangeAssembler.reset()
        orangeAssembler = KayakFirstOrangeLineAssembler()
        orangeParser.reset()
        orangeParser = KayakFirstOrangeResponseParser()
        orangeSession?.reset()
        orangeSession = null
        orangeInFlight = null
        orangeIssuedAtMs = 0L
        orangeRetryCount = 0
        orangeReady = false
        orangeStarted = false
        generation = KayakFirstGeneration.BLUE
        structuralMatch = false
        protocolConfirmed = false
    }

    private companion object {
        const val ORANGE_RESPONSE_TIMEOUT_MS = 2_000L
        const val ORANGE_MAX_RETRIES = 2
    }
}
