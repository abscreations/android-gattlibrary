// gatt-conformance: test-only module (L25). Each central family in the core catalog has a persona in
// the simulator, and persona output decodes through central against golden captures from
// gatt-fixtures. It has no production code and is never shipped; every edge is testImplementation.
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.gmail.absolutebollockscreations.gattlibrary.conformance"
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

    sourceSets {
        // The sanitized Stryd capture corpus lives with the evidence documents; the golden test reads
        // it from the classpath by file name rather than keeping a second copy.
        getByName("test").resources.directories.add("../docs/evidence/stryd")
    }

    testOptions {
        unitTests {
            isReturnDefaultValues = true
        }
    }
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile>().configureEach {
    compilerOptions.optIn.add(
        "com.gmail.absolutebollockscreations.gattlibrary.core.GattLibraryInternalApi"
    )
    compilerOptions.optIn.add(
        "com.gmail.absolutebollockscreations.gattlibrary.simulator.SimulatorProbeApi"
    )
}

dependencies {
    testImplementation(project(":gatt-core"))
    testImplementation(project(":gatt-central"))
    testImplementation(project(":gatt-server"))
    testImplementation(project(":gatt-peripheral"))
    testImplementation(project(":gatt-simulator"))
    testImplementation(project(":gatt-fixtures"))
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
