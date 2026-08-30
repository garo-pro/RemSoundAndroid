import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.garo.remsound.kit"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

// Kotlin 2.4 removed the `kotlinOptions { jvmTarget = "17" }` String DSL inside `android`;
// the JVM target is set through the Kotlin extension instead.
kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    api(libs.kotlinx.coroutines.android)
    // Pure-Java Opus (a port of libopus). Chosen over an NDK build of libopus so the wire
    // path stays buildable — and unit-testable — on a plain JVM CI runner with no NDK.
    api(libs.concentus)
    testImplementation(libs.junit)
    // The real org.json, so the protocol and profile tests exercise the same parsing the app
    // does. Android's bundled org.json is a stub in unit tests and would silently return
    // defaults for everything.
    testImplementation(libs.json.jvm)
}
