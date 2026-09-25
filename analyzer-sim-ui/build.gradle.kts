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
    // The repo's Material 3 and icon pack, same coordinates composeApp uses, so
    // this window and BNM Lab are drawn from one Material. (The icons accessor
    // is deprecated in this Compose version and warns; composeApp carries the
    // same warning, and matching it is worth more than silencing it here.)
    implementation(libs.compose.material3)
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
    // LiveRunTest needs a real BNM Lab listening, so it is opt-in — the same
    // shape composeApp uses for `bnm.e2e`. A -D on the Gradle command line
    // reaches the GRADLE jvm, never the forked test jvm, unless it is passed
    // on like this; declaring it as a property also makes the task re-run when
    // it changes instead of reporting UP-TO-DATE.
    val live = providers.systemProperty("bnm.lab.live")
        .orElse(providers.gradleProperty("bnm.lab.live")).orNull
    if (live != null) {
        systemProperty("bnm.lab.live", live)
        for (key in listOf("bnm.lab.host", "bnm.lab.port")) {
            providers.systemProperty(key).orNull?.let { systemProperty(key, it) }
        }
        // The point of the live run is reading its transcript.
        testLogging { showStandardStreams = true }
    }
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
            // jlink trims the bundled runtime: a module that is not in the image
            // is MISSING at runtime, and `./gradlew run` (full JDK) never shows
            // it — BNM Lab learned that by shipping a packaged app that died on
            // launch. So this list was established by BUILDING the app and
            // running the packaged binary, not by reasoning:
            //
            //   The Compose plugin's own default image already carries
            //   java.base, java.datatransfer, java.xml, java.prefs, java.desktop,
            //   java.logging and jdk.crypto.ec (read from the built runtime's
            //   `release` file). java.desktop is named here anyway: this app
            //   cannot draw a pixel without it, and that is not a fact worth
            //   leaving to somebody else's default.
            //
            //   jdk.unsupported is the one module genuinely ADDED — jSerialComm
            //   reaches for sun.misc.Unsafe, and enumerating serial ports is a
            //   Mispa install's first five minutes.
            //
            // Deliberately NOT java.sql / java.naming (BNM Lab needs them for
            // SQLDelight and TLS; nothing here opens a database or an HTTPS
            // socket) and not java.instrument (jdeps suggests it; the packaged
            // app runs without it). Every unused module is weight in an
            // installer a field engineer downloads over a client's wifi.
            //
            // To re-verify after changing anything here:
            //   ./gradlew :analyzer-sim-ui:createDistributable
            //   "analyzer-sim-ui/build/compose/binaries/main/app/BNM Analyzer \
            //     Simulator.app/Contents/MacOS/BNM Analyzer Simulator" --selftest
            modules("java.desktop", "jdk.unsupported")
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
