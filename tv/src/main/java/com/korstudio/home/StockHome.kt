package com.korstudio.home

/** Bir ana ekran (HOME) etkinliğinin kimliği. */
data class HomeComponent(val packageName: String, val className: String)

object StockHome {
    /**
     * Sistemdeki HOME etkinliklerinden gerçek "eski ana ekran" olanları seçer.
     * Kendimiz, Ayarlar'ın açılışta kullandığı yedek ekran (FallbackHome) ve kurulum
     * sihirbazları dışarıda kalır; aksi hâlde Ayarlar'ı açmak da Kor Home'a geri atardı.
     */
    fun redirectTargets(all: List<HomeComponent>, ownPackage: String): Set<HomeComponent> = all.filter {
        val name = it.className.lowercase()
        val pkg = it.packageName.lowercase()
        it.packageName != ownPackage &&
            "fallback" !in name &&
            "setup" !in name && "setup" !in pkg &&
            "provision" !in name && "provision" !in pkg &&
            !pkg.endsWith(".settings")
    }.toSet()

    /** Öne gelen pencere eski ana ekran mı? */
    fun shouldRedirect(targets: Set<HomeComponent>, packageName: String?, className: String?): Boolean {
        if (packageName == null) return false
        if (className != null && HomeComponent(packageName, className) in targets) return true
        // Bazı cihazlar sınıf adını bildirmez; paket yalnızca ana ekrandan ibaretse yine yönlendir.
        return className == null && targets.any { it.packageName == packageName }
    }
}
