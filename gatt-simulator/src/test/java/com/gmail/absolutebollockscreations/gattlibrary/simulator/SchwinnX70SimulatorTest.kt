package com.gmail.absolutebollockscreations.gattlibrary.simulator

import android.bluetooth.BluetoothGattCharacteristic
import com.gmail.absolutebollockscreations.gattlibrary.central.BluetoothIds
import com.gmail.absolutebollockscreations.gattlibrary.central.SchwinnX70Measurement
import com.gmail.absolutebollockscreations.gattlibrary.central.SchwinnX70PowerModel
import kotlin.math.abs
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.gmail.absolutebollockscreations.gattlibrary.peripheral.advertisedName

/**
 * Schwinn x70 simulator — work units X70-S1 (persona, advertising, GATT, gate) and X70-S3
 * (console model and templating encoder), per `docs/schwinn_x70-simulator-implementation.md`.
 *
 * **No Android object is constructed here**, which is S1's own exit criterion: the advertising plan,
 * the manifest and the gate are pure descriptions, and only `buildServices` needs a device.
 */
class SchwinnX70SimulatorTest {

    // ── X70-S1: advertising ─────────────────────────────────────────────────────

    @Test
    fun `the advertisement is exactly full and its contents are the captured ones`() {
        val plan = SchwinnX70AdvertisingPlan.create()

        // Flags 3 + 128-bit UUID 18 + manufacturer 10. One more element and the stack truncates or
        // refuses to start, so this is pinned rather than assumed.
        assertEquals("ADV_IND is exactly at the legacy limit", 31, plan.advertisingBytes)
        assertEquals(SchwinnX70AdvertisingPlan.MAX_LEGACY_BYTES, plan.advertisingBytes)
        assertEquals("SCAN_RSP carries the name and TX power", 20, plan.scanResponseBytes)

        assertEquals(0x3364, plan.manufacturerId)
        assertEquals(
            listOf(0xDB, 0xD0, 0xF4, 0xD7, 0x00, 0x80),
            plan.manufacturerPayload.map { it.toInt() and 0xFF },
        )
        assertEquals("SCHWINN 170/270", plan.localName)
    }

    @Test
    fun `the name is advertised where the console advertises it, and nowhere else`() {
        // The captured name is in the scan response. A client that does not collect scan responses
        // sees a nameless device advertising one service UUID — which is what a physical console
        // looks like, and one reason §7 registers no local-name matcher for this family. Putting it
        // in ADV_IND would also overflow the 31 octets the UUID and manufacturer block already fill.
        val plan = SchwinnX70AdvertisingPlan.create()
        assertFalse(plan.includeNameInAdvertising)
        assertTrue(plan.includeNameInScanResponse)
        assertTrue(plan.includeTxPowerInScanResponse)
    }

    @Test
    fun `the simulator advertises the constant the central filters on`() {
        // §11 obligation 0, first half, in the peripheral direction: the persona is discoverable by
        // the same identifier FitBridge scans for. A simulator advertising a UUID nobody filters on
        // is a device that cannot be found, which is the V11 shape.
        assertEquals(
            BluetoothIds.UUID_X70_SERVICE,
            SchwinnX70AdvertisingPlan.create().serviceUuid,
        )
        assertEquals(
            "3bf58980-3a2f-11e6-9011-0002a5d5c51b",
            BluetoothIds.UUID_X70_SERVICE.toString(),
        )
    }

    @Test
    fun `the one persona advertises the family name`() {
        // Two physically distinct captured consoles both discover under this name, which is why
        // three selectable personas were never distinguishable on the wire and are now one.
        assertEquals("SCHWINN 170/270", SimulatorPersona.SchwinnX70.advertisedName)
    }

    // ── X70-S1: GATT manifest ───────────────────────────────────────────────────

    @Test
    fun `the service exposes the captured thirteen-characteristic surface`() {
        run {
            val services = SchwinnX70GattProfile.manifest()
            val family = services.single { it.uuid == BluetoothIds.UUID_X70_SERVICE }

            assertEquals(
                "the service exposes thirteen characteristics",
                BluetoothIds.X70_OBSERVED_CHARACTERISTICS.toSet(),
                family.characteristics.map { it.uuid }.toSet(),
            )

            val notifying = family.characteristics.filter { it.notifies }.map { it.uuid }
            assertEquals(
                "six notify; the rest read or write",
                SchwinnX70GattProfile.NOTIFY_CHARACTERISTICS.toSet(),
                notifying.toSet(),
            )

            // **Write only, from the 570u inventory.** An earlier revision served both write types
            // as a hedge; the evidence arrived and the hedge became a divergence.
            val control = family.characteristics.single { it.uuid == BluetoothIds.UUID_X70_CONTROL }
            assertFalse("the control characteristic does not notify", control.notifies)
            assertEquals(
                "write, and not write-without-response",
                BluetoothGattCharacteristic.PROPERTY_WRITE,
                control.properties,
            )

            // The family exposes three writable characteristics. FitBridge writes exactly one of
            // them; the simulator must still present all three or its GATT is narrower than a
            // console's.
            val writable = family.characteristics.filter {
                it.properties and (BluetoothGattCharacteristic.PROPERTY_WRITE or
                    BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0
            }.map { it.uuid }.toSet()
            assertEquals(
                "three writable characteristics, as the inventory shows",
                setOf(
                    BluetoothIds.UUID_X70_CONTROL,
                    BluetoothIds.UUID_X70_RW_5EC4,
                    BluetoothIds.UUID_X70_WRITE_EC86,
                ),
                writable,
            )
        }
    }

    @Test
    fun `the notify characteristics are notify-only and never readable`() {
        // The captures show notification only. A readable measurement characteristic would invite a
        // client to poll a value the console never serves that way.
        val family = SchwinnX70GattProfile.manifest()
            .single { it.uuid == BluetoothIds.UUID_X70_SERVICE }
        val measurementStreams = family.characteristics.filter {
            it.uuid in SchwinnX70GattProfile.MEASUREMENT_CHARACTERISTICS
        }
        assertEquals(3, measurementStreams.size)
        for (characteristic in measurementStreams) {
            assertEquals(
                "${characteristic.uuid} is notify and nothing else",
                BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                characteristic.properties,
            )
        }

        // The aux characteristic is read *and* notify — served notify-only until the 570u
        // inventory said otherwise.
        val aux = family.characteristics.single { it.uuid == BluetoothIds.UUID_X70_AUX_STATUS }
        assertEquals(
            BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            aux.properties,
        )
    }

    @Test
    fun `no FTMS, heart rate or battery service is served`() {
        // None appears in the captured GATT inventory. A client that finds one on an x70 has learned
        // something false about the family, and heart rate especially: this console carries it inside
        // a proprietary frame, not on 0x180D.
        run {
            val served = SchwinnX70GattProfile.manifest().map { it.uuid }.toSet()
            assertFalse(served.contains(BluetoothIds.UUID_FTMS_SERVICE))
            assertEquals(
                "only the family service and Device Information",
                setOf(BluetoothIds.UUID_X70_SERVICE, BluetoothIds.UUID_DEVICE_INFORMATION_SERVICE),
                served,
            )
        }
    }

    @Test
    fun `device information reads back the captured 270 identity`() {
        // The 570 has no captured identity at all (Q3) and the 170's was never read, so distinct
        // strings would be invented evidence. Sharing asserts only what "SCHWINN 170/270" being
        // generic already implies.
        run {
            val values = SchwinnX70GattProfile.readValues()
            assertEquals(
                SchwinnX70Identity.FIRMWARE_REVISION,
                values[BluetoothIds.UUID_FIRMWARE_REVISION]?.toString(Charsets.UTF_8),
            )
            // NUL-padded to nineteen octets, as the console sends it. TMIW's `contains` check
            // never noticed; a byte-exact simulator has to.
            val manufacturer = values[BluetoothIds.UUID_MANUFACTURER_NAME]
            assertEquals("the padded wire length", 19, manufacturer?.size)
            assertTrue(
                "and it still contains the name a client greps for",
                manufacturer?.toString(Charsets.UTF_8)?.startsWith("Nautilus, Inc.") == true,
            )
            assertEquals(
                "SCHWINN 170/270",
                values[BluetoothIds.UUID_MODEL_NUMBER]?.toString(Charsets.UTF_8),
            )
            assertNotNull("the PnP ID is served", values[BluetoothIds.UUID_PNP_ID])
        }
    }

    // ── X70-S1: the initialization gate ─────────────────────────────────────────

    @Test
    fun `the gate accepts both known literals and the structural family they belong to`() {
        assertTrue("QZ's literal, which FitBridge's own central sends", SchwinnX70SetupGate.accepts(SchwinnX70SetupGate.QZ_COMMAND))
        assertTrue("TMIW's literal", SchwinnX70SetupGate.accepts(SchwinnX70SetupGate.TMIW_COMMAND))

        // A server may accept a family where a client must choose one value. That asymmetry is the
        // V13 lesson: Life Fitness showed four vendor tokens, all absorbable by a peripheral and
        // none choosable by a central.
        val synthesised = byteArrayOf(0x04, 0x01, 0x01, 0xFA.toByte())
        assertTrue(
            "any well-formed command opens the gate",
            SchwinnX70Measurement.isWellFormedCommand(synthesised) && SchwinnX70SetupGate.accepts(synthesised),
        )
    }

    @Test
    fun `the gate rejects a command that is not well formed`() {
        assertFalse("empty", SchwinnX70SetupGate.accepts(ByteArray(0)))
        assertFalse("length prefix disagrees", SchwinnX70SetupGate.accepts(byteArrayOf(0x09, 0x01, 0x01, 0xF5.toByte())))
        assertFalse("checksum wrong", SchwinnX70SetupGate.accepts(byteArrayOf(0x04, 0x01, 0x01, 0x01)))

        val corrupted = SchwinnX70SetupGate.QZ_COMMAND.copyOf().also { it[3] = 0x7F }
        assertFalse("a corrupted known literal is not repaired", SchwinnX70SetupGate.accepts(corrupted))
    }

    // ── X70-S3: the templating encoder ──────────────────────────────────────────

    /** The shared ride engine's indoor-bike band, which the persona now publishes unscaled. */
    private companion object {
        const val ENGINE_PEAK_KPH = 38.0
        const val ENGINE_PEAK_WATTS = 260.0
        const val ENGINE_PEAK_RPM = 95.0
    }

    private fun newModel() = SchwinnX70ConsoleModel() to SchwinnX70FrameEncoder()

    /**
     * A short generated ride, one tick per pair period, standing in for the shared engine.
     *
     * Energy and distance accumulate here rather than in the model, because that is now where they
     * come from: the persona publishes the engine's totals instead of integrating its own.
     */
    private fun ride(ticks: Int, heartRate: Int = 0): List<Triple<ByteArray, ByteArray, ByteArray>> {
        val (model, encoder) = newModel()
        val seconds = SchwinnX70Ride.PAIR_PERIOD_MS / 1000.0
        var metres = 0.0
        var kcal = 0.0
        return (0 until ticks).map { tick ->
            // A ramp to the engine's peak and back, so the ride visits stillness and motion.
            val phase = tick.toDouble() / ticks
            val fraction = if (phase < 0.5) phase * 2 else (1 - phase) * 2
            val speed = ENGINE_PEAK_KPH * fraction
            val power = ENGINE_PEAK_WATTS * fraction
            metres += speed * seconds / 3.6
            kcal += power * seconds / 4184.0 / 0.24
            val state = model.advance(
                seconds = seconds,
                speedKph = speed,
                powerWatts = power,
                cadenceRpm = ENGINE_PEAK_RPM * fraction,
                heartRateBpm = heartRate,
                energyKcal = kcal,
                distanceMeters = metres,
            )
            val supplemental = encoder.encodeSupplemental(state)
            val sibling = encoder.encodeSibling(state)
            val main = encoder.encodeMain(state)
            encoder.completeTick()
            Triple(supplemental, sibling, main)
        }
    }

    // ── X70-S3: the accumulators carry the engine's totals ─────────────────────

    /**
     * The Explore the World regression.
     *
     * ETW displays sibling accumulator A as the ride's calorie total — 53 kJ over its own window
     * against 52 displayed, on a ten-minute capture. These were per-notification counters until
     * 2026-08-19 and ran up to 140x fast. They now carry the engine's own totals, so what a client
     * shows is what this app shows.
     */
    @Test
    fun `the accumulators carry the engine's own energy, distance and moving time`() {
        val (model, _) = newModel()
        var kcal = 0.0
        var metres = 0.0
        var last = model.advance(1.0, 0.0, 0.0, 0.0, 0, 0.0, 0.0)
        repeat(600) {
            kcal += 0.1
            metres += 8.0
            last = model.advance(1.0, 28.8, 180.0, 88.0, 130, kcal, metres)
        }

        assertEquals("A is the engine's calorie total", kcal.toInt(), last.siblingAccumulatorA)
        assertEquals(
            "B is 100 units per mile, as the corpus measured relative to the speed field",
            (metres / SchwinnX70ConsoleModel.METRES_PER_MILE *
                SchwinnX70ConsoleModel.SIBLING_DISTANCE_UNITS_PER_MILE).toInt(),
            last.siblingAccumulatorB,
        )
        assertEquals("0x3C counts seconds of motion, not ticks", 600, last.supplementalAccumulator)
        assertEquals(
            "and TMIW's field carries the same number as ETW's",
            kcal,
            last.energyRaw.toDouble() / SchwinnX70Measurement.TMIW_CALORIE_DIVISOR,
            0.001,
        )
    }

    /** A stopped rider accrues no time, and publishes no power. */
    @Test
    fun `the moving-time accumulator does not advance while the rider is stopped`() {
        val (model, _) = newModel()
        repeat(50) { model.advance(0.1, 12.0, 60.0, 70.0, 120, 1.0, 100.0) }
        val moving = model.advance(0.1, 12.0, 60.0, 70.0, 120, 2.0, 200.0)
        repeat(100) { model.advance(0.1, 0.0, 0.0, 0.0, 120, 2.0, 200.0) }
        val stopped = model.advance(0.1, 0.0, 0.0, 0.0, 120, 2.0, 200.0)

        assertEquals(moving.supplementalAccumulator, stopped.supplementalAccumulator)
        assertEquals("power is suppressed rather than published", 0.0, stopped.powerWatts, 0.0)
    }

    /**
     * The main stream must not contradict the sibling stream about the same ride.
     *
     * The main frame carries a resistance level, and every consumer that reads it — FitBridge's own
     * 270 path, TMIW, QZ — recovers power from `(cadence, resistance)`. So the level *is* the power,
     * encoded, and it has to invert the same table rather than be scaled from the engine's own
     * resistance band. Mapping 3-12 onto 1-22 instead put TMIW at 330-480 W against the engine's
     * 223, at 43 km/h against 34, and 17% long on distance.
     */
    @Test
    fun `the published resistance reproduces the engine's power through the model that reads it`() {
        for ((cadence, power) in listOf(88.0 to 223.0, 75.0 to 120.0, 95.0 to 310.0, 80.0 to 60.0)) {
            val (model, _) = newModel()
            var state = model.advance(1.0, 30.0, power, cadence, 130, 1.0, 10.0)
            repeat(60) { state = model.advance(1.0, 30.0, power, cadence, 130, 1.0, 10.0) }

            val modelled = SchwinnX70PowerModel.powerWatts(cadence.roundToInt(), state.resistance)
            assertTrue(
                "at $cadence rpm the level ${state.resistance} reads back as $modelled W, not $power",
                abs(modelled - power) <= maxOf(20.0, power * 0.12),
            )
            assertTrue("and it stays in the family's legal range", state.resistance in 1..22)
        }
    }

    /** A knob, not a measurement: seeded at once, then rate-limited. */
    @Test
    fun `resistance is seeded immediately and then steps rather than jittering`() {
        val (model, _) = newModel()
        val seeded = model.advance(0.096, 30.0, 223.0, 88.0, 130, 1.0, 10.0)
        assertTrue("the opening frame is already at a plausible level", seeded.resistance > 5)

        // The engine's power carries noise, and +/-8 W straddles a row boundary at 88 rpm: the
        // instantaneous best level alternates 9 / 11 every tick. The captured 270 held one level
        // across 42 seconds, so what the rate limit has to prevent is following that at 10 Hz.
        val levels = mutableListOf(seeded.resistance)
        repeat(20) { tick ->
            val jitter = if (tick % 2 == 0) -8.0 else 8.0
            levels += model.advance(0.096, 30.0, 223.0 + jitter, 88.0, 130, 1.0, 10.0).resistance
        }

        val changes = levels.zipWithNext().count { (a, b) -> a != b }
        assertTrue("two seconds of alternating noise moved the knob $changes times", changes <= 2)
        assertTrue("and never by more than one step", levels.zipWithNext().all { (a, b) -> abs(a - b) <= 1 })
    }

    /**
     * Q12: the speed the engine generates is the speed every client sees.
     *
     * ETW prints this field unconverted and labels it mph, whatever the phone's locale says —
     * confirmed across all three former personas, which emitted 13.43 / 16.28 / 20.38 for one
     * engine speed and were displayed as 13.4 / 16.4 / 20.3. The simulator converts on the way out
     * and [SchwinnX70Measurement.parseSibling] converts back on the way in, so this is a
     * round-trip: what the ride engine generated is what FitBridge publishes, and the number in
     * between is the one a native client reads correctly.
     */
    @Test
    fun `the engine's speed survives the round trip through the mph wire field`() {
        val (model, encoder) = newModel()
        val engineKph = 32.18688
        val state = model.advance(1.0, engineKph, 150.0, 90.0, 130, 10.0, 500.0)
        assertEquals("32.18688 km/h is exactly 20 mph", 20.0, state.speedMph, 0.001)

        val decoded = requireNotNull(SchwinnX70Measurement.parseSibling(encoder.encodeSibling(state)))
        assertEquals("the wire carries mph, which is what ETW displays", 20.0, decoded.speedMph, 0.01)
        assertEquals(
            "and FitBridge gets back the speed the engine generated",
            engineKph,
            decoded.speedKph,
            0.02,
        )
    }

    @Test
    fun `every octet the encoder does not own is byte-identical to its template`() {
        // The whole design in one assertion. If this fails, the simulator is emitting a frame no
        // console ever sent in a field nobody understands.
        val owned = mapOf(
            "supplemental" to setOf(0, 2, 3, 13, 14, 16, 18),
            "sibling" to setOf(0, 1, 3, 4, 6, 7, 9, 10, 12, 13),
            "main" to setOf(3, 4, 5, 8, 9, 10, 11, 12, 13, 14, 15, 16),
        )
        run {
            val template = SchwinnX70Template.DEFAULT
            for ((supplemental, sibling, main) in ride(ticks = 40)) {
                assertUntouched("supplemental", template.supplemental, supplemental, owned["supplemental"]!!)
                assertUntouched("sibling", template.sibling, sibling, owned["sibling"]!!)
                assertUntouched("main", template.main, main, owned["main"]!!)
            }
        }
    }

    private fun assertUntouched(label: String, template: ByteArray, frame: ByteArray, owned: Set<Int>) {
        assertEquals("$label length", template.size, frame.size)
        for (offset in template.indices) {
            if (offset in owned) continue
            assertEquals(
                "$label offset $offset must stay at its captured value",
                template[offset],
                frame[offset],
            )
        }
    }

    @Test
    fun `every generated frame parses as the shape it claims and as no other`() {
        run {
            for ((supplemental, sibling, main) in ride(ticks = 40)) {
                assertNotNull("supplemental", SchwinnX70Measurement.parseSupplemental(supplemental))
                assertNotNull("sibling", SchwinnX70Measurement.parseSibling(sibling))
                assertNotNull("main", SchwinnX70Measurement.parseMain(main))
                assertTrue("main prefix", SchwinnX70Measurement.isMainFrame(main))
                assertFalse(SchwinnX70Measurement.isSupplementalFrame(sibling))
                assertFalse(SchwinnX70Measurement.isSiblingFrame(supplemental))
            }
        }
    }

    @Test
    fun `the paired frames share one wrapping sequence counter`() {
        // Verified on 4,409 captured frames of a second console, so it is a protocol property and
        // not one firmware's quirk. A simulator scheduling the two streams separately would break it.
        val frames = ride(ticks = 300)
        for ((supplemental, sibling, _) in frames) {
            assertEquals("supplemental[0] == sibling[0]", supplemental[0], sibling[0])
            assertEquals("and the duplicate at byte 2", supplemental[0], supplemental[2])
        }
        val sequences = frames.map { it.first[0].toInt() and 0xFF }
        assertEquals("it starts at zero", 0, sequences.first())
        assertTrue("and wraps at 256 rather than saturating", sequences.contains(255) && sequences.drop(256).first() == 0)
    }

    @Test
    fun `resistance is one field, so the two streams cannot disagree`() {
        // Hardware agrees exactly on one console and within one during a change on the other. A
        // generator rendering both from a single state has no way to disagree at all, and this
        // asserts that it is in fact rendered from one state.
        run {
            for ((supplemental, _, main) in ride(ticks = 60)) {
                assertEquals(
                    "main byte 16 and supplemental byte 18 are the same field",
                    main[16],
                    supplemental[18],
                )
            }
        }
    }

    @Test
    fun `speed and power are zero together or neither, as 2996 captured frames require`() {
        run {
            for ((_, sibling, _) in ride(ticks = 60)) {
                val decoded = requireNotNull(SchwinnX70Measurement.parseSibling(sibling))
                assertEquals(
                    "they are one measurement, not two",
                    decoded.speedKph == 0.0,
                    decoded.powerWatts == 0.0,
                )
            }
        }
    }

    @Test
    fun `heart rate is emitted where the capture confirmed it, and absence stays absent`() {
        val withStrap = ride(ticks = 10, heartRate = 121)
        for ((supplemental, _, _) in withStrap) {
            val decoded = requireNotNull(SchwinnX70Measurement.parseSupplemental(supplemental))
            assertEquals(121, decoded.heartRateBpm)
        }

        // Zero is "no strap", not a reading of zero — the decoder suppresses it for that reason, so
        // the encoder has to be able to produce it.
        val withoutStrap = ride(ticks = 10, heartRate = 0)
        for ((supplemental, _, _) in withoutStrap) {
            assertEquals(0, requireNotNull(SchwinnX70Measurement.parseSupplemental(supplemental)).heartRateBpm)
        }
    }

    // ── X70-S3: energy and heart rate follow the power the console reports ──────

    @Test
    fun `the console clock wraps rather than saturating`() {
        // Every 64 seconds, and reproduced rather than avoided: this wrap is the edge that broke
        // TMIW's test corpus and it must be exercisable against any client.
        val ticks = (70.0 / (SchwinnX70Ride.PAIR_PERIOD_MS / 1000.0)).toInt()
        val clocks = ride(ticks = ticks)
            .map { requireNotNull(SchwinnX70Measurement.parseMain(it.third)).timeTicks }
        assertTrue("the ride is longer than one wrap", ticks > 600)
        assertTrue("the clock wrapped", clocks.zipWithNext().any { (a, b) -> b < a })
        assertTrue("and stayed inside uint16", clocks.all { it in 0..65535 })
    }

    @Test
    fun `the crank counter advances at the cadence the engine reports`() {
        // The main frame's counter and the engine's cadence have to agree, or FitBridge's inferred
        // power contradicts the console's measured one inside the same tick.
        val (model, encoder) = newModel()
        val frames = (0 until 300).map { tick ->
            val state = model.advance(1.0, 28.8, 180.0, 90.0, 130, tick * 0.1, tick * 8.0)
            encoder.encodeMain(state).also { encoder.completeTick() }
        }
        val counters = frames.map { requireNotNull(SchwinnX70Measurement.parseMain(it)).cadenceEventCounter }
        val revolutions = counters.zipWithNext().sumOf { (a, b) -> (b - a + 65536) % 65536 }
        val meanRpm = revolutions / frames.size.toDouble() * 60.0

        assertEquals("90 rpm in, 90 rpm out", 90.0, meanRpm, 1.0)
    }

    @Test
    fun `the stream marker is emitted verbatim in both captured variants`() {
        val encoder = SchwinnX70FrameEncoder()
        val variants = (0 until 8).map { encoder.streamMarker(it).map { b -> b.toInt() and 0xFF } }.toSet()
        assertEquals(
            "all four observed variants, cycled",
            setOf(
                listOf(0x05, 0x09, 0x01, 0x00, 0x00),
                listOf(0x05, 0x09, 0x01, 0x01, 0x00),
                listOf(0x05, 0x09, 0x01, 0x03, 0x00),
                listOf(0x05, 0x09, 0x02, 0x01, 0x00),
            ),
            variants,
        )
        // **The regression that matters.** `05 09 02 01 00` was rejected by the central until
        // 2026-08-19, because the marker predicate generalised a three-octet prefix from two
        // samples — the same defect the predicate was written to fix, one layer down.
        for (marker in variants) {
            val frame = marker.map { it.toByte() }.toByteArray()
            assertTrue("FitBridge recognises $marker", SchwinnX70Measurement.isStreamMarker(frame))
        }
    }

    @Test
    fun `the unexplained octets replay their captured sequence rather than a constant`() {
        // Q1: supplemental byte 3 and sibling byte 1 are structured — runs, then a sawtooth — and
        // were mischaracterised as noise because they were measured as a set rather than a sequence.
        // Replaying the captured order preserves that shape. Freezing them at one value would not,
        // and would make every frame identical in a field a native consumer might well check.
        val frames = ride(ticks = 60)
        val byte3 = frames.map { it.first[3].toInt() and 0xFF }
        val byte1 = frames.map { it.second[1].toInt() and 0xFF }

        assertTrue("byte 3 moves", byte3.toSet().size > 10)
        assertTrue("sibling byte 1 moves", byte1.toSet().size > 3)

        val template = SchwinnX70Template.DEFAULT
        assertEquals(
            "and it is the captured order, not a shuffle",
            template.supplementalByte3.take(20).toList(),
            byte3.take(20),
        )
    }

}
