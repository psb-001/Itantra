package com.bjp.itantra

/**
 * Who has confirmed receipt of what, and when to ask again.
 *
 * WHY THIS EXISTS. The transports can tell a dead link from an idle one, and the [Outbox]
 * guarantees a packet survives a link that is *known* to be down. Neither can prove that a
 * packet written into a socket that was already dying ever arrived — a write can "succeed" into
 * the RFCOMM buffer while the radio is gone. The receiving phone answers every message with a
 * one-line receipt ([Message.Type.ACK]); this class remembers what has not been confirmed yet
 * and schedules bounded resends. Only when a receipt comes back does the sender know the words
 * travelled. The receiving phone suppresses duplicate UUIDs, so a resend is spoken at most once.
 *
 * RETRY SCHEDULE: first resend after 3 s, then 6 s, 12 s, and every 15 s after that, up to
 * [MAX_ATTEMPTS] times — and never past the message's own TTL ([deadlineMs]). A message that
 * runs out of either is abandoned *loudly* by the caller; nothing is dropped silently.
 *
 * Pure JVM: no threads, no Android clock. The caller owns the ticker and passes `clock`; tests
 * pass a fake clock — see DeliveryTrackerTest.
 */
class DeliveryTracker(
    private val clock: () -> Long,
    private val maxAttempts: Int = MAX_ATTEMPTS,
    private val firstRetryMs: Long = FIRST_RETRY_MS,
    private val maxRetryMs: Long = MAX_RETRY_MS,
    private val capacity: Int = 32,
) {
    companion object {
        /** Wait this long before the first resend: a receipt on a live link takes milliseconds. */
        const val FIRST_RETRY_MS = 3_000L

        /** Never wait longer than this between resends. */
        const val MAX_RETRY_MS = 15_000L

        /** Give up after this many resends (still bounded by the message's TTL). */
        const val MAX_ATTEMPTS = 10
    }

    /** One message waiting for its receipt. */
    private class Entry(
        val id: String,
        val bytes: ByteArray,
        val deadlineMs: Long,
        var attempts: Int,
        var nextAttemptMs: Long,
        var delayMs: Long,
    )

    /** A resend the caller should perform now. */
    class Outgoing(val id: String, val bytes: ByteArray, val attempt: Int)

    /** What [collectDue] found this tick: resends to perform and messages to abandon. */
    class Due(val outgoing: List<Outgoing>, val gaveUp: List<String>)

    private val lock = Any()
    private val pending = LinkedHashMap<String, Entry>()

    private var quietUntilMs = 0L
    private var deliveredCount = 0
    private var gaveUpCount = 0

    /** Messages still waiting for a receipt (shown in Settings). */
    val waiting: Int get() = synchronized(lock) { pending.size }

    /** Messages the far end has confirmed. */
    val delivered: Int get() = synchronized(lock) { deliveredCount }

    /** Messages abandoned because no receipt ever came. */
    val gaveUp: Int get() = synchronized(lock) { gaveUpCount }

    /**
     * Remember [bytes] under [id] until the far end confirms it.
     *
     * @param deadlineMs monotonic time after which a receipt is no longer worth chasing (the
     *                   message's TTL, converted to the same clock); [Long.MAX_VALUE] for none.
     */
    fun track(id: String, bytes: ByteArray, deadlineMs: Long) {
        val now = clock()
        synchronized(lock) {
            // Bounded on purpose: a tracker that grew without limit would be a leak, and real
            // usage is a handful of unconfirmed messages. The oldest goes first — the newest
            // information is the useful one.
            while (pending.size >= capacity) {
                val oldest = pending.keys.firstOrNull() ?: break
                pending.remove(oldest)
            }
            pending[id] = Entry(
                id = id,
                bytes = bytes,
                deadlineMs = deadlineMs,
                attempts = 0,
                nextAttemptMs = now + firstRetryMs,
                delayMs = firstRetryMs,
            )
        }
    }

    /**
     * The far end confirmed [id].
     *
     * @return how many resends this took, or −1 when the id was not being tracked (already
     *         confirmed, or the app restarted since it was sent).
     */
    fun ack(id: String): Int = synchronized(lock) {
        val e = pending.remove(id) ?: return -1
        deliveredCount++
        e.attempts
    }

    /**
     * The link just came up. Packets the outbox flushed this instant should not be resent before
     * their receipt has had a chance to arrive — hold every retry back one interval.
     */
    fun onTransportUp() {
        val now = clock()
        synchronized(lock) {
            quietUntilMs = now + firstRetryMs
            for (e in pending.values) e.nextAttemptMs = maxOf(e.nextAttemptMs, quietUntilMs)
        }
    }

    /**
     * Resends that are due right now, plus messages to abandon because their deadline passed or
     * they have had [maxAttempts] tries. Records each attempt before returning, so calling this
     * twice in a tick cannot double-count.
     */
    fun collectDue(): Due {
        val now = clock()
        return synchronized(lock) {
            if (now < quietUntilMs) return@synchronized Due(emptyList(), emptyList())
            val outgoing = ArrayList<Outgoing>()
            val gaveUp = ArrayList<String>()
            val it = pending.values.iterator()
            while (it.hasNext()) {
                val e = it.next()
                if (now < e.nextAttemptMs) continue
                if (now >= e.deadlineMs || e.attempts >= maxAttempts) {
                    it.remove()
                    gaveUpCount++
                    gaveUp.add(e.id)
                    continue
                }
                e.attempts++
                e.delayMs = minOf(e.delayMs * 2, maxRetryMs)
                e.nextAttemptMs = now + e.delayMs
                outgoing.add(Outgoing(e.id, e.bytes, e.attempts))
            }
            Due(outgoing, gaveUp)
        }
    }
}
