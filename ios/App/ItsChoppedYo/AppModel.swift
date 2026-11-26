import AVFoundation
import ChoppedCore
import Foundation
import Observation
import SwiftUI
import UniformTypeIdentifiers

/// Where the navigation stack can go.
enum Route: Hashable {
    case file, presets, saved, settings, selfTest
}

/// Everything Pro adds. The free app is one file, a built-in preset, then save or share
/// (the owner's decision, hub `ItsChoppedYo/screen-flow.md`); every one of these opens the Pro sheet.
enum ProFeature: String, CaseIterable, Identifiable {
    case trim, fade, normalize, slices, record, batch, chain, customPreset
    var id: String { rawValue }

    /// The label on its key.
    var key: String {
        switch self {
        case .record: return "REC"
        case .batch: return "BATCH"
        case .chain: return "CHAIN"
        case .customPreset: return "PRESETS"
        case .trim: return "TRIM"
        case .fade: return "FADE"
        case .normalize: return "NORM"
        case .slices: return "SLICE"
        }
    }

    var title: String {
        switch self {
        case .record: return "Record"
        case .batch: return "Batch a folder"
        case .chain: return "Sample chains"
        case .customPreset: return "Your own presets"
        case .trim: return "Trim"
        case .fade: return "Fade in and out"
        case .normalize: return "Normalize"
        case .slices: return "Slices"
        }
    }

    var detail: String {
        switch self {
        case .record: return "From the microphone or a USB audio interface."
        case .batch: return "A whole folder or many files in, one folder out, the same preset for all."
        case .chain: return "Several files joined in equal slots, for samplers that slice evenly."
        case .customPreset: return "Any sample rate, bit depth, channels and WAV or AIFF, saved on this phone."
        case .trim: return "Cut the start and the end, snapped to a zero crossing."
        case .fade: return "Linear or equal power, in milliseconds."
        case .normalize: return "Set the peak to a fixed level."
        case .slices: return "Cut a file into 2 to 64 equal parts, saved as numbered files."
        }
    }
}

/// A file the user opened.
struct LoadedFile {
    var name: String   // without the extension
    var audio: Audio
}

/// The processed result for the current file and preset.
struct Output {
    var audio: Audio
    var url: URL                  // a temporary file with the final name, for Share and playback
    var data: Data
    var waveform: [ClosedRange<Double>]
}

/// The app's state. Processing runs off the main thread; results come back here.
@Observable
@MainActor
final class AppModel {
    let presets: [Preset]
    var path: [Route] = []

    var presetID: String {
        didSet { UserDefaults.standard.set(presetID, forKey: "presetID"); reprocess() }
    }
    var dither: Bool {
        didSet { UserDefaults.standard.set(dither, forKey: "dither"); reprocess() }
    }

    var file: LoadedFile?
    var output: Output?
    var working = false
    var error: String?
    var note: String?
    var saved: String?        // the saved file's name
    var pro: ProFeature?
    var playing = false

    private var generation = 0
    private var player: AVAudioPlayer?
    private let playerDelegate = PlayerDelegate()

    init() {
        presets = Self.loadPresets()
        let stored = UserDefaults.standard.string(forKey: "presetID")
        presetID = presets.first { $0.id == stored }?.id ?? presets.first?.id ?? ""
        dither = UserDefaults.standard.object(forKey: "dither") as? Bool ?? true
        playerDelegate.onFinish = { [weak self] in Task { @MainActor in self?.playing = false } }
    }

    var preset: Preset? { presets.first { $0.id == presetID } }

    private static func loadPresets() -> [Preset] {
        guard let url = Bundle.main.url(forResource: "presets", withExtension: "json"),
              let data = try? Data(contentsOf: url),
              let list = try? Presets.parse(data) else { return [] }
        return list
    }

    // MARK: Input

    /// Opens the first of the picked files. More than one at a time is Pro (batch).
    func open(_ urls: [URL]) {
        guard let first = urls.first else { return }
        load(first)
        if urls.count > 1, file != nil {
            note = "Opened \(first.lastPathComponent). More than one file at a time is part of Pro."
        }
    }

    func load(_ url: URL) {
        stop()
        error = nil
        note = nil
        saved = nil
        output = nil
        let scoped = url.startAccessingSecurityScopedResource()
        defer { if scoped { url.stopAccessingSecurityScopedResource() } }
        do {
            let audio = try AudioFile.read(Array(try Data(contentsOf: url)))
            file = LoadedFile(name: url.deletingPathExtension().lastPathComponent, audio: audio)
            path = [.file]
            reprocess()
        } catch {
            file = nil
            self.error = Self.readError(url.lastPathComponent, error)
        }
    }

    nonisolated static func readError(_ name: String, _ error: Error) -> String {
        switch error as? ChoppedError {
        case .notWave, .notAiff, .noData, .badFormat, .dataBeforeFmt:
            return "\(name) is not a WAV or AIFF file this app can read."
        case .unsupportedFormat, .unsupportedSampleRate:
            return "\(name) uses an encoding this app does not read yet."
        default:
            return "Could not open \(name)."
        }
    }

    // MARK: Process

    /// Runs the current preset on the current file. A newer run wins over an older one.
    func reprocess() {
        guard let file, let preset else { return }
        stop()
        saved = nil
        generation += 1
        let run = generation
        let settings = preset.settings(dither: dither)
        working = true
        Task.detached(priority: .userInitiated) {
            let result: Result<Output, Error>
            do {
                let audio = try Processing.process(file.audio, settings)
                let data = Data(try Processing.encode(audio, settings))
                let dir = FileManager.default.temporaryDirectory.appendingPathComponent("out", isDirectory: true)
                try? FileManager.default.removeItem(at: dir)
                try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
                let url = dir.appendingPathComponent("\(file.name).\(AudioFile.fileExtension(settings.format))")
                try data.write(to: url)
                result = .success(Output(audio: audio, url: url, data: data, waveform: Waveform.buckets(audio, count: 160)))
            } catch {
                result = .failure(error)
            }
            await MainActor.run {
                guard run == self.generation else { return }
                self.working = false
                switch result {
                case .success(let o): self.output = o; self.error = nil
                case .failure: self.output = nil; self.error = "Could not convert this file."
                }
            }
        }
    }

    /// "→ 48 kHz · 16-bit · mono", what the saved file will be.
    var afterText: String? {
        guard let output, let preset else { return nil }
        return "\(Describe.rate(output.audio.sampleRate)) · \(preset.bits)-bit · \(Describe.channels(output.audio.channels.count)) · \(preset.format == .aiff ? "AIFF" : "WAV")"
    }

    var exportType: UTType { preset?.format == .aiff ? .aiff : .wav }

    // MARK: Play

    func togglePlay() {
        if playing { stop(); return }
        guard let output else { return }
        do {
            try AVAudioSession.sharedInstance().setCategory(.playback)
            try AVAudioSession.sharedInstance().setActive(true)
            let p = try AVAudioPlayer(contentsOf: output.url)
            p.delegate = playerDelegate
            p.play()
            player = p
            playing = true
        } catch {
            self.error = "Could not play the file."
        }
    }

    func stop() {
        player?.stop()
        player = nil
        playing = false
    }

    /// How far playback has come, 0 to 1, for the playhead on the display.
    func progress() -> Double {
        guard let player, player.duration > 0 else { return 0 }
        return player.currentTime / player.duration
    }

    // MARK: Save

    func didSave(_ result: Result<URL, Error>) {
        switch result {
        case .success(let url):
            saved = url.lastPathComponent
            if path.last != .saved { path.append(.saved) }
        case .failure(let e):
            if (e as NSError).code != NSUserCancelledError { error = "Could not save the file." }
        }
    }

    func newFile() {
        stop()
        file = nil
        output = nil
        saved = nil
        note = nil
        error = nil
        path = []
    }
}

private final class PlayerDelegate: NSObject, AVAudioPlayerDelegate {
    var onFinish: (() -> Void)?
    func audioPlayerDidFinishPlaying(_ player: AVAudioPlayer, successfully flag: Bool) { onFinish?() }
}

/// The min and max of each slice of the file, for drawing. Display only.
enum Waveform {
    static func buckets(_ a: Audio, count: Int) -> [ClosedRange<Double>] {
        let n = a.frameCount
        guard n > 0, count > 0 else { return [] }
        return (0..<count).map { b in
            let start = b * n / count
            let end = max(start + 1, (b + 1) * n / count)
            var lo = 0.0, hi = 0.0
            for ch in a.channels {
                for i in start..<min(end, n) {
                    lo = min(lo, ch[i]); hi = max(hi, ch[i])
                }
            }
            return max(-1, lo)...min(1, hi)
        }
    }
}

/// The saved file, for the system's "save to a folder" sheet.
struct AudioDocument: FileDocument {
    static var readableContentTypes: [UTType] { [.wav, .aiff] }
    var data: Data
    init(data: Data) { self.data = data }
    init(configuration: ReadConfiguration) throws { data = configuration.file.regularFileContents ?? Data() }
    func fileWrapper(configuration: WriteConfiguration) throws -> FileWrapper { FileWrapper(regularFileWithContents: data) }
}
