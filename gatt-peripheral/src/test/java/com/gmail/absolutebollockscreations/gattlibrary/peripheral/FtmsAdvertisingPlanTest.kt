package com.gmail.absolutebollockscreations.gattlibrary.peripheral

import com.gmail.absolutebollockscreations.gattlibrary.central.BluetoothIds
import com.gmail.absolutebollockscreations.gattlibrary.core.ftms.FtmsMachineProfile
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import com.gmail.absolutebollockscreations.gattlibrary.core.ftms.FtmsUuids
import com.gmail.absolutebollockscreations.gattlibrary.core.ids.SigUuids

class FtmsAdvertisingPlanTest {

    @Test
    fun `every persona fits one explicit legacy advertising set`() {
        for (profile in FtmsMachineProfile.entries) {
            val plan = FtmsAdvertisingPlan.forProfile(profile)
            val expectedBytes = 3 + (2 + profile.advertisedName.toByteArray().size) + 4 + 7
            assertTrue(
                "$profile must be attributable to this bridge on a scan list",
                profile.advertisedName.startsWith("FB-"),
            )
            assertEquals(expectedBytes, plan.advertisingBytes)
            assertTrue(plan.advertisingBytes <= 31)
            assertEquals(0, plan.scanResponseBytes)
            assertArrayEquals(ftmsServiceData(profile), plan.serviceData)
        }
    }

    @Test
    fun `advertised machine type override changes only the service data`() {
        val honest = FtmsAdvertisingPlan.forProfile(FtmsMachineProfile.INDOOR_ROWER)
        val broken = FtmsAdvertisingPlan.forProfile(
            FtmsMachineProfile.INDOOR_ROWER,
            advertisedMachineType = FtmsMachineProfile.ELLIPTICAL,
        )
        // Flags = available, Fitness Machine Type = Cross Trainer (bit 1), little-endian.
        assertArrayEquals(byteArrayOf(0x01, 0x02, 0x00), broken.serviceData)
        assertArrayEquals(byteArrayOf(0x01, 0x10, 0x00), honest.serviceData)
        assertEquals(honest.localName, broken.localName)
        assertEquals(honest.serviceUuids, broken.serviceUuids)
        assertEquals(honest.advertisingBytes, broken.advertisingBytes)
    }

    @Test
    fun `appearance is deliberately not declared`() {
        for (profile in FtmsMachineProfile.entries) {
            assertTrue(!FtmsAdvertisingPlan.forProfile(profile).appearanceSupported)
        }
    }

    @Test
    fun `native bike mode advertises ftms csc and cycling power within legacy budget`() {
        val plan = FtmsAdvertisingPlan.forProfile(
            FtmsMachineProfile.INDOOR_BIKE,
            includeNativeBikingSensors = true,
        )

        assertEquals(
            listOf(
                FtmsUuids.UUID_FTMS_SERVICE,
                SigUuids.UUID_CSC_SERVICE,
                SigUuids.UUID_CYCLING_POWER_SERVICE,
            ),
            plan.serviceUuids,
        )
        // 3 (Flags) + 9 ("FB-Bike" name) + 8 (three 16-bit UUIDs) + 7 (service data)
        assertEquals(27, plan.advertisingBytes)
        assertTrue(plan.advertisingBytes <= FtmsAdvertisingPlan.MAX_LEGACY_BYTES)
    }

    @Test
    fun `native bike request is ignored for non bike personas`() {
        val plan = FtmsAdvertisingPlan.forProfile(
            FtmsMachineProfile.INDOOR_ROWER,
            includeNativeBikingSensors = true,
        )

        assertEquals(listOf(FtmsUuids.UUID_FTMS_SERVICE), plan.serviceUuids)
    }

    @Test
    fun `the longest fallback persona name fits the legacy budget`() {
        val longest = FtmsMachineProfile.entries.maxBy { it.advertisedName.length }
        val plan = FtmsAdvertisingPlan.forProfile(longest)
        assertEquals(FtmsMachineProfile.ELLIPTICAL, longest)
        assertEquals("FB-Elliptical", longest.advertisedName)
        assertEquals(29, plan.advertisingBytes)
    }

    // ── LIB-18e bridged machine names ─────────────────────────────────────────

    @Test
    fun `name budgets are 15 octets per persona and 11 for the native-sensor bike`() {
        for (profile in FtmsMachineProfile.entries) {
            assertEquals("$profile", 15, FtmsAdvertisingPlan.localNameBudget(profile))
        }
        assertEquals(
            11,
            FtmsAdvertisingPlan.localNameBudget(
                FtmsMachineProfile.INDOOR_BIKE,
                includeNativeBikingSensors = true,
            ),
        )
        // The native-sensor request changes nothing for a non-bike persona.
        assertEquals(
            15,
            FtmsAdvertisingPlan.localNameBudget(
                FtmsMachineProfile.TREADMILL,
                includeNativeBikingSensors = true,
            ),
        )
    }

    @Test
    fun `a bridged machine name follows the FB prefix and fills the budget exactly`() {
        val name = FtmsAdvertisingPlan.bridgeLocalName(
            FtmsMachineProfile.TREADMILL,
            "Life Fitness Integrity Treadmill",
        )
        assertEquals("FB-Life Fitness", name)
        val plan = FtmsAdvertisingPlan.forProfile(FtmsMachineProfile.TREADMILL, localName = name)
        assertEquals(FtmsAdvertisingPlan.MAX_LEGACY_BYTES, plan.advertisingBytes)

        assertEquals(
            "FB-SCHWINN 170/",
            FtmsAdvertisingPlan.bridgeLocalName(FtmsMachineProfile.INDOOR_BIKE, "SCHWINN 170/270"),
        )
        assertEquals(
            "FB-SCHWINN",
            FtmsAdvertisingPlan.bridgeLocalName(
                FtmsMachineProfile.INDOOR_BIKE,
                "SCHWINN 170/270",
                includeNativeBikingSensors = true,
            ),
        )
        assertEquals("FB-M3i #56", FtmsAdvertisingPlan.bridgeLocalName(FtmsMachineProfile.INDOOR_BIKE, "M3i #56"))
    }

    @Test
    fun `every profile stays within the legacy budget for any bridged name`() {
        val names = listOf(
            "x".repeat(64),
            "\u00E9".repeat(40),
            "\uD83D\uDEB4".repeat(20),
            "PM5 430000000 Row",
            "a\u00E9\uD83D\uDEB4b".repeat(8),
        )
        for (profile in FtmsMachineProfile.entries) {
            for (native in listOf(false, true)) {
                for (upstream in names) {
                    val name = FtmsAdvertisingPlan.bridgeLocalName(profile, upstream, native)
                    val plan = FtmsAdvertisingPlan.forProfile(profile, native, localName = name)
                    assertTrue("$profile/$native/$name", plan.advertisingBytes <= FtmsAdvertisingPlan.MAX_LEGACY_BYTES)
                    assertTrue(name.startsWith("FB-"))
                    // Never a lone surrogate: re-encoding must be lossless.
                    assertEquals(name, String(name.toByteArray(Charsets.UTF_8), Charsets.UTF_8))
                }
            }
        }
    }

    @Test
    fun `multi-byte names are cut on a code point boundary by octets`() {
        // 12 octets remain after "FB-": six 2-octet characters fill them exactly, so the 4-octet
        // emoji that follows is dropped whole rather than split.
        val name = FtmsAdvertisingPlan.bridgeLocalName(
            FtmsMachineProfile.TREADMILL,
            "\u00E9\u00E9\u00E9\u00E9\u00E9\u00E9\uD83D\uDEB4",
        )
        assertEquals("FB-\u00E9\u00E9\u00E9\u00E9\u00E9\u00E9", name)
        assertEquals("", FtmsAdvertisingPlan.truncateUtf8("\uD83D\uDEB4", 3))
        assertEquals("\uD83D\uDEB4", FtmsAdvertisingPlan.truncateUtf8("\uD83D\uDEB4", 4))
    }

    @Test
    fun `unknown, blank or unprintable names fall back to the machine type`() {
        for (upstream in listOf(null, "", "   ", "\u0000\u0007")) {
            assertEquals(
                "FB-Treadmill",
                FtmsAdvertisingPlan.bridgeLocalName(FtmsMachineProfile.TREADMILL, upstream),
            )
        }
    }

    @Test
    fun `machine names are cleaned before truncation`() {
        assertEquals(
            "FB-Kickr Core",
            FtmsAdvertisingPlan.bridgeLocalName(FtmsMachineProfile.INDOOR_BIKE, "  Kickr\t\n  Core \u0001 "),
        )
        // Control characters become spaces rather than joining words.
        assertEquals(
            "FB-Kickr Core",
            FtmsAdvertisingPlan.bridgeLocalName(FtmsMachineProfile.INDOOR_BIKE, "Kickr\tCore"),
        )
        // A cut that lands just after a space does not leave the space dangling (8 octets here).
        assertEquals(
            "FB-Echelon",
            FtmsAdvertisingPlan.bridgeLocalName(
                FtmsMachineProfile.INDOOR_BIKE,
                "Echelon Connect",
                includeNativeBikingSensors = true,
            ),
        )
    }

    @Test
    fun `current and legacy prefixes are both recognised as session names`() {
        assertTrue(AdapterNameCoordinator.isKnownSessionName("FB-Treadmill"))
        assertTrue(AdapterNameCoordinator.isKnownSessionName("FB-Life Fitness"))
        assertTrue(AdapterNameCoordinator.isKnownSessionName("FBRG-Treadmill"))
        assertTrue(!AdapterNameCoordinator.isKnownSessionName("Jeff's Pixel"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `oversized plans fail before Android advertising is called`() {
        FtmsAdvertisingPlan(
            localName = "too long",
            serviceData = byteArrayOf(1, 2, 3),
            advertisingBytes = 32,
            scanResponseBytes = 0,
        )
    }
}
