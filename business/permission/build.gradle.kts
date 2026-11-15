plugins {
    id("com.android.library")
}

android {
    namespace = "com.niki914.zafiro.business.permission"
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
    api(project(":business:api"))
    implementation(project(":business:application"))
    implementation(project(":libs:logging"))

    // shell 通道：root (libsu) + shizuku (:libs:shizuku)
    implementation("com.github.topjohnwu.libsu:core:6.0.0")
    implementation(project(":libs:shizuku"))

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit:2.2.10")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
}
