package com.gmail.absolutebollockscreations.gattlibrary.central

import android.annotation.SuppressLint
import android.app.Activity
import java.util.Locale
import com.gmail.absolutebollockscreations.gattlibrary.core.HrZoneMode
import com.gmail.absolutebollockscreations.gattlibrary.core.vendor.KayakFirstHandshakeVariant
import com.gmail.absolutebollockscreations.gattlibrary.core.ActivityType

@SuppressLint("MissingPermission")
internal class EngineConnectionBackend(private val engine: BtEngine) : CentralConnectionBackend {
    override val state get() = engine.state
    override val discoveryRequested get() = engine.scanLeases.discoveryRequested
    override val scanFailure get() = engine.scanFailureCode
    override fun platformFailure(scan: Boolean) = engine.clientPlatformFailure(scan)
    override fun startScan(hardwareFilters: Boolean) { engine.startScan(hardwareFilters) }
    override fun stopScan() { engine.releaseScanLease(ScanLeaseOwner.DISCOVERY_UI) }
    override fun selectMachine(address: String): Boolean = engine.selectMachine(address) != MachineSelectionOutcome.FAILED
    override fun connectSensor(address: String, assignment: SensorAssignment): Boolean = engine.connectSensor(
        address, BtEngineState.BtServiceType.valueOf(assignment.service.name), assignment.engineRoles(),
    )
    override fun activateService(key: String, assignment: SensorAssignment): Boolean = engine.activateServiceOn(
        key, BtEngineState.BtServiceType.valueOf(assignment.service.name), assignment.engineRoles(),
    )
    override fun disconnect(key: String): Boolean = engine.disconnectDevice(key)
    /**
     * Ends whichever machine is selected. Advertisement end is idempotent (as the legacy
     * closeRunMode call relied on). GATT machine links are disconnected by registry identity; if a
     * disconnect is refused (BLUETOOTH_CONNECT revoked) the legacy FTMS teardown clears local link,
     * control, and provider state so a setup-gated machine cannot stay logically connected.
     * With no machine link at all, no FTMS/provider reset runs: an advertisement selection must
     * not stop downstream advertising, which the legacy Keiser deselect never did either.
     */
    override fun deselectMachine(): Boolean {
        engine.deselectAdvertisementMachine()
        val machineKeys = state.connectedDevices.value.filter { it.machineConnection }.map { it.connectionKey }
        val disconnected = machineKeys.map { engine.disconnectDevice(it) }.all { it }
        if (!disconnected) engine.disconnectFtms()
        return disconnected
    }
    override fun close() { engine.closeClient() }
    override fun currentMachineKey(): Any? = engine.currentMachineKey()
    override val config: CentralConfigBackend = object : CentralConfigBackend {
        override val lifeFitnessProfilePrompt get() = engine.lifeFitnessProfilePrompt
        override val kayakFirstSetupProgress get() = engine.kayakFirstSetupProgress
        override fun setHeartRateProfile(maxHr: Int?, restingHr: Int?, zoneMode: HrZoneMode) {
            engine.setHrMaxFromApp(maxHr)
            engine.setRestingHrFromApp(restingHr)
            engine.setHrZoneModeFromApp(zoneMode)
        }
        override fun setCscCalibration(wheelCircumferenceMm: Int, ellipticalStrideLengthMm: Int) =
            engine.setCscCalibration(wheelCircumferenceMm, ellipticalStrideLengthMm)
        override fun setLifeFitnessProfile(profile: LifeFitnessProfile, ageYears: Int) =
            engine.setLifeFitnessProfileFromApp(profile, ageYears)
        override fun dismissLifeFitnessProfilePrompt() = engine.dismissLifeFitnessProfilePrompt()
        override fun setKayakFirstSportMode(mode: KayakFirstSportMode) = engine.setKayakFirstSportMode(mode)
        override fun setKayakFirstHandshakeVariant(variant: KayakFirstHandshakeVariant) =
            engine.setKayakFirstHandshakeVariant(variant)
        override fun setKayakFirstDisplayConfiguration(configuration: KayakFirstDisplayConfiguration) =
            engine.setKayakFirstDisplayConfiguration(configuration)
        override fun clearSessionData() = engine.clearSessionData()
        override fun ensureBluetoothEnabled(activity: Activity) = engine.ensureBluetoothEnabled(activity)
    }

    override fun machineIdentity(): MachineIdentity? {
        val advertised = engine.state.activeAdvertisementMachine.value
        val device = engine.state.gatt?.device
        if (advertised == null && device == null) return null
        val address = device?.let { runCatching { it.address }.getOrNull() }
        val normalized = address?.trim()?.uppercase(Locale.US)
        val scanName = normalized?.let { wanted ->
            engine.state.discoveredDevices.value.values.firstOrNull {
                it.address.trim().uppercase(Locale.US) == wanted
            }?.name?.takeIf { it.isNotBlank() }
        }
        val platformName = device?.let {
            try {
                @Suppress("MissingPermission")
                it.name?.takeIf { name -> name.isNotBlank() }
            } catch (_: SecurityException) {
                null
            }
        }
        return MachineIdentity(
            gattAddress = address,
            advertisementLabel = advertised?.let { "${it.model} #${it.systemId}" },
            scanName = scanName,
            platformName = platformName,
            serviceUuids = engine.state.currentMachineServiceUuids(),
            advertisementCandidateId = advertised?.candidateId,
        )
    }

    override fun beginSession(activity: ActivityType, machineAddress: String?) {
        engine.beginRecoverySession()
        engine.markSessionStarted(activity, machineAddress)
    }
    override fun endSession() {
        engine.markSessionStopped()
        engine.endRecoverySession()
        engine.resetSession()
    }
    override fun submit(expectedKey: Any, command: MachineCommand): Boolean? {
        if (engine.currentMachineKey() != expectedKey) return null
        if (command.terminatesLocallyOn(engine.hasSelectedKayakFirstAdapter())) return true
        return when (command) {
            MachineCommand.StartOrResume -> engine.startOrResume()
            MachineCommand.StopOrPause -> engine.stopOrPause()
            MachineCommand.RequestControl -> engine.requestControl()
            // The app's own Start/Stop: a machine that runs its own session starts that session
            // here, where a relayed Start/Stop is accepted without being sent (see terminatesLocallyOn).
            MachineCommand.StartRun ->
                if (engine.hasSelectedKayakFirstAdapter()) engine.startLocalKayakFirstSession() else engine.startOrResume()
            MachineCommand.StopRun ->
                if (engine.hasSelectedKayakFirstAdapter()) engine.stopLocalKayakFirstSession() else engine.stopOrPause()
            is MachineCommand.TargetSpeedKph -> engine.setTargetSpeedKph(command.kph)
            is MachineCommand.TargetInclinePercent -> engine.setTargetInclinationPercent(command.percent)
            is MachineCommand.TargetResistanceLevel -> engine.setTargetResistance(command.level)
            is MachineCommand.TargetPowerWatts -> engine.setTargetPowerWatts(command.watts)
            is MachineCommand.WheelCircumferenceMm -> engine.setWheelCircumferenceMm(command.millimetres)
            is MachineCommand.IndoorBikeSimulation -> engine.setIndoorBikeSimulation(
                command.windSpeedMps,
                command.gradePercent,
                command.rollingResistanceCoefficient,
                command.windResistanceCoefficient,
            )
        }
    }
}

private fun SensorAssignment.engineRoles(): Set<BtEngineState.CscRole>? =
    roles?.mapTo(linkedSetOf()) { BtEngineState.CscRole.valueOf(it.name) }
