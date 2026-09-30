package com.gmail.absolutebollockscreations.gattlibrary.simulator

import android.bluetooth.BluetoothGattCharacteristic
import com.gmail.absolutebollockscreations.gattlibrary.central.FixtureJson
import com.gmail.absolutebollockscreations.gattlibrary.core.ftms.FtmsUuids
import com.gmail.absolutebollockscreations.gattlibrary.core.ids.LifeFitnessUuids
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/**
 * Replays the observed Life Fitness setup handshake (`qz/life_fitness_issue_1018_setup_transcript.json`,
 * a physical treadmill seen from the official client, two sessions, HCI) against the simulator's
 * server side: [LifeFitnessSetupServer], the GATT manifest, [LifeFitnessNotificationPolicy] and the
 * Training Status values. The central-side half of the same fixture is covered in :gatt-central
 * (`LifeFitnessCaptureEvidenceTest`); the simulator-side half had no golden before.
 *
 * Personal field values are redacted in the fixture on purpose and stay redacted: the test fills them
 * with filler octets of the captured length, so name, email, age, gender, weight and height are
 * checked for length and ordering, never content. Language and both vendor tokens are not personal
 * and are replayed as captured. Training Status notifications other than the three observed values
 * are not exercised. The fixture records no CCCD write to the status characteristic (the peer
 * notified regardless); that delivery behaviour lives in the advertiser's Android callbacks and is
 * not reachable from a unit test.
 */
class LifeFitnessSetupTranscriptGoldenTest {
    private val transcript = FixtureJson.obj(fixture("qz/life_fitness_issue_1018_setup_transcript.json"))
    private val sessions = FixtureJson.array(transcript["sessions"]).map(FixtureJson::obj)

    private fun fixture(path: String): Any? {
        val stream = requireNotNull(javaClass.classLoader?.getResourceAsStream("fixtures/$path")) { "missing $path" }
        return FixtureJson.parse(stream.use { it.readBytes().toString(Charsets.UTF_8) })
    }

    private fun cases(session: Map<String, Any?>) = FixtureJson.array(session["cases"]).map(FixtureJson::obj)

    private fun valueOf(case: Map<String, Any?>): ByteArray =
        if (case["bytes"] != null) {
            FixtureJson.array(case["bytes"]).map { FixtureJson.int(it).toByte() }.toByteArray()
        } else {
            // Redacted personal value: filler of the captured length.
            ByteArray(FixtureJson.int(case["byteLength"])) { 0x61 }
        }

    private fun uuidOf(case: Map<String, Any?>) = UUID.fromString(FixtureJson.string(case["characteristic"]))

    private fun isClientWrite(case: Map<String, Any?>) =
        FixtureJson.string(case["opcode"]).let { it == "WRITE_REQUEST" || it == "WRITE_COMMAND" } &&
            !FixtureJson.string(case["step"]).startsWith("CCCD")

    @Test
    fun `both captured sessions complete the handshake on the server and draw the captured statuses`() {
        assertEquals(2, sessions.size)
        for (session in sessions) {
            val server = LifeFitnessSetupServer()
            val statuses = mutableListOf<ByteArray>()
            for (case in cases(session).filter(::isClientWrite)) {
                val step = FixtureJson.string(case["step"])
                val result = server.write(uuidOf(case), valueOf(case))
                assertTrue("session ${session["session"]} step '$step' accepted", result.accepted)
                result.statusNotification?.let { statuses += it }
            }
            assertEquals(LifeFitnessSetupServer.Stage.UNLOCKED, server.stage)

            val captured = cases(session)
                .filter { FixtureJson.string(it["step"]) == "status" }
                .map(::valueOf)
            assertEquals("status notifications in the capture", 2, captured.size)
            assertEquals(captured.size, statuses.size)
            captured.zip(statuses).forEachIndexed { i, (want, got) ->
                assertArrayEquals("session ${session["session"]} status #$i", want, got)
            }
        }
    }

    @Test
    fun `the stage advances at the captured points`() {
        val server = LifeFitnessSetupServer()
        val stages = mutableMapOf<String, LifeFitnessSetupServer.Stage>()
        for (case in cases(sessions.first()).filter(::isClientWrite)) {
            stages[FixtureJson.string(case["step"])] = server.write(uuidOf(case), valueOf(case)).stage
        }
        assertEquals(LifeFitnessSetupServer.Stage.PROFILE_RECEIVING, stages["first name"])
        assertEquals(LifeFitnessSetupServer.Stage.PROFILE_RECEIVING, stages["height"])
        assertEquals(LifeFitnessSetupServer.Stage.MAGIC_ACCEPTED, stages["control 2"])
        assertEquals(LifeFitnessSetupServer.Stage.UNLOCKED, server.stage)
    }

    @Test
    fun `the captured write order is the order the server requires`() {
        val steps = cases(sessions.first()).filter(::isClientWrite).map { FixtureJson.string(it["step"]) }
        assertEquals(
            listOf(
                "control 1", "first name", "last name", "email", "age", "gender", "language",
                "vendor token 1", "vendor token 2", "weight", "height", "control 2", "control 1",
            ),
            steps,
        )
    }

    @Test
    fun `each captured write uses an ATT opcode the persona's characteristic permits`() {
        val properties = LifeFitnessGattProfile.manifest(LifeFitnessMachine.TREADMILL)
            .flatMap { it.characteristics }.associate { it.uuid to it.properties }
        for (session in sessions) for (case in cases(session).filter(::isClientWrite)) {
            val uuid = uuidOf(case)
            val props = requireNotNull(properties[uuid]) { "characteristic $uuid is not served" }
            val needed = if (case["opcode"] == "WRITE_REQUEST") {
                BluetoothGattCharacteristic.PROPERTY_WRITE
            } else {
                BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE
            }
            assertTrue("${case["step"]} ($uuid) opcode ${case["opcode"]}", props and needed != 0)
        }
    }

    @Test
    fun `measurement streaming stays closed until the captured unlock point and the subscription`() {
        val measurement = LifeFitnessMachine.TREADMILL.measurementUuid
        val subscribed = setOf(measurement)
        LifeFitnessSetupServer.Stage.entries.forEach { stage ->
            assertEquals(
                "stage $stage subscribed",
                stage == LifeFitnessSetupServer.Stage.UNLOCKED,
                LifeFitnessNotificationPolicy.mayStreamMeasurement(stage, measurement, subscribed),
            )
        }
        assertFalse(
            LifeFitnessNotificationPolicy.mayStreamMeasurement(LifeFitnessSetupServer.Stage.UNLOCKED, measurement, emptySet()),
        )

        // The capture writes both CCCDs only after the closing control write, never before.
        for (session in sessions) {
            val list = cases(session)
            val close = list.indexOfLast { it["step"] == "control 1" }
            val cccd = list.indices.filter { FixtureJson.string(list[it]["step"]).startsWith("CCCD") }
            assertTrue(cccd.isNotEmpty())
            assertTrue("session ${session["session"]}: CCCD writes follow the unlock", cccd.all { it > close })
        }
    }

    @Test
    fun `the CCCD targets and notified characteristics are the ones the persona serves`() {
        val served = LifeFitnessGattProfile.manifest(LifeFitnessMachine.TREADMILL)
            .flatMap { it.characteristics }.associate { it.uuid to it.properties }
        val status = cases(sessions.first()).first { it["step"] == "status" }
        assertEquals(LifeFitnessUuids.UUID_LF_STATUS, uuidOf(status))
        val training = cases(sessions.first()).first { it["step"] == "Training Status" }
        assertEquals(FtmsUuids.UUID_TRAINING_STATUS, uuidOf(training))
        for (uuid in listOf(LifeFitnessUuids.UUID_LF_STATUS, FtmsUuids.UUID_TRAINING_STATUS, FtmsUuids.UUID_TREADMILL_DATA)) {
            assertTrue("$uuid notifies", (served[uuid] ?: 0) and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0)
        }
    }

    @Test
    fun `Training Status values follow the captured pre-workout, active, post-workout sequence`() {
        val observed = cases(sessions.first()).filter { it["step"] == "Training Status" }.map(::valueOf)
        assertEquals(3, observed.size)
        assertArrayEquals(LifeFitnessPeripheralAdvertiser.TRAINING_PRE_WORKOUT, observed[0])
        assertArrayEquals(LifeFitnessPeripheralAdvertiser.TRAINING_ACTIVE, observed[1])
        assertArrayEquals(LifeFitnessPeripheralAdvertiser.TRAINING_POST_WORKOUT, observed[2])
    }

    @Test
    fun `the two captured sessions sent byte-identical writes`() {
        fun writes(session: Map<String, Any?>) = cases(session).filter(::isClientWrite)
            .map { FixtureJson.string(it["step"]) to valueOf(it).toList() }
        assertEquals(writes(sessions[0]), writes(sessions[1]))
    }
}
