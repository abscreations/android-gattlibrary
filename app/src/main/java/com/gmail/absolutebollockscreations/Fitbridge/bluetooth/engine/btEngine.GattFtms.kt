// =================================================================================================
// File: btEngine.GattFtms.kt
// Package: com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine
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
//         - STATUS_MACHINE_RESET:
//               → clear safety key, clear control.
//         - STATUS_USER_STARTED_OR_RESUMED / STATUS_USER_STOPPED_OR_PAUSED:
//               → update isRunning flag.
//         - STATUS_FITNESS_MACHINE_STOPPED_BY_SAFETY_KEY:
//               → set safetyKeyEngaged=true, isRunning=false, and call
//                 serviceControlSink?.onSafetyKeyPulled().
//         - STATUS_CONTROL_PERMISSION_LOST:
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

package com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine

import android.Manifest
import android.bluetooth.*
import androidx.annotation.RequiresPermission
import com.gmail.absolutebollockscreations.Fitbridge.ActivityType
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine.BluetoothIds.UUID_BIKING_DATA
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine.BluetoothIds.UUID_CCCD
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine.BluetoothIds.UUID_ELLIPTICAL_DATA
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine.BluetoothIds.UUID_FITNESS_MACHINE_CONTROL_POINT
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine.BluetoothIds.UUID_FITNESS_MACHINE_FEATURE
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine.BluetoothIds.UUID_FITNESS_MACHINE_STATUS
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine.BluetoothIds.UUID_FTMS_SERVICE
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine.BluetoothIds.UUID_ROWING_MACHINE_DATA
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine.BluetoothIds.UUID_STAIR_CLIMBER_DATA
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine.BluetoothIds.UUID_STAIR_STEPPER_DATA
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine.BluetoothIds.UUID_SUPPORTED_INCLINATION_RANGE
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine.BluetoothIds.UUID_SUPPORTED_POWER_RANGE
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine.BluetoothIds.UUID_SUPPORTED_RESISTANCE_RANGE
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine.BluetoothIds.UUID_SUPPORTED_SPEED_RANGE
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine.BluetoothIds.UUID_TREADMILL_DATA
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine.BluetoothIds.FTMS_INCLINE_SCALE
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine.BluetoothIds.FTMS_POWER_SCALE
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine.BluetoothIds.FTMS_RESISTANCE_SCALE
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine.BluetoothIds.FTMS_SPEED_SCALE
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine.BluetoothIds.OPCODE_RESPONSE_CODE
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine.BluetoothIds.OPCODE_RESET
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine.BluetoothIds.OPCODE_SET_TARGET_INCLINATION
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine.BluetoothIds.OPCODE_SET_TARGET_POWER
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine.BluetoothIds.OPCODE_SET_TARGET_RESISTANCE
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine.BluetoothIds.OPCODE_SET_TARGET_SPEED
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine.BluetoothIds.OPCODE_START_RESUME
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine.BluetoothIds.OPCODE_STOP_PAUSE
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine.BluetoothIds.RESULT_CONTROL_NOT_PERMITTED
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine.BluetoothIds.RESULT_INVALID_PARAMETER
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine.BluetoothIds.RESULT_OPCODE_NOT_SUPPORTED
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine.BluetoothIds.RESULT_OPERATION_FAILED
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine.BluetoothIds.RESULT_SUCCESS
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine.BluetoothIds.STATUS_CONTROL_PERMISSION_LOST
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine.BluetoothIds.STATUS_FITNESS_MACHINE_STOPPED_BY_SAFETY_KEY
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine.BluetoothIds.STATUS_MACHINE_RESET
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine.BluetoothIds.STATUS_TARGET_HEART_RATE_CHANGED
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine.BluetoothIds.STATUS_TARGET_INCLINE_CHANGED
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine.BluetoothIds.STATUS_TARGET_POWER_CHANGED
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine.BluetoothIds.STATUS_TARGET_RESISTANCE_LEVEL_CHANGED
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine.BluetoothIds.STATUS_TARGET_SPEED_CHANGED
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine.BluetoothIds.STATUS_USER_STARTED_OR_RESUMED
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine.BluetoothIds.STATUS_USER_STOPPED_OR_PAUSED
import timber.log.Timber
import java.util.UUID

internal class FtmsProtocolHandler : BtEngineGattCore.ProtocolHandler {

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
        UUID_FITNESS_MACHINE_STATUS
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
        // Ranges are either already parsed or in-flight, but we at least know which
        // targets are supported.
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
            UUID_TREADMILL_DATA                -> BtEngineFtmsParsers.doTreadmill(emitter, value)
            UUID_ELLIPTICAL_DATA               -> BtEngineFtmsParsers.doElliptical(emitter, value)
            UUID_STAIR_STEPPER_DATA            -> BtEngineFtmsParsers.doStepper(emitter, value)
            UUID_STAIR_CLIMBER_DATA            -> BtEngineFtmsParsers.doClimber(emitter, value)
            UUID_ROWING_MACHINE_DATA           -> BtEngineFtmsParsers.doRower(emitter, value)
            UUID_BIKING_DATA                   -> BtEngineFtmsParsers.doBike(emitter, value)
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
        if (value.size < 3) {
            Timber.tag(BtEngineState.TAG).w("CP frame too short (len=%d)", value.size)
            return
        }

        val header = value[0]
        val requestOpcode = value[1]
        val resultCode = value[2]
        if (header != OPCODE_RESPONSE_CODE) {
            Timber.tag(BtEngineState.TAG).w("Unexpected CP header 0x%02X", header)
            return
        }

        if (requestOpcode == OPCODE_RESET) {
            if (resultCode == RESULT_SUCCESS) {
                engine.state.setHaveControl(false)
                Timber.tag(BtEngineState.TAG).i("FTMS CP: RESET success; control cleared.")
            } else {
                engine.state.setGattErrorFor(
                    gatt,
                    "RESET failed (rc=0x%02X)".format(resultCode)
                )
            }
            return
        }

        fun resultToString(code: Byte) = when (code) {
            RESULT_SUCCESS               -> "SUCCESS"
            RESULT_OPCODE_NOT_SUPPORTED  -> "OPCODE_NOT_SUPPORTED"
            RESULT_INVALID_PARAMETER     -> "INVALID_PARAMETER"
            RESULT_OPERATION_FAILED      -> "OPERATION_FAILED"
            RESULT_CONTROL_NOT_PERMITTED -> "CONTROL_NOT_PERMITTED"
            else                         -> "UNKNOWN(0x%02X)".format(code)
        }
        Timber.tag(BtEngineState.TAG).i(
            "CP resp for 0x%02X → %s",
            requestOpcode,
            resultToString(resultCode)
        )

        when (requestOpcode) {
            // REQUEST_CONTROL and START/RESUME are now only *sent* from higher layers,
            // but we still fully interpret their responses here.

            BluetoothIds.OPCODE_REQUEST_CONTROL -> when (resultCode) {
                RESULT_SUCCESS -> {
                    engine.state.setHaveControl(true)
                    Timber.tag(BtEngineState.TAG).i("Control granted")
                }
                RESULT_CONTROL_NOT_PERMITTED -> onControlLost(engine, gatt, "Request control: not permitted")
                RESULT_OPCODE_NOT_SUPPORTED  -> onControlLost(engine, gatt, "Request control: not supported by device")
                RESULT_OPERATION_FAILED      -> onControlLost(engine, gatt, "Request control: operation failed")
            }

            OPCODE_START_RESUME -> when (resultCode) {
                RESULT_SUCCESS               -> Timber.tag(BtEngineState.TAG).i("Start/Resume accepted by device")
                RESULT_CONTROL_NOT_PERMITTED -> onControlLost(engine, gatt, "Start/Resume: control not permitted")
                RESULT_INVALID_PARAMETER     -> engine.state.setGattErrorFor(gatt, "Start/Resume: invalid parameter")
                RESULT_OPCODE_NOT_SUPPORTED  -> engine.state.setGattErrorFor(gatt, "Start/Resume: opcode not supported")
                RESULT_OPERATION_FAILED      -> engine.state.setGattErrorFor(gatt, "Start/Resume: operation failed")
            }

            OPCODE_STOP_PAUSE -> when (resultCode) {
                RESULT_SUCCESS               -> Timber.tag(BtEngineState.TAG).i("Stop/Pause accepted by device")
                RESULT_CONTROL_NOT_PERMITTED -> onControlLost(engine, gatt, "Stop/Pause: control not permitted")
                RESULT_OPCODE_NOT_SUPPORTED  -> engine.state.setGattErrorFor(gatt, "Stop/Pause: opcode not supported")
                RESULT_OPERATION_FAILED      -> engine.state.setGattErrorFor(gatt, "Stop/Pause: operation failed")
            }

            OPCODE_SET_TARGET_SPEED,
            OPCODE_SET_TARGET_INCLINATION,
            OPCODE_SET_TARGET_RESISTANCE,
            OPCODE_SET_TARGET_POWER -> when (resultCode) {
                RESULT_SUCCESS               -> Timber.tag(BtEngineState.TAG)
                    .i("Set target 0x%02X accepted", requestOpcode)
                RESULT_CONTROL_NOT_PERMITTED -> onControlLost(
                    engine,
                    gatt,
                    "Set target 0x%02X: control not permitted".format(requestOpcode)
                )
                RESULT_INVALID_PARAMETER     -> engine.state.setGattErrorFor(
                    gatt,
                    "Set target 0x%02X: invalid parameter".format(requestOpcode)
                )
                RESULT_OPCODE_NOT_SUPPORTED  -> engine.state.setGattErrorFor(
                    gatt,
                    "Set target 0x%02X: not supported".format(requestOpcode)
                )
                RESULT_OPERATION_FAILED      -> engine.state.setGattErrorFor(
                    gatt,
                    "Set target 0x%02X: operation failed".format(requestOpcode)
                )
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
internal fun applyFitnessMachineStatus(
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

    fun u16LE(offset: Int): Int {
        if (value.size < offset + 2) return 0
        val b0 = value[offset].toInt() and 0xFF
        val b1 = value[offset + 1].toInt() and 0xFF
        return b0 or (b1 shl 8)
    }

    /**
     * Signed little-endian 16-bit, or null when the payload cannot contain it. Signed FTMS
     * targets must not silently fall back to zero: zero is itself a valid movement target.
     */
    fun s16LE(offset: Int): Int? {
        if (value.size < offset + 2) return null
        val b0 = value[offset].toInt() and 0xFF
        val b1 = value[offset + 1].toInt() and 0xFF
        return (b0 or (b1 shl 8)).toShort().toInt()
    }

    Timber.tag(BtEngineState.TAG).d(
        "FTMS Status opcode=0x%02X payload=%s",
        status,
        value.joinToString(" ") { b -> "%02X".format(b) },
    )

    when (status) {
        STATUS_MACHINE_RESET -> {
            Timber.tag(BtEngineState.TAG).i("FTMS Status: MACHINE_RESET")
            state.setSafetyKeyEngaged(false)
            state.setHaveControl(false)
        }

        STATUS_USER_STARTED_OR_RESUMED -> {
            Timber.tag(BtEngineState.TAG).i("FTMS Status: USER_STARTED_OR_RESUMED")
            state.setIsRunning(true)
        }

        STATUS_USER_STOPPED_OR_PAUSED -> {
            Timber.tag(BtEngineState.TAG).i("FTMS Status: USER_STOPPED_OR_PAUSED")
            state.setIsRunning(false)
        }

        STATUS_FITNESS_MACHINE_STOPPED_BY_SAFETY_KEY -> {
            Timber.tag(BtEngineState.TAG).w("FTMS Status: STOPPED_BY_SAFETY_KEY")
            state.setSafetyKeyEngaged(true)
            state.setIsRunning(false)
            serviceControlSink?.onSafetyKeyPulled()
        }

        STATUS_CONTROL_PERMISSION_LOST -> {
            Timber.tag(BtEngineState.TAG).w("FTMS Status: CONTROL_PERMISSION_LOST")
            state.setHaveControl(false)
            serviceControlSink?.onControlPermissionLost()
        }

        STATUS_TARGET_SPEED_CHANGED -> {
            val kmh = u16LE(1) / FTMS_SPEED_SCALE
            state.setTargetSpeedKmh(kmh)
            serviceControlSink?.onTargetSpeedChangedKmh(kmh)
            Timber.tag(BtEngineState.TAG)
                .i("FTMS Status: TARGET_SPEED_CHANGED → %.2f km/h", kmh)
        }

        STATUS_TARGET_INCLINE_CHANGED -> {
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
            val percent = raw / FTMS_INCLINE_SCALE
            state.setTargetInclinePercent(percent)
            serviceControlSink?.onTargetInclineChangedPercent(percent)
            Timber.tag(BtEngineState.TAG)
                .i("FTMS Status: TARGET_INCLINE_CHANGED → %.1f %%", percent)
        }

        STATUS_TARGET_RESISTANCE_LEVEL_CHANGED -> {
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

        STATUS_TARGET_POWER_CHANGED -> {
            val watts = (u16LE(1) / FTMS_POWER_SCALE).toInt()
            state.setTargetPowerWatts(watts)
            serviceControlSink?.onTargetPowerChangedWatts(watts)
            Timber.tag(BtEngineState.TAG)
                .i("FTMS Status: TARGET_POWER_CHANGED → %d W", watts)
        }

        STATUS_TARGET_HEART_RATE_CHANGED -> {
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
    if (value.firstOrNull() != STATUS_TARGET_RESISTANCE_LEVEL_CHANGED) return null
    val raw = when (value.size) {
        2 -> value[1].toInt() and 0xFF
        3 -> {
            val bits = (value[1].toInt() and 0xFF) or
                ((value[2].toInt() and 0xFF) shl 8)
            bits.toShort().toInt()
        }
        else -> return null
    }
    return raw / FTMS_RESISTANCE_SCALE
}
