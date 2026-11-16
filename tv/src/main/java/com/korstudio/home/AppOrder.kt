package com.korstudio.home

/** Bir uygulamanın sıralama için gereken kimliği. */
data class Listing(val packageName: String, val label: String)

object AppOrder {
    /**
     * Gizlenenleri (istenmedikçe) çıkarır; favorileri başa alır, her grubu ada göre sıralar.
     */
    fun arrange(
        all: List<Listing>,
        favorites: Set<String>,
        hidden: Set<String>,
        showHidden: Boolean,
    ): List<Listing> = all
        .filter { showHidden || it.packageName !in hidden }
        .sortedWith(compareBy({ it.packageName !in favorites }, { it.label.lowercase() }, { it.packageName }))

    /** Kaldırılmış uygulamaların kayıtlarını temizlemek için: yalnızca hâlâ kurulu olanlar kalır. */
    fun prune(saved: Set<String>, installed: Collection<String>): Set<String> = saved.intersect(installed.toSet())
}
