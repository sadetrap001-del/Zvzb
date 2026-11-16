package com.korstudio.cast

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException

/**
 * Telefon (gönderici) ile TV (alıcı) arasındaki tel protokolü. Tek bir TCP bağlantısı kullanılır.
 *
 * Açılış: gönderici [MAGIC, VERSION] yollar; alıcı [MAGIC, VERSION, hevcMaxHeight, avcMaxHeight]
 * ile yanıtlar. Sonrası göndericiden alıcıya akan paketlerdir:
 * [type:1][flags:1][ptsUs:8][length:4][payload].
 */
object Protocol {
    const val PORT = 47800
    const val SERVICE_TYPE = "_korcast._tcp."
    const val MAGIC = 0x4B4F5243 // "KORC"
    const val VERSION = 1

    const val TYPE_VIDEO_CONFIG = 1
    const val TYPE_VIDEO = 2
    const val TYPE_AUDIO_CONFIG = 3
    const val TYPE_AUDIO = 4

    const val FLAG_KEY_FRAME = 1
    const val FLAG_CODEC_CONFIG = 2

    const val MAX_PAYLOAD = 16 * 1024 * 1024
}

class Packet(val type: Int, val flags: Int, val ptsUs: Long, val payload: ByteArray) {
    val isKeyFrame get() = flags and Protocol.FLAG_KEY_FRAME != 0
    val isCodecConfig get() = flags and Protocol.FLAG_CODEC_CONFIG != 0
}

class VideoConfig(val mime: String, val width: Int, val height: Int, val fps: Int) {
    fun toPacket(): Packet {
        val bytes = java.io.ByteArrayOutputStream()
        DataOutputStream(bytes).apply {
            writeUTF(mime)
            writeInt(width)
            writeInt(height)
            writeInt(fps)
        }
        return Packet(Protocol.TYPE_VIDEO_CONFIG, 0, 0, bytes.toByteArray())
    }

    companion object {
        fun parse(payload: ByteArray): VideoConfig {
            val input = DataInputStream(payload.inputStream())
            return VideoConfig(input.readUTF(), input.readInt(), input.readInt(), input.readInt())
        }
    }
}

class AudioConfig(val sampleRate: Int, val channels: Int) {
    fun toPacket(): Packet {
        val bytes = java.io.ByteArrayOutputStream()
        DataOutputStream(bytes).apply {
            writeInt(sampleRate)
            writeInt(channels)
        }
        return Packet(Protocol.TYPE_AUDIO_CONFIG, 0, 0, bytes.toByteArray())
    }

    companion object {
        fun parse(payload: ByteArray): AudioConfig {
            val input = DataInputStream(payload.inputStream())
            return AudioConfig(input.readInt(), input.readInt())
        }
    }
}

/** Alıcının çözebildiği en yüksek çözünürlük (yükseklik olarak); 0 = o kodek yok. */
class ReceiverCaps(val hevcMaxHeight: Int, val avcMaxHeight: Int)

fun DataOutputStream.writePacket(p: Packet) {
    writeByte(p.type)
    writeByte(p.flags)
    writeLong(p.ptsUs)
    writeInt(p.payload.size)
    write(p.payload)
}

fun DataInputStream.readPacket(): Packet {
    val type = readUnsignedByte()
    val flags = readUnsignedByte()
    val pts = readLong()
    val length = readInt()
    if (length < 0 || length > Protocol.MAX_PAYLOAD) throw IOException("Geçersiz paket boyutu: $length")
    val payload = ByteArray(length)
    readFully(payload)
    return Packet(type, flags, pts, payload)
}

fun DataOutputStream.writeSenderHello() {
    writeInt(Protocol.MAGIC)
    writeInt(Protocol.VERSION)
    flush()
}

fun DataInputStream.readSenderHello() {
    if (readInt() != Protocol.MAGIC) throw IOException("Tanınmayan bağlantı")
    val version = readInt()
    if (version != Protocol.VERSION) throw IOException("Uyumsuz sürüm: $version")
}

fun DataOutputStream.writeReceiverHello(caps: ReceiverCaps) {
    writeInt(Protocol.MAGIC)
    writeInt(Protocol.VERSION)
    writeInt(caps.hevcMaxHeight)
    writeInt(caps.avcMaxHeight)
    flush()
}

fun DataInputStream.readReceiverHello(): ReceiverCaps {
    if (readInt() != Protocol.MAGIC) throw IOException("Karşı taraf Kor Cast değil")
    val version = readInt()
    if (version != Protocol.VERSION) throw IOException("Uyumsuz sürüm: $version")
    return ReceiverCaps(readInt(), readInt())
}
