# libsamplerate (vendored)

The resampler for both apps, so iOS and Android resample with the same code and write the same bytes.

- **Upstream:** https://github.com/libsndfile/libsamplerate
- **Version:** 0.2.2, tag `0.2.2`, commit `c96f5e3de9c4488f4e6c97f59f5245f22fda22f7`
- **License:** BSD-2-Clause, in `COPYING` (kept with the source, as the license asks)
- **What was copied:** `include/samplerate.h`, and from `src/` the files `samplerate.c`,
  `src_sinc.c`, `src_linear.c`, `src_zoh.c`, `common.h` and the three coefficient tables. Nothing
  was changed.

## How it is compiled (the same everywhere)

Every build of these files uses these flags, and no others that change floating-point results:

```
-O2 -ffp-contract=off
-DENABLE_SINC_BEST_CONVERTER -DENABLE_SINC_MEDIUM_CONVERTER -DENABLE_SINC_FAST_CONVERTER
-DHAVE_STDBOOL_H=1 -DPACKAGE="libsamplerate" -DVERSION="0.2.2"
-DCPU_CLIPS_POSITIVE=0 -DCPU_CLIPS_NEGATIVE=0
```

`-ffp-contract=off` stops the compiler from fusing a multiply and an add into one instruction, which
arm64 can do and older x86 can't. Fused and unfused results can differ in the last bit, so without it
an Android phone and the x86 emulator, or an iPhone and Linux CI, could write different files. It is
a safeguard, not a measured fix: on the Mac (arm64, 2026-11-25) a build with `-ffp-contract=fast`
gave the same output for a 44.1 to 48 kHz test, so clang fused nothing there. CI runs the same test
files on Linux x86_64, which is the check across architectures. Never build these files with
`-ffast-math`.

The builds: `Package.swift` (Swift package, iOS and the Mac tests), `android/core/src/main/cpp/CMakeLists.txt`
(Android NDK), `android/core/build.gradle.kts` (the host library for the JVM unit tests) and
`tools/native.py` (the host library for the Python reference).
