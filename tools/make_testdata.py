"""Writes testdata/: inputs, cases.json, expected output files and expected.json.

    python3 tools/make_testdata.py            # keep existing inputs, rebuild cases and expected
    python3 tools/make_testdata.py --inputs   # also rewrite the inputs

The inputs are committed. They are made with math.sin, which can differ in the last bit between
machines, so they are only rewritten on request. Everything after the input is the spec.
"""

from __future__ import annotations

import hashlib
import json
import math
import struct
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import reference as ref  # noqa: E402

ROOT = Path(__file__).resolve().parent.parent / "testdata"
INPUT = ROOT / "input"
EXPECTED = ROOT / "expected"


# --- Input writers (only for making test inputs; the cores never write these formats) ----------

def _pcm(samples_by_ch, bits, tag):
    nch = len(samples_by_ch)
    frames = len(samples_by_ch[0])
    out = bytearray()
    for i in range(frames):
        for c in range(nch):
            x = samples_by_ch[c][i]
            if tag == 3 and bits == 32:
                out += struct.pack("<f", x)
            elif tag == 3 and bits == 64:
                out += struct.pack("<d", x)
            elif bits == 8:
                out.append(max(0, min(255, int(round(x * 127.0)) + 128)))
            else:
                full = 1 << (bits - 1)
                q = max(-full, min(full - 1, int(round(x * (full - 1)))))
                out += (q & ((1 << bits) - 1)).to_bytes(bits // 8, "little")
    return bytes(out)


FORCE_INPUTS = "--inputs" in sys.argv


def write_input(name, rate, samples_by_ch, bits, tag=1, extensible=False, extra_chunk=None):
    if (INPUT / name).exists() and not FORCE_INPUTS:
        return  # committed inputs stay as they are; see the module docstring
    nch = len(samples_by_ch)
    block = nch * bits // 8
    data = _pcm(samples_by_ch, bits, tag)
    if extensible:
        guid_tail = bytes.fromhex("000000001000800000aa00389b71")
        fmt_body = struct.pack("<HHIIHH", 0xFFFE, nch, rate, rate * block, block, bits)
        fmt_body += struct.pack("<HHI", 22, bits, 3 if nch == 2 else 0)
        fmt_body += struct.pack("<H", tag) + guid_tail
    else:
        fmt_body = struct.pack("<HHIIHH", tag, nch, rate, rate * block, block, bits)
    chunks = b"fmt " + struct.pack("<I", len(fmt_body)) + fmt_body
    if extra_chunk:
        cid, body = extra_chunk
        chunks += cid + struct.pack("<I", len(body)) + body + (b"\x00" if len(body) & 1 else b"")
    chunks += b"data" + struct.pack("<I", len(data)) + data + (b"\x00" if len(data) & 1 else b"")
    blob = b"RIFF" + struct.pack("<I", 4 + len(chunks)) + b"WAVE" + chunks
    (INPUT / name).write_bytes(blob)


def sine(freq, amp, frames, rate, phase=0.0):
    return [amp * math.sin(2.0 * math.pi * freq * i / rate + phase) for i in range(frames)]


def noise(amp, frames, seed):
    rng = ref.XorShift32(seed)
    return [amp * (2.0 * rng.next() - 1.0) for _ in range(frames)]


def make_inputs():
    INPUT.mkdir(parents=True, exist_ok=True)
    r = 48000
    write_input("noise_right_st24.wav", r, [[0.0] * 4800, noise(0.5, 4800, 7)], 24)
    write_input("sine_st24.wav", r, [sine(440, 0.8, 9600, r), sine(660, 0.6, 9600, r)], 24)
    write_input("sine_m16.wav", r, [sine(1000, 0.7, 24000, r)], 16)
    write_input("quiet_m24.wav", r, [sine(220, 0.25, 12000, r)], 24)
    write_input("six_ch_24.wav", r,
                [sine(100 * (c + 1), 0.4, 4800, r) for c in range(6)], 24)
    overs = [[1.5 * v for v in sine(330, 1.0, 4800, r)], [1.2 * v for v in sine(495, 1.0, 4800, r)]]
    write_input("float_overs_st32f.wav", r, overs, 32, tag=3)
    write_input("u8_m.wav", 22050, [sine(300, 0.9, 2205, 22050)], 8)
    write_input("ext_st24.wav", r, [sine(250, 0.5, 4800, r), noise(0.3, 4800, 11)], 24,
                extensible=True)
    write_input("odd_m24.wav", r, [sine(510, 0.6, 4801, r)], 24)
    write_input("float64_m.wav", r, [sine(777, 0.9, 4800, r)], 64, tag=3)
    write_input("sine_m16_44k.wav", 44100, [sine(1000, 0.7, 4410, 44100)], 16)
    write_input("chunks_m16.wav", r, [sine(880, 0.5, 2400, r)], 16,
                extra_chunk=(b"LIST", b"INFOISFT\x05\x00\x00\x00test\x00"))
    # Version 0.2: a 96 kHz stereo file for resampling down.
    write_input("sine_st24_96k.wav", 96000,
                [sine(1000, 0.8, 9600, 96000), sine(15000, 0.5, 9600, 96000)], 24)


def cases():
    base16 = {"mono": "keep", "bits": 16, "dither": False}
    return [
        {"id": "rightpan_sum_24", "inputs": ["noise_right_st24.wav"],
         "settings": {"mono": "sum", "bits": 24, "dither": False}},
        {"id": "stereo_keep_16_dither", "inputs": ["sine_st24.wav"],
         "settings": {"mono": "keep", "bits": 16, "dither": True}},
        {"id": "trim_fade_linear_16", "inputs": ["sine_m16.wav"],
         "settings": {**base16, "trim": [1000, 20000],
                      "fadeIn": {"frames": 480, "curve": "linear"},
                      "fadeOut": {"frames": 960, "curve": "linear"}}},
        {"id": "trim_fade_equalpower_16_dither", "inputs": ["sine_m16.wav"],
         "settings": {"mono": "keep", "bits": 16, "dither": True, "trim": [1000, 20000],
                      "fadeIn": {"frames": 480, "curve": "equalPower"},
                      "fadeOut": {"frames": 960, "curve": "equalPower"}}},
        {"id": "normalize_quiet_24", "inputs": ["quiet_m24.wav"],
         "settings": {"mono": "keep", "bits": 24, "dither": False, "normalize": -0.3}},
        {"id": "surround51_sum_24", "inputs": ["six_ch_24.wav"],
         "settings": {"mono": "sum", "bits": 24, "dither": False}},
        {"id": "float_overs_clamp_16", "inputs": ["float_overs_st32f.wav"],
         "settings": base16},
        {"id": "float_overs_normalized_16", "inputs": ["float_overs_st32f.wav"],
         "settings": {**base16, "normalize": -1.0}},
        {"id": "u8_to_16", "inputs": ["u8_m.wav"], "settings": base16},
        {"id": "extensible_left_24", "inputs": ["ext_st24.wav"],
         "settings": {"mono": "left", "bits": 24, "dither": False}},
        {"id": "extensible_right_16_dither", "inputs": ["ext_st24.wav"],
         "settings": {"mono": "right", "bits": 16, "dither": True}},
        {"id": "odd_frames_24", "inputs": ["odd_m24.wav"],
         "settings": {"mono": "keep", "bits": 24, "dither": False}},
        {"id": "float64_to_24", "inputs": ["float64_m.wav"],
         "settings": {"mono": "keep", "bits": 24, "dither": False, "normalize": -0.1}},
        {"id": "rate_44k_kept_24", "inputs": ["sine_m16_44k.wav"],
         "settings": {"mono": "keep", "bits": 24, "dither": False}},
        {"id": "skips_unknown_chunks_16", "inputs": ["chunks_m16.wav"], "settings": base16},
        {"id": "slices_8", "mode": "slices", "slices": 8, "inputs": ["sine_m16.wav"],
         "settings": {"mono": "sum", "bits": 16, "dither": True,
                      "fadeIn": {"frames": 48, "curve": "linear"}}},
        {"id": "chain_3", "mode": "chain",
         "inputs": ["sine_m16.wav", "odd_m24.wav", "quiet_m24.wav"],
         "settings": {"mono": "sum", "bits": 16, "dither": True, "normalize": -0.3}},
        # Version 0.2: resampling.
        {"id": "resample_44k_to_48k_16_dither", "inputs": ["sine_m16_44k.wav"],
         "settings": {"mono": "keep", "bits": 16, "dither": True, "sampleRate": 48000}},
        {"id": "resample_to_46875_sum_16", "inputs": ["sine_st24.wav"],
         "settings": {"mono": "sum", "bits": 16, "dither": False, "sampleRate": 46875}},
        {"id": "resample_96k_stereo_to_48k_24", "inputs": ["sine_st24_96k.wav"],
         "settings": {"mono": "keep", "bits": 24, "dither": False, "sampleRate": 48000}},
        {"id": "resample_u8_22k_to_48k_16", "inputs": ["u8_m.wav"],
         "settings": {**base16, "sampleRate": 48000}},
        {"id": "resample_down_to_22050_24", "inputs": ["noise_right_st24.wav"],
         "settings": {"mono": "sum", "bits": 24, "dither": False, "sampleRate": 22050}},
        {"id": "resample_six_ch_to_44k_24", "inputs": ["six_ch_24.wav"],
         "settings": {"mono": "keep", "bits": 24, "dither": False, "sampleRate": 44100}},
        {"id": "resample_after_fade_normalized_16", "inputs": ["sine_m16.wav"],
         "settings": {"mono": "keep", "bits": 16, "dither": True, "trim": [0, 12000],
                      "fadeOut": {"frames": 2400, "curve": "equalPower"},
                      "sampleRate": 32000, "normalize": -0.1}},
        {"id": "chain_mixed_rates_48k", "mode": "chain",
         "inputs": ["sine_m16_44k.wav", "sine_m16.wav", "u8_m.wav"],
         "settings": {"mono": "sum", "bits": 16, "dither": True, "normalize": -0.3,
                      "sampleRate": 48000}},
        {"id": "chain_3_slots_4", "mode": "chain", "slots": 4,
         "inputs": ["sine_m16.wav", "odd_m24.wav", "quiet_m24.wav"],
         "settings": {"mono": "sum", "bits": 24, "dither": False}},
    ]


def main():
    make_inputs()
    all_cases = cases()
    (ROOT / "cases.json").write_text(
        json.dumps({"specVersion": ref.SPEC_VERSION, "cases": all_cases}, indent=2) + "\n")
    EXPECTED.mkdir(parents=True, exist_ok=True)
    for old in EXPECTED.glob("*.wav"):
        old.unlink()
    expected = {}
    for case in all_cases:
        outputs = ref.run_case(case, lambda n: (INPUT / n).read_bytes())
        expected[case["id"]] = {}
        for name, blob in sorted(outputs.items()):
            (EXPECTED / name).write_bytes(blob)
            expected[case["id"]][name] = hashlib.sha256(blob).hexdigest()
    (ROOT / "expected.json").write_text(
        json.dumps({"specVersion": ref.SPEC_VERSION, "outputs": expected}, indent=2) + "\n")
    files = sum(len(v) for v in expected.values())
    print(f"{len(all_cases)} cases, {files} expected files written to {ROOT}")


if __name__ == "__main__":
    main()
