// business:remote —— 遥测实现（渠道、首装事实、设备画像、账页）。
//
// 边界：只有 :app 依赖本模块。契约常量来自 business:api，应用语言来自 xsettings。
plugins {
    id("com.android.library")
}

android {
    namespace = "com.niki914.zafiro.business.remote"
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
    implementation(project(":business:api"))
    implementation(project(":xsettings"))
    implementation(project(":libs:logging"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    // 与 ui-kit、remote-view 的 lifecycle 2.8.7 对齐。仓库没有 version catalog。
    implementation("androidx.lifecycle:lifecycle-process:2.8.7")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit:2.2.10")
    testImplementation("org.json:json:20240303")
}
