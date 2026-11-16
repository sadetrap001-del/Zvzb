package com.korstudio.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StockHomeTest {
    private val own = "com.korstudio.home"
    private val stock = HomeComponent("com.google.android.tvlauncher", "com.google.android.tvlauncher.MainActivity")
    private val all = listOf(
        stock,
        HomeComponent(own, "$own.HomeActivity"),
        HomeComponent("com.android.tv.settings", "com.android.tv.settings.system.FallbackHome"),
        HomeComponent("com.google.android.tungsten.setupwraith", "com.google.android.tungsten.setupwraith.MainActivity"),
        HomeComponent("com.android.provision", "com.android.provision.DefaultActivity"),
    )

    @Test
    fun onlyTheRealStockLauncherIsATarget() {
        assertEquals(setOf(stock), StockHome.redirectTargets(all, own))
    }

    @Test
    fun redirectsOnlyForTheStockHomeScreen() {
        val targets = StockHome.redirectTargets(all, own)
        assertTrue(StockHome.shouldRedirect(targets, stock.packageName, stock.className))
        // Aynı paketin başka bir ekranı (ör. arama) yönlendirilmez.
        assertFalse(StockHome.shouldRedirect(targets, stock.packageName, "com.google.android.tvlauncher.SearchActivity"))
        assertFalse(StockHome.shouldRedirect(targets, "com.android.tv.settings", "com.android.tv.settings.MainSettings"))
        assertFalse(StockHome.shouldRedirect(targets, own, "$own.HomeActivity"))
        assertFalse(StockHome.shouldRedirect(targets, null, null))
    }

    @Test
    fun missingClassNameFallsBackToThePackage() {
        val targets = StockHome.redirectTargets(all, own)
        assertTrue(StockHome.shouldRedirect(targets, stock.packageName, null))
        assertFalse(StockHome.shouldRedirect(targets, "com.netflix.ninja", null))
    }
}
