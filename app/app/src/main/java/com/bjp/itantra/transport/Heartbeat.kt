package com.bjp.itantra.transport

/**
 * Dead-link detection for a framed stream, shared by both transports.
 *
 * WHY THIS EXISTS. A Bluetooth socket dying silently — the peer walks out of range, the phone
 * sleeps, the stack reaps the connection — is indistinguishable from an idle one: `read()` simply
 * keeps blocking, `isConnected` stays true, and the app cheerfully reports "Sent" into a link
 * that no longer reaches anyone. Before this, the app found out only when someone spoke again and
 * the *next* write failed, which is precisely the case where the message is already lost.
 *
 * HOW. Each side pings when the link has been quiet, and any inbound frame — data, ping or pong —
 * is proof the peer is alive. After [timeoutMs] with no frame at all, the transport is told the
 * link is dead; it closes the socket, which unblocks the reader, and the normal reconnect takes
 * over. A live link costs one 1-byte-typed ping every few seconds; a dead one is detected in ~12 s
 * instead of "whenever the user next speaks".
 *
 * This class is pure policy: no thread, no clock of its own. The transport owns a small loop that
 * calls [tick] about once a second while connected, and passes `clock`. Tests pass a fake clock —
 * see HeartbeatTest.
 *
 * @param intervalMs how long the link must be quiet before a ping goes out. Deliberately a third
 *                   of [timeoutMs], so a working link misses two replies before it is declared
 *                   dead — transient radio hiccups must not kill a session.
 * @param timeoutMs  no frame from the peer for this long means dead, not idle.
 * @param clock      monotonic milliseconds (transports pass `SystemClock.elapsedRealtime`).
 * @param sendPing   writes one PING frame; called at most once per [intervalMs].
 * @param onDead     called at most once per link, with the reason, when the timeout is exceeded.
 */
class Heartbeat(
    private val intervalMs: Long = PING_INTERVAL_MS,
    private val timeoutMs: Long = DEAD_AFTER_MS,
    private val clock: () -> Long,
    private val sendPing: () -> Unit,
    private val onDead: (String) -> Unit,
) {

    companion object {
        /** Idle time before a ping goes out. 4 s keeps the app frugal and the detection quick. */
        const val PING_INTERVAL_MS = 4_000L

        /** No frame from the peer for this long = the link is dead, not idle. Three missed pings. */
        const val DEAD_AFTER_MS = 12_000L
    }

    @Volatile private var lastRxMs = clock()
    @Volatile private var lastPingMs = 0L
    @Volatile private var pingOutstandingAt = 0L
    @Volatile private var deadFired = false

    /** Any inbound frame — data, ping or pong — is proof the peer is there. */
    fun noteAlive() {
        lastRxMs = clock()
    }

    /**
     * A pong came back. Returns the round-trip in milliseconds, or 0 when no ping was
     * outstanding (the pong raced a data frame, for example).
     */
    fun notePong(): Long {
        val now = clock()
        lastRxMs = now
        val at = pingOutstandingAt
        pingOutstandingAt = 0L
        return if (at == 0L) 0L else now - at
    }

    /** A fresh socket was attached: the clocks start now, and death may be declared once again. */
    fun reset() {
        lastRxMs = clock()
        lastPingMs = 0L
        pingOutstandingAt = 0L
        deadFired = false
    }

    /**
     * Decide whether to ping or to give up on the link. Call about once a second while connected;
     * harmless to call more often.
     */
    fun tick() {
        if (deadFired) return
        val now = clock()
        val silentFor = now - lastRxMs
        if (silentFor > timeoutMs) {
            deadFired = true
            onDead("no frame from the peer for ${silentFor / 1000} s — the link is dead")
            return
        }
        // Ping only when the link has been quiet, and never twice within one interval. The first
        // tick after attach always pings: that verifies the fresh link within a second.
        val quietFor = now - lastRxMs
        if (lastPingMs == 0L || (quietFor >= intervalMs && now - lastPingMs >= intervalMs)) {
            lastPingMs = now
            pingOutstandingAt = now
            sendPing()
        }
    }
}
