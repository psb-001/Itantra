package com.bjp.itantra.transport

/**
 * The wire format shared by both transports.
 *
 * FRAMING (unchanged since the first demo): every packet is a 4-byte big-endian length followed
 * by that many bytes. RFCOMM and TCP are byte streams with no message boundaries, so without a
 * length prefix two messages sent close together arrive glued into one.
 *
 * WHAT CHANGED (15 Oct 2026): the body now starts with a 1-byte TYPE. Two packets arriving glued
 * together was never the problem; the problem was telling a *message* from a *keepalive*. A type
 * byte is the smallest honest way to do that, and it keeps the app-level payload untouched —
 * `onMessage` receives exactly the bytes the sender passed to `send()`, with the type stripped.
 *
 * Both phones run the same build, so both speak this format. The transports are never mixed
 * (Bluetooth talks Bluetooth, Wi-Fi talks Wi-Fi), but they deliberately share one format so
 * there is exactly one thing to keep correct.
 */
internal object Framing {

    /** An app-level packet — what `Transport.send()` carries. */
    const val DATA: Int = 0x01

    /** "Are you still there?" Sent by the heartbeat when the link has been quiet. */
    const val PING: Int = 0x02

    /** "Yes." Sent by the reader, immediately, whenever a PING is read. */
    const val PONG: Int = 0x03

    /**
     * Largest accepted frame body, type byte included. Same 64 KiB budget as before, so a
     * malformed length can never make either side allocate something absurd.
     */
    const val MAX_FRAME: Int = 64 * 1024

    /** Write one frame. [payload] must fit the [MAX_FRAME] budget with its type byte. */
    fun write(out: java.io.DataOutputStream, type: Int, payload: ByteArray) {
        require(payload.size + 1 <= MAX_FRAME) { "frame too large: ${payload.size + 1}" }
        out.writeInt(payload.size + 1)
        out.writeByte(type)
        out.write(payload)
        out.flush()
    }
}
