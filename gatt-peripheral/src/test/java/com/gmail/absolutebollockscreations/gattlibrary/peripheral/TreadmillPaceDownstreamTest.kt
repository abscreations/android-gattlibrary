package com.gmail.absolutebollockscreations.gattlibrary.peripheral

import com.gmail.absolutebollockscreations.gattlibrary.central.BtEngineState
import com.gmail.absolutebollockscreations.gattlibrary.central.BtServiceProfiles.FtmsMachineProfile
import com.gmail.absolutebollockscreations.gattlibrary.central.ConnectionId
import com.gmail.absolutebollockscreations.gattlibrary.central.TreadmillNativePaceSnapshot
import com.gmail.absolutebollockscreations.gattlibrary.central.TreadmillNativePaceStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Plan §11.8 coverage for downstream treadmill pace, and §11.4 lifecycle coverage for the store
 * that feeds it.
 *
 * Both were shipped without tests. That is worth stating plainly: the pace bit is the
 * honesty-critical part of the change — it is the claim FitBridge makes to a client about what
 * it can supply — and §11.8 asks for a test asserting *exactly* which capability bits are set.
 *
 * The design being pinned here: FitBridge never synthesises treadmill pace, because the wire
 * field resolves to one raw step per 6 km/h and a value derived from fused speed would
 * contradict the Instantaneous Speed field in the same packet. It relays the machine's own
 * octet, and it claims the capability only when the machine it is bridging claims it.
 */
class TreadmillPaceDownstreamTest {

    private val paceBit = 1 shl FtmsSpec.Feature.PACE

    private fun u16(b: ByteArray, o: Int) = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)
    private fun u32(b: ByteArray, o: Int) = u16(b, o) or (u16(b, o + 2) shl 16)

    // ── The Feature bit is mirrored, not asserted ────────────────────────────────

    @Test
    fun `the treadmill claims pace only when the bridged machine claims it`() {
        val targets = TargetCapabilities.NONE

        val withoutPace = buildFtmsFeaturePayload(FtmsMachineProfile.TREADMILL, targets, false)
        assertFalse(
            "claiming pace for a machine that never reports it advertises a measurement " +
                "FitBridge cannot supply",
            u32(withoutPace, 0) and paceBit != 0,
        )

        val withPace = buildFtmsFeaturePayload(FtmsMachineProfile.TREADMILL, targets, true)
        assertTrue(
            "the machine declares pace, so the bridge may too",
            u32(withPace, 0) and paceBit != 0,
        )
    }

    @Test
    fun `the default is unclaimed, which is the safe direction to fail`() {
        // The upstream 0x2ACC read completes during machine setup and can land after the GATT
        // server is already open. A client that reads Feature first must see pace unclaimed:
        // unclaimed-and-unsent is invisible, claimed-and-never-delivered is not.
        val defaulted = buildFtmsFeaturePayload(FtmsMachineProfile.TREADMILL, TargetCapabilities.NONE)
        assertFalse(
            "pace must default to unclaimed until the upstream Feature read says otherwise",
            u32(defaulted, 0) and paceBit != 0,
        )
    }

    @Test
    fun `no other persona gains a pace claim from an upstream treadmill bit`() {
        // Only 0x2ACD carries the field. A rower's pace is a different field on a different
        // characteristic and is governed by its own capability.
        for (profile in FtmsMachineProfile.values()) {
            if (profile == FtmsMachineProfile.TREADMILL) continue
            val payload = buildFtmsFeaturePayload(profile, TargetCapabilities.NONE, true)
            assertEquals(
                "$profile must be unaffected by the treadmill pace bit",
                u32(buildFtmsFeaturePayload(profile, TargetCapabilities.NONE, false), 0),
                u32(payload, 0),
            )
        }
    }

    @Test
    fun `target-setting capability is untouched by the pace claim`() {
        val targets = TargetCapabilities.NONE
        val withPace = buildFtmsFeaturePayload(FtmsMachineProfile.TREADMILL, targets, true)
        // Pace is a measurement capability; the upper word must not move.
        assertEquals(0, u32(withPace, 4))
        assertEquals(8, withPace.size)
    }

    // ── Engine state clears the claim ────────────────────────────────────────────

    @Test
    fun `resetting FTMS capabilities clears the pace claim`() {
        val state = BtEngineState()
        state.supportsPaceMeasurement = true

        state.resetFtmsCapabilities()

        assertFalse(
            "a stale claim would advertise pace for a machine that never declared it",
            state.supportsPaceMeasurement,
        )
    }

    // ── The store ────────────────────────────────────────────────────────────────

    private fun conn(generation: Long) = ConnectionId("AA:BB:CC:DD:EE:F0", generation)

    @Test
    fun `the store keeps raw octets and merges partial updates`() {
        val store = TreadmillNativePaceStore()
        val c = conn(1L)

        store.publish(c, instantaneousRaw = 2, averageRaw = null)
        assertEquals(2, store.snapshot()?.instantaneousRaw)
        assertNull(store.snapshot()?.averageRaw)

        // A later frame carrying only the average must not erase the instantaneous value:
        // the two share a characteristic but not a flag, so either can arrive alone.
        store.publish(c, instantaneousRaw = null, averageRaw = 3)
        assertEquals(2, store.snapshot()?.instantaneousRaw)
        assertEquals(3, store.snapshot()?.averageRaw)

        store.publish(c, instantaneousRaw = 4, averageRaw = null)
        assertEquals(4, store.snapshot()?.instantaneousRaw)
        assertEquals(3, store.snapshot()?.averageRaw)
    }

    @Test
    fun `a publish carrying nothing is ignored`() {
        val store = TreadmillNativePaceStore()
        store.publish(conn(1L), instantaneousRaw = null, averageRaw = null)
        assertNull(store.snapshot())
    }

    @Test
    fun `the newest link generation wins`() {
        val store = TreadmillNativePaceStore()
        store.publish(conn(1L), 2, null)
        store.publish(conn(7L), 5, null)

        assertEquals(
            "a reconnect opens a new generation and its readings supersede the old link's",
            5,
            store.snapshot()?.instantaneousRaw,
        )
    }

    @Test
    fun `removing a connection drops its pace, and clear empties the store`() {
        val store = TreadmillNativePaceStore()
        store.publish(conn(1L), 2, 3)
        store.remove(conn(1L))
        assertNull("a disconnected machine must not keep supplying pace", store.snapshot())

        store.publish(conn(2L), 4, null)
        store.clear()
        assertNull(store.snapshot())
    }

    // ── The encoder ──────────────────────────────────────────────────────────────

    @Test
    fun `the encoder emits the pace octets verbatim, and only when they exist`() {
        val base = FtmsMetricSnapshot(speedKph = 10.0)

        val without = FtmsDataEncoders.encode(FtmsMachineProfile.TREADMILL, base, base.speedKph)
            .single()
        val flagsWithout = u16(without, 0)
        assertFalse("no native pace, no claim", flagsWithout and (1 shl 5) != 0)
        assertFalse(flagsWithout and (1 shl 6) != 0)

        val withPace = base.copy(
            treadmillNativePace = TreadmillNativePaceSnapshot(instantaneousRaw = 2, averageRaw = 3),
        )
        val encoded = FtmsDataEncoders.encode(
            FtmsMachineProfile.TREADMILL,
            withPace,
            withPace.speedKph,
        ).single()
        val flags = u16(encoded, 0)

        assertTrue("instantaneous pace present", flags and (1 shl 5) != 0)
        assertTrue("average pace present", flags and (1 shl 6) != 0)

        // Speed is mandatory and occupies octets 2..3; the two pace octets follow immediately
        // because no lower optional bit is set. Verbatim: the same quantisation out as in.
        assertEquals(2, encoded[4].toInt() and 0xFF)
        assertEquals(3, encoded[5].toInt() and 0xFF)
    }
}
