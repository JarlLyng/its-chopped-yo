// The audio core for It's chopped, yo! on Android. Follows specs/audio-processing.md.
// Plain Kotlin plus libsamplerate through JNI. The unit tests run on the JVM against a host build
// of the same C code, so CI needs no phone or emulator.
plugins {
    id("com.android.library")
}

android {
    namespace = "com.iamjarl.itschoppedyo.core"
    compileSdk = 36
    ndkVersion = "27.1.12297006"

    defaultConfig {
        minSdk = 29
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    // A real org.json for the JVM tests (Android's own is a stub there).
    testImplementation("org.json:json:20240303")
}

// ---- The host build of libsamplerate and the JNI bridge, for the JVM unit tests

val isMac = System.getProperty("os.name").lowercase().contains("mac")
val lsrDir = rootProject.file("../third_party/libsamplerate")
val jniSource = file("src/main/cpp/resampler_jni.c")
val hostLib = layout.buildDirectory.file("host-native/libchoppedcore" + if (isMac) ".dylib" else ".so")

val buildHostNative by tasks.registering(Exec::class) {
    description = "Builds libsamplerate and the JNI bridge for this machine, for the unit tests."
    val javaHome = System.getProperty("java.home")
    inputs.dir(File(lsrDir, "src"))
    inputs.dir(File(lsrDir, "include"))
    inputs.file(jniSource)
    outputs.file(hostLib)
    doFirst { hostLib.get().asFile.parentFile.mkdirs() }
    commandLine(
        listOf(
            "cc", "-shared", "-fPIC",
            // The same flags as every other build (third_party/libsamplerate/README.md).
            "-O2", "-ffp-contract=off",
            "-DENABLE_SINC_BEST_CONVERTER", "-DENABLE_SINC_MEDIUM_CONVERTER", "-DENABLE_SINC_FAST_CONVERTER",
            "-DHAVE_STDBOOL_H=1", "-DPACKAGE=\"libsamplerate\"", "-DVERSION=\"0.2.2\"",
            "-DCPU_CLIPS_POSITIVE=0", "-DCPU_CLIPS_NEGATIVE=0",
            "-I$javaHome/include", "-I$javaHome/include/" + if (isMac) "darwin" else "linux",
            "-I${lsrDir}/include", "-I${lsrDir}/src",
            jniSource.path,
            "$lsrDir/src/samplerate.c", "$lsrDir/src/src_sinc.c",
            "$lsrDir/src/src_linear.c", "$lsrDir/src/src_zoh.c",
            "-lm", "-o", hostLib.get().asFile.path,
        )
    )
}

tasks.withType<Test>().configureEach {
    dependsOn(buildHostNative)
    // The tests read ../../testdata and write ../../testdata/out/kotlin.
    systemProperty("testdata.dir", rootProject.file("../testdata").absolutePath)
    systemProperty("chopped.native.lib", hostLib.get().asFile.absolutePath)
}
