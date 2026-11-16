package com.korstudio.play

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

/**
 * Bir piksel açık, bir piksel koyu çizgiler çizer. Görüntü yolda küçültülüp büyütülüyorsa
 * çizgiler griye döner; gerçek çözünürlükte tek tek seçilir.
 */
class LinePatternView(context: Context, attrs: AttributeSet?) : View(context, attrs) {
    private val paint = Paint().apply {
        color = Color.WHITE
        strokeWidth = 1f
        isAntiAlias = false
    }

    override fun onDraw(canvas: Canvas) {
        val half = width / 2f
        // Sol yarı: dikey çizgiler, sağ yarı: yatay çizgiler.
        var x = 0
        while (x < half) {
            canvas.drawRect(x.toFloat(), 0f, x + 1f, height.toFloat(), paint)
            x += 2
        }
        var y = 0
        while (y < height) {
            canvas.drawRect(half, y.toFloat(), width.toFloat(), y + 1f, paint)
            y += 2
        }
    }
}
