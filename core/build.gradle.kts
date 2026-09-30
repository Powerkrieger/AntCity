import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Pure-Kotlin game rules + deterministic simulation, shared by the Android app and the server.
// Anything that affects game state must live here so that both sides compute identical results.
plugins {
    `java-library`
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_11) }
}

dependencies {
    api(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
    testImplementation(kotlin("test-junit"))
}
