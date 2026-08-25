package com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine

internal fun rideOnProviderCapabilities() = BtEngineState.FtmsCapabilities(
    featuresRead = true,
    supportsPower = true,
    supportsIndoorBikeSimulation = true,
    powerMin = 0f,
    powerMax = 2_000f,
    powerIncr = 1f,
    powerRangeKnown = true,
)

internal class RideOnMachineControlEndpoint(
    private val session: RideOnControlSession,
    private val riderMassKg: () -> Double,
    private val bicycleMassKg: () -> Double = { 10.0 },
    private val gearRatio: () -> Double? = { null },
) : MachineControlEndpoint {
    override fun requestControl() = session.controlGranted
    override fun startOrResume() = session.controlGranted
    override fun stopOrPause() = session.controlGranted
    override fun setTargetSpeedKph(kph: Float) = false
    override fun setTargetInclinationPercent(percent: Float) = false
    override fun setTargetResistance(level: Float) = false
    override fun setTargetPowerWatts(watts: Int) = session.submit(RideOnControlTarget.Erg(watts))
    override fun setWheelCircumferenceMm(millimetres: Float) = false
    override fun setIndoorBikeSimulation(
        windSpeedMps: Float,
        gradePercent: Float,
        rollingResistanceCoefficient: Float,
        windResistanceCoefficient: Float,
    ) = session.submit(
        RideOnControlTarget.Simulation(
            windSpeedMps.toDouble(), gradePercent.toDouble(),
            rollingResistanceCoefficient.toDouble(), windResistanceCoefficient.toDouble(),
            gearRatio(), bicycleMassKg(), riderMassKg(),
        ),
    )
}
