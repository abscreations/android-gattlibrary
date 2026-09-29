package com.gmail.absolutebollockscreations.gattlibrary.core

/** Library implementation API exposed only where the module graph requires it. */
@RequiresOptIn(
    message = "This gattLibrary implementation API may change without notice.",
    level = RequiresOptIn.Level.WARNING,
)
@Retention(AnnotationRetention.BINARY)
@Target(
    AnnotationTarget.CLASS,
    AnnotationTarget.FUNCTION,
    AnnotationTarget.PROPERTY,
    // Setters too, so a property can stay publicly readable while only its setter is an
    // implementation API (LIB-10d: FtmsProcedureSession state set by tests above the module).
    AnnotationTarget.PROPERTY_SETTER,
    AnnotationTarget.CONSTRUCTOR,
    AnnotationTarget.TYPEALIAS,
)
annotation class GattLibraryInternalApi
