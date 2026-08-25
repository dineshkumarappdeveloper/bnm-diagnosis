import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties
import java.io.FileInputStream

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.sqldelight)
}

kotlin {
    // expect/actual classes (DriverFactory, ConnectivityMonitor) are still a Beta
    // Kotlin feature; this is the recommended flag to silence the Beta warning.
    compilerOptions { freeCompilerArgs.add("-Xexpect-actual-classes") }

    androidTarget {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_11) }
    }

    listOf(iosArm64(), iosSimulatorArm64()).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "ComposeApp"
            isStatic = true
        }
    }

    jvm("desktop")

    sourceSets {
        androidMain.dependencies {
            implementation(libs.compose.uiToolingPreview)
            implementation(libs.androidx.activity.compose)
            implementation(libs.ktor.client.okhttp)
            implementation(libs.sqldelight.android.driver)
        }
        iosMain.dependencies {
            implementation(libs.ktor.client.darwin)
            implementation(libs.sqldelight.native.driver)
        }
        val desktopMain by getting {
            dependencies {
                implementation(compose.desktop.currentOs)
                implementation(libs.ktor.client.okhttp)
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.8.0")
                implementation(libs.sqldelight.sqlite.driver)
            }
        }
        commonMain.dependencies {
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
            implementation(libs.compose.ui)
            implementation(libs.compose.components.resources)
            implementation(libs.compose.uiToolingPreview)
            implementation(libs.androidx.lifecycle.viewmodelCompose)
            implementation(libs.androidx.lifecycle.runtimeCompose)
            implementation(libs.androidx.navigation.compose)
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.serialization.kotlinx.json)
            implementation(libs.ktor.client.logging)
            implementation(libs.ktor.network) // raw TCP for ESC/POS network thermal printers
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.datetime)
            implementation(libs.multiplatform.settings)
            implementation("com.russhwolf:multiplatform-settings-no-arg:1.1.1")
            implementation(libs.qrose) // QR painter (UPI pay + payment links)
            implementation(compose.materialIconsExtended)
            implementation(libs.sqldelight.runtime)
            implementation(libs.sqldelight.coroutines.extensions)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
    }
}

// Release signing: read from a gitignored keystore.properties (or CI env vars).
// Absent → release builds simply stay unsigned, so the build never breaks.
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) FileInputStream(keystorePropsFile).use { load(it) }
}

android {
    namespace = "com.bnm.diagnosis"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.bnm.diagnosis"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = 10000
        versionName = "1.0.0"
    }
    packaging {
        resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    }
    signingConfigs {
        create("release") {
            val sf = keystoreProps.getProperty("RELEASE_STORE_FILE") ?: System.getenv("RELEASE_STORE_FILE")
            if (sf != null) {
                storeFile = rootProject.file(sf)
                storePassword = keystoreProps.getProperty("RELEASE_STORE_PASSWORD") ?: System.getenv("RELEASE_STORE_PASSWORD")
                keyAlias = keystoreProps.getProperty("RELEASE_KEY_ALIAS") ?: System.getenv("RELEASE_KEY_ALIAS")
                keyPassword = keystoreProps.getProperty("RELEASE_KEY_PASSWORD") ?: System.getenv("RELEASE_KEY_PASSWORD")
            }
        }
    }
    buildTypes {
        getByName("debug") { isMinifyEnabled = false }
        getByName("release") {
            isMinifyEnabled = false
            signingConfigs.getByName("release").storeFile?.let { if (it.exists()) signingConfig = signingConfigs.getByName("release") }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    debugImplementation(libs.compose.uiTooling)
}

// Generates the typed `AppDatabase` into com.bnm.diagnosis.db from the .sq files
// under composeApp/src/commonMain/sqldelight/com/bnm/diagnosis/db/.
sqldelight {
    databases {
        create("AppDatabase") {
            packageName.set("com.bnm.diagnosis.db")
        }
    }
}

compose.resources {
    publicResClass = true
    packageOfResClass = "com.bnm.diagnosis.resources"
}

compose.desktop {
    application {
        mainClass = "com.bnm.diagnosis.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "BNMDiagnosis"
            packageVersion = (project.findProperty("appVersion") as String?) ?: "1.0.0"
            // jlink trims the bundled runtime to a minimal module set — anything
            // not listed here is MISSING at runtime (dev `gradlew run` uses the
            // full JDK, so these only break in the packaged app):
            //   java.sql        → SQLDelight JDBC driver ("java/sql/DriverManager" crash)
            //   java.naming     → OkHttp/Ktor TLS internals
            //   jdk.unsupported → sun.misc.Unsafe (sqlite-jdbc / okio)
            modules("java.sql", "java.naming", "jdk.unsupported")
            description = "BNM Diagnosis — offline-first diagnostic laboratory (LIMS)"
            vendor = "BNM"
            windows {
                menu = true
                shortcut = true
                menuGroup = "BNM Diagnosis"
                perUserInstall = true
                // Fresh UUID for BNMDiagnosis (never reuse BNMAdmin's).
                upgradeUuid = "b7e1c0a2-3f4d-4a8b-9c2e-1d6f0a5b7c93"
            }
            macOS { bundleID = "com.bnm.diagnosis" }
            linux {
                shortcut = true
                menuGroup = "BNM Diagnosis"
            }
        }
    }
}
