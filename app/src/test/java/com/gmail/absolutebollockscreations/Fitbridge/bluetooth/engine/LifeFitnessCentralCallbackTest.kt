package com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine

import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.simulator.FtmsMockSample
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.simulator.LifeFitnessMachine
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.simulator.LifeFitnessPacketEncoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/** Capture-order regression for the complete Phase 6 central callback boundary. */
class LifeFitnessCentralCallbackTest {

    private val profile = LifeFitnessProfile("Synthetic", "User", "synthetic@example.test")

    private fun characteristic(
        uuid: UUID,
        writable: Boolean = true,
        notify: Boolean = false,
        cccd: Boolean = false,
    ) = LifeFitnessFingerprint.CharacteristicInventory(uuid, writable, notify, cccd)

    private fun inventory() = listOf(
        LifeFitnessFingerprint.ServiceInventory(
            BluetoothIds.UUID_LF_CONTROL_SERVICE,
            listOf(
                characteristic(BluetoothIds.UUID_LF_CONTROL_1),
                characteristic(BluetoothIds.UUID_LF_CONTROL_2),
                characteristic(BluetoothIds.UUID_LF_STATUS, writable = false, notify = true, cccd = true),
            ),
        ),
        LifeFitnessFingerprint.ServiceInventory(
            BluetoothIds.UUID_LF_USER_SERVICE,
            listOf(
                characteristic(BluetoothIds.UUID_LF_VENDOR_TOKEN_1),
                characteristic(BluetoothIds.UUID_LF_VENDOR_TOKEN_2),
            ),
        ),
    )

    @Test
    fun `status descriptor then every setup write routes before all four personas reach fusion`() {
        val handler = FtmsProtocolHandler()
        val issued = mutableListOf<LifeFitnessSetup.Op>()
        val issue = LifeFitnessSetupDriver.Issue { op -> issued += op; true }
        val driver = LifeFitnessSetupDriver()

        var progress = driver.start(inventory(), profile, ageYears = 41, issue = issue)
        var completionCount = 0
        while (progress is LifeFitnessSetupDriver.Progress.Running) {
            val op = progress.op
            val route = resolveProtocolHandlerRoute(
                listOf(handler),
                characteristicUuid = op.characteristicUuid,
                serviceUuid = op.serviceUuid,
            )
            assertEquals("callback ${op.label}", listOf(handler), route.owners)
            assertEquals(HandlerOwnership.EXPLICIT_CHARACTERISTIC, route.ownership)
            progress = driver.onCompleted(success = true, issue = issue)
            completionCount++
        }

        assertEquals(LifeFitnessSetupDriver.Progress.Succeeded, progress)
        assertEquals(14, completionCount)
        assertEquals("status subscribe", issued.first().label)
        assertEquals("control 1 close", issued.last().label)
        assertTrue(issued.map { it.characteristicUuid }.all(LifeFitnessSetup.CALLBACK_CHARACTERISTICS::contains))

        LifeFitnessMachine.entries.forEach { machine ->
            assertEquals(
                "measurement callback for $machine",
                listOf(handler),
                resolveProtocolHandlerRoute(
                    listOf(handler),
                    characteristicUuid = machine.measurementUuid,
                    serviceUuid = BluetoothIds.UUID_FTMS_SERVICE,
                ).owners,
            )
            val fusion = replayNotification(machine)
            val elapsed = fusion.latestSample(MetricKind.ELAPSED)
            assertNotNull("$machine notification must reach fusion", elapsed)
            assertEquals(MetricProtocol.FTMS, elapsed!!.protocol)
            assertEquals(machine.measurementUuid, elapsed.sourceId.characteristicUuid)
            when (machine) {
                LifeFitnessMachine.TREADMILL -> assertEquals(18.25, fusion.latestSample(MetricKind.SPEED)!!.value, 0.001)
                LifeFitnessMachine.CROSS_TRAINER -> assertEquals(215.0, fusion.latestSample(MetricKind.POWER)!!.value, 0.001)
                LifeFitnessMachine.STAIR_CLIMBER -> assertEquals(6.0, fusion.latestSample(MetricKind.TOTAL_FLOORS)!!.value, 0.001)
                LifeFitnessMachine.INDOOR_BIKE -> assertEquals(81.0, fusion.latestSample(MetricKind.CADENCE)!!.value, 0.001)
            }
        }
    }

    @Test
    fun `request control requires readiness completed setup and a real control point`() {
        assertFalse(canRequestFtmsControl(cccdReady = false, setupPending = false, controlPointPresent = true))
        assertFalse(canRequestFtmsControl(cccdReady = true, setupPending = true, controlPointPresent = true))
        assertFalse(canRequestFtmsControl(cccdReady = true, setupPending = false, controlPointPresent = false))
        assertTrue(canRequestFtmsControl(cccdReady = true, setupPending = false, controlPointPresent = true))
    }

    private fun replayNotification(machine: LifeFitnessMachine): MetricFusion {
        val fusion = MetricFusion(BtEngineState()).also {
            it.setPersona(if (machine == LifeFitnessMachine.INDOOR_BIKE) FusionPersona.RIDE else FusionPersona.RUN)
        }
        val emitter = MetricEmitter(
            fusion = fusion,
            sourceId = MetricSourceId(
                ConnectionId("life-fitness-callback", machine.ordinal.toLong() + 1L),
                MetricProtocol.FTMS,
                machine.measurementUuid,
            ),
            sourceClass = MetricSourceClass.FTMS,
            arrivalMs = 1_000L,
        )
        val packet = LifeFitnessPacketEncoder.encode(machine, sample)
        when (machine) {
            LifeFitnessMachine.TREADMILL -> BtEngineFtmsParsers.doTreadmill(emitter, packet)
            LifeFitnessMachine.CROSS_TRAINER -> BtEngineFtmsParsers.doElliptical(emitter, packet)
            LifeFitnessMachine.STAIR_CLIMBER -> BtEngineFtmsParsers.doClimber(emitter, packet)
            LifeFitnessMachine.INDOOR_BIKE -> BtEngineFtmsParsers.doBike(emitter, packet, "LF callback fixture")
        }
        return fusion
    }

    private val sample = FtmsMockSample(
        elapsedSeconds = 42,
        heartRate = 131,
        caloriesTotal = 17,
        caloriesPerHour = 430,
        caloriesPerMinute = 7,
        speedKph = 18.25,
        averageSpeedKph = 11.5,
        distanceMeters = 250,
        cadence = 81.0,
        averageCadence = 79.0,
        resistance = 12,
        inclinationPercent = 3.5,
        powerWatts = 215,
        averagePowerWatts = 202,
        movementCount = 48,
        positiveElevationMeters = 7.0,
        floors = 6,
    )
}
