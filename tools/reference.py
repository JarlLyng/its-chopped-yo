"""Reference implementation of specs/audio-processing.md, version 0.3.

Standard library only. This is the spec written as code: the Swift and Kotlin cores must produce
the same bytes as this file. Keep it plain and close to the spec text, not fast.
"""

from __future__ import annotations

import math
import struct
from dataclasses import dataclass

import native  # tools/native.py: libsamplerate built for this machine

SPEC_VERSION = "0.3"

# Section 6: normalize targets, dBFS -> nearest double to 10^(dB/20). Never computed with pow.
NORMALIZE_TARGETS = {
    0.0: 1.0,
    -0.1: 0.9885530946569389,
    -0.3: 0.9660508789898133,
    -0.5: 0.9440608762859234,
    -1.0: 0.8912509381337456,
    -3.0: 0.7079457843841379,
    -6.0: 0.5011872336272722,
}

# Section 3: downmix weights before normalisation, by channel count.
DOWNMIX_K = {
    1: [1.0],
    2: [1.0, 1.0],
    3: [1.0, 1.0, 1.414],
    4: [1.0, 1.0, 1.0, 1.0],
    5: [1.0, 1.0, 0.707, 0.707, 0.707],
    6: [1.0, 1.0, 0.707, 0.0, 0.707, 0.707],
}

DITHER_SEED = 0x2545F491


class SpecError(Exception):
    pass


@dataclass
class Audio:
    rate: int
    channels: list[list[float]]  # channels[ch][frame]

    @property
    def frames(self) -> int:
        return len(self.channels[0]) if self.channels else 0


# --- Section 2: reading -------------------------------------------------------------------------

def read_wav(data: bytes) -> Audio:
    if len(data) < 12 or data[0:4] != b"RIFF" or data[8:12] != b"WAVE":
        raise SpecError("not a RIFF/WAVE file")
    pos = 12
    fmt = None
    while pos + 8 <= len(data):
        cid = data[pos:pos + 4]
        size = struct.unpack_from("<I", data, pos + 4)[0]
        body = pos + 8
        if cid == b"fmt ":
            tag, ch, rate, _byte_rate, block, bits = struct.unpack_from("<HHIIHH", data, body)
            if tag == 0xFFFE:
                tag = struct.unpack_from("<H", data, body + 24)[0]
            fmt = (tag, ch, rate, block, bits)
        elif cid == b"data":
            if fmt is None:
                raise SpecError("data before fmt")
            available = len(data) - body
            data_size = min(size, available)
            return _decode(data, body, data_size, fmt)
        pos = body + size + (size & 1)
    raise SpecError("no data chunk")


def _decode(data: bytes, start: int, size: int, fmt) -> Audio:
    tag, ch, rate, block, bits = fmt
    if ch < 1 or block < 1:
        raise SpecError("bad fmt")
    frames = size // block
    out = [[0.0] * frames for _ in range(ch)]
    width = bits // 8
    if tag == 1 and bits == 8:
        conv = lambda o: (data[o] - 128) / 128.0
    elif tag == 1 and bits == 16:
        conv = lambda o: struct.unpack_from("<h", data, o)[0] / 32768.0
    elif tag == 1 and bits == 24:
        def conv(o):
            v = data[o] | (data[o + 1] << 8) | (data[o + 2] << 16)
            if v >= 0x800000:
                v -= 0x1000000
            return v / 8388608.0
    elif tag == 1 and bits == 32:
        conv = lambda o: struct.unpack_from("<i", data, o)[0] / 2147483648.0
    elif tag == 3 and bits == 32:
        conv = lambda o: struct.unpack_from("<f", data, o)[0]
    elif tag == 3 and bits == 64:
        conv = lambda o: struct.unpack_from("<d", data, o)[0]
    else:
        raise SpecError("unsupported format")
    for i in range(frames):
        base = start + i * block
        for c in range(ch):
            out[c][i] = conv(base + c * width)
    return Audio(rate, out)


def read_audio(data: bytes) -> Audio:
    """Section 1, step 1: the first four bytes decide."""
    if data[0:4] == b"FORM":
        return read_aiff(data)
    return read_wav(data)


# --- Section 2a: reading AIFF -------------------------------------------------------------------

def extended_to_rate(b: bytes) -> int:
    e = (b[0] << 8) | b[1]
    m = int.from_bytes(b[2:10], "big")
    shift = 16383 + 63 - (e & 0x7FFF)
    if e & 0x8000 or not (0 <= shift < 64) or (m & ((1 << shift) - 1)) != 0:
        raise SpecError("unsupported sample rate")
    return m >> shift


def read_aiff(data: bytes) -> Audio:
    if len(data) < 12 or data[0:4] != b"FORM" or data[8:12] not in (b"AIFF", b"AIFC"):
        raise SpecError("not an AIFF file")
    aifc = data[8:12] == b"AIFC"
    pos = 12
    comm = None
    ssnd = None
    while pos + 8 <= len(data):
        cid = data[pos:pos + 4]
        size = struct.unpack_from(">I", data, pos + 4)[0]
        body = pos + 8
        if cid == b"COMM":
            ch, frames, bits = struct.unpack_from(">HIH", data, body)
            rate = extended_to_rate(data[body + 8:body + 18])
            comp = data[body + 18:body + 22] if aifc else b"NONE"
            comm = (ch, frames, bits, rate, comp)
        elif cid == b"SSND":
            offset = struct.unpack_from(">I", data, body)[0]
            start = body + 8 + offset
            available = min(size - 8 - offset, len(data) - start)
            ssnd = (start, max(available, 0))
        pos = body + size + (size & 1)
    if comm is None or ssnd is None:
        raise SpecError("no COMM or SSND chunk")
    ch, frames, bits, rate, comp = comm
    if ch < 1:
        raise SpecError("bad fmt")
    start, available = ssnd
    width = bits // 8
    if comp == b"NONE" and bits == 8:
        conv = lambda o: struct.unpack_from(">b", data, o)[0] / 128.0
    elif comp == b"NONE" and bits == 16:
        conv = lambda o: struct.unpack_from(">h", data, o)[0] / 32768.0
    elif comp == b"NONE" and bits == 24:
        def conv(o):
            v = (data[o] << 16) | (data[o + 1] << 8) | data[o + 2]
            if v >= 0x800000:
                v -= 0x1000000
            return v / 8388608.0
    elif comp == b"NONE" and bits == 32:
        conv = lambda o: struct.unpack_from(">i", data, o)[0] / 2147483648.0
    elif comp == b"sowt" and bits == 16:
        conv = lambda o: struct.unpack_from("<h", data, o)[0] / 32768.0
    elif comp in (b"fl32", b"FL32") and bits == 32:
        conv = lambda o: struct.unpack_from(">f", data, o)[0]
    elif comp in (b"fl64", b"FL64") and bits == 64:
        conv = lambda o: struct.unpack_from(">d", data, o)[0]
    else:
        raise SpecError("unsupported format")
    frame_bytes = ch * width
    n = min(frames, available // frame_bytes)
    out = [[0.0] * n for _ in range(ch)]
    for i in range(n):
        base = start + i * frame_bytes
        for c in range(ch):
            out[c][i] = conv(base + c * width)
    return Audio(rate, out)


# --- Sections 3 to 6 ---------------------------------------------------------------------------

def apply_channels(a: Audio, mode: str) -> Audio:
    c = len(a.channels)
    if mode == "keep":
        return a
    if mode == "left":
        return Audio(a.rate, [a.channels[0]])
    if mode == "right":
        return Audio(a.rate, [a.channels[1] if c > 1 else a.channels[0]])
    if mode != "sum":
        raise SpecError("unknown mono mode")
    k = DOWNMIX_K.get(c, [1.0] * c)
    total = 0.0
    for v in k:
        total = total + v
    w = [v / total for v in k]
    out = [0.0] * a.frames
    for i in range(a.frames):
        acc = 0.0
        for ch in range(c):
            acc = acc + a.channels[ch][i] * w[ch]
        out[i] = acc
    return Audio(a.rate, [out])


def trim(a: Audio, rng) -> Audio:
    if rng is None:
        return a
    start = min(max(rng[0], 0), a.frames)
    end = min(max(rng[1], 0), a.frames)
    if end <= start:
        raise SpecError("empty after trim")
    return Audio(a.rate, [ch[start:end] for ch in a.channels])


def _gain(t: float, curve: str) -> float:
    if curve == "linear":
        return t
    if curve == "equalPower":
        return math.sqrt(t)
    raise SpecError("unknown curve")


def fade(a: Audio, fade_in, fade_out) -> Audio:
    length = a.frames
    chans = [list(ch) for ch in a.channels]
    if fade_in:
        n = min(fade_in["frames"], length)
        for i in range(n):
            g = _gain(float(i) / float(n), fade_in["curve"])
            for ch in chans:
                ch[i] = ch[i] * g
    if fade_out:
        n = min(fade_out["frames"], length)
        for i in range(n):
            g = _gain(float(n - 1 - i) / float(n), fade_out["curve"])
            f = length - n + i
            for ch in chans:
                ch[f] = ch[f] * g
    return Audio(a.rate, chans)


def resample(a: Audio, rate) -> Audio:
    """Section 5a: libsamplerate's src_simple, SRC_SINC_BEST_QUALITY, through 32-bit floats."""
    if rate is None or rate == a.rate:
        return a
    ratio = float(rate) / float(a.rate)
    if ratio < 1.0 / 256.0 or ratio > 256.0:
        raise SpecError("bad ratio")
    nch = len(a.channels)
    frames = a.frames
    interleaved = [a.channels[c][i] for i in range(frames) for c in range(nch)]
    capacity = math.floor(float(frames) * ratio) + 256
    try:
        out = native.src_simple(interleaved, frames, nch, ratio, capacity, converter=0)
    except RuntimeError as e:
        raise SpecError("resample failed") from e
    gen = len(out) // nch
    return Audio(int(rate), [[out[i * nch + c] for i in range(gen)] for c in range(nch)])


def normalize(a: Audio, db) -> Audio:
    if db is None:
        return a
    if db not in NORMALIZE_TARGETS:
        raise SpecError("normalize target not in the table")
    target = NORMALIZE_TARGETS[db]
    peak = 0.0
    for ch in a.channels:
        for x in ch:
            ax = abs(x)
            if ax > peak:
                peak = ax
    if peak == 0.0:
        return a
    gain = target / peak
    return Audio(a.rate, [[x * gain for x in ch] for ch in a.channels])


def process(a: Audio, s: dict) -> Audio:
    a = apply_channels(a, s.get("mono", "keep"))
    a = trim(a, s.get("trim"))
    a = fade(a, s.get("fadeIn"), s.get("fadeOut"))
    a = resample(a, s.get("sampleRate"))
    a = normalize(a, s.get("normalize"))
    return a


# --- Sections 7 and 8 --------------------------------------------------------------------------

class XorShift32:
    def __init__(self, seed: int = DITHER_SEED):
        self.s = seed & 0xFFFFFFFF

    def next(self) -> float:
        s = self.s
        s ^= (s << 13) & 0xFFFFFFFF
        s ^= s >> 17
        s ^= (s << 5) & 0xFFFFFFFF
        self.s = s
        return (s >> 8) / 16777216.0


def quantize(a: Audio, bits: int, dither: bool) -> list[int]:
    """Section 7: interleaved integers in file order."""
    if bits not in (16, 24):
        raise SpecError("bits must be 16 or 24")
    scale = float(1 << (bits - 1))
    hi = (1 << (bits - 1)) - 1
    lo = -(1 << (bits - 1))
    rng = XorShift32()
    out = []
    nch = len(a.channels)
    for i in range(a.frames):
        for c in range(nch):
            v = a.channels[c][i] * scale
            if dither:
                r1 = rng.next()
                r2 = rng.next()
                v = v + (r1 + r2 - 1.0)
            q = math.floor(v + 0.5)
            out.append(hi if q > hi else lo if q < lo else q)
    return out


def pack(samples: list[int], bits: int, big_endian: bool) -> bytes:
    width = bits // 8
    order = "big" if big_endian else "little"
    mask = (1 << bits) - 1
    return b"".join((q & mask).to_bytes(width, order) for q in samples)


def write_wav(rate: int, nch: int, bits: int, pcm: bytes) -> bytes:
    data_size = len(pcm)
    pad = data_size & 1
    block = nch * bits // 8
    header = b"RIFF" + struct.pack("<I", 4 + 24 + 8 + data_size + pad) + b"WAVE"
    header += b"fmt " + struct.pack("<IHHIIHH", 16, 1, nch, rate, rate * block, block, bits)
    header += b"data" + struct.pack("<I", data_size)
    return header + pcm + (b"\x00" if pad else b"")


def rate_to_extended(r: int) -> bytes:
    """Section 8a: a positive integer rate as an 80-bit extended float, without floating point."""
    if r <= 0:
        raise SpecError("unsupported sample rate")
    p = r.bit_length() - 1
    return (16383 + p).to_bytes(2, "big") + (r << (63 - p)).to_bytes(8, "big")


def write_aiff(rate: int, nch: int, bits: int, frames: int, pcm: bytes) -> bytes:
    data_size = len(pcm)
    pad = data_size & 1
    out = b"FORM" + struct.pack(">I", 4 + 26 + 16 + data_size + pad) + b"AIFF"
    out += b"COMM" + struct.pack(">IHIH", 18, nch, frames, bits) + rate_to_extended(rate)
    out += b"SSND" + struct.pack(">III", 8 + data_size, 0, 0)
    return out + pcm + (b"\x00" if pad else b"")


def encode(a: Audio, s: dict) -> bytes:
    """Steps 6 and 7."""
    bits = s["bits"]
    samples = quantize(a, bits, s.get("dither", False))
    if s.get("format", "wav") == "aiff":
        return write_aiff(a.rate, len(a.channels), bits, a.frames, pack(samples, bits, True))
    return write_wav(a.rate, len(a.channels), bits, pack(samples, bits, False))


def extension(s: dict) -> str:
    return ".aif" if s.get("format", "wav") == "aiff" else ".wav"


# --- Sections 9 and 10, and a whole test case ---------------------------------------------------

def slice_bounds(length: int, n: int) -> list[int]:
    if n < 2 or n > 64:
        raise SpecError("slices must be 2 to 64")
    if length < n:
        raise SpecError("fewer frames than slices")
    return [(k * length) // n for k in range(n + 1)]


def chain(items: list[Audio], slots) -> Audio:
    nch = len(items[0].channels)
    rate = items[0].rate
    for it in items:
        if len(it.channels) != nch or it.rate != rate:
            raise SpecError("chain inputs differ in channels or rate")
    slots = len(items) if slots is None else slots
    if slots < len(items):
        raise SpecError("fewer slots than inputs")
    size = max(it.frames for it in items)
    out = [[0.0] * (slots * size) for _ in range(nch)]
    for j, it in enumerate(items):
        for c in range(nch):
            out[c][j * size:j * size + it.frames] = it.channels[c]
    return Audio(rate, out)


def run_case(case: dict, read_input) -> dict[str, bytes]:
    """Returns {output file name: bytes}. read_input(name) returns the input file's bytes."""
    s = case["settings"]
    cid = case["id"]
    ext = extension(s)
    mode = case.get("mode", "single")
    if mode == "single":
        a = process(read_audio(read_input(case["inputs"][0])), s)
        return {f"{cid}{ext}": encode(a, s)}
    if mode == "slices":
        a = process(read_audio(read_input(case["inputs"][0])), s)
        b = slice_bounds(a.frames, case["slices"])
        out = {}
        for k in range(case["slices"]):
            part = Audio(a.rate, [ch[b[k]:b[k + 1]] for ch in a.channels])
            out[f"{cid}_{k + 1:02d}{ext}"] = encode(part, s)
        return out
    if mode == "chain":
        items = [process(read_audio(read_input(n)), s) for n in case["inputs"]]
        return {f"{cid}{ext}": encode(chain(items, case.get("slots")), s)}
    raise SpecError("unknown mode")
