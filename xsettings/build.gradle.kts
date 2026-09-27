// xsettings —— app 模块以外的本地配置读取口。
//
// 边界（结构性保证）：纯 JVM 模块，不依赖 Android / androidx / 任何实现模块，
// 因此接口里写不出 Context、R、资源 id。只依赖 coroutines（StateFlow 出现在签名上）。
//
// 实现（读 XRepo 的适配器）只在 app 组合根注册，见 AppServices。
plugins {
    id("org.jetbrains.kotlin.jvm")
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
}
