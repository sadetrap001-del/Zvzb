package com.korstudio.play

import android.app.Presentation
import android.content.Context
import android.os.Bundle
import android.view.Display
import android.widget.TextView

/** TV'de, o ekranın en yüksek modunu isteyerek açılan çözünürlük test ekranı. */
class TestPresentation(context: Context, display: Display) : Presentation(context, display) {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.presentation_test)

        // Bu pencere açıkken sistemden ekranın en yüksek çözünürlüklü modunu iste.
        val best = display.supportedModes.maxWithOrNull(
            compareBy<Display.Mode> { it.physicalWidth.toLong() * it.physicalHeight }.thenBy { it.refreshRate }
        )
        if (best != null) {
            window?.attributes = window?.attributes?.apply { preferredDisplayModeId = best.modeId }
        }

        val root = findViewById<TextView>(R.id.resolution)
        root.addOnLayoutChangeListener { view, _, _, _, _, _, _, _, _ ->
            val decor = view.rootView
            val text = context.getString(R.string.test_resolution, "${decor.width}×${decor.height}")
            if (root.text.toString() != text) root.text = text
        }
    }
}
