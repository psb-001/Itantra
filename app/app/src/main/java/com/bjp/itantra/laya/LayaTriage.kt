package com.bjp.itantra.laya

import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.LongBuffer
import kotlin.math.exp
import kotlin.math.roundToInt
import org.json.JSONObject

/**
 * Optional triage layer for the RECEIVING path. Off by default; nothing here runs unless the
 * Settings toggle is on, and nothing here ever runs on the audio thread.
 *
 * It answers one question about a message that has just arrived — "What kind of message is
 * this?" — and returns the option the model prefers with its probability. That reading can
 * only ever *escalate*: a normal message may be spoken as an ALERT when the classifier is
 * confident and the label is destructive, and an ALERT is never downgraded by it. Laya never
 * suppresses a message, never edits its text, and never decides anything on its own.
 *
 * ## The escalation floor is measured, not chosen
 *
 * `docs/LAYA_INTEGRATION_FINDINGS.md` (Addendum 3) records the int8-vs-fp32 comparison: six of
 * twelve bench sentences produced *identical* decisions, and every decision that flipped sat
 * between 0.36 and 0.80. Nothing at or above 0.87 changed its argmax. So the floor sits at
 * [ESCALATE_FLOOR] = 0.85 — above the band where the quantised model is known to wobble — and
 * the band below it is treated as "no opinion" rather than as a decision:
 *
 *  - below 0.85, nothing happens except a note in the log. The message is still spoken.
 *  - at or above 0.85 with a destructive label, the announcement is upgraded to ALERT.
 *
 * The reason to keep the below-floor band visible instead of silent is that it is exactly where
 * the model is least reliable; hiding it would make the layer look better than it is.
 *
 * ## Model and runtime
 *
 * `encoder.onnx` (118 MB) + `head.onnx` (25 MB), int8, from `eval/laya_quantize.py`. Both run on
 * the ONNX Runtime that this app already ships for sherpa-onnx — csukuangfj's 1.28.2 Android
 * build. That is not luck: the Java API in use (`app/libs/onnxruntime-android-1.28.2.jar` +
 * `libonnxruntime4j_jni.so`) comes from the *same release* as `libonnxruntime.so` already in
 * `jniLibs`, verified byte-identical by setup-project.sh. The Maven 1.29.0 AAR was rejected: its
 * library defines `OrtGetApiBase@@VERS_1.29.0`, while sherpa's JNI requires `@VERS_1.28.2`, and
 * Android's linker matches versioned symbols exactly. Swapping it in would have broken ASR/TTS
 * at load time. See Addendum 4.
 *
 * ## Threading
 *
 * [prepare] returns immediately and copies/loads on a daemon thread. [decide] is blocking
 * (~100–200 ms on a phone) and must be called from a worker thread — MainActivity calls it on
 * the same `tts` thread that is about to synthesise speech, which is never the audio thread.
 */
class LayaTriage(private val context: Context, private val onState: ((String) -> Unit)? = null) {

    /** One read of the head. Probabilities sum to 1 over [OPTIONS]. */
    class Decision internal constructor(private val options: List<String>, val probs: FloatArray) {

        val index: Int = run {
            var best = 0
            for (i in probs.indices) if (probs[i] > probs[best]) best = i
            best
        }

        val label: String get() = options[index]
        val confidence: Float get() = probs[index]

        val runnerUp: Float get() {
            var second = 0f
            for (i in probs.indices) if (i != index && probs[i] > second) second = probs[i]
            return second
        }

        /** Distance to the second option. A large margin is a genuinely settled read. */
        val margin: Float get() = confidence - runnerUp

        /** The reading is confident enough to act on (see the class doc for why 0.85). */
        val isConfident: Boolean get() = confidence >= ESCALATE_FLOOR

        /** May this read turn a normal announcement into an ALERT? Only ever upward. */
        val escalate: Boolean get() = label in ESCALATING && isConfident

        fun describe(): String = buildString {
            append(label)
            append(' ')
            append((confidence * 100).roundToInt())
            append('%')
            if (!isConfident) {
                append(" (below the ")
                append((ESCALATE_FLOOR * 100).roundToInt())
                append("% floor — the band where int8 flips)")
            }
        }
    }

    companion object {
        private const val TAG = "LayaTriage"

        /** Asset folder staged by app/setup-project.sh. */
        private const val ASSETS = "laya"

        /**
         * The prompt is English on purpose: the model is zero-shot over the message text, so the
         * question is fixed and only the state changes language. Copied verbatim from
         * eval/laya_onnx.py — it is part of the model's training format, not a UI string.
         */
        const val QUESTION = "What kind of message is this?"

        val OPTIONS = listOf("routine message", "safety warning", "evacuation order", "medical emergency")

        /** Labels that may raise an ALERT. "safety warning" does not: it is not an order. */
        private val ESCALATING = setOf("evacuation order", "medical emergency")

        /** See the class doc. Above the measured 0.36–0.80 flip band. */
        const val ESCALATE_FLOOR = 0.85f

        /** qtype 0 asks the "kind" question; the head takes it as a hint. */
        private const val QTYPE_KIND = 0L

        private const val HIDDEN = 768L
    }

    private val ready = java.util.concurrent.atomic.AtomicBoolean(false)
    private val started = java.util.concurrent.atomic.AtomicBoolean(false)
    private val closed = java.util.concurrent.atomic.AtomicBoolean(false)

    private var bpe: LayaBpe? = null
    private var encoder: OrtSession? = null
    private var head: OrtSession? = null
    private var env: OrtEnvironment? = null

    private var bos = 2
    private var eos = 1
    private var maskId = 4
    private var maxLen = 1024

    val isReady: Boolean get() = ready.get()

    /** True once [close] has run: the sessions are gone and this object cannot be reused. */
    val isClosed: Boolean get() = closed.get()

    /** "idle", "loading", "ready", or "failed". For the Settings dialog and the log. */
    @Volatile
    var state: String = "idle"
        private set

    /**
     * Copy what is missing and open both sessions on a background thread. Safe to call twice;
     * only the first call does anything. Never throws.
     */
    fun prepare() {
        if (ready.get() || !started.compareAndSet(false, true)) return
        setState("loading")
        val t = Thread({
            try {
                load()
                ready.set(true)
                setState("ready")
                Log.i(TAG, "triage ready — vocab ${bpe?.vocabSize}, merges ${bpe?.mergeCount}")
            } catch (t: Throwable) {
                setState("failed: ${t.message}")
                Log.w(TAG, "triage unavailable: ${t.message}", t)
            }
        }, "laya-init")
        t.isDaemon = true
        t.start()
    }

    /**
     * Read the message. Returns null when the layer is not ready, the text is empty, or anything
     * at all goes wrong — in every one of those cases the caller just speaks the message normally.
     */
    fun decide(text: String): Decision? {
        if (!ready.get() || text.isEmpty()) return null
        val bpe = this.bpe ?: return null
        val encoder = this.encoder ?: return null
        val head = this.head ?: return null
        val env = this.env ?: return null

        // The prompt, byte-for-byte as eval/laya_onnx.py builds it:
        //   <bos> question <eos> (<mask> option)* <eos> state <eos>
        // The head scores each option from the hidden state at that option's <mask> position.
        val ids = ArrayList<Int>(64)
        ids.add(bos)
        appendTokens(bpe, QUESTION, ids)
        ids.add(eos)
        val markers = IntArray(OPTIONS.size)
        for (i in OPTIONS.indices) {
            markers[i] = ids.size
            ids.add(maskId)
            appendTokens(bpe, OPTIONS[i], ids)
        }
        ids.add(eos)
        appendTokens(bpe, text, ids)
        ids.add(eos)

        // Truncation is the harness's too: the prompt is at the front, so every marker survives
        // and only the tail of a very long message is lost. If a marker did not survive, this is
        // not a reading worth acting on.
        val n = minOf(ids.size, maxLen)
        for (m in markers) if (m >= n) return null
        val seq = IntArray(n) { ids[it] }

        val shapeSeq = longArrayOf(1, n.toLong())
        val shapeMarkers = longArrayOf(1, markers.size.toLong())
        val tensors = ArrayList<OnnxTensor>(6)
        try {
            val tIds = OnnxTensor.createTensor(env, longs(seq.map { it.toLong() }.toLongArray()), shapeSeq)
                .also { tensors.add(it) }
            val tMask = OnnxTensor.createTensor(env, longs(LongArray(n) { 1L }), shapeSeq)
                .also { tensors.add(it) }

            val hidden = encoder.run(mapOf("input_ids" to tIds, "attention_mask" to tMask)).use { out ->
                floats(out)
            }
            if (hidden.size != n * HIDDEN.toInt()) {
                Log.w(TAG, "unexpected hidden-state size ${hidden.size} for $n tokens")
                return null
            }

            val tHidden = OnnxTensor.createTensor(
                env, floatsBuffer(hidden), longArrayOf(1, n.toLong(), HIDDEN),
            ).also { tensors.add(it) }
            val tPos = OnnxTensor.createTensor(env, longs(markers.map { it.toLong() }.toLongArray()), shapeMarkers)
                .also { tensors.add(it) }
            val tMm = OnnxTensor.createTensor(
                env, bytes(ByteArray(markers.size) { 1 }), shapeMarkers, OnnxJavaType.BOOL,
            ).also { tensors.add(it) }
            val tQ = OnnxTensor.createTensor(env, longs(longArrayOf(QTYPE_KIND)), longArrayOf(1, 1))
                .also { tensors.add(it) }

            val logits = head.run(
                mapOf(
                    "hidden_states" to tHidden,
                    "marker_pos" to tPos,
                    "marker_mask" to tMm,
                    "qtype" to tQ,
                    "attention_mask" to tMask,
                ),
            ).use { out -> floats(out) }

            if (logits.size != OPTIONS.size) {
                Log.w(TAG, "expected ${OPTIONS.size} logits, got ${logits.size}")
                return null
            }
            return Decision(OPTIONS, softmax(logits))
        } catch (t: Throwable) {
            // An optional layer that takes the app down with it would be worse than no layer.
            Log.w(TAG, "triage run failed: ${t.message}")
            return null
        } finally {
            for (t in tensors) runCatching { t.close() }
        }
    }

    /**
     * Free the two sessions. The OrtEnvironment is process-wide and deliberately left alive.
     * After this the object is spent; callers drop it and build a new one to use the layer again.
     */
    fun close() {
        closed.set(true)
        ready.set(false)
        runCatching { encoder?.close() }
        runCatching { head?.close() }
        encoder = null
        head = null
        setState("closed")
    }

    private fun setState(s: String) {
        state = s
        onState?.invoke(s)
    }

    // ---------------------------------------------------------------- loading

    private fun load() {
        // The AAR's own manifest says minSdk 24 and the native library is built for API 27.
        // The app ships the same library for ASR/TTS today, so this gate only keeps the *Java*
        // API off devices where it could not work at all.
        if (Build.VERSION.SDK_INT < 24) throw IllegalStateException("the ONNX Runtime Java API needs API 24+")

        val cfg = JSONObject(
            context.assets.open("$ASSETS/config.json").use { it.readBytes().toString(Charsets.UTF_8) },
        )
        bos = cfg.optInt("bos", 2)
        eos = cfg.optInt("eos", 1)
        maskId = cfg.optInt("mask", 4)
        maxLen = cfg.optInt("max_len", 1024)

        // The tokenizer is small (530 KB) and read straight from the APK: no reason to copy it.
        bpe = context.assets.open("$ASSETS/vocab.b64").use { vocab ->
            context.assets.open("$ASSETS/merges.b64").use { merges ->
                LayaBpe.fromStreams(vocab, merges)
            }
        }

        // The sessions need real paths — ORT takes a file name, not an asset stream — so the two
        // .onnx files are copied to app storage once, verified by byte count.
        val dir = modelDir()
        val encPath = copyAsset("encoder.onnx", dir)
        val headPath = copyAsset("head.onnx", dir)

        val opts = OrtSession.SessionOptions()
        opts.setIntraOpNumThreads(2)   // the same budget the ASR engine uses
        opts.setInterOpNumThreads(1)
        val e = OrtEnvironment.getEnvironment()
        encoder = e.createSession(encPath.absolutePath, opts)
        head = e.createSession(headPath.absolutePath, opts)
        env = e
    }

    private fun modelDir(): File {
        // Same location the TTS voice data uses: app-private, and not internal storage, which is
        // tighter on the cheap phones this is aimed at.
        val root = context.getExternalFilesDir(null) ?: context.filesDir
        val dir = File(root, "laya")
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("cannot create ${dir.absolutePath}")
        return dir
    }

    /**
     * Copy `assets/laya/[name]` to [dir] unless it is already there with the right size. The copy
     * lands on a `.part` name first, so a process death halfway through cannot leave a truncated
     * file that a later run would trust. Both .onnx assets are stored uncompressed, which is why
     * `openFd` can tell us their exact length.
     */
    private fun copyAsset(name: String, dir: File): File {
        val dest = File(dir, name)
        val expected = try {
            context.assets.openFd("$ASSETS/$name").use { it.length }
        } catch (e: Exception) {
            -1L
        }
        if (expected > 0 && dest.length() == expected) return dest
        if (expected < 0 && dest.isFile) return dest

        val part = File(dir, "$name.part")
        Log.i(TAG, "copying $ASSETS/$name into ${dir.absolutePath} (first use)")
        context.assets.open("$ASSETS/$name").use { input ->
            part.outputStream().use { out -> input.copyTo(out, 1 shl 16) }
        }
        if (expected > 0 && part.length() != expected) {
            val got = part.length()
            part.delete()
            throw IOException("short copy of $name: $got of $expected bytes")
        }
        if (!part.renameTo(dest)) {
            part.copyTo(dest, overwrite = true)
            part.delete()
        }
        return dest
    }

    // ---------------------------------------------------------------- tensors

    /**
     * The buffer overloads of `OnnxTensor.createTensor` hand the JNI layer a direct address, so
     * every buffer here is a direct buffer with native byte order — including the bool one, which
     * is one byte per element.
     */
    private fun longs(values: LongArray): LongBuffer =
        ByteBuffer.allocateDirect(values.size * 8)
            .order(ByteOrder.nativeOrder())
            .asLongBuffer()
            .apply { put(values) ; rewind() }

    private fun floatsBuffer(values: FloatArray): FloatBuffer =
        ByteBuffer.allocateDirect(values.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply { put(values) ; rewind() }

    private fun bytes(values: ByteArray): ByteBuffer =
        ByteBuffer.allocateDirect(values.size).apply { put(values) ; rewind() }

    /** First output of a run, as a float array. Both graphs are float-output here. */
    private fun floats(out: OrtSession.Result): FloatArray {
        val buf = (out.get(0) as OnnxTensor).floatBuffer
        return FloatArray(buf.remaining()).also { buf.get(it) }
    }

    private fun appendTokens(bpe: LayaBpe, text: String, into: MutableList<Int>) {
        for (id in bpe.encode(text)) into.add(id)
    }

    /** Numerically stable softmax, matching the harness: max is subtracted before exp. */
    private fun softmax(logits: FloatArray): FloatArray {
        var max = Float.NEGATIVE_INFINITY
        for (v in logits) if (v > max) max = v
        var sum = 0.0
        val out = FloatArray(logits.size)
        for (i in logits.indices) {
            val e = exp((logits[i] - max).toDouble())
            out[i] = e.toFloat()
            sum += e
        }
        for (i in out.indices) out[i] = (out[i] / sum).toFloat()
        return out
    }
}
