// =================================================================================================
// File: btEngine.GattFtms.kt
// Package: com.gmail.absolutebollockscreations.gattlibrary.central
// -------------------------------------------------------------------------------------------------
// ROLE
//   FTMS (Fitness Machine Service, 0x1826) protocol handler for BtEngineGattCore.
//
//   This class implements [BtEngineGattCore.ProtocolHandler] and owns everything specific to
//   Fitness Machine Service on the **consumer** side:
//     • Identifies the FTMS service and its characteristics after discovery.
//     • Wires up CCCDs (NOTIFY / INDICATE) for data, status, and control point chars.
//     • Parses FTMS Control Point indications (0x2AD9) and Fitness Machine Status (0x2ADA).
//     • Delegates FTMS data frame parsing to BtEngineFtmsParsers for each machine type.
//     • Updates [BtEngineState] with features, ranges, machine type, status, and targets.
//     • Notifies the provider-side BtService layer via BtEngine.serviceControlSink when
//       control is lost, safety key triggers, or target parameters change.
// -------------------------------------------------------------------------------------------------
// SERVICE / CHARACTERISTIC HANDLING
//   onServicesDiscovered(...):
//     • Locates the FTMS service (0x1826) and caches the Control Point characteristic.
//     • Logs all FTMS characteristics, their properties, and CCCD presence (for debugging).
//     • Infers machine ActivityType (treadmill, rower, bike, etc.) from which FTMS data
//       characteristics are present, and stores that in the corresponding DiscoveredDevice.
//     • Queues a READ of Fitness Machine Feature (0x2ACC) first to discover capabilities.
//     • Builds an “enable plan” (data/status/CP) **later**, after FEATURES is parsed, and
//       then calls engine.enableNotifications(...) to write CCCDs via the shared GATT queue.
//     • Sets state.cccdReady immediately if there are no CCCDs to arm on this device.
// -------------------------------------------------------------------------------------------------
// POST-CCCD BEHAVIOR
//   onCccdPlanReady(...):
//     • Runs after the context-wide merged CCCD plan from every active handler is armed.
//     • When the plan becomes ready and the
//       one-shot “postCccdKick” has not yet been used, it enqueues a *single* safety
//       re-read of Fitness Machine Feature (0x2ACC).
//     • It **no longer** sends REQUEST_CONTROL or START/RESUME. Those are now solely
//       driven by higher layers via BtEngine.requestControl() / startOrResume().
// -------------------------------------------------------------------------------------------------
// NOTIFICATIONS / INDICATIONS
//   onCharacteristicChanged(...):
//     • Routes FTMS notifications/indications by characteristic UUID:
//         - CONTROL_POINT (2AD9): handleControlPointIndication(...)
//         - FITNESS_MACHINE_STATUS (2ADA): handleFitnessMachineStatus(...)
//         - FTMS data chars (treadmill/elliptical/rower/stair/ bike):
//               → delegated to BtEngineFtmsParsers.doTreadmill / doRower / etc.
//     • Unknown FTMS UUIDs are logged but otherwise ignored.
//
//   handleControlPointIndication(...):
//     • Validates and decodes CP Response Code frames (opcode 0x80).
//     • For RESET (0x01): clears control flag on success; sets GATT error on failure.
//     • For REQUEST_CONTROL / START_RESUME / STOP_PAUSE / SET_TARGET_*:
//         - Logs result codes (SUCCESS, INVALID_PARAMETER, etc.).
//         - Updates haveControl accordingly.
//         - On control-not-permitted/unsupported/failure, calls onControlLost(...)
//           which clears control and surfaces an error, then notifies the serviceControlSink.
// -------------------------------------------------------------------------------------------------
// FITNESS MACHINE STATUS (0x2ADA)
//   handleFitnessMachineStatus(...):
//     • Reads the status opcode (first byte) and updates BtEngineState:
//         - FtmsSpec.Status.RESET:
//               → clear safety key, clear control.
//         - FtmsSpec.Status.STARTED_OR_RESUMED_BY_USER / FtmsSpec.Status.STOPPED_OR_PAUSED_BY_USER:
//               → update isRunning flag.
//         - FtmsSpec.Status.STOPPED_BY_SAFETY_KEY:
//               → set safetyKeyEngaged=true, isRunning=false, and call
//                 serviceControlSink?.onSafetyKeyPulled().
//         - FtmsSpec.Status.CONTROL_PERMISSION_LOST:
//               → clear control and call serviceControlSink?.onControlPermissionLost().
//         - STATUS_TARGET_*_CHANGED (speed, incline, resistance, power, heart rate):
//               → decode raw fields using FTMS_*_SCALE constants,
//                 update target* flows in BtEngineState,
//                 forward the change to serviceControlSink (e.g. onTargetSpeedChangedKmh()).
//     • Any unhandled status opcode is logged verbosely and ignored.
// -------------------------------------------------------------------------------------------------
// CAPABILITIES & RANGES
//   onCharacteristicRead(...):
//     • For Fitness Machine Feature (0x2ACC):
//         - Calls BtEngineCapabilities.parseFitnessMachineFeatures(...)
//           to set supportsSpeed / supportsInclination / supportsResistance / supportsPower.
//         - Logs the resolved feature set alongside the inferred machine type label.
//         - Optionally queues range reads via maybeQueueCapabilityReads(...).
//     • For Supported Speed / Incline / Resistance / Power Range:
//         - Delegates to BtEngineCapabilities.parseSupported*Range(...) to populate
//           min/max/step fields in BtEngineState and mark rangeKnown flags.
//     • Other FTMS reads are logged and ignored by this handler.
// -------------------------------------------------------------------------------------------------
// IMPLEMENTATION NOTES
//   • All writes/reads are enqueued via BtEngine.pushWrite / pushRead to honor the
//     serialized GATT queue managed by BtEngine.
//   • The handler is mostly stateless; dynamic behavior lives inside BtEngineState or
//     higher-level components (BtEngine, serviceControlSink).
//   • All public-facing behavior (control ownership, safety key, targets, machine type)
//     is exposed via BtEngineState flows or BtServiceControlSink callbacks for the app/UI
//     and provider-side GATT service to observe.
// =================================================================================================

package com.gmail.absolutebollockscreations.gattlibrary.central

import android.Manifest
import android.bluetooth.*
import androidx.annotation.RequiresPermission
import com.gmail.absolutebollockscreations.gattlibrary.core.ActivityType
import com.gmail.absolutebollockscreations.gattlibrary.core.GattLibraryInternalApi
import com.gmail.absolutebollockscreations.gattlibrary.core.ftms.FtmsResistanceScale
import com.gmail.absolutebollockscreations.gattlibrary.core.ftms.FtmsUuids.UUID_BIKING_DATA
import com.gmail.absolutebollockscreations.gattlibrary.core.ids.SigUuids.UUID_CCCD
import com.gmail.absolutebollockscreations.gattlibrary.core.ftms.FtmsUuids.UUID_ELLIPTICAL_DATA
import com.gmail.absolutebollockscreations.gattlibrary.core.ftms.FtmsUuids.UUID_FITNESS_MACHINE_CONTROL_POINT
import com.gmail.absolutebollockscreations.gattlibrary.core.ftms.FtmsUuids.UUID_FITNESS_MACHINE_FEATURE
import com.gmail.absolutebollockscreations.gattlibrary.core.ftms.FtmsUuids.UUID_FITNESS_MACHINE_STATUS
import com.gmail.absolutebollockscreations.gattlibrary.core.ftms.FtmsUuids.UUID_FTMS_SERVICE
import com.gmail.absolutebollockscreations.gattlibrary.core.ftms.FtmsUuids.UUID_ROWING_MACHINE_DATA
import com.gmail.absolutebollockscreations.gattlibrary.core.ftms.FtmsUuids.UUID_STAIR_CLIMBER_DATA
import com.gmail.absolutebollockscreations.gattlibrary.core.ftms.FtmsUuids.UUID_STAIR_STEPPER_DATA
import com.gmail.absolutebollockscreations.gattlibrary.core.ftms.FtmsUuids.UUID_SUPPORTED_INCLINATION_RANGE
import com.gmail.absolutebollockscreations.gattlibrary.core.ftms.FtmsUuids.UUID_SUPPORTED_POWER_RANGE
import com.gmail.absolutebollockscreations.gattlibrary.core.ftms.FtmsUuids.UUID_SUPPORTED_RESISTANCE_RANGE
import com.gmail.absolutebollockscreations.gattlibrary.core.ftms.FtmsUuids.UUID_SUPPORTED_SPEED_RANGE
import com.gmail.absolutebollockscreations.gattlibrary.core.ftms.FtmsUuids.UUID_TREADMILL_DATA
import timber.log.Timber
import java.util.UUID
import com.gmail.absolutebollockscreations.gattlibrary.core.bytes.LittleEndian
import com.gmail.absolutebollockscreations.gattlibrary.core.ftms.FtmsSpec
import com.gmail.absolutebollockscreations.gattlibrary.core.ids.LifeFitnessUuids

@GattLibraryInternalApi
class FtmsProtocolHandler : BtEngineGattCore.ProtocolHandler {

    override val primaryServices: Set<UUID> = setOf(UUID_FTMS_SERVICE)

    override val extraCharacteristics: Set<UUID> = setOf(
        UUID_FITNESS_MACHINE_CONTROL_POINT,
        UUID_FITNESS_MACHINE_FEATURE,
        UUID_SUPPORTED_SPEED_RANGE,
        UUID_SUPPORTED_INCLINATION_RANGE,
        UUID_SUPPORTED_RESISTANCE_RANGE,
        UUID_SUPPORTED_POWER_RANGE,
        UUID_TREADMILL_DATA,
        UUID_ELLIPTICAL_DATA,
        UUID_STAIR_STEPPER_DATA,
        UUID_STAIR_CLIMBER_DATA,
        UUID_ROWING_MACHINE_DATA,
        UUID_BIKING_DATA,
        UUID_FITNESS_MACHINE_STATUS,
        // The setup transcript crosses vendor control and user-data parent services. Strict
        // callback routing therefore needs explicit FTMS-handler ownership for every step.
        *LifeFitnessSetup.CALLBACK_CHARACTERISTICS.toTypedArray(),
    )

    // One-shot post-CCCD FEATURE re-read.
    private var postCccdKickDone: Boolean = false
    // Make sure we only attempt provider advertising once per FTMS connection.
    private var providerAdvertiseAttempted: Boolean = false


    // Queue the "enable plan" exactly once per discovery → after FEATURES is parsed.
    private var enablePlanQueued: Boolean = false

    // Hoisted list of FTMS characteristics for which we enable notifications/indications.
    private val notifyUuids: List<UUID> = listOf(
        UUID_TREADMILL_DATA,
        UUID_ELLIPTICAL_DATA,
        UUID_STAIR_STEPPER_DATA,
        UUID_STAIR_CLIMBER_DATA,
        UUID_ROWING_MACHINE_DATA,
        UUID_BIKING_DATA,
        UUID_FITNESS_MACHINE_STATUS,       // notify
        UUID_FITNESS_MACHINE_CONTROL_POINT // indicate
    )

    // ───────────────────────── Services discovered ─────────────────────────

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    override fun onServicesDiscovered(
        engine: BtEngine,
        gatt: BluetoothGatt,
        services: List<BluetoothGattService>
    ): Boolean {
        val ftms = services.firstOrNull { it.uuid == UUID_FTMS_SERVICE } ?: run {
            Timber.tag(BtEngineState.TAG).w(
                "FTMS service (0x1826) not found; services=%d",
                services.size
            )
            return false
        }
        val context = engine.state.connectionRegistry.contextForGatt(gatt) ?: run {
            Timber.tag(BtEngineState.TAG).w("FTMS discovery ignored for unregistered GATT")
            return false
        }

        // Reset per-connection flags on fresh discovery.
        postCccdKickDone = false
        enablePlanQueued = false
        providerAdvertiseAttempted = false

        // Stash CP for later (writes / indications)
        engine.state.controlPointChar = ftms.getCharacteristic(UUID_FITNESS_MACHINE_CONTROL_POINT)

        // Log inventory (handy for vendor quirks)
        runCatching {
            ftms.characteristics.forEach { ch ->
                val props = propsString(ch.properties)
                val hasCccd = ch.getDescriptor(UUID_CCCD) != null
                Timber.tag(BtEngineState.TAG).d(
                    "FTMS char %s props=[%s] cccd=%s",
                    short(ch.uuid),
                    props,
                    hasCccd
                )
            }
        }

        // Infer machine type from present data characteristics and publish to DiscoveredDevice
        fun BluetoothGattService.hasChar(u: UUID) = getCharacteristic(u) != null
        val inferredActivity = when {
            ftms.hasChar(UUID_TREADMILL_DATA)      -> ActivityType.TREADMILL
            ftms.hasChar(UUID_ROWING_MACHINE_DATA) -> ActivityType.ROWING
            ftms.hasChar(UUID_BIKING_DATA)         -> ActivityType.BIKING
            ftms.hasChar(UUID_ELLIPTICAL_DATA)     -> ActivityType.ELLIPTICAL
            ftms.hasChar(UUID_STAIR_STEPPER_DATA)  -> ActivityType.STAIRSTEPPER
            ftms.hasChar(UUID_STAIR_CLIMBER_DATA)  -> ActivityType.STAIRCLIMBER
            else -> null
        }
        inferredActivity?.let { act ->
            engine.state.updateDeviceMachineFeatureByAddress(
                gatt.device?.address ?: return@let,
                act.ordinal
            )
            Timber.tag(BtEngineState.TAG).i("Inferred FTMS machine feature = %s", act.name)
        }

        // ---------- IMPORTANT: Read FEATURES FIRST ----------
        ftms.getCharacteristic(UUID_FITNESS_MACHINE_FEATURE)?.let {
            Timber.tag(BtEngineState.TAG).d("Queue READ 2ACC FIRST (features)")
            engine.pushRead(context, UUID_FITNESS_MACHINE_FEATURE)
        } ?: Timber.tag(BtEngineState.TAG).w("Features char (0x2ACC) missing")

        // NOTE: We DO NOT enable CCCDs here anymore.
        // CCCD enables are queued later, after FEATURES is parsed in onCharacteristicRead(...).

        // Life Fitness setup, if this machine turns out to need it (plan §6). Started here
        // rather than after subscriptions because that is the order the captures show: QZ issue
        // #1018 arms the standard FTMS CCCDs *after* the proprietary exchange completes, which
        // is consistent with a machine that sends nothing until it has been unlocked.
        //
        // A machine that is not Life Fitness returns from this immediately having issued
        // nothing.
        engine.startLifeFitnessSetupIfNeeded(context)

        return true
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun maybeStartProviderAdvertisingIfReady(
        engine: BtEngine,
        gatt: BluetoothGatt
    ) {
        if (providerAdvertiseAttempted) return
        providerAdvertiseAttempted = true

        // Machine must be inferred from discovery
        val activity = currentMachineActivity(engine, gatt)
        if (activity == null) {
            Timber.tag(BtEngineState.TAG)
                .w("Provider advertising not started: no machine ActivityType resolved yet")
            return
        }

        // At this point FEATURES (0x2ACC) has been parsed and supports* flags are set.
        // The claimed Supported-* Range reads are usually still queued; the provider loop
        // waits for them (up to 5 s) before advertising (LIB-9e, ProviderAdvertisingReadiness).
        engine.maybeStartProviderAdvertisingAfterFeatures(activity)
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    override fun onDescriptorWrite(
        engine: BtEngine,
        gatt: BluetoothGatt,
        desc: BluetoothGattDescriptor,
        status: Int
    ) {
        val charUuid = desc.characteristic?.uuid
        Timber.tag(BtEngineState.TAG).d(
            "onDescriptorWrite %s for %s status=%d",
            short(desc.uuid),
            short(charUuid),
            status
        )

        // The Life Fitness status subscription is the one transcript step that completes here
        // rather than on a characteristic write, because arming a CCCD is a descriptor write.
        if (charUuid == LifeFitnessUuids.UUID_LF_STATUS) {
            engine.advanceLifeFitnessSetup(gatt, status == BluetoothGatt.GATT_SUCCESS)
        }
    }

    /**
     * Every characteristic write on this link, including the Life Fitness setup transcript.
     *
     * The handler decides nothing: it identifies which run the completion belongs to and hands
     * the result to the driver, which owns the retry policy, the ordering and the terminal
     * outcome. Keeping the decisions out of here is what made them testable — see
     * `LifeFitnessSetupDriverTest`, which exercises branches this method can only forward.
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    override fun onCharacteristicWrite(
        engine: BtEngine,
        gatt: BluetoothGatt,
        ch: BluetoothGattCharacteristic,
        status: Int
    ) {
        val context = engine.state.connectionRegistry.contextForGatt(gatt) ?: return
        val inFlight = context.lifeFitnessSetup?.inFlight ?: return
        // A write for something other than the operation we are waiting on is not this run's
        // business — the FTMS control point shares this callback.
        if (ch.uuid != inFlight.characteristicUuid) return

        engine.advanceLifeFitnessSetup(gatt, status == BluetoothGatt.GATT_SUCCESS)
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    override fun onCccdPlanReady(engine: BtEngine, gatt: BluetoothGatt) {
        // The final descriptor may belong to another handler on this connection, so the
        // context-wide ready event is separate from descriptor ownership routing.
        if (!postCccdKickDone) {
            val context = engine.state.connectionRegistry.contextForGatt(gatt) ?: return
            postCccdKickDone = true
            Timber.tag(BtEngineState.TAG)
                .i("Merged CCCD plan armed → re-read FTMS FEATURES (0x2ACC)")
            engine.pushRead(context, UUID_FITNESS_MACHINE_FEATURE)
        }
    }

    // ───────────────────────── I/O fan-out ─────────────────────────
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    override fun onCharacteristicChanged(
        engine: BtEngine,
        gatt: BluetoothGatt,
        ch: BluetoothGattCharacteristic,
        value: ByteArray
    ) {
        Timber.tag(BtEngineState.TAG).v("Notify %s len=%d", short(ch.uuid), value.size)
        val emitter = engine.metricEmitterFor(
            gatt = gatt,
            characteristicUuid = ch.uuid,
            protocol = MetricProtocol.FTMS,
            sourceClass = MetricSourceClass.FTMS,
        ) ?: return
        when (ch.uuid) {
            UUID_FITNESS_MACHINE_CONTROL_POINT -> handleControlPointIndication(engine, gatt, value)
            UUID_FITNESS_MACHINE_STATUS        -> handleFitnessMachineStatus(engine, value)
            UUID_TREADMILL_DATA                -> BtEngineFtmsParsers.doTreadmill(
                emitter,
                value,
                onNativePace = { instantaneousRaw, averageRaw ->
                    // Preserved raw so the downstream encoder re-emits this machine's own
                    // quantisation instead of synthesising a 6 km/h-resolution field.
                    engine.state.treadmillNativePace.publish(
                        requireNotNull(emitter.sourceId.gattConnectionId),
                        instantaneousRaw,
                        averageRaw,
                    )
                },
            )
            UUID_ELLIPTICAL_DATA               -> BtEngineFtmsParsers.doElliptical(
                emitter,
                value,
                resistanceToLevel = { raw -> ellipticalResistanceLevel(engine.state, raw) },
            )
            UUID_STAIR_STEPPER_DATA            -> BtEngineFtmsParsers.doStepper(emitter, value)
            UUID_STAIR_CLIMBER_DATA            -> BtEngineFtmsParsers.doClimber(emitter, value)
            UUID_ROWING_MACHINE_DATA           -> BtEngineFtmsParsers.doRower(
                emitter,
                value,
                // Phase 4c quirk selection. A name prefix is the only identity these machines
                // expose; the flag word and packet length still have to match before any
                // override fires.
                deviceName = discoveredDeviceName(engine, gatt),
            )
            UUID_BIKING_DATA                   -> BtEngineFtmsParsers.doBike(
                emitter,
                value,
                // Same quirk-selection contract as the rower above. The Life Fitness bike
                // under-reports its cadence presence bit; name prefix plus exact flag word
                // plus exact packet length must all match before the correction applies.
                deviceName = discoveredDeviceName(engine, gatt),
            )
            else -> Timber.tag(BtEngineState.TAG)
                .w("Notify from unknown FTMS char %s: %s", short(ch.uuid), value.toHex())
        }
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    override fun onCharacteristicRead(
        engine: BtEngine,
        gatt: BluetoothGatt,
        ch: BluetoothGattCharacteristic,
        value: ByteArray,
        status: Int
    ) {
        Timber.tag(BtEngineState.TAG).d(
            "Read %s status=%d len=%d",
            short(ch.uuid),
            status,
            value.size
        )
        if (status != BluetoothGatt.GATT_SUCCESS) {
            Timber.tag(BtEngineState.TAG).w(
                "Read failed %s status=%d",
                short(ch.uuid),
                status
            )
            return
        }
        when (ch.uuid) {
            UUID_FITNESS_MACHINE_FEATURE -> {
                val context = engine.state.connectionRegistry.contextForGatt(gatt) ?: run {
                    Timber.tag(BtEngineState.TAG).w("FTMS feature read ignored for unregistered GATT")
                    return
                }
                // Parse features (sets engine.state.supportsSpeed/Inclination/Resistance/Power + may infer type internally)
                BtEngineCapabilities.parseFitnessMachineFeatures(engine, value)

                // Log machine feature using the connected device’s DiscoveredDevice.machineFeature ordinal
                val machineLabel = currentMachineFeatureLabel(engine, gatt) ?: "unknown"
                Timber.tag(BtEngineState.TAG).i(
                    "Feature discovery: supports speed=%s incline=%s resistance=%s power=%s | machine=%s",
                    engine.state.supportsSpeed,
                    engine.state.supportsInclination,
                    engine.state.supportsResistance,
                    engine.state.supportsPower,
                    machineLabel
                )

                // Now that we know what’s supported, queue the capability-range reads.
                maybeQueueCapabilityReads(engine)

                // Queue CCCD enables exactly once, AFTER FEATURES (and range reads have been queued).
                if (!enablePlanQueued) {
                    enablePlanQueued = true

                    val ftmsSvc = gatt.services?.firstOrNull { it.uuid == UUID_FTMS_SERVICE }

                    var willEnableAny = false
                    notifyUuids.forEach { u ->
                        ftmsSvc?.getCharacteristic(u)?.let { c ->
                            val wantsInd = (c.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0
                            val wantsNot = (c.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0
                            val hasCccd  = c.getDescriptor(UUID_CCCD) != null
                            if ((wantsInd || wantsNot) && hasCccd) {
                                willEnableAny = true
                                Timber.tag(BtEngineState.TAG).d(
                                    "Enable plan (post FEATURES) %s → indicate=%s notify=%s",
                                    short(u), wantsInd, wantsNot
                                )
                                // This goes through the serialized GATT queue.
                                engine.enableNotifications(context, c)
                            }
                        }
                    }

                    // If there was nothing to enable, mark ready; otherwise GattCore will flip ready when pending hits 0.
                    if (!willEnableAny) {
                        engine.state.setCccdReady(context, true)
                        Timber.tag(BtEngineState.TAG).w("No FTMS CCCDs to enable → cccdReady=true")
                    } else {
                        engine.state.setCccdReady(context, false)
                    }
                }

                // NEW: features + machine type are known → kick provider advertising.
                maybeStartProviderAdvertisingIfReady(engine, gatt)
            }
            UUID_SUPPORTED_SPEED_RANGE       -> BtEngineCapabilities.parseSupportedSpeedRange(engine, value)
            UUID_SUPPORTED_INCLINATION_RANGE -> BtEngineCapabilities.parseSupportedInclinationRange(engine, value)
            UUID_SUPPORTED_RESISTANCE_RANGE  -> BtEngineCapabilities.parseSupportedResistanceRange(engine, value)
            UUID_SUPPORTED_POWER_RANGE       -> BtEngineCapabilities.parseSupportedPowerRange(engine, value)
            else -> Timber.tag(BtEngineState.TAG)
                .d("Read from non-handled FTMS char %s", short(ch.uuid))
        }
    }

    // ───────────────────────── CP indication parsing ─────────────────────────
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun handleControlPointIndication(
        engine: BtEngine,
        gatt: BluetoothGatt,
        value: ByteArray
    ) {
        Timber.tag(BtEngineState.TAG).d("FTMS CP indication: %s", value.toHex())

        // Correlation itself lives in `correlateControlPointResponse` so it can be tested
        // without a live GATT (plan §11.7). This handler keeps the GATT-shaped consequences.
        val outcome = correlateControlPointResponse(value)
        when (outcome) {
            is CpResponseOutcome.Malformed -> {
                Timber.tag(BtEngineState.TAG).w("CP response ignored: %s", outcome.why)
                return
            }

            is CpResponseOutcome.Unhandled -> {
                // Always a FitBridge omission: a write was added without a response branch.
                Timber.tag(BtEngineState.TAG).w(
                    "CP response for unhandled opcode 0x%02X (rc=0x%02X)",
                    outcome.requestOpcode,
                    outcome.resultCode,
                )
                return
            }

            is CpResponseOutcome.ControlLost -> {
                onControlLost(engine, gatt, outcome.detail)
                return
            }

            is CpResponseOutcome.Rejected ->
                engine.state.setGattErrorFor(gatt, outcome.detail)

            is CpResponseOutcome.Accepted -> Unit
        }

        // Everything above is now decided by the outcome. What remains is the state the
        // outcome model deliberately does not express: the two opcodes that change whether
        // FitBridge holds control of the machine at all.
        if (outcome is CpResponseOutcome.Accepted) {
            when (outcome.requestOpcode) {
                FtmsSpec.Opcode.REQUEST_CONTROL -> {
                    engine.state.setHaveControl(true)
                    Timber.tag(BtEngineState.TAG).i("Control granted")
                }

                FtmsSpec.Opcode.RESET -> {
                    engine.state.setHaveControl(false)
                    Timber.tag(BtEngineState.TAG).i("FTMS CP: RESET success; control cleared")
                }

                else -> Timber.tag(BtEngineState.TAG)
                    .i("CP 0x%02X accepted", outcome.requestOpcode)
            }
        }
    }

    private fun onControlLost(engine: BtEngine, gatt: BluetoothGatt?, reason: String) {
        engine.state.setHaveControl(false)
        gatt?.let { engine.state.setGattErrorFor(it, reason) }
        // Tell local provider-side stack that control was lost so it can mirror a status.
        engine.serviceControlSink?.onControlPermissionLost()
        Timber.tag(BtEngineState.TAG).w("Control lost: %s", reason)
    }

    // ───────────────────────── Fitness Machine Status parsing ─────────────────────────

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun handleFitnessMachineStatus(
        engine: BtEngine,
        value: ByteArray
    ) {
        applyFitnessMachineStatus(engine.state, engine.serviceControlSink, value)
    }

    // ───────────────────────── helpers ─────────────────────────
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun maybeQueueCapabilityReads(engine: BtEngine) {
        val st = engine.state
        if (st.supportsSpeed)       engine.readSupportedSpeedRange()
        if (st.supportsInclination) engine.readSupportedInclinationRange()
        if (st.supportsResistance)  engine.readSupportedResistanceRange()
        if (st.supportsPower)       engine.readSupportedPowerRange()
    }

    /**
     * Advertised name of the connected device, for Phase 4c quirk fingerprinting only.
     *
     * Plan §0.7 ranks name evidence below service and characteristic evidence and this does not
     * change that: a name never selects a protocol here, it only narrows which non-conforming
     * layout may apply, and the flag word and packet length must match as well before one does.
     */
    private fun discoveredDeviceName(engine: BtEngine, gatt: BluetoothGatt): String? {
        val addr = gatt.device?.address ?: return null
        return engine.state.devices.value.values
            .firstOrNull { it.address.equals(addr, ignoreCase = true) }
            ?.name
    }

    /** Get friendly label for the connected device’s machine feature (from DiscoveredDevice.machineFeature). */
    private fun currentMachineFeatureLabel(engine: BtEngine, gatt: BluetoothGatt): String? {
        val addr = gatt.device?.address ?: return null
        val ord = engine.state.devices.value.values
            .firstOrNull { it.address.equals(addr, ignoreCase = true) }
            ?.machineFeature ?: return null
        val act = ActivityType.values().getOrNull(ord) ?: return null
        return when (act) {
            ActivityType.BIKING       -> "Indoor Bike"
            ActivityType.ELLIPTICAL   -> "Elliptical"
            ActivityType.ROWING       -> "Indoor Rower"
            ActivityType.STAIRCLIMBER -> "Stair Climber"
            ActivityType.STAIRSTEPPER -> "Stair Stepper"
            ActivityType.TREADMILL    -> "Treadmill"
            ActivityType.HEARTRATE    -> "Heart Rate Only"
        }
    }

    /** Resolve the connected device’s ActivityType (from DiscoveredDevice.machineFeature). */
    private fun currentMachineActivity(engine: BtEngine, gatt: BluetoothGatt): ActivityType? {
        val addr = gatt.device?.address ?: return null
        val ord = engine.state.devices.value.values
            .firstOrNull { it.address.equals(addr, ignoreCase = true) }
            ?.machineFeature ?: return null
        return ActivityType.values().getOrNull(ord)
    }


    private fun propsString(props: Int): String {
        val list = mutableListOf<String>()
        if ((props and BluetoothGattCharacteristic.PROPERTY_READ) != 0) list += "READ"
        if ((props and BluetoothGattCharacteristic.PROPERTY_WRITE) != 0) list += "WRITE"
        if ((props and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0) list += "WRITE_NR"
        if ((props and BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0) list += "NOTIFY"
        if ((props and BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0) list += "INDICATE"
        if ((props and BluetoothGattCharacteristic.PROPERTY_SIGNED_WRITE) != 0) list += "SIGNED"
        if ((props and BluetoothGattCharacteristic.PROPERTY_BROADCAST) != 0) list += "BCAST"
        if ((props and BluetoothGattCharacteristic.PROPERTY_EXTENDED_PROPS) != 0) list += "EXT"
        return list.joinToString("|")
    }

    private fun short(u: UUID?): String = when (u) {
        null -> "null"
        else -> u.toString().let { s ->
            if (s.startsWith("0000") && s.endsWith("-0000-1000-8000-00805f9b34fb")) {
                s.substring(4, 8)
            } else {
                s
            }
        }
    }

    private fun ByteArray.toHex(): String =
        joinToString(" ") { b -> "%02X".format(b) }
}

/** Applies the observable state and provider callbacks produced by upstream FTMS 0x2ADA. */
/**
 * What a Control Point response means, independent of any `BluetoothGatt`.
 *
 * Extracted 2026-08-10 for plan §11.7. Correlation logic previously lived inside a private
 * handler that needs a live GATT, so **no upstream control response was testable at all** —
 * which is how `0x11` came to be sent without a response branch: the write was added, the
 * response fell through an inexhaustive `when`, and nothing failed.
 *
 * Mirrors the [applyFitnessMachineStatus] precedent: the caller keeps the GATT-shaped work
 * (error text, sink notification) and this decides.
 */
internal sealed interface CpResponseOutcome {
    /** Frame is not a Control Point response, or is too short to be one. */
    data class Malformed(val why: String) : CpResponseOutcome

    /** The machine accepted the request. */
    data class Accepted(val requestOpcode: Byte) : CpResponseOutcome

    /** The machine rejected it, and control is still held. */
    data class Rejected(val requestOpcode: Byte, val resultCode: Byte, val detail: String) :
        CpResponseOutcome

    /** The machine says control is not ours. The session must stop believing it is. */
    data class ControlLost(val requestOpcode: Byte, val detail: String) : CpResponseOutcome

    /** A response arrived for an opcode with no branch. Always a FitBridge omission. */
    data class Unhandled(val requestOpcode: Byte, val resultCode: Byte) : CpResponseOutcome
}

/**
 * Correlate one Control Point indication against the request it answers.
 *
 * FTMS responses carry the originating opcode, so correlation is per frame rather than by
 * queue position — a machine that answers out of order, or answers a request FitBridge never
 * sent, is still interpreted against the opcode it names.
 */
internal fun correlateControlPointResponse(value: ByteArray): CpResponseOutcome {
    if (value.size < 3) return CpResponseOutcome.Malformed("frame too short (len=${value.size})")

    val header = value[0]
    val requestOpcode = value[1]
    val resultCode = value[2]
    if (header != FtmsSpec.Opcode.RESPONSE_CODE) {
        return CpResponseOutcome.Malformed("unexpected header 0x%02X".format(header))
    }

    fun label(code: Byte) = when (code) {
        FtmsSpec.Result.SUCCESS               -> "SUCCESS"
        FtmsSpec.Result.OPCODE_NOT_SUPPORTED  -> "OPCODE_NOT_SUPPORTED"
        FtmsSpec.Result.INVALID_PARAMETER     -> "INVALID_PARAMETER"
        FtmsSpec.Result.OPERATION_FAILED      -> "OPERATION_FAILED"
        FtmsSpec.Result.CONTROL_NOT_PERMITTED -> "CONTROL_NOT_PERMITTED"
        else                         -> "UNKNOWN(0x%02X)".format(code)
    }

    val known = requestOpcode in setOf(
        FtmsSpec.Opcode.REQUEST_CONTROL,
        FtmsSpec.Opcode.RESET,
        FtmsSpec.Opcode.START_RESUME,
        FtmsSpec.Opcode.STOP_PAUSE,
        FtmsSpec.Opcode.SET_TARGET_SPEED,
        FtmsSpec.Opcode.SET_TARGET_INCLINATION,
        FtmsSpec.Opcode.SET_TARGET_RESISTANCE,
        FtmsSpec.Opcode.SET_TARGET_POWER,
        FtmsSpec.Opcode.SET_INDOOR_BIKE_SIMULATION,
    )
    if (!known) return CpResponseOutcome.Unhandled(requestOpcode, resultCode)

    return when {
        resultCode == FtmsSpec.Result.SUCCESS -> CpResponseOutcome.Accepted(requestOpcode)

        resultCode == FtmsSpec.Result.CONTROL_NOT_PERMITTED -> CpResponseOutcome.ControlLost(
            requestOpcode,
            "0x%02X: control not permitted".format(requestOpcode),
        )

        // Request Control is the one opcode where *any* failure means control was never
        // established. Treating "not supported" as a mere rejection would leave the session
        // believing it holds control of a machine that has never granted it.
        requestOpcode == FtmsSpec.Opcode.REQUEST_CONTROL -> CpResponseOutcome.ControlLost(
            requestOpcode,
            "request control: ${label(resultCode)}",
        )

        else -> CpResponseOutcome.Rejected(
            requestOpcode,
            resultCode,
            "0x%02X: %s".format(requestOpcode, label(resultCode)),
        )
    }
}

@GattLibraryInternalApi
fun applyFitnessMachineStatus(
    state: BtEngineState,
    serviceControlSink: BtServiceControlSink?,
    value: ByteArray,
) {
    if (value.isEmpty()) {
        Timber.tag(BtEngineState.TAG).w("FTMS Status: empty payload")
        return
    }

    val status = value[0]
    state.setLastFtmsStatusCode(status)

    // Deliberately zero when short (LIB-18b R3); unlike s16LE below. Changing it is its own unit.
    fun u16LE(offset: Int): Int = LittleEndian.u16OrNull(value, offset) ?: 0

    /**
     * Signed little-endian 16-bit, or null when the payload cannot contain it. Signed FTMS
     * targets must not silently fall back to zero: zero is itself a valid movement target.
     */
    fun s16LE(offset: Int): Int? = LittleEndian.s16OrNull(value, offset)

    Timber.tag(BtEngineState.TAG).d(
        "FTMS Status opcode=0x%02X payload=%s",
        status,
        value.joinToString(" ") { b -> "%02X".format(b) },
    )

    when (status) {
        FtmsSpec.Status.RESET -> {
            Timber.tag(BtEngineState.TAG).i("FTMS Status: MACHINE_RESET")
            state.setSafetyKeyEngaged(false)
            state.setHaveControl(false)
        }

        FtmsSpec.Status.STARTED_OR_RESUMED_BY_USER -> {
            Timber.tag(BtEngineState.TAG).i("FTMS Status: USER_STARTED_OR_RESUMED")
            state.setIsRunning(true)
        }

        FtmsSpec.Status.STOPPED_OR_PAUSED_BY_USER -> {
            Timber.tag(BtEngineState.TAG).i("FTMS Status: USER_STOPPED_OR_PAUSED")
            state.setIsRunning(false)
        }

        FtmsSpec.Status.STOPPED_BY_SAFETY_KEY -> {
            Timber.tag(BtEngineState.TAG).w("FTMS Status: STOPPED_BY_SAFETY_KEY")
            state.setSafetyKeyEngaged(true)
            state.setIsRunning(false)
            serviceControlSink?.onSafetyKeyPulled()
        }

        FtmsSpec.Status.CONTROL_PERMISSION_LOST -> {
            Timber.tag(BtEngineState.TAG).w("FTMS Status: CONTROL_PERMISSION_LOST")
            state.setHaveControl(false)
            serviceControlSink?.onControlPermissionLost()
        }

        FtmsSpec.Status.TARGET_SPEED_CHANGED -> {
            val kmh = u16LE(1) / FtmsSpec.SPEED_SCALE
            state.setTargetSpeedKmh(kmh)
            serviceControlSink?.onTargetSpeedChangedKmh(kmh)
            Timber.tag(BtEngineState.TAG)
                .i("FTMS Status: TARGET_SPEED_CHANGED → %.2f km/h", kmh)
        }

        FtmsSpec.Status.TARGET_INCLINE_CHANGED -> {
            // Inclination is sint16 in 0.1 percent grade. Read unsigned, a decline target of
            // -5.0 % decoded as 6553.5 % instead of a negative grade.
            val raw = s16LE(1)
            if (raw == null) {
                Timber.tag(BtEngineState.TAG).w(
                    "FTMS Status: malformed TARGET_INCLINE_CHANGED payload=%s",
                    value.joinToString(" ") { b -> "%02X".format(b) },
                )
                return
            }
            val percent = raw / FtmsSpec.INCLINE_SCALE
            state.setTargetInclinePercent(percent)
            serviceControlSink?.onTargetInclineChangedPercent(percent)
            Timber.tag(BtEngineState.TAG)
                .i("FTMS Status: TARGET_INCLINE_CHANGED → %.1f %%", percent)
        }

        FtmsSpec.Status.TARGET_RESISTANCE_CHANGED -> {
            val level = decodeTargetResistanceStatus(value)
            if (level == null) {
                Timber.tag(BtEngineState.TAG).w(
                    "FTMS Status: malformed TARGET_RESISTANCE_CHANGED payload=%s",
                    value.joinToString(" ") { b -> "%02X".format(b) },
                )
                return
            }
            state.setTargetResistanceLevel(level)
            serviceControlSink?.onTargetResistanceLevelChanged(level)
            Timber.tag(BtEngineState.TAG)
                .i("FTMS Status: TARGET_RESISTANCE_CHANGED → %.1f", level)
        }

        FtmsSpec.Status.TARGET_POWER_CHANGED -> {
            val watts = (u16LE(1) / FtmsSpec.POWER_SCALE).toInt()
            state.setTargetPowerWatts(watts)
            serviceControlSink?.onTargetPowerChangedWatts(watts)
            Timber.tag(BtEngineState.TAG)
                .i("FTMS Status: TARGET_POWER_CHANGED → %d W", watts)
        }

        FtmsSpec.Status.TARGET_HEART_RATE_CHANGED -> {
            val bpm = if (value.size > 1) value[1].toInt() and 0xFF else 0
            state.setTargetHeartRateBpm(if (bpm == 0) null else bpm)
            serviceControlSink?.onTargetHeartRateChangedBpm(bpm)
            Timber.tag(BtEngineState.TAG)
                .i("FTMS Status: TARGET_HEART_RATE_CHANGED → %d bpm", bpm)
        }

        else -> Timber.tag(BtEngineState.TAG).d(
            "FTMS Status: unhandled opcode=0x%02X payload=%s",
            status,
            value.joinToString(" ") { b -> "%02X".format(b) },
        )
    }
}

/**
 * Decode Fitness Machine Status 0x07 across both FTMS wire definitions.
 *
 * Older/conformant devices use a UINT8 target (opcode + one byte). Newer FTMS
 * revisions also define a SINT16 target (opcode + two little-endian bytes).
 * Reject every other shape instead of silently converting a truncated value to
 * zero, which would be mirrored back to a connected peripheral client.
 */
internal fun decodeTargetResistanceStatus(value: ByteArray): Float? {
    if (value.firstOrNull() != FtmsSpec.Status.TARGET_RESISTANCE_CHANGED) return null
    val raw = when (value.size) {
        2 -> value[1].toInt() and 0xFF
        3 -> {
            val bits = (value[1].toInt() and 0xFF) or
                ((value[2].toInt() and 0xFF) shl 8)
            bits.toShort().toInt()
        }
        else -> return null
    }
    return raw / FtmsSpec.RESISTANCE_SCALE
}

/**
 * Cross Trainer Resistance Level: wire value -> public level, honouring a
 * machine that contradicts the spec's `DecimalExponent -1`.
 *
 * Owns the latch described on [BtEngineState.resistanceIsRawLevels]. Once a
 * machine has produced one sample that can only be raw levels, every later
 * sample is read that way for the rest of the connection, including the zeroes
 * it reports at rest which carry no evidence either way.
 */
internal fun ellipticalResistanceLevel(state: BtEngineState, raw: Int): Double {
    if (state.resistanceIsRawLevels) return raw.toDouble()

    val encoding = FtmsResistanceScale.decide(
        raw = raw,
        min = state.resistanceMin,
        max = state.resistanceMax,
        incr = state.resistanceIncr,
        known = state.resistanceRangeKnown,
    )
    if (encoding == FtmsResistanceScale.Encoding.RAW_LEVELS) {
        state.resistanceIsRawLevels = true
        Timber.tag(BtEngineState.TAG).i(
            "Cross Trainer resistance reads as raw levels, not tenths: raw=%d is outside the " +
                "machine's declared range %.1f-%.1f when divided by ten. Latched for this " +
                "connection.",
            raw, state.resistanceMin, state.resistanceMax,
        )
        return raw.toDouble()
    }
    return raw / 10.0
}
