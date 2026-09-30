package com.gmail.absolutebollockscreations.gattlibrary.core.ids

import java.util.UUID

/**
 * Schwinn 170/270/570 (x70) proprietary GATT identifiers. Provenance (capture inventory, TMIW source) is in each member comment.
 *
 * Moved from central `BluetoothIds` (LIB-19b); names unchanged.
 */
public object SchwinnX70Uuids {
    // ─────────────────────────────────────────────────────────────
    // Schwinn x70 — 170 / 270 / 570 (proprietary, plan §7)
    // ─────────────────────────────────────────────────────────────
    /**
     * Written out in full for the same reason as the Precor and Life Fitness sets, and here the
     * reason has teeth: **TMIW stores these as four-character aliases** (`8980`, `82a0`, `b3c0`,
     * `f580`) and matches on characters 5–8 of whatever UUID it is handed. §8.4 exists because of
     * exactly this. Note that the five identifiers below do **not** share a base — `3bf58980` sits
     * on `-3a2f-11e6-9011-`, while the other four sit on `-9803-11e3-` with three *different*
     * third groups. No helper could generate them.
     *
     * Source: QZ issue #1365 nRF Connect GATT screenshots and HCI, and TMIW issue #240's 270 and
     * 570u nRF Connect logs, archived at `docs/evidence/phase-7/`. Every string is transcribed
     * from a GATT inventory; none is constructed from an alias.
     *
     * ## Two consoles, not one — and this is the whole shape of Phase 7
     *
     * The captures agree. A **170** emits paired 20-byte notifications on [UUID_X70_SUPPLEMENTAL]
     * and 14-byte notifications on [UUID_X70_SIBLING], in equal counts, and **no 17-byte stream at
     * all**. A **270** and a submitter-labelled **570u** emit 17-byte notifications on
     * [UUID_X70_MEASUREMENT]. TMIW implements only the 17-byte path and QZ only the 170 pair;
     * neither project is wrong, and neither alone covers the family.
     *
     * Dispatch by exact characteristic and accepted length. Never by length alone — that is QZ's
     * one structural defect here (`Q_UNUSED(characteristic)`), and it is why a 20-byte frame from
     * any source would be read as this family's supplemental frame.
     */
    public val UUID_X70_SERVICE: UUID =
        UUID.fromString("3bf58980-3a2f-11e6-9011-0002a5d5c51b")

    /** Notify. The 17-byte main stream — 270 and 570 consoles only. */
    public val UUID_X70_MEASUREMENT: UUID =
        UUID.fromString("5c7d82a0-9803-11e3-8a6c-0002a5d5c51b")

    /** Write. Initialization; starts the notification streams. See V17. */
    public val UUID_X70_CONTROL: UUID =
        UUID.fromString("1717b3c0-9803-11e3-90e1-0002a5d5c51b")

    /** Notify. 20-byte frames carrying heart rate at 16 and resistance at 18 (V16). */
    public val UUID_X70_SUPPLEMENTAL: UUID =
        UUID.fromString("6be8f580-9803-11e3-ab03-0002a5d5c51b")

    /**
     * Notify. 14-byte frames; speed and power on a 170 (V15a, published under D72).
     *
     * TMIW has no parser for this characteristic and does not name it. It is here because two
     * independent physical captures observe it carrying 698 notifications paired one-for-one with
     * the supplemental stream.
     */
    public val UUID_X70_SIBLING: UUID =
        UUID.fromString("a46a4a80-9803-11e3-8f3c-0002a5d5c51b")

    /**
     * Notify. A 4-octet `01 1f 00 00`, and **the first notification the console sends on the
     * link**, ahead of every measurement frame.
     *
     * Registered on 2026-08-18 from QZ debug logs, which record each notification with its
     * characteristic. The payload is byte-identical on two physically distinct consoles and appears
     * exactly once per session.
     *
     * **Deliberately not subscribed to.** [planSchwinnX70Discovery] arms the three measurement
     * streams and this is not one: nothing decodes `01 1f 00 00`, so a subscription would deliver a
     * frame no consumer can use. That is the shape of defect §11 obligation 0 exists to catch, and
     * adding it here to look thorough would be committing it deliberately. The constant exists so
     * the inventory is complete and so a decoder arriving later has a name to attach to.
     */
    public val UUID_X70_AUX_STATUS: UUID =
        UUID.fromString("35ddd0a0-9803-11e3-9a8b-0002a5d5c51b")

    /**
     * Notify. **QZ subscribes to both of these and neither ever notified** across 11,373 captured
     * notifications on one console and 8,552 on another.
     *
     * Registered for the same reason as [UUID_X70_AUX_STATUS] and subscribed to for none: an empty
     * stream is not evidence of a stream. Silence in three sessions is also not proof they are
     * always silent, which is why they are named rather than dismissed.
     */
    public val UUID_X70_UNUSED_B8: UUID =
        UUID.fromString("b8066ec0-9803-11e3-8346-0002a5d5c51b")

    /** Notify. See [UUID_X70_UNUSED_B8] — subscribed by QZ, never observed notifying. */
    public val UUID_X70_UNUSED_D5: UUID =
        UUID.fromString("d57cda20-9803-11e3-8426-0002a5d5c51b")

    /**
     * **Write without response.** Recorded as a notify characteristic until 2026-08-19 and it is
     * not one.
     *
     * A 570u GATT inventory — the first full characteristic table this family has produced, and
     * byte-identical across two physically distinct consoles — lists this as
     * `writeWithoutResponse` only. The earlier note that nothing had ever subscribed to it was
     * true and misleading: nothing can. **The family therefore exposes three writable
     * characteristics, not one**, which is a wider control surface than §7 recorded. FitBridge
     * writes none of them but the initialization command.
     */
    public val UUID_X70_WRITE_EC86: UUID =
        UUID.fromString("ec865fc0-9803-11e3-8bf6-0002a5d5c51b")

    /**
     * Read only. Five characteristics the family exposes that no project reads and nothing decodes.
     *
     * Registered 2026-08-19 from the 570u inventory. Named by role and alias rather than by
     * function, because their function is unknown and a speculative name would outlive the
     * speculation.
     */
    public val UUID_X70_READ_E3F9: UUID =
        UUID.fromString("e3f9af20-2674-11e3-879e-0002a5d5c51b")

    /** **Read and write.** The family's second writable characteristic. See [UUID_X70_WRITE_EC86]. */
    public val UUID_X70_RW_5EC4: UUID =
        UUID.fromString("5ec4e520-9804-11e3-b4b9-0002a5d5c51b")

    /** Read only. See [UUID_X70_READ_E3F9]. */
    public val UUID_X70_READ_4E34: UUID =
        UUID.fromString("4e349c00-999e-11e3-b341-0002a5d5c51b")

    /**
     * Read only. Previously seen exactly once in a QZ log and recorded as unresolved; the 570u
     * inventory shows it is an ordinary readable characteristic of the service.
     */
    public val UUID_X70_READ_4ED1: UUID =
        UUID.fromString("4ed124e0-9803-11e3-b14c-0002a5d5c51b")

    /** Read only. See [UUID_X70_READ_E3F9]. */
    public val UUID_X70_READ_7241: UUID =
        UUID.fromString("7241b880-a560-11e3-9f31-0002a5d5c51b")

    /**
     * Every characteristic the family exposes under [UUID_X70_SERVICE] — **thirteen, not eight**.
     *
     * Corrected 2026-08-19 from a complete 570u service discovery, identical on two physically
     * distinct consoles. FitBridge decodes three of these and writes one; the other nine are named
     * so the inventory is honest about the gap rather than silent about it.
     */
    public val X70_OBSERVED_CHARACTERISTICS: List<UUID> = listOf(
        UUID_X70_CONTROL,
        UUID_X70_MEASUREMENT,
        UUID_X70_SUPPLEMENTAL,
        UUID_X70_SIBLING,
        UUID_X70_AUX_STATUS,
        UUID_X70_UNUSED_B8,
        UUID_X70_UNUSED_D5,
        UUID_X70_WRITE_EC86,
        UUID_X70_READ_E3F9,
        UUID_X70_RW_5EC4,
        UUID_X70_READ_4E34,
        UUID_X70_READ_4ED1,
        UUID_X70_READ_7241,
    )
}
