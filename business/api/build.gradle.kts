// business:api —— 消费侧契约与业务模型。
//
// 边界（结构性保证）：纯 JVM 模块，不依赖 okia / Android / androidx /
// 任何实现模块，因此契约里写不出 Context、R、资源 id。
// 只依赖 coroutines（StateFlow 出现在签名上）。
plugins {
    id("org.jetbrains.kotlin.jvm")
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
}
