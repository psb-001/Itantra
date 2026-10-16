package com.bjp.itantra

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [DeliveryTracker]. Pure JVM with a fake clock.
 *
 * The failure these pin down: a message whose write "succeeded" into a dying socket was lost
 * with no trace, and the sender's screen said "Sent". The tracker's job is to keep asking —
 * on a schedule — until the other phone confirms, and to give up loudly rather than silently.
 */
class DeliveryTrackerTest {

    private var nowMs = 0L
    private val bytes = byteArrayOf(1, 2, 3)

    private fun tracker(maxAttempts: Int = DeliveryTracker.MAX_ATTEMPTS) =
        DeliveryTracker(clock = { nowMs }, maxAttempts = maxAttempts)

    private fun advance(ms: Long) {
        nowMs += ms
    }

    @Test
    fun `nothing is due before the first retry interval`() {
        val d = tracker()
        d.track("a", bytes, Long.MAX_VALUE)
        advance(DeliveryTracker.FIRST_RETRY_MS - 1)
        assertTrue(d.collectDue().outgoing.isEmpty())
        advance(1)
        val due = d.collectDue()
        assertEquals(1, due.outgoing.size)
        assertEquals("a", due.outgoing[0].id)
        assertEquals(1, due.outgoing[0].attempt)
        assertTrue(due.outgoing[0].bytes.contentEquals(bytes))
    }

    @Test
    fun `resends back off 3 s, 6 s, 12 s, then 15 s`() {
        val d = tracker()
        d.track("a", bytes, Long.MAX_VALUE)
        advance(3_000)
        assertEquals(1, d.collectDue().outgoing[0].attempt)
        advance(5_999)
        assertTrue("must not be due early", d.collectDue().outgoing.isEmpty())
        advance(1) // t = 9 000: 6 s after the first resend
        assertEquals(2, d.collectDue().outgoing[0].attempt)
        advance(12_000) // t = 21 000: 12 s after the second
        assertEquals(3, d.collectDue().outgoing[0].attempt)
        advance(14_999)
        assertTrue("must not be due early", d.collectDue().outgoing.isEmpty())
        advance(1) // t = 36 000: 15 s after the third — the cap
        assertEquals(4, d.collectDue().outgoing[0].attempt)
    }

    @Test
    fun `a receipt stops the retries and reports how many resends it took`() {
        val d = tracker()
        d.track("a", bytes, Long.MAX_VALUE)
        advance(3_000)
        d.collectDue() // one resend happened
        assertEquals(1, d.ack("a"))
        assertEquals(0, d.waiting)
        assertEquals(1, d.delivered)
        advance(60_000)
        assertTrue(d.collectDue().outgoing.isEmpty())
        assertEquals(-1, d.ack("a")) // not tracked any more
    }

    @Test
    fun `a first-try receipt reports zero resends`() {
        val d = tracker()
        d.track("a", bytes, Long.MAX_VALUE)
        assertEquals(0, d.ack("a"))
        assertEquals(1, d.delivered)
    }

    @Test
    fun `a link-up holds retries back one interval`() {
        val d = tracker()
        d.track("a", bytes, Long.MAX_VALUE) // first retry would be due at t = 3 000
        advance(1_000)
        d.onTransportUp() // link came up at t = 1 000 — the outbox flush just sent it
        advance(2_999) // t = 3 999
        assertTrue("flush must not be raced by a resend", d.collectDue().outgoing.isEmpty())
        advance(1) // t = 4 000 — the grace period is over
        assertEquals(1, d.collectDue().outgoing.size)
    }

    @Test
    fun `a message past its deadline is abandoned, not resent`() {
        val d = tracker()
        d.track("a", bytes, 5_000) // a receipt after t = 5 000 is no longer useful
        advance(5_000)
        val due = d.collectDue()
        assertTrue(due.outgoing.isEmpty())
        assertEquals(listOf("a"), due.gaveUp)
        assertEquals(0, d.waiting)
        assertEquals(1, d.gaveUp)
    }

    @Test
    fun `a message is abandoned after max attempts`() {
        val d = tracker(maxAttempts = 2)
        d.track("a", bytes, Long.MAX_VALUE)
        advance(3_000)
        assertEquals(1, d.collectDue().outgoing.size)
        advance(6_000)
        assertEquals(1, d.collectDue().outgoing.size) // attempt 2
        advance(12_000)
        val due = d.collectDue()
        assertTrue(due.outgoing.isEmpty())
        assertEquals(listOf("a"), due.gaveUp)
        assertEquals(1, d.gaveUp)
    }

    @Test
    fun `tracking beyond capacity evicts the oldest`() {
        val d = DeliveryTracker(clock = { nowMs }, capacity = 2)
        d.track("a", bytes, Long.MAX_VALUE)
        d.track("b", bytes, Long.MAX_VALUE)
        d.track("c", bytes, Long.MAX_VALUE)
        assertEquals(2, d.waiting)
        advance(3_000)
        assertEquals(listOf("b", "c"), d.collectDue().outgoing.map { it.id })
    }

    @Test
    fun `snapshot and restore carry the schedule, not a fresh one`() {
        val d = tracker()
        d.track("a", bytes, 10_000)
        advance(3_000)
        d.collectDue() // one resend happened: attempts 1, delay doubled to 6 s

        val d2 = DeliveryTracker(clock = { nowMs })
        d2.restore(d.snapshot())
        assertEquals(1, d2.waiting)

        // After a restart the first resend waits one interval — the outbox flush that runs on
        // launch gets its chance to be the thing that arrives first.
        assertTrue(d2.collectDue().outgoing.isEmpty())
        advance(3_000) // t = 6 000
        val due = d2.collectDue()
        assertEquals(1, due.outgoing.size)
        assertEquals(2, due.outgoing[0].attempt) // the attempt count carried over
        assertTrue(due.outgoing[0].bytes.contentEquals(bytes))
    }

    @Test
    fun `a restored message whose time ran out is abandoned, not re-sent`() {
        val d = tracker()
        d.track("a", bytes, 5_000)
        val saved = d.snapshot()
        advance(9_000) // the app was dead while the TTL ran out
        val d2 = DeliveryTracker(clock = { nowMs })
        d2.restore(saved)
        advance(3_000) // its next attempt comes due at t = 12 000
        val due = d2.collectDue()
        assertTrue(due.outgoing.isEmpty())
        assertEquals(listOf("a"), due.gaveUp)
        assertEquals(1, d2.gaveUp)
    }

    @Test
    fun `restoreCounters brings back the Settings totals`() {
        val d = tracker()
        d.restoreCounters(delivered = 5, gaveUp = 2)
        assertEquals(5, d.delivered)
        assertEquals(2, d.gaveUp)
    }
}
