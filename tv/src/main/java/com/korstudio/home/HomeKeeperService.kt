package com.korstudio.home

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.util.Log
import android.view.accessibility.AccessibilityEvent

/**
 * Eski ana ekran öne geldiğinde (Home tuşu, TV'nin açılması) Kor Home'u başlatır.
 * Pencere içeriği okunmaz; yalnızca öne gelen ekranın paket ve sınıf adına bakılır.
 */
class HomeKeeperService : AccessibilityService() {

    private var targets: Set<HomeComponent> = emptySet()

    override fun onServiceConnected() {
        val query = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val homes = packageManager.queryIntentActivities(query, 0)
            .map { HomeComponent(it.activityInfo.packageName, it.activityInfo.name) }
        targets = StockHome.redirectTargets(homes, packageName)
        // Servis TV açılırken de bağlanır; bu an Kor Home'u göstermek için doğru zamandır.
        showHome()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        if (StockHome.shouldRedirect(targets, event.packageName?.toString(), event.className?.toString())) showHome()
    }

    override fun onInterrupt() {}

    private fun showHome() {
        try {
            startActivity(Intent(this, HomeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            Log.w("KorHome", "Ana ekran açılamadı", e)
        }
    }
}
