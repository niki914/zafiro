// autonomy:core — scheduling, run state machine and budgets.
//
// Boundary (structural): pure JVM, no Android, no okia, no upstream modules. Same pattern as :business:api.
// Everything here is deterministic (time is always passed in), so it is unit-tested on any machine, including
// a sandbox without the Android SDK: `cd autonomy/core && gradle test`.
plugins {
    id("org.jetbrains.kotlin.jvm")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit:2.2.0")
}
