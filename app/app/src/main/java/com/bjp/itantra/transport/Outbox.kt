package com.bjp.itantra.transport

/**
 * A small FIFO for packets that could not go out yet.
 *
 * WHY THIS EXISTS. `send()` on a dead link used to log a warning and silently drop the packet.
 * On a walkie-talkie that is the worst possible failure: the speaker's screen says "Sent" and the
 * other phone never hears a word. The [Transport.send] contract now returns false instead, and
 * this is where the packet waits.
 *
 * The outbox is a pass-through while the link is up: if the sink accepts the packet it was sent
 * and nothing is stored. Only when the link is down does it buffer, in order, and the transport's
 * CONNECTED callback calls [flush] to drain it.
 *
 * ORDER IS THE POINT. The receiving phone speaks messages in arrival order, and a reordered
 * emergency message is a wrong message. A packet is therefore never allowed to overtake one that
 * is already buffered, even if the socket has just come back up.
 *
 * RETRANSMISSION IS SAFE BY CONSTRUCTION. Every [com.bjp.itantra.Message] carries a UUID and the
 * receiving side suppresses duplicates (`MessageLedger.accept`), so flushing after a reconnect
 * cannot make the other phone speak the same sentence twice.
 *
 * @param capacity maximum packets held while the link is down. Past this the OLDEST is dropped:
 *                 the newest information is the useful one, and the receiver already refuses to
 *                 *announce* messages older than their TTL, so a 5-minute-old sentence is stale
 *                 by design anyway.
 * @param sink     sends one packet; returns true when it was handed to a connected socket.
 */
class Outbox(
    private val capacity: Int = 64,
    private val sink: (ByteArray) -> Boolean,
) {
    private val lock = Any()
    private val pending = ArrayDeque<ByteArray>()

    private var sentImmediately = 0
    private var droppedOldest = 0

    /** Packets currently waiting for the link. */
    val depth: Int get() = synchronized(lock) { pending.size }

    /** Packets dropped because the buffer was full — surfaced in the log, never silent. */
    val dropped: Int get() = synchronized(lock) { droppedOldest }

    /**
     * Try to send [bytes] now; buffer it when the link is down.
     *
     * @return true when the packet went out immediately. False means it is in the buffer (or was
     *         dropped whole because the buffer is full and [dropped] counts those).
     */
    fun enqueue(bytes: ByteArray): Boolean = synchronized(lock) {
        // The order guard matters as much as the send itself: if older packets are still waiting,
        // this one must go BEHIND them even though the socket may already be up, or the receiver
        // hears the sentences out of order. The flush on CONNECTED drains the backlog first.
        if (pending.isEmpty() && sink(bytes)) {
            sentImmediately++
            return true
        }
        if (pending.size >= capacity) {
            pending.removeFirst()
            droppedOldest++
        }
        pending.addLast(bytes)
        false
    }

    /**
     * Send everything buffered, oldest first, stopping at the first failure.
     *
     * Called when the transport reports CONNECTED. Stopping at the first failure — rather than
     * skipping ahead — keeps the remainder ordered for the next flush.
     *
     * @return how many packets went out.
     */
    fun flush(): Int = synchronized(lock) {
        var n = 0
        while (pending.isNotEmpty()) {
            if (!sink(pending.first())) break
            pending.removeFirst()
            n++
        }
        n
    }

    override fun toString(): String =
        "Outbox(depth=$depth, dropped=$dropped, sentImmediately=$sentImmediately)"
}
