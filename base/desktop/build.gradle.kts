// Orpheon desktop (Windows, Linux): Compose Multiplatform Desktop, Immersive look only.
// Part of Orpheon (https://github.com/kota-zo/Orpheon), GPL-3.0.
//   ./gradlew :desktop:run
//   ./gradlew :desktop:packageDistributionForCurrentOS   (Msi on Windows, Deb on Linux; run on each OS)
import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)

    // Same contract and JVM-only modules as the phone.
    implementation(project(":api"))
    implementation(project(":extensions"))
    implementation(project(":catalog"))

    // Dispatchers.Main = the Swing event thread Compose Desktop draws on.
    implementation(libs.coroutines.swing)
    implementation(libs.okhttp)

    // Playback: libVLC (GPL-compatible, 10-band equalizer, rate, every codec the app plays).
    implementation("uk.co.caprica:vlcj:4.8.3")

    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}

compose.desktop {
    application {
        mainClass = "app.orpheon.desktop.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Deb)
            packageName = "orpheon"
            packageVersion = "1.0.0"
            description = "Orpheon, an extension-based music player"
            vendor = "Orpheon"
            licenseFile.set(rootProject.file("../LICENSE"))
            windows {
                menuGroup = "Orpheon"
                upgradeUuid = "6b0d7c1e-3f4a-4b8e-9a52-0c1f2d9e7a10"
            }
            linux {
                debMaintainer = "orpheon@localhost"
                menuGroup = "AudioVideo;Audio;Player"
            }
        }
    }
}
