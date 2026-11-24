# It's chopped, yo!

Sample prep for hardware samplers, on the phone. Record or import a sound, trim it, fade it,
normalize it, sum it to mono properly (both channels, not just the left one) and export it as WAV or
AIFF at the sample rate and bit depth your machine wants. Whole folders at once, equal slices and sample chains
are part of the plan too.

It's built for iPhone and Android as two native apps, SwiftUI on iOS and Kotlin with Jetpack Compose
on Android. Both follow one written spec for the audio processing and run the same test files, so a
file prepared on either phone comes out byte for byte the same. Resampling uses
[libsamplerate](https://github.com/libsndfile/libsamplerate), compiled from the same source on both.

Everything happens on the phone. No account, no cloud, no ads and no tracking. It will be a one-time
purchase, no subscription.

**Status:** in development. Nothing is in the App Store or on Google Play yet.

## What's in the repo

| Folder | What it is |
|---|---|
| `specs/` | The audio processing spec, the source of truth for both apps |
| `testdata/` | Generated input files, expected output files and their checksums |
| `tools/` | The generator and the reference implementation of the spec (Python, standard library only) |
| `ios/` | The Swift side: the `ChoppedCore` sources and tests (the package is `Package.swift` at the root) |
| `android/` | The Kotlin side: the `core` module with the audio core, its JNI bridge and its tests |
| `third_party/` | libsamplerate 0.2.2 (BSD-2-Clause), the resampler both platforms compile from the same source |

## Run the tests

Regenerate the test files (only needed when the spec changes):

```bash
python3 tools/make_testdata.py
```

The Swift core, on the Mac, from the repo root:

```bash
swift test
```

The Kotlin core, on the Mac (JDK 17 and the Android SDK):

```bash
cd android && ./gradlew :core:testDebugUnitTest
```

The Kotlin core on an Android emulator or phone (Android's runtime and the NDK build):

```bash
cd android && ./gradlew :core:connectedDebugAndroidTest
```

All of them read `testdata/cases.json`, run every case and compare the SHA-256 of each output file
with `testdata/expected.json`. CI runs both, and rebuilds the expected files on Linux to check
that they come out the same on another CPU architecture.

## Related apps

From the same maker: [It's mono, yo!](https://itsmonoyo.iamjarl.com) (stereo to mono on the Mac) and
[TonVault](https://tonvault.iamjarl.com) (a companion for a sampler's SD card on the Mac).

It's chopped, yo! is an independent app and is not affiliated with any hardware maker.

## License

[MIT](LICENSE). Made by [IAMJARL](https://iamjarl.com).
