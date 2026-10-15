package com.bjp.itantra.laya

import java.io.BufferedReader
import java.io.InputStream
import java.util.Locale

/**
 * The pruned Laya tokenizer, ported from the exact pipeline in `tokenizer.json`.
 *
 * That pipeline is small, and it is not GPT-2 byte-level BPE even though that is what most
 * ports assume. Reading the file is the difference between a tokenizer that works and one
 * that silently produces garbage:
 *
 *  1. Normalizer `Replace " " -> U+2581` (Laya's word marker; `MARKER` below).
 *  2. Pre-tokenizer `Metaspace`: prepend MARKER unless the text already starts with it, then
 *     split on MARKER so a marker starts a piece and travels with it. A marker followed by
 *     another marker, or by the end of the text, is its own one-character piece — which is why
 *     `"a  b"` is `["▁a", "▁", "▁b"]` and not two pieces.
 *  3. BPE with `byte_fallback: true`: a character that is not in the vocab becomes its UTF-8
 *     byte tokens (`<0xE0>`, `<0x0A>`, uppercase, two digits) when every one of those byte
 *     tokens is in the vocab, otherwise a single `<unk>`.
 *
 * Merges are applied lowest rank first; when two pairs tie, the leftmost wins.
 *
 * ## Parity is a test, not a hope
 *
 * `eval/laya_mobile_assets.py` runs this same algorithm in Python against the real `tokenizers`
 * library on the 12 bench sentences, whitespace/script edge cases and a deterministic corpus
 * sample. It aborts unless every one matches, then writes the expected ids *and* pieces into
 * `app/src/test/resources/laya_tokenizer_cases.txt`. [LayaBpeTest] replays that file on the JVM
 * — no model, no device — so a drift in this port turns red immediately.
 *
 * ## Why two text files instead of tokenizer.json
 *
 * The app loads `vocab.b64` and `merges.b64`, staged from the pruned tokenizer by the script
 * above:
 *
 * ```
 * vocab.b64    one base64-encoded UTF-8 token per line; the line number is the id
 * merges.b64   one merge per line: base64(a) space base64(b); the line number is the rank
 * ```
 *
 * That keeps a JSON parser (and a 1.6 MB JSON parse heap churn) out of the receiving path on
 * a 2 GB phone. Base64 rather than raw lines because a token can be `"\n"` (it is in the vocab)
 * and any character is legal inside a token.
 */
class LayaBpe private constructor(
    private val vocab: HashMap<String, Int>,
    private val vocabR: Array<String>,
    /** key = (aId shl 32) or bId; value = (rank shl 32) or newId — packed to keep this small. */
    private val merges: HashMap<Long, Long>,
    private val unkId: Int,
) {
    /** Ids and the pieces that produced them; `tokens` exists for tests and diagnostics. */
    class Encoding(val ids: IntArray, val tokens: List<String>)

    val vocabSize: Int get() = vocabR.size
    val mergeCount: Int get() = merges.size

    /** Convenience: just the ids, which is all the decision head needs. */
    fun encode(text: String): IntArray = encodeDetailed(text).ids

    fun encodeDetailed(text: String): Encoding {
        // The library returns no tokens at all for the empty string; every other input goes
        // through the normal pipeline (even " ", which yields the single MARKER piece).
        if (text.isEmpty()) return Encoding(IntArray(0), emptyList())

        val ids = ArrayList<Int>(text.length + 4)
        for (piece in metaspacePieces(text.replace(' ', MARKER))) {
            encodePiece(piece, ids)
        }
        val tokens = ArrayList<String>(ids.size)
        for (id in ids) tokens.add(vocabR[id])
        return Encoding(ids.toIntArray(), tokens)
    }

    private fun metaspacePieces(norm: String): List<String> {
        if (norm.isEmpty()) return emptyList()
        val s = if (norm[0] == MARKER) norm else MARKER + norm
        val pieces = ArrayList<String>()
        val cur = StringBuilder()
        var i = 0
        while (i < s.length) {
            val ch = s[i]
            if (ch == MARKER) {
                if (cur.isNotEmpty()) {
                    pieces.add(cur.toString())
                    cur.setLength(0)
                }
                if (i + 1 < s.length && s[i + 1] != MARKER) cur.append(MARKER)
                else pieces.add(MARKER.toString())
            } else {
                cur.append(ch)
            }
            i++
        }
        if (cur.isNotEmpty()) pieces.add(cur.toString())
        return pieces
    }

    private fun encodePiece(piece: String, out: MutableList<Int>) {
        val syms = ArrayList<Int>(piece.length + 2)
        var i = 0
        while (i < piece.length) {
            // Iterate code points, not UTF-16 units. A Python/Rust "char" is one code point and
            // its byte fallback is that code point's UTF-8 bytes; an emoji is two Kotlin Chars,
            // and encoding those separately would emit the replacement character twice.
            val cp = piece.codePointAt(i)
            val width = Character.charCount(cp)
            val s = piece.substring(i, i + width)
            i += width

            val direct = vocab[s]
            if (direct != null) {
                syms.add(direct)
                continue
            }
            // Byte fallback. The library requires every byte token to exist, otherwise the
            // whole character degrades to one <unk>; it does not mix the two.
            val bytes = s.toByteArray(Charsets.UTF_8)
            val byteIds = IntArray(bytes.size)
            var all = true
            for (bi in bytes.indices) {
                val id = vocab[byteToken(bytes[bi])]
                if (id == null) {
                    all = false
                    break
                }
                byteIds[bi] = id
            }
            if (all) for (id in byteIds) syms.add(id) else syms.add(unkId)
        }

        while (true) {
            var bestRank = Int.MAX_VALUE
            var bestIdx = -1
            for (i in 0 until syms.size - 1) {
                val slot = merges[pairKey(syms[i], syms[i + 1])] ?: continue
                val rank = (slot ushr 32).toInt()
                if (rank < bestRank) {
                    bestRank = rank
                    bestIdx = i
                }
            }
            if (bestIdx < 0) break
            val slot = merges[pairKey(syms[bestIdx], syms[bestIdx + 1])]!!
            syms[bestIdx] = (slot and 0xFFFFFFFFL).toInt()
            syms.removeAt(bestIdx + 1)
        }
        out.addAll(syms)
    }

    private fun byteToken(b: Byte): String =
        String.format(Locale.ROOT, "<0x%02X>", b.toInt() and 0xFF)

    companion object {
        const val MARKER = '\u2581'
        private const val UNK_TOKEN = "<unk>"

        /** Loads the two compact files, e.g. from `assets.open("laya/vocab.b64")`. */
        fun fromStreams(vocabIn: InputStream, mergesIn: InputStream): LayaBpe =
            fromReaders(vocabIn.bufferedReader(), mergesIn.bufferedReader())

        fun fromReaders(vocabIn: BufferedReader, mergesIn: BufferedReader): LayaBpe {
            val vocab = HashMap<String, Int>(16384)
            val tokens = ArrayList<String>(16384)
            while (true) {
                val line = vocabIn.readLine() ?: break
                if (line.isEmpty()) continue
                val token = decodeBase64Utf8(line)
                vocab[token] = tokens.size
                tokens.add(token)
            }
            require(tokens.isNotEmpty()) { "empty vocab" }
            val vocabR = tokens.toTypedArray()

            val merges = HashMap<Long, Long>(49152)
            var rank = 0
            while (true) {
                val line = mergesIn.readLine() ?: break
                if (line.isEmpty()) continue
                val sp = line.indexOf(' ')
                require(sp > 0) { "malformed merge line: $line" }
                val a = decodeBase64Utf8(line.substring(0, sp))
                val b = decodeBase64Utf8(line.substring(sp + 1))
                val aId = vocab[a] ?: error("merge input '$a' is not in the vocab")
                val bId = vocab[b] ?: error("merge input '$b' is not in the vocab")
                val newId = vocab[a + b] ?: error("merge result '${a + b}' is not in the vocab")
                merges[pairKey(aId, bId)] = (rank.toLong() shl 32) or (newId.toLong() and 0xFFFFFFFFL)
                rank++
            }
            require(rank > 0) { "empty merge list" }

            val unkId = vocab[UNK_TOKEN] ?: error("$UNK_TOKEN is not in the vocab")
            return LayaBpe(vocab, vocabR, merges, unkId)
        }

        private fun pairKey(a: Int, b: Int): Long =
            (a.toLong() shl 32) or (b.toLong() and 0xFFFFFFFFL)

        /**
         * Standard base64, hand-rolled on purpose: `java.util.Base64` needs API 26 and
         * `android.util.Base64` does not exist on the JVM, so neither can be used by a class
         * that runs in both the app and [LayaBpeTest]. `internal` so the test can decode
         * fixture fields with the same code the app uses.
         */
        internal fun decodeBase64Utf8(s: String): String {
            if (s.isEmpty()) return ""
            val out = ByteArray(s.length / 4 * 3 + 3)
            var n = 0
            var acc = 0
            var bits = 0
            for (c in s) {
                if (c == '=') break
                val v = if (c.code < REV.size) REV[c.code] else -1
                require(v >= 0) { "invalid base64 character '$c'" }
                acc = (acc shl 6) or v
                bits += 6
                if (bits >= 8) {
                    bits -= 8
                    out[n++] = (acc ushr bits).toByte()
                }
            }
            return String(out, 0, n, Charsets.UTF_8)
        }

        private const val B64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
        private val REV = IntArray(128) { -1 }.also { rev ->
            for (i in B64.indices) rev[B64[i].code] = i
        }
    }
}
