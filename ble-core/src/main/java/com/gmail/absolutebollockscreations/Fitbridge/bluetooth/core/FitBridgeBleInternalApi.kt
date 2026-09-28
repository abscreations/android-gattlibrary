package com.gmail.absolutebollockscreations.Fitbridge.bluetooth.core

/** Library implementation API exposed only where the module graph requires it. */
@RequiresOptIn(
    message = "This FitBridge BLE implementation API may change without notice.",
    level = RequiresOptIn.Level.WARNING,
)
@Retention(AnnotationRetention.BINARY)
@Target(
    AnnotationTarget.CLASS,
    AnnotationTarget.FUNCTION,
    AnnotationTarget.PROPERTY,
    AnnotationTarget.CONSTRUCTOR,
    AnnotationTarget.TYPEALIAS,
)
annotation class FitBridgeBleInternalApi
