package com.korstudio.cast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SizingTest {
    // 20:9 oranlı tipik bir oyun telefonu ekranı.
    private val long = 2480
    private val short = 1116

    @Test
    fun nativeKeepsThePhoneResolution() {
        assertEquals(VideoSize(2480, 1116), Sizing.fit(long, short, Preset.NATIVE))
    }

    @Test
    fun uhdUpscalesToFitInside4kKeepingAspect() {
        val size = Sizing.fit(long, short, Preset.UHD)
        assertEquals(3840, size.width)
        assertEquals(1728, size.height)
    }

    @Test
    fun smallerPresetsScaleDownButNeverUp() {
        assertEquals(VideoSize(1920, 864), Sizing.fit(long, short, Preset.FHD))
        assertEquals(VideoSize(1280, 576), Sizing.fit(long, short, Preset.HD))
        // 1080p telefon 1440p seçilse de büyütülmez.
        assertEquals(VideoSize(1920, 1080), Sizing.fit(1920, 1080, Preset.QHD))
    }

    @Test
    fun dimensionsAreAlwaysEven() {
        for (preset in Preset.values()) {
            val size = Sizing.fit(2399, 1079, preset)
            assertEquals(0, size.width % 2)
            assertEquals(0, size.height % 2)
        }
    }

    @Test
    fun candidatesStartWithTheChoiceAndOnlyGetSmaller() {
        val sizes = Sizing.candidates(long, short, Preset.UHD)
        assertEquals(VideoSize(3840, 1728), sizes.first())
        assertEquals(sizes.sortedByDescending { it.pixels }, sizes)
        assertEquals(sizes.distinct(), sizes)
        assertEquals(VideoSize(1280, 576), sizes.last())

        assertEquals(listOf(VideoSize(1280, 576)), Sizing.candidates(long, short, Preset.HD))
    }

    @Test
    fun receiverLimitIsRespected() {
        assertTrue(Sizing.fitsReceiver(VideoSize(3840, 1728), 2160))
        assertFalse(Sizing.fitsReceiver(VideoSize(3840, 1728), 1080))
        assertTrue(Sizing.fitsReceiver(VideoSize(1920, 864), 1080))
        // 1080p basamağına sığmayacak kadar geniş.
        assertFalse(Sizing.fitsReceiver(VideoSize(2480, 1116), 1080))
        assertFalse(Sizing.fitsReceiver(VideoSize(1280, 720), 0))
    }
}
