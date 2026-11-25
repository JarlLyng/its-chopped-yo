import Foundation
import XCTest
@testable import ChoppedCore
import ChoppedSpecRunner

/// Every case in testdata/cases.json against testdata/expected.json, which tools/reference.py
/// wrote (SpecRunner does the work; the test app's self-test runs the same code). Outputs land in
/// testdata/out/swift/.
final class SpecCasesTests: XCTestCase {
    static let repoRoot = URL(fileURLWithPath: #filePath)
        .deletingLastPathComponent()  // ChoppedCoreTests
        .deletingLastPathComponent()  // Tests
        .deletingLastPathComponent()  // ChoppedCore
        .deletingLastPathComponent()  // ios
        .deletingLastPathComponent()  // repo root
    static let testdata = repoRoot.appendingPathComponent("testdata")

    func testEveryCaseMatchesTheReference() throws {
        let outDir = Self.testdata.appendingPathComponent("out/swift")
        try FileManager.default.createDirectory(at: outDir, withIntermediateDirectories: true)
        let r = try SpecRunner.runAll(
            casesJSON: try Data(contentsOf: Self.testdata.appendingPathComponent("cases.json")),
            expectedJSON: try Data(contentsOf: Self.testdata.appendingPathComponent("expected.json")),
            readInput: { name in
                Array(try Data(contentsOf: Self.testdata.appendingPathComponent("input").appendingPathComponent(name)))
            },
            onOutput: { name, bytes in try Data(bytes).write(to: outDir.appendingPathComponent(name)) }
        )
        XCTAssertGreaterThan(r.checked, 0)
        XCTAssertEqual(r.nameProblems, [], "output file names")
        XCTAssertEqual(r.mismatches, [], "files differing from the reference")
        XCTAssertEqual(r.checked, r.expectedTotal)
        XCTAssertTrue(r.passed)
    }

    func testRightPannedSoundSurvivesTheMonoSum() throws {
        let a = Audio(sampleRate: 48000, channels: [[0.0, 0.0], [0.5, -0.5]])
        let m = Processing.applyChannels(a, .sum)
        XCTAssertEqual(m.channels, [[0.25, -0.25]])
    }

    func testErrorsFollowTheSpec() throws {
        let a = Audio(sampleRate: 48000, channels: [[0.1, 0.2, 0.3]])
        XCTAssertThrowsError(try Processing.trim(a, (2, 2))) { XCTAssertEqual($0 as? ChoppedError, .emptyAfterTrim) }
        XCTAssertThrowsError(try Processing.normalize(a, -0.2)) { XCTAssertEqual($0 as? ChoppedError, .unknownNormalizeTarget) }
        XCTAssertThrowsError(try Processing.quantize(a, bits: 20, dither: false)) { XCTAssertEqual($0 as? ChoppedError, .badBits) }
        XCTAssertThrowsError(try Processing.sliceBounds(length: 3, count: 4)) { XCTAssertEqual($0 as? ChoppedError, .fewerFramesThanSlices) }
        XCTAssertThrowsError(try Wav.read(Array("RIFX....WAVE".utf8))) { XCTAssertEqual($0 as? ChoppedError, .notWave) }
        XCTAssertThrowsError(try Processing.resample(a, to: 48000 * 300)) { XCTAssertEqual($0 as? ChoppedError, .badRatio) }
    }

    func testSampleRatesSurviveTheExtendedFloat() throws {
        for r in [1, 8000, 11025, 22050, 44100, 46875, 48000, 96000, 192000, 384000] {
            XCTAssertEqual(try Aiff.extendedToRate(Aiff.rateToExtended(r)), r)
        }
        // One lower exponent halves the rate: 88201 / 2 = 44100.5, not a whole number of hertz.
        var x = Aiff.rateToExtended(88201)
        x[1] = x[1] - 1
        XCTAssertThrowsError(try Aiff.extendedToRate(x)) { XCTAssertEqual($0 as? ChoppedError, .unsupportedSampleRate) }
    }
}
