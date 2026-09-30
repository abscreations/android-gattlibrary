package com.gmail.absolutebollockscreations.gattlibrary.server

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which devices the GATT server must refuse to treat as downstream clients.
 *
 * ### The session these are written from
 *
 * On 2026-08-07 a Garmin HRM-Pro that FitBridge was reading as a **central** was
 * admitted as a downstream **client** and paused advertising for 75 of the
 * session's 95 seconds, until an engagement watchdog released it. A watch
 * scanning in that window would have found nothing.
 *
 * The cause was that exclusion was decided from two fixed-in-time inputs - a
 * single upstream machine address, and a snapshot of connected devices taken
 * before the server opened - while the user connects sensors afterwards. Every
 * test below is that shape or a boundary of it.
 */
class PhantomDownstreamAdmissionTest {

    private companion object {
        const val WATCH = "C0:FF:EE:00:00:01"
        const val TREADMILL = "5F:F0:47:B2:2F:35"
        const val STRAP = "F4:55:89:A3:47:42"
        const val FOOTPOD = "3C:E4:B0:68:EB:A2"
    }

    private fun phantom(
        device: String?,
        upstream: String? = null,
        seeded: Set<String> = emptySet(),
        central: Set<String> = emptySet(),
    ) = isPhantomDownstream(device, upstream, seeded, central)

    // ── the regression ───────────────────────────────────────────────────────

    @Test
    fun `a sensor connected after the server opened is not a client`() {
        // The exact failure. The seed was taken when only the treadmill was
        // linked, so the strap appears in neither the seed nor the upstream
        // address - only in the live central-role set.
        assertTrue(
            "the strap FitBridge is itself reading must never occupy a client slot",
            phantom(
                device = STRAP,
                upstream = TREADMILL,
                seeded = setOf(TREADMILL.lowercase()),
                central = setOf(TREADMILL.lowercase(), STRAP.lowercase()),
            ),
        )
    }

    @Test
    fun `a footpod on its own link is excluded too`() {
        // §4a means a second and third central link are normal, not exceptional.
        assertTrue(
            phantom(
                device = FOOTPOD,
                upstream = TREADMILL,
                central = setOf(TREADMILL.lowercase(), STRAP.lowercase(), FOOTPOD.lowercase()),
            ),
        )
    }

    // ── what must still be admitted ──────────────────────────────────────────

    @Test
    fun `a watch is admitted while three sensors are connected`() {
        // The guard must exclude our own links without excluding everything: the
        // whole point of the peripheral is to serve this device.
        assertFalse(
            "a downstream client must still get through",
            phantom(
                device = WATCH,
                upstream = TREADMILL,
                seeded = setOf(TREADMILL.lowercase()),
                central = setOf(TREADMILL.lowercase(), STRAP.lowercase(), FOOTPOD.lowercase()),
            ),
        )
    }

    @Test
    fun `a device that disconnects upstream becomes admissible`() {
        // Live means live in both directions. Once FitBridge releases a central
        // link, that hardware is free to come back as a client - a phone running
        // TMIW, say, which can be either.
        assertFalse(
            phantom(device = STRAP, upstream = TREADMILL, central = setOf(TREADMILL.lowercase())),
        )
    }

    // ── the inputs that are kept ─────────────────────────────────────────────

    @Test
    fun `the pre-open snapshot still covers a link another app owns`() {
        // FitBridge's registry knows nothing about a device some other app on the
        // phone connected before we opened, so the seed is not redundant.
        assertTrue(
            phantom(device = "AA:BB:CC:DD:EE:FF", seeded = setOf("aa:bb:cc:dd:ee:ff")),
        )
    }

    @Test
    fun `the upstream address alone still excludes`() {
        // Set explicitly by the controller and does not wait on the registry.
        assertTrue(phantom(device = TREADMILL, upstream = TREADMILL))
    }

    // ── boundaries ───────────────────────────────────────────────────────────

    @Test
    fun `address comparison ignores case from every source`() {
        assertTrue(phantom(device = STRAP.lowercase(), upstream = STRAP.uppercase()))
        assertTrue(phantom(device = STRAP.uppercase(), seeded = setOf(STRAP.lowercase())))
        assertTrue(phantom(device = STRAP.lowercase(), central = setOf(STRAP.uppercase())))
    }

    @Test
    fun `a missing or blank address is admitted rather than silently dropped`() {
        // Excluding on absent identity would refuse a real client for a platform
        // quirk. Admission is recoverable - the engagement watchdog releases a
        // link that never engages - where a wrongly excluded watch just never works.
        assertFalse(phantom(device = null, central = setOf(STRAP.lowercase())))
        assertFalse(phantom(device = "", central = setOf(STRAP.lowercase())))
        assertFalse(phantom(device = "   ", central = setOf(STRAP.lowercase())))
    }

    @Test
    fun `no exclusions at all admits everything`() {
        assertFalse(phantom(device = WATCH))
    }
}
