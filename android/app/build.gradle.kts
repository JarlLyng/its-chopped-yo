// The Android test app for It's chopped, yo!: a self-test of the audio core on the device, and a
// manual test of one file. The application ID is the proposed final one; it becomes permanent only
// with the first upload to Google Play, which waits for the owner.
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.iamjarl.itschoppedyo"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.iamjarl.itschoppedyo"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "0.1"
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    sourceSets {
        // The self-test runs the same case runner as the core's tests.
        getByName("main").kotlin.directories.add("../core/src/sharedTest/kotlin")
        // testdata/ (inputs, cases, expected hashes) goes into debug builds as assets/testdata/.
        getByName("debug").assets.directories.add("build/generated/testdata-assets")
    }
}

dependencies {
    implementation(project(":core"))
    val composeBom = platform("androidx.compose:compose-bom:2026.05.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.12.4")
    implementation("androidx.core:core-ktx:1.16.0")
}

val copyTestdataAssets by tasks.registering(Sync::class) {
    description = "Copies testdata/ (without outputs) into the debug app's assets for the self-test."
    from(rootProject.file("../testdata")) {
        include("cases.json", "expected.json", "input/**")
    }
    into(layout.buildDirectory.dir("generated/testdata-assets/testdata"))
}

tasks.matching { it.name.startsWith("merge") && it.name.endsWith("Assets") }.configureEach {
    dependsOn(copyTestdataAssets)
}
