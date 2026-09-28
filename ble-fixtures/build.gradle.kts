// ble-fixtures: test-only support for the BLE libraries (LIB-10c). Holds the fixture corpus as
// Java resources (classloader paths stay "fixtures/...") and the shared FixtureJson reader.
// Modules depend on it only through testImplementation; it is never shipped.
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.gmail.absolutebollockscreations.fitbridge.ble.fixtures"
    compileSdk {
        version = release(37) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        minSdk = 30
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}
