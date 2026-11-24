"""Builds libsamplerate for the machine running Python, for the reference implementation.

The flags match every other build of third_party/libsamplerate (see its README.md): the same
source and no fused multiply-add give the same bytes on every platform.
"""

from __future__ import annotations

import ctypes
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
LIB_SRC = ROOT / "third_party" / "libsamplerate"
BUILD = ROOT / "tools" / "build"

FLAGS = [
    "-O2", "-ffp-contract=off",
    "-DENABLE_SINC_BEST_CONVERTER", "-DENABLE_SINC_MEDIUM_CONVERTER", "-DENABLE_SINC_FAST_CONVERTER",
    "-DHAVE_STDBOOL_H=1", '-DPACKAGE="libsamplerate"', '-DVERSION="0.2.2"',
    "-DCPU_CLIPS_POSITIVE=0", "-DCPU_CLIPS_NEGATIVE=0",
]
SOURCES = ["samplerate.c", "src_sinc.c", "src_linear.c", "src_zoh.c"]


def library_path() -> Path:
    suffix = ".dylib" if sys.platform == "darwin" else ".so"
    return BUILD / f"libsamplerate{suffix}"


def build() -> Path:
    out = library_path()
    sources = [LIB_SRC / "src" / s for s in SOURCES]
    newest = max(p.stat().st_mtime for p in sources + [Path(__file__)])
    if out.exists() and out.stat().st_mtime >= newest:
        return out
    BUILD.mkdir(parents=True, exist_ok=True)
    cmd = ["cc", "-shared", "-fPIC", *FLAGS, f"-I{LIB_SRC / 'include'}",
           *[str(s) for s in sources], "-lm", "-o", str(out)]
    subprocess.run(cmd, check=True)
    return out


class SRC_DATA(ctypes.Structure):
    _fields_ = [
        ("data_in", ctypes.POINTER(ctypes.c_float)),
        ("data_out", ctypes.POINTER(ctypes.c_float)),
        ("input_frames", ctypes.c_long),
        ("output_frames", ctypes.c_long),
        ("input_frames_used", ctypes.c_long),
        ("output_frames_gen", ctypes.c_long),
        ("end_of_input", ctypes.c_int),
        ("src_ratio", ctypes.c_double),
    ]


_lib = None


def src_simple(interleaved: list[float], frames: int, channels: int, ratio: float,
               capacity: int, converter: int = 0) -> list[float]:
    """Calls libsamplerate's src_simple. Returns the generated interleaved frames as floats."""
    global _lib
    if _lib is None:
        _lib = ctypes.CDLL(str(build()))
        _lib.src_simple.argtypes = [ctypes.POINTER(SRC_DATA), ctypes.c_int, ctypes.c_int]
        _lib.src_simple.restype = ctypes.c_int
        _lib.src_strerror.argtypes = [ctypes.c_int]
        _lib.src_strerror.restype = ctypes.c_char_p
    data_in = (ctypes.c_float * (frames * channels))(*interleaved)
    data_out = (ctypes.c_float * (capacity * channels))()
    d = SRC_DATA(data_in, data_out, frames, capacity, 0, 0, 0, ratio)
    err = _lib.src_simple(ctypes.byref(d), converter, channels)
    if err != 0:
        raise RuntimeError(_lib.src_strerror(err).decode())
    return list(data_out[: d.output_frames_gen * channels])


if __name__ == "__main__":
    print(build())
