package com.bjp.itantra

import android.Manifest
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
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.bjp.itantra.speech.AsrEngine
import com.bjp.itantra.speech.Lang
import com.bjp.itantra.speech.Speaker
import com.bjp.itantra.speech.TtsEngine
import com.bjp.itantra.speech.VadEngine
import com.bjp.itantra.transport.Transport
import com.bjp.itantra.transport.TransportKind
import com.k2fsa.sherpa.onnx.WaveReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

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

        /** How many lines the Settings log keeps in memory. */
        private const val LIVE_LOG_MAX = 400
    }

    // --- UI ---
    private lateinit var connectionView: TextView
    private lateinit var statusView: TextView
    private lateinit var transcriptView: TextView
    private lateinit var logView: TextView
    private lateinit var levelView: TextView
    private lateinit var pttButton: Button
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
    private val speaker = Speaker()

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
                        val voice = TtsEngine.voiceFor(received.lang)
                        if (voice == null) {
                            Log.i(TAG, "LOOP   no bundled voice for ${received.lang}")
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
                        val player = Speaker()
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
                        connectTransport(null)
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
            // Required to discover Wi-Fi Direct peers. Documented platform requirement.
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
        asr = AsrEngine(assets, numThreads = 2)

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
            tts = TtsEngine(this@MainActivity, voice, numThreads = 2)
        }
        true
    } catch (e: Throwable) {
        Log.e(TAG, "model load failed", e)
        FileLogger.write("FAIL: model load threw ${e::class.simpleName}: ${e.message}")
        setStatusBlocking("Model load failed: ${e.message}")
        false
    }

    /** Free every native engine. Safe to call when some are already null. */
    private fun releaseModels() {
        runCatching { asr?.release() }; asr = null
        runCatching { tts?.release() }; tts = null
        runCatching { vad?.release() }; vad = null
    }

    // ------------------------------------------------------------------ capture

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
        val engine = asr
        if (engine == null) {
            log("segment $n (${segment.size} samples) but ASR not loaded — is this phone in LISTENER mode?")
            return
        }
        log("segment $n: ${segment.size} samples = ${"%.2f".format(segment.size / 16000.0)}s -> recognising")

        thread(name = "asr") {
            val t0 = System.currentTimeMillis()
            val raw = engine.transcribe(segment)
            val ms = System.currentTimeMillis() - t0
            log("ASR ${ms}ms -> \"${raw.take(48)}\"")

            if (raw.isBlank()) {
                log("ASR returned empty — nothing to send")
                return@thread
            }

            // Pass the selected language so the filter enforces THAT script. Without it, a
            // Hindi→Gujarati script flip (common under mild noise) would pass as valid text.
            val verdict = LanguageFilter.filter(raw, langTag)
            if (!verdict.send) {
                // Name the offending characters. Without them this is unactionable: "dropped"
                // alone does not distinguish a model failure from a filter bug, and the whole
                // "hello -> هello" class of problem was invisible for exactly that reason.
                val culprits = LanguageFilter.foreignLetters(raw, langTag)
                    .map { "%s(U+%04X)".format(it, it.code) }
                    .joinToString(" ")
                log(
                    "DROPPED whole segment: ${verdict.removed} foreign char(s) " +
                        "[$culprits] fraction=${verdict.fraction}"
                )
                // Tell the sender to repeat. Silence with no explanation reads as a broken app.
                runOnUiThread { setStatus("Not heard as $langTag — hold PTT and repeat") }
                return@thread
            }

            val msg = Message(
                lang = langTag,
                type = Message.Type.MSG,
                seq = seq.getAndIncrement(),
                text = verdict.text,
            )
            val bytes = msg.toBytes()
            log("packet ${bytes.size} B -> transport")
            runOnUiThread {
                append("▸ ${verdict.text}")
                setStatus("Sent ${bytes.size} B")
            }
            transport?.send(bytes)
        }
    }

    /** Called by the transport when a packet arrives on the LISTENER phone. */
    private fun onPacket(bytes: ByteArray) {
        val msg = Message.fromBytes(bytes) ?: run {
            Log.w(TAG, "dropped malformed packet")
            return
        }
        if (!ledger.accept(msg)) {
            Log.i(TAG, "duplicate ${msg.id} — not replaying")
            return
        }

        val engine = tts
        if (engine == null) {
            runOnUiThread { append("▸ ${msg.text}   (no voice loaded)") }
            return
        }

        // A stale message must not be announced as a live alert.
        if (!ledger.isFresh(msg)) {
            runOnUiThread { append("▸ ${msg.text}   (stale, not announced)") }
            return
        }

        thread(name = "tts") {
            val samples = engine.synthesise(msg.text)
            val mode = if (msg.type == Message.Type.ALERT) Speaker.Mode.ALERT else Speaker.Mode.NORMAL
            runOnUiThread {
                append(if (mode == Speaker.Mode.ALERT) "🔴 ${msg.text}" else "▸ ${msg.text}")
                setStatus("Speaking (${msg.type})")
                speaker.play(samples, engine.sampleRate(), mode)
            }
            ledger.markPlayed(msg)
        }
    }

    // ------------------------------------------------------------------ controls

    /**
     * SETTINGS. A live, scrollable view of everything the app is doing, plus the actions that used
     * to be buried in the device picker.
     *
     * The on-screen strip is capped at 14 lines and cannot be scrolled, which is fine for glancing
     * at and useless for diagnosing. This is the version you can actually read.
     */
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
            append("\nLog file:   ${FileLogger.path() ?: "not initialised"}")
        }

        val body = android.widget.ScrollView(this).apply {
            val tv = android.widget.TextView(this@MainActivity).apply {
                text = liveLog.joinToString("\n")
                textSize = 9f
                typeface = android.graphics.Typeface.MONOSPACE
                setPadding(28, 28, 28, 28)
                setTextIsSelectable(true)
            }
            addView(tv)
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

        val t = kind.create(applicationContext).also { transport = it }
        t.onMessage = { bytes -> onPacket(bytes) }
        t.onStateChange = { state, detail ->
            runOnUiThread { setStatus("$state — $detail") }
            updateConnectionIndicator()
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
            log("also dialling the chosen device")
            t.connectToPeer(peerId)
        }
        updateConnectionIndicator()
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

    override fun onDestroy() {
        runCatching { unregisterReceiver(bluetoothStateReceiver) }
        super.onDestroy()
        stopCapture()
        transport?.stop()
        releaseModels()
        speaker.stop()
    }
}
