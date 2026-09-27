plugins {
    id("com.android.library")
}

android {
    namespace = "com.niki914.zafiro.business.application"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("androidx.activity:activity:1.4.0")
    implementation(project(":libs:logging"))
}
