package com.gmail.absolutebollockscreations.gattlibrary.core.vendor

import com.gmail.absolutebollockscreations.gattlibrary.core.GattLibraryInternalApi

/**
 * Schwinn 170/270/570 (x70) framing (frame lengths, invariant prefix, stream marker), the initialization command and the resistance-to-power coefficient table.
 *
 * Provenance: the values below were inherited from external projects named in the member comments and are used as published there; those projects report working hardware behavior, and FitBridge treats them as correct on that feedback. FitBridge has not independently verified a value unless its comment says so.
 */
@GattLibraryInternalApi
public object SchwinnX70Spec {
    /** 270 / 570 main stream. Exact length; a 16- or 18-octet frame is not one of these. */
    public const val MAIN_FRAME_LENGTH: Int = 17

    /** 170 supplemental stream: heart rate and resistance (V16). */
    public const val SUPPLEMENTAL_FRAME_LENGTH: Int = 20

    /** 170 sibling stream: speed and power (V15a, published under D72). */
    public const val SIBLING_FRAME_LENGTH: Int = 14

    /** The invariant first three octets of a main frame, `0x11 0x20 0x00`. */
    public val MAIN_PREFIX: IntArray = intArrayOf(17, 32, 0)

    /**
     * The console's stream marker: a 5-octet frame on the measurement characteristic.
     *
     * **Registered 2026-08-18 to stop counting a legitimate frame as corruption.** `05 09 01 01 00`
     * and `05 09 01 03 00` arrive on [SchwinnX70Uuids.UUID_X70_MEASUREMENT], self-describe their
     * length in byte 0, and appear in four of five captured sessions — as the **first**
     * main-characteristic frame of the link in both single-connection ones. [parseMain] requires
     * exactly 17 octets, so before this existed every one of them incremented the handler's
     * rejected-frame counter. That counter feeds the §11 evidence record and diagnostic capture,
     * so it was wrong on every x70 session that produced a main stream.
     *
     * **This recognises the frame; it does not decode it.**
     *
     * **Widened 2026-08-19, because the first version repeated the defect it was written to fix.**
     * It required the first three octets to be `05 09 01`, generalised from two samples. A 570u
     * corpus then produced `05 09 02 01 00` — byte 2 is `0x02` — which the check rejected and the
     * handler counted as corruption, which is precisely what `isStreamMarker` exists to stop.
     *
     * Five variants are now observed across three consoles: `05 09 01 00 00`, `05 09 01 01 00`,
     * `05 09 01 03 00` and `05 09 02 01 00`. Bytes 2 and 3 both vary and neither is understood.
     * What holds everywhere is the length, the `05 09` opening, and a zero last octet, so that is
     * what is checked — enough to reject a foreign 5-octet frame, and no more than the evidence
     * supports.
     */
    public const val STREAM_MARKER_LENGTH: Int = 5

    /** The two leading octets that hold across every observed variant. */
    public val STREAM_MARKER_PREFIX: IntArray = intArrayOf(0x05, 0x09)

    /** Byte 4 is zero in every observed marker; bytes 2 and 3 vary and mean nothing known. */
    public const val STREAM_MARKER_TRAILER: Int = 0x00

    /**
     * The initialization command, sent verbatim (V17).
     *
     * QZ's literal, chosen because a physical console was observed accepting it. Byte 0 is the frame
     * length and every octet sums to `0x100`; both properties are asserted by test rather than computed
     * here, because a command FitBridge generated would be a third value for a field where two already
     * disagree and nothing can choose between them.
     */
    public val INITIALISATION: ByteArray = byteArrayOf(
        0x07, 0x01, 0xD3.toByte(), 0x00, 0x1F, 0x05, 0x01,
    )

    /**
     * Quadratic coefficients per resistance level: `a`, `b`, `c` for `a·rpm² + b·rpm + c`.
     *
     * Source: TMIW `schwinn_x70.dart`, adapted from
     * `ursoft/ANT_Libraries` `WROOM_esp32.ino`. Third-party, unverified, and the weakest evidence in
     * Phase 7 — recorded as such in §7 rather than smoothed over.
     */
    public val RESISTANCE_POWER_COEFFICIENTS: List<Triple<Double, Double, Double>> = listOf(
        Triple(-0.0014, 0.7920, -13.985),
        Triple(-0.0004, 0.9991, -19.051),
        Triple(0.0015, 0.9179, -13.745),
        Triple(0.0040, 0.9857, -13.095),
        Triple(0.0027, 1.3958, -22.741),
        Triple(0.0057, 1.1586, -15.126),
        Triple(-0.0013, 2.4666, -49.052),
        Triple(0.0002, 2.6349, -52.390),
        Triple(0.0034, 2.6240, -48.072),
        Triple(0.0147, 1.6372, -19.653),
        Triple(0.0062, 2.5851, -43.254),
        Triple(0.0064, 3.2864, -59.336),
        Triple(0.0048, 3.6734, -69.245),
        Triple(0.0184, 2.1842, -28.936),
        Triple(0.0052, 4.3939, -78.603),
        Triple(0.0094, 3.8871, -65.982),
        Triple(0.0165, 3.3074, -49.906),
        Triple(0.0251, 3.2956, -44.436),
        Triple(0.0281, 2.9107, -38.767),
        Triple(0.0311, 2.9435, -35.851),
        Triple(0.0141, 5.5646, -88.686),
        Triple(0.0517, 1.8361, -13.777),
        Triple(0.0467, 2.9273, -35.908),
        Triple(0.0429, 4.1821, -50.141),
        Triple(0.0652, 3.6670, -46.863),
    )
}
