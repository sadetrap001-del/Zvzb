package com.korstudio.cast

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Locale
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** Telefonun ekranını (ve oyun sesini) yakalayıp kodlayarak TV'ye gönderen ön plan servisi. */
class CastService : Service() {

    enum class Phase { IDLE, CONNECTING, STREAMING, ERROR, ENDED }
    class State(val phase: Phase, val detail: String = "")

    private class Encoder(val codec: MediaCodec, val mime: String, val size: VideoSize)

    private val running = AtomicBoolean(false)
    private val stopped = AtomicBoolean(false)
    private val queue = LinkedBlockingDeque<Packet>()
    private val queuedVideo = AtomicInteger(0)
    private val bytesSent = AtomicLong(0)
    @Volatile private var dropUntilKey = false

    private var socket: Socket? = null
    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var encoder: MediaCodec? = null
    private var encoderThread: HandlerThread? = null
    private var audioRecord: AudioRecord? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var summary = ""

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() = shutdown(null)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                if (running.getAndSet(true)) return START_NOT_STICKY
                // Android 14+: ön plan servisi, ekran yakalama izni kullanılmadan önce başlamış olmalı.
                startAsForeground()
                publish(State(Phase.CONNECTING))
                val args = intent.extras ?: Bundle()
                Thread({ runSession(args) }, "kor-session").start()
            }
            ACTION_STOP -> shutdown(null)
            else -> if (!running.get()) stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        shutdown(null)
        super.onDestroy()
    }

    private fun startAsForeground() {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW)
            )
        }
        val immutable = if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0
        val open = PendingIntent.getActivity(this, 0, Intent(this, SenderActivity::class.java), immutable)
        val stop = PendingIntent.getService(
            this, 1, Intent(this, CastService::class.java).setAction(ACTION_STOP), immutable
        )
        @Suppress("DEPRECATION")
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL_ID) else Notification.Builder(this)
        val notification = builder
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(getString(R.string.notif_text))
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, getString(R.string.stop), stop).build())
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun runSession(args: Bundle) {
        try {
            val host = args.getString(EXTRA_HOST) ?: error("TV adresi yok")
            val port = args.getInt(EXTRA_PORT, Protocol.PORT)
            val preset = Preset.values()[args.getInt(EXTRA_PRESET, Preset.FHD.ordinal)]
            val bitrate = args.getInt(EXTRA_BITRATE_MBPS, 20) * 1_000_000
            val fps = args.getInt(EXTRA_FPS, 60)
            val wantAudio = args.getBoolean(EXTRA_AUDIO, false)
            @Suppress("DEPRECATION")
            val grant: Intent = args.getParcelable(EXTRA_RESULT_DATA) ?: error("Ekran izni yok")
            val resultCode = args.getInt(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)

            val sock = Socket()
            socket = sock
            sock.tcpNoDelay = true
            sock.connect(InetSocketAddress(host, port), 5000)
            val output = DataOutputStream(sock.getOutputStream().buffered(64 * 1024))
            val input = DataInputStream(sock.getInputStream())
            sock.soTimeout = 5000
            output.writeSenderHello()
            val caps = input.readReceiverHello()
            sock.soTimeout = 0
            if (stopped.get()) return

            val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val proj = manager.getMediaProjection(resultCode, grant) ?: error("Ekran yakalama başlatılamadı")
            projection = proj
            proj.registerCallback(projectionCallback, Handler(Looper.getMainLooper()))

            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            (getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(metrics)
            val srcLong = maxOf(metrics.widthPixels, metrics.heightPixels)
            val srcShort = minOf(metrics.widthPixels, metrics.heightPixels)

            val enc = createEncoder(srcLong, srcShort, preset, caps, bitrate, fps)
            encoder = enc.codec
            val surface = enc.codec.createInputSurface()

            queue.put(VideoConfig(enc.mime, enc.size.width, enc.size.height, fps).toPacket())
            val thread = HandlerThread("kor-encoder").also { it.start() }
            encoderThread = thread
            enc.codec.setCallback(encoderCallback, Handler(thread.looper))
            enc.codec.start()

            // Sanal ekran yatay olduğu için telefon dikeyken görüntü ortalanır, yatayken tam oturur.
            virtualDisplay = proj.createVirtualDisplay(
                "KorCast", enc.size.width, enc.size.height, metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, surface, null, null
            )

            if (wantAudio && Build.VERSION.SDK_INT >= 29) startAudio(proj)
            acquireWifiLock()

            val codecName = if (enc.mime == Sizing.MIME_HEVC) "H.265" else "H.264"
            summary = "${enc.size} · $codecName · $fps fps"
            publish(State(Phase.STREAMING, summary))
            writeLoop(output)
        } catch (e: Exception) {
            Log.w(TAG, "Yayın hatası", e)
            shutdown(e.message ?: e.javaClass.simpleName)
        }
    }

    /** Hem TV'nin çözebildiği hem telefonun kodlayabildiği en iyi boyut ve kodeği bulur. */
    private fun createEncoder(
        srcLong: Int, srcShort: Int, preset: Preset, caps: ReceiverCaps, bitrate: Int, fps: Int,
    ): Encoder {
        for (size in Sizing.candidates(srcLong, srcShort, preset)) {
            for ((mime, receiverMax) in listOf(Sizing.MIME_HEVC to caps.hevcMaxHeight, Sizing.MIME_AVC to caps.avcMaxHeight)) {
                if (!Sizing.fitsReceiver(size, receiverMax)) continue
                var codec: MediaCodec? = null
                try {
                    codec = MediaCodec.createEncoderByType(mime)
                    val format = MediaFormat.createVideoFormat(mime, size.width, size.height).apply {
                        setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                        setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                        setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                        setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
                        // Ekran durağanken de kare gönder ki TV'de görüntü hemen gelsin.
                        setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 100_000L)
                        setInteger(MediaFormat.KEY_PRIORITY, 0)
                    }
                    val encoderCaps = codec.codecInfo.getCapabilitiesForType(mime).encoderCapabilities
                    if (encoderCaps.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)) {
                        format.setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
                    }
                    codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                    return Encoder(codec, mime, size)
                } catch (e: Exception) {
                    Log.i(TAG, "$mime $size kodlanamıyor: ${e.message}")
                    try { codec?.release() } catch (_: Exception) {}
                }
            }
        }
        error("Telefon ile TV'nin ortak desteklediği bir görüntü biçimi bulunamadı")
    }

    private val encoderCallback = object : MediaCodec.Callback() {
        override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {}

        override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
            try {
                val buffer = codec.getOutputBuffer(index)
                if (buffer != null && info.size > 0) {
                    val data = ByteArray(info.size)
                    buffer.position(info.offset)
                    buffer.get(data)
                    var flags = 0
                    if (info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0) flags = flags or Protocol.FLAG_KEY_FRAME
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) flags = flags or Protocol.FLAG_CODEC_CONFIG
                    enqueueVideo(Packet(Protocol.TYPE_VIDEO, flags, info.presentationTimeUs, data))
                }
                codec.releaseOutputBuffer(index, false)
            } catch (e: IllegalStateException) {
                // Kodlayıcı kapatılırken gelen geç geri çağrılar.
            }
        }

        override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
            shutdown("Kodlayıcı hatası: ${e.diagnosticInfo}")
        }

        override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {}
    }

    /**
     * Ağ yavaşlayıp kuyruk dolarsa birikmiş kareleri atar ve yeni bir anahtar kare ister;
     * böylece gecikme birikmez, görüntü kısa bir takılmayla toparlanır.
     */
    private fun enqueueVideo(packet: Packet) {
        if (packet.isCodecConfig) {
            queue.put(packet)
            return
        }
        if (dropUntilKey) {
            if (!packet.isKeyFrame) return
            dropUntilKey = false
        } else if (queuedVideo.get() > MAX_QUEUED_FRAMES) {
            queue.removeIf { it.type == Protocol.TYPE_VIDEO && !it.isCodecConfig }
            queuedVideo.set(0)
            dropUntilKey = true
            requestKeyFrame()
            return
        }
        queuedVideo.incrementAndGet()
        queue.put(packet)
    }

    private fun requestKeyFrame() {
        try {
            encoder?.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) })
        } catch (_: Exception) {
        }
    }

    private fun writeLoop(output: DataOutputStream) {
        var windowStart = System.nanoTime()
        try {
            while (!stopped.get()) {
                val packet = queue.take()
                if (stopped.get()) break
                if (packet.type == Protocol.TYPE_VIDEO && !packet.isCodecConfig) queuedVideo.decrementAndGet()
                output.writePacket(packet)
                if (queue.isEmpty()) output.flush()
                bytesSent.addAndGet(packet.payload.size.toLong())

                val now = System.nanoTime()
                if (now - windowStart > 2_000_000_000L) {
                    val mbps = bytesSent.getAndSet(0) * 8.0 / ((now - windowStart) / 1e9) / 1e6
                    windowStart = now
                    if (!stopped.get()) {
                        publish(State(Phase.STREAMING, String.format(Locale.US, "%s · %.1f Mbps", summary, mbps)))
                    }
                }
            }
        } catch (e: InterruptedException) {
            // Durdurma sırasında beklenir.
        } catch (e: Exception) {
            shutdown(if (stopped.get()) null else "TV ile bağlantı koptu")
        }
    }

    @SuppressLint("MissingPermission")
    private fun startAudio(proj: MediaProjection) {
        if (Build.VERSION.SDK_INT < 29) return
        try {
            val config = AudioPlaybackCaptureConfiguration.Builder(proj)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()
            val format = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(AUDIO_RATE)
                .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                .build()
            val chunk = AUDIO_RATE * 2 * 2 / 50 // 20 ms
            val minBuffer = AudioRecord.getMinBufferSize(AUDIO_RATE, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT)
            val record = AudioRecord.Builder()
                .setAudioFormat(format)
                .setBufferSizeInBytes(maxOf(minBuffer, chunk * 4))
                .setAudioPlaybackCaptureConfig(config)
                .build()
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                record.release()
                return
            }
            audioRecord = record
            record.startRecording()
            queue.put(AudioConfig(AUDIO_RATE, 2).toPacket())
            Thread({
                val buffer = ByteArray(chunk)
                while (!stopped.get()) {
                    val read = try { record.read(buffer, 0, buffer.size) } catch (e: Exception) { -1 }
                    if (read <= 0) break
                    // Ağ tıkanmışsa sesi biriktirme, at.
                    if (queue.size < MAX_QUEUED_PACKETS) {
                        queue.put(Packet(Protocol.TYPE_AUDIO, 0, System.nanoTime() / 1000, buffer.copyOf(read)))
                    }
                }
            }, "kor-audio").start()
        } catch (e: Exception) {
            // Mikrofon izni yoksa ya da cihaz desteklemiyorsa yayın sessiz devam eder.
            Log.w(TAG, "Ses yakalanamadı", e)
        }
    }

    private fun acquireWifiLock() {
        try {
            val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION")
            val mode = if (Build.VERSION.SDK_INT >= 29) WifiManager.WIFI_MODE_FULL_LOW_LATENCY else WifiManager.WIFI_MODE_FULL_HIGH_PERF
            wifiLock = wifi.createWifiLock(mode, "korcast:stream").apply { acquire() }
        } catch (e: Exception) {
            Log.w(TAG, "Wi-Fi kilidi alınamadı", e)
        }
    }

    /** Her şeyi kapatır. [error] doluysa hata olarak, boşsa normal bitiş olarak bildirilir. */
    private fun shutdown(error: String?) {
        if (stopped.getAndSet(true)) return
        publish(if (error != null) State(Phase.ERROR, error) else State(Phase.ENDED))
        try { socket?.close() } catch (_: Exception) {}
        try { audioRecord?.stop() } catch (_: Exception) {}
        try { audioRecord?.release() } catch (_: Exception) {}
        try { virtualDisplay?.release() } catch (_: Exception) {}
        try { encoder?.stop() } catch (_: Exception) {}
        try { encoder?.release() } catch (_: Exception) {}
        encoderThread?.quitSafely()
        try { projection?.unregisterCallback(projectionCallback) } catch (_: Exception) {}
        try { projection?.stop() } catch (_: Exception) {}
        try { wifiLock?.takeIf { it.isHeld }?.release() } catch (_: Exception) {}
        queue.clear()
        // Yazma döngüsü kuyrukta bekliyorsa uyansın.
        queue.offer(Packet(0, 0, 0, ByteArray(0)))
        @Suppress("DEPRECATION")
        stopForeground(true)
        stopSelf()
    }

    companion object {
        private const val TAG = "KorCast"
        private const val CHANNEL_ID = "cast"
        private const val NOTIFICATION_ID = 1
        private const val AUDIO_RATE = 48000
        private const val MAX_QUEUED_FRAMES = 45
        private const val MAX_QUEUED_PACKETS = 200

        const val ACTION_START = "com.korstudio.cast.START"
        const val ACTION_STOP = "com.korstudio.cast.STOP"
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_RESULT_DATA = "resultData"
        const val EXTRA_HOST = "host"
        const val EXTRA_PORT = "port"
        const val EXTRA_PRESET = "preset"
        const val EXTRA_BITRATE_MBPS = "bitrate"
        const val EXTRA_FPS = "fps"
        const val EXTRA_AUDIO = "audio"

        private val mainHandler = Handler(Looper.getMainLooper())

        @Volatile var state = State(Phase.IDLE)
            private set

        /** Ana iş parçacığında çağrılır. */
        var listener: ((State) -> Unit)? = null

        val isActive get() = state.phase == Phase.CONNECTING || state.phase == Phase.STREAMING

        private fun publish(newState: State) {
            state = newState
            mainHandler.post { listener?.invoke(newState) }
        }
    }
}
