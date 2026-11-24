import CryptoKit
import Foundation
import XCTest
@testable import ChoppedCore

/// Runs every case in testdata/cases.json and compares each output's SHA-256 with
/// testdata/expected.json, which tools/reference.py wrote. Outputs land in testdata/out/swift/.
final class SpecCasesTests: XCTestCase {
    static let repoRoot = URL(fileURLWithPath: #filePath)
        .deletingLastPathComponent()  // ChoppedCoreTests
        .deletingLastPathComponent()  // Tests
        .deletingLastPathComponent()  // ChoppedCore
        .deletingLastPathComponent()  // ios
        .deletingLastPathComponent()  // repo root
    static let testdata = repoRoot.appendingPathComponent("testdata")

    func testEveryCaseMatchesTheReference() throws {
        let cases = try json("cases.json")["cases"] as! [[String: Any]]
        let expected = try json("expected.json")["outputs"] as! [String: [String: String]]
        let outDir = Self.testdata.appendingPathComponent("out/swift")
        try FileManager.default.createDirectory(at: outDir, withIntermediateDirectories: true)

        XCTAssertFalse(cases.isEmpty)
        var checked = 0
        for c in cases {
            let id = c["id"] as! String
            let outputs = try run(c)
            let want = expected[id] ?? [:]
            XCTAssertEqual(Set(outputs.keys), Set(want.keys), "\(id): output file names")
            for (name, bytes) in outputs {
                try Data(bytes).write(to: outDir.appendingPathComponent(name))
                let hash = SHA256.hash(data: Data(bytes)).map { String(format: "%02x", $0) }.joined()
                XCTAssertEqual(hash, want[name], "\(id): \(name) differs from the reference")
                checked += 1
            }
        }
        XCTAssertEqual(checked, expected.values.reduce(0) { $0 + $1.count })
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

    // MARK: Helpers

    private func json(_ name: String) throws -> [String: Any] {
        let data = try Data(contentsOf: Self.testdata.appendingPathComponent(name))
        return try JSONSerialization.jsonObject(with: data) as! [String: Any]
    }

    private func input(_ name: String) throws -> Audio {
        let url = Self.testdata.appendingPathComponent("input").appendingPathComponent(name)
        return try AudioFile.read(Array(try Data(contentsOf: url)))
    }

    private func settings(_ d: [String: Any]) -> Settings {
        var s = Settings()
        s.mono = MonoMode(rawValue: d["mono"] as? String ?? "keep")!
        if let t = d["trim"] as? [Int] { s.trim = (t[0], t[1]) }
        s.fadeIn = fade(d["fadeIn"])
        s.fadeOut = fade(d["fadeOut"])
        s.sampleRate = d["sampleRate"] as? Int
        s.normalizeDB = (d["normalize"] as? NSNumber)?.doubleValue
        s.bits = d["bits"] as! Int
        s.dither = d["dither"] as? Bool ?? false
        s.format = OutputFormat(rawValue: d["format"] as? String ?? "wav")!
        return s
    }

    private func fade(_ any: Any?) -> Fade? {
        guard let d = any as? [String: Any] else { return nil }
        return Fade(frames: d["frames"] as! Int, curve: FadeCurve(rawValue: d["curve"] as! String)!)
    }

    private func run(_ c: [String: Any]) throws -> [String: [UInt8]] {
        let id = c["id"] as! String
        let s = settings(c["settings"] as! [String: Any])
        let inputs = c["inputs"] as! [String]
        let ext = AudioFile.fileExtension(s.format)
        switch c["mode"] as? String ?? "single" {
        case "single":
            let a = try Processing.process(try input(inputs[0]), s)
            return ["\(id).\(ext)": try Processing.encode(a, s)]
        case "slices":
            let a = try Processing.process(try input(inputs[0]), s)
            let parts = try Processing.slices(a, count: c["slices"] as! Int)
            var out: [String: [UInt8]] = [:]
            for (k, p) in parts.enumerated() {
                out[String(format: "%@_%02d.%@", id, k + 1, ext)] = try Processing.encode(p, s)
            }
            return out
        case "chain":
            let items = try inputs.map { try Processing.process(try input($0), s) }
            let chained = try Processing.chain(items, slots: c["slots"] as? Int)
            return ["\(id).\(ext)": try Processing.encode(chained, s)]
        default:
            XCTFail("unknown mode in \(id)")
            return [:]
        }
    }
}
