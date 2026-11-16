package com.korstudio.cast

import android.app.Activity
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaFormat
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket

/** TV tarafı: telefondan gelen yayını bekler, çözer ve tam ekran gösterir. */
class ReceiverActivity : Activity(), SurfaceHolder.Callback {

    private lateinit var root: FrameLayout
    private lateinit var surfaceView: SurfaceView
    private lateinit var overlay: View
    private lateinit var title: TextView
    private lateinit var hint: TextView
    private lateinit var address: TextView

    @Volatile private var surface: Surface? = null
    @Volatile private var active = false
    @Volatile private var server: ServerSocket? = null
    @Volatile private var client: Socket? = null
    private var registered = false
    private var videoWidth = 0
    private var videoHeight = 0

    private val nsd by lazy { getSystemService(Context.NSD_SERVICE) as NsdManager }
    private val registration = object : NsdManager.RegistrationListener {
        override fun onServiceRegistered(info: NsdServiceInfo) {}
        override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {}
        override fun onServiceUnregistered(info: NsdServiceInfo) {}
        override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {}
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_receiver)
        root = findViewById(R.id.root)
        surfaceView = findViewById(R.id.surface)
        overlay = findViewById(R.id.overlay)
        title = findViewById(R.id.title)
        hint = findViewById(R.id.hint)
        address = findViewById(R.id.address)
        surfaceView.holder.addCallback(this)
        root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> applyAspect() }
        hideSystemBars()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    @Suppress("DEPRECATION")
    private fun hideSystemBars() {
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
    }

    override fun onStart() {
        super.onStart()
        active = true
        showWaiting(null)
        Thread({ serve() }, "kor-server").start()
        registerService()
    }

    override fun onStop() {
        active = false
        try { server?.close() } catch (_: Exception) {}
        try { client?.close() } catch (_: Exception) {}
        if (registered) {
            registered = false
            try { nsd.unregisterService(registration) } catch (_: Exception) {}
        }
        super.onStop()
    }

    override fun surfaceCreated(holder: SurfaceHolder) { surface = holder.surface }
    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}
    override fun surfaceDestroyed(holder: SurfaceHolder) {
        surface = null
        // Yüzey gidince çözücü de duramaz; bağlantıyı kapat, telefon yeniden bağlanabilir.
        try { client?.close() } catch (_: Exception) {}
    }

    private fun registerService() {
        val info = NsdServiceInfo().apply {
            serviceName = Build.MODEL ?: "Android TV"
            serviceType = Protocol.SERVICE_TYPE
            port = Protocol.PORT
        }
        try {
            nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, registration)
            registered = true
        } catch (e: Exception) {
            Log.w(TAG, "Ağda duyurulamadı", e)
        }
    }

    /* ---------- Arayüz ---------- */

    private fun showWaiting(error: String?) = runOnUiThread {
        videoWidth = 0
        videoHeight = 0
        overlay.visibility = View.VISIBLE
        title.setText(R.string.rx_waiting)
        hint.text = error ?: getString(R.string.rx_hint)
        val ip = localAddress()
        address.text = if (ip != null) getString(R.string.rx_address, ip) else getString(R.string.rx_no_network)
    }

    private fun showConnected() = runOnUiThread {
        title.setText(R.string.rx_connected)
        hint.text = ""
    }

    private fun showVideo(width: Int, height: Int) = runOnUiThread {
        videoWidth = width
        videoHeight = height
        applyAspect()
        overlay.visibility = View.GONE
    }

    /** Görüntü oranını koruyarak yüzeyi ekrana sığdırır (gerekirse siyah bantlarla). */
    private fun applyAspect() {
        if (videoWidth <= 0 || videoHeight <= 0 || root.width == 0 || root.height == 0) return
        val scale = minOf(root.width.toFloat() / videoWidth, root.height.toFloat() / videoHeight)
        val width = (videoWidth * scale).toInt()
        val height = (videoHeight * scale).toInt()
        val params = surfaceView.layoutParams
        if (params.width != width || params.height != height) {
            params.width = width
            params.height = height
            surfaceView.layoutParams = params
        }
    }

    private fun localAddress(): String? = try {
        NetworkInterface.getNetworkInterfaces().asSequence()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.asSequence() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { it.isSiteLocalAddress }
            ?.hostAddress
    } catch (e: Exception) {
        null
    }

    /* ---------- Ağ ve çözme ---------- */

    private fun serve() {
        val socket = try {
            ServerSocket().apply {
                reuseAddress = true
                bind(java.net.InetSocketAddress(Protocol.PORT))
            }
        } catch (e: Exception) {
            if (active) showWaiting(e.message)
            return
        }
        server = socket
        while (active) {
            val accepted = try { socket.accept() } catch (e: Exception) { break }
            client = accepted
            var error: String? = null
            try {
                handle(accepted)
            } catch (e: MediaCodec.CodecException) {
                Log.w(TAG, "Çözücü hatası", e)
                error = getString(R.string.rx_decoder_error)
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "Çözücü yapılandırılamadı", e)
                error = getString(R.string.rx_decoder_error)
            } catch (e: Exception) {
                Log.i(TAG, "Bağlantı kapandı: ${e.message}")
            } finally {
                try { accepted.close() } catch (_: Exception) {}
                client = null
            }
            if (active) showWaiting(error)
        }
        try { socket.close() } catch (_: Exception) {}
    }

    private fun handle(socket: Socket) {
        socket.tcpNoDelay = true
        val input = DataInputStream(socket.getInputStream().buffered(256 * 1024))
        val output = DataOutputStream(socket.getOutputStream())
        socket.soTimeout = 5000
        input.readSenderHello()
        output.writeReceiverHello(ReceiverCaps(maxDecodeHeight(Sizing.MIME_HEVC), maxDecodeHeight(Sizing.MIME_AVC)))
        socket.soTimeout = 0
        showConnected()

        var decoder: MediaCodec? = null
        var drain: Thread? = null
        var track: AudioTrack? = null
        try {
            while (active) {
                val packet = input.readPacket()
                when (packet.type) {
                    Protocol.TYPE_VIDEO_CONFIG -> {
                        val config = VideoConfig.parse(packet.payload)
                        val target = awaitSurface() ?: return
                        showVideo(config.width, config.height)
                        val codec = MediaCodec.createDecoderByType(config.mime)
                        decoder = codec
                        val format = MediaFormat.createVideoFormat(config.mime, config.width, config.height).apply {
                            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 4 * 1024 * 1024)
                            setInteger(MediaFormat.KEY_PRIORITY, 0)
                            if (Build.VERSION.SDK_INT >= 30) setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
                        }
                        codec.configure(format, target, null, 0)
                        codec.start()
                        drain = Thread({ drainLoop(codec, socket) }, "kor-render").also { it.start() }
                    }
                    Protocol.TYPE_VIDEO -> decoder?.let { feed(it, packet) }
                    Protocol.TYPE_AUDIO_CONFIG -> {
                        val config = AudioConfig.parse(packet.payload)
                        track?.release()
                        track = createTrack(config)
                    }
                    Protocol.TYPE_AUDIO -> track?.let {
                        // Engellemeden yaz: tampon doluysa fazlası atılır, ses gecikmesi birikmez.
                        it.write(packet.payload, 0, packet.payload.size, AudioTrack.WRITE_NON_BLOCKING)
                    }
                }
            }
        } finally {
            try { socket.close() } catch (_: Exception) {}
            drain?.let { it.interrupt(); try { it.join(500) } catch (_: InterruptedException) {} }
            try { decoder?.stop() } catch (_: Exception) {}
            try { decoder?.release() } catch (_: Exception) {}
            try { track?.release() } catch (_: Exception) {}
        }
    }

    private fun awaitSurface(): Surface? {
        repeat(50) {
            surface?.let { if (it.isValid) return it }
            if (!active) return null
            Thread.sleep(100)
        }
        return null
    }

    private fun feed(codec: MediaCodec, packet: Packet) {
        while (active) {
            val index = codec.dequeueInputBuffer(10_000)
            if (index < 0) continue
            val buffer = codec.getInputBuffer(index) ?: continue
            buffer.clear()
            buffer.put(packet.payload)
            var flags = 0
            if (packet.isCodecConfig) flags = flags or MediaCodec.BUFFER_FLAG_CODEC_CONFIG
            if (packet.isKeyFrame) flags = flags or MediaCodec.BUFFER_FLAG_KEY_FRAME
            codec.queueInputBuffer(index, 0, packet.payload.size, packet.ptsUs, flags)
            return
        }
    }

    /** Çözülen kareleri bekletmeden ekrana basar. */
    private fun drainLoop(codec: MediaCodec, socket: Socket) {
        val info = MediaCodec.BufferInfo()
        try {
            while (active && !Thread.currentThread().isInterrupted) {
                val index = codec.dequeueOutputBuffer(info, 10_000)
                if (index >= 0) codec.releaseOutputBuffer(index, true)
            }
        } catch (e: Exception) {
            // Çözücü hata verdiyse ya da kapatıldıysa okuma döngüsü de bitsin.
            try { socket.close() } catch (_: Exception) {}
        }
    }

    private fun createTrack(config: AudioConfig): AudioTrack {
        val mask = if (config.channels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
        val minBuffer = AudioTrack.getMinBufferSize(config.sampleRate, mask, AudioFormat.ENCODING_PCM_16BIT)
        val tenthSecond = config.sampleRate * config.channels * 2 / 10
        val builder = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(config.sampleRate)
                    .setChannelMask(mask)
                    .build()
            )
            .setBufferSizeInBytes(maxOf(minBuffer, tenthSecond))
            .setTransferMode(AudioTrack.MODE_STREAM)
        if (Build.VERSION.SDK_INT >= 26) builder.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
        return builder.build().apply { play() }
    }

    /** Bu cihazın [mime] için çözebildiği en yüksek 16:9 basamağın yüksekliği; yoksa 0. */
    private fun maxDecodeHeight(mime: String): Int {
        val tiers = listOf(3840 to 2160, 2560 to 1440, 1920 to 1080, 1280 to 720)
        val decoders = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { info ->
            !info.isEncoder && info.supportedTypes.any { it.equals(mime, ignoreCase = true) }
        }
        for ((width, height) in tiers) {
            val supported = decoders.any { info ->
                try {
                    info.getCapabilitiesForType(mime).videoCapabilities?.isSizeSupported(width, height) == true
                } catch (e: Exception) {
                    false
                }
            }
            if (supported) return height
        }
        return 0
    }

    companion object {
        private const val TAG = "KorCast"
    }
}
