package com.iamjarl.itschoppedyo.core

/**
 * libsamplerate through JNI (src/main/cpp/resampler_jni.c). In the app the library is the NDK
 * build, `libchoppedcore.so`. The JVM unit tests point the system property `chopped.native.lib` at a
 * host build of the same C code.
 */
internal object NativeResampler {
    init {
        val path = System.getProperty("chopped.native.lib")
        if (path != null) System.load(path) else System.loadLibrary("choppedcore")
    }

    /** `src_simple` with SRC_SINC_BEST_QUALITY. Interleaved frames in and out; null on error. */
    @JvmStatic
    external fun simple(input: FloatArray, frames: Int, channels: Int, ratio: Double, capacity: Int): FloatArray?
}
