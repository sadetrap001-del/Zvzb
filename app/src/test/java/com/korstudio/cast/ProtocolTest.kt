package com.korstudio.cast

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException

class ProtocolTest {
    private fun roundTrip(write: DataOutputStream.() -> Unit): DataInputStream {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).write()
        return DataInputStream(bytes.toByteArray().inputStream())
    }

    @Test
    fun packetsSurviveARoundTrip() {
        val payload = ByteArray(5000) { (it * 7).toByte() }
        val input = roundTrip {
            writePacket(Packet(Protocol.TYPE_VIDEO, Protocol.FLAG_KEY_FRAME, 123_456_789_012L, payload))
            writePacket(Packet(Protocol.TYPE_AUDIO, 0, 5, ByteArray(0)))
        }
        val first = input.readPacket()
        assertEquals(Protocol.TYPE_VIDEO, first.type)
        assertTrue(first.isKeyFrame)
        assertFalse(first.isCodecConfig)
        assertEquals(123_456_789_012L, first.ptsUs)
        assertArrayEquals(payload, first.payload)
        val second = input.readPacket()
        assertEquals(Protocol.TYPE_AUDIO, second.type)
        assertEquals(0, second.payload.size)
    }

    @Test
    fun oversizedPacketIsRejected() {
        val input = roundTrip {
            writeByte(Protocol.TYPE_VIDEO)
            writeByte(0)
            writeLong(0)
            writeInt(Protocol.MAX_PAYLOAD + 1)
        }
        assertThrows(IOException::class.java) { input.readPacket() }
    }

    @Test
    fun handshakeCarriesReceiverCapabilities() {
        roundTrip { writeSenderHello() }.readSenderHello()
        val caps = roundTrip { writeReceiverHello(ReceiverCaps(2160, 1080)) }.readReceiverHello()
        assertEquals(2160, caps.hevcMaxHeight)
        assertEquals(1080, caps.avcMaxHeight)
    }

    @Test
    fun foreignConnectionIsRejected() {
        val input = roundTrip { writeInt(0x47455420); writeInt(1) } // "GET "
        assertThrows(IOException::class.java) { input.readSenderHello() }
    }

    @Test
    fun configsSurviveARoundTrip() {
        val video = VideoConfig.parse(VideoConfig("video/hevc", 3840, 1728, 60).toPacket().payload)
        assertEquals("video/hevc", video.mime)
        assertEquals(3840, video.width)
        assertEquals(1728, video.height)
        assertEquals(60, video.fps)
        val audio = AudioConfig.parse(AudioConfig(48000, 2).toPacket().payload)
        assertEquals(48000, audio.sampleRate)
        assertEquals(2, audio.channels)
    }
}
