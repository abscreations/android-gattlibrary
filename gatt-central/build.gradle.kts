// gatt-central: BLE central engine, protocol parsers, metric fusion and BLECAP capture.
// Same AGP 9 DSL as :app; see docs/extract-ble-resource-libraries.md Stage 2 (LIB-10a).
plugins {
    alias(libs.plugins.android.library)
    // API dump: releaseApiDump writes api/gatt-central.api; releaseApiCheck (part of check) fails on
    // an unrecorded public-API change (LIB-12a).
    alias(libs.plugins.android.bcv.bridge)
}

android {
    namespace = "com.gmail.absolutebollockscreations.gattlibrary.central"
    compileSdk {
        version = release(37) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        minSdk = 30
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    testOptions {
        unitTests {
            // android.bluetooth types appear in signatures the JVM tests load; see :app.
            isReturnDefaultValues = true
        }
    }
}

// Library code may use its own and lower modules' @GattLibraryInternalApi declarations;
// the opt-in marks them for outside consumers, not for the libraries themselves (LIB-10a).
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile>().configureEach {
    compilerOptions.optIn.add(
        "com.gmail.absolutebollockscreations.gattlibrary.core.GattLibraryInternalApi"
    )
    // Match gatt-core: enforce explicit API on production variants only.
    if (!name.contains("UnitTest") && !name.contains("AndroidTest")) {
        compilerOptions.freeCompilerArgs.add("-Xexplicit-api=strict")
    }
}

// Unit-test compilations only (LIB-72): tests moved from gatt-conformance use the simulator's probe
// API, as they did there. Main never sees gatt-simulator, so the marker is not opted into there.
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile>()
    .matching { it.name.contains("UnitTest") }
    .configureEach {
        compilerOptions.optIn.add(
            "com.gmail.absolutebollockscreations.gattlibrary.simulator.SimulatorProbeApi"
        )
    }

dependencies {
    api(project(":gatt-core"))
    // Public API exposes StateFlow and coroutine types.
    api(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.core.ktx)
    implementation(libs.timber)
    testImplementation(project(":gatt-fixtures"))
    // Test-only (LIB-72, user decision 2026-11-12 amending L25): cross-module tests that need
    // central internals live in this module's tests. Server and simulator never depend on central
    // (L22), so these edges are acyclic; peripheral depends on central main, not on these tests.
    // BleLayeringTest checks production edges only; these never reach a shipped artifact.
    testImplementation(project(":gatt-server"))
    testImplementation(project(":gatt-peripheral"))
    testImplementation(project(":gatt-simulator"))
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
