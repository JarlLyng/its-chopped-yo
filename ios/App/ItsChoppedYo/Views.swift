import ChoppedCore
import IAMJARLDesignTokens
import SwiftUI
import UniformTypeIdentifiers

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
                    case .file: FileView()
                    case .presets: PresetListView()
                    case .saved: SavedView(importing: $importing)
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
        PanelScreen {
            HStack {
                VStack(alignment: .leading, spacing: 0) {
                    Text("SAMPLE PREP").font(.mono(.xs)).tracking(1.5).foregroundStyle(DesignTokens.Palette.Text.tertiary)
                    Text("It's chopped, yo!").font(.token(.xl).weight(.bold))
                }
                Spacer()
                Button { model.path.append(.settings) } label: {
                    Image(systemName: "slider.horizontal.3").font(.token(.lg)).frame(width: 44, height: 44)
                }
                .buttonStyle(KeyStyle())
                .accessibilityLabel("Settings")
            }

            DisplayPanel {
                HStack {
                    Text("READY")
                    Spacer()
                    Text("WAV · AIFF IN").foregroundStyle(Panel.displayText3)
                }
                .font(.mono(.xs))
                Rectangle().fill(Panel.displayDim).frame(height: 2).padding(.vertical, DesignTokens.Spacing.lg)
                HStack(spacing: DesignTokens.Spacing.sm) {
                    Text("NO FILE").font(.mono(.base))
                    Rectangle().fill(Panel.displayText).frame(width: 9, height: 16)
                }
            }
            .accessibilityElement(children: .ignore)
            .accessibilityLabel("No file open. Reads WAV and AIFF.")

            Button {
                importing = true
            } label: {
                VStack(alignment: .leading) {
                    HStack {
                        Circle().fill(DesignTokens.Palette.onPrimary).frame(width: 12, height: 12)
                        Spacer()
                        Image(systemName: "square.and.arrow.down").font(.token(.xl))
                    }
                    Spacer()
                    Text("Import a file").font(.token(.xl).weight(.bold))
                    Text("Turn a WAV or AIFF file into the format your sampler wants.")
                        .font(.token(.sm)).opacity(0.85).multilineTextAlignment(.leading)
                }
                .padding(DesignTokens.Spacing.lg + 2)
                .frame(maxWidth: .infinity, minHeight: 190, alignment: .leading)
            }
            .buttonStyle(PadStyle())
            .accessibilityIdentifier("importButton")

            if let error = model.error, model.file == nil {
                Text(error).font(.token(.sm)).foregroundStyle(DesignTokens.Palette.State.error)
            }

            SilkLabel("MORE")
            HStack(spacing: DesignTokens.Spacing.sm + 2) {
                ProKey(feature: .record, sub: "Record")
                ProKey(feature: .batch, sub: "A whole folder")
                ProKey(feature: .chain, sub: "Sample chains")
            }

            Text("Everything happens on this phone.")
                .font(.token(.xs))
                .foregroundStyle(DesignTokens.Palette.Text.tertiary)
                .frame(maxWidth: .infinity)
                .padding(.top, DesignTokens.Spacing.lg)
        }
    }
}

/// A Pro tool as a key: dark LED, the Pro tag, a tap opens the Pro sheet.
struct ProKey: View {
    @Environment(AppModel.self) private var model
    var feature: ProFeature
    var sub: String? = nil
    var height: CGFloat = 104

    var body: some View {
        Button {
            model.pro = feature
        } label: {
            VStack(alignment: .leading, spacing: 2) {
                HStack {
                    LED(on: false, size: sub == nil ? 8 : 10)
                    Spacer()
                    if sub != nil { ProTag() }
                }
                Spacer(minLength: 0)
                Text(feature.key).font(.mono(sub == nil ? .xs : .sm)).tracking(1)
                if let sub {
                    Text(sub).font(.token(.xs)).foregroundStyle(DesignTokens.Palette.Text.tertiary).lineLimit(1)
                }
            }
            .padding(DesignTokens.Spacing.sm + 2)
            .frame(maxWidth: .infinity, minHeight: height, alignment: .leading)
            .foregroundStyle(sub == nil ? DesignTokens.Palette.Text.secondary : DesignTokens.Palette.Text.primary)
        }
        .buttonStyle(KeyStyle(radius: sub == nil ? DesignTokens.Radius.md : DesignTokens.Radius.lg - 2))
        .accessibilityLabel(feature.title)
        .accessibilityHint("Part of Pro")
    }
}

// MARK: File

struct FileView: View {
    @Environment(AppModel.self) private var model
    @State private var exporting = false

    var body: some View {
        PanelScreen {
            PanelHeader(label: "FILE", title: model.file.map { "\($0.name).\(model.preset?.format == .aiff ? "aif" : "wav")" } ?? "") {
                model.stop()
                model.path.removeLast()
            }

            DisplayPanel {
                if let file = model.file {
                    HStack {
                        Text(Describe.inLine(file.audio))
                        Spacer()
                        Text(Describe.shortPeak(file.audio))
                    }
                    .font(.mono(.xs))
                }
                WaveformDisplay(buckets: model.output?.waveform ?? [], playing: model.playing, progress: model.progress)
                    .frame(height: 84)
                    .overlay { if model.working { ProgressView().tint(Panel.displayText) } }
                HStack {
                    if let output = model.output, let preset = model.preset {
                        Text(Describe.outLine(output.audio, bits: preset.bits, format: preset.format))
                            .padding(.horizontal, DesignTokens.Spacing.sm)
                            .padding(.vertical, 2)
                            .background(Panel.displayText, in: RoundedRectangle(cornerRadius: 4))
                            .foregroundStyle(Panel.displayInk)
                        Spacer()
                        Text(Describe.shortDuration(frames: output.audio.frameCount, sampleRate: output.audio.sampleRate))
                            .foregroundStyle(Panel.displayText2)
                    } else {
                        Text(" ")
                    }
                }
                .font(.mono(.sm))
                .accessibilityIdentifier("formatLine")
            }

            Button {
                model.togglePlay()
            } label: {
                HStack(spacing: DesignTokens.Spacing.sm + 2) {
                    LED(on: model.playing)
                    Image(systemName: model.playing ? "stop.fill" : "play.fill")
                    Text(model.playing ? "STOP" : "PLAY").font(.mono(.sm)).tracking(1)
                    Text("the result").font(.token(.xs)).foregroundStyle(DesignTokens.Palette.Text.tertiary)
                    Spacer()
                }
                .padding(.horizontal, DesignTokens.Spacing.md + 2)
                .frame(minHeight: 56)
            }
            .buttonStyle(KeyStyle(selected: model.playing, radius: DesignTokens.Radius.lg - 2))
            .disabled(model.output == nil)

            if let note = model.note {
                Text(note).font(.token(.sm)).foregroundStyle(DesignTokens.Palette.Text.tertiary)
            }
            if let error = model.error {
                Text(error).font(.token(.sm)).foregroundStyle(DesignTokens.Palette.State.error)
            }

            SilkLabel(title: "FORMAT") {
                Button { model.path.append(.presets) } label: {
                    Text("ALL ›").font(.mono(.xs)).tracking(1.5).foregroundStyle(DesignTokens.Palette.Text.secondary)
                        .frame(minHeight: 44)
                }
                .accessibilityLabel("All presets")
                .accessibilityIdentifier("presetRow")
            }
            LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: DesignTokens.Spacing.sm), count: 4), spacing: DesignTokens.Spacing.sm) {
                ForEach(model.presets) { p in
                    PresetKey(preset: p)
                }
            }

            SilkLabel(title: "EDIT") { ProTag() }
            HStack(spacing: DesignTokens.Spacing.sm) {
                ProKey(feature: .trim, height: 56)
                ProKey(feature: .fade, height: 56)
                ProKey(feature: .normalize, height: 56)
                ProKey(feature: .slices, height: 56)
            }
        } bottom: {
            HStack(spacing: DesignTokens.Spacing.sm) {
                Button {
                    exporting = true
                } label: {
                    Label("Save to a folder", systemImage: "folder")
                        .font(.token(.lg).weight(.bold))
                        .frame(maxWidth: .infinity, minHeight: 64)
                }
                .buttonStyle(PadStyle())
                .disabled(model.output == nil)
                .accessibilityIdentifier("saveButton")
                ShareKey().frame(maxWidth: 120)
            }
        }
        .fileExporter(
            isPresented: $exporting,
            document: model.output.map { AudioDocument(data: $0.data) },
            contentType: model.exportType,
            defaultFilename: model.file?.name
        ) { model.didSave($0) }
        .onDisappear { model.stop() }
    }
}

struct ShareKey: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        if let output = model.output {
            ShareLink(item: output.url) {
                Label("Share", systemImage: "square.and.arrow.up")
                    .font(.token(.base).weight(.semibold))
                    .frame(maxWidth: .infinity, minHeight: 64)
            }
            .buttonStyle(KeyStyle(radius: DesignTokens.Radius.lg))
        }
    }
}

struct PresetKey: View {
    @Environment(AppModel.self) private var model
    var preset: Preset

    var body: some View {
        let on = preset.id == model.presetID
        Button {
            model.presetID = preset.id
        } label: {
            VStack(alignment: .leading, spacing: 0) {
                LED(on: on)
                Spacer(minLength: DesignTokens.Spacing.sm)
                Text(Describe.shortRate(preset.sampleRate)).font(.mono(.base))
                Text("\(preset.bits) · \(preset.channels == .mono ? "MONO" : "ST")")
                    .font(.mono(.xs)).foregroundStyle(DesignTokens.Palette.Text.tertiary)
                    .lineLimit(1).minimumScaleFactor(0.8)
            }
            .padding(DesignTokens.Spacing.sm)
            .frame(maxWidth: .infinity, minHeight: 76, alignment: .leading)
        }
        .buttonStyle(KeyStyle(selected: on))
        .accessibilityLabel(preset.name)
        .accessibilityAddTraits(on ? .isSelected : [])
    }
}

/// The output's waveform on the display, with a playhead while it plays. Display only.
struct WaveformDisplay: View {
    var buckets: [ClosedRange<Double>]
    var playing: Bool
    var progress: () -> Double

    var body: some View {
        TimelineView(.animation(paused: !playing)) { _ in
            Canvas { context, size in
                guard !buckets.isEmpty else {
                    context.fill(Path(CGRect(x: 0, y: size.height / 2 - 1, width: size.width, height: 2)), with: .color(Panel.displayDim))
                    return
                }
                let mid = size.height / 2
                let w = size.width / CGFloat(buckets.count)
                var path = Path()
                for (i, r) in buckets.enumerated() {
                    let x = CGFloat(i) * w + w / 2
                    path.move(to: CGPoint(x: x, y: mid - CGFloat(r.upperBound) * mid))
                    path.addLine(to: CGPoint(x: x, y: mid - CGFloat(r.lowerBound) * mid + 1))
                }
                context.stroke(path, with: .color(Panel.displayText), lineWidth: max(1, w * 0.6))
                if playing {
                    let x = size.width * progress()
                    context.fill(Path(CGRect(x: x - 1, y: 0, width: 2, height: size.height)), with: .color(Panel.playhead))
                }
            }
        }
        .accessibilityHidden(true)
    }
}

// MARK: Presets

struct PresetListView: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        PanelScreen {
            PanelHeader(label: "FORMAT", title: "Presets") { model.path.removeLast() }

            if let p = model.preset {
                DisplayPanel {
                    Text("SELECTED").font(.mono(.xs)).foregroundStyle(Panel.displayText3)
                    Text(p.name).font(.mono(.lg))
                    Text("\(p.format == .aiff ? "AIFF" : "WAV") · \(p.channels == .mono ? "LEFT + RIGHT SUMMED" : "CHANNELS AS THEY ARE")")
                        .foregroundStyle(Panel.displayText2)
                }
            }

            SilkLabel("BUILT IN")
            VStack(spacing: DesignTokens.Spacing.sm) {
                ForEach(model.presets) { p in
                    let on = p.id == model.presetID
                    Button {
                        model.presetID = p.id
                    } label: {
                        HStack(spacing: DesignTokens.Spacing.md + 2) {
                            LED(on: on)
                            Text(p.name).font(.mono(.base))
                            Spacer()
                            Text(p.format == .aiff ? "AIFF" : "WAV").font(.mono(.xs)).foregroundStyle(DesignTokens.Palette.Text.tertiary)
                        }
                        .padding(.horizontal, DesignTokens.Spacing.lg)
                        .frame(minHeight: 60)
                    }
                    .buttonStyle(KeyStyle(selected: on))
                    .accessibilityAddTraits(on ? .isSelected : [])
                }
            }
            Text("Presets are named by format. Pick the one your sampler's manual asks for. Mono adds left and right together.")
                .font(.token(.sm)).foregroundStyle(DesignTokens.Palette.Text.tertiary)

            SilkLabel("YOURS")
            Button {
                model.pro = .customPreset
            } label: {
                HStack(spacing: DesignTokens.Spacing.md + 2) {
                    Image(systemName: "plus")
                    Text("Your own presets").font(.token(.base))
                    Spacer()
                    ProTag()
                }
                .foregroundStyle(DesignTokens.Palette.Text.secondary)
                .padding(.horizontal, DesignTokens.Spacing.lg)
                .frame(minHeight: 60)
                .overlay(RoundedRectangle(cornerRadius: DesignTokens.Radius.md).strokeBorder(Panel.keyBorder, style: StrokeStyle(lineWidth: 1, dash: [4, 3])))
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityHint("Part of Pro")
        }
    }
}

// MARK: Saved

struct SavedView: View {
    @Environment(AppModel.self) private var model
    @Binding var importing: Bool

    var body: some View {
        PanelScreen {
            PanelHeader(label: "FILE", title: model.saved ?? "") { model.path.removeLast() }

            DisplayPanel {
                HStack {
                    Text("WRITE")
                    Spacer()
                    Text("OK").foregroundStyle(Panel.displayText3)
                }
                .font(.mono(.xs))
                Spacer(minLength: DesignTokens.Spacing.xxl)
                HStack(spacing: DesignTokens.Spacing.md) {
                    Circle().fill(Panel.displayText).frame(width: 14, height: 14)
                    Text("SAVED").font(.mono(.xxl))
                }
                Rectangle().fill(Panel.displayText).frame(height: 8).clipShape(Capsule())
                Spacer(minLength: DesignTokens.Spacing.xxl)
                if let saved = model.saved { Text(saved) }
                if let output = model.output, let preset = model.preset {
                    Text(String(Describe.outLine(output.audio, bits: preset.bits, format: preset.format).dropFirst(4)) + " · " + Describe.size(output.data.count))
                        .foregroundStyle(Panel.displayText2)
                }
            }
            .accessibilityElement(children: .combine)
            .accessibilityIdentifier("savedLine")

            Text("Put the file on your sampler the way its manual says: a card, USB or the maker's transfer app.")
                .font(.token(.sm)).foregroundStyle(DesignTokens.Palette.Text.secondary)
        } bottom: {
            HStack(spacing: DesignTokens.Spacing.sm) {
                Button {
                    model.newFile()
                    importing = true
                } label: {
                    Label("New file", systemImage: "square.and.arrow.down")
                        .font(.token(.lg).weight(.bold))
                        .frame(maxWidth: .infinity, minHeight: 64)
                }
                .buttonStyle(PadStyle())
                ShareKey().frame(maxWidth: 120)
            }
        }
    }
}

// MARK: Pro

struct ProSheet: View {
    @Environment(\.dismiss) private var dismiss
    var feature: ProFeature

    var body: some View {
        PanelScreen {
            PanelHeader(label: "UNLOCK", title: "Pro", icon: "xmark", iconLabel: "Close") { dismiss() }

            DisplayPanel {
                Text("YOU TAPPED").font(.mono(.xs)).foregroundStyle(Panel.displayText3)
                Text(feature.key).font(.mono(.lg))
                Text("ONE-TIME PURCHASE · NO SUBSCRIPTION").foregroundStyle(Panel.displayText2)
            }

            SilkLabel("PRO ADDS")
            LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: DesignTokens.Spacing.sm), count: 2), spacing: DesignTokens.Spacing.sm) {
                ForEach(ProFeature.allCases) { f in
                    VStack(alignment: .leading, spacing: DesignTokens.Spacing.xs) {
                        HStack(spacing: DesignTokens.Spacing.sm) {
                            LED(on: true, size: 8)
                            Text(f.key).font(.mono(.xs)).tracking(1)
                        }
                        Text(f.detail).font(.token(.xs)).foregroundStyle(DesignTokens.Palette.Text.secondary)
                            .fixedSize(horizontal: false, vertical: true)
                        Spacer(minLength: 0)
                    }
                    .padding(DesignTokens.Spacing.sm + 2)
                    .frame(maxWidth: .infinity, minHeight: 84, alignment: .topLeading)
                    .background(Panel.key, in: RoundedRectangle(cornerRadius: DesignTokens.Radius.md))
                    .overlay(RoundedRectangle(cornerRadius: DesignTokens.Radius.md)
                        .strokeBorder(f == feature ? DesignTokens.Palette.primary : Panel.keyBorder, lineWidth: f == feature ? 2 : 1))
                    .accessibilityElement(children: .combine)
                }
            }
        } bottom: {
            VStack(spacing: DesignTokens.Spacing.xs) {
                Button {} label: {
                    Text("Buy Pro").font(.token(.lg).weight(.bold)).frame(maxWidth: .infinity, minHeight: 64)
                }
                .buttonStyle(PadStyle())
                .disabled(true)
                Button("Restore purchase") {}
                    .font(.token(.base))
                    .foregroundStyle(DesignTokens.Palette.Text.secondary)
                    .frame(minHeight: 44)
                    .disabled(true)
                Text("Purchases are not set up in this build yet.")
                    .font(.token(.xs)).foregroundStyle(DesignTokens.Palette.Text.tertiary)
            }
        }
    }
}

// MARK: Settings

struct SettingsView: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        PanelScreen {
            PanelHeader(label: "SYSTEM", title: "Settings") { model.path.removeLast() }

            SilkLabel("OUTPUT")
            Button {
                model.dither.toggle()
            } label: {
                HStack(alignment: .top, spacing: DesignTokens.Spacing.md + 2) {
                    LED(on: model.dither).padding(.top, 4)
                    VStack(alignment: .leading, spacing: 2) {
                        Text(model.dither ? "DITHER ON" : "DITHER OFF").font(.mono(.sm)).tracking(1)
                        Text("A tiny amount of noise when the bit depth goes down, so quiet parts fade out smoothly. On is the usual choice.")
                            .font(.token(.sm)).foregroundStyle(DesignTokens.Palette.Text.tertiary)
                            .multilineTextAlignment(.leading)
                    }
                    Spacer(minLength: 0)
                }
                .padding(DesignTokens.Spacing.md + 2)
                .frame(maxWidth: .infinity, alignment: .leading)
            }
            .buttonStyle(KeyStyle(selected: model.dither))
            .accessibilityLabel("Dither")
            .accessibilityValue(model.dither ? "On" : "Off")

            SilkLabel("PRO")
            Button {
                model.pro = .batch
            } label: {
                HStack(spacing: DesignTokens.Spacing.md + 2) {
                    LED(on: false)
                    Text("Pro and restore purchase").font(.token(.base))
                    Spacer()
                    ProTag()
                }
                .padding(.horizontal, DesignTokens.Spacing.lg)
                .frame(minHeight: 56)
            }
            .buttonStyle(KeyStyle())

            SilkLabel("ABOUT")
            VStack(alignment: .leading, spacing: 0) {
                VStack(alignment: .leading, spacing: DesignTokens.Spacing.xs) {
                    Text("Privacy").font(.token(.base).weight(.semibold))
                    Text("Everything happens on this phone. No account, no ads, no tracking.")
                        .font(.token(.sm)).foregroundStyle(DesignTokens.Palette.Text.secondary)
                }
                .padding(DesignTokens.Spacing.md + 2)
                Rectangle().fill(Panel.rule).frame(height: 1)
                VStack(alignment: .leading, spacing: DesignTokens.Spacing.xs) {
                    Text("Licenses").font(.token(.base).weight(.semibold))
                    Text("Resampling uses libsamplerate 0.2.2 by Erik de Castro Lopo, under the BSD 2-Clause license.")
                        .font(.token(.sm)).foregroundStyle(DesignTokens.Palette.Text.secondary)
                }
                .padding(DesignTokens.Spacing.md + 2)
                Rectangle().fill(Panel.rule).frame(height: 1)
                Button { model.path.append(.selfTest) } label: {
                    HStack {
                        Text("Self-test").font(.token(.base).weight(.semibold))
                        Spacer()
                        Text("RUN ›").font(.mono(.xs)).tracking(1).foregroundStyle(DesignTokens.Palette.Text.tertiary)
                    }
                    .padding(.horizontal, DesignTokens.Spacing.md + 2)
                    .frame(minHeight: 52)
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(Panel.key, in: RoundedRectangle(cornerRadius: DesignTokens.Radius.md))
            .overlay(RoundedRectangle(cornerRadius: DesignTokens.Radius.md).strokeBorder(Panel.keyBorder, lineWidth: 1))

            Text(versionText)
                .font(.mono(.xs)).tracking(1).foregroundStyle(DesignTokens.Palette.Text.tertiary)
                .frame(maxWidth: .infinity)
                .padding(.top, DesignTokens.Spacing.lg)
        }
    }

    private var versionText: String {
        let v = Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "?"
        let b = Bundle.main.infoDictionary?["CFBundleVersion"] as? String ?? "?"
        return "VERSION \(v) (\(b)) · IAMJARL"
    }
}
