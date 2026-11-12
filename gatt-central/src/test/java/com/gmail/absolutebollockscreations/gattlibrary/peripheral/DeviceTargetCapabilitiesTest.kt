package com.gmail.absolutebollockscreations.gattlibrary.peripheral

import com.gmail.absolutebollockscreations.gattlibrary.central.BtEngineState.FtmsCapabilities
import com.gmail.absolutebollockscreations.gattlibrary.central.DeviceCapabilities
import com.gmail.absolutebollockscreations.gattlibrary.central.flywheelProviderCapabilities
import com.gmail.absolutebollockscreations.gattlibrary.central.sensorOnlyProviderCapabilities
import com.gmail.absolutebollockscreations.gattlibrary.central.toDeviceCapabilities
import com.gmail.absolutebollockscreations.gattlibrary.central.wahooKickrProviderCapabilities
import com.gmail.absolutebollockscreations.gattlibrary.server.ReportedTargetRanges
import com.gmail.absolutebollockscreations.gattlibrary.server.TargetCapabilities
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit 4b step 2c: the bridge builds its downstream claims from the published, canonical
 * [DeviceCapabilities]. Every case compares that path with the raw-snapshot path it replaces; the
 * two must agree exactly, because the downstream Feature word and Supported-* Range
 * characteristics are packed from the result.
 */
class DeviceTargetCapabilitiesTest {

    private fun viaDevice(raw: FtmsCapabilities, pace: Boolean = false) =
        TargetCapabilities.fromDeviceCapabilities(raw.toDeviceCapabilities(pace))

    /**
     * Reference oracle: the raw-snapshot mapping deleted from main in LIB-70, kept verbatim so the
     * canonical production path is still compared with the path it replaced instead of with itself.
     */
    private fun viaRawSnapshot(caps: FtmsCapabilities) = TargetCapabilities.fromReportedRanges(
        ReportedTargetRanges(
            supportsSpeed = caps.supportsSpeed,
            supportsInclination = caps.supportsInclination,
            supportsResistance = caps.supportsResistance,
            supportsPower = caps.supportsPower,
            supportsIndoorBikeSimulation = caps.supportsIndoorBikeSimulation,
            supportsWheelCircumference = caps.supportsWheelCircumference,
            speedMin = caps.speedMin, speedMax = caps.speedMax, speedIncr = caps.speedIncr,
            speedRangeKnown = caps.speedRangeKnown,
            inclineMin = caps.inclineMin, inclineMax = caps.inclineMax, inclineIncr = caps.inclineIncr,
            inclineRangeKnown = caps.inclineRangeKnown,
            resistanceMin = caps.resistanceMin, resistanceMax = caps.resistanceMax,
            resistanceIncr = caps.resistanceIncr, resistanceRangeKnown = caps.resistanceRangeKnown,
            powerMin = caps.powerMin, powerMax = caps.powerMax, powerIncr = caps.powerIncr,
            powerRangeKnown = caps.powerRangeKnown,
        ),
    )

    private fun assertSame(label: String, raw: FtmsCapabilities) =
        assertEquals(label, viaRawSnapshot(raw), viaDevice(raw))

    @Test
    fun `no published capabilities publish nothing`() {
        assertEquals(TargetCapabilities.NONE, TargetCapabilities.fromDeviceCapabilities(DeviceCapabilities.NONE))
    }

    @Test
    fun `provider and fixture snapshots agree between the raw and canonical paths`() {
        val featuresOnly = FtmsCapabilities(
            featuresRead = true, supportsSpeed = true, supportsInclination = true, supportsPower = true,
        )
        val treadmill = featuresOnly.copy(
            speedMin = 100f, speedMax = 2_000f, speedIncr = 10f, speedRangeKnown = true,
            inclineMin = -30f, inclineMax = 150f, inclineIncr = 5f, inclineRangeKnown = true,
            powerMin = 0f, powerMax = 1_000f, powerIncr = 1f, powerRangeKnown = true,
        )
        val bike = FtmsCapabilities(
            featuresRead = true, supportsResistance = true, supportsPower = true,
            supportsIndoorBikeSimulation = true, supportsWheelCircumference = true,
            resistanceMin = 10f, resistanceMax = 1_000f, resistanceIncr = 10f, resistanceRangeKnown = true,
            powerMin = 0f, powerMax = 2_000f, powerIncr = 1f, powerRangeKnown = true,
        )
        val partial = treadmill.copy(powerRangeKnown = false, powerMin = null, powerMax = null, powerIncr = null)
        val absurdSpeed = treadmill.copy(speedMax = 60_000f)
        val everything = FtmsCapabilities(
            featuresRead = true, supportsSpeed = true, supportsInclination = true, supportsResistance = true,
            supportsPower = true, supportsIndoorBikeSimulation = true, supportsWheelCircumference = true,
            speedMin = 50f, speedMax = 3_000f, speedIncr = 5f, speedRangeKnown = true,
            inclineMin = -150f, inclineMax = 200f, inclineIncr = 1f, inclineRangeKnown = true,
            resistanceMin = 0f, resistanceMax = 300f, resistanceIncr = 5f, resistanceRangeKnown = true,
            powerMin = 0f, powerMax = 1_500f, powerIncr = 5f, powerRangeKnown = true,
        )
        val fixtures = mapOf(
            "nothing" to FtmsCapabilities(),
            "features only" to featuresOnly,
            "treadmill with ranges" to treadmill,
            "indoor bike" to bike,
            "a missing power range" to partial,
            "absurd speed maximum" to absurdSpeed,
            "every control" to everything,
            "Wahoo KICKR provider" to wahooKickrProviderCapabilities(),
            "sensor only" to sensorOnlyProviderCapabilities(),
            "Flywheel" to flywheelProviderCapabilities(),
        )
        for ((label, raw) in fixtures) assertSame(label, raw)
    }

    @Test
    fun `every 16-bit speed value survives the canonical round trip`() {
        for (n in 0..65_535) {
            val raw = FtmsCapabilities(
                featuresRead = true, supportsSpeed = true,
                speedMin = n.toFloat(), speedMax = n.toFloat(), speedIncr = n.toFloat(), speedRangeKnown = true,
            )
            assertSame("speed raw $n", raw)
        }
    }

    @Test
    fun `every 16-bit incline value survives the canonical round trip`() {
        for (n in -32_768..65_535) {
            val raw = FtmsCapabilities(
                featuresRead = true, supportsInclination = true,
                inclineMin = n.toFloat(), inclineMax = n.toFloat(),
                inclineIncr = n.coerceAtLeast(0).toFloat(), inclineRangeKnown = true,
            )
            assertSame("incline raw $n", raw)
        }
    }

    @Test
    fun `every 16-bit resistance value survives the canonical round trip`() {
        for (n in -32_768..65_535) {
            val raw = FtmsCapabilities(
                featuresRead = true, supportsResistance = true,
                resistanceMin = n.toFloat(), resistanceMax = n.toFloat(),
                resistanceIncr = n.coerceAtLeast(0).toFloat(), resistanceRangeKnown = true,
            )
            assertSame("resistance raw $n", raw)
        }
    }

    @Test
    fun `every 16-bit power value survives the canonical round trip`() {
        for (n in -32_768..65_535) {
            val raw = FtmsCapabilities(
                featuresRead = true, supportsPower = true,
                powerMin = n.toFloat(), powerMax = n.toFloat(),
                powerIncr = n.coerceAtLeast(0).toFloat(), powerRangeKnown = true,
            )
            assertSame("power raw $n", raw)
        }
    }
}
