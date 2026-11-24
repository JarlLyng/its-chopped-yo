// swift-tools-version: 5.9
// The audio core for It's chopped, yo! on iOS. Follows specs/audio-processing.md.
// The package sits at the repo root so it can compile third_party/libsamplerate, which the
// Android build compiles too.
import PackageDescription

let package = Package(
    name: "ChoppedCore",
    platforms: [.iOS(.v17), .macOS(.v14)],
    products: [
        .library(name: "ChoppedCore", targets: ["ChoppedCore"]),
    ],
    targets: [
        // libsamplerate 0.2.2, unchanged. The flags match every other build of it
        // (third_party/libsamplerate/README.md): no fused multiply-add, the same bytes everywhere.
        .target(
            name: "CSamplerate",
            path: "third_party/libsamplerate",
            exclude: ["COPYING", "README.md"],
            sources: ["src"],
            publicHeadersPath: "include",
            cSettings: [
                .define("ENABLE_SINC_BEST_CONVERTER"),
                .define("ENABLE_SINC_MEDIUM_CONVERTER"),
                .define("ENABLE_SINC_FAST_CONVERTER"),
                .define("HAVE_STDBOOL_H", to: "1"),
                .define("PACKAGE", to: "\"libsamplerate\""),
                .define("VERSION", to: "\"0.2.2\""),
                .define("CPU_CLIPS_POSITIVE", to: "0"),
                .define("CPU_CLIPS_NEGATIVE", to: "0"),
                .unsafeFlags(["-O2", "-ffp-contract=off"]),
            ]
        ),
        .target(
            name: "ChoppedCore",
            dependencies: ["CSamplerate"],
            path: "ios/ChoppedCore/Sources/ChoppedCore"
        ),
        .testTarget(
            name: "ChoppedCoreTests",
            dependencies: ["ChoppedCore"],
            path: "ios/ChoppedCore/Tests/ChoppedCoreTests"
        ),
    ]
)
