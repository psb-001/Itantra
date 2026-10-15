package com.bjp.itantra

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pins the delivery-receipt envelope: an ACK must carry the id it confirms and survive the
 * wire, and an ordinary message must keep carrying no `ackFor` field at all.
 */
class MessageAckTest {

    @Test
    fun `an ack carries the id it confirms and survives the wire`() {
        val ack = Message(
            lang = "und",
            type = Message.Type.ACK,
            seq = 0,
            ttl = 0,
            text = "",
            ackFor = "0f8c-abc",
        )
        val back = Message.fromBytes(ack.toBytes())!!
        assertEquals(Message.Type.ACK, back.type)
        assertEquals("0f8c-abc", back.ackFor)
    }

    @Test
    fun `a normal message has no ackFor`() {
        val msg = Message(lang = "hin_Deva", type = Message.Type.MSG, seq = 7, text = "मदद भेजो")
        assertNull(msg.ackFor)
        assertNull(Message.fromBytes(msg.toBytes())!!.ackFor)
    }
}
