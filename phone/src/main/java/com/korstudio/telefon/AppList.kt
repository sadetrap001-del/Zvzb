package com.korstudio.telefon

import java.text.Collator
import java.text.Normalizer
import java.util.Locale

/** Bir uygulamanın sıralama ve arama için gereken kimliği. */
data class Entry(val packageName: String, val label: String)

object AppList {
    private val turkish = Locale("tr", "TR")
    private val marks = Regex("\\p{Mn}+")
    private val collator: Collator = Collator.getInstance(turkish).apply { strength = Collator.PRIMARY }

    /** Türkçe alfabeye göre (Ç, C'den sonra gelir), büyük-küçük harf farkı gözetmeden. */
    val byName: Comparator<Entry> = Comparator { a, b ->
        val byLabel = collator.compare(a.label, b.label)
        if (byLabel != 0) byLabel else a.packageName.compareTo(b.packageName)
    }

    /** Aramada Türkçe karakter yazmak zorunlu olmasın: "Işık", "isik" ile de bulunur. */
    fun normalize(text: String): String {
        val lower = text.lowercase(turkish)
        val plain = buildString(lower.length) {
            for (c in lower) {
                append(
                    when (c) {
                        'ı' -> 'i'
                        'ş' -> 's'
                        'ğ' -> 'g'
                        'ü' -> 'u'
                        'ö' -> 'o'
                        'ç' -> 'c'
                        else -> c
                    }
                )
            }
        }
        return Normalizer.normalize(plain, Normalizer.Form.NFD).replace(marks, "")
    }

    /**
     * Arama boşsa gizlenmeyenleri ada göre sıralar. Arama varsa gizlenenler de dahil eşleşenleri döndürür:
     * önce adı aramayla başlayanlar, sonra bir kelimesi aramayla başlayanlar, sonra adında geçenler.
     */
    fun arrange(all: List<Entry>, hidden: Set<String>, showHidden: Boolean, query: String): List<Entry> {
        val q = normalize(query.trim())
        if (q.isEmpty()) {
            return all.filter { showHidden || it.packageName !in hidden }.sortedWith(byName)
        }
        return all
            .mapNotNull { entry ->
                val name = normalize(entry.label)
                val rank = when {
                    name.startsWith(q) -> 0
                    name.split(' ', '-', '.').any { it.startsWith(q) } -> 1
                    name.contains(q) -> 2
                    else -> return@mapNotNull null
                }
                rank to entry
            }
            .sortedWith(compareBy<Pair<Int, Entry>> { it.first }.thenComparator { a, b -> byName.compare(a.second, b.second) })
            .map { it.second }
    }

    /** Favori ekler; çubuk doluysa null döner. */
    fun addFavorite(favorites: List<String>, packageName: String, max: Int): List<String>? = when {
        packageName in favorites -> favorites
        favorites.size >= max -> null
        else -> favorites + packageName
    }

    /** Kaldırılmış uygulamaların kayıtlarını temizler; sıra korunur. */
    fun prune(saved: List<String>, installed: Collection<String>): List<String> {
        val present = installed.toSet()
        return saved.filter { it in present }.distinct()
    }
}
