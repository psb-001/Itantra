package com.bjp.itantra

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * The durable copy of everything that must outlive the process: packets buffered while the link
 * was down, messages still waiting for a receipt, and the ids of messages already heard.
 *
 * WHY THIS EXISTS. Every other drop path is closed — a dead link buffers ([Outbox]), a dying
 * socket is retried until a receipt returns ([DeliveryTracker]), a duplicate never replays
 * ([MessageLedger]) — but all three lived only in RAM. Android killing the app in the background
 * (low RAM, a crash, the user swiping it away) took the sender's "Sent" and "Buffered" rows with
 * it and nobody ever heard those sentences. This was the last silent-drop path.
 *
 * The fix is deliberately small: one JSON file, written on every change, put back at startup.
 * Restored entries keep their original attempt counts and deadlines, so nothing gets a fresh TTL
 * by accident — a message whose time ran out while the app was dead is abandoned loudly on the
 * first tick, exactly as if the app had stayed alive.
 *
 * FORMAT NOTES. Packets are hex-encoded (they are ~250 bytes; hex doubles that and needs no
 * codec older Androids lack). The file is written to a temp name and renamed into place, so a
 * kill mid-write cannot leave a torn file — the worst case is the previous snapshot. Version
 * mismatches load as "nothing stored" instead of crashing.
 *
 * PURE JVM on purpose: it takes a plain [File]. Tests exercise it against a temp directory.
 */
class PendingStore(private val file: File) {

    /** One unconfirmed message, with its deadline on the wall clock (survives reboots). */
    class Tracked(
        val id: String,
        val bytes: ByteArray,
        val wallDeadlineMs: Long,
        val attempts: Int,
        val delayMs: Long,
    )

    class Snapshot(
        val outbox: List<ByteArray>,
        val tracked: List<Tracked>,
        val seen: List<String>,
        val delivered: Int,
        val gaveUp: Int,
    )

    /**
     * Write [s] atomically (temp file + rename). Throws on I/O failure; callers guard.
     *
     * Synchronized because saves arrive from different threads — the ASR worker after a send,
     * the link reader after a receipt or an incoming message, the main-thread resend ticker.
     * Two concurrent saves would share one temp file name and could rename a torn file over the
     * good one, defeating the whole point of temp+rename.
     */
    @Synchronized
    fun save(s: Snapshot) {
        val json = JSONObject().apply {
            put("v", VERSION)
            put("outbox", JSONArray(s.outbox.map { hex(it) }))
            put(
                "tracked",
                JSONArray(
                    s.tracked.map { t ->
                        JSONObject().apply {
                            put("id", t.id)
                            put("bytes", hex(t.bytes))
                            put("deadline", t.wallDeadlineMs)
                            put("attempts", t.attempts)
                            put("delay", t.delayMs)
                        }
                    }
                )
            )
            put("seen", JSONArray(s.seen))
            put("delivered", s.delivered)
            put("gaveUp", s.gaveUp)
        }.toString()

        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(json)
        if (!tmp.renameTo(file)) {
            // Some filesystems refuse to rename onto an existing file; overwrite directly
            // (a torn write here is still bounded to this one file).
            file.writeText(json)
            tmp.delete()
        }
    }

    /** null when nothing is stored or the file cannot be understood — never throws. */
    fun load(): Snapshot? = try {
        if (!file.exists()) null else parse(file.readText())
    } catch (e: Exception) {
        null
    }

    private fun parse(raw: String): Snapshot? {
        val o = JSONObject(raw)
        if (o.optInt("v", 0) != VERSION) return null
        val outbox = o.optJSONArray("outbox")?.let { a ->
            (0 until a.length()).mapNotNull { i -> if (a.isNull(i)) null else unhex(a.getString(i)) }
        } ?: emptyList()
        val tracked = o.optJSONArray("tracked")?.let { a ->
            (0 until a.length()).mapNotNull { i ->
                val t = a.optJSONObject(i) ?: return@mapNotNull null
                val id = t.optString("id", "")
                val bytes = t.optString("bytes", "")
                if (id.isEmpty() || bytes.isEmpty()) null
                else Tracked(
                    id = id,
                    bytes = unhex(bytes),
                    wallDeadlineMs = t.optLong("deadline", 0L),
                    attempts = t.optInt("attempts", 0),
                    delayMs = t.optLong("delay", DeliveryTracker.FIRST_RETRY_MS),
                )
            }
        } ?: emptyList()
        val seen = o.optJSONArray("seen")?.let { a ->
            (0 until a.length()).mapNotNull { i -> if (a.isNull(i)) null else a.getString(i) }
        } ?: emptyList()
        return Snapshot(
            outbox = outbox,
            tracked = tracked,
            seen = seen,
            delivered = o.optInt("delivered", 0),
            gaveUp = o.optInt("gaveUp", 0),
        )
    }

    private fun hex(b: ByteArray): String {
        val digits = "0123456789abcdef"
        val sb = StringBuilder(b.size * 2)
        for (x in b) {
            val v = x.toInt() and 0xff
            sb.append(digits[v ushr 4]).append(digits[v and 0x0f])
        }
        return sb.toString()
    }

    private fun unhex(s: String): ByteArray {
        val out = ByteArray(s.length / 2)
        for (i in out.indices) {
            out[i] = ((Character.digit(s[i * 2], 16) shl 4) or Character.digit(s[i * 2 + 1], 16)).toByte()
        }
        return out
    }

    companion object {
        private const val VERSION = 1
    }
}
