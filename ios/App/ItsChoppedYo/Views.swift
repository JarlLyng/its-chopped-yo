import ChoppedCore
import IAMJARLDesignTokens
import SwiftUI
import UniformTypeIdentifiers

extension Font {
    /// A step of the token type scale, as a Dynamic Type style so it follows the system text size.
    static func token(_ style: DesignTokens.Typography.Style) -> Font { .system(style.textStyle) }
}

/// The types the importer offers: WAV and AIFF only (the owner's decision for the first version).
let importTypes: [UTType] = [.wav, .aiff] + [UTType("public.aifc-audio")].compactMap { $0 }

// MARK: Root

struct RootView: View {
    @State private var model = AppModel()
    @State private var importing = false

    var body: some View {
        NavigationStack(path: $model.path) {
            StartView(importing: $importing)
                .navigationDestination(for: Route.self) { route in
                    switch route {
                    case .file: FileView(importing: $importing)
                    case .presets: PresetListView()
                    case .settings: SettingsView()
                    case .selfTest: SelfTestView()
                    }
                }
        }
        .environment(model)
        .tint(DesignTokens.Palette.primary)
        .fileImporter(isPresented: $importing, allowedContentTypes: importTypes, allowsMultipleSelection: true) { result in
            if case .success(let urls) = result { model.open(urls) }
        }
        .onOpenURL { model.load($0) }
        .sheet(item: $model.pro) { ProSheet(feature: $0) }
    }
}

// MARK: Start

struct StartView: View {
    @Environment(AppModel.self) private var model
    @Binding var importing: Bool

    var body: some View {
        List {
            Section {
                VStack(alignment: .leading, spacing: DesignTokens.Spacing.md) {
                    Text("Turn a WAV or AIFF file into the format your sampler wants.")
                        .font(.token(.lg))
                        .foregroundStyle(DesignTokens.Palette.Text.secondary)
                    Button {
                        importing = true
                    } label: {
                        Text("Import a file")
                            .font(.token(.lg).weight(.semibold))
                            .frame(maxWidth: .infinity)
                            .padding(.vertical, DesignTokens.Spacing.sm)
                    }
                    .buttonStyle(.borderedProminent)
                    .accessibilityIdentifier("importButton")
                    if let error = model.error, model.file == nil {
                        Text(error).font(.token(.sm)).foregroundStyle(DesignTokens.Palette.State.error)
                    }
                }
                .padding(.vertical, DesignTokens.Spacing.sm)
            }
            Section {
                ProRow(feature: .record)
                ProRow(feature: .batch)
                ProRow(feature: .chain)
            }
        }
        .navigationTitle("It's chopped, yo!")
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                NavigationLink(value: Route.settings) { Image(systemName: "gearshape") }
                    .accessibilityLabel("Settings")
            }
        }
    }
}

// MARK: File

struct FileView: View {
    @Environment(AppModel.self) private var model
    @Binding var importing: Bool
    @State private var exporting = false

    var body: some View {
        List {
            Section {
                WaveformView(buckets: model.output?.waveform ?? [])
                    .frame(height: 96)
                    .overlay { if model.working { ProgressView() } }
                if let file = model.file {
                    VStack(alignment: .leading, spacing: DesignTokens.Spacing.xs) {
                        Text(Describe.summary(file.audio) + " · " + Describe.peak(file.audio))
                            .foregroundStyle(DesignTokens.Palette.Text.secondary)
                        if let after = model.afterText {
                            Text("→ " + after).foregroundStyle(DesignTokens.Palette.Text.primary)
                        }
                    }
                    .font(.token(.sm).monospacedDigit())
                    .accessibilityIdentifier("formatLine")
                }
                Button {
                    model.togglePlay()
                } label: {
                    Label(model.playing ? "Stop" : "Play the result", systemImage: model.playing ? "stop.fill" : "play.fill")
                }
                .disabled(model.output == nil)
                if let note = model.note {
                    Text(note).font(.token(.sm)).foregroundStyle(DesignTokens.Palette.Text.tertiary)
                }
                if let error = model.error {
                    Text(error).font(.token(.sm)).foregroundStyle(DesignTokens.Palette.State.error)
                }
            }

            Section("Format") {
                NavigationLink(value: Route.presets) {
                    HStack {
                        Text("Preset")
                        Spacer()
                        Text(model.preset?.name ?? "None").foregroundStyle(DesignTokens.Palette.Text.secondary)
                    }
                }
                .accessibilityIdentifier("presetRow")
            }

            Section("Edit") {
                ProRow(feature: .trim)
                ProRow(feature: .fade)
                ProRow(feature: .normalize)
                ProRow(feature: .slices)
            }

            Section {
                Button {
                    exporting = true
                } label: {
                    Label("Save to a folder", systemImage: "folder")
                }
                .disabled(model.output == nil)
                .accessibilityIdentifier("saveButton")
                if let output = model.output {
                    ShareLink(item: output.url) { Label("Share", systemImage: "square.and.arrow.up") }
                }
                if let saved = model.saved {
                    Text(saved).foregroundStyle(DesignTokens.Palette.State.success)
                        .accessibilityIdentifier("savedLine")
                    Button("New file") {
                        model.newFile()
                        importing = true
                    }
                }
            }
        }
        .navigationTitle(model.file?.name ?? "")
        .navigationBarTitleDisplayMode(.inline)
        .fileExporter(
            isPresented: $exporting,
            document: model.output.map { AudioDocument(data: $0.data) },
            contentType: model.exportType,
            defaultFilename: model.file?.name
        ) { model.didSave($0) }
        .onDisappear { model.stop() }
    }
}

struct WaveformView: View {
    var buckets: [ClosedRange<Double>]

    var body: some View {
        Canvas { context, size in
            guard !buckets.isEmpty else { return }
            let mid = size.height / 2
            let w = size.width / CGFloat(buckets.count)
            var path = Path()
            for (i, r) in buckets.enumerated() {
                let x = CGFloat(i) * w + w / 2
                path.move(to: CGPoint(x: x, y: mid - CGFloat(r.upperBound) * mid))
                path.addLine(to: CGPoint(x: x, y: mid - CGFloat(r.lowerBound) * mid + 0.5))
            }
            context.stroke(path, with: .color(DesignTokens.Palette.primary), lineWidth: max(1, w * 0.6))
        }
        .accessibilityHidden(true)
    }
}

// MARK: Presets

struct PresetListView: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        @Bindable var model = model
        List {
            Section {
                ForEach(model.presets) { p in
                    Button {
                        model.presetID = p.id
                    } label: {
                        HStack {
                            Text(p.name).foregroundStyle(DesignTokens.Palette.Text.primary)
                            Spacer()
                            if p.id == model.presetID {
                                Image(systemName: "checkmark").foregroundStyle(DesignTokens.Palette.primary)
                            }
                        }
                    }
                    .accessibilityAddTraits(p.id == model.presetID ? .isSelected : [])
                }
            } footer: {
                Text("Presets are named by format. Pick the one your sampler's manual asks for. All of them save a WAV file; mono adds left and right together.")
            }
            Section {
                ProRow(feature: .customPreset)
            }
        }
        .navigationTitle("Preset")
        .navigationBarTitleDisplayMode(.inline)
    }
}

// MARK: Pro

struct ProBadge: View {
    var body: some View {
        Text("Pro")
            .font(.token(.xs).weight(.semibold))
            .padding(.horizontal, DesignTokens.Spacing.sm)
            .padding(.vertical, DesignTokens.Spacing.xs / 2)
            .background(DesignTokens.Palette.primarySubtle, in: Capsule())
            .foregroundStyle(DesignTokens.Palette.primary)
    }
}

/// A Pro tool as a row: the name, the badge, and a tap that opens the Pro sheet.
struct ProRow: View {
    @Environment(AppModel.self) private var model
    var feature: ProFeature

    var body: some View {
        Button {
            model.pro = feature
        } label: {
            HStack {
                Text(feature.title).foregroundStyle(DesignTokens.Palette.Text.primary)
                Spacer()
                ProBadge()
            }
        }
        .accessibilityHint("Part of Pro")
    }
}

struct ProSheet: View {
    @Environment(\.dismiss) private var dismiss
    var feature: ProFeature

    var body: some View {
        NavigationStack {
            List {
                Section {
                    Text("That is part of Pro. Pro is a one-time purchase, no subscription.")
                        .font(.token(.lg))
                }
                Section("Pro adds") {
                    ForEach(ProFeature.allCases) { f in
                        VStack(alignment: .leading, spacing: DesignTokens.Spacing.xs) {
                            Text(f.title).font(.token(.base).weight(f == feature ? .semibold : .regular))
                            Text(f.detail).font(.token(.sm)).foregroundStyle(DesignTokens.Palette.Text.secondary)
                        }
                    }
                }
                Section {
                    Button("Buy Pro") {}.disabled(true)
                    Button("Restore purchase") {}.disabled(true)
                } footer: {
                    Text("Purchases are not set up in this build yet.")
                }
            }
            .navigationTitle("Pro")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Close") { dismiss() } }
            }
        }
    }
}

// MARK: Settings

struct SettingsView: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        @Bindable var model = model
        List {
            Section {
                Toggle("Dither", isOn: $model.dither)
            } footer: {
                Text("Adds a tiny amount of noise when the bit depth goes down, so quiet parts fade out smoothly instead of breaking up. On is the usual choice.")
            }
            Section {
                Button {
                    model.pro = .batch
                } label: {
                    HStack { Text("Pro").foregroundStyle(DesignTokens.Palette.Text.primary); Spacer(); ProBadge() }
                }
            }
            Section("Privacy") {
                Text("Everything happens on this phone. No account, no ads, no tracking.")
            }
            Section("Licenses") {
                Text("Resampling uses libsamplerate 0.2.2 by Erik de Castro Lopo, under the BSD 2-Clause license.")
                    .font(.token(.sm))
            }
            Section {
                NavigationLink("Self-test", value: Route.selfTest)
            } footer: {
                Text(versionText)
            }
        }
        .navigationTitle("Settings")
        .navigationBarTitleDisplayMode(.inline)
    }

    private var versionText: String {
        let v = Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "?"
        let b = Bundle.main.infoDictionary?["CFBundleVersion"] as? String ?? "?"
        return "Version \(v) (\(b))"
    }
}
