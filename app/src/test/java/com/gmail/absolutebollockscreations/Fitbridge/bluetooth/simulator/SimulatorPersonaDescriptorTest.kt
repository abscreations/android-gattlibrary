package com.gmail.absolutebollockscreations.Fitbridge.bluetooth.simulator

import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine.BtServiceProfiles.FtmsMachineProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The point of the descriptors is that every persona answers the same questions in the same
 * shape, so these tests assert the shape and the derivation, not the prose.
 */
class SimulatorPersonaDescriptorTest {

    private val everyPersona = simulatorPersonaOptions(includeKickr = true)

    @Test
    fun `speed and power persona describes CSC and CPS without target control`() {
        val descriptor = SimulatorPersona.IndoorBikeSpeedAndPower.describe()
        assertEquals("Indoor Bike Speed and Power", descriptor.title)
        assertTrue(descriptor.protocol.contains("CSC 0x1816/0x2A5B"))
        assertTrue(descriptor.protocol.contains("CPS 0x1818/0x2A63"))
        assertEquals(emptyList<String>(), descriptor.controlModes)
        assertEquals("speed, cadence, power", descriptor.metrics)
    }

    @Test
    fun `broken advertisement rower is titled and describes the advertised type`() {
        val descriptor = SimulatorPersona.Ftms.BROKEN_ROWER_ADVERTISEMENT.describe()
        assertEquals("FTMS · Rower (Broken Feature Advertisement)", descriptor.title)
        assertTrue(descriptor.protocol.contains("advertises Cross trainer machine type"))
        val honest = SimulatorPersona.Ftms(FtmsMachineProfile.INDOOR_ROWER).describe()
        assertEquals("FTMS · Rower", honest.title)
        assertEquals(honest.metrics, descriptor.metrics)
        assertEquals(honest.controlModes, descriptor.controlModes)
    }

    @Test
    fun `every persona names a protocol, a control answer and its metrics`() {
        everyPersona.forEach { persona ->
            val descriptor = persona.describe()
            assertTrue("blank title for $persona", descriptor.title.isNotBlank())
            assertTrue("blank protocol for $persona", descriptor.protocol.isNotBlank())
            assertTrue("blank metrics for $persona", descriptor.metrics.isNotBlank())
            assertTrue(
                "descriptor line for $persona omits the control slot",
                descriptor.descriptorLine.contains("Control: "),
            )
            assertTrue(
                "descriptor line for $persona omits the metrics slot",
                descriptor.descriptorLine.contains("Metrics: "),
            )
        }
    }

    @Test
    fun `control-free personas say so rather than leaving the slot empty`() {
        val measurementOnly = listOf(
            SimulatorPersona.KeiserMSeries(KeiserMSeriesSimulatorProfile.M3I_IMPERIAL),
            SimulatorPersona.PrecorChrono,
            SimulatorPersona.FlywheelSports,
            SimulatorPersona.KayakFirstBlue,
            SimulatorPersona.KayakFirstOrange,
            SimulatorPersona.StrydFootpod,
            SimulatorPersona.SchwinnX70,
            SimulatorPersona.LifeFitness(LifeFitnessMachine.TREADMILL),
            SimulatorPersona.Concept2Pm5(Concept2Machine.ROW_ERG),
            SimulatorPersona.Ftms(FtmsMachineProfile.STAIR_CLIMBER),
        )
        measurementOnly.forEach { persona ->
            val descriptor = persona.describe()
            assertTrue(descriptor.controlModes.isEmpty())
            assertEquals(SimulatorControlModeLabels.Kind.NONE, descriptor.controlSummary)
            assertTrue(descriptor.descriptorLine.contains("Control: none"))
        }
    }

    @Test
    fun `a persona reports target ranges when it accepts control and ride ranges otherwise`() {
        everyPersona.forEach { persona ->
            val descriptor = persona.describe()
            if (descriptor.ranges.isEmpty()) return@forEach
            val expected = if (descriptor.controlModes.isEmpty()) {
                SimulatorRangeKind.RIDE
            } else {
                SimulatorRangeKind.TARGETS
            }
            assertEquals("wrong range kind for $persona", expected, descriptor.rangeKind)
            assertTrue(descriptor.descriptorLine.contains("${expected.heading}: "))
        }
    }

    @Test
    fun `FTMS control modes and target spans come from the engine's own control profile`() {
        val bike = SimulatorPersona.Ftms(FtmsMachineProfile.INDOOR_BIKE).describe()
        val kind = SimulatorControlModeLabels.Kind
        assertEquals(listOf(kind.DIRECT, kind.ERG, kind.SIM), bike.controlModes)
        assertEquals("FTMS 0x2AD2 + 0x2AD9 control point", bike.protocol)
        assertEquals(
            listOf("speed", "resistance", "power"),
            bike.ranges.map { it.metric },
        )
        // FtmsMockControlProfile.powerRange is 1..1000 W in whole watts.
        assertEquals("1–1000 W", bike.ranges.single { it.metric == "power" }.span)
        // speedRange is wire 0..3000 at 0.01 km/h.
        assertEquals("0.0–30.0 km/h", bike.ranges.single { it.metric == "speed" }.span)

        // Only the Indoor Bike machine type has a Set Indoor Bike Simulation Parameters procedure.
        assertFalse(
            SimulatorPersona.Ftms(FtmsMachineProfile.TREADMILL).describe().controlModes.contains(kind.SIM),
        )
        // A negative lower bound reads as a word, not a second dash.
        assertEquals(
            "-10.0 to 30.0 %",
            SimulatorPersona.Ftms(FtmsMachineProfile.TREADMILL).describe()
                .ranges.single { it.metric == "incline" }.span,
        )
    }

    @Test
    fun `personas with no target-setting procedure advertise no control point`() {
        val stepper = SimulatorPersona.Ftms(FtmsMachineProfile.STAIR_STEPPER).describe()
        assertEquals("FTMS 0x2ACF", stepper.protocol)
        assertTrue(stepper.ranges.isEmpty())
    }

    @Test
    fun `FE-C descriptors follow the equipment type's own control capability`() {
        val trainer = SimulatorPersona.FeCBle(FeCEquipmentType.TRAINER).describe()
        assertTrue(trainer.protocol.contains("FEC3"))
        assertEquals(SimulatorRangeKind.TARGETS, trainer.rangeKind)
        assertEquals(
            "${FeCBleSyntheticModel.MIN_CONTROLLED_POWER_WATTS.toInt()}" +
                "–${FeCBleSyntheticModel.MAX_CONTROLLED_POWER_WATTS.toInt()} W",
            trainer.ranges.single { it.metric == "power" }.span,
        )

        val bike = SimulatorPersona.FeCBle(FeCEquipmentType.STATIONARY_BIKE).describe()
        assertTrue(bike.controlModes.isEmpty())
        assertFalse(bike.protocol.contains("FEC3"))
        // Both bike-shaped personas carry the trainer pages; each other type has one of its own.
        assertTrue(bike.protocol.contains("16/17/18/25/26/81"))
        assertEquals(SimulatorRangeKind.RIDE, bike.rangeKind)
        val bikePower = requireNotNull(FeCEquipmentType.STATIONARY_BIKE.workoutRange.powerWatts)
        assertEquals(
            "${bikePower.first}–${bikePower.last} W",
            bike.ranges.single { it.metric == "power" }.span,
        )
    }

    @Test
    fun `the CPS compatibility overlay stays out of the UI vocabulary`() {
        // Implicit to equipment type, never user-selectable, never described (see FeCBle's KDoc).
        everyPersona.filterIsInstance<SimulatorPersona.FeCBle>().forEach { persona ->
            assertFalse(persona.describe().descriptorLine.contains("CPS", ignoreCase = true))
        }
    }

    @Test
    fun `KICKR personas share one control vocabulary and the model's own limits`() {
        val kind = SimulatorControlModeLabels.Kind
        val kickr = everyPersona.filterIsInstance<SimulatorPersona.Kickr>()
        assertEquals(KickrSimulatorPersona.entries.size, kickr.size)
        kickr.forEach { persona ->
            val descriptor = persona.describe()
            assertEquals(
                listOf(kind.DIRECT, kind.ERG, kind.SIM),
                descriptor.controlModes,
            )
            assertEquals(
                "${KickrSyntheticModel.MIN_POWER_WATTS}–${KickrSyntheticModel.MAX_POWER_WATTS} W",
                descriptor.ranges.single { it.metric == "power" }.span,
            )
        }
    }

    @Test
    fun `the Schwinn x70 reports the family's captured resistance span`() {
        val descriptor = SimulatorPersona.SchwinnX70.describe()
        assertEquals(SimulatorRangeKind.RIDE, descriptor.rangeKind)
        assertEquals(
            "${SchwinnX70Ride.RESISTANCE_FLOOR}–${SchwinnX70Ride.RESISTANCE_CEILING}",
            descriptor.ranges.single { it.metric == "resistance" }.span,
        )
    }

    @Test
    fun `Keiser descriptor states advertisement only Android limitations`() {
        val descriptor = SimulatorPersona.KeiserMSeries(KeiserMSeriesSimulatorProfile.M3M_METRIC).describe()
        assertTrue(descriptor.protocol.contains("Non-connectable"))
        assertTrue(descriptor.protocol.contains("0x0102"))
        assertTrue(descriptor.protocol.contains("Android best effort"))
        assertTrue(descriptor.protocol.contains("5-minute validation loop"))
        assertTrue(descriptor.controlModes.isEmpty())
        assertTrue(descriptor.metrics.contains("dropouts"))
    }

    @Test
    fun `control kinds in a descriptor are the same words the runtime labels are built from`() {
        val kind = SimulatorControlModeLabels.Kind
        assertTrue(SimulatorControlModeLabels.FTMS_ERG.endsWith(kind.ERG))
        assertTrue(SimulatorControlModeLabels.FTMS_SIM.endsWith(kind.SIM))
        assertTrue(SimulatorControlModeLabels.ANT_DIRECT.endsWith(kind.DIRECT))
        assertTrue(SimulatorControlModeLabels.KICKR_SIM.endsWith(kind.SIM))
        assertTrue(SimulatorControlModeLabels.RIDEON_DIRECT.endsWith(kind.DIRECT))
    }

    @Test
    fun `each FE-C equipment type names the specific page its own rotation emits`() {
        // Naming page 25 for every type - as the old summaries did - described a frame a treadmill
        // never sends, and cadence and power live only on these pages.
        mapOf(
            FeCEquipmentType.TRAINER to "25/26",
            FeCEquipmentType.STATIONARY_BIKE to "25/26",
            FeCEquipmentType.TREADMILL to "19",
            FeCEquipmentType.ELLIPTICAL to "20",
            FeCEquipmentType.ROWER to "22",
            FeCEquipmentType.CLIMBER to "23",
            FeCEquipmentType.NORDIC_SKIER to "24",
        ).forEach { (equipment, pages) ->
            assertEquals(pages, feCSpecificPages(equipment))
            assertTrue(
                SimulatorPersona.FeCBle(equipment).describe().protocol.contains("/$pages/81"),
            )
        }
    }
}
