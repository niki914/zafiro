// autonomy:android — Android shell of the scheduler: Room store, AlarmManager projection, receivers,
// foreground service, run executor, approvals, notifications. No UI here: the Tasks screens live in the app module and use
// Zafiro's own design system.
// Pure scheduling logic lives in :autonomy:core.
plugins {
    id("com.android.library")
    id("com.google.devtools.ksp")
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

android {
    namespace = "com.tricreta.zafiro.autonomy"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    lint { checkReleaseBuilds = false }
}

dependencies {
    api(project(":autonomy:core"))
    implementation(project(":business:api"))
    implementation(project(":business:permission"))
    implementation(project(":libs:logging"))

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("androidx.core:core-ktx:1.15.0")

    implementation("androidx.room:room-runtime:2.7.2")
    implementation("androidx.room:room-ktx:2.7.2")
    ksp("androidx.room:room-compiler:2.7.2")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit:2.2.10")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    testImplementation("androidx.room:room-testing:2.7.2")
    testImplementation("org.robolectric:robolectric:4.15.1")
    testImplementation("androidx.test:core:1.6.1")
}
