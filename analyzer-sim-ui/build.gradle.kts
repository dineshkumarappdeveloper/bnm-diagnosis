import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.jetbrains.kotlin.jvm")
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.kotlinSerialization)
}

// A desktop-only Compose app, not a KMP module: this never ships to a phone.
// Java 17 to match the core, which is deliberately built for a lab PC several
// Java releases behind this Mac.
kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    // The simulator itself. This module is a FRONT END: every frame, every
    // fault and every byte on the wire comes from the core, so the window and
    // `java -jar analyzer-sim.jar` can never drift into sending different things.
    implementation(project(":analyzer-sim"))
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)
    // Serial port list for the picker. Same library the core (and the app) use,
    // so "the port the simulator offered" is the port the app would open.
    implementation("com.fazecast:jSerialComm:2.11.0")
    // Presets on disk.
    implementation(libs.kotlinx.serialization.json)
    // Compose's Main dispatcher on desktop is the Swing event thread; without
    // this, launch(Dispatchers.Main) from a composition has no dispatcher.
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.8.0")

    testImplementation(kotlin("test"))
    // ImageComposeScene, for the render tests — off-screen, no window.
    testImplementation(compose.desktop.currentOs)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    testLogging { events("passed", "failed", "skipped") }
}

compose.desktop {
    application {
        mainClass = "com.bnm.analyzersim.ui.MainKt"
        nativeDistributions {
            // MSI first on purpose: the engineers who need this are on Windows
            // laptops at a client's bench, where "download and double-click" is
            // the only install that happens.
            targetFormats(TargetFormat.Msi, TargetFormat.Dmg, TargetFormat.Deb)
            packageName = "BNM Analyzer Simulator"
            packageVersion = (project.findProperty("appVersion") as String?) ?: "1.0.0"
            // jlink trims the bundled runtime: anything not listed is MISSING at
            // runtime, and `./gradlew run` (full JDK) never shows it — BNM Lab
            // learned that by shipping a packaged app that died on launch.
            // Verified by building the DMG and running the packaged binary with
            // --selftest, which renders the real screen off-screen.
            //   java.desktop     → Skiko/AWT — Compose Desktop cannot draw without it
            //   jdk.unsupported  → sun.misc.Unsafe (jSerialComm)
            //   java.logging     → java.util.logging, reached by Skiko and jSerialComm
            // Deliberately NOT java.sql or java.naming: nothing here touches a
            // database or TLS, and an unused module is dead weight in the installer.
            modules("java.desktop", "jdk.unsupported", "java.logging")
            description = "BNM Analyzer Simulator — rehearse a lab commissioning with no analyzer on the bench"
            vendor = "BNM"
            windows {
                menu = true
                shortcut = true
                menuGroup = "BNM"
                perUserInstall = true
                // Its own UUID. Reusing BNM Lab's would make the installer treat
                // this as an upgrade OF BNM LAB and uninstall a lab's LIMS.
                upgradeUuid = "4c1f8b06-5d92-4e37-a0b8-2e7d9a1c6f45"
            }
            macOS { bundleID = "com.bnm.analyzersim" }
            linux {
                shortcut = true
                menuGroup = "BNM"
                packageName = "bnm-analyzer-simulator"
            }
        }
    }
}
