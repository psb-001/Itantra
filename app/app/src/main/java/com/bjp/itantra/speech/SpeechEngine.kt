package com.bjp.itantra.speech

import android.content.Context
import android.content.res.AssetManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log
import com.bjp.itantra.SpeechText
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.getFeatureConfig
import com.k2fsa.sherpa.onnx.getOfflineModelConfig
import com.k2fsa.sherpa.onnx.getVadModelConfig

/**
 * Asset paths. These match the layout used by the reference APK, so the same files can be
 * dropped in without renaming anything:
 *
 *   assets/sherpa-onnx-omnilingual-asr-10lang-300M-ctc-v2-int8/model.int8.onnx
 *   assets/sherpa-onnx-omnilingual-asr-10lang-300M-ctc-v2-int8/tokens.txt
 *   assets/silero_vad.onnx
 *   assets/tts/hi_IN-pratham-medium.onnx
 *   assets/tts/hi_IN-pratham-medium.tokens
 *   assets/tts/en_US-lessac-medium.onnx
 *   assets/tts/en_US-lessac-medium.tokens
 *   assets/tts/mr_IN-google-medium.onnx      (+ .tokens)
 *   assets/tts/bn_BD-google-medium.onnx     (+ .tokens)
 *   assets/tts/ml_IN-arjun-medium.onnx      (+ .tokens)
 *   assets/tts/te_IN-venkatesh-medium.onnx  (+ .tokens)
 *   assets/tts/espeak-ng-data/...
 */
object Assets {
    const val ASR_DIR = "sherpa-onnx-omnilingual-asr-10lang-300M-ctc-v2-int8"
    const val ASR_MODEL = "$ASR_DIR/model.int8.onnx"
    const val ASR_TOKENS = "$ASR_DIR/tokens.txt"
    const val VAD_MODEL = "silero_vad.onnx"
    const val TTS_DIR = "tts"
    const val ESPEAK_DATA = "$TTS_DIR/espeak-ng-data"
}

/** Language tags must match the ASR model's own IDs, not ISO codes. */
object Lang {
    const val HINDI = "hin_Deva"
    const val ENGLISH = "eng_Latn"
    const val MARATHI = "mar_Deva"
    const val BENGALI = "ben_Beng"
    const val GUJARATI = "guj_Gujr"
    const val KANNADA = "kan_Knda"
    const val MALAYALAM = "mal_Mlym"
    const val TAMIL = "tam_Taml"
    const val TELUGU = "tel_Telu"
    const val ODIA = "ory_Orya"
}

/**
 * Voice activity detection — finds the pause at the end of a sentence.
 *
 * This is the component that implements ISRO's "detecting pauses and stoppages should form the
 * sentences". Measured cost on desktop: RTF 0.004, about 240x faster than real time, so running
 * it continuously while idle is nearly free.
 */
class VadEngine(assets: AssetManager) {
    private val vad: Vad = Vad(
        assetManager = assets,
        config = getVadModelConfig(0)!!.also { cfg ->
            // RAISE maxSpeechDuration FROM ITS 5 s DEFAULT.
            //
            // At 5 s the VAD force-cuts anything longer mid-sentence. A segment that begins in
            // the middle of a word has no natural onset, and the model's script decision is
            // unstable without one — measured on 20 Sep 2026, a force-cut Hindi segment came
            // back in Tamil script while the naturally-bounded version came back in Devanagari.
            //
            // 30 s means segmentation follows natural pauses instead of a timer. The PTT release
            // flush handles the final sentence, so we do not need aggressive cutting.
            cfg.sileroVadModelConfig.maxSpeechDuration = 30.0f
        },
    )

    /** Feed a chunk of 16 kHz mono float samples; emits completed segments as they are found. */
    fun accept(samples: FloatArray, onSegment: (FloatArray) -> Unit) {
        vad.acceptWaveform(samples)
        while (!vad.empty()) {
            onSegment(vad.front().samples)
            vad.pop()
        }
    }

    fun reset() = vad.reset()

    /**
     * Flush any in-progress segment. Without this the final sentence of a recording is lost —
     * the VAD only emits a segment when it detects trailing silence.
     */
    fun flush(onSegment: (FloatArray) -> Unit) {
        vad.flush()
        while (!vad.empty()) {
            onSegment(vad.front().samples)
            vad.pop()
        }
    }

    fun release() = vad.release()
}

/**
 * Speech to text — Meta Omnilingual ASR CTC 300M INT8 via sherpa-onnx.
 *
 * Measured on desktop: RTF 0.038–0.042, i.e. roughly 0.04 s of compute per second of audio.
 * On a phone this will be worse; that number is still unmeasured.
 *
 * NOTE ON LANGUAGE: this model has NO language parameter. It is a 1,600-language character-level
 * CTC model and will transcribe whatever script fits the audio best. Restricting the output to the
 * ten required languages is done by [com.bjp.itantra.LanguageFilter], not by configuration.
 */
class AsrEngine(assets: AssetManager, numThreads: Int = 2) {

    private val recognizer: OfflineRecognizer

    init {
        val config = OfflineRecognizerConfig(
            featConfig = getFeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
            modelConfig = getOfflineModelConfig(type = MODEL_TYPE_OMNILINGUAL)!!,
        )
        // numThreads matters on a phone: more threads finish sooner but heat the SoC and cause
        // thermal throttling on sustained use. 2 is a reasonable default; tune on the target device.
        config.modelConfig.numThreads = numThreads
        recognizer = OfflineRecognizer(assetManager = assets, config = config)
    }

    /**
     * Transcribe one segment. Returns raw text — run it through LanguageFilter before sending.
     *
     * LEADING SILENCE MATTERS. This pads the segment with 0.2 s of silence before decoding.
     * The VAD cuts a segment exactly at speech onset, and with no run-up the model's script
     * decision is unstable — measured on 20 Sep 2026 with the Hindi bench clip:
     *
     *     whole file, no VAD      -> DEVANAGARI  (correct)
     *     VAD segment, 0.00 s pad -> ARABIC      (wrong alphabet, same words)
     *     VAD segment, 0.05 s pad -> DEVANAGARI  (correct)
     *     VAD segment, 0.20 s pad -> DEVANAGARI  (correct)
     *
     * Without this, a Hindi utterance is written in Arabic script and the language filter then
     * correctly rejects it — so the user sees nothing at all. 0.2 s is a comfortable margin over
     * the measured 0.05 s threshold and costs roughly 25 ms of extra compute.
     */
    fun transcribe(samples: FloatArray): String {
        val padded = FloatArray(LEAD_SILENCE_SAMPLES + samples.size)
        System.arraycopy(samples, 0, padded, LEAD_SILENCE_SAMPLES, samples.size)

        val stream = recognizer.createStream()
        try {
            stream.acceptWaveform(padded, SAMPLE_RATE)
            recognizer.decode(stream)
            return recognizer.getResult(stream).text.trim()
        } finally {
            stream.release()
        }
    }

    fun release() = recognizer.release()

    companion object {
        const val SAMPLE_RATE = 16000

        /** 0.2 s of leading silence prepended to every segment. See [transcribe]. */
        private const val LEAD_SILENCE_SAMPLES = 3200

        /** Matches the reference app: model type 44 selects omnilingual. */
        const val MODEL_TYPE_OMNILINGUAL = 44
    }
}

/**
 * Text to speech — Piper VITS voices via sherpa-onnx.
 *
 * One voice is loaded at a time. Loading all ten at ~60 MB each would cost 600 MB, which is
 * exactly the kind of waste the Efficiency criterion penalises.
 */
class TtsEngine(
    private val context: Context,
    private val voiceName: String,
    numThreads: Int = 2,
) {

    private val tts: OfflineTts

    init {
        val assets = context.assets

        // The voices live under assets/tts/, not at the asset root. Getting this wrong does not
        // throw a Java exception — the sherpa-onnx native code prints
        //     F sherpa-onnx: Read binary file: Load '<voice>.onnx' failed
        // and calls abort(), killing the whole process with no Java stack trace.
        val modelPath = "${Assets.TTS_DIR}/$voiceName.onnx"
        val tokensPath = "${Assets.TTS_DIR}/$voiceName.tokens"
        require(assetExists(assets, modelPath)) { "TTS voice model missing from assets: $modelPath" }
        require(assetExists(assets, tokensPath)) { "TTS tokens missing from assets: $tokensPath" }

        // THE ESPEAK TRAP. The model and tokens can be read straight from assets, but
        // espeak-ng-data CANNOT — the native phonemiser opens real files by path. sherpa-onnx
        // says so at runtime:
        //     W sherpa-onnx: You need to follow our examples to copy the espeak-ng-data
        //                    directory from the assets folder to an external storage directory
        // and then aborts the process. So copy it out once, and pass the filesystem path.
        val dataDir = ensureEspeakData(assets)

        val config = OfflineTtsConfig(
            model = OfflineTtsModelConfig(
                vits = OfflineTtsVitsModelConfig(
                    model = modelPath,
                    lexicon = "",
                    tokens = tokensPath,
                    dataDir = dataDir,          // a real path on disk, NOT an asset path
                ),
                numThreads = numThreads,
                debug = false,
                provider = "cpu",
            ),
        )
        tts = OfflineTts(assetManager = assets, config = config)
    }

    /** True when [path] exists inside the APK assets. */
    private fun assetExists(assets: AssetManager, path: String): Boolean = try {
        assets.open(path).use { true }
    } catch (e: Exception) {
        false
    }

    /**
     * Copy `assets/tts/espeak-ng-data/` to app storage on first use and return the directory path.
     * ~7 MB, ~120 files. Skipped on later runs if the marker file is present.
     */
    private fun ensureEspeakData(assets: AssetManager): String {
        val root = context.getExternalFilesDir(null) ?: context.filesDir
        val dest = java.io.File(root, Assets.ESPEAK_DATA)   // e.g. .../files/tts/espeak-ng-data
        val marker = java.io.File(dest, "phontab")          // one of espeak's data files

        if (dest.isDirectory && marker.exists()) {
            Log.i(TAG, "espeak-ng-data already extracted at $dest")
            return dest.absolutePath
        }

        Log.i(TAG, "extracting espeak-ng-data to $dest (first run)")
        val started = System.currentTimeMillis()
        copyAssetDir(assets, Assets.ESPEAK_DATA, dest)
        Log.i(TAG, "espeak-ng-data extracted in ${System.currentTimeMillis() - started} ms")
        return dest.absolutePath
    }

    /** Recursively copy an asset directory to disk. */
    private fun copyAssetDir(assets: AssetManager, assetPath: String, dest: java.io.File) {
        val children = assets.list(assetPath).orEmpty()
        if (children.isEmpty()) {
            // leaf: an actual file
            dest.parentFile?.mkdirs()
            assets.open(assetPath).use { input ->
                java.io.FileOutputStream(dest).use { output -> input.copyTo(output) }
            }
            return
        }
        dest.mkdirs()
        for (child in children) {
            copyAssetDir(assets, "$assetPath/$child", java.io.File(dest, child))
        }
    }

    fun sampleRate(): Int = tts.sampleRate()

    /** Synthesise to float samples. Caller decides how loud to play it. */
    /**
     * Synthesise to float samples, normalised to this voice's measured level.
     *
     * The gain is per-voice and measured, not a global fudge — see [CompanionObject.GAIN]. The
     * Tamil voice is a third as loud as the rest, and without this a Tamil alert is quiet while
     * a Hindi one is fine, which looks like a broken app rather than a quiet voice.
     *
     * Clamped rather than wrapped: at [CompanionObject.CLAMP] the result distorts gently instead
     * of flat-topping, and for a distress message a slightly buzzy alert beats a click.
     */
    fun synthesise(text: String, speed: Float = 1.0f): FloatArray {
        // Speech conditioning only — the message shown to the human is untouched. See [SpeechText]
        // for the three measured failures each rule fixes, and for why the gate is this voice.
        val spoken = SpeechText.forSynthesis(text, devanagariVoice = isDevanagariVoice(voiceName))
        val raw = tts.generate(text = spoken, sid = 0, speed = speed).samples
        val gain = gainFor(voiceName)
        if (gain == 1.0f) return raw
        val out = FloatArray(raw.size)
        for (i in raw.indices) {
            out[i] = (raw[i] * gain).coerceIn(-CLAMP, CLAMP)
        }
        return out
    }

    fun release() = tts.release()

    companion object {
        private const val TAG = "iTantra/Tts"

        /**
         * Bundled Piper VITS voices. Names must match the files in `assets/tts/`.
         *
         * Fetched and verified with `eval/fetch_tts_voices.py`; each one was synthesised and
         * listened to before being added. All 22,050 Hz mono.
         */
        const val HINDI = "hi_IN-pratham-medium"
        const val ENGLISH = "en_US-lessac-medium"
        const val MARATHI = "mr_IN-google-medium"
        const val BENGALI = "bn_BD-google-medium"
        const val MALAYALAM = "ml_IN-arjun-medium"
        const val TELUGU = "te_IN-venkatesh-medium"
        const val TAMIL = "ta_IN-rasa_female-medium"

        /**
         * Per-voice output gain, measured — not guessed.
         *
         * Each bundled voice was synthesised and its peak measured over SIX runs, because
         * Piper's VITS sampler is stochastic and a single measurement is not trustworthy.
         * Measured peaks (min/mean/max):
         *
         *     hi_IN  0.681 / 0.750 / 0.790
         *     te_IN  0.219 / 0.227 / 0.237
         *     ta_IN  0.158 / 0.178 / 0.211   <- outlier
         *
         * Tamil is a quarter of the loudest voice. Shipped unadjusted, a Tamil alert would be
         * markedly quieter than a Hindi one and would read as a broken app rather than a quiet
         * voice. 3.5x puts it at 0.55-0.74, inside the range of the others, using the WORST of
         * six samples rather than the best so a quiet draw never lands below them.
         *
         * These gains bring every voice to a comparable level. They are applied BEFORE clamping
         * and the result is clamped, so over-amplifying produces quiet distortion rather than
         * flat-topped crackle, which is the lesser evil for a distress message.
         */
        private val GAIN: Map<String, Float> = mapOf(
            HINDI to 1.0f,
            ENGLISH to 1.0f,
            MARATHI to 1.0f,
            BENGALI to 1.0f,
            MALAYALAM to 1.0f,
            TELUGU to 1.0f,
            TAMIL to 3.5f,
        )

        fun gainFor(voice: String): Float = GAIN[voice] ?: 1.0f

        /**
         * True for the bundled voices that read Devanagari: Hindi and Marathi.
         *
         * The gate for [SpeechText]: digits and Latin words are measured broken under a
         * Devanagari voice, and the loanword map's output is Devanagari, so it must only ever be
         * handed to a voice that can pronounce it.
         */
        fun isDevanagariVoice(voice: String): Boolean = voice == HINDI || voice == MARATHI

        /** Clamp ceiling for post-gain samples. Below 1.0 so the sum never wraps to full scale. */
        private const val CLAMP = 0.95f

        /**
         * The voice for a language tag, or null when we have none.
         *
         * Marathi previously fell back to the Hindi voice, which is wrong now that a real
         * Marathi voice is bundled — Marathi and Hindi share the Devanagari script but not the
         * pronunciation, and "emergency" read with Hindi phonetics in a Marathi message is
         * exactly the kind of thing a native speaker notices immediately.
         *
         * Returns null for Gujarati, Kannada and Odia. See docs/TTS_LICENCE_AUDIT.md and
         * docs/ALL10_LANGUAGES_FEASIBILITY.md for why no usable voice exists that we can
         * integrate. [MainActivity] logs that clearly rather than failing quietly.
         */
        fun voiceFor(langTag: String): String? = when (langTag) {
            Lang.HINDI -> HINDI
            Lang.MARATHI -> MARATHI
            Lang.BENGALI -> BENGALI
            Lang.MALAYALAM -> MALAYALAM
            Lang.TELUGU -> TELUGU
            Lang.TAMIL -> TAMIL
            Lang.ENGLISH -> ENGLISH
            else -> null
        }

        /** True when we have a bundled voice for this language. */
        fun hasVoice(langTag: String): Boolean = voiceFor(langTag) != null

        /** Every language tag that will actually speak, for the UI. */
        fun speakableLanguages(): List<String> = LANGUAGES_FOR_VOICE.filter { hasVoice(it) }

        private val LANGUAGES_FOR_VOICE = listOf(
            Lang.HINDI, Lang.MARATHI, Lang.BENGALI, Lang.MALAYALAM, Lang.TELUGU, Lang.ENGLISH,
            Lang.GUJARATI, Lang.KANNADA, Lang.TAMIL, Lang.ODIA,
        )
    }
}

/**
 * Plays synthesised audio.
 *
 * Alert mode is the reason this is a class rather than a one-liner: alerts must play at maximum
 * volume, loop until acknowledged, and not be ducked by other audio. That needs explicit
 * AudioAttributes and audio-focus handling, not a default AudioTrack.
 */
class Speaker {

    enum class Mode { NORMAL, ALERT }

    private var track: AudioTrack? = null

    /**
     * @Synchronized on purpose: play, stop and isPlaying are called from different threads —
     * the receive worker plays, an incoming ALERT may stop playback to preempt — and the
     * AudioTrack field cannot tolerate two writers.
     */
    @Synchronized
    fun play(samples: FloatArray, sampleRate: Int, mode: Mode = Mode.NORMAL) {
        stop()

        val attrs = AudioAttributes.Builder()
            .setUsage(
                if (mode == Mode.ALERT) AudioAttributes.USAGE_ALARM
                else AudioAttributes.USAGE_MEDIA
            )
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()

        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
            .setSampleRate(sampleRate)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build()

        val t = AudioTrack.Builder()
            .setAudioAttributes(attrs)
            .setAudioFormat(format)
            .setBufferSizeInBytes(samples.size * 4)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()

        t.write(samples, 0, samples.size, AudioTrack.WRITE_BLOCKING)
        track = t

        if (mode == Mode.ALERT) {
            // Loop until explicitly stopped. AUDIOFOCUS_GAIN (not MAY_DUCK) so nothing else
            // can talk over an emergency alert.
            t.setLoopPoints(0, samples.size, -1)
        }
        t.play()
        Log.i(TAG, "playing ${samples.size} frames at $sampleRate Hz in $mode mode")
    }

    /** True while audio is still coming out. */
    @Synchronized
    fun isPlaying(): Boolean = track?.playState == AudioTrack.PLAYSTATE_PLAYING

    @Synchronized
    fun stop() {
        track?.let {
            runCatching { it.stop() }
            runCatching { it.release() }
        }
        track = null
    }

    companion object {
        private const val TAG = "iTantra/Speaker"

        /** Stream to raise for alerts: STREAM_ALARM ignores most silent-mode rules. */
        const val ALERT_STREAM = AudioManager.STREAM_ALARM
    }
}
