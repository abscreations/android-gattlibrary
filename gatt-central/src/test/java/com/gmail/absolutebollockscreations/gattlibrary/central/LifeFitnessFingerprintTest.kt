package com.gmail.absolutebollockscreations.gattlibrary.central

import com.gmail.absolutebollockscreations.gattlibrary.central.LifeFitnessFingerprint.CharacteristicInventory
import com.gmail.absolutebollockscreations.gattlibrary.central.LifeFitnessFingerprint.Decline
import com.gmail.absolutebollockscreations.gattlibrary.central.LifeFitnessFingerprint.Result
import com.gmail.absolutebollockscreations.gattlibrary.central.LifeFitnessFingerprint.ServiceInventory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/**
 * Phase 6 work unit 6.1 — plan §6, §11 obligations 0 and 1, D52 and D54.
 *
 * The fingerprint is the only thing standing between "this machine advertises FTMS" and
 * "FitBridge writes a vendor handshake into it", so every decline path is tested by name.
 */
class LifeFitnessFingerprintTest {

    private fun ch(
        uuid: UUID,
        writable: Boolean = true,
        notifyOrIndicate: Boolean = false,
        hasCccd: Boolean = false,
    ) = CharacteristicInventory(uuid, writable, notifyOrIndicate, hasCccd)

    private fun status(notifyOrIndicate: Boolean = true, hasCccd: Boolean = true) =
        ch(BluetoothIds.UUID_LF_STATUS, writable = false, notifyOrIndicate = notifyOrIndicate, hasCccd = hasCccd)

    private fun controlService(
        uuid: UUID = BluetoothIds.UUID_LF_CONTROL_SERVICE,
        extra: List<CharacteristicInventory> = listOf(status()),
        writable: Boolean = true,
    ) = ServiceInventory(
        uuid,
        listOf(
            ch(BluetoothIds.UUID_LF_CONTROL_1, writable = writable),
            ch(BluetoothIds.UUID_LF_CONTROL_2, writable = writable),
        ) + extra,
    )

    private fun userService(
        uuid: UUID = BluetoothIds.UUID_LF_USER_SERVICE,
        writable: Boolean = true,
    ) = ServiceInventory(
        uuid,
        listOf(
            ch(BluetoothIds.UUID_LF_VENDOR_TOKEN_1, writable = writable),
            ch(BluetoothIds.UUID_LF_VENDOR_TOKEN_2, writable = writable),
        ),
    )

    /** Standard services a real inventory also carries; none of them may confuse the rule. */
    private fun noise() = listOf(
        ServiceInventory(
            BluetoothIds.UUID_FTMS_SERVICE,
            listOf(ch(BluetoothIds.UUID_TREADMILL_DATA, writable = false, notifyOrIndicate = true, hasCccd = true)),
        ),
        ServiceInventory(UUID.fromString("0000fe13-0000-1000-8000-00805f9b34fb"), emptyList()),
        ServiceInventory(UUID.fromString("0000fd00-ebae-4526-9511-8357c35d7be2"), emptyList()),
    )

    private fun matchOf(result: Result): LifeFitnessFingerprint.Resolved {
        assertTrue("expected a match, got $result", result is Result.Match)
        return (result as Result.Match).resolved
    }

    private fun declineOf(result: Result): Decline {
        assertTrue("expected a decline, got $result", result is Result.NoMatch)
        return (result as Result.NoMatch).reason
    }

    // ── The captured inventory ───────────────────────────────────────────────────

    @Test
    fun `the captured inventory resolves and is recognised as canonical`() {
        // The shape recorded by QZ issue #1018 and repeated across TMIW issue #462's four
        // personas, with the standard services a real device also exposes.
        val resolved = matchOf(
            LifeFitnessFingerprint.identify(noise() + controlService() + userService()),
        )

        assertEquals(BluetoothIds.UUID_LF_CONTROL_SERVICE, resolved.controlService)
        assertEquals(BluetoothIds.UUID_LF_USER_SERVICE, resolved.userService)
        assertEquals(BluetoothIds.UUID_LF_STATUS, resolved.statusCharacteristic)
        assertTrue(resolved.canonicalParents)
    }

    @Test
    fun `a variant with different parents still resolves, and is not called canonical`() {
        // D52's whole purpose. The parents are known from capture but are not required, so a
        // firmware that moves the characteristics elsewhere completes setup rather than
        // silently failing — while the report still distinguishes it from the captured shape.
        val variantControl = UUID.fromString("11111111-2222-3333-4444-555555555555")
        val variantUser = UUID.fromString("66666666-7777-8888-9999-aaaaaaaaaaaa")

        val resolved = matchOf(
            LifeFitnessFingerprint.identify(
                noise() +
                    controlService(uuid = variantControl) +
                    userService(uuid = variantUser),
            ),
        )

        assertEquals(variantControl, resolved.controlService)
        assertEquals(variantUser, resolved.userService)
        assertFalse("parents differ from the captured pair", resolved.canonicalParents)
        assertEquals("status is scoped to the resolved parent, not the captured one",
            BluetoothIds.UUID_LF_STATUS, resolved.statusCharacteristic)
    }

    @Test
    fun `isSetupSurface answers the adapter layer's question and nothing more`() {
        // `candidatesFor` needs a boolean for `requiresSetup`, not a resolved pair. Handing it
        // the pair would invite callers to keep it and let it drift from the link it came from,
        // so the convenience returns only what that decision needs.
        assertTrue(
            LifeFitnessFingerprint.isSetupSurface(noise() + controlService() + userService()),
        )
        assertFalse("an ordinary FTMS machine requires no setup", LifeFitnessFingerprint.isSetupSurface(noise()))
        assertFalse(
            "a half-present surface must not arm a handshake that cannot complete",
            LifeFitnessFingerprint.isSetupSurface(noise() + controlService()),
        )
    }

    // ── Decline paths, each by name ──────────────────────────────────────────────

    @Test
    fun `an ordinary FTMS machine is not Life Fitness`() {
        assertEquals(
            Decline.NOT_LIFE_FITNESS,
            declineOf(LifeFitnessFingerprint.identify(noise())),
        )
        assertEquals(
            Decline.NOT_LIFE_FITNESS,
            declineOf(LifeFitnessFingerprint.identify(emptyList())),
        )
    }

    @Test
    fun `an incomplete pair declines as incomplete, not as a different family`() {
        val halfControl = ServiceInventory(
            BluetoothIds.UUID_LF_CONTROL_SERVICE,
            listOf(ch(BluetoothIds.UUID_LF_CONTROL_1)),
        )

        assertEquals(
            Decline.INCOMPLETE_PAIR,
            declineOf(LifeFitnessFingerprint.identify(noise() + halfControl + userService())),
        )
    }

    @Test
    fun `a control surface with no user surface declines rather than half-matching`() {
        // Reporting a partial match would only defer the failure to the first profile write.
        assertEquals(
            Decline.INCOMPLETE_PAIR,
            declineOf(LifeFitnessFingerprint.identify(noise() + controlService())),
        )
    }

    @Test
    fun `a split parent declines even though both characteristics exist`() {
        val first = ServiceInventory(
            UUID.fromString("11111111-0000-0000-0000-000000000000"),
            listOf(ch(BluetoothIds.UUID_LF_CONTROL_1)),
        )
        val second = ServiceInventory(
            UUID.fromString("22222222-0000-0000-0000-000000000000"),
            listOf(ch(BluetoothIds.UUID_LF_CONTROL_2)),
        )

        assertEquals(
            Decline.SPLIT_PARENT,
            declineOf(LifeFitnessFingerprint.identify(noise() + first + second + userService())),
        )
    }

    @Test
    fun `a co-located pair that cannot be written declines on properties`() {
        assertEquals(
            Decline.PROPERTY_INCOMPATIBLE,
            declineOf(
                LifeFitnessFingerprint.identify(
                    noise() + controlService(writable = false) + userService(),
                ),
            ),
        )
        assertEquals(
            Decline.PROPERTY_INCOMPATIBLE,
            declineOf(
                LifeFitnessFingerprint.identify(
                    noise() + controlService() + userService(writable = false),
                ),
            ),
        )
    }

    @Test
    fun `split parents are reported as split, not as a property problem`() {
        // Both declines are technically true of this inventory. The reason that names the first
        // real problem is the one worth reporting; a property complaint would send a reader
        // looking at characteristic flags for a layout fault.
        val first = ServiceInventory(
            UUID.fromString("11111111-0000-0000-0000-000000000000"),
            listOf(ch(BluetoothIds.UUID_LF_CONTROL_1, writable = false)),
        )
        val second = ServiceInventory(
            UUID.fromString("22222222-0000-0000-0000-000000000000"),
            listOf(ch(BluetoothIds.UUID_LF_CONTROL_2, writable = false)),
        )

        assertEquals(
            Decline.SPLIT_PARENT,
            declineOf(LifeFitnessFingerprint.identify(listOf(first, second, userService()))),
        )
    }

    // ── §8.4: no alias matching ──────────────────────────────────────────────────

    @Test
    fun `characteristics sharing only the alias octets do not match`() {
        // TMIW compares characters 5-8. These UUIDs carry 58dc, 69e2, 4cf2 and e85e in that
        // position on an unrelated base, so an alias matcher accepts them and a full-UUID
        // matcher does not. This family is §8.4's own example: ce78e85e-9cb6-… shares no base
        // with the 5da5____-9cb2-… group, so no consistent alias rule could be right anyway.
        val aliasControl = ServiceInventory(
            UUID.fromString("00004b90-0000-1000-8000-00805f9b34fb"),
            listOf(
                ch(UUID.fromString("000058dc-0000-1000-8000-00805f9b34fb")),
                ch(UUID.fromString("000069e2-0000-1000-8000-00805f9b34fb")),
            ),
        )
        val aliasUser = ServiceInventory(
            UUID.fromString("00004b91-0000-1000-8000-00805f9b34fb"),
            listOf(
                ch(UUID.fromString("00004cf2-0000-1000-8000-00805f9b34fb")),
                ch(UUID.fromString("0000e85e-0000-1000-8000-00805f9b34fb")),
            ),
        )

        assertEquals(
            Decline.NOT_LIFE_FITNESS,
            declineOf(LifeFitnessFingerprint.identify(noise() + aliasControl + aliasUser)),
        )
    }

    @Test
    fun `the vendor token UUIDs really do sit on different bases`() {
        // Pins the fact that motivates the rule, so a future contributor tempted to build a
        // lifeFitnessUuid(alias) helper sees immediately that no single base exists.
        fun base(u: UUID) = u.toString().substringAfter('-')

        assertEquals(base(BluetoothIds.UUID_LF_CONTROL_1), base(BluetoothIds.UUID_LF_CONTROL_2))
        assertNotEquals(
            "ce78e85e sits on a different base from the 5da5 group",
            base(BluetoothIds.UUID_LF_VENDOR_TOKEN_1),
            base(BluetoothIds.UUID_LF_VENDOR_TOKEN_2),
        )
        assertNotEquals(
            "the parents are a third base again",
            base(BluetoothIds.UUID_LF_CONTROL_SERVICE),
            base(BluetoothIds.UUID_LF_CONTROL_1),
        )
    }

    // ── Status: resolved, optional, never a gate ─────────────────────────────────

    @Test
    fun `a missing status characteristic does not fail the fingerprint`() {
        val resolved = matchOf(
            LifeFitnessFingerprint.identify(
                noise() + controlService(extra = emptyList()) + userService(),
            ),
        )

        assertNull(resolved.statusCharacteristic)
        assertEquals(BluetoothIds.UUID_LF_CONTROL_SERVICE, resolved.controlService)
    }

    @Test
    fun `status without notify or a CCCD is not usable and is not claimed`() {
        for (candidate in listOf(
            status(notifyOrIndicate = false, hasCccd = true),
            status(notifyOrIndicate = true, hasCccd = false),
        )) {
            val resolved = matchOf(
                LifeFitnessFingerprint.identify(
                    noise() + controlService(extra = listOf(candidate)) + userService(),
                ),
            )
            assertNull("an unsubscribable status must not be claimed", resolved.statusCharacteristic)
        }
    }

    @Test
    fun `status under a foreign parent is not this machine's status source`() {
        val foreign = ServiceInventory(
            UUID.fromString("33333333-0000-0000-0000-000000000000"),
            listOf(status()),
        )

        val resolved = matchOf(
            LifeFitnessFingerprint.identify(
                noise() + controlService(extra = emptyList()) + userService() + foreign,
            ),
        )

        assertNull(resolved.statusCharacteristic)
    }
}
