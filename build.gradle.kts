// Root build file — plugin versions live in gradle/libs.versions.toml; the modules apply them.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
