package com.iamjarl.itschoppedyo.core

import android.os.Build
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Every case in testdata/cases.json on Android's runtime (ART), with the NDK build of
 * libsamplerate, against the same SHA-256 values as the JVM and Swift tests. The test files are
 * packed into the test APK's assets under testdata/ (build.gradle.kts, copyTestdataAssets).
 */
@RunWith(AndroidJUnit4::class)
class DeviceSpecCasesTest {
    @Test
    fun everyCaseMatchesTheReferenceOnTheDevice() {
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        fun text(name: String) = assets.open("testdata/$name").use { it.readBytes().toString(Charsets.UTF_8) }
        val r = SpecCaseRunner.runAll(
            text("cases.json"),
            text("expected.json"),
            readInput = { name -> assets.open("testdata/input/$name").use { it.readBytes() } },
        )
        val abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown"
        Log.i("ChoppedCore", "ABI $abi, API ${Build.VERSION.SDK_INT}: ${r.checked} files, ${r.mismatches.size} differ")
        assertTrue(r.checked > 0)
        assertEquals("output file names on $abi", emptyList<String>(), r.nameProblems)
        assertEquals("files differing from the reference on $abi", emptyList<String>(), r.mismatches)
        assertEquals(r.expectedTotal, r.checked)
    }
}
