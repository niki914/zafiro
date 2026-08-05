// 可复用日志库：Android + JVM 双 target（KMP）。
// AGP 9 built-in Kotlin KMP 形态：multiplatform 插件 + com.android.kotlin.multiplatform.library。
plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("com.android.kotlin.multiplatform.library")
}

kotlin {
    android {
        namespace = "com.niki914.logging"
        compileSdk = 37
        minSdk = 26
    }
    jvm {
        compilerOptions {
            jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
        }
    }
}
