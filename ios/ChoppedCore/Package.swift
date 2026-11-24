// swift-tools-version: 5.9
// The audio core for It's chopped, yo! on iOS. Follows specs/audio-processing.md.
import PackageDescription

let package = Package(
    name: "ChoppedCore",
    platforms: [.iOS(.v17), .macOS(.v14)],
    products: [
        .library(name: "ChoppedCore", targets: ["ChoppedCore"]),
    ],
    targets: [
        .target(name: "ChoppedCore"),
        .testTarget(name: "ChoppedCoreTests", dependencies: ["ChoppedCore"]),
    ]
)
