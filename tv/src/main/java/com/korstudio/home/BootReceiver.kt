package com.korstudio.home

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * TV yeniden başladığında Kor Home'u açmayı dener. Yeni Android sürümleri arka plandan
 * ekran açmayı engelleyebilir; o durumda işi HomeKeeperService yapar.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        try {
            context.startActivity(Intent(context, HomeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            Log.i("KorHome", "Açılışta başlatılamadı: ${e.message}")
        }
    }
}
