package com.iamjarl.itschoppedyo.core

import com.iamjarl.itschoppedyo.core.ChoppedException.Reason
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * Every case in testdata/cases.json on the JVM, with a host build of libsamplerate
 * (SpecCaseRunner does the work). Outputs land in testdata/out/kotlin/.
 * DeviceSpecCasesTest runs the same cases on Android's runtime.
 */
class SpecCasesTest {
    private val testdata = File(System.getProperty("testdata.dir") ?: "../../testdata")

    @Test
    fun everyCaseMatchesTheReference() {
        val outDir = File(testdata, "out/kotlin").apply { mkdirs() }
        val r = SpecCaseRunner.runAll(
            File(testdata, "cases.json").readText(),
            File(testdata, "expected.json").readText(),
            readInput = { File(File(testdata, "input"), it).readBytes() },
            onOutput = { name, bytes -> File(outDir, name).writeBytes(bytes) },
        )
        assertTrue(r.checked > 0)
        assertEquals("output file names", emptyList<String>(), r.nameProblems)
        assertEquals("files differing from the reference", emptyList<String>(), r.mismatches)
        assertEquals(r.expectedTotal, r.checked)
    }

    @Test
    fun rightPannedSoundSurvivesTheMonoSum() {
        val a = Audio(48000, listOf(doubleArrayOf(0.0, 0.0), doubleArrayOf(0.5, -0.5)))
        assertArrayEquals(doubleArrayOf(0.25, -0.25), Processing.applyChannels(a, MonoMode.SUM).channels[0], 0.0)
    }

    @Test
    fun errorsFollowTheSpec() {
        val a = Audio(48000, listOf(doubleArrayOf(0.1, 0.2, 0.3)))
        expect(Reason.EMPTY_AFTER_TRIM) { Processing.trim(a, TrimRange(2, 2)) }
        expect(Reason.UNKNOWN_NORMALIZE_TARGET) { Processing.normalize(a, -0.2) }
        expect(Reason.BAD_BITS) { Processing.quantize(a, 20, false) }
        expect(Reason.FEWER_FRAMES_THAN_SLICES) { Processing.sliceBounds(3, 4) }
        expect(Reason.NOT_WAVE) { Wav.read("RIFX....WAVE".toByteArray()) }
        expect(Reason.BAD_RATIO) { Processing.resample(a, 48000 * 300) }
    }

    @Test
    fun sampleRatesSurviveTheExtendedFloat() {
        for (r in intArrayOf(1, 8000, 11025, 22050, 44100, 46875, 48000, 96000, 192000, 384000)) {
            assertEquals(r, Aiff.extendedToRate(Aiff.rateToExtended(r)))
        }
        // One lower exponent halves the rate: 88201 / 2 = 44100.5, not a whole number of hertz.
        val x = Aiff.rateToExtended(88201)
        x[1] = (x[1] - 1).toByte()
        expect(Reason.UNSUPPORTED_SAMPLE_RATE) { Aiff.extendedToRate(x) }
    }

    private fun expect(reason: Reason, block: () -> Unit) {
        try {
            block()
            fail("expected $reason")
        } catch (e: ChoppedException) {
            assertEquals(reason, e.reason)
        }
    }
}
