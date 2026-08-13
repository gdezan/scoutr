// Vendored Termux terminal-view (com.termux.view) — see ../UPSTREAM.md.
// Build file is new (Scoutr adaptation): upstream used Groovy DSL with
// maven-publish; sources themselves are byte-identical to upstream.
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.termux.view"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    // The view types its session as com.termux.terminal.TerminalSession and
    // uses TerminalEmulator/TerminalBuffer/TerminalRow/TextStyle/WcWidth.
    api(project(":vendor:termux:terminal-emulator"))
    // TerminalView and text selection use androidx.annotation (upstream pins 1.9.0).
    implementation(libs.androidx.annotation)
}
