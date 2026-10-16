package com.bjp.itantra.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [Outbox]. Pure JVM — no Android, no sockets.
 *
 * The failure these pin down: before this class, `send()` on a down link logged and dropped, and
 * the speaker's screen showed "Sent" anyway. The tests below are the contract that replaces it.
 */
class OutboxTest {

    private fun bytes(vararg n: Int) = ByteArray(n.size) { n[it].toByte() }

    @Test
    fun `up link sends immediately and buffers nothing`() {
        val out = mutableListOf<ByteArray>()
        val box = Outbox { b -> out.add(b); true }

        assertTrue(box.enqueue(bytes(1)))
        assertTrue(box.enqueue(bytes(2)))

        assertEquals(2, out.size)
        assertEquals(0, box.depth)
        assertEquals(0, box.dropped)
    }

    @Test
    fun `down link buffers in order and flush drains oldest-first`() {
        var up = false
        val out = mutableListOf<ByteArray>()
        val box = Outbox { b -> if (up) { out.add(b); true } else false }

        assertFalse(box.enqueue(bytes(1)))
        assertFalse(box.enqueue(bytes(2)))
        assertFalse(box.enqueue(bytes(3)))
        assertEquals(3, box.depth)

        up = true
        assertEquals(3, box.flush())

        assertEquals(3, out.size)
        assertEquals(1, out[0][0].toInt())
        assertEquals(2, out[1][0].toInt())
        assertEquals(3, out[2][0].toInt())
        assertEquals(0, box.depth)
    }

    @Test
    fun `flush stops at the first failure and keeps the remainder ordered`() {
        var up = false
        val out = mutableListOf<ByteArray>()
        var accepted = 0
        val box = Outbox { b ->
            if (up && accepted < 1) { accepted++; out.add(b); true } else false
        }

        box.enqueue(bytes(1)); box.enqueue(bytes(2)); box.enqueue(bytes(3))
        up = true

        assertEquals(1, box.flush())
        assertEquals(2, box.depth)
        assertEquals(1, out.size)
    }

    @Test
    fun `new packet queues behind buffered ones even when the link is back up`() {
        var up = false
        val out = mutableListOf<ByteArray>()
        val box = Outbox { b -> if (up) { out.add(b); true } else false }

        box.enqueue(bytes(1))            // buffered while down
        up = true
        assertFalse(box.enqueue(bytes(2)))   // must NOT overtake packet 1

        assertEquals(2, box.depth)
        assertEquals(2, box.flush())
        assertEquals(1, out[0][0].toInt())
        assertEquals(2, out[1][0].toInt())
    }

    @Test
    fun `overflow drops the oldest and counts it`() {
        var up = false
        val out = mutableListOf<ByteArray>()
        val box = Outbox(capacity = 2) { b -> if (up) { out.add(b); true } else false }

        box.enqueue(bytes(1))
        box.enqueue(bytes(2))
        box.enqueue(bytes(3))            // capacity 2 — packet 1 is dropped

        assertEquals(2, box.depth)
        assertEquals(1, box.dropped)

        up = true
        box.flush()
        assertEquals(2, out.size)
        assertEquals(2, out[0][0].toInt())
        assertEquals(3, out[1][0].toInt())
    }

    @Test
    fun `flush on an empty buffer is a no-op`() {
        val box = Outbox { true }
        assertEquals(0, box.flush())
    }

    @Test
    fun `snapshot copies the buffer without draining it and restore keeps the order`() {
        var up = false
        val box = Outbox { up }
        box.enqueue(bytes(1)); box.enqueue(bytes(2)); box.enqueue(bytes(3))

        val saved = box.snapshot()
        assertEquals(3, saved.size)
        assertEquals(3, box.depth) // a snapshot is a copy, not a flush

        // A fresh outbox, as after a process restart.
        val sent = mutableListOf<ByteArray>()
        val box2 = Outbox { b -> sent.add(b); true }
        box2.restore(saved)
        assertEquals(3, box2.flush())
        assertEquals(1, sent[0][0].toInt())
        assertEquals(2, sent[1][0].toInt())
        assertEquals(3, sent[2][0].toInt())
    }

    @Test
    fun `restore respects capacity, dropping the oldest`() {
        val sent = mutableListOf<ByteArray>()
        val box = Outbox(capacity = 2) { b -> sent.add(b); true }
        box.restore(listOf(bytes(1), bytes(2), bytes(3)))
        assertEquals(2, box.depth)
        assertEquals(1, box.dropped)
        box.flush()
        assertEquals(2, sent[0][0].toInt())
        assertEquals(3, sent[1][0].toInt())
    }
}
