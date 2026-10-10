// Offline experiments: evolves bots by self-play and measures how rule variants play out.
// Pure JVM, so it runs on any server with Java 21 (./gradlew :lab:installDist, then lab/build/install/lab/bin/lab).
plugins {
    application
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass.set("com.example.antcity.lab.LabKt")
    applicationDefaultJvmArgs = listOf("-Xmx8g")
}

dependencies {
    implementation(project(":core"))
    testImplementation(kotlin("test-junit"))
}
