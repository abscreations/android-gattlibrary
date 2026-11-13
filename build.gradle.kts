buildscript {
    repositories {
        mavenCentral()
    }
    dependencies {
        // AGP's built-in Kotlin uses the KGP on the build classpath.
        classpath(libs.kotlin.gradle.plugin)
    }
}

plugins {
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.bcv.bridge) apply false
}

allprojects {
    group = "com.gmail.absolutebollockscreations.gattlibrary"
}
