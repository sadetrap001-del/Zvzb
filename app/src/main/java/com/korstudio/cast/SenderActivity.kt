package com.korstudio.cast

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.util.ArrayDeque

/** Telefon tarafı: TV'yi bulur, kaliteyi seçtirir ve yayını başlatır. */
class SenderActivity : Activity() {

    private class Device(val name: String, val host: String, val port: Int)

    private lateinit var deviceGroup: RadioGroup
    private lateinit var searching: TextView
    private lateinit var manualIp: EditText
    private lateinit var quality: Spinner
    private lateinit var bitrate: Spinner
    private lateinit var fps: Spinner
    private lateinit var audio: Switch
    private lateinit var status: TextView
    private lateinit var start: Button

    private val presets = listOf(
        Preset.UHD to R.string.quality_4k,
        Preset.NATIVE to R.string.quality_native,
        Preset.QHD to R.string.quality_1440,
        Preset.FHD to R.string.quality_1080,
        Preset.HD to R.string.quality_720,
    )
    private val bitrates = listOf(8, 15, 25, 40, 60)
    private val frameRates = listOf(30, 60)

    private val devices = LinkedHashMap<String, Device>()
    private val resolveQueue = ArrayDeque<NsdServiceInfo>()
    private var resolving = false
    private var discovering = false
    private val nsd by lazy { getSystemService(Context.NSD_SERVICE) as NsdManager }
    private val prefs by lazy { getSharedPreferences("sender", Context.MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_sender)
        deviceGroup = findViewById(R.id.deviceGroup)
        searching = findViewById(R.id.searching)
        manualIp = findViewById(R.id.manualIp)
        quality = findViewById(R.id.quality)
        bitrate = findViewById(R.id.bitrate)
        fps = findViewById(R.id.fps)
        audio = findViewById(R.id.audio)
        status = findViewById(R.id.status)
        start = findViewById(R.id.start)

        quality.adapter = adapter(presets.map { getString(it.second) })
        bitrate.adapter = adapter(bitrates.map { "$it Mbps" })
        fps.adapter = adapter(frameRates.map { "$it fps" })

        quality.setSelection(prefs.getInt("quality", 0).coerceIn(0, presets.lastIndex))
        bitrate.setSelection(prefs.getInt("bitrate", 2).coerceIn(0, bitrates.lastIndex))
        fps.setSelection(prefs.getInt("fps", 1).coerceIn(0, frameRates.lastIndex))
        manualIp.setText(prefs.getString("ip", ""))

        if (Build.VERSION.SDK_INT >= 29) {
            audio.isChecked = prefs.getBoolean("audio", true)
        } else {
            audio.isChecked = false
            audio.isEnabled = false
            audio.text = getString(R.string.audio_unsupported)
        }

        start.setOnClickListener { if (CastService.isActive) stopCast() else beginCast() }
        findViewById<Button>(R.id.toReceiver).setOnClickListener {
            startActivity(Intent(this, ReceiverActivity::class.java))
        }
    }

    private fun adapter(items: List<String>) =
        ArrayAdapter(this, android.R.layout.simple_spinner_item, items).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }

    override fun onStart() {
        super.onStart()
        CastService.listener = { render(it) }
        render(CastService.state)
        startDiscovery()
    }

    override fun onStop() {
        CastService.listener = null
        stopDiscovery()
        prefs.edit()
            .putInt("quality", quality.selectedItemPosition)
            .putInt("bitrate", bitrate.selectedItemPosition)
            .putInt("fps", fps.selectedItemPosition)
            .putBoolean("audio", audio.isChecked)
            .putString("ip", manualIp.text.toString().trim())
            .apply()
        super.onStop()
    }

    private fun render(state: CastService.State) {
        status.text = when (state.phase) {
            CastService.Phase.IDLE -> getString(R.string.status_idle)
            CastService.Phase.CONNECTING -> getString(R.string.status_connecting)
            CastService.Phase.STREAMING -> getString(R.string.status_streaming, state.detail)
            CastService.Phase.ERROR -> getString(R.string.status_error, state.detail)
            CastService.Phase.ENDED -> getString(R.string.status_ended)
        }
        start.text = getString(if (CastService.isActive) R.string.stop else R.string.start)
    }

    /* ---------- TV keşfi ---------- */

    private val discoveryListener = object : NsdManager.DiscoveryListener {
        override fun onDiscoveryStarted(serviceType: String) {}
        override fun onDiscoveryStopped(serviceType: String) {}
        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) { discovering = false }
        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}

        override fun onServiceFound(info: NsdServiceInfo) {
            runOnUiThread {
                resolveQueue.add(info)
                resolveNext()
            }
        }

        override fun onServiceLost(info: NsdServiceInfo) {
            runOnUiThread {
                if (devices.remove(info.serviceName) != null) renderDevices()
            }
        }
    }

    private fun startDiscovery() {
        if (discovering) return
        devices.clear()
        renderDevices()
        try {
            nsd.discoverServices(Protocol.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
            discovering = true
        } catch (e: Exception) {
            discovering = false
        }
    }

    private fun stopDiscovery() {
        if (!discovering) return
        discovering = false
        try { nsd.stopServiceDiscovery(discoveryListener) } catch (_: Exception) {}
        resolveQueue.clear()
        resolving = false
    }

    // Eski Android sürümleri aynı anda tek çözümlemeye izin verdiği için sırayla çözülür.
    private fun resolveNext() {
        if (resolving || !discovering) return
        val next = resolveQueue.poll() ?: return
        resolving = true
        try {
            @Suppress("DEPRECATION")
            nsd.resolveService(next, object : NsdManager.ResolveListener {
                override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) = finished()

                override fun onServiceResolved(info: NsdServiceInfo) {
                    @Suppress("DEPRECATION")
                    val address = info.host?.hostAddress
                    runOnUiThread {
                        if (address != null) {
                            devices[info.serviceName] = Device(info.serviceName, address, info.port)
                            renderDevices()
                        }
                    }
                    finished()
                }

                private fun finished() = runOnUiThread {
                    resolving = false
                    resolveNext()
                }
            })
        } catch (e: Exception) {
            resolving = false
        }
    }

    private fun renderDevices() {
        val selected = selectedDevice()?.name
        deviceGroup.removeAllViews()
        devices.values.forEachIndexed { index, device ->
            val button = RadioButton(this).apply {
                id = View.generateViewId()
                text = "${device.name}  ·  ${device.host}"
                tag = device
                textSize = 16f
                minHeight = (48 * resources.displayMetrics.density).toInt()
            }
            deviceGroup.addView(button)
            if (device.name == selected || (selected == null && index == 0)) deviceGroup.check(button.id)
        }
        searching.visibility = if (devices.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun selectedDevice(): Device? =
        deviceGroup.findViewById<RadioButton>(deviceGroup.checkedRadioButtonId)?.tag as? Device

    /* ---------- Yayın ---------- */

    /** Elle yazılan adres doluysa o, değilse listeden seçilen TV kullanılır. */
    private fun target(): Device? {
        val typed = manualIp.text.toString().trim()
        if (typed.isNotEmpty()) {
            val host = typed.substringBefore(':')
            val port = typed.substringAfter(':', "").toIntOrNull() ?: Protocol.PORT
            return Device(host, host, port)
        }
        return selectedDevice()
    }

    private fun beginCast() {
        if (target() == null) {
            Toast.makeText(this, R.string.err_no_target, Toast.LENGTH_SHORT).show()
            return
        }
        val needed = ArrayList<String>()
        if (audio.isChecked && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            needed += Manifest.permission.RECORD_AUDIO
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            needed += Manifest.permission.POST_NOTIFICATIONS
        }
        if (needed.isNotEmpty()) requestPermissions(needed.toTypedArray(), REQUEST_PERMISSIONS) else askForScreen()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        // İzinler reddedilse de yayın yapılabilir; yalnızca ses ya da bildirim eksik kalır.
        if (requestCode == REQUEST_PERMISSIONS) askForScreen()
    }

    private fun askForScreen() {
        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        @Suppress("DEPRECATION")
        startActivityForResult(manager.createScreenCaptureIntent(), REQUEST_SCREEN)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_SCREEN) return
        val device = target()
        if (resultCode != RESULT_OK || data == null || device == null) {
            Toast.makeText(this, R.string.err_permission, Toast.LENGTH_SHORT).show()
            return
        }
        val audioGranted = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val intent = Intent(this, CastService::class.java)
            .setAction(CastService.ACTION_START)
            .putExtra(CastService.EXTRA_RESULT_CODE, resultCode)
            .putExtra(CastService.EXTRA_RESULT_DATA, data)
            .putExtra(CastService.EXTRA_HOST, device.host)
            .putExtra(CastService.EXTRA_PORT, device.port)
            .putExtra(CastService.EXTRA_PRESET, presets[quality.selectedItemPosition].first.ordinal)
            .putExtra(CastService.EXTRA_BITRATE_MBPS, bitrates[bitrate.selectedItemPosition])
            .putExtra(CastService.EXTRA_FPS, frameRates[fps.selectedItemPosition])
            .putExtra(CastService.EXTRA_AUDIO, audio.isChecked && audioGranted)
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent) else startService(intent)
    }

    private fun stopCast() {
        startService(Intent(this, CastService::class.java).setAction(CastService.ACTION_STOP))
    }

    companion object {
        private const val REQUEST_SCREEN = 1
        private const val REQUEST_PERMISSIONS = 2
    }
}
