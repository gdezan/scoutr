// Vendored Termux terminal-emulator (com.termux.terminal) — see ../UPSTREAM.md.
// Build file is new (Cockpit adaptation): upstream used Groovy DSL with
// externalNativeBuild (ndkBuild) and maven-publish; the native PTY path
// (JNI.java, src/main/jni/) is intentionally not vendored.
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.termux.emulator"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // Upstream runs these JVM tests with returnDefaultValues=true: the
    // sources touch android.util.Base64, android.graphics.Color and
    // android.view.KeyEvent, which return default values outside Android.
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    // TerminalSessionClient uses androidx.annotation NonNull/Nullable (upstream pins 1.9.0).
    implementation(libs.androidx.annotation)
    // Retained upstream emulator tests (ANSI/Unicode/modes/scrollback).
    testImplementation(libs.junit)
}
