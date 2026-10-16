package com.bjp.itantra

import com.bjp.itantra.speech.SpeechQueueOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.LinkedBlockingDeque

/**
 * The alert-yield rule, pinned down.
 *
 * The failure it closes: an ALERT that arrives while the speech worker is loading a voice or
 * synthesising has nothing playing to interrupt, so without this rule the worker would play the
 * message it was synthesising IN FULL first — pushing the alert behind several seconds of the
 * chatter it was meant to cut in front of. The message itself must lose nothing: it re-queues
 * behind the alerts and ahead of all later normal messages.
 */
class SpeechQueueOrderTest {

    private fun msg(n: Int, type: Message.Type = Message.Type.MSG) =
        Message(id = "m$n", lang = "hin_Deva", type = type, seq = n, text = "text $n")

    private fun order(q: LinkedBlockingDeque<Message>): List<String> = q.map { it.id }

    @Test
    fun `enqueue puts alerts at the head and messages at the tail`() {
        val q = LinkedBlockingDeque<Message>()
        SpeechQueueOrder.enqueue(q, msg(1))
        SpeechQueueOrder.enqueue(q, msg(2, Message.Type.ALERT))
        SpeechQueueOrder.enqueue(q, msg(3))
        assertEquals(listOf("m2", "m1", "m3"), order(q))
    }

    @Test
    fun `no alert waiting -- nothing changes`() {
        val q = LinkedBlockingDeque<Message>()
        SpeechQueueOrder.enqueue(q, msg(1))
        SpeechQueueOrder.enqueue(q, msg(2))
        assertFalse(SpeechQueueOrder.yieldBehindAlerts(q, msg(3)))
        assertEquals(listOf("m1", "m2"), order(q))
    }

    @Test
    fun `yields behind one alert and keeps its place ahead of later messages`() {
        val q = LinkedBlockingDeque<Message>()
        SpeechQueueOrder.enqueue(q, msg(2))
        SpeechQueueOrder.enqueue(q, msg(3))
        SpeechQueueOrder.enqueue(q, msg(9, Message.Type.ALERT))
        assertTrue(SpeechQueueOrder.yieldBehindAlerts(q, msg(1)))
        assertEquals(listOf("m9", "m1", "m2", "m3"), order(q))
    }

    @Test
    fun `several alerts keep their newest-first order and all precede the message`() {
        val q = LinkedBlockingDeque<Message>()
        SpeechQueueOrder.enqueue(q, msg(2))
        SpeechQueueOrder.enqueue(q, msg(8, Message.Type.ALERT))
        SpeechQueueOrder.enqueue(q, msg(9, Message.Type.ALERT))
        assertTrue(SpeechQueueOrder.yieldBehindAlerts(q, msg(1)))
        assertEquals(listOf("m9", "m8", "m1", "m2"), order(q))
    }

    @Test
    fun `an alert does not yield to another alert`() {
        val q = LinkedBlockingDeque<Message>()
        SpeechQueueOrder.enqueue(q, msg(9, Message.Type.ALERT))
        assertFalse(SpeechQueueOrder.yieldBehindAlerts(q, msg(8, Message.Type.ALERT)))
        assertEquals(listOf("m9"), order(q))
    }

    @Test
    fun `empty queue -- nothing to yield to`() {
        val q = LinkedBlockingDeque<Message>()
        assertFalse(SpeechQueueOrder.yieldBehindAlerts(q, msg(1)))
        assertTrue(q.isEmpty())
    }
}
