// specs/audio-processing.md, version 0.1. Read the spec before changing anything here: the same
// bytes on Android and iOS depend on doing exactly what it says, in the order it says.
package com.iamjarl.itschoppedyo.core

/** Audio as 64-bit floats, one array per channel (`channels[ch][frame]`). */
class Audio(val sampleRate: Int, val channels: List<DoubleArray>) {
    val frameCount: Int get() = channels.firstOrNull()?.size ?: 0
}

class ChoppedException(val reason: Reason) : Exception(reason.name) {
    enum class Reason {
        NOT_WAVE, DATA_BEFORE_FMT, NO_DATA, BAD_FORMAT, UNSUPPORTED_FORMAT, EMPTY_AFTER_TRIM,
        UNKNOWN_NORMALIZE_TARGET, BAD_BITS, BAD_SLICE_COUNT, FEWER_FRAMES_THAN_SLICES,
        CHAIN_MISMATCH, FEWER_SLOTS_THAN_INPUTS,
    }
}

internal fun fail(reason: ChoppedException.Reason): Nothing = throw ChoppedException(reason)

enum class MonoMode { KEEP, SUM, LEFT, RIGHT }

enum class FadeCurve { LINEAR, EQUAL_POWER }

data class Fade(val frames: Int, val curve: FadeCurve)

/** `[start, end)` in frames. */
data class TrimRange(val start: Int, val end: Int)

/** The settings for one run (spec section 1). */
data class Settings(
    val mono: MonoMode = MonoMode.KEEP,
    val trim: TrimRange? = null,
    val fadeIn: Fade? = null,
    val fadeOut: Fade? = null,
    /** A dBFS value from [Processing.normalizeTargets], or null for no normalize. */
    val normalizeDb: Double? = null,
    val bits: Int = 16,
    val dither: Boolean = false,
)
