plugins {
    id("com.android.library") version "9.1.1"
    id("org.jetbrains.kotlin.plugin.serialization") version "2.2.0"

    id("com.chaquo.python") version "17.0.0"
}

android {
    namespace = "com.niki914.zafiro"
    compileSdk = 37

    defaultConfig {
        minSdk = 26

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            // 真机都是 arm64；x86_64 模拟器在各模块的 debug 构建里追加
            abiFilters += "arm64-v8a"
        }
    }
    buildTypes {
        debug {
            ndk {
                abiFilters += "x86_64"   // 模拟器调试
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

chaquopy {
    defaultConfig {
        version = "3.11"
        pip {
            install("requests==2.34.2")
            install("beautifulsoup4==4.15.0")
        }
    }
}

dependencies {
    implementation(project(":xposed-api"))
    implementation(project(":libs:logging"))
    implementation(project(":libs:okia"))
    implementation(project(":libs:libterm-runtime"))
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    testImplementation("org.mockito:mockito-core:5.10.0")
}

// ---- Python host-side tests ----

val testPythonRuntime by tasks.registering(Exec::class) {
    group = "verification"
    description = "Run runtime.py unit tests with python3"
    workingDir = file("${project.rootDir}")
    environment("PYTHONPATH", "agent-runtime/src/main/python")
    commandLine(
        "python3", "-m", "unittest", "discover",
        "-s", "agent-runtime/src/test/python",
        "-v",
    )
}

tasks.named("check") {
    dependsOn(testPythonRuntime)
}
