package com.gmail.absolutebollockscreations.Fitbridge.bluetooth.core

/**
 * Domain and protocol constants the BLE stack owns (LIB-2/S2).
 *
 * These used to live in the app's `AppSettings`, which the engine and peripheral imported. They
 * are facts about sensors, SIG characteristics and the Life Fitness handshake rather than
 * per-app preferences, so the library owns them and `AppSettings` aliases them under its
 * existing names. Values are unchanged.
 */
object BleDefaults {

    // ── CSC calibration ──────────────────────────────────────────────────────────────────────
    const val DEFAULT_CSC_WHEEL_CIRCUMFERENCE_MM = 2_405
    const val MIN_CSC_WHEEL_CIRCUMFERENCE_MM = 900
    const val MAX_CSC_WHEEL_CIRCUMFERENCE_MM = 3_000

    const val DEFAULT_ELLIPTICAL_STRIDE_LENGTH_MM = 1_600
    const val MIN_ELLIPTICAL_STRIDE_LENGTH_MM = 100
    const val MAX_ELLIPTICAL_STRIDE_LENGTH_MM = 3_000

    // ── SIG Gender characteristic 0x2A8C ─────────────────────────────────────────────────────
    const val SIG_GENDER_MALE = 0
    const val SIG_GENDER_FEMALE = 1

    /**
     * Specification-valid and unverified against a Life Fitness console; see the risk note on
     * `AppSettings.GENDER_UNSPECIFIED`.
     */
    const val SIG_GENDER_UNSPECIFIED = 2

    // ── Life Fitness user-data defaults (truthful as defaults; never block a connection) ─────
    const val LF_DEFAULT_GENDER = SIG_GENDER_UNSPECIFIED
    const val LF_DEFAULT_WEIGHT_KG = 75
    const val LF_DEFAULT_HEIGHT_CM = 170
}

/** SIG user-data wire encodings, moved from `AppSettings` (LIB-2/S2). */
object SigUserDataUnits {

    /**
     * Encode weight for the SIG Weight characteristic `0x2A98`: `uint16` in 0.005 kg units.
     *
     * A scale conversion is the classic silent-failure field, so it is a named function with a
     * test rather than an inline multiply at the write site. 75 kg is 15000, not 75 and not 750.
     */
    fun weightToWireUnits(kilograms: Int): Int = kilograms * 200

    /**
     * Encode height for the SIG Height characteristic `0x2A8E`: `uint16` in 0.01 m units.
     *
     * 0.01 m is one centimetre, so the wire value equals the stored centimetres. Written out
     * anyway: an identity conversion that is *documented* as an identity cannot later be mistaken
     * for a missing one.
     */
    fun heightToWireUnits(centimetres: Int): Int = centimetres
}
