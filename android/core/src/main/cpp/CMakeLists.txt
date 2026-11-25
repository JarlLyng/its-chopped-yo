# libsamplerate and the JNI bridge for the app. The flags match every other build of
# third_party/libsamplerate (its README.md): no fused multiply-add, the same bytes everywhere.
cmake_minimum_required(VERSION 3.22.1)
project(choppedcore C)

set(LSR ${CMAKE_CURRENT_SOURCE_DIR}/../../../../../third_party/libsamplerate)

add_library(choppedcore SHARED
    resampler_jni.c
    ${LSR}/src/samplerate.c
    ${LSR}/src/src_sinc.c
    ${LSR}/src/src_linear.c
    ${LSR}/src/src_zoh.c)

target_include_directories(choppedcore PRIVATE ${LSR}/include ${LSR}/src)
target_compile_definitions(choppedcore PRIVATE
    ENABLE_SINC_BEST_CONVERTER ENABLE_SINC_MEDIUM_CONVERTER ENABLE_SINC_FAST_CONVERTER
    HAVE_STDBOOL_H=1 PACKAGE="libsamplerate" VERSION="0.2.2"
    CPU_CLIPS_POSITIVE=0 CPU_CLIPS_NEGATIVE=0)
target_compile_options(choppedcore PRIVATE -O2 -ffp-contract=off)
# 16 KB pages, which Google Play asks of apps that target Android 15 and later.
target_link_options(choppedcore PRIVATE "-Wl,-z,max-page-size=16384")
target_link_libraries(choppedcore m)
