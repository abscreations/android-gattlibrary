package com.gmail.absolutebollockscreations.gattlibrary.simulator

/**
 * Opt-in marker for simulator declarations that are public only so the test-only `gatt-conformance`
 * module can compare simulator output with central's decoders (L25). They were `internal`; nothing
 * outside the library's own tests should use them, and they are not a supported surface. The
 * simulator, gatt-conformance and their tests opt in through `compilerOptions.optIn`.
 */
@RequiresOptIn(
    level = RequiresOptIn.Level.ERROR,
    message = "Simulator probe API: public only for gatt-conformance; not a supported surface.",
)
@Retention(AnnotationRetention.BINARY)
annotation class SimulatorProbeApi
