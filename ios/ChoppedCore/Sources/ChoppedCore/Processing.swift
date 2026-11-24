// Spec sections 3 to 7, 9 and 10. Only + - * /, floor, sqrt and abs touch the audio.

public enum Processing {
    /// Section 6: dBFS to the double nearest 10^(dB/20), written out so nothing computes a power.
    public static let normalizeTargets: [Double: Double] = [
        0.0: 1.0,
        -0.1: 0.9885530946569389,
        -0.3: 0.9660508789898133,
        -0.5: 0.9440608762859234,
        -1.0: 0.8912509381337456,
        -3.0: 0.7079457843841379,
        -6.0: 0.5011872336272722,
    ]

    /// Section 3: downmix weights before they are divided by their sum.
    static func downmixK(_ c: Int) -> [Double] {
        switch c {
        case 1: return [1.0]
        case 2: return [1.0, 1.0]
        case 3: return [1.0, 1.0, 1.414]
        case 4: return [1.0, 1.0, 1.0, 1.0]
        case 5: return [1.0, 1.0, 0.707, 0.707, 0.707]
        case 6: return [1.0, 1.0, 0.707, 0.0, 0.707, 0.707]
        default: return [Double](repeating: 1.0, count: c)
        }
    }

    /// Steps 2 to 5 of the pipeline.
    public static func process(_ input: Audio, _ s: Settings) throws -> Audio {
        var a = applyChannels(input, s.mono)
        a = try trim(a, s.trim)
        a = try fade(a, fadeIn: s.fadeIn, fadeOut: s.fadeOut)
        a = try normalize(a, s.normalizeDB)
        return a
    }

    // MARK: Section 3

    public static func applyChannels(_ a: Audio, _ mode: MonoMode) -> Audio {
        let c = a.channels.count
        switch mode {
        case .keep:
            return a
        case .left:
            return Audio(sampleRate: a.sampleRate, channels: [a.channels[0]])
        case .right:
            return Audio(sampleRate: a.sampleRate, channels: [c > 1 ? a.channels[1] : a.channels[0]])
        case .sum:
            let k = downmixK(c)
            var total = 0.0
            for v in k { total = total + v }
            let w = k.map { $0 / total }
            var out = [Double](repeating: 0.0, count: a.frameCount)
            for i in 0..<a.frameCount {
                var acc = 0.0
                for ch in 0..<c {
                    acc = acc + a.channels[ch][i] * w[ch]
                }
                out[i] = acc
            }
            return Audio(sampleRate: a.sampleRate, channels: [out])
        }
    }

    // MARK: Section 4

    public static func trim(_ a: Audio, _ range: (start: Int, end: Int)?) throws -> Audio {
        guard let r = range else { return a }
        let start = min(max(r.start, 0), a.frameCount)
        let end = min(max(r.end, 0), a.frameCount)
        guard end > start else { throw ChoppedError.emptyAfterTrim }
        return Audio(sampleRate: a.sampleRate, channels: a.channels.map { Array($0[start..<end]) })
    }

    // MARK: Section 5

    static func gain(_ t: Double, _ curve: FadeCurve) -> Double {
        switch curve {
        case .linear: return t
        case .equalPower: return t.squareRoot()
        }
    }

    public static func fade(_ a: Audio, fadeIn: Fade?, fadeOut: Fade?) throws -> Audio {
        let length = a.frameCount
        var chans = a.channels
        if let f = fadeIn {
            let n = min(f.frames, length)
            for i in 0..<max(n, 0) {
                let g = gain(Double(i) / Double(n), f.curve)
                for c in 0..<chans.count { chans[c][i] = chans[c][i] * g }
            }
        }
        if let f = fadeOut {
            let n = min(f.frames, length)
            for i in 0..<max(n, 0) {
                let g = gain(Double(n - 1 - i) / Double(n), f.curve)
                let frame = length - n + i
                for c in 0..<chans.count { chans[c][frame] = chans[c][frame] * g }
            }
        }
        return Audio(sampleRate: a.sampleRate, channels: chans)
    }

    // MARK: Section 6

    public static func normalize(_ a: Audio, _ db: Double?) throws -> Audio {
        guard let db else { return a }
        guard let target = normalizeTargets[db] else { throw ChoppedError.unknownNormalizeTarget }
        var peak = 0.0
        for ch in a.channels {
            for x in ch {
                let ax = abs(x)
                if ax > peak { peak = ax }
            }
        }
        if peak == 0.0 { return a }
        let g = target / peak
        return Audio(sampleRate: a.sampleRate, channels: a.channels.map { ch in ch.map { $0 * g } })
    }

    // MARK: Section 7

    /// xorshift32, restarted for every output file.
    public struct XorShift32 {
        public static let seed: UInt32 = 0x2545F491
        var s: UInt32

        public init(seed: UInt32 = XorShift32.seed) { s = seed }

        public mutating func next() -> Double {
            s ^= s << 13
            s ^= s >> 17
            s ^= s << 5
            return Double(s >> 8) / 16777216.0
        }
    }

    /// Interleaved little-endian PCM, 2 or 3 bytes per sample.
    public static func quantize(_ a: Audio, bits: Int, dither: Bool) throws -> [UInt8] {
        guard bits == 16 || bits == 24 else { throw ChoppedError.badBits }
        let scale = Double(1 << (bits - 1))
        let hi = (1 << (bits - 1)) - 1
        let lo = -(1 << (bits - 1))
        var rng = XorShift32()
        let nch = a.channels.count
        var out: [UInt8] = []
        out.reserveCapacity(a.frameCount * nch * bits / 8)
        for i in 0..<a.frameCount {
            for c in 0..<nch {
                var v = a.channels[c][i] * scale
                if dither {
                    let r1 = rng.next()
                    let r2 = rng.next()
                    v = v + (r1 + r2 - 1.0)
                }
                var q = Int((v + 0.5).rounded(.down))
                if q > hi { q = hi } else if q < lo { q = lo }
                let u = UInt32(bitPattern: Int32(q))
                out.append(UInt8(u & 0xFF))
                out.append(UInt8((u >> 8) & 0xFF))
                if bits == 24 { out.append(UInt8((u >> 16) & 0xFF)) }
            }
        }
        return out
    }

    /// Steps 6 and 7: quantize and write one WAV file.
    public static func encode(_ a: Audio, _ s: Settings) throws -> [UInt8] {
        let pcm = try quantize(a, bits: s.bits, dither: s.dither)
        return Wav.write(sampleRate: a.sampleRate, channelCount: a.channels.count, bits: s.bits, pcm: pcm)
    }

    // MARK: Sections 9 and 10

    public static func sliceBounds(length: Int, count: Int) throws -> [Int] {
        guard (2...64).contains(count) else { throw ChoppedError.badSliceCount }
        guard length >= count else { throw ChoppedError.fewerFramesThanSlices }
        return (0...count).map { Int((Int64($0) * Int64(length)) / Int64(count)) }
    }

    public static func slices(_ a: Audio, count: Int) throws -> [Audio] {
        let b = try sliceBounds(length: a.frameCount, count: count)
        return (0..<count).map { k in
            Audio(sampleRate: a.sampleRate, channels: a.channels.map { Array($0[b[k]..<b[k + 1]]) })
        }
    }

    public static func chain(_ items: [Audio], slots: Int? = nil) throws -> Audio {
        guard let first = items.first else { throw ChoppedError.fewerSlotsThanInputs }
        let nch = first.channels.count
        for it in items where it.channels.count != nch || it.sampleRate != first.sampleRate {
            throw ChoppedError.chainMismatch
        }
        let slotCount = slots ?? items.count
        guard slotCount >= items.count else { throw ChoppedError.fewerSlotsThanInputs }
        let size = items.map(\.frameCount).max() ?? 0
        var out = [[Double]](repeating: [Double](repeating: 0.0, count: slotCount * size), count: nch)
        for (j, it) in items.enumerated() {
            for c in 0..<nch {
                out[c].replaceSubrange((j * size)..<(j * size + it.frameCount), with: it.channels[c])
            }
        }
        return Audio(sampleRate: first.sampleRate, channels: out)
    }
}
