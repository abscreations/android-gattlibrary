package com.gmail.absolutebollockscreations.gattlibrary.server

import com.gmail.absolutebollockscreations.gattlibrary.core.ftms.FtmsMachineProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import com.gmail.absolutebollockscreations.gattlibrary.core.bytes.LittleEndian.u16Wrapping
import com.gmail.absolutebollockscreations.gattlibrary.core.ftms.FtmsSpec
import com.gmail.absolutebollockscreations.gattlibrary.core.ftms.FtmsUuids

/**
 * Byte-level tests for the FTMS encoders.
 *
 * These exist because flag/field mismatches, scaling errors and sign errors are
 * invisible in review and only show up as wrong numbers on someone's watch.
 * Every expectation here is derived from FTMS v1.0 tables 4.5-4.10, not from
 * the implementation.
 */
class FtmsEncoderTest {

    // ── helpers ──────────────────────────────────────────────────────────────

    private fun flags(p: ByteArray, width: Int = 2): Int = when (width) {
        3 -> (p[0].toInt() and 0xFF) or ((p[1].toInt() and 0xFF) shl 8) or ((p[2].toInt() and 0xFF) shl 16)
        else -> (p[0].toInt() and 0xFF) or ((p[1].toInt() and 0xFF) shl 8)
    }

    private fun Int.bitSet(n: Int) = (this shr n) and 1 == 1

    private fun u16At(p: ByteArray, off: Int) =
        (p[off].toInt() and 0xFF) or ((p[off + 1].toInt() and 0xFF) shl 8)

    private fun s16At(p: ByteArray, off: Int) = u16At(p, off).toShort().toInt()

    private fun u24At(p: ByteArray, off: Int) =
        (p[off].toInt() and 0xFF) or
            ((p[off + 1].toInt() and 0xFF) shl 8) or
            ((p[off + 2].toInt() and 0xFF) shl 16)

    private val full = FtmsMetricSnapshot(
        speedKph = 32.5,
        averageSpeedKph = 24.75,
        paceSecondsPer500m = 125.4,
        averagePaceSecondsPer500m = 130.6,
        cadence = 85.0,
        averageCadence = 75.0,
        powerWatts = 210,
        averagePowerWatts = 180,
        forceOnBeltNewtons = 42,
        heartRate = 142,
        distanceMeters = 12345,
        elapsedSeconds = 1830,
        caloriesTotal = 250,
        caloriesPerHour = 600,
        caloriesPerMinute = 10,
        resistance = 7f,
        inclinationPercent = -2.5,
        positiveElevationGainMeters = 48.0,
        negativeElevationGainMeters = 12.0,
        floors = 16,
        strokeCount = 420,
    )

    // ── packer invariants ────────────────────────────────────────────────────

    @Test
    fun `no packet exceeds the ATT payload limit`() {
        for (profile in FtmsMachineProfile.entries) {
            val packets = FtmsDataEncoders.encode(
                profile,
                full,
                full.speedKph,
                payloadMode = FtmsPayloadMode.STRICT_FRAGMENTATION,
            )
            assertTrue("$profile produced no packets", packets.isNotEmpty())
            packets.forEachIndexed { i, p ->
                assertTrue(
                    "$profile packet $i is ${p.size} octets, limit is ${FtmsPacker.MAX_PAYLOAD}",
                    p.size <= FtmsPacker.MAX_PAYLOAD,
                )
            }
        }
    }

    @Test
    fun `first packet clears More Data and continuations set it`() {
        for (profile in FtmsMachineProfile.entries) {
            val width = if (profile == FtmsMachineProfile.ELLIPTICAL) 3 else 2
            val packets = FtmsDataEncoders.encode(
                profile,
                full,
                full.speedKph,
                payloadMode = FtmsPayloadMode.STRICT_FRAGMENTATION,
            )
            assertEquals("$profile first packet must carry the mandatory field", 0, flags(packets[0], width) and 1)
            packets.drop(1).forEach {
                assertEquals("$profile continuation must set More Data", 1, flags(it, width) and 1)
            }
        }
    }

    @Test
    fun `elapsed time appears in every fragment of a split record`() {
        // FTMS revision D05r06 mandates Elapsed Time in each notification when a
        // Data Record is split, so a client can correlate the fragments.
        val bitFor = mapOf(
            FtmsMachineProfile.INDOOR_BIKE to 11,
            FtmsMachineProfile.TREADMILL to 10,
            FtmsMachineProfile.ELLIPTICAL to 13,
            FtmsMachineProfile.INDOOR_ROWER to 11,
            FtmsMachineProfile.STAIR_STEPPER to 7,
            FtmsMachineProfile.STAIR_CLIMBER to 8,
        )
        for ((profile, bit) in bitFor) {
            val width = if (profile == FtmsMachineProfile.ELLIPTICAL) 3 else 2
            val packets = FtmsDataEncoders.encode(
                profile,
                full,
                full.speedKph,
                payloadMode = FtmsPayloadMode.STRICT_FRAGMENTATION,
            )
            if (packets.size == 1) continue // not split; nothing to prove
            packets.forEachIndexed { i, p ->
                assertTrue(
                    "$profile fragment $i is missing Elapsed Time",
                    flags(p, width).bitSet(bit),
                )
            }
        }
    }

    @Test
    fun `packer rejects unsorted optional fields`() {
        var threw = false
        try {
            FtmsPacker.pack(
                flagsWidth = 2,
                mandatory = u16Wrapping(100),
                optionals = listOf(FtmsField(4, u16Wrapping(1)), FtmsField(2, u16Wrapping(2))),
            )
        } catch (e: IllegalArgumentException) {
            threw = true
        }
        assertTrue("ascending-bit-order precondition was not enforced", threw)
    }

    // ── Indoor Bike, 0x2AD2 ──────────────────────────────────────────────────

    @Test
    fun `indoor bike uses bit 2 for cadence not bit 1`() {
        // FTMS Table 4.10: bit 1 is Average Speed, bit 2 is Instantaneous
        // Cadence. FitBridge had these transposed, which mis-assigned both
        // fields for any spec-conformant client.
        val only = FtmsMetricSnapshot(speedKph = 10.0, cadence = 90.0)
        val p = FtmsDataEncoders.encode(FtmsMachineProfile.INDOOR_BIKE, only, 10.0).single()
        val f = flags(p)
        assertTrue("cadence must be declared by bit 2", f.bitSet(2))
        assertTrue("bit 1 stays clear when Average Speed is absent", !f.bitSet(1))
        assertEquals("cadence is uint16 in 0.5 rpm", 180, u16At(p, 4))
    }

    @Test
    fun `indoor bike round trips every field`() {
        val packets = FtmsDataEncoders.encode(
            FtmsMachineProfile.INDOOR_BIKE,
            full,
            full.speedKph,
            payloadMode = FtmsPayloadMode.STRICT_FRAGMENTATION,
        )
        val decoded = mutableMapOf<String, Int>()
        for (p in packets) {
            val f = flags(p)
            var off = 2
            if (!f.bitSet(0)) { decoded["speed"] = u16At(p, off); off += 2 }
            if (f.bitSet(1)) { decoded["averageSpeed"] = u16At(p, off); off += 2 }
            if (f.bitSet(2)) { decoded["cadence"] = u16At(p, off); off += 2 }
            if (f.bitSet(3)) { decoded["averageCadence"] = u16At(p, off); off += 2 }
            if (f.bitSet(4)) { decoded["distance"] = u24At(p, off); off += 3 }
            if (f.bitSet(5)) { decoded["resistance"] = s16At(p, off); off += 2 }
            if (f.bitSet(6)) { decoded["power"] = s16At(p, off); off += 2 }
            if (f.bitSet(7)) { decoded["averagePower"] = s16At(p, off); off += 2 }
            if (f.bitSet(8)) {
                decoded["kcal"] = u16At(p, off)
                decoded["kcalPerHour"] = u16At(p, off + 2)
                decoded["kcalPerMin"] = p[off + 4].toInt() and 0xFF
                off += 5
            }
            if (f.bitSet(9)) { decoded["hr"] = p[off].toInt() and 0xFF; off += 1 }
            if (f.bitSet(11)) { decoded["elapsed"] = u16At(p, off); off += 2 }
            assertEquals("packet length must match its flags exactly", p.size, off)
        }
        assertEquals(3250, decoded["speed"])       // 32.5 km/h x100
        assertEquals(2475, decoded["averageSpeed"])
        assertEquals(170, decoded["cadence"])      // 85 rpm x2
        assertEquals(150, decoded["averageCadence"])
        assertEquals(12345, decoded["distance"])
        assertEquals(7, decoded["resistance"])
        assertEquals(210, decoded["power"])
        assertEquals(180, decoded["averagePower"])
        assertEquals(250, decoded["kcal"])
        assertEquals(600, decoded["kcalPerHour"])
        assertEquals(10, decoded["kcalPerMin"])
        assertEquals(142, decoded["hr"])
        assertEquals(1830, decoded["elapsed"])
    }

    @Test
    fun `null fields clear their flag bit rather than sending zero`() {
        val sparse = FtmsMetricSnapshot(speedKph = 20.0)
        val p = FtmsDataEncoders.encode(FtmsMachineProfile.INDOOR_BIKE, sparse, 20.0).single()
        assertEquals("flags + speed only", 4, p.size)
        assertEquals(0, flags(p))
        assertEquals(2000, u16At(p, 2))
    }

    @Test
    fun `speed based personas emit session average speed in bit 1`() {
        val only = FtmsMetricSnapshot(speedKph = 12.5, averageSpeedKph = 10.25)
        for (profile in listOf(
            FtmsMachineProfile.INDOOR_BIKE,
            FtmsMachineProfile.TREADMILL,
            FtmsMachineProfile.ELLIPTICAL,
        )) {
            val p = FtmsDataEncoders.encode(profile, only, 12.5).single()
            val width = if (profile == FtmsMachineProfile.ELLIPTICAL) 3 else 2
            assertTrue("$profile average speed must be declared by bit 1", flags(p, width).bitSet(1))
            assertEquals("$profile instantaneous speed is first", 1250, u16At(p, width))
            assertEquals(
                "$profile average speed uses 0.01 km/h resolution",
                1025,
                u16At(p, width + 2),
            )
        }
    }

    @Test
    fun `treadmill emits force on belt and power as one paired field`() {
        val pair = FtmsMetricSnapshot(
            speedKph = 18.0,
            forceOnBeltNewtons = 50,
            powerWatts = 250,
        )
        val packet = FtmsDataEncoders.encode(
            FtmsMachineProfile.TREADMILL,
            pair,
            pair.speedKph,
        ).single()

        assertTrue("paired treadmill field must use bit 12", flags(packet).bitSet(12))
        assertEquals(50, s16At(packet, 4))
        assertEquals(250, s16At(packet, 6))

        val missingForce = pair.copy(forceOnBeltNewtons = null)
        val withoutPair = FtmsDataEncoders.encode(
            FtmsMachineProfile.TREADMILL,
            missingForce,
            missingForce.speedKph,
        ).single()
        assertTrue("power alone cannot set the paired field", !flags(withoutPair).bitSet(12))
    }

    @Test
    fun `speed-based personas never fabricate speed`() {
        for (profile in listOf(FtmsMachineProfile.TREADMILL, FtmsMachineProfile.ELLIPTICAL)) {
            assertTrue(
                "$profile must not fabricate a speed",
                FtmsDataEncoders.encode(profile, full, null).isEmpty(),
            )
            assertTrue("$profile requiresSpeed", requiresSpeed(profile))
        }
        val bike = FtmsDataEncoders.encode(
            FtmsMachineProfile.INDOOR_BIKE,
            full,
            null,
            maxPayload = 509,
        ).single()
        assertTrue("Indoor Bike uses More Data to omit speed", flags(bike).bitSet(0))
        assertTrue("Indoor Bike still declares speed as its mandatory field", requiresSpeed(FtmsMachineProfile.INDOOR_BIKE))
    }

    // ── signed fields ────────────────────────────────────────────────────────

    @Test
    fun `negative power survives as twos complement`() {
        val braking = FtmsMetricSnapshot(speedKph = 5.0, powerWatts = -50)
        val p = FtmsDataEncoders.encode(FtmsMachineProfile.INDOOR_BIKE, braking, 5.0).single()
        assertTrue(flags(p).bitSet(6))
        assertEquals(-50, s16At(p, 4))
    }

    @Test
    fun `negative inclination survives and ramp angle is derived`() {
        val downhill = FtmsMetricSnapshot(speedKph = 8.0, inclinationPercent = -2.5)
        val p = FtmsDataEncoders.encode(FtmsMachineProfile.TREADMILL, downhill, 8.0).single()
        assertTrue("inclination is declared by bit 3", flags(p).bitSet(3))
        // Inclination sint16 x10, then Ramp Angle sint16 x10 - one flag bit
        // covers both fields, so both must be present.
        assertEquals(-25, s16At(p, 4))
        val ramp = s16At(p, 6)
        // atan(-0.025) = -1.43 degrees -> -14 in 0.1 degree units
        assertEquals(-14, ramp)
        assertTrue("ramp must share the sign of the grade", ramp < 0)
    }

    // ── Cross Trainer, 0x2ACE ────────────────────────────────────────────────

    @Test
    fun `cross trainer uses a three octet flags field`() {
        val p = FtmsDataEncoders.encode(FtmsMachineProfile.ELLIPTICAL, full, full.speedKph)[0]
        // Speed starts at offset 3, not 2 - a 2-octet read here would decode
        // every subsequent field one byte early.
        assertEquals(3250, u16At(p, 3))
        assertTrue("step count is bit 3", flags(p, 3).bitSet(3))
        assertTrue("stride count is bit 4", flags(p, 3).bitSet(4))
    }

    @Test
    fun `cross trainer stride count is transmitted in tenths`() {
        // This test previously asserted 123 and justified it by claiming
        // TrackMyIndoorWorkout's x10 was a legacy Dart decoder quirk with no
        // spec basis. That was backwards. The 2ACE XML gives Stride Count
        // `DecimalExponent -1`, so tenths is the spec and TMIW was right.
        //
        // Rower Stroke Count (0x2AD1) has no exponent and shares this same
        // snapshot field, which is why the scaling lives in the cross trainer
        // field builder rather than at the snapshot.
        val snapshot = FtmsMetricSnapshot(speedKph = 8.0, strokeCount = 123)
        val packet = FtmsDataEncoders.encode(
            FtmsMachineProfile.ELLIPTICAL,
            snapshot,
            snapshot.speedKph,
        ).single()

        assertTrue("stride count must be declared by bit 4", flags(packet, 3).bitSet(4))
        assertEquals("123 strides go out as 1230", 1230, u16At(packet, 5))
    }

    @Test
    fun `rower stroke count is not scaled like cross trainer stride count`() {
        // Both read FtmsMetricSnapshot.strokeCount; only 2ACE declares tenths.
        val snapshot = FtmsMetricSnapshot(cadence = 30.0, strokeCount = 123)
        val packet = FtmsDataEncoders.encode(
            FtmsMachineProfile.INDOOR_ROWER,
            snapshot,
            null,
        ).single()

        // Mandatory block is stroke rate (uint8, 0.5 spm) then stroke count.
        assertEquals("stroke count is a plain uint16", 123, u16At(packet, 3))
    }

    @Test
    fun `cross trainer resistance level is scaled to tenths on the wire`() {
        // A Garmin datafield rendered a level of 6 as 0.6, so that consumer
        // applies a 0.1 resolution to this field. Sending the level raw loses a
        // factor of ten at the reader.
        //
        // Scoped to Cross Trainer deliberately - see the sibling test below.
        val snapshot = FtmsMetricSnapshot(speedKph = 8.0, resistance = 7f)
        val packet = FtmsDataEncoders.encode(
            FtmsMachineProfile.ELLIPTICAL,
            snapshot,
            snapshot.speedKph,
        ).single()

        assertTrue("resistance is bit 7", flags(packet, 3).bitSet(7))
        assertEquals("a level of 7 goes out as 70", 70, s16At(packet, 5))
    }

    @Test
    fun `indoor bike and rower resistance are left unscaled`() {
        // There is no hardware evidence for the resolution these two consumers
        // expect, and the physical elliptical reports levels as whole numbers -
        // so machine and watch already disagree on 0x2ACE. Changing 0x2AD2 and
        // 0x2AD1 on that basis would be a guess applied to machines that cannot
        // be tested. Recorded in the divergence register instead.
        val snapshot = FtmsMetricSnapshot(speedKph = 8.0, resistance = 7f)

        val bike = FtmsDataEncoders.encode(
            FtmsMachineProfile.INDOOR_BIKE,
            snapshot,
            snapshot.speedKph,
        ).single()
        assertTrue("bike resistance is bit 5", flags(bike).bitSet(5))
        assertEquals("bike resistance is unscaled", 7, s16At(bike, 4))

        val rowerSnapshot = FtmsMetricSnapshot(cadence = 20.0, strokeCount = 1, resistance = 7f)
        val rower = FtmsDataEncoders.encode(
            FtmsMachineProfile.INDOOR_ROWER,
            rowerSnapshot,
            null,
        ).single()
        assertTrue("rower resistance is bit 7", flags(rower).bitSet(7))
    }

    @Test
    fun `resistance scaling cannot overflow the sint16 field`() {
        // Clamping after the multiply would let a wild upstream level wrap into
        // a small, plausible-looking number rather than saturating.
        assertEquals(32767, s16At(FtmsDataEncoders.crossTrainerResistanceField(9_000f), 0))
        assertEquals(-32768, s16At(FtmsDataEncoders.crossTrainerResistanceField(-9_000f), 0))
    }

    @Test
    fun `a fractional resistance level survives the encoder`() {
        // The whole point of widening the snapshot field to Float: a compliant
        // machine reporting 0.7 used to round to 1 before reaching here.
        assertEquals(7, s16At(FtmsDataEncoders.crossTrainerResistanceField(0.7f), 0))
        assertEquals(65, s16At(FtmsDataEncoders.crossTrainerResistanceField(6.5f), 0))
    }

    @Test
    fun `cross trainer resistance round trips unchanged through the bridge`() {
        // FitBridge is a bridge, so the scaling applied on decode and the scaling
        // applied on encode must cancel exactly. Any characteristic where they do
        // not is a defect by construction, which is how both the 10x resistance
        // and the 10x stride count got in.
        val rawFromMachine = 7
        val decoded = rawFromMachine / 10.0f // BtEngineFtmsParsers, div10 = true
        val reencoded = s16At(FtmsDataEncoders.crossTrainerResistanceField(decoded), 0)

        assertEquals("what the machine sent must be what the client receives", rawFromMachine, reencoded)
    }

    @Test
    fun `every cross trainer field is emitted in ascending flag bit order`() {
        // Inclination is bit 6 and Resistance Level is bit 7, so inclination must
        // come first. The central parser had these reversed and it stayed hidden
        // because the two fields' widths cancel - 2 + 4 octets either way. Only
        // the values are wrong, never the length, so nothing downstream faults.
        for (payload in listOf(FtmsPacker.MAX_PAYLOAD, 182)) {
            val packets = FtmsDataEncoders.encode(
                FtmsMachineProfile.ELLIPTICAL,
                full,
                full.speedKph,
                maxPayload = payload,
            )
            for (p in packets) {
                val bits = (0..15).filter { flags(p, 3).bitSet(it) && it != 0 }
                assertEquals("packet fields must ascend by bit", bits.sorted(), bits)
            }
        }
    }

    @Test
    fun `compact framing rejects fields that are not in ascending bit order`() {
        // The compact path bypasses FtmsPacker's ordering guard and its bit lists
        // are literals, so it needs its own check.
        val out = FtmsDataEncoders.encode(
            FtmsMachineProfile.ELLIPTICAL,
            full,
            full.speedKph,
            maxPayload = 20,
            garminCompatibilityFraming = true,
        )
        for (p in out) {
            val bits = (1..15).filter { flags(p, 3).bitSet(it) }
            assertEquals("compact frame fields must ascend by bit", bits.sorted(), bits)
        }
        // Inclination (6) and resistance (7) are split across the two frames, so
        // they can never be transposed within one - pin that, since it is the
        // property that makes the compact layout safe rather than lucky.
        assertTrue("resistance rides in frame 1", flags(out[0], 3).bitSet(7))
        assertTrue("inclination rides in frame 2", flags(out[1], 3).bitSet(6))
        assertTrue("frame 1 must not also carry inclination", !flags(out[0], 3).bitSet(6))
    }

    @Test
    fun `Garmin compact framing carries the same resistance scaling`() {
        // Compact framing relocates resistance into the first frame's spare
        // bytes. It shares the field builder, so this pins that it stays shared.
        val packets = FtmsDataEncoders.encode(
            FtmsMachineProfile.ELLIPTICAL,
            full,
            full.speedKph,
            maxPayload = 20,
            garminCompatibilityFraming = true,
        )

        assertTrue("resistance rides in the first frame", flags(packets[0], 3).bitSet(7))
        assertEquals("a level of 7 goes out as 70", 70, s16At(packets[0], 16))
    }

    @Test
    fun `Garmin MTU 23 cross trainer framing is exactly two full packets`() {
        var outcome: FtmsPackOutcome? = null
        val packets = FtmsDataEncoders.encode(
            FtmsMachineProfile.ELLIPTICAL,
            full,
            full.speedKph,
            maxPayload = 20,
            onPackOutcome = { outcome = it },
            garminCompatibilityFraming = true,
        )

        assertEquals(FtmsPackOutcome.FRAGMENTED, outcome)
        assertEquals(listOf(20, 20), packets.map { it.size })

        val firstFlags = flags(packets[0], 3)
        assertEquals(0x209E, firstFlags)
        assertEquals(3250, u16At(packets[0], 3))
        assertEquals(2475, u16At(packets[0], 5))
        assertEquals(12345, u24At(packets[0], 7))
        assertEquals(85, u16At(packets[0], 10))
        assertEquals(75, u16At(packets[0], 12))
        // Stride count 420, in tenths per the 2ACE DecimalExponent of -1.
        assertEquals(4200, u16At(packets[0], 14))
        // Resistance level 7, scaled by CROSS_TRAINER_RESISTANCE_SCALE. This
        // asserted the raw 7 until 2026-08-06, when a Garmin datafield was found
        // rendering a level of 6 as 0.6.
        assertEquals(70, s16At(packets[0], 16))
        assertEquals(1830, u16At(packets[0], 18))

        val continuationFlags = flags(packets[1], 3)
        assertEquals(0x0761, continuationFlags)
        assertTrue("continuation must set More Data", continuationFlags.bitSet(0))
        assertTrue("heart rate must be omitted", !continuationFlags.bitSet(11))
        assertTrue("elapsed time must be omitted from the continuation", !continuationFlags.bitSet(13))
        assertEquals(48, u16At(packets[1], 3))
        assertEquals(12, u16At(packets[1], 5))
        assertEquals(-25, s16At(packets[1], 7))
        assertEquals(-14, s16At(packets[1], 9))
        assertEquals(210, s16At(packets[1], 11))
        assertEquals(180, s16At(packets[1], 13))
        assertEquals(250, u16At(packets[1], 15))
        assertEquals(600, u16At(packets[1], 17))
        assertEquals(10, packets[1][19].toInt() and 0xFF)
    }

    @Test
    fun `Garmin framing is limited to the MTU 23 cross trainer path`() {
        val normalMtu23 = FtmsDataEncoders.encode(
            FtmsMachineProfile.ELLIPTICAL,
            full,
            full.speedKph,
        )
        assertEquals(listOf(18, 19, 11), normalMtu23.map { it.size })

        val largeMtu = FtmsDataEncoders.encode(
            FtmsMachineProfile.ELLIPTICAL,
            full,
            full.speedKph,
            maxPayload = 509,
            garminCompatibilityFraming = true,
        )
        assertEquals(1, largeMtu.size)
        assertTrue("large-MTU record keeps heart rate", flags(largeMtu.single(), 3).bitSet(11))
        assertTrue("large-MTU record keeps elapsed time", flags(largeMtu.single(), 3).bitSet(13))
    }

    // ── Rower, 0x2AD1 ────────────────────────────────────────────────────────

    @Test
    fun `rower emits mandatory stroke rate and count without a speed field`() {
        val p = FtmsDataEncoders.encode(FtmsMachineProfile.INDOOR_ROWER, full, null)[0]
        assertEquals("More Data clear means stroke fields present", 0, flags(p) and 1)
        assertEquals("stroke rate is uint8 in 0.5 stroke/min", 170, p[2].toInt() and 0xFF)
        assertEquals("stroke count is uint16", 420, u16At(p, 3))
        assertTrue("rower needs no speed", !requiresSpeed(FtmsMachineProfile.INDOOR_ROWER))
    }

    @Test
    fun `rower uses two MTU 23 notifications and puts both pace values first`() {
        val packets = FtmsDataEncoders.encode(
            FtmsMachineProfile.INDOOR_ROWER,
            full,
            null,
        )
        assertEquals("full rower record should need exactly two frames", 2, packets.size)
        assertEquals("first frame carries mandatory stroke fields", 0, flags(packets[0]) and 1)
        assertEquals("second frame is a More Data continuation", 1, flags(packets[1]) and 1)
        val packet = packets.first()

        assertTrue("instantaneous pace bit 3", flags(packet).bitSet(3))
        assertTrue("average pace bit 4", flags(packet).bitSet(4))
        assertEquals(125, u16At(packet, 9))
        assertEquals(131, u16At(packet, 11))
    }

    // ── feature bits versus emitted fields ───────────────────────────────────

    @Test
    fun `feature bits claim average speed for speed based personas`() {
        val f = FtmsSpec.Feature
        val averageSpeedProfiles = setOf(
            FtmsMachineProfile.INDOOR_BIKE,
            FtmsMachineProfile.TREADMILL,
            FtmsMachineProfile.ELLIPTICAL,
        )
        for (profile in FtmsMachineProfile.entries) {
            val bits = profile.featureBits
            assertEquals(
                "$profile Pace capability",
                profile == FtmsMachineProfile.INDOOR_ROWER,
                bits.bitSet(f.PACE),
            )
            assertTrue("$profile must not claim MET", !bits.bitSet(f.METABOLIC_EQUIVALENT))
            assertEquals(
                "$profile Average Speed capability",
                profile in averageSpeedProfiles,
                bits.bitSet(f.AVERAGE_SPEED),
            )
        }
    }

    @Test
    fun `feature bits stay inside the defined range`() {
        for (profile in FtmsMachineProfile.entries) {
            assertEquals(
                "$profile sets a reserved bit above 16",
                0,
                profile.featureBits ushr 17,
            )
        }
    }

    @Test
    fun `climbers use step count not cadence for step rate`() {
        // FTMS Tables 4.7 and 4.8 both map Step per Minute to Step Count
        // Supported (bit 6), not Cadence (bit 1). Only Indoor Bike and Rower
        // use the Cadence bit.
        val f = FtmsSpec.Feature
        for (profile in listOf(FtmsMachineProfile.STAIR_STEPPER, FtmsMachineProfile.STAIR_CLIMBER)) {
            assertTrue("$profile should claim Step Count", profile.featureBits.bitSet(f.STEP_COUNT))
            assertTrue("$profile must not claim Cadence", !profile.featureBits.bitSet(f.CADENCE))
        }
        assertTrue(FtmsMachineProfile.INDOOR_BIKE.featureBits.bitSet(f.CADENCE))
        assertTrue(FtmsMachineProfile.INDOOR_ROWER.featureBits.bitSet(f.CADENCE))
    }

    // ── machine type mapping ─────────────────────────────────────────────────

    @Test
    fun `service data carries availability and exactly one machine type bit`() {
        for (profile in FtmsMachineProfile.entries) {
            val sd = ftmsServiceData(profile)
            assertEquals("service data payload is 3 octets", 3, sd.size)
            assertEquals("Fitness Machine Available", 0x01, sd[0].toInt() and 0xFF)
            val type = (sd[1].toInt() and 0xFF) or ((sd[2].toInt() and 0xFF) shl 8)
            assertEquals("exactly one type bit", 1, Integer.bitCount(type))
            assertEquals(profile.machineTypeBit, type)
        }
    }

    @Test
    fun `step and stair climber machine types are not transposed`() {
        // The spec's "Step Climber" is characteristic 0x2ACF and type bit 2;
        // "Stair Climber" is 0x2AD0 and bit 3. Easy to swap, hard to notice.
        assertEquals(1 shl 2, FtmsMachineProfile.STAIR_STEPPER.machineTypeBit)
        assertEquals(FtmsUuids.UUID_STAIR_STEPPER_DATA, FtmsMachineProfile.STAIR_STEPPER.dataCharacteristic)
        assertEquals(1 shl 3, FtmsMachineProfile.STAIR_CLIMBER.machineTypeBit)
        assertEquals(FtmsUuids.UUID_STAIR_CLIMBER_DATA, FtmsMachineProfile.STAIR_CLIMBER.dataCharacteristic)
        assertNotEquals(
            FtmsMachineProfile.STAIR_STEPPER.dataCharacteristic,
            FtmsMachineProfile.STAIR_CLIMBER.dataCharacteristic,
        )
    }

    @Test
    fun `advertised names fit the legacy advertising budget`() {
        // 3 (Flags) + 2+name + 2+2 (UUID list) + 2+2+3 (Service Data) <= 31
        for (profile in FtmsMachineProfile.entries) {
            val used = 3 + (2 + profile.advertisedName.toByteArray().size) + (2 + 2) + (2 + 2 + 3)
            assertTrue(
                "${profile.advertisedName} needs $used of 31 octets",
                used <= 31,
            )
        }
    }

    @Test
    fun `MTU 512 carries a complete record in one notification`() {
        for (profile in FtmsMachineProfile.entries) {
            val packets = FtmsDataEncoders.encode(profile, full, full.speedKph, maxPayload = 509)
            assertEquals("$profile should not fragment at MTU 512", 1, packets.size)
            assertEquals("$profile complete record clears More Data", 0, flags(packets.single(), if (profile == FtmsMachineProfile.ELLIPTICAL) 3 else 2) and 1)
        }
    }

    @Test
    fun `explicit MTU 23 compatibility mode emits one compact record`() {
        var compacted = 0
        for (profile in FtmsMachineProfile.entries) {
            var outcome: FtmsPackOutcome? = null
            val packets = FtmsDataEncoders.encode(
                profile,
                full,
                full.speedKph,
                payloadMode = FtmsPayloadMode.COMPATIBILITY_COMPACT,
                onPackOutcome = { outcome = it },
            )
            assertEquals("$profile should be one compatibility record", 1, packets.size)
            assertTrue(packets.single().size <= FtmsPacker.MAX_PAYLOAD)
            assertEquals(
                "$profile compatibility record must contain mandatory fields",
                0,
                flags(packets.single(), if (profile == FtmsMachineProfile.ELLIPTICAL) 3 else 2) and 1,
            )
            if (outcome == FtmsPackOutcome.COMPACTED) compacted++
        }
        assertTrue("at least one full persona record should require compaction", compacted > 0)
    }

    @Test
    fun `compact indoor bike preserves cadence power and heart rate`() {
        var outcome: FtmsPackOutcome? = null
        val packet = FtmsDataEncoders.encode(
            FtmsMachineProfile.INDOOR_BIKE,
            full,
            full.speedKph,
            payloadMode = FtmsPayloadMode.COMPATIBILITY_COMPACT,
            onPackOutcome = { outcome = it },
        ).single()
        val f = flags(packet)
        assertEquals(FtmsPackOutcome.COMPACTED, outcome)
        assertTrue("average speed is a compatibility-priority field", f.bitSet(1))
        assertTrue("cadence is a compatibility-priority field", f.bitSet(2))
        assertTrue("power is a compatibility-priority field", f.bitSet(6))
        assertTrue("heart rate is a compatibility-priority field", f.bitSet(9))
    }

    @Test
    fun `compact treadmill preserves average speed`() {
        var outcome: FtmsPackOutcome? = null
        val packet = FtmsDataEncoders.encode(
            FtmsMachineProfile.TREADMILL,
            full,
            full.speedKph,
            payloadMode = FtmsPayloadMode.COMPATIBILITY_COMPACT,
            onPackOutcome = { outcome = it },
        ).single()
        val f = flags(packet)
        assertEquals(FtmsPackOutcome.COMPACTED, outcome)
        assertTrue("average speed is a compatibility-priority field", f.bitSet(1))
        assertEquals(2475, u16At(packet, 4))
    }

    @Test
    fun `default MTU 23 treadmill uses More Data without dropping elevation or energy`() {
        var outcome: FtmsPackOutcome? = null
        val packets = FtmsDataEncoders.encode(
            FtmsMachineProfile.TREADMILL,
            full,
            full.speedKph,
            onPackOutcome = { outcome = it },
        )

        assertEquals(FtmsPackOutcome.FRAGMENTED, outcome)
        assertEquals("the full treadmill record needs exactly two frames", 2, packets.size)
        assertEquals("first frame carries mandatory speed", 0, flags(packets[0]) and 1)
        assertEquals("second frame is an FTMS More Data continuation", 1, flags(packets[1]) and 1)
        packets.forEach {
            assertTrue("frame exceeds the 20-byte ATT payload", it.size <= FtmsPacker.MAX_PAYLOAD)
        }

        val combinedFlags = flags(packets[0]) or flags(packets[1])
        for (bit in listOf(1, 2, 3, 4, 7, 8, 10, 12)) {
            assertTrue("treadmill field $bit was dropped across frames", combinedFlags.bitSet(bit))
        }

        // First frame: flags[2], speed[2], average speed[2], distance[3],
        // inclination+ramp[4], positive+negative elevation[4], elapsed[2].
        assertEquals(48, u16At(packets[0], 13))
        assertEquals(12, u16At(packets[0], 15))
        assertEquals(1830, u16At(packets[0], 17))
        // The continuation carries energy and heart rate, and repeats elapsed
        // time so the client can correlate both frames.
        assertEquals(250, u16At(packets[1], 2))
        assertEquals(600, u16At(packets[1], 4))
        assertEquals(10, packets[1][6].toInt() and 0xFF)
        assertEquals(142, packets[1][7].toInt() and 0xFF)
        assertEquals(1830, u16At(packets[1], 8))
        assertEquals(42, s16At(packets[1], 10))
        assertEquals(210, s16At(packets[1], 12))
    }

    @Test
    fun `step and stair climbers emit floors and positive elevation`() {
        val step = FtmsDataEncoders.encode(
            FtmsMachineProfile.STAIR_STEPPER,
            FtmsMetricSnapshot(floors = 4, strokeCount = 120, positiveElevationGainMeters = 12.0),
            null,
        ).single()
        assertTrue(flags(step).bitSet(3))
        assertEquals(4, u16At(step, 2))
        assertEquals(120, u16At(step, 4))
        assertEquals(12, u16At(step, 6))

        val stair = FtmsDataEncoders.encode(
            FtmsMachineProfile.STAIR_CLIMBER,
            FtmsMetricSnapshot(floors = 4, positiveElevationGainMeters = 12.0),
            null,
        ).single()
        assertTrue(flags(stair).bitSet(3))
        assertEquals(4, u16At(stair, 2))
        assertEquals(12, u16At(stair, 4))
    }

    @Test
    fun `strict MTU 23 mode retains ordered fragments`() {
        var outcome: FtmsPackOutcome? = null
        val packets = FtmsDataEncoders.encode(
            FtmsMachineProfile.INDOOR_BIKE,
            full,
            full.speedKph,
            payloadMode = FtmsPayloadMode.STRICT_FRAGMENTATION,
            onPackOutcome = { outcome = it },
        )
        assertEquals(FtmsPackOutcome.FRAGMENTED, outcome)
        assertTrue(packets.size > 1)
        assertEquals(0, flags(packets.first()) and 1)
        packets.drop(1).forEach { assertEquals(1, flags(it) and 1) }
    }

    @Test
    fun `missing energy rates use unavailable sentinels rather than measured zero`() {
        val snapshot = FtmsMetricSnapshot(speedKph = 10.0, caloriesTotal = 12)
        val p = FtmsDataEncoders.encode(FtmsMachineProfile.INDOOR_BIKE, snapshot, 10.0).single()
        assertTrue(flags(p).bitSet(8))
        assertEquals(12, u16At(p, 4))
        assertEquals(0xFFFF, u16At(p, 6))
        assertEquals(0xFF, p[8].toInt() and 0xFF)
    }
}
