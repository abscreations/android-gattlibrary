package com.gmail.absolutebollockscreations.gattlibrary.simulator

import com.gmail.absolutebollockscreations.gattlibrary.central.BluetoothIds
import com.gmail.absolutebollockscreations.gattlibrary.central.Concept2Measurement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.gmail.absolutebollockscreations.gattlibrary.core.ftms.FtmsUuids
import com.gmail.absolutebollockscreations.gattlibrary.core.ids.Concept2Uuids

class Concept2Pm5EncoderTest {
    private val sample = Concept2Pm5MockEngine(Concept2Machine.ROW_ERG).run {
        startWorkout()
        tick(15.34)
    }

    @Test
    fun `modern RowErg FTMS pair reproduces the captured active layout exactly`() {
        val captured = sample.copy(
            elapsedCentiseconds = 6_221,
            distanceDecimetres = 1_830,
            strokeRate = 53,
            heartRate = 255,
            currentPaceCentiseconds = 23_547,
            averagePaceCentiseconds = 16_993,
            currentPowerWatts = 27,
            averagePowerWatts = 71,
            totalCalories = 9,
            caloriesPerHour = 393,
            dragFactor = 119,
            strokeCount = 25,
            averageCadence = 24.0,
        )

        assertEquals(
            listOf(
                "ff0a30b70000eb00a9001b0047007700003e00",
                "00016a190009008901ff",
            ),
            Concept2Pm5Encoder.ftmsRower(captured).map { it.hex() },
        )
    }

    @Test
    fun `FTMS rower pair stays coherent with Concept2 packets from one snapshot`() {
        val general = Concept2Pm5Encoder.generalStatus(sample)
        val status1 = Concept2Pm5Encoder.individual(Concept2Uuids.UUID_C2_ADDITIONAL_STATUS_1, sample)!!
        val stroke = Concept2Pm5Encoder.individual(Concept2Uuids.UUID_C2_ADDITIONAL_STROKE_DATA, sample)!!
        val (first, second) = Concept2Pm5Encoder.ftmsRower(sample)

        assertEquals(u24(general, 3) / 10, u24(first, 3))
        assertEquals(u16(status1, 7) / 100, u16(first, 6))
        assertEquals(u16(status1, 9) / 100, u16(first, 8))
        assertEquals(u16(stroke, 3), u16(first, 10))
        assertEquals(sample.averagePowerWatts, u16(first, 12))
        assertEquals(sample.dragFactor, u16(first, 14))
        assertEquals(sample.elapsedCentiseconds / 100, u16(first, 17))
        assertEquals(sample.strokeRate, (second[2].toInt() and 0xFF) / 2)
        assertEquals(u16(stroke, 7), u16(second, 3))
        assertEquals(sample.totalCalories, u16(second, 5))
        assertEquals(sample.caloriesPerHour, u16(second, 7))
    }

    @Test
    fun `FTMS rower unavailable heart rate and energy per minute use captured sentinels`() {
        val (first, second) = Concept2Pm5Encoder.ftmsRower(sample.copy(heartRate = 255))
        assertEquals(0, first[16].toInt() and 0xFF)
        assertEquals(0xFF, second.last().toInt() and 0xFF)
    }

    @Test
    fun `FTMS rower fields saturate instead of wrapping`() {
        val (first, second) = Concept2Pm5Encoder.ftmsRower(
            sample.copy(
                elapsedCentiseconds = Int.MAX_VALUE,
                distanceDecimetres = -1,
                strokeRate = Int.MAX_VALUE,
                currentPaceCentiseconds = Int.MAX_VALUE,
                currentPowerWatts = -1,
                dragFactor = Int.MAX_VALUE,
                strokeCount = -1,
            ),
        )

        assertEquals(0, u24(first, 3))
        assertEquals(0xFFFF, u16(first, 6))
        assertEquals(0, u16(first, 10))
        assertEquals(0xFFFF, u16(first, 14))
        assertEquals(0xFFFF, u16(first, 17))
        assertEquals(254, second[2].toInt() and 0xFF)
        assertEquals(0, u16(second, 3))
    }

    @Test
    fun `BikeErg retains captured stroke records without inventing rower mechanics or force curve`() {
        val bike = Concept2Pm5MockEngine(Concept2Machine.BIKE_ERG).run {
            startWorkout()
            tick(15.34)
        }

        // The pinned physical BikeErg multiplex capture contains 0x35 and 0x36, so those records
        // remain available; their rower-specific mechanics are physically observed as zero.
        assertTrue(bike.strokeDataAvailable)
        assertEquals(0, bike.driveLengthCentimetres)
        assertEquals(0, bike.driveTimeCentiseconds)
        assertEquals(0, bike.recoveryTimeCentiseconds)
        assertEquals(0, bike.strokeDistanceCentimetres)
        assertEquals(0, bike.peakForceTenthsPound)
        assertEquals(0, bike.averageForceTenthsPound)
        assertTrue(bike.forceCurvePounds.isEmpty())
        assertFalse(Concept2Pm5PeripheralAdvertiser.forceCurveAvailable(Concept2Machine.BIKE_ERG, bike))

        assertTrue(Concept2Pm5Encoder.multiplexed(0x35, bike) != null)
        assertTrue(Concept2Pm5Encoder.multiplexed(0x36, bike) != null)
    }

    @Test
    fun `core individual packets have exact baseline lengths and round trip`() {
        val general = Concept2Pm5Encoder.individual(Concept2Uuids.UUID_C2_GENERAL_STATUS, sample)!!
        val status1 = Concept2Pm5Encoder.individual(Concept2Uuids.UUID_C2_ADDITIONAL_STATUS_1, sample)!!
        val status2 = Concept2Pm5Encoder.individual(Concept2Uuids.UUID_C2_ADDITIONAL_STATUS_2, sample)!!
        val stroke = Concept2Pm5Encoder.individual(Concept2Uuids.UUID_C2_ADDITIONAL_STROKE_DATA, sample)!!
        assertEquals(listOf(19, 17, 20, 15), listOf(general.size, status1.size, status2.size, stroke.size))

        assertEquals(sample.distanceDecimetres / 10.0, Concept2Measurement.generalStatus(general)!!.distanceMetres!!, 0.001)
        assertEquals(sample.speedMillimetresPerSecond / 1000.0, Concept2Measurement.additionalStatus1(status1)!!.speedMetresPerSecond!!, 0.001)
        assertEquals(sample.averagePowerWatts, Concept2Measurement.additionalStatus2(status2)!!.averagePowerWatts)
        assertEquals(sample.currentPowerWatts, Concept2Measurement.additionalStrokeData(stroke)!!.strokePowerWatts)
    }

    @Test
    fun `multiplexed layouts stay within MTU 23 and round trip through dispatch`() {
        listOf(0x31 to 20, 0x32 to 20, 0x33 to 19, 0x36 to 18).forEach { (id, length) ->
            val frame = Concept2Pm5Encoder.multiplexed(id, sample)!!
            assertEquals(id, frame[0].toInt() and 0xFF)
            assertEquals(length, frame.size)
            assertEquals(sample.elapsedCentiseconds, Concept2Measurement.multiplexed(frame)!!.elapsedCentiseconds)
        }
        assertNull(Concept2Pm5Encoder.multiplexed(0x7F, sample))
    }

    @Test
    fun `complete baseline packets use documented individual and mux widths`() {
        val individualLengths = linkedMapOf(
            Concept2Uuids.UUID_C2_STROKE_DATA to 20,
            Concept2Uuids.UUID_C2_SPLIT_INTERVAL_DATA to 18,
            Concept2Uuids.UUID_C2_ADDITIONAL_SPLIT_INTERVAL to 19,
            Concept2Uuids.UUID_C2_WORKOUT_SUMMARY to 20,
            Concept2Uuids.UUID_C2_ADDITIONAL_WORKOUT_SUMMARY to 19,
            Concept2Uuids.UUID_C2_HEART_RATE_BELT_INFO to 6,
            Concept2Uuids.UUID_C2_ADDITIONAL_WORKOUT_SUMMARY_2 to 10,
        )
        individualLengths.forEach { (uuid, length) ->
            assertEquals(length, Concept2Pm5Encoder.individual(uuid, sample)!!.size)
        }
        val muxLengths = mapOf(0x35 to 19, 0x37 to 19, 0x38 to 20, 0x39 to 19, 0x3A to 19, 0x3B to 7, 0x3C to 11)
        muxLengths.forEach { (id, length) ->
            assertEquals(length, Concept2Pm5Encoder.multiplexed(id, sample)!!.size)
        }
    }

    @Test
    fun `additional status 3 has exact documented individual and mux layout`() {
        val status = sample.copy(
            operationalState = 2,
            workoutVerificationState = 3,
            screenNumber = 0x1234,
            lastError = 0x5678,
            calibrationMode = 4,
            calibrationState = 5,
            calibrationStatus = 6,
            gameId = 2,
            gameScore = 0x9ABC,
        )
        val expected = byteArrayOf(
            0x02, 0x03, 0x34, 0x12, 0x78, 0x56,
            0x04, 0x05, 0x06, 0x02, 0xBC.toByte(), 0x9A.toByte(),
        )

        val individual = Concept2Pm5Encoder.individual(
            Concept2Uuids.UUID_C2_ADDITIONAL_STATUS_3,
            status,
        )!!
        assertTrue(expected.contentEquals(individual))

        val multiplexed = Concept2Pm5Encoder.multiplexed(0x3E, status)!!
        assertEquals(13, multiplexed.size)
        assertEquals(0x3E, multiplexed[0].toInt() and 0xFF)
        assertTrue(expected.contentEquals(multiplexed.copyOfRange(1, multiplexed.size)))
    }

    @Test
    fun `additional status 3 saturates every unsigned field`() {
        val status = sample.copy(
            operationalState = -1,
            workoutVerificationState = Int.MAX_VALUE,
            screenNumber = -1,
            lastError = Int.MAX_VALUE,
            calibrationMode = -1,
            calibrationState = Int.MAX_VALUE,
            calibrationStatus = -1,
            gameId = Int.MAX_VALUE,
            gameScore = -1,
        )
        val packet = Concept2Pm5Encoder.individual(
            Concept2Uuids.UUID_C2_ADDITIONAL_STATUS_3,
            status,
        )!!

        assertEquals(12, packet.size)
        assertEquals(
            listOf(0, 0xFF, 0, 0, 0xFF, 0xFF, 0, 0xFF, 0, 0xFF, 0, 0),
            packet.map { it.toInt() and 0xFF },
        )
    }

    @Test
    fun `device and status packets carry each persona machine type`() {
        Concept2Machine.entries.forEach { machine ->
            val snapshot = Concept2Pm5MockEngine(machine).tick(1.0)
            val expected = machine.wireMachineType
            val packets = listOf(
                Concept2Pm5Encoder.individual(Concept2Uuids.UUID_C2_ADDITIONAL_STATUS_1, snapshot)!!,
                Concept2Pm5Encoder.multiplexed(0x32, snapshot)!!,
                Concept2Pm5Encoder.individual(Concept2Uuids.UUID_C2_ADDITIONAL_SPLIT_INTERVAL, snapshot)!!,
                Concept2Pm5Encoder.multiplexed(0x38, snapshot)!!,
            )
            packets.forEach { packet ->
                assertEquals(expected, packet.last().toInt() and 0xFF)
            }
        }
    }

    @Test
    fun `force curve sequence is ordered bounded and lossless`() {
        val frames = Concept2Pm5Encoder.forceCurve(sample)
        assertEquals(3, frames.size)
        assertEquals(listOf(0, 1, 2), frames.map { it[1].toInt() and 0xFF })
        assertTrue(frames.all { it.size <= 20 })
        assertEquals(24, frames.sumOf { it[0].toInt() and 0x0F })
        assertTrue(frames.all { (it[0].toInt() ushr 4) == frames.size })
        val encodedPoints = frames.flatMap { frame ->
            (0 until (frame[0].toInt() and 0x0F)).map { index ->
                val offset = 2 + index * 2
                (frame[offset].toInt() and 0xFF) or ((frame[offset + 1].toInt() and 0xFF) shl 8)
            }
        }
        assertEquals(sample.forceCurvePounds, encodedPoints)
        assertEquals(sample.peakForceTenthsPound, encodedPoints.maxOrNull()!! * 10)
        assertEquals(0, encodedPoints.first())
        assertEquals(0, encodedPoints.last())
        assertTrue(encodedPoints[1] > 0)
        assertTrue(encodedPoints[encodedPoints.size - 2] > 0)
    }

    @Test
    fun `integer encoders saturate high and low inputs instead of wrapping`() {
        val extreme = sample.copy(
            elapsedCentiseconds = Int.MAX_VALUE,
            distanceDecimetres = -1,
            speedMillimetresPerSecond = Int.MAX_VALUE,
            heartRate = -50,
        )
        val general = Concept2Pm5Encoder.generalStatus(extreme)
        assertEquals(0xFF, general[0].toInt() and 0xFF)
        assertEquals(0, general[3].toInt() and 0xFF)
        val status = Concept2Pm5Encoder.individual(Concept2Uuids.UUID_C2_ADDITIONAL_STATUS_1, extreme)!!
        assertEquals(0xFF, status[3].toInt() and 0xFF)
        assertEquals(0, status[6].toInt() and 0xFF)
    }

    @Test
    fun `sample rate and subscriptions are connection scoped`() {
        val first = Concept2Pm5Session()
        val second = Concept2Pm5Session()
        assertTrue(first.setSampleRate(3))
        assertFalse(first.setSampleRate(4))
        assertEquals(100L, first.periodMs)
        assertEquals(500L, second.periodMs)

        first.setSubscribed(Concept2Uuids.UUID_C2_MULTIPLEXED_INFORMATION, true)
        first.setSubscribed(Concept2Uuids.UUID_C2_GENERAL_STATUS, true)
        assertEquals(0x32, first.nextMuxId(listOf(0x31, 0x32)))
        assertFalse(second.isSubscribed(Concept2Uuids.UUID_C2_MULTIPLEXED_INFORMATION))
        first.reset()
        assertEquals(500L, first.periodMs)
        assertFalse(first.isSubscribed(Concept2Uuids.UUID_C2_GENERAL_STATUS))
    }

    @Test
    fun `only workout data subscriptions request the first pull`() {
        val session = Concept2Pm5Session()
        session.setSubscribed(Concept2Uuids.UUID_C2_ADDITIONAL_STATUS_3, true)
        assertFalse(session.hasWorkoutMeasurementSubscription)

        session.setSubscribed(Concept2Uuids.UUID_C2_FORCE_CURVE_DATA, true)
        assertTrue(session.hasWorkoutMeasurementSubscription)
        session.setSubscribed(Concept2Uuids.UUID_C2_FORCE_CURVE_DATA, false)
        assertFalse(session.hasWorkoutMeasurementSubscription)

        session.setSubscribed(Concept2Uuids.UUID_C2_MULTIPLEXED_INFORMATION, true)
        assertTrue(session.hasWorkoutMeasurementSubscription)
        session.reset()
        assertFalse(session.hasWorkoutMeasurementSubscription)

        session.setSubscribed(FtmsUuids.UUID_ROWING_MACHINE_DATA, true)
        assertTrue(session.hasWorkoutMeasurementSubscription)
    }

    private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    private fun u16(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)

    private fun u24(bytes: ByteArray, offset: Int): Int =
        u16(bytes, offset) or ((bytes[offset + 2].toInt() and 0xFF) shl 16)

    @Test
    fun `additional status 3 individual subscription suppresses its mux frame`() {
        val session = Concept2Pm5Session()
        val ids = listOf(0x3E, 0x31)
        session.setSubscribed(Concept2Uuids.UUID_C2_MULTIPLEXED_INFORMATION, true)
        assertEquals(0x3E, session.nextMuxId(ids))

        session.setSubscribed(Concept2Uuids.UUID_C2_ADDITIONAL_STATUS_3, true)
        assertEquals(0x31, session.nextMuxId(ids))

        session.setSubscribed(Concept2Uuids.UUID_C2_ADDITIONAL_STATUS_3, false)
        assertEquals(0x3E, session.nextMuxId(ids))
    }

    @Test
    fun `additional status 3 delivery detects initial changes and forced rate ticks`() {
        val session = Concept2Pm5Session()
        val initial = byteArrayOf(2, 0, 0)
        assertTrue(session.shouldEmitAdditionalStatus3(initial))
        initial[0] = 7
        assertFalse(session.shouldEmitAdditionalStatus3(byteArrayOf(2, 0, 0)))
        assertTrue(session.shouldEmitAdditionalStatus3(byteArrayOf(1, 0, 0)))
        assertFalse(session.shouldEmitAdditionalStatus3(byteArrayOf(1, 0, 0)))
        assertTrue(session.shouldEmitAdditionalStatus3(byteArrayOf(1, 0, 0), force = true))

        session.reset()
        assertTrue(session.shouldEmitAdditionalStatus3(byteArrayOf(1, 0, 0)))
    }

    @Test
    fun `PM5 holds physical idle then prepares the first pull before becoming active`() {
        val engine = Concept2Pm5MockEngine(
            Concept2Machine.ROW_ERG,
            Concept2Scenario.FINISH,
            startsPaused = true,
        )
        val initial = engine.snapshot()
        assertEquals(0, initial.elapsedCentiseconds)
        assertEquals(0, initial.distanceDecimetres)
        assertEquals(0, initial.workoutState)
        assertEquals(0, initial.rowingState)
        assertEquals(0, initial.strokeState)
        assertEquals(Concept2Pm5OperationalState.IDLE, initial.operationalState)
        assertEquals(1, initial.screenNumber)
        assertEquals(
            listOf(0, 0, 0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0x80, 0),
            Concept2Pm5Encoder.generalStatus(initial).map { it.toInt() and 0xFF },
        )
        assertEquals(
            listOf(10, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0),
            Concept2Pm5Encoder.individual(Concept2Uuids.UUID_C2_ADDITIONAL_STATUS_3, initial)!!
                .map { it.toInt() and 0xFF },
        )
        assertEquals(
            listOf(0x3E, 10, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0),
            Concept2Pm5Encoder.multiplexed(0x3E, initial)!!.map { it.toInt() and 0xFF },
        )

        repeat(20) { assertEquals(0, engine.tick(0.5).elapsedCentiseconds) }
        assertEquals(Concept2Pm5StartupPhase.IDLE, engine.startupPhase)
        assertFalse(engine.isWorkoutRunning)

        assertTrue(engine.startWorkout())

        repeat(4) {
            val held = engine.tick(0.5)
            assertEquals(Concept2Pm5StartupPhase.SUBSCRIBED_IDLE, engine.startupPhase)
            assertEquals(0, held.elapsedCentiseconds)
            assertEquals(0, held.distanceDecimetres)
            assertEquals(0, held.forceCurveSequence)
        }

        val waiting = engine.tick(0.5)
        assertEquals(Concept2Pm5StartupPhase.WAITING_FOR_ACCELERATION, engine.startupPhase)
        assertEquals(listOf(0, 0, 1), listOf(waiting.workoutState, waiting.rowingState, waiting.strokeState))
        assertEquals(Concept2Pm5OperationalState.READY, waiting.operationalState)
        assertEquals(0, waiting.forceCurveSequence)

        val accelerating = engine.tick(0.5)
        assertEquals(Concept2Pm5StartupPhase.ACCELERATING, engine.startupPhase)
        assertEquals(listOf(0, 0, 2), listOf(accelerating.workoutState, accelerating.rowingState, accelerating.strokeState))
        assertEquals(0, accelerating.forceCurveSequence)

        val active = engine.tick(0.5)
        assertEquals(Concept2Pm5StartupPhase.ACTIVE, engine.startupPhase)
        assertEquals(1, active.workoutState)
        assertEquals(1, active.rowingState)
        assertEquals(Concept2Pm5OperationalState.WORKOUT, active.operationalState)
        assertEquals(0, active.forceCurveSequence)

        var firstCompletedStroke = active
        while (firstCompletedStroke.forceCurveSequence == 0) {
            firstCompletedStroke = engine.tick(0.5)
        }
        assertTrue(firstCompletedStroke.elapsedCentiseconds > active.elapsedCentiseconds)

        assertEquals(Concept2Pm5OperationalState.READY, engine.tick(60.0).operationalState)
    }

    @Test
    fun `autonomous PM5 carries a time weighted average cadence for generated metrics`() {
        val engine = Concept2Pm5MockEngine(Concept2Machine.ROW_ERG)
        engine.startWorkout()
        engine.tick(3.5)
        val first = engine.tick(10.0)
        val second = engine.tick(20.0)

        assertTrue(second.averageCadence in 18.0..34.0)
        assertTrue(second.averageCadence != first.averageCadence)
        assertTrue(second.averagePowerWatts > 0)
    }

    @Test
    fun `pause freezes an active PM5 workout and resume continues without reset`() {
        val engine = Concept2Pm5MockEngine(
            Concept2Machine.ROW_ERG,
            startsPaused = true,
        )
        assertTrue(engine.startWorkout())
        engine.tick(4.0)
        val beforePause = engine.snapshot()

        assertTrue(engine.pauseWorkout())
        assertFalse(engine.isWorkoutRunning)
        val paused = engine.tick(30.0)
        assertEquals(beforePause.elapsedCentiseconds, paused.elapsedCentiseconds)
        assertEquals(beforePause.distanceDecimetres, paused.distanceDecimetres)
        assertEquals(beforePause.strokeCount, paused.strokeCount)
        assertEquals(beforePause.forceCurveSequence, paused.forceCurveSequence)

        val wirePaused = engine.wireSnapshot()
        assertEquals(beforePause.elapsedCentiseconds, wirePaused.elapsedCentiseconds)
        assertEquals(beforePause.distanceDecimetres, wirePaused.distanceDecimetres)
        assertEquals(beforePause.strokeCount, wirePaused.strokeCount)
        assertEquals(0, wirePaused.speedMillimetresPerSecond)
        assertEquals(0, wirePaused.strokeRate)
        assertEquals(0, wirePaused.currentPaceCentiseconds)
        assertEquals(0, wirePaused.currentPowerWatts)
        assertEquals(0, wirePaused.averagePowerWatts)
        assertEquals(0, wirePaused.strokeState)
        assertEquals(0, wirePaused.driveLengthCentimetres)
        assertEquals(0, wirePaused.peakForceTenthsPound)
        assertFalse(wirePaused.strokeDataAvailable)
        assertFalse(0x35 in Concept2Pm5PeripheralAdvertiser.liveIdsFor(wirePaused))
        assertFalse(0x36 in Concept2Pm5PeripheralAdvertiser.liveIdsFor(wirePaused))

        assertTrue(engine.startWorkout())
        val resumed = engine.tick(0.5)
        assertTrue(resumed.elapsedCentiseconds > paused.elapsedCentiseconds)
        assertTrue(resumed.distanceDecimetres >= paused.distanceDecimetres)
        assertTrue(resumed.strokeDataAvailable)
        assertTrue(0x35 in Concept2Pm5PeripheralAdvertiser.liveIdsFor(resumed))
        assertTrue(0x36 in Concept2Pm5PeripheralAdvertiser.liveIdsFor(resumed))
    }

    @Test
    fun `PM5 producer matches ErgData sample rate two cadence`() {
        assertEquals(250L, FtmsSimulator.CONCEPT2_PERIOD_MS)
    }

    @Test
    fun `autonomous undefined rests stop strokes for reproducible thirty to sixty second windows`() {
        fun firstRestDurationSeconds(): Int {
            val engine = Concept2Pm5MockEngine(
                Concept2Machine.ROW_ERG,
                Concept2Scenario.UNDEFINED_REST_INTERVALS,
            )
            engine.startWorkout()
            val active = engine.tick(3.5)
            assertTrue(active.strokeRate in 40..50)

            val rest = engine.tick(30.0)
            assertEquals(0, rest.speedMillimetresPerSecond)
            assertEquals(0, rest.strokeRate)
            assertEquals(0, rest.currentPowerWatts)
            assertEquals(0, rest.driveLengthCentimetres)
            assertEquals(0, rest.peakForceTenthsPound)
            assertFalse(rest.strokeDataAvailable)
            val elapsedAtRest = rest.elapsedCentiseconds
            val distanceAtRest = rest.distanceDecimetres

            var restSeconds = 0
            var current = rest
            while (current.strokeRate == 0 && restSeconds <= 60) {
                current = engine.tick(1.0)
                restSeconds++
                if (current.strokeRate == 0) {
                    assertEquals(elapsedAtRest, current.elapsedCentiseconds)
                    assertEquals(distanceAtRest, current.distanceDecimetres)
                }
            }
            assertTrue(restSeconds in 30..60)
            assertTrue(current.strokeRate in 40..50)
            return restSeconds
        }

        assertEquals(firstRestDurationSeconds(), firstRestDurationSeconds())
    }

    @Test
    fun `single CSAFE goal finishes without resetting accumulated workout totals`() {
        val engine = Concept2Pm5MockEngine(Concept2Machine.ROW_ERG, startsPaused = true)
        engine.configureWorkout(
            Concept2WorkoutProgram(4, Concept2WorkoutGoalKind.TIME, goalValue = 400),
        )
        assertFalse(engine.isWorkoutRunning)
        assertTrue(engine.startWorkout())

        engine.tick(7.0)
        val completed = engine.tick(2.0)

        assertFalse(engine.isWorkoutRunning)
        assertEquals(Concept2ProgramPhase.COMPLETE, engine.programProgress()?.phase)
        assertEquals(10, completed.workoutState)
        assertEquals(1, completed.summarySequence)
        assertTrue(completed.elapsedCentiseconds >= 400)
        assertTrue(completed.distanceDecimetres > 0)
        assertEquals(0, completed.speedMillimetresPerSecond)
        assertFalse(completed.strokeDataAvailable)
    }

    @Test
    fun `General Status encodes the raw programmed goal in the selected duration units`() {
        listOf(
            Triple(Concept2WorkoutGoalKind.TIME, 12_000, 0x00),
            Triple(Concept2WorkoutGoalKind.DISTANCE, 1_000, 0x80),
            Triple(Concept2WorkoutGoalKind.CALORIES, 500, 0x40),
        ).forEach { (kind, goal, durationType) ->
            val workoutType = when (kind) {
                Concept2WorkoutGoalKind.TIME -> 5
                Concept2WorkoutGoalKind.DISTANCE -> 3
                Concept2WorkoutGoalKind.CALORIES -> 10
            }
            val engine = Concept2Pm5MockEngine(Concept2Machine.ROW_ERG, startsPaused = true)
            engine.configureWorkout(Concept2WorkoutProgram(workoutType, kind, goal))
            engine.startWorkout()
            val snapshot = engine.tick(7.0)
            val general = Concept2Pm5Encoder.generalStatus(snapshot)
            val additionalStroke = Concept2Pm5Encoder.multiplexed(0x36, snapshot)!!

            assertEquals(goal, u24le(general, 14))
            assertEquals(durationType, general[17].toInt() and 0xFF)
            assertEquals(snapshot.projectedWorkTimeSeconds, u24le(additionalStroke, 10))
            assertEquals(snapshot.projectedWorkDistanceMetres, u24le(additionalStroke, 13))
        }
    }

    @Test
    fun `configured single-workout split boundaries advance split telemetry`() {
        val engine = Concept2Pm5MockEngine(Concept2Machine.ROW_ERG, startsPaused = true)
        engine.configureWorkout(
            Concept2WorkoutProgram(
                workoutType = 3,
                goalKind = Concept2WorkoutGoalKind.DISTANCE,
                goalValue = 100,
                splitGoalKind = Concept2WorkoutGoalKind.DISTANCE,
                splitGoalValue = 10,
            ),
        )
        engine.startWorkout()

        var snapshot = engine.tick(7.0)
        while (snapshot.splitSequence == 0) snapshot = engine.tick(0.5)

        assertEquals(1, snapshot.splitSequence)
        assertTrue(snapshot.distanceDecimetres >= 100)
        val summary = Concept2Pm5Encoder.individual(
            Concept2Uuids.UUID_C2_ADDITIONAL_WORKOUT_SUMMARY,
            snapshot,
        )!!
        assertEquals(10, u16le(summary, 5))
    }

    @Test
    fun `manual end preserves Just Row totals and enters the summary state`() {
        val engine = Concept2Pm5MockEngine(Concept2Machine.ROW_ERG, startsPaused = true)
        engine.startWorkout()
        engine.tick(7.0)
        val beforeEnd = engine.snapshot()

        assertTrue(engine.endWorkout())
        val ended = engine.snapshot()

        assertFalse(engine.isWorkoutRunning)
        assertEquals(beforeEnd.elapsedCentiseconds, ended.elapsedCentiseconds)
        assertEquals(beforeEnd.distanceDecimetres, ended.distanceDecimetres)
        assertEquals(beforeEnd.strokeCount, ended.strokeCount)
        assertEquals(10, ended.workoutState)
        assertEquals(1, ended.summarySequence)
        assertFalse(ended.strokeDataAvailable)
        assertFalse(engine.startWorkout())
        assertFalse(engine.endWorkout())
    }

    @Test
    fun `every PM5 machine and autonomous scenario can enter one stable terminal summary`() {
        Concept2Machine.entries.forEach { machine ->
            Concept2Scenario.entries.forEach { scenario ->
                val engine = Concept2Pm5MockEngine(machine, scenario, startsPaused = true)
                engine.startWorkout()
                engine.tick(1.0)
                val beforeEnd = engine.snapshot()

                assertTrue("$machine/$scenario must accept its first end", engine.endWorkout())
                val ended = engine.snapshot()
                assertEquals(beforeEnd.elapsedCentiseconds, ended.elapsedCentiseconds)
                assertEquals(beforeEnd.distanceDecimetres, ended.distanceDecimetres)
                assertEquals(1, ended.summarySequence)
                assertEquals(10, ended.workoutState)
                assertFalse(engine.isWorkoutRunning)
                assertFalse("$machine/$scenario must not emit a second end", engine.endWorkout())
                assertEquals(ended, engine.snapshot())
            }
        }
    }

    @Test
    fun `constant intervals alternate active work and motionless countdown rest`() {
        val engine = Concept2Pm5MockEngine(Concept2Machine.ROW_ERG, startsPaused = true)
        engine.configureWorkout(
            Concept2WorkoutProgram(
                workoutType = 6,
                goalKind = Concept2WorkoutGoalKind.TIME,
                goalValue = 100,
                restSeconds = 2,
            ),
        )
        engine.startWorkout()

        val rest = engine.tick(7.0)
        assertTrue(engine.isWorkoutRunning)
        assertEquals(Concept2ProgramPhase.REST, engine.programProgress()?.phase)
        assertEquals(2, engine.programProgress()?.remaining)
        assertEquals(0, rest.strokeRate)
        assertEquals(0, rest.currentPowerWatts)
        assertFalse(rest.strokeDataAvailable)

        engine.tick(1.0)
        assertEquals(1, engine.programProgress()?.remaining)
        val active = engine.tick(1.0)
        assertEquals(Concept2ProgramPhase.ACTIVE, engine.programProgress()?.phase)
        assertEquals(2, engine.programProgress()?.intervalNumber)
        assertTrue(active.strokeDataAvailable)
        assertTrue(engine.isWorkoutRunning)
    }

    @Test
    fun `variable intervals apply entry targets in order and finish after the final entry`() {
        val intervals = listOf(
            Concept2WorkoutInterval(
                Concept2WorkoutGoalKind.TIME,
                goalValue = 100,
                restSeconds = 1,
                targetStrokeRate = 30,
            ),
            Concept2WorkoutInterval(
                Concept2WorkoutGoalKind.TIME,
                goalValue = 100,
                restSeconds = 0,
                targetPowerWatts = 240,
                targetHeartRateBpm = 150,
            ),
        )
        val engine = Concept2Pm5MockEngine(Concept2Machine.ROW_ERG, startsPaused = true)
        engine.configureWorkout(
            Concept2WorkoutProgram(
                workoutType = 8,
                goalKind = intervals.first().goalKind,
                goalValue = intervals.first().goalValue,
                intervals = intervals,
            ),
        )
        engine.startWorkout()

        val firstRest = engine.tick(7.0)
        assertEquals(Concept2ProgramPhase.REST, engine.programProgress()?.phase)
        assertEquals(0, firstRest.strokeRate)
        assertFalse(firstRest.strokeDataAvailable)

        val second = engine.tick(1.0)
        assertEquals(2, engine.programProgress()?.intervalNumber)
        assertEquals(240, second.currentPowerWatts)
        assertTrue(second.heartRate in 135..165)

        val completed = engine.tick(1.0)
        assertEquals(Concept2ProgramPhase.COMPLETE, engine.programProgress()?.phase)
        assertFalse(engine.isWorkoutRunning)
        assertEquals(10, completed.workoutState)
        assertEquals(1, completed.summarySequence)
    }

    @Test
    fun `CSAFE undefined variable rest resumes automatically after thirty to sixty seconds`() {
        val intervals = listOf(
            Concept2WorkoutInterval(Concept2WorkoutGoalKind.TIME, 100, restSeconds = null),
            Concept2WorkoutInterval(Concept2WorkoutGoalKind.TIME, 100, restSeconds = null),
        )
        val engine = Concept2Pm5MockEngine(Concept2Machine.ROW_ERG, startsPaused = true)
        engine.configureWorkout(
            Concept2WorkoutProgram(9, intervals[0].goalKind, intervals[0].goalValue, intervals = intervals),
        )
        engine.startWorkout()
        val rest = engine.tick(7.0)

        assertTrue(engine.isWorkoutRunning)
        assertEquals(Concept2ProgramPhase.REST, engine.programProgress()?.phase)
        assertEquals(-1, engine.programProgress()?.remaining)
        assertEquals(0, rest.strokeRate)
        assertFalse(rest.strokeDataAvailable)
        assertFalse(engine.startWorkout())
        assertEquals(1, engine.programProgress()?.intervalNumber)

        var seconds = 0
        var current = rest
        while (engine.programProgress()?.phase == Concept2ProgramPhase.REST && seconds <= 60) {
            current = engine.tick(1.0)
            seconds++
            if (engine.programProgress()?.phase == Concept2ProgramPhase.REST) {
                assertEquals(0, current.strokeRate)
                assertFalse(current.strokeDataAvailable)
            }
        }

        assertTrue(seconds in 30..60)
        assertEquals(Concept2ProgramPhase.ACTIVE, engine.programProgress()?.phase)
        assertEquals(2, engine.programProgress()?.intervalNumber)
        assertTrue(current.strokeRate in 40..50)
    }

    @Test
    fun `CSAFE fixed undefined rest repeats automatically without strokes`() {
        val engine = Concept2Pm5MockEngine(Concept2Machine.ROW_ERG, startsPaused = true)
        engine.configureWorkout(
            Concept2WorkoutProgram(
                workoutType = 6,
                goalKind = Concept2WorkoutGoalKind.TIME,
                goalValue = 100,
                restSeconds = null,
            ),
        )
        engine.startWorkout()
        val rest = engine.tick(7.0)

        assertEquals(Concept2ProgramPhase.REST, engine.programProgress()?.phase)
        assertEquals(0, rest.strokeRate)
        assertFalse(rest.strokeDataAvailable)

        var seconds = 0
        while (engine.programProgress()?.phase == Concept2ProgramPhase.REST && seconds <= 60) {
            engine.tick(1.0)
            seconds++
        }
        assertTrue(seconds in 30..60)
        assertEquals(2, engine.programProgress()?.intervalNumber)
        assertTrue(engine.snapshot().strokeRate in 40..50)
    }

    @Test
    fun `CSAFE targets bound generated cadence and heart rate and override finish scenario`() {
        val engine = Concept2Pm5MockEngine(
            Concept2Machine.ROW_ERG,
            scenario = Concept2Scenario.FINISH,
            startsPaused = true,
        )
        engine.configureWorkout(
            Concept2WorkoutProgram(
                workoutType = 2,
                goalKind = Concept2WorkoutGoalKind.DISTANCE,
                goalValue = 10_000,
                targetStrokeRate = 30,
                targetHeartRateBpm = 150,
            ),
        )
        engine.startWorkout()
        engine.tick(7.0)

        repeat(180) {
            val active = engine.tick(0.5)
            assertTrue(active.strokeRate in 27..33)
            assertTrue(active.heartRate in 135..165)
            assertTrue(active.currentPowerWatts > 0)
            assertTrue(engine.isWorkoutRunning)
        }
    }

    @Test
    fun `reset clears an active workout and returns to paused idle`() {
        val engine = Concept2Pm5MockEngine(Concept2Machine.ROW_ERG, startsPaused = true)
        engine.startWorkout()
        engine.tick(8.0)
        assertTrue(engine.snapshot().distanceDecimetres > 0)

        engine.resetWorkout()
        val reset = engine.snapshot()

        assertFalse(engine.isWorkoutRunning)
        assertEquals(Concept2Pm5StartupPhase.IDLE, engine.startupPhase)
        assertEquals(Concept2Pm5OperationalState.IDLE, reset.operationalState)
        assertEquals(0, reset.elapsedCentiseconds)
        assertEquals(0, reset.distanceDecimetres)
        assertEquals(0, reset.totalCalories)
        assertEquals(0, reset.strokeCount)
        assertEquals(0, reset.splitSequence)
        assertEquals(0, reset.summarySequence)
        assertEquals(0, reset.forceCurveSequence)
        assertEquals(reset, engine.tick(60.0))
    }

    @Test
    fun `heart rate receive input is exact width typed copied and connection scoped`() {
        val first = Concept2Pm5Session()
        val second = Concept2Pm5Session()
        val input = ByteArray(20).also { it[0] = 1 }

        assertTrue(first.setHeartRateInput(input))
        input[1] = 0x55
        assertEquals(0, first.heartRateInput!![1].toInt())
        assertNull(second.heartRateInput)
        assertFalse(first.setHeartRateInput(ByteArray(19)))
        assertFalse(first.setHeartRateInput(ByteArray(20).also { it[0] = 2 }))
        first.reset()
        assertNull(first.heartRateInput)
    }

    private fun u24le(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16)

    private fun u16le(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)

    @Test
    fun `every PM5 persona remains coherent for thirty simulated minutes`() {
        Concept2Machine.entries.forEach { machine ->
            val engine = Concept2Pm5MockEngine(machine, Concept2Scenario.INTERVALS)
            engine.startWorkout()
            repeat(6) { engine.tick(0.5) }
            var previous = engine.snapshot()
            repeat(3_600) {
                val current = engine.tick(0.5)
                assertTrue(current.elapsedCentiseconds >= previous.elapsedCentiseconds)
                assertTrue(current.distanceDecimetres >= previous.distanceDecimetres)
                assertTrue(current.speedMillimetresPerSecond in 1..65_535)
                assertTrue(current.currentPowerWatts in 0..65_535)
                val reciprocalPace = 500_000.0 / current.speedMillimetresPerSecond * 100.0
                assertEquals(reciprocalPace, current.currentPaceCentiseconds.toDouble(), 1.0)
                assertTrue(current.strokeState in 0..4)
                previous = current
            }
            assertEquals(180_100, previous.elapsedCentiseconds)
        }
    }
}
