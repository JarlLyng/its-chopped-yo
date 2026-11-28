import Foundation

/// A built-in format preset from presets/presets.json, the same file the Android app reads.
/// Presets are named by format, never by machine (the owner's decision, so the stores accept them).
public struct Preset: Equatable, Identifiable {
    public enum Channels: String { case mono, stereo }

    public let id: String
    public let name: String
    public let sampleRate: Int
    public let bits: Int
    public let channels: Channels
    public let format: OutputFormat

    /// The processing settings for this preset. Mono sums the channels as (L+R)/2 (spec section 3);
    /// stereo keeps the channels as they are. Nothing else is touched: no trim, fade or normalize.
    public func settings(dither: Bool) -> Settings {
        var s = Settings()
        s.mono = channels == .mono ? .sum : .keep
        s.sampleRate = sampleRate
        s.bits = bits
        s.dither = dither
        s.format = format
        return s
    }
}

public enum Presets {
    public enum ParseError: Error, Equatable { case invalid(String) }

    /// Reads presets.json. Throws on anything the app would not know how to use, so a bad file
    /// fails the tests instead of showing a broken list.
    public static func parse(_ data: Data) throws -> [Preset] {
        guard let root = try JSONSerialization.jsonObject(with: data) as? [String: Any],
              let list = root["presets"] as? [[String: Any]] else { throw ParseError.invalid("no presets list") }
        var seen = Set<String>()
        return try list.map { p in
            guard let id = p["id"] as? String, let name = p["name"] as? String,
                  let rate = p["sampleRate"] as? Int, let bits = p["bits"] as? Int,
                  let ch = (p["channels"] as? String).flatMap(Preset.Channels.init(rawValue:)),
                  let fmt = (p["format"] as? String).flatMap({ $0 == "aiff" ? OutputFormat.aiff : $0 == "wav" ? .wav : nil })
            else { throw ParseError.invalid("bad preset \(p["id"] ?? "?")") }
            guard bits == 16 || bits == 24, rate > 0 else { throw ParseError.invalid("bad format in \(id)") }
            guard seen.insert(id).inserted else { throw ParseError.invalid("duplicate id \(id)") }
            return Preset(id: id, name: name, sampleRate: rate, bits: bits, channels: ch, format: fmt)
        }
    }
}

/// Text for the screen. The same strings on iOS and Android, whatever the phone's language, so a
/// rate reads "44.1 kHz" on both (no locale thousands separators). Display only, never audio.
public enum Describe {
    /// 48000 → "48 kHz", 44100 → "44.1 kHz", 46875 → "46.875 kHz".
    public static func rate(_ hz: Int) -> String {
        let whole = hz / 1000, frac = hz % 1000
        if frac == 0 { return "\(whole) kHz" }
        var digits = String(frac)
        digits = String(repeating: "0", count: 3 - digits.count) + digits
        while digits.hasSuffix("0") { digits.removeLast() }
        return "\(whole).\(digits) kHz"
    }

    /// 1 → "mono", 2 → "stereo", n → "n ch".
    public static func channels(_ n: Int) -> String {
        n == 1 ? "mono" : n == 2 ? "stereo" : "\(n) ch"
    }

    /// Length, rounded down: under a minute "4.25 s", otherwise "1:05".
    public static func duration(frames: Int, sampleRate: Int) -> String {
        guard sampleRate > 0 else { return "0.00 s" }
        let centi = frames * 100 / sampleRate
        if centi < 6000 {
            let c = centi % 100
            return "\(centi / 100).\(c < 10 ? "0" : "")\(c) s"
        }
        let s = centi / 100
        let sec = s % 60
        return "\(s / 60):\(sec < 10 ? "0" : "")\(sec)"
    }

    /// The highest absolute sample as dBFS with one decimal, or "silent".
    public static func peak(_ a: Audio) -> String {
        var p = 0.0
        for ch in a.channels { for x in ch where abs(x) > p { p = abs(x) } }
        guard p > 0 else { return "silent" }
        // floor(x + 0.5), as in the Kotlin twin, so a value on a half rounds the same way on both.
        let db = (20 * log10(p) * 10 + 0.5).rounded(.down) / 10
        let tenths = Int((abs(db) * 10 + 0.5).rounded(.down))
        let sign = db < 0 && tenths != 0 ? "-" : ""
        return "peak \(sign)\(tenths / 10).\(tenths % 10) dBFS"
    }

    /// "96 kHz · stereo · 0.10 s"
    public static func summary(_ a: Audio) -> String {
        "\(rate(a.sampleRate)) · \(channels(a.channels.count)) · \(duration(frames: a.frameCount, sampleRate: a.sampleRate))"
    }

    // MARK: The display: short uppercase values, like a sampler's screen

    /// 48000 → "48K", 44100 → "44.1K".
    public static func shortRate(_ hz: Int) -> String {
        String(rate(hz).dropLast(4)) + "K"
    }

    /// 1 → "MONO", 2 → "ST", n → "nCH".
    public static func shortChannels(_ n: Int) -> String {
        n == 1 ? "MONO" : n == 2 ? "ST" : "\(n)CH"
    }

    /// "1.42S" under a minute, otherwise "1:05".
    public static func shortDuration(frames: Int, sampleRate: Int) -> String {
        let d = duration(frames: frames, sampleRate: sampleRate)
        return d.hasSuffix(" s") ? String(d.dropLast(2)) + "S" : d
    }

    /// "PK -1.9", or "SILENT".
    public static func shortPeak(_ a: Audio) -> String {
        let p = peak(a)
        return p == "silent" ? "SILENT" : "PK " + p.dropFirst(5).dropLast(5)
    }

    /// "IN 96K · ST · 1.42S"
    public static func inLine(_ a: Audio) -> String {
        "IN \(shortRate(a.sampleRate)) · \(shortChannels(a.channels.count)) · \(shortDuration(frames: a.frameCount, sampleRate: a.sampleRate))"
    }

    /// "OUT 48K · 16B · MONO · WAV"
    public static func outLine(_ a: Audio, bits: Int, format: OutputFormat) -> String {
        "OUT \(shortRate(a.sampleRate)) · \(bits)B · \(shortChannels(a.channels.count)) · \(format == .aiff ? "AIFF" : "WAV")"
    }

    /// A file size: "512 B", "66 KB" (rounded up), "3.2 MB" (rounded down).
    public static func size(_ bytes: Int) -> String {
        if bytes < 1024 { return "\(bytes) B" }
        if bytes < 1024 * 1024 { return "\((bytes + 1023) / 1024) KB" }
        let tenths = bytes * 10 / (1024 * 1024)
        return "\(tenths / 10).\(tenths % 10) MB"
    }
}
