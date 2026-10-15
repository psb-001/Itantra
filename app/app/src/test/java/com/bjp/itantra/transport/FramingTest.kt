package com.bjp.itantra.transport

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

/**
 * Tests for [Framing]. Pins the wire format so a future edit cannot silently change what goes
 * over Bluetooth without this test failing.
 */
class FramingTest {

    private fun write(type: Int, payload: ByteArray): ByteArray {
        val bytes = ByteArrayOutputStream()
        Framing.write(DataOutputStream(bytes), type, payload)
        return bytes.toByteArray()
    }

    @Test
    fun `a frame is length, then type, then payload`() {
        val frame = write(Framing.DATA, byteArrayOf(0x41, 0x42))
        // length = payload + type byte, big-endian
        assertEquals(0, frame[0].toInt())
        assertEquals(0, frame[1].toInt())
        assertEquals(0, frame[2].toInt())
        assertEquals(3, frame[3].toInt())
        assertEquals(Framing.DATA, frame[4].toInt())
        assertEquals(0x41, frame[5].toInt())
        assertEquals(0x42, frame[6].toInt())
        assertEquals(7, frame.size)
    }

    @Test
    fun `a keepalive is a frame with only the type byte`() {
        val frame = write(Framing.PING, ByteArray(0))
        assertEquals(5, frame.size)
        assertEquals(1, frame[3].toInt())
        assertEquals(Framing.PING, frame[4].toInt())
    }

    @Test(expected = IllegalArgumentException::class)
    fun `an oversized frame is refused rather than truncated`() {
        write(Framing.DATA, ByteArray(Framing.MAX_FRAME))
    }
}
