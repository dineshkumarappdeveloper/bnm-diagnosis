// Gradle project names may not contain spaces ([a-zA-Z][A-Za-z0-9\-_]*).
// The DISPLAY name is "BNM Lab" (see composeApp/build.gradle.kts
// nativeDistributions.packageName); this is only the build identifier.
rootProject.name = "BNMLab"
enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

pluginManagement {
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
    }
}

include(":composeApp")
include(":analyzer-sim")
// The simulator's window — a front end on :analyzer-sim, packaged on its own
// so a field engineer installs one thing and never opens a terminal.
include(":analyzer-sim-ui")
