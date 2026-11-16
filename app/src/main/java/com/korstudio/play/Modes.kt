package com.korstudio.play

import kotlin.math.roundToInt

/** Bir ekranın sunduğu görüntü modu (fiziksel piksel ve yenileme hızı). */
data class ModeInfo(val width: Int, val height: Int, val refreshRate: Float) {
    private val long get() = maxOf(width, height)
    private val short get() = minOf(width, height)
    val pixels get() = width.toLong() * height

    /** UHD (3840×2160) ya da daha büyük mü? Ekran dik bildirilse de çalışır. */
    val is4k get() = long >= 3840 && short >= 2160

    fun label() = "$width×$height · ${refreshRate.roundToInt()} Hz"
}

object Modes {
    /** En yüksek çözünürlüklü mod; eşitlikte en yüksek yenileme hızı. */
    fun best(modes: List<ModeInfo>): ModeInfo? =
        modes.maxWithOrNull(compareBy<ModeInfo> { it.pixels }.thenBy { it.refreshRate })

    enum class Verdict { ACTIVE_4K, AVAILABLE_4K, NO_4K }

    fun verdict(current: ModeInfo, supported: List<ModeInfo>): Verdict = when {
        current.is4k -> Verdict.ACTIVE_4K
        supported.any { it.is4k } -> Verdict.AVAILABLE_4K
        else -> Verdict.NO_4K
    }
}
