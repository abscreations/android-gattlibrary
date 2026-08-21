package com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine

import android.Manifest
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattService
import androidx.annotation.RequiresPermission
import timber.log.Timber
import java.util.UUID

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
 * ## What this handler still does not do
 *
 * Control encoding, the 4Hz write scheduler, and capability translation are §9.6-9.9. A
 * measurement-only FE-C device (no FEC3) is fully supported by what is here; a device that also
 * exposes FEC3 is measured the same way and offers no control yet.
 */
internal class FeCOverBleProtocolHandler : BtEngineGattCore.ProtocolHandler {

    override val primaryServices: Set<UUID> = setOf(BluetoothIds.UUID_FEC_SERVICE)

    override val extraCharacteristics: Set<UUID> = setOf(
        BluetoothIds.UUID_FEC_MEASUREMENT,
        BluetoothIds.UUID_FEC_CONTROL,
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
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    override fun onServicesDiscovered(
        engine: BtEngine,
        gatt: BluetoothGatt,
        services: List<BluetoothGattService>,
    ): Boolean {
        val fecService = services.firstOrNull { it.uuid == BluetoothIds.UUID_FEC_SERVICE }
            ?: return false

        val measurementChar = fecService.getCharacteristic(BluetoothIds.UUID_FEC_MEASUREMENT)
        if (measurementChar == null) {
            Timber.tag(BtEngineState.TAG).w(
                "FE-C over BLE: FEC1 present but FEC2 (measurement) is missing; declining activation",
            )
            return false
        }

        val context = engine.state.connectionRegistry.contextForGatt(gatt) ?: run {
            Timber.tag(BtEngineState.TAG).w("FE-C over BLE discovery ignored for unregistered GATT")
            return false
        }

        val controlChar = fecService.getCharacteristic(BluetoothIds.UUID_FEC_CONTROL)
        controlAvailable = controlChar != null
        if (!controlAvailable) {
            Timber.tag(BtEngineState.TAG).i(
                "FE-C over BLE: FEC3 (control) absent on %s; measurement-only device",
                context.connectionId,
            )
        }

        // Unit 9.8: publish conservative capability booleans from the FEC3 presence/absence
        // fact just determined above. Never claims a known Supported-* range - see
        // FeCCapabilities's KDoc for why that would be an invented number, not a reading.
        FeCCapabilities.publish(engine.state, controlAvailable)

        session = FeCProtocolSession(context.connectionId)
        val linkTransport = BtEngineGattTransport(engine, context.connectionId)
        transport = linkTransport
        scheduler = if (controlAvailable) FeCControlScheduler(linkTransport) else null

        // Only FEC2 is armed here. FEC3, when present, is a write-without-response target for
        // the control scheduler (§9.9) - it is never subscribed to, and nothing is written to
        // it during discovery.
        engine.enableNotifications(context, measurementChar)

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
        if (ch.uuid != BluetoothIds.UUID_FEC_MEASUREMENT) return
        val activeSession = session ?: return

        val arrivalMs = MonotonicMetricClock.SYSTEM.nowMs()
        activeSession.onPacket(ch.uuid, value, arrivalMs)

        val page = activeSession.lastAppliedPage ?: return
        val snap = activeSession.snapshot

        if (!deviceClassificationPublished) {
            snap.equipmentType?.let { classifyDevice(engine, gatt, it) }
        }

        val samples: List<FeCSample> = when (page) {
            is FeCPage.GeneralFe -> FeCMetricMapper.samplesForGeneralFe(page, snap)
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
            is FeCPage.SpecificTreadmillData -> FeCMetricMapper.samplesForTreadmillData(page)
            is FeCPage.SpecificRowerData -> FeCMetricMapper.samplesForRowerData(page)
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
        deviceClassificationPublished = true
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    override fun onCccdPlanReady(engine: BtEngine, gatt: BluetoothGatt) {
        session?.onSubscriptionsReady(MonotonicMetricClock.SYSTEM.nowMs())
        // §9.9: FEC2 subscribed + FEC3 present (scheduler only exists when it is) is the
        // whole start gate - there is no proprietary handshake to wait for, unlike Life
        // Fitness's FTMS setup.
        scheduler?.start()
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    override fun onLivenessTick(engine: BtEngine, gatt: BluetoothGatt) {
        session?.onTick(MonotonicMetricClock.SYSTEM.nowMs())
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
}
