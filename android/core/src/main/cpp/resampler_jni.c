// JNI bridge to libsamplerate for spec section 5a. Built twice from the same source: by the NDK
// for the app (CMakeLists.txt) and by the host compiler for the JVM unit tests (build.gradle.kts),
// both with -ffp-contract=off.
#include <jni.h>
#include <stdlib.h>

#include "samplerate.h"

// Returns the generated interleaved frames, or null on any error (Kotlin turns that into
// RESAMPLE_FAILED).
JNIEXPORT jfloatArray JNICALL
Java_com_iamjarl_itschoppedyo_core_NativeResampler_simple(
    JNIEnv *env, jclass cls, jfloatArray input, jint frames, jint channels, jdouble ratio,
    jint capacity) {
    (void) cls;
    if (frames < 0 || channels < 1 || capacity < 0) return NULL;
    float *out = malloc(sizeof(float) * (size_t) capacity * (size_t) channels + 1);
    if (out == NULL) return NULL;
    jfloat *in = (*env)->GetFloatArrayElements(env, input, NULL);
    if (in == NULL) {
        free(out);
        return NULL;
    }

    SRC_DATA d = {0};
    d.data_in = in;
    d.data_out = out;
    d.input_frames = frames;
    d.output_frames = capacity;
    d.src_ratio = ratio;
    int err = src_simple(&d, SRC_SINC_BEST_QUALITY, channels);
    (*env)->ReleaseFloatArrayElements(env, input, in, JNI_ABORT);
    if (err != 0) {
        free(out);
        return NULL;
    }

    jsize n = (jsize) (d.output_frames_gen * channels);
    jfloatArray result = (*env)->NewFloatArray(env, n);
    if (result != NULL) (*env)->SetFloatArrayRegion(env, result, 0, n, out);
    free(out);
    return result;
}
