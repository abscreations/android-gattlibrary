package com.gmail.absolutebollockscreations.gattlibrary.core.vendor

/**
 * Which console handshake dialect a connection uses.
 *
 * [LEGACY] is the pinned TMIW command-2 form proven by the V2/Blue captures. [BULL] is the
 * command-21 form shipped by the KayakFit desktop application against physical Bull consoles,
 * whose fourth and fifth fields are athlete weight and boat weight rather than weight and sport.
 * Both dialects share every other command in the profile.
 */
public enum class KayakFirstHandshakeVariant { LEGACY, BULL }
