plugins {
    id("com.android.library")
}

android {
    namespace = "com.niki914.zafiro.accessibility"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    api(project(":business:screen"))
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")

    implementation(project(":business:api"))
    implementation(project(":libs:logging"))
    implementation(project(":libs:libterm-runtime"))

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    testImplementation("org.mockito:mockito-core:5.10.0")
}
