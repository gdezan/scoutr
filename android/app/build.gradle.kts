import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.io.ByteArrayOutputStream
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Version identity comes from the shared scripts/version.mjs — the same script
// the bridge runs at runtime, so the APK and the host always agree. Run it once
// at configuration time (no configuration-cache here) and read key=value props.
val buildIdentity: Properties = Properties().apply {
    val out = ByteArrayOutputStream()
    exec {
        commandLine("node", file("../../scripts/version.mjs").absolutePath, "--props")
        standardOutput = out
    }
    load(String(out.toByteArray(), Charsets.UTF_8).byteInputStream())
}

android {
    namespace = "dev.scoutr.app"
    compileSdk = 36

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
    defaultConfig {
        applicationId = "dev.scoutr.app"
        minSdk = 26
        targetSdk = 36
        versionCode = buildIdentity.getProperty("versionCode").toInt()
        versionName = buildIdentity.getProperty("version")
        buildConfigField("String", "VERSION_NAME", "\"${buildIdentity.getProperty("version")}\"")
        buildConfigField("int", "VERSION_CODE", buildIdentity.getProperty("versionCode"))
        buildConfigField("String", "GIT_COMMIT", "\"${buildIdentity.getProperty("commit")}\"")
        buildConfigField("boolean", "GIT_DIRTY", buildIdentity.getProperty("dirty"))
        buildConfigField("String", "BUILD_TIME", "\"${buildIdentity.getProperty("buildTime")}\"")
        // Zeroes animation scales before Espresso runs; see ScoutrTestRunner.
        testInstrumentationRunner = "dev.scoutr.app.ScoutrTestRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        create("benchmark") {
            // Macrobenchmark's test APK is debuggable; the app under test is not.
            initWith(getByName("release"))
            isDebuggable = false
            isMinifyEnabled = true
            isShrinkResources = true
            isProfileable = true
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    // FakeScoutrApi lives in src/commonTest/kotlin, shared by the local
    // unit tests and the emulator suite so both can stub the bridge.
    sourceSets {
        getByName("test") { java.srcDir("src/commonTest/kotlin") }
        getByName("androidTest") { java.srcDir("src/commonTest/kotlin") }
    }

    testOptions {
        managedDevices {
            localDevices {
                create("pixel2api36") {
                    device = "Pixel 2"
                    apiLevel = 36
                    systemImageSource = "aosp-atd"
                    // aosp-atd lacks NDK translation; pin the test ABI so the
                    // managed device does not fail setup (see gradle warning).
                    testedAbi = "x86_64"
                }
            }
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.textmate.core)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.zxing.android.embedded)
    implementation(libs.markdown.renderer.m3)
    // Vendored Termux terminal stack (see android/vendor/termux/UPSTREAM.md).
    implementation(project(":vendor:termux:terminal-view"))

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)

    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.mockwebserver)
    testImplementation(libs.mockwebserver)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)

    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
