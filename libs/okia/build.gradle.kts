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

    // 默认 HttpEngine（OkHttpEngine，public）：构造签名暴露 OkHttpClient（D-T2B-4），
    // 消费者需自建 client 注入（如 proxy interceptor），故 api。
    api("com.squareup.okhttp3:okhttp:4.12.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    // 默认 HttpEngine 测试：本地起 HTTP server 验证真实请求/响应/超时/取消
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}
