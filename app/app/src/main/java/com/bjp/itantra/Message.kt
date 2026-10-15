package com.bjp.itantra

import org.json.JSONObject
import java.util.UUID

/**
 * The packet that crosses the link. This is the entire point of the project: send text, not audio.
 *
 * Measured payload: a spoken sentence becomes 49–111 bytes of UTF-8 (see end_to_end.json).
 * With this envelope it lands around 150–250 bytes. The equivalent audio is roughly 1,000x larger.
 *
 * The envelope is JSON for debuggability, not minimum size. A compact binary encoding would be
 * smaller, but on a link carrying kilobits per second a 200-byte packet is not the bottleneck,
 * and being able to read the wire during a live demo is worth more.
 */
data class Message(
    /** Stable identity, used to suppress duplicate playback on retransmission. */
    val id: String = UUID.randomUUID().toString(),

    /** BCP-47-ish language tag matching the ASR model's IDs, e.g. "hin_Deva", "eng_Latn". */
    val lang: String,

    /**
     * ALERT plays at maximum volume, loops, and cannot be interrupted. MSG does not. ACK is a
     * one-line delivery receipt: it carries [ackFor] and nothing worth speaking.
     */
    val type: Type,

    /** Monotonic per-sender sequence number, for ordering. */
    val seq: Int,

    /** Creation time, Unix seconds. */
    val ts: Long = System.currentTimeMillis() / 1000,

    /** Seconds after which this must not be announced as a fresh alert. */
    val ttl: Int = 300,

    /** The recognised sentence. UTF-8 on the wire. */
    val text: String,

    /**
     * For [Type.ACK] packets: the [id] of the message whose arrival is being confirmed. Null on
     * everything else. A receipt answers "did the words arrive", nothing more.
     */
    val ackFor: String? = null,
) {
    enum class Type { ALERT, MSG, ACK }

    /** True when this should no longer be played as a live alert. */
    fun isExpired(now: Long = System.currentTimeMillis() / 1000): Boolean =
        ttl > 0 && (now - ts) > ttl

    fun toJson(): String = JSONObject().apply {
        put("id", id)
        put("lang", lang)
        put("type", type.name)
        put("seq", seq)
        put("ts", ts)
        put("ttl", ttl)
        put("text", text)
        // Only present on receipts, so every other packet stays exactly as small as before.
        ackFor?.let { put("ackFor", it) }
    }.toString()

    /** UTF-8 bytes ready for the transport. */
    fun toBytes(): ByteArray = toJson().toByteArray(Charsets.UTF_8)

    companion object {
        fun fromBytes(bytes: ByteArray): Message? = try {
            fromJson(String(bytes, Charsets.UTF_8))
        } catch (e: Exception) {
            null
        }

        fun fromJson(raw: String): Message {
            val o = JSONObject(raw)
            return Message(
                id = o.optString("id", UUID.randomUUID().toString()),
                lang = o.optString("lang", "hin_Deva"),
                type = runCatching { Type.valueOf(o.optString("type", "MSG")) }
                    .getOrDefault(Type.MSG),
                seq = o.optInt("seq", 0),
                ts = o.optLong("ts", System.currentTimeMillis() / 1000),
                ttl = o.optInt("ttl", 300),
                text = o.optString("text", ""),
                ackFor = o.optString("ackFor", "").ifEmpty { null },
            )
        }
    }
}

/**
 * Delivery state. These must stay distinct.
 *
 * "Received" is NOT the same as "a human heard it". A packet checksum proves the bytes arrived;
 * it proves nothing about comprehension. Collapsing these into one tick mark is the kind of
 * shortcut that reads as dishonest under questioning — keeping them separate reads as engineering
 * maturity, and it is also just true.
 */
enum class DeliveryState {
    RECORDED,        // mic captured a segment
    RECOGNISED,      // ASR produced text and the language filter accepted it
    QUEUED,          // waiting for the link
    TRANSMITTED,     // handed to the transport
    RECEIVED,        // arrived on the other phone
    PLAYED,          // TTS finished speaking it
    ACKNOWLEDGED,    // a human pressed something
}

/**
 * Suppresses duplicate playback and expired alerts.
 *
 * Without this, a retransmitted packet replays an emergency alert at full volume, and a
 * day-old queued message gets announced as if it just happened. Both are real failure modes
 * for this app, not theoretical ones.
 */
class MessageLedger(private val maxRemembered: Int = 200) {

    private val seen = LinkedHashSet<String>()
    private var lastSeq = -1

    /** @return true if this message should be processed (not a duplicate). */
    fun accept(msg: Message): Boolean {
        if (!seen.add(msg.id)) return false
        if (seen.size > maxRemembered) {
            val it = seen.iterator()
            if (it.hasNext()) {
                it.next()
                it.remove()
            }
        }
        return true
    }

    /**
     * True when this message is recent enough to be announced as a live alert.
     *
     * STALENESS IS ABOUT AGE, NOT SEQUENCE NUMBER. An earlier version also required
     * `msg.seq > lastSeq`, which looked sensible and was wrong: the sender's counter resets when
     * its app restarts, so after phone A restarts at seq 0, phone B would silently drop every
     * subsequent message as "stale" and the user would see nothing. Sequence numbers are useful
     * for ordering and diagnostics; they must never gate delivery.
     *
     * Duplicate suppression is handled separately by [accept], which uses the message UUID.
     */
    fun isFresh(msg: Message): Boolean = !msg.isExpired()

    /** Record what we last announced, for diagnostics only — never used to reject. */
    fun markPlayed(msg: Message) {
        if (msg.seq > lastSeq) lastSeq = msg.seq
    }

    fun reset() {
        seen.clear()
        lastSeq = -1
    }
}
