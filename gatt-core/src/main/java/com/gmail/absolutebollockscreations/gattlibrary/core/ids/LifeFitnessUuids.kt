package com.gmail.absolutebollockscreations.gattlibrary.core.ids

import java.util.UUID

/**
 * Life Fitness proprietary setup, discovery and B90 characteristic identifiers. Provenance is in each member comment.
 *
 * Moved from central `BluetoothIds` (LIB-19b); names unchanged.
 */
public object LifeFitnessUuids {
    public val UUID_LF_SECONDARY_SERVICE: UUID = shortUuid(0xFE13)

    // ─────────────────────────────────────────────────────────────
    // Life Fitness proprietary setup (plan §6, D54)
    // ─────────────────────────────────────────────────────────────
    /**
     * Written out in full for the same reason as the Precor pair: these are seven extracted
     * facts, not an enumerable namespace, and no helper should be able to manufacture an
     * eighth. Note that they do **not** share one base — `ce78e85e` differs from the
     * `5da5____-9cb2-11e5-…` family in both its first and second groups, and the two parent
     * services are a third base again. §8.4 exists because someone assumed otherwise.
     *
     * Source: QZ issue #1018 (nRF Connect inventory plus two physical HCI captures) and TMIW
     * issue #462 (official-app HCI across four personas), archived at
     * `docs/evidence/phase-6/2022-11-11-life-fitness-qz-gatt-and-setup-evidence.md` and
     * `docs/evidence/phase-6/2024-05-15-life-fitness-tmiw-multi-persona-hci.md`.
     *
     * **These are the canonical captured parents, not a match requirement.** D52's runtime
     * same-parent characteristic rule remains the admission test so a firmware variant with
     * different parent UUIDs can still complete setup; these constants are preferred positive
     * evidence when the discovered inventory happens to match. See `LifeFitnessFingerprint`.
     */
    public val UUID_LF_CONTROL_SERVICE: UUID =
        UUID.fromString("fb694b90-f49e-4597-8306-171bba78f846")
    public val UUID_LF_USER_SERVICE: UUID =
        UUID.fromString("fb694b91-f49e-4597-8306-171bba78f846")

    /** Notify/indicate status. Observed `07` then `04 01`; never a setup-completion gate. */
    public val UUID_LF_STATUS: UUID =
        UUID.fromString("5da551de-9cb2-11e5-8994-feff819cdc9f")

    /** Write: `[0x01]` opens the sequence, `[0x02]` closes it. */
    public val UUID_LF_CONTROL_1: UUID =
        UUID.fromString("5da558dc-9cb2-11e5-8994-feff819cdc9f")

    /** Write: the seven-octet `00 00 00 01 b8 5b 5d` magic. */
    public val UUID_LF_CONTROL_2: UUID =
        UUID.fromString("5da569e2-9cb2-11e5-8994-feff819cdc9f")

    /** Write: vendor token 1, a 32-byte ASCII hex string. Sensitive; redaction-registered. */
    public val UUID_LF_VENDOR_TOKEN_1: UUID =
        UUID.fromString("5da54cf2-9cb2-11e5-8994-feff819cdc9f")

    /** Write: vendor token 2, `[0x00]`. Sensitive; redaction-registered. */
    public val UUID_LF_VENDOR_TOKEN_2: UUID =
        UUID.fromString("ce78e85e-9cb6-11e5-8994-feff819cdc9f")

    /** Capture-only discovery surface shared by all four issue #462 personas. */
    public val UUID_LF_FD00_SERVICE: UUID =
        UUID.fromString("0000fd00-ebae-4526-9511-8357c35d7be2")
    public val UUID_LF_FD01: UUID = UUID.fromString("0000fd01-ebae-4526-9511-8357c35d7be2")
    public val UUID_LF_FD02: UUID = UUID.fromString("0000fd02-ebae-4526-9511-8357c35d7be2")
    public val UUID_LF_FD05: UUID = UUID.fromString("0000fd05-ebae-4526-9511-8357c35d7be2")

    public val UUID_LF_B90_READ_5AA9: UUID = UUID.fromString("5da55aa9-9cb2-11e5-8994-feff819cdc9f")
    public val UUID_LF_B90_RW_NOTIFY_52A6: UUID = UUID.fromString("5da552a6-9cb2-11e5-8994-feff819cdc9f")
    public val UUID_LF_B90_NOTIFY_5AA8: UUID = UUID.fromString("5da55aa8-9cb2-11e5-8994-feff819cdc9f")
    public val UUID_LF_B90_READ_ECA0: UUID = UUID.fromString("ce78eca0-9cb6-11e5-8994-feff819cdc9f")
    public val UUID_LF_B90_READ_EF5C: UUID = UUID.fromString("ce78ef5c-9cb6-11e5-8994-feff819cdc9f")
    public val UUID_LF_B90_WRITE_F4E8: UUID = UUID.fromString("ce78f4e8-9cb6-11e5-8994-feff819cdc9f")
    public val UUID_LF_B90_WRITE_F164: UUID = UUID.fromString("ce78f164-9cb6-11e5-8994-feff819cdc9f")
    public val UUID_LF_B90_WRITE_F330: UUID = UUID.fromString("ce78f330-9cb6-11e5-8994-feff819cdc9f")
    public val UUID_LF_B90_WRITE_FB00: UUID = UUID.fromString("ce78fb00-9cb6-11e5-8994-feff819cdc9f")
    public val UUID_LF_B90_WRITE_FD94: UUID = UUID.fromString("ce78fd94-9cb6-11e5-8994-feff819cdc9f")
    public val UUID_LF_B90_WRITE_6AB6: UUID = UUID.fromString("5da56ab6-9cb2-11e5-8994-feff819cdc9f")
}
