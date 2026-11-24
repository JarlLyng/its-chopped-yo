// specs/audio-processing.md, version 0.1. Read the spec before changing anything here: the same
// bytes on iOS and Android depend on doing exactly what it says, in the order it says.

/// Audio as 64-bit floats, one array per channel (`channels[ch][frame]`).
public struct Audio: Equatable {
    public var sampleRate: Int
    public var channels: [[Double]]

    public init(sampleRate: Int, channels: [[Double]]) {
        self.sampleRate = sampleRate
        self.channels = channels
    }

    public var frameCount: Int { channels.first?.count ?? 0 }
}

public enum ChoppedError: Error, Equatable {
    case notWave
    case dataBeforeFmt
    case noData
    case badFormat
    case unsupportedFormat
    case unknownMonoMode
    case emptyAfterTrim
    case unknownCurve
    case unknownNormalizeTarget
    case badBits
    case badSliceCount
    case fewerFramesThanSlices
    case chainMismatch
    case fewerSlotsThanInputs
}

public enum MonoMode: String {
    case keep, sum, left, right
}

public enum FadeCurve: String {
    case linear, equalPower
}

public struct Fade: Equatable {
    public var frames: Int
    public var curve: FadeCurve

    public init(frames: Int, curve: FadeCurve) {
        self.frames = frames
        self.curve = curve
    }
}

/// The settings for one run (spec section 1).
public struct Settings: Equatable {
    public var mono: MonoMode = .keep
    /// `[start, end)` in frames.
    public var trim: (start: Int, end: Int)?
    public var fadeIn: Fade?
    public var fadeOut: Fade?
    /// A dBFS value from `Normalize.targets`, or nil for no normalize.
    public var normalizeDB: Double?
    public var bits: Int = 16
    public var dither: Bool = false

    public init() {}

    public static func == (a: Settings, b: Settings) -> Bool {
        a.mono == b.mono && a.trim?.start == b.trim?.start && a.trim?.end == b.trim?.end
            && a.fadeIn == b.fadeIn && a.fadeOut == b.fadeOut && a.normalizeDB == b.normalizeDB
            && a.bits == b.bits && a.dither == b.dither
    }
}
