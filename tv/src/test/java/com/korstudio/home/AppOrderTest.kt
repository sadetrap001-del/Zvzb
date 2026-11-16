package com.korstudio.home

import org.junit.Assert.assertEquals
import org.junit.Test

class AppOrderTest {
    private val youtube = Listing("yt", "YouTube")
    private val netflix = Listing("nf", "Netflix")
    private val browser = Listing("br", "browser")
    private val all = listOf(youtube, netflix, browser)

    @Test
    fun sortsByNameIgnoringCase() {
        assertEquals(listOf(browser, netflix, youtube), AppOrder.arrange(all, emptySet(), emptySet(), false))
    }

    @Test
    fun favoritesComeFirst() {
        assertEquals(listOf(youtube, browser, netflix), AppOrder.arrange(all, setOf("yt"), emptySet(), false))
    }

    @Test
    fun hiddenAppsAppearOnlyWhenAsked() {
        assertEquals(listOf(browser, youtube), AppOrder.arrange(all, emptySet(), setOf("nf"), false))
        assertEquals(listOf(browser, netflix, youtube), AppOrder.arrange(all, emptySet(), setOf("nf"), true))
    }

    @Test
    fun sameNameIsOrderedStably() {
        val a = Listing("a", "Oyun")
        val b = Listing("b", "Oyun")
        assertEquals(listOf(a, b), AppOrder.arrange(listOf(b, a), emptySet(), emptySet(), false))
    }

    @Test
    fun pruneDropsUninstalledPackages() {
        assertEquals(setOf("yt"), AppOrder.prune(setOf("yt", "gone"), listOf("yt", "nf")))
    }
}
