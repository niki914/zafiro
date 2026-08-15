// Okia — Okia 重写骨架（libs:okia）。骨架阶段：接口与数据类型，无实现。
// 依赖保持最小，骨架编译快、JVM 可测。
plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.plugin.serialization") version "2.2.0"
}

android {
    namespace = "com.niki914.okia"
    compileSdk = 36

    defaultConfig {
        minSdk = 26

        consumerProguardFiles("consumer-rules.pro")
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    // api：公开签名暴露 StateFlow / SharedFlow / Flow（coroutines）与 Json（serialization），
    // 这些类型必须出现在消费者编译 classpath 上，故不能用 implementation。
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
}
