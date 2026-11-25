# Audio processing spec

**Version 0.3 (2026-11-25).** Version 0.2 added resampling (section 5a); 0.3 adds AIFF in and out (sections 2a and 8a). The source of truth for the audio core on both platforms. The Swift
core (`ios/ChoppedCore`), the Kotlin core (`android/core`) and the reference implementation
(`tools/reference.py`) follow this text. When the text and a core disagree, the core is wrong.

**The goal is the same bytes on both platforms.** For the same input file and the same settings,
both apps write identical files. The test files in `testdata/` check that.

## 0. Rules that make identical bytes possible

- Every sample is processed as a 64-bit IEEE 754 float (`Double`).
- The audio path uses only `+`, `-`, `*`, `/`, `floor`, `sqrt` and `abs`, which IEEE 754 defines
  exactly, plus integer arithmetic. **No `sin`, `cos`, `pow`, `exp` or `log`:** their results can
  differ by one unit in the last place between Apple's and Android's math libraries.
- Constants are written in this spec as decimal literals. Every platform parses a decimal literal to
  the nearest double, so the constant is the same everywhere.
- Loops run in the order written here. Floating-point sums depend on order.
- Integers that can exceed 2^31 (frame counts times slice indices) use 64-bit integers.

## 1. Pipeline

For one output file:

1. Read the input (section 2 for WAV, 2a for AIFF; the first four bytes decide: `RIFF` or `FORM`).
2. Channels (section 3).
3. Trim (section 4).
4. Fade in, then fade out (section 5).
4a. Resample (section 5a).
5. Normalize (section 6).
6. Quantize to integers, with or without dither (section 7).
7. Write WAV (section 8) or AIFF (section 8a).

Slices and chains (sections 9 and 10) split or join after step 5 and quantize each output file on
its own. Trim and fade lengths are in frames of the input's sample rate, since they come before the
resampling.

The settings for one run:

| Setting | Values |
|---|---|
| `mono` | `keep`, `sum`, `left`, `right` |
| `trim` | none, or `[start, end)` in frames |
| `fadeIn`, `fadeOut` | none, or `{frames, curve}` with curve `linear` or `equalPower` |
| `sampleRate` | none, or the output sample rate in Hz |
| `normalize` | none, or a target in dBFS from the table in section 6 |
| `bits` | `16` or `24` |
| `dither` | `true` or `false` |
| `format` | `wav` (the default) or `aiff`; output files end in `.wav` or `.aif` |

## 2. Reading WAV

The file is a RIFF chunk with form type `WAVE`. Chunks follow: a 4-byte ASCII id, a 4-byte
little-endian unsigned size, the data, and **one pad byte when the size is odd**. Unknown chunks are
skipped, pad byte included.

The `fmt ` chunk gives `formatTag` (u16), `channels` (u16), `sampleRate` (u32), `byteRate` (u32),
`blockAlign` (u16) and `bitsPerSample` (u16). When `formatTag` is `0xFFFE` (WAVE_FORMAT_EXTENSIBLE),
the real tag is the first two bytes (little-endian u16) of the SubFormat GUID, which starts 24 bytes
into the chunk data.

Supported:

| Tag | Bits | Sample type | To double |
|---|---|---|---|
| 1 (PCM) | 8 | unsigned | `(u - 128) / 128.0` |
| 1 | 16 | signed, little-endian | `s / 32768.0` |
| 1 | 24 | signed, little-endian, 3 bytes | `s / 8388608.0` |
| 1 | 32 | signed, little-endian | `s / 2147483648.0` |
| 3 (float) | 32 | IEEE float, little-endian | the float widened to double |
| 3 | 64 | IEEE double, little-endian | as is |

Anything else is an error ("unsupported format"). The `data` chunk holds interleaved frames.
`frames = floor(dataSize / blockAlign)`. If `dataSize` is larger than the bytes left in the file,
the bytes left are used. A file without `fmt ` before `data`, or without `data`, is an error.

## 2a. Reading AIFF

The file is an IFF chunk: `FORM`, a 4-byte **big-endian** unsigned size, and form type `AIFF` or
`AIFC`. Chunks follow with a 4-byte id, a big-endian u32 size, the data, and one pad byte when the
size is odd. Unknown chunks are skipped, pad byte included. Every number in AIFF is big-endian.

The `COMM` chunk gives `channels` (u16), `numSampleFrames` (u32), `sampleSize` (u16, bits) and
`sampleRate` as an 80-bit IEEE extended float. In `AIFC` files a 4-byte `compressionType` follows.

**The sample rate** is read without floating point. The 10 bytes are a 16-bit sign-and-exponent `e`
and a 64-bit mantissa `m`. With `shift = 16383 + 63 - (e & 0x7FFF)`, the rate is `m >> shift` when
`0 <= shift < 64`, the sign bit is clear, and the bits shifted out are all zero. Anything else is an
error ("unsupported sample rate"): the rate must be a whole number of hertz.

Supported:

| Form | compressionType | Bits | Sample type | To double |
|---|---|---|---|---|
| AIFF, or AIFC `NONE` | | 8 | signed | `s / 128.0` |
| AIFF, or AIFC `NONE` | | 16 | signed, big-endian | `s / 32768.0` |
| AIFF, or AIFC `NONE` | | 24 | signed, big-endian, 3 bytes | `s / 8388608.0` |
| AIFF, or AIFC `NONE` | | 32 | signed, big-endian | `s / 2147483648.0` |
| AIFC | `sowt` | 16 | signed, **little-endian** | `s / 32768.0` |
| AIFC | `fl32` or `FL32` | 32 | IEEE float, big-endian | widened to double |
| AIFC | `fl64` or `FL64` | 64 | IEEE double, big-endian | as is |

Anything else is an error ("unsupported format"). Note that 8-bit AIFF is signed, unlike 8-bit WAV.

The `SSND` chunk starts with `offset` (u32) and `blockSize` (u32); the sample data begins `8 +
offset` bytes into the chunk. Frames are interleaved, `channels * sampleSize / 8` bytes each.
`frames = min(numSampleFrames, floor(bytesAvailable / frameBytes))`, where `bytesAvailable` is the
chunk's size minus `8 + offset`, cut to the bytes left in the file. `COMM` may come before or after
`SSND`; a file without both is an error.

## 3. Channels

The input has `c` channels; `x[ch][i]` is channel `ch`, frame `i`.

- `keep`: unchanged.
- `left`: channel 0 only.
- `right`: channel 1 only; for a mono input, channel 0.
- `sum`: one channel, `m[i] = acc`, where `acc` starts at `0.0` and for `ch = 0 ... c-1` in order
  `acc = acc + x[ch][i] * w[ch]`. The weights are `w[ch] = k[ch] / K`, where `K` is the sum of
  `k[0] ... k[c-1]` added in order starting from `0.0`:

| c | k (channel order as stored) | Note |
|---|---|---|
| 1 | 1 | unchanged |
| 2 | 1, 1 | gives `0.5 * L + 0.5 * R`: both channels count, nothing panned is lost |
| 3 | 1, 1, 1.414 | L, R, C |
| 4 | 1, 1, 1, 1 | quad |
| 5 | 1, 1, 0.707, 0.707, 0.707 | L, R, C, Ls, Rs (ITU-R BS.775 weights) |
| 6 | 1, 1, 0.707, 0, 0.707, 0.707 | L, R, C, LFE, Ls, Rs (LFE dropped) |
| 7 or more | 1 for every channel | equal average |

These are the weights It's mono, yo! uses (its `downmixWeights`). It's mono computes in 32-bit
float, so its files are not byte-identical to ours.

## 4. Trim

`[start, end)` in frames, after the channel step. Both are clamped to `[0, frames]`. If
`end <= start` after clamping, it is an error ("empty after trim").

## 5. Fades

Fade in over `n` frames, with `n` clamped to the length `L`: for `i = 0 ... n-1`,
`t = i / n` (both converted to double, then divided) and every channel's sample at frame `i` is
multiplied by `g(t)`.

Fade out over `n` frames, clamped to `L`: for `i = 0 ... n-1`, frame `L - n + i` is multiplied by
`g(t)` with `t = (n - 1 - i) / n`.

The fade in runs first. If the two overlap, both multiply.

- `linear`: `g(t) = t`
- `equalPower`: `g(t) = sqrt(t)`. This is the constant-power curve: a fade out and a fade in of
  the same length always have squared gains that sum to one.

## 5a. Resample

Resampling uses **libsamplerate 0.2.2**, vendored in `third_party/libsamplerate` and compiled the same
way on every platform (its `README.md`; above all `-ffp-contract=off`). The library does its own
arithmetic in 32-bit floats and uses only `lrint` and `fabs` from the math library, both exact.

If `sampleRate` is none or equals the input's rate, nothing happens. Otherwise:

1. `ratio = sampleRate / inputRate`, both converted to double. It must lie in `[1/256, 256]`
   (libsamplerate's limits), or it is an error ("bad ratio").
2. The frames are interleaved, frame by frame and channel by channel, as 32-bit floats: each double
   rounded to the nearest float.
3. `capacity = floor(frames * ratio) + 256`, with `frames` converted to double and the product
   floored to an integer.
4. Call `src_simple` with converter `SRC_SINC_BEST_QUALITY` (0), the channel count, `input_frames =
   frames`, `output_frames = capacity` and `src_ratio = ratio`. Any error code is an error
   ("resample failed").
5. The result is the first `output_frames_gen` frames, each float widened back to double and split
   into channels. Its sample rate is `sampleRate`.

The length is whatever libsamplerate generates, which can be one frame short of `frames * ratio`
(48000 to 22050 Hz turns 4800 frames into 2204). Every platform runs the same code, so every
platform gets the same length. Values above full scale can appear after resampling; normalize or the
clamp in section 7 handles them.

## 6. Normalize

`peak` is the largest `abs(x)` over every channel and frame. If `peak` is `0.0`, nothing happens.
Otherwise `gain = target / peak` and every sample becomes `x * gain`. Normalize also lowers a file
whose peak is above the target, such as a float file above full scale.

Targets are a fixed list; the linear value is the double nearest to `10^(dB / 20)`, written out so
no platform computes a power:

| dBFS | Linear target |
|---|---|
| 0.0 | 1.0 |
| -0.1 | 0.9885530946569389 |
| -0.3 | 0.9660508789898133 |
| -0.5 | 0.9440608762859234 |
| -1.0 | 0.8912509381337456 |
| -3.0 | 0.7079457843841379 |
| -6.0 | 0.5011872336272722 |

## 7. Quantize and dither

For `bits` B, `scale = 2^(B-1)` (32768.0 or 8388608.0), `max = scale - 1`, `min = -scale`.

Samples are visited in file order: frame by frame, and within a frame channel by channel. For each:

1. `v = x * scale`
2. With dither: `v = v + (r1 + r2 - 1.0)`, where `r1` and `r2` are the next two numbers from the
   generator below, `r1` first. This is TPDF dither of plus or minus one step.
3. `q = floor(v + 0.5)`
4. Clamp `q` to `[min, max]`.

The generator is xorshift32 on an unsigned 32-bit state. It starts at `0x2545F491` for every output
file. `next()` does, with all shifts logical and every result kept to 32 bits:

```
s = s XOR (s << 13)
s = s XOR (s >> 17)
s = s XOR (s << 5)
return (s >> 8) / 16777216.0
```

`(s >> 8)` is an integer below 2^24, so the result is an exact double in `[0, 1)`.

## 8. Writing WAV

PCM only, 44-byte header, little-endian:

| Offset | Bytes | Value |
|---|---|---|
| 0 | 4 | `RIFF` |
| 4 | 4 | `4 + 24 + 8 + dataSize + pad` |
| 8 | 4 | `WAVE` |
| 12 | 4 | `fmt ` |
| 16 | 4 | 16 |
| 20 | 2 | 1 (PCM) |
| 22 | 2 | channels |
| 24 | 4 | sample rate |
| 28 | 4 | `sampleRate * channels * B / 8` |
| 32 | 2 | `channels * B / 8` |
| 34 | 2 | B |
| 36 | 4 | `data` |
| 40 | 4 | `dataSize` = `frames * channels * B / 8` |
| 44 | | interleaved samples, two's complement, 2 or 3 bytes each |

`pad` is 1 when `dataSize` is odd, and that one byte, `0x00`, follows the samples. No other chunks.

## 8a. Writing AIFF

Plain AIFF (form type `AIFF`, no compression), big-endian:

| Offset | Bytes | Value |
|---|---|---|
| 0 | 4 | `FORM` |
| 4 | 4 | `4 + 26 + 16 + dataSize + pad` |
| 8 | 4 | `AIFF` |
| 12 | 4 | `COMM` |
| 16 | 4 | 18 |
| 20 | 2 | channels |
| 22 | 4 | frames |
| 26 | 2 | B |
| 28 | 10 | sample rate, 80-bit extended (below) |
| 38 | 4 | `SSND` |
| 42 | 4 | `8 + dataSize` |
| 46 | 4 | 0 (offset) |
| 50 | 4 | 0 (blockSize) |
| 54 | | interleaved samples, big-endian two's complement, 2 or 3 bytes each |

`dataSize = frames * channels * B / 8`; `pad` is 1 when `dataSize` is odd, and that byte, `0x00`,
follows the samples. No other chunks.

The sample rate `r` (a positive integer) is written without floating point: with `p` the index of
its highest set bit (0 for 1), the exponent is `16383 + p` as a big-endian u16 (sign bit clear) and
the mantissa is `r << (63 - p)` as a big-endian u64.

## 9. Equal slices

After step 5, a buffer of `L` frames is cut into `N` slices (2 to 64). `L < N` is an error. With
64-bit integers, `b[k] = floor(k * L / N)` for `k = 0 ... N`; slice `k` is frames
`[b[k], b[k+1])`. Fades and normalize apply to the whole buffer before it is cut, not to each slice.
Each slice is quantized and written as its own file, `<name>_01.wav` to `<name>_NN.wav` (two digits,
`.aif` for AIFF),
with the generator restarted for each.

## 10. Sample chains

Each input goes through steps 1 to 5 with the same settings, so inputs with different sample rates
can be chained when `sampleRate` is set. All must then have the same channel count and sample rate,
or it is an error. `S` is the longest length in frames. With `k` inputs and
`slots` (at least `k`; `k` when not given), the chain is `slots * S` frames of `0.0`, and input `j`
is copied in from frame `j * S`. The chain is then quantized once and written as one file.

## 11. Test files

`tools/make_testdata.py` writes the inputs to `testdata/input/`, runs `tools/reference.py` on every
case in `testdata/cases.json`, writes the results to `testdata/expected/` and their SHA-256 to
`testdata/expected.json`. A core passes when every output file it writes has the expected SHA-256.
