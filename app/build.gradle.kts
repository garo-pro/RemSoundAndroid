import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.garo.remsound"
    // compileSdk only controls which APIs the code compiles against; Compose 1.12 and AGP 9
    // both require 37. targetSdk stays at 35 deliberately — that is the runtime-behaviour
    // opt-in and a separate product decision.
    compileSdk = 37

    defaultConfig {
        applicationId = "com.garo.remsound"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // No signing config on purpose: nothing in this repo uploads to the Play Store, so
            // there is no keystore and no signing secret. `assembleRelease` therefore produces
            // app-release-unsigned.apk, which has to be signed before a device will install it.
        }
        debug {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
    }
    lint {
        // Lint runs in CI and its report is uploaded, but it does not gate the APK: an SDK or
        // dependency bump can introduce a new check that has nothing to do with this code, and a
        // build that produces no installable APK for that is worse than one that reports it.
        abortOnError = false
        checkReleaseBuilds = false
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
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
    implementation(project(":remsoundkit"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.media)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    debugImplementation(libs.androidx.ui.tooling)
    testImplementation(libs.junit)
}
