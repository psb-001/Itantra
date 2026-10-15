package com.bjp.itantra.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [Heartbeat]. Pure JVM with a fake clock.
 *
 * The failure these pin down: before this class a silently dead Bluetooth link stayed
 * `isConnected == true` forever, and messages written into it were reported as sent. The tests
 * say when a ping must go out, when the link must be declared dead, and — just as important —
 * when it must NOT be.
 */
class HeartbeatTest {

    private var nowMs = 0L
    private var pings = 0
    private val deadReasons = mutableListOf<String>()

    private fun heartbeat() = Heartbeat(
        clock = { nowMs },
        sendPing = { pings++ },
        onDead = { deadReasons.add(it) },
    )

    private fun advance(ms: Long) {
        nowMs += ms
    }

    @Test
    fun `first tick after attach pings immediately`() {
        val hb = heartbeat()
        hb.reset()
        advance(500)
        hb.tick()
        assertEquals(1, pings)
    }

    @Test
    fun `a busy link does not ping twice within one interval`() {
        val hb = heartbeat()
        hb.reset()
        advance(500)
        hb.tick()          // first ping
        // the peer answered, and keeps talking
        hb.noteAlive()
        advance(2_000)
        hb.noteAlive()
        hb.tick()
        advance(1_500)
        hb.tick()
        assertEquals("mid-conversation ticks must not ping", 1, pings)

        // now go quiet: after an interval of silence the next ping is due
        advance(3_000)
        hb.tick()
        assertEquals(2, pings)
    }

    @Test
    fun `silence past the timeout declares the link dead exactly once`() {
        val hb = heartbeat()
        hb.reset()
        // The peer never responds; ticks every second like the transport does.
        repeat(20) {
            advance(1_000)
            hb.tick()
        }
        assertEquals(1, deadReasons.size)
        assertTrue(deadReasons[0], deadReasons[0].contains("no frame"))
        // Further ticks must not fire again.
        advance(5_000)
        hb.tick()
        assertEquals(1, deadReasons.size)
    }

    @Test
    fun `a pong keeps the link alive past the timeout`() {
        val hb = heartbeat()
        hb.reset()
        // The peer answers each ping 1 s later, and the pings themselves go out at the 10 s mark —
        // before the 12 s timeout would fire. Three rounds of this: the link must never be
        // declared dead, and exactly one ping goes out per round.
        repeat(3) {
            advance(10_000)
            hb.tick()
            advance(1_000)
            hb.notePong()
        }
        assertTrue("link was wrongly declared dead: $deadReasons", deadReasons.isEmpty())
        assertEquals(3, pings)
    }

    @Test
    fun `notePong returns the round trip and zero when no ping was outstanding`() {
        val hb = heartbeat()
        hb.reset()
        advance(500)
        hb.tick()                 // ping goes out at t=500
        advance(85)
        assertEquals(85L, hb.notePong())
        // Second pong with nothing outstanding.
        advance(10)
        assertEquals(0L, hb.notePong())
    }

    @Test
    fun `reset after a new attach restarts the clocks`() {
        val hb = heartbeat()
        hb.reset()
        // Long silence that would kill the link…
        advance(11_000)
        hb.tick()
        assertTrue(deadReasons.isEmpty())
        // …but a new socket is attached before the timeout, and the clock starts over.
        hb.reset()
        advance(11_000)
        hb.tick()
        assertTrue(deadReasons.isEmpty())
        advance(2_000)
        hb.tick()
        assertEquals(1, deadReasons.size)
    }
}
