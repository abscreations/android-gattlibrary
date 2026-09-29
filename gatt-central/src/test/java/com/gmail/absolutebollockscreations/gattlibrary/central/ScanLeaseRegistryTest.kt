package com.gmail.absolutebollockscreations.gattlibrary.central

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ownership of the platform scan (plan §5.3, phase 11.5).
 *
 * The behavior worth pinning is the one the old single-owner model got wrong by construction:
 * closing the device picker must not stop a scan that an advertisement-only machine is living on.
 */
class ScanLeaseRegistryTest {

    private val leases = ScanLeaseRegistry()

    @Test
    fun `the first acquire starts the scan and the last release stops it`() {
        assertFalse(leases.isHeld())

        assertTrue("first owner must start the radio", leases.acquire(ScanLeaseOwner.DISCOVERY_UI))
        assertTrue(leases.isHeld())

        assertTrue("last owner may stop it", leases.release(ScanLeaseOwner.DISCOVERY_UI))
        assertFalse(leases.isHeld())
    }

    @Test
    fun `closing the picker does not stop a scan an advertisement machine is living on`() {
        // The defect the whole mechanism exists to prevent. Under the previous single-owner model
        // this sequence stopped the radio, and a selected Keiser simply went silent five minutes
        // into a ride with no error anywhere.
        leases.acquire(ScanLeaseOwner.DISCOVERY_UI)
        leases.acquire(ScanLeaseOwner.ADVERTISEMENT_MACHINE)

        assertFalse(
            "another owner still needs the radio",
            leases.release(ScanLeaseOwner.DISCOVERY_UI),
        )
        assertTrue(leases.isHeld())
        assertTrue(leases.holds(ScanLeaseOwner.ADVERTISEMENT_MACHINE))
        assertFalse(leases.holds(ScanLeaseOwner.DISCOVERY_UI))
        assertFalse(leases.discoveryRequested.value)

        assertTrue(leases.release(ScanLeaseOwner.ADVERTISEMENT_MACHINE))
        assertFalse(leases.isHeld())
    }

    @Test
    fun `an advertisement machine selected first keeps the scan when the picker later closes`() {
        leases.acquire(ScanLeaseOwner.ADVERTISEMENT_MACHINE)

        assertFalse("the picker joins a running scan", leases.acquire(ScanLeaseOwner.DISCOVERY_UI))
        assertFalse(leases.release(ScanLeaseOwner.DISCOVERY_UI))
        assertTrue(leases.isHeld())
    }

    @Test
    fun `acquiring twice and releasing twice cannot unbalance the radio`() {
        // Named owners rather than a counter, precisely so a double release cannot stop a scan
        // somebody else is using, and a double acquire cannot leave the radio on forever.
        assertTrue(leases.acquire(ScanLeaseOwner.DISCOVERY_UI))
        assertFalse("acquiring twice is a no-op", leases.acquire(ScanLeaseOwner.DISCOVERY_UI))

        assertTrue(leases.release(ScanLeaseOwner.DISCOVERY_UI))
        assertFalse(
            "releasing what you do not hold changes nothing",
            leases.release(ScanLeaseOwner.DISCOVERY_UI),
        )
        assertFalse(leases.isHeld())
    }

    @Test
    fun `releaseAll reports whether it stopped anything`() {
        assertFalse("nothing to stop", leases.releaseAll())

        leases.acquire(ScanLeaseOwner.DISCOVERY_UI)
        leases.acquire(ScanLeaseOwner.ADVERTISEMENT_MACHINE)

        assertTrue(leases.releaseAll())
        assertFalse(leases.isHeld())
        assertEquals(emptySet<ScanLeaseOwner>(), leases.owners())
    }

    @Test
    fun `owners are reported in acquisition order for diagnostics`() {
        leases.acquire(ScanLeaseOwner.ADVERTISEMENT_MACHINE)
        leases.acquire(ScanLeaseOwner.DISCOVERY_UI)

        assertEquals(
            listOf(ScanLeaseOwner.ADVERTISEMENT_MACHINE, ScanLeaseOwner.DISCOVERY_UI),
            leases.owners().toList(),
        )
    }
}
