// business:screen —— 屏幕契约。
//
// 边界：纯 JVM。不依赖 Android、无障碍类型、agent-runtime。
// 只有签名和值类型。Builtin 通过 findService<Screens>() 取实现。
plugins {
    id("org.jetbrains.kotlin.jvm")
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
}
