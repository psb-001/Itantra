package com.bjp.itantra

import android.Manifest
import android.annotation.SuppressLint
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.os.Debug
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.bjp.itantra.laya.LayaTriage
import com.bjp.itantra.speech.AsrEngine
import com.bjp.itantra.speech.Lang
import com.bjp.itantra.speech.Speaker
import com.bjp.itantra.speech.SpeechQueueOrder
import com.bjp.itantra.speech.TtsEngine
import com.bjp.itantra.speech.VadEngine
import com.bjp.itantra.transport.Outbox
import com.bjp.itantra.transport.Transport
import com.bjp.itantra.transport.TransportKind
import com.k2fsa.sherpa.onnx.WaveReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.concurrent.withLock

/**
 * iTantra — offline voice transceiver. SIH26173.
 *
 * Push-to-talk: hold the button, speak, release. Phone A recognises the speech, sends only the
 * text, and phone B speaks it out. Neither phone runs both heavy models at once — the speaker
 * pays the ASR cost, the listener pays the TTS cost. That asymmetry is the core design decision
 * and it is what makes this fit on a low-end phone.
 *
 * Everything runs on-device. No network call is made at any point.
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "iTantra"
        private const val REQ_PERMISSIONS = 200
        private const val SAMPLE_RATE = 16000
        private const val BUFFER_SAMPLES = 512

        /** Where the chosen role is remembered between launches. */
        private const val PREFS = "itantra"
        private const val KEY_IS_SPEAKER = "is_speaker"

        /**
         * The optional Laya triage layer. Off by default: it is ~143 MB of assets and an
         * experimental decision path, so it stays a deliberate choice, not a surprise.
         */
        private const val KEY_LAYA_TRIAGE = "laya_triage"

        /** How many lines the Settings log keeps in memory. */
        private const val LIVE_LOG_MAX = 400

        /** How often the delivery ticker checks for unconfirmed messages to resend. */
        private const val DELIVERY_TICK_MS = 2_000L

        /**
         * Longest clip handed to one AudioTrack in MODE_STATIC before the speech worker splits it
         * into back-to-back pieces. 240 k samples ≈ 1 MB of float, under 11 s at 22 kHz — safely
         * below what even a low-RAM phone will allocate for a static track. A 30 s VAD segment at
         * 22 kHz is ~2.6 MB, and a failed track build would mean a message shown but never heard.
         */
        private const val MAX_PIECE_SAMPLES = 240_000
    }

    // --- UI ---
    private lateinit var connectionView: TextView
    private lateinit var statusView: TextView
    private lateinit var transcriptView: TextView
    private lateinit var logView: TextView
    private lateinit var levelView: TextView
    private lateinit var pttButton: MaterialButton
    private lateinit var settingsButton: Button
    private lateinit var devicesButton: Button
    private lateinit var aboutButton: Button
    private lateinit var logToggle: Button
    private lateinit var logScroll: View
    private lateinit var levelBar: LinearProgressIndicator
    private lateinit var connectionDot: View

    /** Rolling on-screen event log. Capped, for glancing at. */
    private val logLines = ArrayDeque<String>()

    /** The full in-memory log, shown in Settings. Uncapped up to [LIVE_LOG_MAX]. */
    private val liveLog = ArrayDeque<String>()

    private fun log(msg: String) {
        Log.i(TAG, msg)
        FileLogger.write(msg)
        val stamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
            .format(java.util.Date())
        val line = "$stamp  $msg"
        runOnUiThread {
            liveLog.addLast(line)
            while (liveLog.size > LIVE_LOG_MAX) liveLog.removeFirst()

            logLines.addLast(line)
            while (logLines.size > 14) logLines.removeFirst()
            logView.text = logLines.joinToString("\n")
        }
    }

    // --- speech ---
    private var vad: VadEngine? = null
    private var asr: AsrEngine? = null
    private var tts: TtsEngine? = null

    /**
     * Name of the voice currently resident in [tts]. Exactly one is kept in memory — each Piper
     * voice is ~60 MB and the ten would cost 600 MB — and it is swapped on demand when a received
     * message is in a different language than the one loaded. [voiceLock] guards the pair across
     * load, swap and synthesis: releasing an engine mid-synthesis is a native use-after-free, and
     * two quick messages in different languages is exactly how that race would be reached.
     */
    @Volatile
    private var loadedVoice: String? = null
    private val voiceLock = Any()

    /**
     * Guards the ASR recogniser's lifecycle the way [voiceLock] guards the voice: a decode holds
     * it for its whole duration, and a memory-trim release takes it only when it is free
     * (tryLock) — never freeing the native recogniser out from under a running decode, which is
     * exactly the race a trim handler would otherwise introduce. See [releaseAsrUnderPressure].
     */
    private val asrLock = java.util.concurrent.locks.ReentrantLock()

    private val speaker = Speaker(applicationContext)

    // --- optional triage (Laya) ---
    /** Built only when the Settings toggle is on. Never on the audio thread. */
    private var laya: LayaTriage? = null

    @Volatile
    private var triageEnabled = false

    // --- audio capture ---
    private var audioRecord: AudioRecord? = null
    private var captureThread: Thread? = null

    @Volatile
    private var recording = false

    /** How many VAD segments this button-hold produced. Reset on each press. */
    @Volatile
    private var segmentsThisHold = 0

    // --- link ---
    private var transport: Transport? = null
    private var kind = TransportKind.BLUETOOTH
    private val ledger = MessageLedger()
    private val seq = AtomicInteger(0)

    /**
     * Packets that could not go out because the link was down, in order, sent when it returns.
     * See [Outbox]: `send()` used to drop silently, which is how a speaker could see "Sent" on
     * their screen while the other phone heard nothing. Flushed from the CONNECTED callback.
     */
    private val outbox = Outbox(capacity = 64) { bytes -> transport?.send(bytes) == true }

    /**
     * Every message this phone sent, until the other phone confirms it arrived.
     *
     * The outbox covers "the link is down"; this covers "the link *looked* up and the packet
     * still never made it" — a write into a socket that was already dead. The receiving phone
     * answers each message with a receipt ([Message.Type.ACK]); resends are scheduled here and
     * stop the moment a receipt returns. See [DeliveryTracker].
     */
    private val delivery = DeliveryTracker(clock = { SystemClock.elapsedRealtime() })

    /**
     * The durable copy of pending work — buffered packets, unconfirmed messages, heard ids, the
     * counters in Settings. Everything above is in RAM only, so without this an Android process
     * kill in the background erased sentences the sender had already been told were on their way.
     * Written on every change (the file is a few KB) and loaded once at startup; see [persistPending]
     * and [restorePending].
     */
    private val pendingStore by lazy { PendingStore(java.io.File(filesDir, "pending.json")) }

    private val deliveryHandler = android.os.Handler(android.os.Looper.getMainLooper())

    /**
     * The resend ticker. One ticker on the main looper; resends go through the same ordered
     * outbox as first sends, so a resend can never overtake a sentence still waiting to go out.
     */
    private val deliveryTicker = object : Runnable {
        override fun run() {
            tickDeliveries()
            if (!destroyed) deliveryHandler.postDelayed(this, DELIVERY_TICK_MS)
        }
    }

    /** The peer this phone dialled last; null when it has only ever listened. Auto-reconnect target. */
    @Volatile
    private var lastDialedPeerId: String? = null

    /** True once any session has existed — automatic reconnection only chases a link that existed. */
    @Volatile
    private var everConnected = false

    private var reconnectAttempts = 0
    private val reconnectHandler = android.os.Handler(android.os.Looper.getMainLooper())

    /**
     * True while a reconnect is already scheduled. One physical link death reports twice — ERROR
     * from the heartbeat or the failing write, then IDLE from the reader unwinding — and without
     * this the second report cancelled the first timer and burned a backoff step, turning a 2 s
     * recovery into a 5 s one for no reason.
     */
    @Volatile
    private var reconnectPending = false

    @Volatile
    private var destroyed = false

    /**
     * Speech-out, one message at a time, in arrival order. Per-message threads used to synthesise
     * and play in parallel, and Speaker.play() stops whatever is playing — so the burst of
     * segments a long hold produces cut each other off into fragments on the other phone.
     */
    private val speechQueue = LinkedBlockingDeque<Message>()
    @Volatile
    private var speechWorker: Thread? = null

    /**
     * Audio already synthesised for the message the worker expects to speak next — built while
     * the current message was still being heard (see [maybePrefetchNext]). One slot, touched
     * only by the "tts" worker thread, so no lock; dropped the moment it is used, not needed,
     * or found stale.
     */
    private class Prefetched(
        val id: String,
        val voiceName: String,
        val samples: FloatArray,
        val sampleRate: Int,
    )

    private var prefetched: Prefetched? = null

    /** Speech-in: VAD segments decoded one at a time, in the order they were spoken. */
    private val asrQueue = LinkedBlockingQueue<FloatArray>()
    @Volatile
    private var asrWorker: Thread? = null

    /** SPEAKER = this phone listens and sends. LISTENER = this phone receives and speaks. */
    @Volatile
    // No speaker/listener role. Both phones load both models and can talk or receive.
    private var langTag = com.bjp.itantra.speech.Lang.HINDI

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        FileLogger.init(this)
        registerBluetoothWatcher()

        // No role to restore any more — every phone does both jobs. See loadModels().
        connectionView = findViewById(R.id.connection)
        statusView = findViewById(R.id.status)
        transcriptView = findViewById(R.id.transcript)
        logView = findViewById(R.id.log)
        levelView = findViewById(R.id.level)
        pttButton = findViewById(R.id.ptt_button)
        settingsButton = findViewById(R.id.settings_button)
        devicesButton = findViewById(R.id.devices_button)
        aboutButton = findViewById(R.id.about_button)
        logToggle = findViewById(R.id.log_toggle)
        logScroll = findViewById(R.id.log_scroll)
        levelBar = findViewById(R.id.level_bar)
        connectionDot = findViewById(R.id.connection_dot)

        log("app started")
        log("permissions: mic=${granted(Manifest.permission.RECORD_AUDIO)}")

        // Receipts and resends run from app start; the ticker idles while there is nothing to
        // confirm or no link.
        deliveryHandler.postDelayed(deliveryTicker, DELIVERY_TICK_MS)

        // Whatever the last session had to leave behind — buffered packets, unconfirmed
        // messages, heard ids — comes back before any link exists. Nothing is sent from here;
        // the packets wait in the outbox and flush, in order, when a link comes up.
        restorePending()

        // The optional triage layer. prepare() only starts a background thread; it copies nothing
        // and loads nothing unless the toggle below is on.
        triageEnabled = getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(KEY_LAYA_TRIAGE, false)
        if (triageEnabled) ensureTriage()

        requestPermissions()

        settingsButton.setOnClickListener { showSettings() }
        devicesButton.setOnClickListener { showDevicePicker() }
        aboutButton.setOnClickListener {
            startActivity(Intent(this, AboutActivity::class.java))
        }

        // The diagnostics log is hidden by default. It is genuinely useful — it is how most of
        // this project's silent bugs were tracked down — but it is noise during a demo, and a
        // wall of monospace text at the bottom of the screen makes the app look unfinished.
        logToggle.setOnClickListener {
            val showing = logScroll.visibility == View.VISIBLE
            logScroll.visibility = if (showing) View.GONE else View.VISIBLE
            logToggle.setText(if (showing) R.string.show_log else R.string.hide_log)
        }

        // Push-to-talk: press and hold. No role check — either phone may talk at any time.
        pttButton.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startCapture()
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    stopCapture()
                    // Accessibility: push-to-talk rides entirely on touch events, which screen
                    // readers cannot synthesise. performClick() is the documented hook that tells
                    // accessibility services (and lint) the button was activated.
                    if (event.action == MotionEvent.ACTION_UP) pttButton.performClick()
                    true
                }
                else -> false
            }
        }

        // Benchmark mode: measure on-device latency and skip the normal interactive flow.
        //   adb shell am start -n com.bjp.itantra/.MainActivity --ez benchmark true
        if (intent?.getBooleanExtra("benchmark", false) == true) {
            setStatus("Benchmark mode — see logcat")
            runBenchmark()
            return
        }
        if (intent?.getBooleanExtra("pipeline", false) == true) {
            setStatus("Pipeline test — see logcat")
            runPipelineTest()
            return
        }
        if (intent?.getBooleanExtra("loopback", false) == true) {
            setStatus("Loopback test — see logcat")
            runLoopbackTest()
            return
        }

        // START THE TRANSPORT BEFORE LOADING MODELS.
        //
        // This was a race and it was the main cause of slow connections. Previously the sequence
        // was: load models (1.5-2 s) -> start listening. So the listener phone was not accepting
        // until ~2 s after launch, while the speaker phone was already trying to connect. Every
        // attempt landing in that window burned the full RFCOMM connect timeout (up to 12 s)
        // before failing, and then had to be retried.
        //
        // The transport does not need the models. Starting it first makes the listener ready
        // within ~100 ms of launch, so the speaker's first attempt lands on a live socket.
        connectTransport(null)
        updateConnectionIndicator()

        // Model loading is slow (a 348 MB model). Keep it off the UI thread and say so on screen.
        lifecycleScope.launch {
            setStatus("Loading models… this takes a moment")
            val ok = withContext(Dispatchers.IO) { loadModels() }
            if (ok) {
                setStatus("Ready — hold to talk (${kind.displayName})")
                pttButton.isEnabled = true
                updateConnectionIndicator()
            }
        }
    }

    // ------------------------------------------------------------------ benchmark

    /**
     * On-device latency and memory benchmark.
     *
     * Triggered with:
     *   adb shell am start -n com.bjp.itantra/.MainActivity --ez benchmark true
     *
     * Runs ASR and TTS on bundled WAV files and logs RTF, latency and peak memory. This exists
     * because every latency figure in the project was previously measured in Python on a Mac.
     * A number from the Android runtime — even an emulator — is closer to the real thing, and it
     * exercises the JNI path that the Python measurements never touch.
     */
    private fun runBenchmark() {
        thread(name = "benchmark") {
            try {
                val clips = listOf("bench/bench_00_en.wav", "bench/bench_01_en.wav", "bench/bench_02_hi.wav")
                val repeats = 3

                Log.i(TAG, "BENCH === on-device latency benchmark ===")
                Log.i(TAG, "BENCH device: ${Build.MANUFACTURER} ${Build.MODEL} / ${Build.SUPPORTED_ABIS.firstOrNull()}")

                val runtime = Runtime.getRuntime()
                val startMem = Debug.getNativeHeapAllocatedSize()

                // ---------- ASR ----------
                val t0 = SystemClock.elapsedRealtime()
                val asr = AsrEngine(assets, numThreads = 2)
                val asrLoadMs = SystemClock.elapsedRealtime() - t0
                Log.i(TAG, "BENCH ASR model load: ${asrLoadMs} ms")

                var totalAudio = 0.0
                var totalAsr = 0.0
                for (name in clips) {
                    val wav = WaveReader.readWave(assets, name)
                    val dur = wav.samples.size.toDouble() / wav.sampleRate
                    val times = mutableListOf<Long>()
                    var text = ""
                    repeat(repeats) {
                        val t = SystemClock.elapsedRealtime()
                        text = asr.transcribe(wav.samples)
                        times.add(SystemClock.elapsedRealtime() - t)
                    }
                    times.sort()
                    val median = times[times.size / 2]
                    totalAudio += dur
                    totalAsr += median
                    Log.i(
                        TAG,
                        "BENCH ASR ${name.substringAfterLast('/')} dur=${"%.2f".format(dur)}s " +
                            "median=${median}ms min=${times.first()}ms max=${times.last()}ms " +
                            "RTF=${"%.4f".format(median / 1000.0 / dur)}"
                    )
                    Log.i(TAG, "BENCH   text: ${text.take(70)}")
                }
                Log.i(TAG, "BENCH ASR TOTAL audio=${"%.2f".format(totalAudio)}s " +
                    "compute=${"%.2f".format(totalAsr / 1000)}s " +
                    "RTF=${"%.4f".format(totalAsr / 1000.0 / totalAudio)}")
                asr.release()

                // ---------- TTS ----------
                val t1 = SystemClock.elapsedRealtime()
                val tts = TtsEngine(this, TtsEngine.ENGLISH, numThreads = 2)
                val ttsLoadMs = SystemClock.elapsedRealtime() - t1
                Log.i(TAG, "BENCH TTS voice load: ${ttsLoadMs} ms  sampleRate=${tts.sampleRate()}")

                val sentence = "Emergency alert. Move to a safe location immediately."
                var totalSpeech = 0.0
                var totalTts = 0.0
                repeat(repeats) {
                    val t = SystemClock.elapsedRealtime()
                    val samples = tts.synthesise(sentence)
                    val ms = SystemClock.elapsedRealtime() - t
                    val speech = samples.size.toDouble() / tts.sampleRate()
                    totalSpeech += speech
                    totalTts += ms
                    Log.i(TAG, "BENCH TTS synth=${ms}ms audio=${"%.2f".format(speech)}s " +
                        "RTF=${"%.4f".format(ms / 1000.0 / speech)}")
                }
                Log.i(TAG, "BENCH TTS TOTAL speech=${"%.2f".format(totalSpeech / repeats)}s " +
                    "RTF=${"%.4f".format(totalTts / 1000.0 / totalSpeech)}")
                tts.release()

                // ---------- memory ----------
                val endMem = Debug.getNativeHeapAllocatedSize()
                Log.i(TAG, "BENCH native heap: start=${startMem / 1048576}MB end=${endMem / 1048576}MB")
                Log.i(TAG, "BENCH java heap: max=${runtime.maxMemory() / 1048576}MB " +
                    "total=${runtime.totalMemory() / 1048576}MB")
                Log.i(TAG, "BENCH === done ===")
            } catch (e: Throwable) {
                Log.e(TAG, "BENCH FAILED", e)
            }
            runOnUiThread { setStatus("Benchmark complete — see logcat") }
        }
    }

    /**
     * Full speaker-side pipeline test: WAV -> VAD (chunked, like the mic) -> flush -> ASR ->
     * language filter -> packet.
     *
     * This exists to verify the VAD-flush fix. The bug was that releasing the PTT button stopped
     * capture WITHOUT flushing, so the final sentence was discarded and no transcript ever
     * appeared. Feeding a file through the identical code path proves the fix works without
     * needing a working microphone.
     */
    private fun runPipelineTest() {
        thread(name = "pipeline-test") {
            try {
                Log.i(TAG, "PIPE === full speaker pipeline test ===")
                val vad = VadEngine(assets)
                val asr = AsrEngine(assets, numThreads = 2)

                for (name in listOf("bench/bench_00_en.wav", "bench/bench_02_hi.wav")) {
                    val wav = WaveReader.readWave(assets, name)
                    val langTag = if (name.contains("_hi")) Lang.HINDI else Lang.ENGLISH
                    val segments = mutableListOf<FloatArray>()

                    vad.reset()
                    // Feed in 512-sample windows, exactly as the microphone loop does.
                    var i = 0
                    while (i < wav.samples.size) {
                        val end = minOf(i + 512, wav.samples.size)
                        vad.accept(wav.samples.copyOfRange(i, end)) { segments.add(it) }
                        i = end
                    }
                    val beforeFlush = segments.size
                    // THE FIX: flush so the in-progress final sentence is emitted.
                    vad.flush { segments.add(it) }

                    Log.i(
                        TAG,
                        "PIPE $name: ${"%.2f".format(wav.samples.size / 16000.0)}s audio -> " +
                            "$beforeFlush segments before flush, ${segments.size} after " +
                            "(+${segments.size - beforeFlush} from flush)"
                    )

                    for ((idx, seg) in segments.withIndex()) {
                        val t0 = System.currentTimeMillis()
                        val raw = asr.transcribe(seg)
                        val ms = System.currentTimeMillis() - t0
                        val verdict = LanguageFilter.filter(raw, langTag)
                        val msg = Message(
                            lang = langTag,
                            type = Message.Type.MSG,
                            seq = idx,
                            text = verdict.text,
                        )
                        Log.i(
                            TAG,
                            "PIPE   seg$idx ${"%.2f".format(seg.size / 16000.0)}s ${ms}ms " +
                                "send=${verdict.send} script=${"%.2f".format(verdict.fraction)} " +
                                "packet=${msg.toBytes().size}B raw=\"${raw.take(40)}\""
                        )
                    }
                }
                vad.release()
                asr.release()
                Log.i(TAG, "PIPE === done ===")
            } catch (e: Throwable) {
                Log.e(TAG, "PIPE FAILED", e)
            }
        }
    }

    /**
     * Full LOOPBACK test: speaker side AND listener side, on one device.
     *
     * WAV -> VAD -> flush -> ASR -> filter -> Message -> bytes -> Message -> onPacket -> TTS ->
     * Speaker. Everything except the radio.
     *
     * The receiver path (`onPacket`, `TtsEngine`, `Speaker`, the duplicate/expiry ledger) has
     * never been executed. This exercises it without needing a second phone.
     */
    private fun runLoopbackTest() {
        thread(name = "loopback") {
            try {
                Log.i(TAG, "LOOP === loopback test: speaker side then listener side ===")
                val vad = VadEngine(assets)
                val asr = AsrEngine(assets, numThreads = 2)
                val ledger = MessageLedger()

                for (name in listOf("bench/bench_00_en.wav", "bench/bench_02_hi.wav")) {
                    val wav = WaveReader.readWave(assets, name)
                    val langTag = if (name.contains("_hi")) Lang.HINDI else Lang.ENGLISH
                    val segments = mutableListOf<FloatArray>()

                    vad.reset()
                    var i = 0
                    while (i < wav.samples.size) {
                        val end = minOf(i + 512, wav.samples.size)
                        vad.accept(wav.samples.copyOfRange(i, end)) { segments.add(it) }
                        i = end
                    }
                    vad.flush { segments.add(it) }

                    for ((idx, seg) in segments.withIndex()) {
                        val raw = asr.transcribe(seg)
                        val verdict = LanguageFilter.filter(raw, langTag)
                        if (!verdict.send) {
                            Log.i(TAG, "LOOP $name seg$idx DROPPED (script=${verdict.fraction})")
                            continue
                        }

                        // ---- SPEAKER SIDE: build and serialise the packet ----
                        val out = Message(
                            lang = langTag,
                            type = Message.Type.MSG,
                            seq = idx,
                            text = verdict.text,
                        )
                        val wire = out.toBytes()
                        Log.i(TAG, "LOOP $name seg$idx SENT ${wire.size} B \"${verdict.text.take(36)}\"")

                        // ---- LISTENER SIDE: exactly what the transport callback does ----
                        val received = Message.fromBytes(wire)
                        if (received == null) {
                            Log.e(TAG, "LOOP   FAIL: packet did not deserialise")
                            continue
                        }
                        if (received.text != out.text) {
                            Log.e(TAG, "LOOP   FAIL: text changed in transit")
                            continue
                        }
                        if (!ledger.accept(received)) {
                            Log.i(TAG, "LOOP   duplicate suppressed (correct)")
                            continue
                        }
                        if (!ledger.isFresh(received)) {
                            Log.i(TAG, "LOOP   stale, not announced (correct)")
                            continue
                        }

                        // ---- TTS + playback ----
                        // Same rule as the live receive path: the text's own script decides the
                        // voice when it contradicts the tag.
                        val wantTag = LanguageFilter.voiceTagFor(received.lang, received.text)
                        val voice = TtsEngine.voiceFor(wantTag)
                        if (voice == null) {
                            Log.i(TAG, "LOOP   no bundled voice for $wantTag")
                            continue
                        }
                        val tts = TtsEngine(applicationContext, voice, numThreads = 2)
                        val t0 = System.currentTimeMillis()
                        val samples = tts.synthesise(received.text)
                        val synthMs = System.currentTimeMillis() - t0
                        val sr = tts.sampleRate()
                        Log.i(
                            TAG,
                            "LOOP   TTS $synthMs ms -> ${samples.size} samples " +
                                "(${"%.2f".format(samples.size.toDouble() / sr)} s @ ${sr} Hz)"
                        )
                        tts.release()

                        // ---- SPEAKER (AudioTrack) ----
                        // Last message is played in ALERT mode to exercise the looping path.
                        // The PS requires alerts at maximum volume that cannot be interrupted.
                        val isAlert = name.contains("_hi")
                        val mode = if (isAlert) Speaker.Mode.ALERT else Speaker.Mode.NORMAL
                        val player = Speaker(applicationContext)
                        player.play(samples, sr, mode)
                        Log.i(
                            TAG,
                            "LOOP   playback started mode=$mode " +
                                "isPlaying=${player.isPlaying()}"
                        )
                        Thread.sleep(1500)
                        player.stop()
                        ledger.markPlayed(received)
                        Log.i(TAG, "LOOP   seg$idx COMPLETE")
                    }
                }
                vad.release()
                asr.release()
                Log.i(TAG, "LOOP === done ===")
            } catch (e: Throwable) {
                Log.e(TAG, "LOOP FAILED", e)
            }
        }
    }

    /**
     * Re-open the listening socket when Bluetooth comes back on.
     *
     * THE BUG THIS FIXES. The app calls startListening() once, at launch. If Bluetooth happens to
     * be off at that moment the call fails with "Bluetooth is switched off" — and nothing ever
     * retries. The user then switches Bluetooth on, sees their paired devices appear in the list,
     * taps one, and every connect is refused instantly because their own phone stopped listening
     * minutes ago and never started again.
     *
     * Observed in a real log on 21 Sep 2026:
     *     20:15:25  listening for the other phone
     *     20:15:25  ERROR — Bluetooth is switched off — turn it on and retry
     *     20:20:59  device picker: 8 peer(s) available      <- Bluetooth now on
     *     20:21:01  connecting to OPPO K12x 5G             <- refused in 0 ms
     *
     * The adapter state broadcast is the signal that the earlier attempt can now succeed.
     */
    private val bluetoothStateReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: android.content.Intent?) {
            if (intent?.action != android.bluetooth.BluetoothAdapter.ACTION_STATE_CHANGED) return
            val state = intent.getIntExtra(
                android.bluetooth.BluetoothAdapter.EXTRA_STATE,
                android.bluetooth.BluetoothAdapter.ERROR,
            )
            when (state) {
                android.bluetooth.BluetoothAdapter.STATE_ON -> {
                    log("Bluetooth switched ON — reopening the link")
                    if (transport?.isConnected != true) {
                        // Re-dial the peer we knew, if there was one: this phone may have been
                        // mid-conversation when Bluetooth went off. connectTransport() starts
                        // listening again either way.
                        connectTransport(lastDialedPeerId)
                    }
                }
                android.bluetooth.BluetoothAdapter.STATE_OFF -> {
                    log("Bluetooth switched OFF")
                    updateConnectionIndicator()
                }
            }
        }
    }

    private fun registerBluetoothWatcher() {
        val filter = android.content.IntentFilter(
            android.bluetooth.BluetoothAdapter.ACTION_STATE_CHANGED
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(bluetoothStateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(bluetoothStateReceiver, filter)
        }
    }

    // ------------------------------------------------------------------ permissions

    private fun granted(permission: String): Boolean =
        ActivityCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun requiredPermissions(): Array<String> = buildList {
        add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // On Android 12+ the legacy BLUETOOTH permissions are ignored. Getting this wrong
            // fails silently: no crash, just no connection.
            add(Manifest.permission.BLUETOOTH_CONNECT)
            add(Manifest.permission.BLUETOOTH_SCAN)
        } else {
            @Suppress("DEPRECATION")
            add(Manifest.permission.BLUETOOTH)
            @Suppress("DEPRECATION")
            add(Manifest.permission.BLUETOOTH_ADMIN)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Required to discover Wi-Fi Direct peers. Documented platform requirement. Request
            // the PAIR: Android 12+ ignores a request for FINE location on its own.
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
            add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.NEARBY_WIFI_DEVICES)
        }
    }.toTypedArray()

    private fun requestPermissions() {
        val missing = requiredPermissions().filter {
            ActivityCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) ActivityCompat.requestPermissions(this, missing.toTypedArray(), REQ_PERMISSIONS)
    }

    // ------------------------------------------------------------------ models

    /** @return true when everything the current mode needs is loaded. */
    /**
     * Load EVERY model on EVERY phone: VAD, ASR and TTS.
     *
     * This is a walkie-talkie. Either phone may talk and either may listen, so both need both
     * engines. An earlier design split them — one phone speech-to-text, the other text-to-speech —
     * which meant the link only worked in one direction and a reply required both users to swap
     * roles. That is not how a walkie-talkie works.
     *
     * The cost is real and deliberate: roughly 500 MB resident instead of ~430 MB, and about a
     * second more startup while the TTS voice loads. That is the price of two-way.
     */
    private fun loadModels(): Boolean = try {
        releaseModels()

        vad = VadEngine(assets)

        // Speech in. Always needed — either phone can be the one talking.
        asrLock.withLock { asr = AsrEngine(assets, numThreads = 2) }

        // Speech out. Always needed — either phone can be the one receiving.
        val voice = TtsEngine.voiceFor(langTag)
        if (voice == null) {
            // Surface this. A language with no bundled voice still shows correct TEXT on the
            // receiving phone, so without a visible warning the demo looks like it is working
            // right up until nobody hears any sound. That is a much worse failure than an
            // honest "this one is text-only".
            val spoken = TtsEngine.speakableLanguages().joinToString(", ") { it.substringBefore('_') }
            log("no TTS voice bundled for $langTag — this language is TEXT-ONLY")
            log("  voices bundled for: $spoken")
            runOnUiThread {
                setStatus("$langTag is text-only — no bundled voice")
                append("⚠ $langTag has no bundled voice. Incoming messages will be shown as text and NOT spoken.")
                append("   Voices bundled for: $spoken")
            }
        } else {
            synchronized(voiceLock) {
                tts = TtsEngine(this@MainActivity, voice, numThreads = 2)
                loadedVoice = voice
            }
        }

        // Laya is optional and warms up in the background: a 143 MB asset copy must never delay
        // speech, and if it fails, the models that actually matter keep working.
        if (triageEnabled) ensureTriage()

        true
    } catch (e: Throwable) {
        Log.e(TAG, "model load failed", e)
        FileLogger.write("FAIL: model load threw ${e::class.simpleName}: ${e.message}")
        setStatusBlocking("Model load failed: ${e.message}")
        false
    }

    /** Free every native engine. Safe to call when some are already null. */
    private fun releaseModels() {
        // tryLock, not withLock: releaseModels runs at shutdown while the ASR worker may be
        // mid-decode, and blocking the main thread on a multi-second decode is worse than
        // leaving that one native engine to the process teardown.
        if (asrLock.tryLock()) {
            try {
                runCatching { asr?.release() }
                asr = null
            } finally {
                asrLock.unlock()
            }
        }
        synchronized(voiceLock) {
            runCatching { tts?.release() }; tts = null; loadedVoice = null
        }
        runCatching { vad?.release() }; vad = null
        runCatching { laya?.close() }; laya = null
    }

    /**
     * Free the TTS voice (~60 MB) when it is idle and the system reports memory pressure. The
     * next message reloads it in about a second — cheaper than the app being the reason a low-RAM
     * phone starts killing things. Runs on a throwaway thread because taking [voiceLock] can wait
     * for a synthesis in flight, and onTrimMemory is a main-thread callback.
     */
    private fun releaseIdleVoice(reason: String) {
        if (recording || speaker.isPlaying() || speechQueue.isNotEmpty()) return
        thread(name = "trim") {
            synchronized(voiceLock) {
                if (recording || speechQueue.isNotEmpty()) return@synchronized
                if (tts != null) {
                    runCatching { tts?.release() }
                    tts = null
                    loadedVoice = null
                    log("memory pressure ($reason): released the TTS voice — the next message reloads it")
                }
            }
        }
    }

    /**
     * Free the ASR recogniser (~340 MB) when the system says memory is critical. Only when
     * nothing can be using it: not recording, no segment queued, no decode in flight (the
     * tryLock). The next recording rebuilds it — a few seconds slower — which is the honest
     * trade on the phone this app is required to survive.
     */
    private fun releaseAsrUnderPressure() {
        if (recording || asrQueue.isNotEmpty()) return
        if (!asrLock.tryLock()) return
        try {
            if (asr != null) {
                runCatching { asr?.release() }
                asr = null
                log("memory critical: released the ASR recogniser — the next recording reloads it")
            }
        } finally {
            asrLock.unlock()
        }
    }

    /**
     * The engine for [voiceName], loading it if a different voice is currently resident.
     * Caller must hold [voiceLock] — see the field comment for why the lock spans synthesis.
     *
     * Returns null when the voice cannot be loaded; the caller still shows the text.
     */
    private fun ensureVoiceLocked(voiceName: String): TtsEngine? {
        val current = tts
        if (current != null && loadedVoice == voiceName) return current
        runCatching { current?.release() }
        tts = null
        loadedVoice = null
        return try {
            val engine = TtsEngine(this, voiceName, numThreads = 2)
            tts = engine
            loadedVoice = voiceName
            log("TTS voice -> $voiceName")
            engine
        } catch (e: Throwable) {
            log("FAIL: TTS voice $voiceName did not load: ${e.message}")
            null
        }
    }

    // ------------------------------------------------------------------ capture

    // MissingPermission is suppressed because the check is the first thing this method does —
    // lint cannot see through the granted() helper. Every path to AudioRecord() passes it.
    @SuppressLint("MissingPermission")
    private fun startCapture() {
        if (recording) return
        if (!granted(Manifest.permission.RECORD_AUDIO)) {
            setStatus("Microphone permission not granted")
            log("FAIL: RECORD_AUDIO not granted")
            requestPermissions()
            return
        }

        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        audioRecord = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                minBuf * 2,
            )
        } catch (e: Throwable) {
            log("FAIL: AudioRecord creation threw ${e.message}")
            setStatus("Microphone unavailable")
            return
        }

        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            log("FAIL: AudioRecord state=${audioRecord?.state} (expected STATE_INITIALIZED=1)")
            setStatus("Microphone unavailable")
            return
        }
        audioRecord?.startRecording()

        recording = true
        vad?.reset()
        segmentsThisHold = 0
        pttButton.text = getString(R.string.recording)
        setStatus("Recording — speak, then release")
        log("recording started (mic OK, sampleRate=$SAMPLE_RATE)")

        captureThread = thread(name = "capture") {
            // The microphone must win under load. TTS synthesis on the other worker is
            // CPU-hungry; a starved capture thread overflows AudioRecord and the words are gone
            // before any gate can see them. Android's own guidance for audio-capture threads is
            // THREAD_PRIORITY_URGENT_AUDIO.
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
            val buf = ShortArray(BUFFER_SAMPLES)
            var peak = 0.0
            var lastUi = 0L
            while (recording) {
                val n = audioRecord?.read(buf, 0, buf.size) ?: 0
                if (n <= 0) continue
                val samples = FloatArray(n) { buf[it] / 32768.0f }

                // Live level meter — proves the microphone is actually delivering audio.
                // Without this, "nothing happens" is indistinguishable from "mic is dead".
                for (s in samples) peak = maxOf(peak, kotlin.math.abs(s.toDouble()))
                val now = System.currentTimeMillis()
                if (now - lastUi > 120) {
                    val p = peak
                    peak = 0.0
                    lastUi = now
                    val bars = (p * 60).toInt().coerceIn(0, 60)
                    runOnUiThread {
                        // The bar is the glanceable part; the ASCII meter below it is the
                        // diagnostic part, because it shows the numeric peak and that is what
                        // proves the mic is genuinely delivering audio.
                        levelBar.visibility = View.VISIBLE
                        levelBar.setProgressCompat(bars * 100 / 60, true)
                        levelView.text = "mic |" + "#".repeat(bars) +
                            ".".repeat(60 - bars) + "| " + "%.3f".format(p)
                    }
                }

                vad?.accept(samples) { segment -> onSentence(segment) }
            }
        }
    }

    private fun stopCapture() {
        if (!recording) return
        recording = false
        captureThread?.join(400)
        captureThread = null
        audioRecord?.let {
            runCatching { it.stop() }
            runCatching { it.release() }
        }
        audioRecord = null
        pttButton.text = getString(R.string.ptt)
        runOnUiThread {
            levelView.text = ""
            levelBar.visibility = View.INVISIBLE
            levelBar.setProgressCompat(0, false)
        }

        // THE CRITICAL BIT — this was the bug that made speech-to-text appear completely dead.
        // The VAD only emits a segment when it sees ~0.25 s of TRAILING SILENCE. A speaker who
        // talks and immediately releases the button never triggers that, so the buffered audio
        // was silently discarded and no transcript ever appeared. Flushing forces the
        // in-progress segment out.
        val v = vad
        if (v == null) {
            setStatus("Idle")
            return
        }
        setStatus("Finishing…")
        thread(name = "vad-flush") {
            v.flush { segment -> onSentence(segment) }
            log("stopped; VAD flushed, segments this hold = $segmentsThisHold")
        }
    }

    // ------------------------------------------------------------------ the pipeline

    /**
     * One completed sentence, captured by the VAD.
     *
     * The order here matters: recognise, then FILTER, then send. Filtering before sending is what
     * keeps stray CJK/Arabic text from a 1,600-language model out of an Indic emergency message.
     */
    private fun onSentence(segment: FloatArray) {
        segmentsThisHold++
        val n = segmentsThisHold
        log("segment $n: ${segment.size} samples = ${"%.2f".format(segment.size / 16000.0)}s -> queued for recognition")
        asrQueue.offer(segment)
        ensureAsrWorker()
    }

    /**
     * THE SINGLE DECODE WORKER.
     *
     * A segment used to spawn its own "asr" thread. A long hold emits several segments in quick
     * succession, so several decodes ran at once on the same recogniser, finished in an order
     * decided by the scheduler, and the packets went out in that order too — a five-sentence
     * monologue could reach the other phone shuffled. One queue, one worker: decodes and packets
     * follow the order the sentences were spoken, and the CPU only ever runs one decode.
     */
    private fun ensureAsrWorker() {
        if (asrWorker?.isAlive == true) return
        synchronized(this) {
            if (asrWorker?.isAlive == true) return
            asrWorker = thread(name = "asr") {
                while (true) {
                    val segment = try {
                        asrQueue.take()
                    } catch (e: InterruptedException) {
                        return@thread
                    }
                    runCatching { recogniseAndSend(segment) }
                        .onFailure { log("ASR worker error: ${it::class.simpleName}: ${it.message}") }
                    // Honest queue display: a long hold makes several segments, and plain
                    // "Finishing…" left the user guessing whether their last words were still
                    // being worked on. Say so; the next "Sent…" clears it when the queue drains.
                    val behind = asrQueue.size
                    if (behind > 0) {
                        log("$behind segment(s) still queued for recognition")
                        runOnUiThread { setStatus("Transcribing $behind more…") }
                    }
                }
            }
        }
    }

    /** One segment, start to finish: recognise, gate, filter, hand to the outbox. */
    private fun recogniseAndSend(segment: FloatArray) {
        if (asr == null) {
            // The recogniser is not resident: either the models are still loading, or memory
            // pressure released it (see onTrimMemory). Rebuilding takes seconds; discarding the
            // words takes none and loses a sentence — the one thing this app must not do.
            log("recogniser not resident — reloading for ${segment.size} samples")
        }
        val t0 = System.currentTimeMillis()
        // Tokens as well as text. The gate's repetition-loop check runs on the decoder's own
        // token list, and this call used to pass an empty one — the check was inert in the live
        // path. eval/probe_loopcheck_tokens.py measured what that missed (a degenerate
        // "ख      0 f 1" decode was transmitted as-is) and what the exemption protects
        // (numbers like 100000 are not loops). QualityGateTest pins both halves.
        //
        // The decode holds asrLock for its whole duration, so a memory-trim release cannot free
        // the native recogniser mid-call — the same discipline voiceLock applies to TTS.
        val (raw, asrTokens) = try {
            asrLock.withLock {
                val engine = asr ?: AsrEngine(assets, numThreads = 2).also {
                    asr = it
                    log("recogniser reloaded — decoding the held segment")
                }
                engine.transcribeWithTokens(segment)
            }
        } catch (e: Throwable) {
            log("FAIL: recogniser unavailable (${e::class.simpleName}: ${e.message}) — segment NOT recognised")
            runOnUiThread {
                setStatus("Could not read that — say it again")
                append(
                    "⚠ ${"%.1f".format(segment.size / 16000f)} s of speech could not be read " +
                        "(recogniser failed to load) — nothing was sent"
                )
            }
            return
        }
        val ms = System.currentTimeMillis() - t0
        val seconds = segment.size / 16000f
        val level = QualityGate.rms(segment)
        log("ASR ${ms}ms -> \"${raw.take(48)}\"")
        log("  segment ${"%.2f".format(seconds)}s  rms ${"%.5f".format(level)}")

        // Physical gates first, and before the ASR even matters: a 0.31 s breath or a door
        // slam is not a sentence, and rejecting it here costs no decode and no user patience.
        // Measured on this app's own VAD output, such segments arrive with an RMS as low as
        // 0.00008 and decode to the empty string every time.
        //
        // langTag = null IS THE FIX OF 15 OCT 2026. This used to pass the phone's default
        // tag (hin_Deva), and that made the purity check reject every Latin-English
        // transcript — expectedScriptPurity(English, hin_Deva) is 0.0, below the 0.75 floor —
        // so English speech was answered with "say it again" and never sent at all. With null,
        // purity asks the same question the filter below asks: is every letter inside one of
        // the nine allowed scripts. Pinned by tests in QualityGateTest.
        val gate = QualityGate.evaluate(
            text = raw,
            tokens = asrTokens,
            audioSeconds = seconds,
            rms = level,
            langTag = null,
            // Script purity is checked below by the zero-tolerance filter; letting the gate
            // duplicate it here would only add a second, softer verdict on the same evidence.
            strictScript = false,
        )
        when (gate.verdict) {
            QualityGate.Verdict.SILENT -> {
                log("SILENT (${gate.reason}) — ${"%.2f".format(seconds)}s rms=${"%.5f".format(level)}")
                return
            }
            QualityGate.Verdict.REPEAT -> {
                log("REPEAT (${gate.reason}) — asking the sender to say it again")
                runOnUiThread { setStatus(QualityGate.repeatPrompt()) }
                return
            }
            QualityGate.Verdict.SEND -> Unit
        }

        if (raw.isBlank()) {
            log("ASR returned empty — nothing to send")
            return
        }

        // THE LANGUAGE OF THIS SEGMENT, inferred instead of assumed.
        //
        // There is no language selector in this build, and `langTag` is only the phone's
        // default. It used to be the language of every message: Latin-English passed the
        // filter anyway (Latin is allowed for loanwords), was sent tagged hin_Deva, and was
        // read aloud by the HINDI voice on the other phone — the "broken English" report.
        //
        // Measured 15 Oct 2026 with the shipped pruned model: English speech is transcribed
        // Latin 22/22 clips and Hindi speech Devanagari 6/6 (eval/probe_en_script.py), so the
        // transcript's own script is a reliable signal for which voice should speak it.
        val spoken = LanguageFilter.tagForScript(raw) ?: langTag
        if (spoken != langTag) log("language $langTag -> $spoken (inferred from the transcript)")

        // The filter no longer enforces one expected alphabet: without a selector there is no
        // honest expectation to enforce, and enforcing Hindi is what silently mislabelled
        // every other language. It still drops a segment whole when a letter falls outside
        // all nine scripts, which is the leak it was built for ("help مe" and friends).
        // TRADE-OFF, deliberate and recorded in docs/MODEL_AND_TTS_FINDINGS.md: a Hindi
        // segment that a noisy decode flips into another Indic script is no longer dropped
        // by the sender — it travels tagged with the script it came back in.
        val verdict = LanguageFilter.filter(raw, null)
        if (!verdict.send) {
            // Name the offending characters. Without them this is unactionable: "dropped"
            // alone does not distinguish a model failure from a filter bug, and the whole
            // "hello -> هello" class of problem was invisible for exactly that reason.
            val culprits = LanguageFilter.foreignLetters(raw, null)
                .map { "%s(U+%04X)".format(it, it.code) }
                .joinToString(" ")
            log(
                "DROPPED whole segment: ${verdict.removed} foreign char(s) " +
                    "[$culprits] fraction=${verdict.fraction}"
            )
            // Tell the sender to repeat. Silence with no explanation reads as a broken app.
            runOnUiThread { setStatus(QualityGate.repeatPrompt()) }
            return
        }

        val msg = Message(
            // The inferred language, not the phone's default — this field is what picks the
            // voice (and the script check) on the receiving side.
            lang = spoken,
            type = Message.Type.MSG,
            seq = seq.getAndIncrement(),
            text = verdict.text,
        )
        val bytes = msg.toBytes()

        // NOT send(): the outbox sends when the link is up and BUFFERS in order when it is
        // down, and the transport's CONNECTED callback flushes the backlog. This is what makes
        // "it should not drop" true — the sentence survives a walk out of range.
        val sentNow = outbox.enqueue(bytes)
        // NOW KEEP ASKING UNTIL THE OTHER PHONE CONFIRMS.
        // The outbox covers a link that is down. This covers the quieter failure: a write that
        // "succeeded" into a socket that was already dying. The message is resent (bounded,
        // inside its TTL) until a receipt comes back, and the receiving phone speaks a duplicate
        // at most once — its UUID ledger.
        delivery.track(msg.id, bytes, deliveryDeadline(msg))
        // On disk before anything else can go wrong: from here on, a process kill is recoverable.
        persistPending()
        log("packet ${bytes.size} B — ${if (sentNow) "sent" else "link down, buffered ($outbox)"}")
        runOnUiThread {
            append("▸ ${verdict.text}")
            setStatus(
                if (sentNow) "Sent ${bytes.size} B"
                else "Buffered ${bytes.size} B — link down; sends when the other phone is back"
            )
        }
    }

    /**
     * Monotonic-clock value after which [msg]'s receipt is no longer worth chasing. The message's
     * TTL is wall-clock seconds; retries run on the monotonic clock, so the remaining TTL is
     * converted here, once.
     */
    private fun deliveryDeadline(msg: Message): Long {
        if (msg.ttl <= 0) return Long.MAX_VALUE
        val ageSeconds = System.currentTimeMillis() / 1000 - msg.ts
        val remainingSeconds = msg.ttl - ageSeconds
        return if (remainingSeconds <= 0) SystemClock.elapsedRealtime()
        else SystemClock.elapsedRealtime() + remainingSeconds * 1000
    }

    /**
     * One tick of the delivery ticker. Resends unconfirmed messages through the outbox — ordered,
     * exactly like first sends — and abandons (loudly) the ones whose TTL or attempt budget ran
     * out. Does nothing while the link is down: the outbox's flush covers reconnects.
     */
    private fun tickDeliveries() {
        if (destroyed) return
        val t = transport ?: return
        if (!t.isConnected) return
        val due = delivery.collectDue()
        for (id in due.gaveUp) {
            log(
                "✗ no receipt for ${id.take(8)} after ${DeliveryTracker.MAX_ATTEMPTS} tries — " +
                    "abandoned (the other phone never confirmed)"
            )
        }
        for (o in due.outgoing) {
            val sent = outbox.enqueue(o.bytes)
            log(
                "no receipt for ${o.id.take(8)} yet — resend ${o.attempt}/" +
                    "${DeliveryTracker.MAX_ATTEMPTS}" +
                    if (sent) "" else " (buffered — link dropped again)"
            )
        }
        // Attempt counts (and abandonments) changed — the disk copy must match, or a kill would
        // restart the schedule from stale state.
        if (due.gaveUp.isNotEmpty() || due.outgoing.isNotEmpty()) persistPending()
    }

    /**
     * Write the pending work to disk: buffered packets, unconfirmed messages, heard ids and the
     * Settings counters. Called on every change — send, receipt, resend, flush, first sight of a
     * message — rather than periodically, because the whole point is that a process kill cannot
     * lose the change. The file is a few kilobytes; the write is measured in milliseconds.
     *
     * Two clocks, deliberately: in RAM the tracker runs on [SystemClock.elapsedRealtime] (which
     * a reboot resets), on disk deadlines are wall-clock epochs (which survive reboots). The
     * conversion happens here, once, in both directions — a restored message gets back exactly
     * the lifetime it had left, neither a fresh TTL nor an instant death.
     */
    private fun persistPending() {
        try {
            val nowElapsed = SystemClock.elapsedRealtime()
            val nowWall = System.currentTimeMillis()
            pendingStore.save(
                PendingStore.Snapshot(
                    outbox = outbox.snapshot(),
                    tracked = delivery.snapshot().map { e ->
                        PendingStore.Tracked(
                            id = e.id,
                            bytes = e.bytes,
                            wallDeadlineMs =
                                if (e.deadlineMs == Long.MAX_VALUE) Long.MAX_VALUE
                                else nowWall + (e.deadlineMs - nowElapsed),
                            attempts = e.attempts,
                            delayMs = e.delayMs,
                        )
                    },
                    seen = ledger.snapshot(),
                    delivered = delivery.delivered,
                    gaveUp = delivery.gaveUp,
                )
            )
        } catch (e: Throwable) {
            // Persistence must never break sending. Worst case, the next launch forgets the
            // pending work and the log says so.
            log("FAIL: could not save pending work: ${e.message}")
        }
    }

    /**
     * Put back what the last session left behind (see [persistPending]). Called once at startup,
     * before the transport exists; nothing is sent from here — the packets wait in the outbox
     * and flush, in order, the moment a link comes up. Restored retries keep their attempt
     * counts and deadlines, so a message whose time ran out while the app was dead is abandoned
     * loudly on the first tick, exactly as if the app had stayed alive.
     */
    private fun restorePending() {
        val snap = try { pendingStore.load() } catch (e: Throwable) { null } ?: return
        if (snap.outbox.isEmpty() && snap.tracked.isEmpty() && snap.seen.isEmpty()) return
        outbox.restore(snap.outbox)
        val nowElapsed = SystemClock.elapsedRealtime()
        val nowWall = System.currentTimeMillis()
        delivery.restore(
            snap.tracked.map { t ->
                DeliveryTracker.EntryView(
                    id = t.id,
                    bytes = t.bytes,
                    deadlineMs =
                        if (t.wallDeadlineMs == Long.MAX_VALUE) Long.MAX_VALUE
                        else nowElapsed + (t.wallDeadlineMs - nowWall),
                    attempts = t.attempts,
                    delayMs = t.delayMs,
                )
            }
        )
        delivery.restoreCounters(snap.delivered, snap.gaveUp)
        ledger.restore(snap.seen)
        log(
            "restored from the last session: ${snap.outbox.size} buffered packet(s), " +
                "${snap.tracked.size} awaiting receipt, ${snap.seen.size} id(s) remembered"
        )
    }

    /**
     * Tell the sender this message arrived — sent straight away, before the duplicate and expiry
     * checks: those decide whether to *speak*, and a receipt is about arrival. A duplicate is
     * confirmed again on purpose, because the sender may be retrying precisely because the first
     * receipt was lost.
     */
    private fun sendReceipt(id: String) {
        val receipt = Message(
            lang = "und",
            type = Message.Type.ACK,
            seq = 0,
            ttl = 0,
            text = "",
            ackFor = id,
        )
        if (transport?.send(receipt.toBytes()) != true) {
            Log.w(TAG, "receipt for ${id.take(8)} could not be sent — the sender will retry")
        }
    }

    /**
     * One buzz when an ALERT lands. Sound is the message; the buzz is so the phone is noticed
     * even in a pocket or a loud room, and it fires at receive time — not when the alert's turn
     * to speak comes — so holding the phone is not a prerequisite for feeling it.
     *
     * Best-effort by design: no vibrator, a platform refusal or a silent-mode policy must never
     * change what the app does with the alert itself.
     */
    private fun vibrateAlert() {
        try {
            val vib = if (Build.VERSION.SDK_INT >= 31) {
                (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)
                    ?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            } ?: return
            // Two strong pulses then a long one: felt through cloth, hard to mistake for a
            // notification tick.
            val pattern = longArrayOf(0, 400, 250, 400, 250, 700)
            if (Build.VERSION.SDK_INT >= 26) {
                vib.vibrate(VibrationEffect.createWaveform(pattern, -1))
            } else {
                @Suppress("DEPRECATION")
                vib.vibrate(pattern, -1)
            }
        } catch (e: RuntimeException) {
            Log.w(TAG, "alert vibration failed: ${e.message} — the spoken alert still happens")
        }
    }

    /** Called by the transport when a packet arrives on the LISTENER phone. */
    private fun onPacket(bytes: ByteArray) {
        val msg = Message.fromBytes(bytes) ?: run {
            Log.w(TAG, "dropped malformed packet")
            return
        }

        // RECEIPTS ARE LINK HOUSEKEEPING, NOT SPEECH. Handle and return before the ledger,
        // before expiry, before the speech queue.
        if (msg.type == Message.Type.ACK) {
            val id = msg.ackFor
            if (id == null) {
                Log.w(TAG, "receipt without ackFor — ignored")
                return
            }
            val resends = delivery.ack(id)
            if (resends >= 0) {
                log(
                    "✓ delivered ${id.take(8)} confirmed by the other phone" +
                        if (resends > 0) " (after $resends resend(s))" else ""
                )
                // Make the proof visible, not just logged: the banner is what a judge reads.
                val note = if (resends > 0) " (after $resends resend(s))" else ""
                runOnUiThread { setStatus("✓ Delivered${note}") }
                // The confirmed message is out of the tracker now; the disk copy must agree,
                // or a restart would resend something already confirmed.
                persistPending()
            }
            return
        }

        // CONFIRM ARRIVAL BEFORE ANYTHING ELSE. This is the proof the sender is waiting for. It
        // says nothing about comprehension — the transcript still distinguishes the two — which
        // is exactly why it is sent before playback rather than after it.
        sendReceipt(msg.id)

        if (!ledger.accept(msg)) {
            Log.i(TAG, "duplicate ${msg.id} — not replaying")
            return
        }
        // The id must survive a kill too: otherwise a resend whose receipt was lost would replay
        // a message the human already heard.
        persistPending()

        // A stale message must not be announced as a live alert.
        if (!ledger.isFresh(msg)) {
            runOnUiThread { append("▸ ${msg.text}   (stale, not announced)") }
            return
        }

        // ALERT gets its buzz here — after the duplicate and freshness gates (a resend of
        // something already felt and spoken must not buzz again), before the speaker (a phone in
        // a pocket feels it even while other audio is still talking).
        if (msg.type == Message.Type.ALERT) vibrateAlert()

        // AN EXPLICIT ALERT TAKES THE SPEAKER IMMEDIATELY.
        //
        // Normal messages no longer interrupt one another — the worker below speaks them to
        // completion, in arrival order — but an alert is the one message type allowed to cut in,
        // exactly as before this change. (Triage escalations are decided on the worker, after
        // this check, and wait their turn.)
        if (msg.type == Message.Type.ALERT && speaker.isPlaying()) {
            log("ALERT ${msg.id.take(8)} — preempts current playback")
            speaker.stop()
        }
        // AN ALERT ALSO JUMPS THE QUEUE. Stopping the current track is not enough when the queue
        // already holds sentences not yet spoken, and the worker can be mid-synthesis on another
        // message (then there is nothing to stop — seconds of work with no audio). A distress
        // message must not wait its turn behind chatter. Head insertion, under the queue's
        // monitor so it cannot interleave with the worker's head surgery (see SpeechQueueOrder);
        // once spoken, the queued sentences continue in their original order.
        SpeechQueueOrder.enqueue(speechQueue, msg)
        ensureSpeechWorker()
    }

    /**
     * THE SINGLE SPEECH WORKER.
     *
     * Every received packet used to spawn its own "tts" thread: two messages arriving close
     * together synthesised concurrently, played concurrently, and Speaker.play() stops whatever
     * is playing — so the second sentence cut off the first mid-word, and the playback order
     * depended on which thread won the synthesis lock. That is what "I spoke a lot and it does
     * not travel" was on the receiving end: the segments of one long hold arrived as a burst and
     * the phone spoke fragments over each other. One queue, one worker, arrival order, and a
     * normal message is heard to its end before the next one starts.
     */
    private fun ensureSpeechWorker() {
        if (speechWorker?.isAlive == true) return
        synchronized(this) {
            if (speechWorker?.isAlive == true) return
            speechWorker = thread(name = "tts") {
                while (true) {
                    val msg = try {
                        speechQueue.take()
                    } catch (e: InterruptedException) {
                        return@thread
                    }
                    runCatching { speakOne(msg) }
                        .onFailure { log("speech worker error: ${it::class.simpleName}: ${it.message}") }
                }
            }
        }
    }

    /**
     * USE THE PLAYBACK TIME. Synthesising the next message takes a second or two and plays no
     * sound; done only after the current message finishes, that synthesis is dead air between
     * two sentences — exactly what a long hold turned into on the receiving phone ("I spoke five
     * sentences and heard them one at a time"). This is called from the playback wait loop, so
     * the work happens under the current message's audio and the gap collapses to nothing.
     *
     * Safety rails, all checked here or at consumption:
     *  - only the next queued message, never an ALERT (alerts synthesise at their turn, above
     *    any queue position, and are never delayed for this);
     *  - only with the voice that is already resident — prefetching across a language boundary
     *    would force a ~60 MB voice churn for a message nobody is hearing yet;
     *  - only into an empty slot: one piece of audio is enough to keep the queue fed;
     *  - the slot is keyed by message id, so an alert arriving mid-prefetch simply leaves it
     *    untouched (it is still this message's audio) and a message that is no longer next is
     *    dropped, not spoken (see speakOne).
     */
    private fun maybePrefetchNext() {
        if (prefetched != null) return
        val next = speechQueue.peekFirst() ?: return
        if (next.type == Message.Type.ALERT) return
        synchronized(voiceLock) {
            val voice =
                TtsEngine.voiceFor(LanguageFilter.voiceTagFor(next.lang, next.text)) ?: return
            if (loadedVoice != voice) return
            val engine = ensureVoiceLocked(voice) ?: return
            prefetched = Prefetched(next.id, voice, engine.synthesise(next.text), engine.sampleRate())
        }
        log("speech: pre-synthesised ${next.id.take(8)} while the previous message plays")
    }

    /** Synthesise and play one message. Runs on the single "tts" worker thread. */
    private fun speakOne(msg: Message) {
        // WHICH VOICE SPEAKS THIS MESSAGE.
        //
        // Until 15 Oct 2026 the answer was "whatever voice this phone loaded at startup" — its
        // own default language — and the message's `lang` field was never consulted here. English
        // text was therefore read by the Hindi voice: measured similarity 0.76 on a short
        // sentence, and "the bridge has collapsed please send help to the school" came back
        // through our own ASR as "अबिद्ज हज कलापस तपली संडहल्पतर सकूल". The message now chooses
        // the voice. [LanguageFilter.voiceTagFor] keeps the sender's tag when the text agrees
        // with it (a mar_Deva tag on Devanagari text stays Marathi) and follows the text's own
        // script when it does not. The engine is swapped on this worker, never on the UI thread.
        val spokenTag = LanguageFilter.voiceTagFor(msg.lang, msg.text)
        if (spokenTag != msg.lang) log("voice ${msg.lang} -> $spokenTag (from the message script)")
        val wantVoice = TtsEngine.voiceFor(spokenTag)

        // The optional triage read happens here — on the worker that is about to speak, never
        // on the audio thread. It cannot suppress or edit the message; the text is spoken
        // either way. Its only power is to raise a NORMAL announcement to an ALERT, and only
        // above the measured confidence floor (see LayaTriage).
        val triage = if (triageEnabled) laya?.decide(msg.text) else null
        val escalate = triage?.escalate == true
        if (triage != null) log("triage ${msg.id.take(8)}: ${triage.describe()}")

        // PREFETCHED AUDIO IS USED WHEN IT IS THIS MESSAGE AND THE CORRECT VOICE. The worker
        // built it while the previous message was still being heard, so a burst of sentences
        // now plays without a synthesis pause between them. Anything else synthesises here,
        // exactly as before; a slot for a message that is no longer next is dropped.
        val pf = prefetched
        val spoken = if (pf != null && pf.id == msg.id && pf.voiceName == wantVoice) {
            log("speech ${msg.id.take(8)}: already synthesised — playing immediately")
            Pair(pf.samples, pf.sampleRate)
        } else {
            if (pf != null) prefetched = null
            // Load (if needed) and synthesise under one lock. A language change between two quick
            // messages must not release the engine another path is mid-synthesis on.
            synchronized(voiceLock) {
                val engine = wantVoice?.let { ensureVoiceLocked(it) }
                if (engine == null) null
                else Pair(engine.synthesise(msg.text), engine.sampleRate())
            }
        }
        if (spoken == null) {
            // No bundled voice, or the load failed. Text is still shown, and never read by an
            // unrelated voice just because one happens to be loaded.
            val why =
                if (wantVoice == null) "no bundled voice for $spokenTag"
                else "voice $wantVoice failed to load"
            log("receive ${msg.id.take(8)}: $why — text only")
            runOnUiThread { append("▸ ${msg.text}   ($why)") }
            ledger.markPlayed(msg)
            return
        }
        val (samples, sampleRate) = spoken

        // AN ALERT THAT ARRIVED WHILE SYNTHESISING GOES FIRST.
        //
        // Voice load and synthesis take seconds, and during that window there is nothing playing
        // for an alert to interrupt. Alerts enter at the head and this worker is the only
        // consumer, so an alert waiting ahead NOW must have arrived while this message was being
        // prepared — and nothing of it has been heard yet, so it can step aside. It is re-queued
        // directly behind the alerts and ahead of every later normal message. Not played, not
        // marked played; the worker picks it up again after the alert has spoken.
        if (SpeechQueueOrder.yieldBehindAlerts(speechQueue, msg)) {
            log("speech ${msg.id.take(8)}: alert arrived during synthesis — speaking it first")
            // The prefetch (this message's own audio, most likely) stays in its slot: when the
            // worker comes back to this message after the alert, it is still valid. That is the
            // point of keeping it keyed by id.
            return
        }

        // Past the yield check this message is definitely next to play; a prefetched copy of it
        // (if any) has just become the audio below, and the slot is free for the following
        // message's prefetch.
        prefetched = null

        val mode =
            if (msg.type == Message.Type.ALERT || escalate) Speaker.Mode.ALERT else Speaker.Mode.NORMAL
        // A TRIAGE-ESCALATED message is spoken as an alert (loops, full volume — see LayaTriage),
        // so it must be FELT like one too. The receive-time buzz in onPacket covers wire-typed
        // ALERTs; this covers the escalations, which — with no ALERT-send UI on the phone — are
        // the alerts a real user actually produces. No double buzz: the wire ALERT case is
        // already covered at receive time.
        if (escalate && msg.type != Message.Type.ALERT) vibrateAlert()
        runOnUiThread {
            val note = if (triage == null) "" else
                "   [${triage.label} ${(triage.confidence * 100).toInt()}%]"
            append((if (mode == Speaker.Mode.ALERT) "🔴 " else "▸ ") + msg.text + note)
            setStatus(
                if (escalate && msg.type != Message.Type.ALERT) "Speaking (ALERT — triage)"
                else "Speaking (${msg.type})",
            )
        }
        // Play from here, not from the UI thread: AudioTrack's blocking write holds its thread
        // for the length of the clip, and that has no business on the main thread.
        //
        // PLAY IN PIECES WHEN THE CLIP IS LONG. MODE_STATIC allocates the whole clip as one
        // buffer, and a 30 s VAD segment at 22 kHz float is ~2.6 MB — a track that large can
        // fail to build on a low-RAM phone, and the failure mode is a message shown but never
        // heard. Above [MAX_PIECE_SAMPLES] the clip is played as back-to-back pieces on this same
        // worker. Alerts are never split: their loop points need the single track.
        val pieces = if (mode == Speaker.Mode.ALERT || samples.size <= MAX_PIECE_SAMPLES) 1
        else (samples.size + MAX_PIECE_SAMPLES - 1) / MAX_PIECE_SAMPLES
        for (i in 0 until pieces) {
            val piece = if (pieces == 1) samples else samples.copyOfRange(
                i * MAX_PIECE_SAMPLES,
                minOf((i + 1) * MAX_PIECE_SAMPLES, samples.size),
            )
            val generation = speaker.generation()
            speaker.play(piece, sampleRate, mode)
            // An alert loops on purpose: the next message may replace it (existing behaviour,
            // kept), so there is nothing to wait for.
            if (mode == Speaker.Mode.ALERT) break

            // WAIT UNTIL THIS PIECE HAS BEEN HEARD.
            //
            // This is the actual fix for the cut-off: the next queued sentence starts only after
            // this clip has finished playing. The cap protects the queue if the track's playState
            // ever sticks: piece length + 2 s, then move on. A preempting alert calls
            // speaker.stop(), which both ends this wait (isPlaying false) and bumps the stop
            // generation checked below.
            val maxWaitMs = piece.size * 1000L / sampleRate + 2_000L
            val started = SystemClock.elapsedRealtime()
            while (speaker.isPlaying() && SystemClock.elapsedRealtime() - started < maxWaitMs) {
                // The wait is otherwise idle: get the next message's audio ready now, under
                // this message's playback (see maybePrefetchNext). A no-op when there is
                // nothing safe to build — including the very common "queue is empty" case.
                maybePrefetchNext()
                try {
                    Thread.sleep(40)
                } catch (e: InterruptedException) {
                    return
                }
            }
            // An external stop — ALERT preemption, shutdown — cancels the rest of the sequence.
            // Without this, an alert that seized the speaker mid-message would be spoken only
            // after we had ploughed through every remaining piece.
            if (speaker.generation() != generation) return
        }
        // The message has been heard to its end. A hard stop releases the track buffer — real
        // memory on a low-RAM phone — and hands audio focus back to whatever ducked for us;
        // without it the old track and the duck lingered until the next message replaced them.
        // Alerts are skipped: their loop is the point, and it ends when the next message seizes
        // the speaker.
        if (mode == Speaker.Mode.NORMAL) speaker.stop()
        ledger.markPlayed(msg)
    }

    // ------------------------------------------------------------------ controls

    /**
     * SETTINGS. A live, scrollable view of everything the app is doing, plus the actions that used
     * to be buried in the device picker.
     *
     * The on-screen strip is capped at 14 lines and cannot be scrolled, which is fine for glancing
     * at and useless for diagnosing. This is the version you can actually read.
     */
    /** "1.0.0 (release)" — read from the installed package so it always matches the APK. */
    @Suppress("DEPRECATION")    // getPackageInfo(String, Int); minSdk is 23, the flags overload is 33+
    private fun appVersionLine(): String = try {
        val v = packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
        val debug = (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
        "$v (${if (debug) "debug" else "release"})"
    } catch (e: Exception) {
        "unknown"
    }

    private fun showSettings() {
        val t = transport
        val status = buildString {
            append("Connection: ")
            if (t?.isConnected == true) {
                append("connected to ${t.connectedPeerName}")
            } else {
                append("not connected")
            }
            append("\nTransport:  ${kind.displayName}")
            append("\nThis phone: ${Build.MANUFACTURER} ${Build.MODEL} (API ${Build.VERSION.SDK_INT})")
            append("\nApp:        ${appVersionLine()}")
            append("\nLog file:   ${FileLogger.path() ?: "not initialised"}")
            append("\nTriage:     ")
            append(if (triageEnabled) "on — ${laya?.state ?: "idle"} (Laya, experimental)" else "off")
            append("\nOutbox:     ")
            append("${outbox.depth} waiting, ${outbox.dropped} dropped")
            append("\nDelivery:   ")
            append(
                "${delivery.waiting} awaiting receipt, ${delivery.delivered} confirmed, " +
                    "${delivery.gaveUp} abandoned"
            )
            append("\nLink RTT:   ")
            val rtt = t?.lastHeartbeatRttMs ?: -1L
            append(if (rtt > 0) "$rtt ms (last heartbeat)" else "not measured yet")
        }

        val logView = android.widget.TextView(this).apply {
            text = liveLog.joinToString("\n")
            textSize = 9f
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(28, 28, 28, 28)
            setTextIsSelectable(true)
        }
        val scroll = android.widget.ScrollView(this).apply { addView(logView) }

        // The triage switch lives here rather than in its own screen: it is one decision, and the
        // state it produces is printed in the status above it. It sits *outside* the ScrollView,
        // so it stays reachable whatever the log length (up to LIVE_LOG_MAX lines).
        val toggle = android.widget.CheckBox(this).apply {
            text = "Triage received messages (Laya, experimental)"
            isChecked = triageEnabled
            textSize = 12f
            setPadding(28, 10, 28, 10)
            setOnCheckedChangeListener { _, on -> setTriage(on) }
        }

        val body = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            addView(toggle)
            addView(scroll)
        }

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Settings")
            .setMessage(status)
            .setView(body)
            .setPositiveButton("Share log") { _, _ -> shareLog() }
            .setNeutralButton("Clear log") { _, _ ->
                liveLog.clear()
                FileLogger.clear()
                log("log cleared")
            }
            .setNegativeButton("Close", null)
            .show()
    }

    /**
     * The triage layer, created on first need. `prepare()` never blocks: it starts a background
     * thread that copies the model out of the APK (once) and opens both ORT sessions, so the
     * first received message is already served by a warm layer.
     */
    private fun ensureTriage(): LayaTriage {
        val existing = laya
        val t = if (existing != null && !existing.isClosed) existing
        else LayaTriage(applicationContext) { s -> log("triage: $s") }.also { laya = it }
        t.prepare()
        return t
    }

    /**
     * The Settings toggle, persisted — re-enabling it after every restart during a test session
     * is a nuisance, and the state is visible in the same dialog.
     */
    private fun setTriage(enabled: Boolean) {
        triageEnabled = enabled
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_LAYA_TRIAGE, enabled).apply()
        if (enabled) {
            ensureTriage()
            log("triage: on — 143 MB of assets are copied to app storage on first use")
        } else {
            runCatching { laya?.close() }
            laya = null
            log("triage: off")
        }
    }

    /**
     * The device picker. This was the missing piece — there was previously no way to see what
     * devices existed, no way to choose one, and no indication of whether anything was connected.
     */
    private fun showDevicePicker() {
        val t = transport
        if (t == null) {
            log("transport not started yet — wait for models to finish loading")
            return
        }

        val peers = t.availablePeers()
        log("device picker: ${peers.size} peer(s) available via ${t.name}")

        if (peers.isEmpty()) {
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("No devices found")
                .setMessage(
                    "Nothing paired yet.\n\n" +
                        "1.  Turn Bluetooth on, on both phones.\n" +
                        "2.  Pair them in Android Settings → Bluetooth.\n" +
                        "3.  Come back here and tap the other phone.\n\n" +
                        "Transport: ${kind.displayName}"
                )
                .setPositiveButton("OK", null)
                .show()
            return
        }

        val labels = peers.map {
            if (it.connected) "● ${it.name}   (connected)" else "○ ${it.name}"
        }

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Connect to")
            .setItems(labels.toTypedArray()) { _, which ->
                val chosen = peers[which]
                log("connecting to ${chosen.name} (${chosen.id})")
                connectTransport(chosen.id)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Hand the log file to any app that can carry it — mail, Drive, WhatsApp, a USB file manager. */
    private fun shareLog() {
        val path = FileLogger.path()
        if (path == null) {
            log("no log file — logging never initialised")
            return
        }
        val f = java.io.File(path)
        if (!f.exists()) {
            log("log file does not exist yet: $path")
            return
        }
        try {
            val uri = androidx.core.content.FileProvider.getUriForFile(
                this, "$packageName.fileprovider", f,
            )
            val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(android.content.Intent.EXTRA_STREAM, uri)
                putExtra(android.content.Intent.EXTRA_SUBJECT, "iTantra log")
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(android.content.Intent.createChooser(send, "Share iTantra log"))
            log("sharing $path")
        } catch (e: Throwable) {
            log("FAIL: could not share log — ${e.message}")
        }
    }

    /** Keeps the big connection banner in sync with reality. */
    private fun updateConnectionIndicator() {
        val t = transport
        val peer = t?.connectedPeerName
        val connected = t?.isConnected == true && peer != null
        runOnUiThread {
            connectionView.text = if (connected) {
                getString(R.string.connected_to, peer)
            } else {
                getString(R.string.not_connected)
            }
            // A coloured dot next to the text, so link state is readable at a glance from
            // across a table during a demo. Green = linked, grey = not.
            val dotColour = if (connected) {
                R.color.md_primary
            } else {
                R.color.md_outline
            }
            connectionDot.background?.mutate()?.setTint(getColor(dotColour))
        }
    }

    /**
     * Open the link.
     *
     * NO ROLE DECIDES THIS ANY MORE. Both phones start out listening, so both are reachable.
     * Tapping a device in DEVICES connects to it; the other phone is already accepting, so the
     * link comes up without anyone having to agree on who is "the speaker".
     *
     * Once connected the link is fully bidirectional — either phone can talk, and only the
     * push-to-talk button decides who has the floor at any moment.
     */
    private fun connectTransport(peerId: String? = null) {
        // STOP THE OLD TRANSPORT FIRST.
        //
        // This used to just build a new one, which left the previous instance running — its
        // server socket still open and still accepting. Tapping a device therefore ended up with
        // one transport listening and another connecting, and the stale one was never cleaned up.
        runCatching { transport?.stop() }
        transport = null
        reconnectAttempts = 0
        reconnectPending = false
        reconnectHandler.removeCallbacksAndMessages(null)

        val t = kind.create(applicationContext).also { transport = it }
        t.onMessage = { bytes -> onPacket(bytes) }
        t.onStateChange = { state, detail ->
            runOnUiThread { setStatus("$state — $detail") }
            updateConnectionIndicator()
            onTransportState(t, state)
        }
        // ALWAYS LISTEN, AND ALSO DIAL IF ASKED.
        //
        // These are not either/or. If the app only ever listened *or* only ever dialled, then two
        // users tapping each other's phone at the same moment would both stop listening and both
        // try to dial — and neither would answer. BlueComm, the reference implementation of the
        // SDP fallback, always starts its server and additionally connects, which removes that
        // deadlock entirely. The first side to succeed wins; the transport drops out of the other
        // role as soon as isConnected flips.
        log("listening for the other phone")
        t.startListening()
        if (peerId != null) {
            lastDialedPeerId = peerId
            log("also dialling the chosen device")
            t.connectToPeer(peerId)
        }
        updateConnectionIndicator()
    }

    /**
     * One place where link state turns into behaviour.
     *
     * CONNECTED: flush the outbox — every sentence spoken while the link was down goes out now,
     * in order — and reset the reconnect backoff.
     *
     * IDLE/ERROR: schedule a reconnect if this link is worth chasing (it existed before, or the
     * user explicitly dialled this peer). The listener side also heals itself inside the
     * transport (the accept loop restarts the moment a session drops), so whichever side knows
     * the peer address drives the recovery.
     */
    private fun onTransportState(t: Transport, state: Transport.State) {
        when (state) {
            Transport.State.CONNECTED -> {
                everConnected = true
                reconnectAttempts = 0
                reconnectPending = false
                reconnectHandler.removeCallbacksAndMessages(null)
                val flushed = outbox.flush()
                if (flushed > 0) log("link up — flushed $flushed packet(s) buffered while down")
                // Give the flush's packets one retry interval before the ticker may resend
                // anything; without this the first tick after a reconnect would duplicate
                // everything the flush has this instant put on the wire.
                delivery.onTransportUp()
                // The flush drained the outbox — the disk copy must match immediately, so a kill
                // in this moment cannot resurrect packets that are already on the wire.
                persistPending()
            }
            Transport.State.IDLE, Transport.State.ERROR -> scheduleReconnect(t, state)
            else -> Unit
        }
    }

    /**
     * Reconnect with backoff: 2 s, 5 s, 10 s, then every 15 s, reset on success.
     *
     * WHY: a dropped link used to be terminal. The transport reported IDLE and nothing ever
     * touched it again — the only way back was the human tapping DEVICES and choosing the phone
     * by hand, mid-emergency. It is also why a message spoken while walking out of range simply
     * vanished: send() had nowhere to go. The outbox holds those sentences; this brings the link
     * back so they can actually arrive.
     */
    private fun scheduleReconnect(t: Transport, state: Transport.State) {
        if (destroyed) return
        // One pending reconnect at a time: a single link death can report ERROR (heartbeat or
        // write failure) and then IDLE (reader unwind) milliseconds apart, and without this the
        // second report cancelled the first timer and burned a backoff step.
        if (reconnectPending) return
        val peer = lastDialedPeerId
        if (peer == null && !everConnected) return
        val delayMs = when (reconnectAttempts) {
            0 -> 2_000L
            1 -> 5_000L
            2 -> 10_000L
            else -> 15_000L
        }
        reconnectAttempts++
        reconnectPending = true
        log("link $state — reconnect attempt $reconnectAttempts in ${delayMs / 1000}s")
        reconnectHandler.removeCallbacksAndMessages(null)
        reconnectHandler.postDelayed({
            reconnectPending = false
            if (destroyed || transport !== t || t.isConnected) return@postDelayed
            if (peer != null) {
                log("reconnecting to $peer")
                t.connectToPeer(peer)
            }
            // peer == null: this phone only ever listened. The Bluetooth transport restarts its
            // accept loop by itself; Wi-Fi Direct needs the far side to re-invite, so both
            // paths are covered without a dial here.
        }, delayMs)
    }

    // ------------------------------------------------------------------ UI helpers

    private fun setStatus(s: String) {
        Log.i(TAG, s)
        FileLogger.write(s)
        val stamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
            .format(java.util.Date())
        runOnUiThread {
            statusView.text = s
            logLines.addLast("$stamp  $s")
            while (logLines.size > 14) logLines.removeFirst()
            logView.text = logLines.joinToString("\n")
        }
    }

    private fun setStatusBlocking(s: String) = runOnUiThread { setStatus(s) }

    private fun append(line: String) {
        transcriptView.append("\n$line")
    }

    /**
     * MEMORY PRESSURE. This app is required to run on low-RAM phones, and between its two
     * engines it is the largest resident thing on one (~400 MB). When the system says memory is
     * short, give some back: the TTS voice first (reloads in about a second), the ASR recogniser
     * only under RUNNING_CRITICAL (reloads in a few seconds). Never at the cost of work in
     * flight — the release helpers check recording, playback and both queues, and the locks
     * guard the native calls (see releaseIdleVoice / releaseAsrUnderPressure).
     */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            releaseIdleVoice(
                if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL) "critical"
                else "low"
            )
        }
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL) {
            releaseAsrUnderPressure()
        }
    }

    override fun onDestroy() {
        destroyed = true
        reconnectPending = false
        reconnectHandler.removeCallbacksAndMessages(null)
        deliveryHandler.removeCallbacksAndMessages(null)
        runCatching { unregisterReceiver(bluetoothStateReceiver) }
        super.onDestroy()
        stopCapture()
        transport?.stop()
        releaseModels()
        speaker.stop()
        // The queue workers exit on interrupt; anything still queued goes with the process,
        // which is what closing the app means.
        asrWorker?.interrupt()
        speechWorker?.interrupt()
    }
}
