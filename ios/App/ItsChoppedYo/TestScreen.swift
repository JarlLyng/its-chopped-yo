import ChoppedCore
import ChoppedSpecRunner
import IAMJARLDesignTokens
import SwiftUI
import UniformTypeIdentifiers

/// One screen with three parts: the self-test (every case in testdata on this device), a file to
/// load, and the settings for processing it. Outputs are written to a temporary folder and shared
/// with the share sheet, which keeps their names ("Save to Files" picks the folder).
struct TestScreen: View {
    @State private var model = TestModel()
    @State private var importing = false

    var body: some View {
        NavigationStack {
            Form {
                selfTestSection
                fileSection
                if model.input != nil {
                    settingsSection
                    processSection
                }
            }
            .navigationTitle("It's chopped, yo!")
            .fileImporter(isPresented: $importing, allowedContentTypes: [.wav, .aiff, .audio]) { result in
                if case .success(let url) = result { model.load(url) }
            }
        }
    }

    /// A step of the token type scale, as a Dynamic Type style so it follows the system text size.
    static func font(_ style: DesignTokens.Typography.Style) -> Font {
        .system(style.textStyle)
    }

    // MARK: Self-test

    private var selfTestSection: some View {
        Section {
            Button(model.selfTestRunning ? "Running…" : "Run the self-test") { model.runSelfTest() }
                .disabled(model.selfTestRunning)
                .accessibilityIdentifier("selfTestButton")
            if let line = model.selfTestSummary {
                Text(line)
                    .foregroundStyle(model.selfTestPassed ? DesignTokens.Palette.State.success : DesignTokens.Palette.State.error)
                    .accessibilityIdentifier("selfTestResult")
            }
            ForEach(model.selfTestProblems, id: \.self) {
                Text($0).font(Self.font(.xs)).foregroundStyle(DesignTokens.Palette.State.error)
            }
        } header: {
            Text("Self-test")
        } footer: {
            Text("Runs every case in the test files on this device and compares each output's SHA-256 with the reference.")
                .font(Self.font(.xs))
                .foregroundStyle(DesignTokens.Palette.Text.tertiary)
        }
    }

    // MARK: File

    private var fileSection: some View {
        Section("File") {
            Button("Choose a WAV or AIFF file") { importing = true }
            if let info = model.inputInfo {
                Text(info).font(Self.font(.sm).monospaced())
            }
            if let error = model.error {
                Text(error).foregroundStyle(DesignTokens.Palette.State.error)
            }
        }
    }

    // MARK: Settings

    private var settingsSection: some View {
        Section("Settings") {
            Picker("Channels", selection: $model.mono) {
                Text("Keep").tag(MonoMode.keep)
                Text("Mono (sum)").tag(MonoMode.sum)
                Text("Left only").tag(MonoMode.left)
                Text("Right only").tag(MonoMode.right)
            }
            Picker("Sample rate", selection: $model.sampleRate) {
                Text("Keep").tag(0)
                ForEach(TestModel.rates, id: \.self) { Text("\($0) Hz").tag($0) }
            }
            Picker("Bit depth", selection: $model.bits) {
                Text("16-bit").tag(16)
                Text("24-bit").tag(24)
            }
            Toggle("Dither", isOn: $model.dither)
            Picker("Normalize", selection: $model.normalizeIndex) {
                Text("Off").tag(-1)
                ForEach(TestModel.normalizeTargets.indices, id: \.self) { i in
                    Text(String(format: "%.1f dBFS", TestModel.normalizeTargets[i])).tag(i)
                }
            }
            Stepper("Fade in: \(model.fadeInMs) ms", value: $model.fadeInMs, in: 0...2000, step: 5)
            Stepper("Fade out: \(model.fadeOutMs) ms", value: $model.fadeOutMs, in: 0...2000, step: 5)
            Toggle("Equal-power fades", isOn: $model.equalPower)
            Picker("Slices", selection: $model.slices) {
                Text("None").tag(1)
                ForEach([2, 4, 8, 16, 32, 64], id: \.self) { Text("\($0)").tag($0) }
            }
            Picker("Format", selection: $model.format) {
                Text("WAV").tag(OutputFormat.wav)
                Text("AIFF").tag(OutputFormat.aiff)
            }
        }
    }

    // MARK: Process

    private var processSection: some View {
        Section("Output") {
            Button(model.processing ? "Processing…" : "Process") { model.process() }
                .disabled(model.processing)
            if let summary = model.outputSummary {
                Text(summary).font(Self.font(.sm).monospaced())
            }
            if !model.outputURLs.isEmpty {
                ShareLink(items: model.outputURLs) {
                    Label(model.outputURLs.count == 1 ? "Save or share the file" : "Save or share \(model.outputURLs.count) files",
                          systemImage: "square.and.arrow.up")
                }
            }
        }
    }
}
