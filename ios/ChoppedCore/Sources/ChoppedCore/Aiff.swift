// Spec sections 2a (reading AIFF) and 8a (writing AIFF). Every number in AIFF is big-endian.

public enum Aiff {
    // MARK: Reading

    public static func read(_ b: [UInt8]) throws -> Audio {
        guard b.count >= 12, Wav.ascii(b, 0) == "FORM" else { throw ChoppedError.notAiff }
        let form = Wav.ascii(b, 8)
        guard form == "AIFF" || form == "AIFC" else { throw ChoppedError.notAiff }
        let aifc = form == "AIFC"
        var pos = 12
        var comm: (channels: Int, frames: Int, bits: Int, rate: Int, comp: String)?
        var ssnd: (start: Int, available: Int)?
        while pos + 8 <= b.count {
            let id = Wav.ascii(b, pos)
            let size = Int(be32(b, pos + 4))
            let body = pos + 8
            if id == "COMM" {
                guard body + 18 <= b.count else { throw ChoppedError.badFormat }
                let rate = try extendedToRate(Array(b[(body + 8)..<(body + 18)]))
                var comp = "NONE"
                if aifc {
                    guard body + 22 <= b.count else { throw ChoppedError.badFormat }
                    comp = Wav.ascii(b, body + 18)
                }
                comm = (Int(be16(b, body)), Int(be32(b, body + 2)), Int(be16(b, body + 6)), rate, comp)
            } else if id == "SSND" {
                guard body + 8 <= b.count else { throw ChoppedError.badFormat }
                let offset = Int(be32(b, body))
                let start = body + 8 + offset
                let available = min(size - 8 - offset, b.count - start)
                ssnd = (start, max(available, 0))
            }
            pos = body + size + (size & 1)
        }
        guard let c = comm, let s = ssnd else { throw ChoppedError.noData }
        guard c.channels >= 1 else { throw ChoppedError.badFormat }
        let width = c.bits / 8
        let conv: (Int) -> Double
        switch (c.comp, c.bits) {
        case ("NONE", 8):
            conv = { o in Double(Int8(bitPattern: b[o])) / 128.0 }
        case ("NONE", 16):
            conv = { o in Double(Int16(bitPattern: be16(b, o))) / 32768.0 }
        case ("NONE", 24):
            conv = { o in
                var v = (Int(b[o]) << 16) | (Int(b[o + 1]) << 8) | Int(b[o + 2])
                if v >= 0x800000 { v -= 0x1000000 }
                return Double(v) / 8388608.0
            }
        case ("NONE", 32):
            conv = { o in Double(Int32(bitPattern: be32(b, o))) / 2147483648.0 }
        case ("sowt", 16):
            conv = { o in Double(Int16(bitPattern: Wav.u16(b, o))) / 32768.0 }
        case ("fl32", 32), ("FL32", 32):
            conv = { o in Double(Float(bitPattern: be32(b, o))) }
        case ("fl64", 64), ("FL64", 64):
            conv = { o in Double(bitPattern: (UInt64(be32(b, o)) << 32) | UInt64(be32(b, o + 4))) }
        default:
            throw ChoppedError.unsupportedFormat
        }
        let frameBytes = c.channels * width
        let n = min(c.frames, s.available / frameBytes)
        var channels = [[Double]](repeating: [Double](repeating: 0.0, count: n), count: c.channels)
        for i in 0..<n {
            let base = s.start + i * frameBytes
            for ch in 0..<c.channels { channels[ch][i] = conv(base + ch * width) }
        }
        return Audio(sampleRate: c.rate, channels: channels)
    }

    /// The 80-bit extended sample rate as a whole number of hertz, without floating point.
    static func extendedToRate(_ x: [UInt8]) throws -> Int {
        let e = (Int(x[0]) << 8) | Int(x[1])
        var m: UInt64 = 0
        for k in 2..<10 { m = (m << 8) | UInt64(x[k]) }
        let shift = 16383 + 63 - (e & 0x7FFF)
        guard e & 0x8000 == 0, (0..<64).contains(shift) else { throw ChoppedError.unsupportedSampleRate }
        if shift > 0, m & ((UInt64(1) << UInt64(shift)) - 1) != 0 { throw ChoppedError.unsupportedSampleRate }
        let r = m >> UInt64(shift)
        guard r <= UInt64(Int.max) else { throw ChoppedError.unsupportedSampleRate }
        return Int(r)
    }

    // MARK: Writing

    /// Plain AIFF: FORM, COMM (18 bytes) and SSND, nothing else. `pcm` is big-endian interleaved.
    public static func write(sampleRate: Int, channelCount: Int, bits: Int, frames: Int, pcm: [UInt8]) -> [UInt8] {
        let dataSize = pcm.count
        let pad = dataSize & 1
        var out: [UInt8] = []
        out.reserveCapacity(54 + dataSize + pad)
        out += Array("FORM".utf8)
        putBE32(&out, UInt32(4 + 26 + 16 + dataSize + pad))
        out += Array("AIFF".utf8)
        out += Array("COMM".utf8)
        putBE32(&out, 18)
        putBE16(&out, UInt16(channelCount))
        putBE32(&out, UInt32(frames))
        putBE16(&out, UInt16(bits))
        out += rateToExtended(sampleRate)
        out += Array("SSND".utf8)
        putBE32(&out, UInt32(8 + dataSize))
        putBE32(&out, 0)
        putBE32(&out, 0)
        out += pcm
        if pad == 1 { out.append(0) }
        return out
    }

    /// A positive integer rate as an 80-bit extended float, without floating point.
    static func rateToExtended(_ r: Int) -> [UInt8] {
        let v = UInt64(r)
        let p = 63 - v.leadingZeroBitCount
        let exponent = UInt16(16383 + p)
        let mantissa = v << UInt64(63 - p)
        var out: [UInt8] = []
        putBE16(&out, exponent)
        for k in stride(from: 56, through: 0, by: -8) { out.append(UInt8((mantissa >> UInt64(k)) & 0xFF)) }
        return out
    }

    // MARK: Byte helpers

    static func be16(_ b: [UInt8], _ o: Int) -> UInt16 {
        (UInt16(b[o]) << 8) | UInt16(b[o + 1])
    }

    static func be32(_ b: [UInt8], _ o: Int) -> UInt32 {
        (UInt32(b[o]) << 24) | (UInt32(b[o + 1]) << 16) | (UInt32(b[o + 2]) << 8) | UInt32(b[o + 3])
    }

    static func putBE16(_ out: inout [UInt8], _ v: UInt16) {
        out.append(UInt8(v >> 8))
        out.append(UInt8(v & 0xFF))
    }

    static func putBE32(_ out: inout [UInt8], _ v: UInt32) {
        out.append(UInt8(v >> 24))
        out.append(UInt8((v >> 16) & 0xFF))
        out.append(UInt8((v >> 8) & 0xFF))
        out.append(UInt8(v & 0xFF))
    }
}

/// Section 1, steps 1 and 7: the first four bytes pick the reader; `Settings.format` picks the writer.
public enum AudioFile {
    public static func read(_ bytes: [UInt8]) throws -> Audio {
        if bytes.count >= 4, Wav.ascii(bytes, 0) == "FORM" { return try Aiff.read(bytes) }
        return try Wav.read(bytes)
    }

    public static func fileExtension(_ format: OutputFormat) -> String {
        format == .aiff ? "aif" : "wav"
    }
}
