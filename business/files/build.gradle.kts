// business:files —— 用户选中的文件 / 文件夹的「路径解析 + 可达性 + 授权」能力。
//
// 边界：只做「把 SAF 的 content uri 变成 agent 能按路径读的真实文件」这一件事。
// 不拷贝文件（TASK.md D3），所以这里既不碰沙箱、也不碰 MediaStore。
plugins {
    id("com.android.library")
}

android {
    namespace = "com.niki914.zafiro.business.files"
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
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    api(project(":business:api"))
    implementation(project(":business:application"))
    implementation(project(":business:permission"))
    implementation(project(":libs:logging"))

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit:2.2.10")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
}
