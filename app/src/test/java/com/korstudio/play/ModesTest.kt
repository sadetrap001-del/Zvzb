package com.korstudio.play

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModesTest {
    private val fhd60 = ModeInfo(1920, 1080, 60f)
    private val uhd30 = ModeInfo(3840, 2160, 30f)
    private val uhd60 = ModeInfo(3840, 2160, 59.94f)

    @Test
    fun bestPrefersResolutionThenRefreshRate() {
        assertEquals(uhd60, Modes.best(listOf(fhd60, uhd30, uhd60)))
        assertEquals(uhd30, Modes.best(listOf(ModeInfo(1920, 1080, 120f), uhd30)))
        assertNull(Modes.best(emptyList()))
    }

    @Test
    fun detects4kInEitherOrientation() {
        assertTrue(uhd60.is4k)
        assertTrue(ModeInfo(2160, 3840, 60f).is4k)
        assertTrue(ModeInfo(4096, 2160, 60f).is4k)
        assertFalse(fhd60.is4k)
        assertFalse(ModeInfo(2560, 1440, 60f).is4k)
    }

    @Test
    fun verdictSeparatesActiveAvailableAndMissing4k() {
        assertEquals(Modes.Verdict.ACTIVE_4K, Modes.verdict(uhd60, listOf(fhd60, uhd60)))
        assertEquals(Modes.Verdict.AVAILABLE_4K, Modes.verdict(fhd60, listOf(fhd60, uhd30)))
        assertEquals(Modes.Verdict.NO_4K, Modes.verdict(fhd60, listOf(fhd60)))
    }

    @Test
    fun labelRoundsTheRefreshRate() {
        assertEquals("3840×2160 · 60 Hz", uhd60.label())
    }
}
