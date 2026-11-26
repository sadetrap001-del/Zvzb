package com.korstudio.telefon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppListTest {
    private val ayarlar = Entry("ayar", "Ayarlar")
    private val chrome = Entry("chrome", "Chrome")
    private val camasir = Entry("cam", "Çamaşır Takibi")
    private val zil = Entry("zil", "zil sesi")
    private val instagram = Entry("ig", "İnstagram")
    private val isik = Entry("fener", "Işık")
    private val all = listOf(zil, camasir, chrome, ayarlar)

    @Test
    fun sortsWithTurkishAlphabetIgnoringCase() {
        assertEquals(listOf(ayarlar, chrome, camasir, zil), AppList.arrange(all, emptySet(), false, ""))
    }

    @Test
    fun hiddenAppsAreLeftOutUnlessAsked() {
        assertEquals(listOf(ayarlar, camasir, zil), AppList.arrange(all, setOf("chrome"), false, ""))
        assertEquals(listOf(ayarlar, chrome, camasir, zil), AppList.arrange(all, setOf("chrome"), true, ""))
    }

    @Test
    fun searchFindsHiddenAppsToo() {
        assertEquals(listOf(chrome), AppList.arrange(all, setOf("chrome"), false, "chr"))
    }

    @Test
    fun searchWorksWithoutTurkishLetters() {
        val apps = listOf(instagram, isik, camasir)
        assertEquals(listOf(instagram), AppList.arrange(apps, emptySet(), false, "inst"))
        assertEquals(listOf(isik), AppList.arrange(apps, emptySet(), false, "isik"))
        assertEquals(listOf(camasir), AppList.arrange(apps, emptySet(), false, "camasir"))
        assertEquals(listOf(camasir), AppList.arrange(apps, emptySet(), false, "ÇAMAŞIR"))
    }

    @Test
    fun startOfNameRanksBeforeWordAndMiddleMatches() {
        val takvim = Entry("takvim", "Takvim")
        val apps = listOf(camasir, Entry("x", "Kitaplık"), takvim)
        // "Takvim" adı "tak" ile başlar; "Çamaşır Takibi"nde bir kelime başlar; "Kitaplık"ta yok.
        assertEquals(listOf(takvim, camasir), AppList.arrange(apps, emptySet(), false, "tak"))
        assertEquals(listOf(Entry("x", "Kitaplık")), AppList.arrange(apps, emptySet(), false, "tapl"))
    }

    @Test
    fun favoritesKeepOrderAndRespectTheLimit() {
        assertEquals(listOf("a", "b"), AppList.addFavorite(listOf("a"), "b", 5))
        assertEquals(listOf("a"), AppList.addFavorite(listOf("a"), "a", 5))
        assertNull(AppList.addFavorite(listOf("a", "b"), "c", 2))
    }

    @Test
    fun pruneDropsUninstalledAndKeepsOrder() {
        assertEquals(listOf("c", "a"), AppList.prune(listOf("c", "gone", "a", "c"), listOf("a", "b", "c")))
    }
}
