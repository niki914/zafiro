// business:agent —— `Agent` 的实现侧（AgentImpl 与归约器）。
//
// 与 business:api 的分工：契约边界只在 api（纯 JVM，不依赖 Android / okia）；
// 实现侧可以依赖引擎（agent-runtime / okia）与 Android。业务方（app）只依赖 api，
// 由组合根装配本模块的实现。
plugins {
    id("com.android.library") version "9.1.1"
}

android {
    namespace = "com.niki914.zafiro.business.agent"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    // 契约类型出现在实现签名上，向下游可见
    api(project(":business:api"))
    // 存储端口的签名用 SessionSnapshot（app 侧实现该端口）
    api(project(":libs:okia"))

    // 委托实现阶段：执行转发到遗留 LLMController（架空完成后删除）
    implementation(project(":agent-runtime"))
    implementation(project(":libs:logging"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")

    testImplementation("junit:junit:4.13.2")
}
