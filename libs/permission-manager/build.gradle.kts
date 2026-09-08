// AGP 9 built-in Kotlin — no org.jetbrains.kotlin.android plugin needed
plugins {
    id("com.android.library")
}

android {
    namespace = "com.niki914.permission"
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
    implementation(project(":libs:logging"))
    implementation("androidx.annotation:annotation:1.10.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    // shell 通道：root（libsu）+ shizuku。逻辑抄自 libterm，代码独立
    implementation("com.github.topjohnwu.libsu:core:6.0.0")
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")

    testImplementation("org.jetbrains.kotlin:kotlin-test-junit:2.2.10")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
}
