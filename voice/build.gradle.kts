// voice — speech-to-text input. Non-UI: a thin, testable wrapper around android.speech.SpeechRecognizer.
// The mic button lives in the app's chat composer and uses this module.
plugins {
    id("com.android.library")
}

android {
    namespace = "com.tricreta.zafiro.voice"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint { checkReleaseBuilds = false }
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation(project(":libs:logging"))

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit:2.2.10")
}
