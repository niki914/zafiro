// Vendored from https://github.com/niki914/libterm @ 55d02c3
// AGP 9 built-in Kotlin — no org.jetbrains.kotlin.android plugin needed
plugins {
    id("com.android.library")
}

android {
    namespace = "com.niki914.libterm.backend.ssh"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":libs:libterm-core"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("com.github.mwiede:jsch:2.28.3")

    testImplementation("org.jetbrains.kotlin:kotlin-test-junit:2.2.10")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
}
