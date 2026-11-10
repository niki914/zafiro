// Standalone build so the core can be tested without the Android toolchain (`gradle test` in this directory).
// When this directory is built as part of the root project, this file is ignored by Gradle.
pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
    plugins {
        id("org.jetbrains.kotlin.jvm") version "2.2.0"
    }
}
dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}
rootProject.name = "autonomy-core"
