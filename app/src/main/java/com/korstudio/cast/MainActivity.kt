package com.korstudio.cast

import android.app.Activity
import android.app.UiModeManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Bundle

/** Cihaz TV ise alıcıyı, telefon ya da tabletse göndericiyi açar. */
class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val target = if (isTv(this)) ReceiverActivity::class.java else SenderActivity::class.java
        startActivity(Intent(this, target))
        finish()
    }

    companion object {
        fun isTv(context: Context): Boolean {
            val uiMode = context.getSystemService(Context.UI_MODE_SERVICE) as UiModeManager
            return uiMode.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION ||
                context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
        }
    }
}
