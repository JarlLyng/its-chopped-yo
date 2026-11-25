import ChoppedCore
import CryptoKit
import Foundation

/// The result of running every case in testdata/cases.json.
public struct SpecRunResult {
    public var checked = 0
    public var expectedTotal = 0
    public var mismatches: [String] = []
    public var nameProblems: [String] = []

    public var passed: Bool { checked > 0 && checked == expectedTotal && mismatches.isEmpty && nameProblems.isEmpty }
}

/// Runs every case in testdata/cases.json and compares each output's SHA-256 with
/// testdata/expected.json. Shared by the XCTest suite and the test app's self-test, so both run
/// exactly the same thing; only where the files come from differs.
public enum SpecRunner {
    public static func runAll(
        casesJSON: Data,
        expectedJSON: Data,
        readInput: (String) throws -> [UInt8],
        onOutput: (String, [UInt8]) throws -> Void = { _, _ in }
    ) throws -> SpecRunResult {
        let cases = (try JSONSerialization.jsonObject(with: casesJSON) as? [String: Any])?["cases"] as? [[String: Any]] ?? []
        let expected = (try JSONSerialization.jsonObject(with: expectedJSON) as? [String: Any])?["outputs"] as? [String: [String: String]] ?? [:]
        var result = SpecRunResult()
        result.expectedTotal = expected.values.reduce(0) { $0 + $1.count }
        for c in cases {
            let id = c["id"] as? String ?? "?"
            let outputs = try run(c, readInput: readInput)
            let want = expected[id] ?? [:]
            if Set(outputs.keys) != Set(want.keys) {
                result.nameProblems.append("\(id): \(outputs.keys.sorted()) != \(want.keys.sorted())")
            }
            for name in outputs.keys.sorted() {
                let bytes = outputs[name]!
                try onOutput(name, bytes)
                if sha256(bytes) != want[name] { result.mismatches.append("\(id): \(name)") }
                result.checked += 1
            }
        }
        return result
    }

    public static func sha256(_ bytes: [UInt8]) -> String {
        SHA256.hash(data: Data(bytes)).map { String(format: "%02x", $0) }.joined()
    }

    public static func settings(_ d: [String: Any]) -> Settings {
        var s = Settings()
        s.mono = MonoMode(rawValue: d["mono"] as? String ?? "keep") ?? .keep
        if let t = d["trim"] as? [Int], t.count == 2 { s.trim = (t[0], t[1]) }
        s.fadeIn = fade(d["fadeIn"])
        s.fadeOut = fade(d["fadeOut"])
        s.sampleRate = d["sampleRate"] as? Int
        s.normalizeDB = (d["normalize"] as? NSNumber)?.doubleValue
        s.bits = d["bits"] as? Int ?? 16
        s.dither = d["dither"] as? Bool ?? false
        s.format = OutputFormat(rawValue: d["format"] as? String ?? "wav") ?? .wav
        return s
    }

    private static func fade(_ any: Any?) -> Fade? {
        guard let d = any as? [String: Any], let frames = d["frames"] as? Int,
              let curve = FadeCurve(rawValue: d["curve"] as? String ?? "") else { return nil }
        return Fade(frames: frames, curve: curve)
    }

    private static func run(_ c: [String: Any], readInput: (String) throws -> [UInt8]) throws -> [String: [UInt8]] {
        let id = c["id"] as? String ?? "?"
        let s = settings(c["settings"] as? [String: Any] ?? [:])
        let inputs = c["inputs"] as? [String] ?? []
        let ext = AudioFile.fileExtension(s.format)
        func input(_ name: String) throws -> Audio { try AudioFile.read(try readInput(name)) }
        switch c["mode"] as? String ?? "single" {
        case "slices":
            let a = try Processing.process(try input(inputs[0]), s)
            let parts = try Processing.slices(a, count: c["slices"] as? Int ?? 0)
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
            let a = try Processing.process(try input(inputs[0]), s)
            return ["\(id).\(ext)": try Processing.encode(a, s)]
        }
    }
}
