// gatt-peripheral: FTMS/CSC/CPS GATT server (downstream bridge) and its helpers.
// Same AGP 9 DSL as :app; see docs/extract-ble-resource-libraries.md Stage 2 (LIB-10a).
plugins {
    alias(libs.plugins.android.library)
    // API dump: releaseApiDump writes api/gatt-peripheral.api; releaseApiCheck (part of check) fails on
    // an unrecorded public-API change (LIB-12a).
    alias(libs.plugins.android.bcv.bridge)
}

android {
    namespace = "com.gmail.absolutebollockscreations.gattlibrary.peripheral"
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

dependencies {
    api(project(":gatt-central"))
    api(project(":gatt-server"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.timber)
    testImplementation(project(":gatt-fixtures"))
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
