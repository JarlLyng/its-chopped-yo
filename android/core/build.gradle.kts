// The audio core for It's chopped, yo! on Android. Follows specs/audio-processing.md.
// No Android APIs inside: plain Kotlin, so the unit tests run on the JVM.
plugins {
    id("com.android.library")
}

android {
    namespace = "com.iamjarl.itschoppedyo.core"
    compileSdk = 36

    defaultConfig {
        minSdk = 29
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.all {
            // The tests read ../../testdata and write ../../testdata/out/kotlin.
            it.systemProperty("testdata.dir", rootProject.file("../testdata").absolutePath)
        }
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    // A real org.json for the JVM tests (Android's own is a stub there).
    testImplementation("org.json:json:20240303")
}
