package com.korstudio.cast

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class VideoSize(val width: Int, val height: Int) {
    val pixels get() = width.toLong() * height
    override fun equals(other: Any?) = other is VideoSize && other.width == width && other.height == height
    override fun hashCode() = width * 31 + height
    override fun toString() = "${width}×$height"
}

/** Kullanıcının seçebildiği kalite basamakları. NATIVE telefonun kendi çözünürlüğüdür. */
enum class Preset(val maxWidth: Int, val maxHeight: Int, val upscale: Boolean) {
    UHD(3840, 2160, true),
    NATIVE(0, 0, false),
    QHD(2560, 1440, false),
    FHD(1920, 1080, false),
    HD(1280, 720, false),
}

object Sizing {
    const val MIME_HEVC = "video/hevc"
    const val MIME_AVC = "video/avc"

    private fun even(v: Double) = max(2, (v / 2).roundToInt() * 2)

    /**
     * Yayın her zaman yatay kodlanır: [srcLong]×[srcShort] telefon ekranının uzun ve kısa kenarıdır.
     * Görüntü oranı korunur; yalnızca 4K basamağı telefonun çözünürlüğünün üstüne büyütür.
     */
    fun fit(srcLong: Int, srcShort: Int, preset: Preset): VideoSize {
        if (preset == Preset.NATIVE) return VideoSize(even(srcLong.toDouble()), even(srcShort.toDouble()))
        var scale = min(preset.maxWidth.toDouble() / srcLong, preset.maxHeight.toDouble() / srcShort)
        if (!preset.upscale) scale = min(scale, 1.0)
        return VideoSize(even(srcLong * scale), even(srcShort * scale))
    }

    /** Seçilen basamak ve o tutmazsa denenecek daha küçük boyutlar, büyükten küçüğe. */
    fun candidates(srcLong: Int, srcShort: Int, chosen: Preset): List<VideoSize> {
        val first = fit(srcLong, srcShort, chosen)
        val smaller = Preset.values()
            .map { fit(srcLong, srcShort, it) }
            .filter { it.pixels < first.pixels }
            .distinct()
            .sortedByDescending { it.pixels }
        return listOf(first) + smaller
    }

    /** Alıcı, yüksekliği [maxHeight] olan 16:9 kareyi çözebiliyorsa bu boyut ona sığar mı? */
    fun fitsReceiver(size: VideoSize, maxHeight: Int): Boolean {
        if (maxHeight <= 0) return false
        val maxWidth = maxHeight * 16 / 9
        return size.height <= maxHeight && size.width <= maxWidth
    }
}
