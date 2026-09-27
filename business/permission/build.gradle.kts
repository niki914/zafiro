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

    // shell 通道：root（libsu）+ shizuku。实现由旧 :libs:permission-manager 迁移而来，
    // 与 libterm 共存；战略迁移完成前不合流。
    implementation("com.github.topjohnwu.libsu:core:6.0.0")
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit:2.2.10")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
}
