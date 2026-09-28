// ble-simulator: debug-only simulated machines (L7); the app uses debugImplementation.
// Same AGP 9 DSL as :app; see docs/extract-ble-resource-libraries.md Stage 2 (LIB-10a).
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.gmail.absolutebollockscreations.fitbridge.ble.simulator"
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

// Library code may use its own and lower modules' @FitBridgeBleInternalApi declarations;
// the opt-in marks them for outside consumers, not for the libraries themselves (LIB-10a).
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile>().configureEach {
    compilerOptions.optIn.add(
        "com.gmail.absolutebollockscreations.Fitbridge.bluetooth.core.FitBridgeBleInternalApi"
    )
}

dependencies {
    api(project(":ble-peripheral"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.timber)
    testImplementation(project(":ble-fixtures"))
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
