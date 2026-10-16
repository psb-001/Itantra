package com.bjp.itantra

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [MessageLedger]'s persistence path.
 *
 * The live rule is one line — a message id is spoken once — but the sender keeps resending until
 * a receipt returns, and the receipt can be lost. If the app was killed for a restart in
 * between, only the [PendingStore] copy of these ids stops a resend from replaying a message the
 * human already heard. These tests pin that copy, and its bound.
 */
class MessageLedgerTest {

    private fun msg() = Message(lang = "hin_Deva", type = Message.Type.MSG, seq = 1, text = "नमस्ते")

    @Test
    fun `an id heard before a restart is not replayed after it`() {
        val before = MessageLedger()
        val m = msg()
        assertTrue(before.accept(m))

        // A fresh ledger, as after a process restart, restored from the store.
        val after = MessageLedger()
        after.restore(before.snapshot())
        assertFalse(after.accept(m))
    }

    @Test
    fun `restore is bounded — the newest ids are the ones remembered`() {
        val ledger = MessageLedger(maxRemembered = 2)
        val a = msg()
        val b = msg()
        val c = msg()
        ledger.restore(listOf(a.id, b.id, c.id)) // oldest first, like a snapshot

        assertFalse(ledger.accept(c))
        assertFalse(ledger.accept(b))
        assertTrue(ledger.accept(a)) // pushed out of the window — honestly forgotten
    }
}
