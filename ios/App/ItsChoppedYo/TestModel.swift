import ChoppedCore
import ChoppedSpecRunner
import Foundation
import Observation
import UIKit

/// The state behind the test screen. Processing runs off the main thread; results come back to it.
@Observable
@MainActor
final class TestModel {
    static let rates = [22050, 32000, 44100, 46875, 48000, 96000]
    static let normalizeTargets: [Double] = [0.0, -0.1, -0.3, -0.5, -1.0, -3.0, -6.0]

    // Self-test
    var selfTestRunning = false
    var selfTestSummary: String?
    var selfTestPassed = false
    var selfTestProblems: [String] = []

    // Input
    var input: Audio?
    var inputName = ""
    var inputInfo: String?
    var error: String?

    // Settings
    var mono: MonoMode = .keep
    var sampleRate = 0
    var bits = 16
    var dither = true
    var normalizeIndex = -1
    var fadeInMs = 0
    var fadeOutMs = 0
    var equalPower = false
    var slices = 1
    var format: OutputFormat = .wav

    // Output
    var processing = false
    var outputSummary: String?
    var outputURLs: [URL] = []

    // MARK: Self-test

    func runSelfTest() {
        selfTestRunning = true
        selfTestSummary = nil
        selfTestProblems = []
        Task.detached(priority: .userInitiated) {
            let started = Date()
            let outcome: (String, Bool, [String])
            do {
                let r = try SpecRunner.runAll(
                    casesJSON: try Self.resource("cases.json"),
                    expectedJSON: try Self.resource("expected.json"),
                    readInput: { Array(try Self.resource($0)) }
                )
                let seconds = Date().timeIntervalSince(started)
                let device = await "\(UIDevice.current.model), iOS \(UIDevice.current.systemVersion)"
                let line = String(format: "%d of %d files match the reference (%@, %.1f s)",
                                  r.checked - r.mismatches.count, r.expectedTotal, device, seconds)
                outcome = (line, r.passed, r.nameProblems + r.mismatches)
            } catch {
                outcome = ("The self-test stopped: \(error)", false, [])
            }
            await MainActor.run {
                self.selfTestSummary = outcome.0
                self.selfTestPassed = outcome.1
                self.selfTestProblems = outcome.2
                self.selfTestRunning = false
            }
        }
    }

    nonisolated private static func resource(_ name: String) throws -> Data {
        let parts = name.split(separator: ".", maxSplits: 1).map(String.init)
        guard let url = Bundle.main.url(forResource: parts[0], withExtension: parts.count > 1 ? parts[1] : nil) else {
            throw CocoaError(.fileNoSuchFile, userInfo: [NSFilePathErrorKey: name])
        }
        return try Data(contentsOf: url)
    }

    // MARK: Input

    func load(_ url: URL) {
        error = nil
        outputSummary = nil
        outputURLs = []
        let scoped = url.startAccessingSecurityScopedResource()
        defer { if scoped { url.stopAccessingSecurityScopedResource() } }
        do {
            let audio = try AudioFile.read(Array(try Data(contentsOf: url)))
            input = audio
            inputName = url.deletingPathExtension().lastPathComponent
            inputInfo = Self.describe(audio)
        } catch {
            input = nil
            inputInfo = nil
            self.error = "Could not read \(url.lastPathComponent): \(error)"
        }
    }

    nonisolated static func describe(_ a: Audio) -> String {
        var peak = 0.0
        for ch in a.channels { for x in ch { peak = max(peak, abs(x)) } }
        let seconds = Double(a.frameCount) / Double(max(a.sampleRate, 1))
        // Display only: log10 never touches the audio path.
        let peakText = peak > 0 ? String(format: "%.1f dBFS", 20 * log10(peak)) : "silent"
        return String(format: "%d Hz, %d ch, %d frames (%.2f s), peak %@",
                      a.sampleRate, a.channels.count, a.frameCount, seconds, peakText)
    }

    // MARK: Process

    var settings: Settings {
        var s = Settings()
        s.mono = mono
        s.sampleRate = sampleRate == 0 ? nil : sampleRate
        s.bits = bits
        s.dither = dither
        s.normalizeDB = normalizeIndex >= 0 ? Self.normalizeTargets[normalizeIndex] : nil
        s.format = format
        let rate = input?.sampleRate ?? 48000
        let curve: FadeCurve = equalPower ? .equalPower : .linear
        if fadeInMs > 0 { s.fadeIn = Fade(frames: fadeInMs * rate / 1000, curve: curve) }
        if fadeOutMs > 0 { s.fadeOut = Fade(frames: fadeOutMs * rate / 1000, curve: curve) }
        return s
    }

    func process() {
        guard let input else { return }
        processing = true
        error = nil
        let s = settings
        let count = slices
        let name = inputName
        Task.detached(priority: .userInitiated) {
            let result: (urls: [URL], summary: String?, failure: String?)
            do {
                let a = try Processing.process(input, s)
                let dir = FileManager.default.temporaryDirectory.appendingPathComponent("chopped", isDirectory: true)
                try? FileManager.default.removeItem(at: dir)
                try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
                let ext = AudioFile.fileExtension(s.format)
                let parts = count > 1 ? try Processing.slices(a, count: count) : [a]
                var urls: [URL] = []
                for (k, part) in parts.enumerated() {
                    let file = count > 1 ? String(format: "%@_%02d.%@", name, k + 1, ext) : "\(name)_chopped.\(ext)"
                    let url = dir.appendingPathComponent(file)
                    try Data(try Processing.encode(part, s)).write(to: url)
                    urls.append(url)
                }
                result = (urls, "\(parts.count) file\(parts.count == 1 ? "" : "s"): " + Self.describe(a), nil)
            } catch {
                result = ([], nil, "Could not process: \(error)")
            }
            await MainActor.run {
                self.outputURLs = result.urls
                self.outputSummary = result.summary
                self.error = result.failure
                self.processing = false
            }
        }
    }
}
