import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    // No version: the root project's plugins block already put the Kotlin
    // Gradle plugin (which carries both the multiplatform and the jvm plugin
    // ids) on the build's classpath, so this module inherits the one version
    // the whole repo is built with.
    id("org.jetbrains.kotlin.jvm")
    application
}

// Plain Kotlin/JVM on purpose: this tool has to run on a client's bare Windows
// PC with nothing but a JRE, so no Compose, no Android, no KMP machinery.
// Bytecode 17, not the JDK the build happens to run on: the lab PC we hand the
// jar to may be several Java releases behind this Mac.
kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

val simMainClass = "com.bnm.analyzersim.MainKt"

dependencies {
    // The ONLY third-party dependency — and only for the RS-232 case. It is the
    // same library composeApp's desktop target uses for the real serial link,
    // so the simulator and the app agree about what 8-N-1 at 115200 means.
    implementation("com.fazecast:jSerialComm:2.11.0")
    testImplementation(kotlin("test"))
}

application {
    mainClass.set(simMainClass)
    applicationName = "analyzer-sim"
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    testLogging { events("passed", "failed", "skipped") }
}

// ── single runnable jar ──
//
// A field engineer gets ONE file and types `java -jar analyzer-sim.jar`. No
// shadow plugin (a new plugin dependency for a 2-jar classpath is not worth
// it): the runtime classpath here is the Kotlin stdlib plus jSerialComm, and
// unpacking both keeps jSerialComm's bundled native libraries at the resource
// paths it looks them up by, so serial still works out of the fat jar.
val fatJar = tasks.register<Jar>("fatJar") {
    group = "distribution"
    description = "Self-contained analyzer-sim.jar (java -jar analyzer-sim.jar …)"
    archiveFileName.set("analyzer-sim.jar")
    // Not build/libs: the application plugin's start scripts read that directory,
    // and a fat jar appearing there makes Gradle flag an implicit dependency.
    destinationDirectory.set(layout.buildDirectory.dir("dist"))
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    manifest { attributes("Main-Class" to simMainClass) }
    from(sourceSets["main"].output)
    from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) })
    // Signatures of the jars we unpacked no longer match the merged content, and
    // a stray module-info would make the JVM treat this as a named module.
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "META-INF/MANIFEST.MF", "module-info.class")
}

tasks.named("build") { dependsOn(fatJar) }
