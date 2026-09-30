package com.gmail.absolutebollockscreations.gattlibrary.conformance

import com.gmail.absolutebollockscreations.gattlibrary.central.FixtureJson
import com.gmail.absolutebollockscreations.gattlibrary.core.ftms.FtmsMachineProfile
import com.gmail.absolutebollockscreations.gattlibrary.core.ftms.FtmsUuids
import com.gmail.absolutebollockscreations.gattlibrary.server.BtServiceProfiles
import com.gmail.absolutebollockscreations.gattlibrary.server.FtmsDataEncoders
import com.gmail.absolutebollockscreations.gattlibrary.server.FtmsMeasurementCapabilities
import com.gmail.absolutebollockscreations.gattlibrary.server.FtmsMetricSnapshot
import com.gmail.absolutebollockscreations.gattlibrary.server.FtmsMetricStore
import com.gmail.absolutebollockscreations.gattlibrary.server.TargetCapabilities
import com.gmail.absolutebollockscreations.gattlibrary.server.TreadmillNativePaceInput
import com.gmail.absolutebollockscreations.gattlibrary.server.buildFtmsFeaturePayload
import com.gmail.absolutebollockscreations.gattlibrary.server.dataCharacteristic
import com.gmail.absolutebollockscreations.gattlibrary.server.featureBits
import com.gmail.absolutebollockscreations.gattlibrary.simulator.FtmsMockEngine
import com.gmail.absolutebollockscreations.gattlibrary.simulator.SimulatorPersona
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * L25 structural checks for the `sig.ftms` personas (user decision 2, 2026-09-30: the personas are
 * synthetic machines, not clones of any captured machine, so they are held to structure rather
 * than byte equality).
 *
 * **Well-formedness.** For each of the six machine profiles: the advertised Fitness Machine Type
 * is the one spec bit for that machine, the data characteristic is the spec UUID, the Feature
 * characteristic value is eight octets with no reserved bits, and the flags the data encoder
 * actually emits line up exactly with the Feature bits the profile claims (a field sent without its
 * bit is a protocol violation; a bit claimed but never sent misleads a strict client).
 *
 * **Representability.** The 16 TMIW FTMS machine corpora (plus the PM5's FTMS rower pair) hold data
 * notifications whose flag words show which fields real machines send. For each, every flag bit
 * must be one the matching persona encoder can emit. The corpus does NOT hold Feature, range or
 * advertisement reads for these machines (they are generated from TMIW's decoder, data
 * characteristic only), so representability is checked on data flags, which is all the evidence
 * supports. Defined flag bits the encoders cannot emit, and reserved bits some machines set anyway,
 * are declared below and asserted exactly, so a corpus or encoder change forces a decision.
 */
class FtmsStructuralTest {
    private val profiles = FtmsMockEngine.supportedMachines

    // ── spec tables (double entry with FTMS v1.0, deliberately independent of production code) ──

    private val machineTypeBit = mapOf(
        FtmsMachineProfile.TREADMILL to 0x0001,
        FtmsMachineProfile.ELLIPTICAL to 0x0002,
        FtmsMachineProfile.STAIR_STEPPER to 0x0004, // spec "Step Climber", 0x2ACF
        FtmsMachineProfile.STAIR_CLIMBER to 0x0008, // spec "Stair Climber", 0x2AD0
        FtmsMachineProfile.INDOOR_ROWER to 0x0010,
        FtmsMachineProfile.INDOOR_BIKE to 0x0020,
    )

    private val dataUuid = mapOf(
        FtmsMachineProfile.TREADMILL to "00002acd-0000-1000-8000-00805f9b34fb",
        FtmsMachineProfile.ELLIPTICAL to "00002ace-0000-1000-8000-00805f9b34fb",
        FtmsMachineProfile.STAIR_STEPPER to "00002acf-0000-1000-8000-00805f9b34fb",
        FtmsMachineProfile.STAIR_CLIMBER to "00002ad0-0000-1000-8000-00805f9b34fb",
        FtmsMachineProfile.INDOOR_ROWER to "00002ad1-0000-1000-8000-00805f9b34fb",
        FtmsMachineProfile.INDOOR_BIKE to "00002ad2-0000-1000-8000-00805f9b34fb",
    )

    /** Highest flag bit the spec defines for each data characteristic (tables 4.5 to 4.10). */
    private val highestDefinedFlag = mapOf(
        FtmsMachineProfile.TREADMILL to 12,
        FtmsMachineProfile.ELLIPTICAL to 15,
        FtmsMachineProfile.STAIR_STEPPER to 8,
        FtmsMachineProfile.STAIR_CLIMBER to 9,
        FtmsMachineProfile.INDOOR_ROWER to 12,
        FtmsMachineProfile.INDOOR_BIKE to 12,
    )

    private val flagWidth = mapOf(FtmsMachineProfile.ELLIPTICAL to 3).withDefault { 2 }

    /** Feature bit (Fitness Machine Features, table 4.3) each data flag belongs to; bit 0 excluded. */
    private val flagToFeature: Map<FtmsMachineProfile, Map<Int, Int>> = mapOf(
        FtmsMachineProfile.INDOOR_BIKE to mapOf(1 to 0, 2 to 1, 3 to 1, 4 to 2, 5 to 7, 6 to 14, 7 to 14, 8 to 9, 9 to 10, 10 to 11, 11 to 12, 12 to 13),
        FtmsMachineProfile.TREADMILL to mapOf(1 to 0, 2 to 2, 3 to 3, 4 to 4, 5 to 5, 6 to 5, 7 to 9, 8 to 10, 9 to 11, 10 to 12, 11 to 13, 12 to 15),
        FtmsMachineProfile.ELLIPTICAL to mapOf(1 to 0, 2 to 2, 3 to 6, 4 to 8, 5 to 4, 6 to 3, 7 to 7, 8 to 14, 9 to 14, 10 to 9, 11 to 10, 12 to 11, 13 to 12, 14 to 13),
        FtmsMachineProfile.INDOOR_ROWER to mapOf(1 to 1, 2 to 2, 3 to 5, 4 to 5, 5 to 14, 6 to 14, 7 to 7, 8 to 9, 9 to 10, 10 to 11, 11 to 12, 12 to 13),
        FtmsMachineProfile.STAIR_STEPPER to mapOf(1 to 6, 2 to 6, 3 to 4, 4 to 9, 5 to 10, 6 to 11, 7 to 12, 8 to 13),
        FtmsMachineProfile.STAIR_CLIMBER to mapOf(1 to 6, 2 to 6, 3 to 4, 4 to 8, 5 to 9, 6 to 10, 7 to 11, 8 to 12, 9 to 13),
    )

    // ── helpers ──

    private fun fullSnapshot(nativePace: Boolean, extended: Boolean = false) = FtmsMetricSnapshot(
        speedKph = 20.0,
        averageSpeedKph = 19.0,
        paceSecondsPer500m = 120.0,
        averagePaceSecondsPer500m = 125.0,
        cadence = 60.0,
        averageCadence = 58.0,
        powerWatts = 150,
        averagePowerWatts = 140,
        forceOnBeltNewtons = 80,
        heartRate = 130,
        distanceMeters = 1000,
        elapsedSeconds = 300,
        caloriesTotal = 50,
        caloriesPerHour = 400,
        caloriesPerMinute = 7,
        metabolicEquivalent = if (extended) 8.5 else null,
        remainingTimeSeconds = if (extended) 600 else null,
        resistance = 5f,
        inclinationPercent = 2.0,
        positiveElevationGainMeters = 10.0,
        negativeElevationGainMeters = 5.0,
        floors = 3,
        strokeCount = 120,
        treadmillNativePace = if (nativePace) TreadmillNativePaceInput(instantaneousRaw = 40, averageRaw = 38) else null,
    )

    /** The single packet a profile emits for [snapshot], with every field in it (payload budget 512). */
    private fun singlePacket(profile: FtmsMachineProfile, snapshot: FtmsMetricSnapshot): ByteArray {
        val packets = FtmsDataEncoders.encode(profile, snapshot, speedKph = 20.0, maxPayload = 512)
        assertEquals("$profile packets", 1, packets.size)
        return packets[0]
    }

    private fun flagsOf(profile: FtmsMachineProfile, packet: ByteArray): Int {
        var value = 0
        for (i in 0 until flagWidth.getValue(profile)) value = value or ((packet[i].toInt() and 0xFF) shl (8 * i))
        return value
    }

    private fun bitsOf(value: Int, from: Int = 1): Set<Int> = (from until 32).filter { value shr it and 1 == 1 }.toSet()

    /** Flag bits (above bit 0) the encoder can emit for [profile]. */
    private fun emittable(profile: FtmsMachineProfile): Set<Int> =
        bitsOf(flagsOf(profile, singlePacket(profile, fullSnapshot(nativePace = true, extended = true))))

    // ── well-formedness ──

    @Test
    fun `every profile advertises exactly its own spec Fitness Machine Type`() {
        assertEquals(machineTypeBit.keys, profiles.toSet())
        for (profile in profiles) {
            val serviceData = BtServiceProfiles.ftmsServiceDataFor(profile)
            assertEquals("$profile service data length", 3, serviceData.size)
            assertEquals("$profile Fitness Machine Available flag", 1, serviceData[0].toInt())
            val type = (serviceData[1].toInt() and 0xFF) or ((serviceData[2].toInt() and 0xFF) shl 8)
            assertEquals("$profile machine type", machineTypeBit.getValue(profile), type)
            assertEquals("$profile type has one bit", 1, Integer.bitCount(type))
            assertEquals("$profile type has no RFU bits", 0, type shr 6)
        }
    }

    @Test
    fun `the broken-advertisement rower claims a cross trainer and nothing else differs`() {
        val broken = SimulatorPersona.Ftms.BROKEN_ROWER_ADVERTISEMENT
        assertEquals(FtmsMachineProfile.INDOOR_ROWER, broken.profile)
        assertEquals(FtmsMachineProfile.ELLIPTICAL, broken.advertisedMachineType)
        assertTrue(broken.misadvertisesMachineType)
        assertEquals(false, SimulatorPersona.Ftms(FtmsMachineProfile.INDOOR_ROWER).misadvertisesMachineType)
    }

    @Test
    fun `every profile serves its spec data characteristic`() {
        for (profile in profiles) {
            assertEquals("$profile", dataUuid.getValue(profile), profile.dataCharacteristic.toString())
            assertEquals("$profile", dataUuid.getValue(profile), FtmsUuids.dataUuidForProfile(profile).toString())
        }
        assertEquals("one characteristic per profile", profiles.size, profiles.map { it.dataCharacteristic }.toSet().size)
    }

    @Test
    fun `the Feature characteristic value is eight octets with no reserved bits`() {
        for (profile in profiles) {
            val value = buildFtmsFeaturePayload(profile, TargetCapabilities.NONE)
            assertEquals("$profile Feature length", 8, value.size)
            val fitness = (0 until 4).fold(0L) { acc, i -> acc or ((value[i].toLong() and 0xFF) shl (8 * i)) }
            val target = (4 until 8).fold(0L) { acc, i -> acc or ((value[i].toLong() and 0xFF) shl (8 * (i - 4))) }
            assertEquals("$profile fitness machine features", profile.featureBits.toLong(), fitness)
            assertEquals("$profile no target settings claimed without ranges", 0L, target)
            assertEquals("$profile Fitness Machine Features reserved bits (17-31)", 0L, fitness shr 17)
        }
    }

    @Test
    fun `the flags each encoder emits match the Feature bits its profile claims`() {
        for (profile in profiles) {
            val flags = bitsOf(flagsOf(profile, singlePacket(profile, fullSnapshot(nativePace = false))))
            val map = flagToFeature.getValue(profile)
            val undefined = flags.filter { it > highestDefinedFlag.getValue(profile) }
            assertTrue("$profile emits reserved flag bits $undefined", undefined.isEmpty())
            val emittedFeatures = flags.map { flag -> requireNotNull(map[flag]) { "$profile flag $flag has no feature mapping" } }.toSet()
            val claimed = bitsOf(profile.featureBits, from = 0)
            assertEquals(
                "$profile: flags emitted (as Feature bits) vs Feature bits claimed",
                claimed,
                emittedFeatures,
            )
        }
    }

    @Test
    fun `a bike with no speed sets the inverted bit and carries no speed field`() {
        val snapshot = FtmsMetricSnapshot(cadence = 60.0)
        val packet = FtmsDataEncoders.encode(FtmsMachineProfile.INDOOR_BIKE, snapshot, speedKph = null, maxPayload = 512).single()
        assertEquals("More Data bit set when Instantaneous Speed is absent", 1, flagsOf(FtmsMachineProfile.INDOOR_BIKE, packet) and 1)
        assertEquals("flags (2) + cadence (2) only", 4, packet.size)
    }

    @Test
    fun `Metabolic Equivalent and Remaining Time land in the right bits, octets and order for every profile`() {
        // profile -> (MET flag bit, Remaining Time flag bit), from tables 4.5 to 4.10.
        val bits = mapOf(
            FtmsMachineProfile.INDOOR_BIKE to (10 to 12),
            FtmsMachineProfile.TREADMILL to (9 to 11),
            FtmsMachineProfile.ELLIPTICAL to (12 to 14),
            FtmsMachineProfile.INDOOR_ROWER to (10 to 12),
            FtmsMachineProfile.STAIR_STEPPER to (6 to 8),
            FtmsMachineProfile.STAIR_CLIMBER to (7 to 9),
        )
        assertEquals(bits.keys, profiles.toSet())
        for (profile in profiles) {
            // Only the mandatory fields plus the two new ones, so they are the last three octets.
            val snapshot = FtmsMetricSnapshot(
                cadence = if (profile == FtmsMachineProfile.INDOOR_ROWER) 30.0 else null,
                strokeCount = if (profile == FtmsMachineProfile.INDOOR_ROWER) 10 else null,
                metabolicEquivalent = 8.5,
                remainingTimeSeconds = 600,
            )
            val packet = singlePacket(profile, snapshot)
            val (met, remaining) = bits.getValue(profile)
            assertEquals("$profile flags", setOf(met, remaining), bitsOf(flagsOf(profile, packet)))
            assertEquals("$profile MET octet (8.5 METs = 85)", 85, packet[packet.size - 3].toInt() and 0xFF)
            assertEquals("$profile Remaining Time low octet (600 s)", 0x58, packet[packet.size - 2].toInt() and 0xFF)
            assertEquals("$profile Remaining Time high octet", 0x02, packet[packet.size - 1].toInt() and 0xFF)
        }
    }

    @Test
    fun `Metabolic Equivalent and Remaining Time are absent unless supplied, and claimed only when opted in`() {
        for (profile in profiles) {
            val without = bitsOf(flagsOf(profile, singlePacket(profile, fullSnapshot(nativePace = false))))
            val with = bitsOf(flagsOf(profile, singlePacket(profile, fullSnapshot(nativePace = false, extended = true))))
            assertEquals("$profile adds exactly two flag bits", 2, (with - without).size)
            assertEquals("$profile drops none", emptySet<Int>(), without - with)
            // No default claims them: nothing in the bridge supplies either field yet.
            val claimed = bitsOf(profile.featureBits, from = 0)
            assertTrue("$profile default Feature bits claim neither", 11 !in claimed && 13 !in claimed)
        }
        val optedIn = FtmsMeasurementCapabilities(metabolicEquivalent = true, remainingTime = true).toFeatureBits()
        assertEquals("opt-in sets Feature bits 11 and 13 only over the defaults",
            FtmsMeasurementCapabilities().toFeatureBits() or (1 shl 11) or (1 shl 13), optedIn)
    }

    @Test
    fun `a stale snapshot zeroes Metabolic Equivalent and holds Remaining Time`() {
        val store = FtmsMetricStore()
        store.update(fullSnapshot(nativePace = false, extended = true).copy(updatedAtMs = 1_000L))

        val fresh = requireNotNull(store.snapshotForNotification(nowMs = 1_000L + 1))
        assertEquals(8.5, fresh.metabolicEquivalent!!, 0.0)
        assertEquals(600, fresh.remainingTimeSeconds)

        val stale = requireNotNull(store.snapshotForNotification(nowMs = 1_000L + FtmsMetricSnapshot.METRIC_FRESHNESS_MS + 1))
        assertEquals("instantaneous, zeroed", 0.0, stale.metabolicEquivalent!!, 0.0)
        assertEquals("countdown, held", 600, stale.remainingTimeSeconds)
        assertEquals("cumulative, held", 300, stale.elapsedSeconds)
    }

    // ── representability ──

    private val tmiwMachines = listOf(
        "bowflex_ic7", "concept2_old_ftms", "cross_trainer", "kayak_pro", "life_fitness_cross_trainer",
        "life_fitness_indoor_bike", "life_fitness_stair_climber", "life_fitness_treadmill",
        "lifepro_flexstride_pro", "matrix_bike", "matrix_treadmill", "mr_captain", "schwinn_ic4",
        "stages_sb20", "step_climber", "technogym_skillrow", "true_treadmill", "yesoul_s3",
    )

    /**
     * Defined flag bits machines send that no persona encoder can emit. Empty since LIB-24 added
     * Metabolic Equivalent and Remaining Time, which were every gap LIB-23 found. Movement
     * Direction (Cross Trainer bit 15) is defined, is not emitted, and no captured machine sends it.
     */
    private val declaredUnsupportedDefined = emptyMap<String, Set<Int>>()

    /** Reserved (undefined) flag bits some machines set anyway; no encoder should emit these. */
    private val declaredReservedSeen = mapOf(
        "concept2_old_ftms" to setOf(13),
        "technogym_skillrow" to setOf(13),
    )

    private fun profileFor(characteristic: String): FtmsMachineProfile =
        profiles.single { it.dataCharacteristic.toString() == characteristic }

    @Test
    fun `every flag bit a captured machine sends is one its persona can emit, apart from the declared gaps`() {
        val unsupported = mutableMapOf<String, Set<Int>>()
        val reserved = mutableMapOf<String, Set<Int>>()
        var packets = 0
        for (name in tmiwMachines) {
            val fixture = Fixtures.json("tmiw/ftms_$name.json")
            val profile = profileFor(fixture["characteristic"] as String)
            assertEquals("$name flag width", flagWidth.getValue(profile), FixtureJson.int(fixture["flagByteSize"]))
            val seen = mutableSetOf<Int>()
            for (case in Fixtures.objects(fixture["cases"])) {
                val packet = Fixtures.bytes(case["packet"])
                seen += bitsOf(flagsOf(profile, packet))
                packets++
            }
            val missing = seen - emittable(profile)
            val undefined = missing.filter { it > highestDefinedFlag.getValue(profile) }.toSet()
            val defined = missing - undefined
            if (defined.isNotEmpty()) unsupported[name] = defined
            if (undefined.isNotEmpty()) reserved[name] = undefined
        }
        assertEquals("packets examined", 74, packets)
        assertEquals("defined flag bits no persona encoder emits", declaredUnsupportedDefined, unsupported)
        assertEquals("reserved flag bits sent by real machines", declaredReservedSeen, reserved)
    }

    @Test
    fun `the step climber corpus holds no notifications, so that profile has no representability evidence`() {
        // Declared gap: ftms_step_climber.json is empty, so the 0x2ACF persona is checked for
        // well-formedness only.
        assertEquals(0, Fixtures.objects(Fixtures.json("tmiw/ftms_step_climber.json")["cases"]).size)
    }

    @Test
    fun `the PM5 FTMS rower flag words are within what the rower persona emits`() {
        val rower = emittable(FtmsMachineProfile.INDOOR_ROWER)
        for (path in listOf("hardware/concept2_pm5_qz_debug.json", "hardware/concept2_pm5_431225189_20260912_ergdata_workouts.json")) {
            val vectors = FixtureJson.obj(Fixtures.json(path)["vectors"])
            val frames = Fixtures.objects(vectors["ftms_rower_data_2ad1"])
            assertTrue("$path has FTMS frames", frames.isNotEmpty())
            for (frame in frames) {
                val octets = Fixtures.hex(frame["hex"] as String)
                val flags = (octets[0].toInt() and 0xFF) or ((octets[1].toInt() and 0xFF) shl 8)
                assertEquals("$path ${frame["hex"]}", emptySet<Int>(), bitsOf(flags) - rower)
            }
        }
    }
}
