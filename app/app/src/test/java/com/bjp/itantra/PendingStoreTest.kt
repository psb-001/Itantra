package com.bjp.itantra

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.UUID
import kotlin.concurrent.thread

/**
 * Tests for [PendingStore] — the file that keeps the sender's promises alive across a process
 * kill. The failure it closes: Android kills the app in the background (low RAM, a swipe, a
 * crash), and the outbox, the receipt tracker and the heard-ids ledger die with it — sentences
 * the sender was told were "Sent" or "Buffered" simply ceased to exist.
 *
 * Byte-for-byte fidelity matters here: the packets are the messages. Hex encoding, the atomic
 * temp-file+rename write, and the "cannot understand it -> nothing stored" rule all get pinned.
 */
class PendingStoreTest {

    private fun tempFile(): File {
        val dir = File(
            System.getProperty("java.io.tmpdir"),
            "itantra-pending-test-${UUID.randomUUID()}",
        )
        dir.mkdirs()
        dir.deleteOnExit()
        return File(dir, "pending.json").also { it.deleteOnExit() }
    }

    @Test
    fun `a snapshot survives save and load byte for byte`() {
        val store = PendingStore(tempFile())
        // Deliberately awkward bytes: null, DEL, high bits — the wire is binary.
        val packet = byteArrayOf(0x00, 0x7f, 0x80.toByte(), 0xff.toByte())
        store.save(
            PendingStore.Snapshot(
                outbox = listOf(packet, byteArrayOf(1)),
                tracked = listOf(PendingStore.Tracked("id-1", packet, 1_700_000_000_000L, 3, 12_000L)),
                seen = listOf("a", "b"),
                delivered = 5,
                gaveUp = 1,
            )
        )

        val back = store.load()!!
        assertEquals(2, back.outbox.size)
        assertTrue(back.outbox[0].contentEquals(packet))
        assertTrue(back.outbox[1].contentEquals(byteArrayOf(1)))
        assertEquals(1, back.tracked.size)
        assertEquals("id-1", back.tracked[0].id)
        assertTrue(back.tracked[0].bytes.contentEquals(packet))
        assertEquals(1_700_000_000_000L, back.tracked[0].wallDeadlineMs)
        assertEquals(3, back.tracked[0].attempts)
        assertEquals(12_000L, back.tracked[0].delayMs)
        assertEquals(listOf("a", "b"), back.seen)
        assertEquals(5, back.delivered)
        assertEquals(1, back.gaveUp)
    }

    @Test
    fun `a missing file loads as nothing, not an error`() {
        assertNull(PendingStore(tempFile()).load())
    }

    @Test
    fun `a torn or unknown file loads as nothing`() {
        val f = tempFile()
        f.writeText("{\"v\":1,\"outbox\":[") // killed mid-write of an old version of the file
        assertNull(PendingStore(f).load())
        f.writeText("{\"v\":99,\"outbox\":[]}") // written by a future version
        assertNull(PendingStore(f).load())
    }

    @Test
    fun `a second save replaces the first and leaves no temp file`() {
        val f = tempFile()
        val store = PendingStore(f)
        store.save(PendingStore.Snapshot(listOf(byteArrayOf(1)), emptyList(), emptyList(), 0, 0))
        store.save(PendingStore.Snapshot(emptyList(), emptyList(), listOf("x"), 2, 0))

        val back = store.load()!!
        assertTrue(back.outbox.isEmpty())
        assertEquals(listOf("x"), back.seen)
        assertEquals(2, back.delivered)
        assertTrue("temp file must be renamed away", !File(f.parentFile, f.name + ".tmp").exists())
    }

    @Test
    fun `concurrent saves from two threads never leave an unreadable file`() {
        val store = PendingStore(tempFile())
        val a = PendingStore.Snapshot(listOf(byteArrayOf(1)), emptyList(), emptyList(), 1, 0)
        val b = PendingStore.Snapshot(emptyList(), emptyList(), listOf("x"), 2, 0)
        // The live app saves from the ASR worker, the link reader and the main-thread ticker;
        // unsynchronized saves would share one temp-file name and could rename a torn file.
        val threads = listOf(thread { repeat(50) { store.save(a) } }, thread { repeat(50) { store.save(b) } })
        threads.forEach { it.join() }

        val back = store.load()
        assertTrue("a save must never leave a file the loader cannot read", back != null)
        assertTrue(back!!.delivered == 1 || back.delivered == 2)
    }
}
