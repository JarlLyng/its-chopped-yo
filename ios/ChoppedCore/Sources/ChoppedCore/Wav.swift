// Spec sections 2 (reading) and 8 (writing).

public enum Wav {
    // MARK: Reading

    public static func read(_ bytes: [UInt8]) throws -> Audio {
        guard bytes.count >= 12, ascii(bytes, 0) == "RIFF", ascii(bytes, 8) == "WAVE" else {
            throw ChoppedError.notWave
        }
        var pos = 12
        var fmt: (tag: Int, channels: Int, rate: Int, block: Int, bits: Int)?
        while pos + 8 <= bytes.count {
            let id = ascii(bytes, pos)
            let size = Int(u32(bytes, pos + 4))
            let body = pos + 8
            if id == "fmt " {
                guard body + 16 <= bytes.count else { throw ChoppedError.badFormat }
                var tag = Int(u16(bytes, body))
                if tag == 0xFFFE {
                    guard body + 26 <= bytes.count else { throw ChoppedError.badFormat }
                    tag = Int(u16(bytes, body + 24))
                }
                fmt = (tag, Int(u16(bytes, body + 2)), Int(u32(bytes, body + 4)),
                       Int(u16(bytes, body + 12)), Int(u16(bytes, body + 14)))
            } else if id == "data" {
                guard let f = fmt else { throw ChoppedError.dataBeforeFmt }
                let dataSize = min(size, bytes.count - body)
                return try decode(bytes, start: body, size: dataSize, fmt: f)
            }
            pos = body + size + (size & 1)
        }
        throw ChoppedError.noData
    }

    private static func decode(_ b: [UInt8], start: Int, size: Int,
                               fmt: (tag: Int, channels: Int, rate: Int, block: Int, bits: Int)) throws -> Audio {
        guard fmt.channels >= 1, fmt.block >= 1 else { throw ChoppedError.badFormat }
        let frames = size / fmt.block
        let width = fmt.bits / 8
        let conv: (Int) -> Double
        switch (fmt.tag, fmt.bits) {
        case (1, 8):
            conv = { o in Double(Int(b[o]) - 128) / 128.0 }
        case (1, 16):
            conv = { o in Double(Int16(bitPattern: u16(b, o))) / 32768.0 }
        case (1, 24):
            conv = { o in
                var v = Int(b[o]) | (Int(b[o + 1]) << 8) | (Int(b[o + 2]) << 16)
                if v >= 0x800000 { v -= 0x1000000 }
                return Double(v) / 8388608.0
            }
        case (1, 32):
            conv = { o in Double(Int32(bitPattern: u32(b, o))) / 2147483648.0 }
        case (3, 32):
            conv = { o in Double(Float(bitPattern: u32(b, o))) }
        case (3, 64):
            conv = { o in Double(bitPattern: UInt64(u32(b, o)) | (UInt64(u32(b, o + 4)) << 32)) }
        default:
            throw ChoppedError.unsupportedFormat
        }
        var channels = [[Double]](repeating: [Double](repeating: 0.0, count: frames), count: fmt.channels)
        for i in 0..<frames {
            let base = start + i * fmt.block
            for c in 0..<fmt.channels {
                channels[c][i] = conv(base + c * width)
            }
        }
        return Audio(sampleRate: fmt.rate, channels: channels)
    }

    // MARK: Writing

    /// A PCM WAV with a 44-byte header and nothing else; `pcm` is interleaved samples.
    public static func write(sampleRate: Int, channelCount: Int, bits: Int, pcm: [UInt8]) -> [UInt8] {
        let dataSize = pcm.count
        let pad = dataSize & 1
        let block = channelCount * bits / 8
        var out: [UInt8] = []
        out.reserveCapacity(44 + dataSize + pad)
        out += Array("RIFF".utf8)
        put32(&out, UInt32(4 + 24 + 8 + dataSize + pad))
        out += Array("WAVE".utf8)
        out += Array("fmt ".utf8)
        put32(&out, 16)
        put16(&out, 1)
        put16(&out, UInt16(channelCount))
        put32(&out, UInt32(sampleRate))
        put32(&out, UInt32(sampleRate * block))
        put16(&out, UInt16(block))
        put16(&out, UInt16(bits))
        out += Array("data".utf8)
        put32(&out, UInt32(dataSize))
        out += pcm
        if pad == 1 { out.append(0) }
        return out
    }

    // MARK: Byte helpers

    static func ascii(_ b: [UInt8], _ o: Int) -> String {
        String(decoding: b[o..<o + 4], as: UTF8.self)
    }

    static func u16(_ b: [UInt8], _ o: Int) -> UInt16 {
        UInt16(b[o]) | (UInt16(b[o + 1]) << 8)
    }

    static func u32(_ b: [UInt8], _ o: Int) -> UInt32 {
        UInt32(b[o]) | (UInt32(b[o + 1]) << 8) | (UInt32(b[o + 2]) << 16) | (UInt32(b[o + 3]) << 24)
    }

    static func put16(_ out: inout [UInt8], _ v: UInt16) {
        out.append(UInt8(v & 0xFF))
        out.append(UInt8(v >> 8))
    }

    static func put32(_ out: inout [UInt8], _ v: UInt32) {
        out.append(UInt8(v & 0xFF))
        out.append(UInt8((v >> 8) & 0xFF))
        out.append(UInt8((v >> 16) & 0xFF))
        out.append(UInt8(v >> 24))
    }
}
