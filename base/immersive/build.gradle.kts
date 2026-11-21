// Orpheon's shared Immersive UI: Android (:app) and desktop (:desktop) draw the same screens.
// Part of Orpheon (https://github.com/kota-zo/Orpheon), GPL-3.0. The plan and status: ../DESKTOP.md.
//
// UNVERIFIED (written without a compiler): if the plugin below is not found or not applicable, fix this
// file and the `android` block only; see "Build wiring" in DESKTOP.md.
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
}

kotlin {
    jvmToolchain(17)

    android {
        namespace = "app.orpheon.immersive"
        compileSdk = 37
        minSdk = 29
    }
    jvm("desktop")

    sourceSets {
        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.ui)
            implementation(compose.material3)
            implementation(libs.coroutines.core)
            implementation("dev.chrisbanes.haze:haze:1.3.1")
        }
    }
}
